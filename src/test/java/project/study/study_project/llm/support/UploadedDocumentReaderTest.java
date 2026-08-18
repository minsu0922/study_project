package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.llm.client.SourceDocument;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업로드 문서 읽기 테스트 — docs/13(업로드 생성 경로, 2026-08-18).
 *
 * <p><b>여기 있는 검사는 전부 돈이 걸린 검사다.</b> 빈 입력이 통과하면 모델에게 빈 문서를 주고
 * 요금을 내고, 길이 상한이 없으면 실수 한 번의 비용에 천장이 없다. 그래서 "막아야 하는 것"과
 * 함께 <b>막으면 안 되는 것</b>(정상 문서)도 같이 못 박는다 — 너무 깐깐하면 기능이 안 쓰인다.
 */
class UploadedDocumentReaderTest {

    @Test
    @DisplayName("본문 첫 H1을 제목으로 쓴다 — 파일명은 내용과 무관한 경우가 흔하다")
    void takesTitleFromFirstHeading() {
        byte[] bytes = "# 트랜잭션 격리 수준\n\n본문이다.".getBytes(StandardCharsets.UTF_8);

        SourceDocument doc = UploadedDocumentReader.fromFile(bytes, "doc(1)_final_v3.md");

        assertThat(doc.title()).isEqualTo("트랜잭션 격리 수준");
        assertThat(doc.contentMd()).contains("본문이다.");
    }

    @Test
    @DisplayName("H1이 없으면 파일명에서 확장자를 떼어 제목으로 쓴다")
    void fallsBackToFileName() {
        byte[] bytes = "제목 없이 본문만 있는 글이다.".getBytes(StandardCharsets.UTF_8);

        SourceDocument doc = UploadedDocumentReader.fromFile(bytes, "카프카 정리.md");

        assertThat(doc.title()).isEqualTo("카프카 정리");
    }

    /**
     * 브라우저·OS에 따라 파일명에 경로가 섞여 오는 경우가 있다(특히 옛 IE·일부 모바일).
     * 그대로 제목에 쓰면 {@code C:\Users\...\카프카 정리}가 프롬프트에 실린다.
     */
    @Test
    @DisplayName("파일명에 경로가 섞여 와도 마지막 조각만 제목으로 쓴다")
    void stripsPathFromFileName() {
        byte[] bytes = "본문".getBytes(StandardCharsets.UTF_8);

        assertThat(UploadedDocumentReader.fromFile(bytes, "C:\\Users\\me\\카프카 정리.md").title())
                .isEqualTo("카프카 정리");
        assertThat(UploadedDocumentReader.fromFile(bytes, "/home/me/kafka.txt").title())
                .isEqualTo("kafka");
    }

    @Test
    @DisplayName("올린 문서는 UPLOADED로 표시된다 — 프롬프트가 절 이름을 지목할지 이 값으로 갈린다")
    void marksDocumentAsUploaded() {
        SourceDocument doc = UploadedDocumentReader.fromText("아무 글이나 붙여넣었다.");

        assertThat(doc.kind()).isEqualTo(SourceDocument.Kind.UPLOADED);
    }

    /**
     * 기본 생성자는 GENERATED다. 배치·평가 CLI가 쓰는 경로라 <b>여기가 뒤집히면 배치 문제의
     * 절 지목이 통째로 사라진다</b> — 조용한 품질 저하라 테스트로 못 박는다.
     */
    @Test
    @DisplayName("생성 문서는 기본이 GENERATED다 — 기존 호출부가 절 지목을 잃지 않아야 한다")
    void generatedIsTheDefaultKind() {
        assertThat(new SourceDocument("slug", "제목", "본문").kind())
                .isEqualTo(SourceDocument.Kind.GENERATED);
    }

    @Test
    @DisplayName("빈 파일·공백만 있는 본문은 거부한다 — 빈 문서로 요금을 내면 안 된다")
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> UploadedDocumentReader.fromFile(new byte[0], "a.md"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("빈 파일");

        assertThatThrownBy(() -> UploadedDocumentReader.fromText("   \n\n  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("비어 있습니다");

        assertThatThrownBy(() -> UploadedDocumentReader.fromText(null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 상한을 넘길 때 <b>잘라서 진행하지 않고 거부하는지</b>를 본다. 잘리면 뒷부분이 사라진 것을
     * 아무도 모른 채 문제가 나오고, 검수에서도 안 걸린다(원본과 대조할 방법이 없다).
     */
    @Test
    @DisplayName("길이 상한을 넘으면 자르지 않고 거부한다 — 조용히 잘리면 아무도 모른다")
    void rejectsTooLongInsteadOfTruncating() {
        String tooLong = "가".repeat(UploadedDocumentReader.MAX_LENGTH + 1);

        assertThatThrownBy(() -> UploadedDocumentReader.fromText(tooLong))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("너무 깁니다");
    }

    @Test
    @DisplayName("상한과 정확히 같은 길이는 통과한다 — 경계에서 멀쩡한 문서를 막으면 안 된다")
    void acceptsExactlyMaxLength() {
        String atLimit = "가".repeat(UploadedDocumentReader.MAX_LENGTH);

        assertThat(UploadedDocumentReader.fromText(atLimit).contentMd()).hasSize(UploadedDocumentReader.MAX_LENGTH);
    }

    /**
     * <b>윈도우 메모장이 저장한 CP949 파일</b>이 이 서비스에서 가장 흔한 사고다. UTF-8로 읽으면
     * 예외가 아니라 깨진 글자가 나오므로, 감지해서 막지 않으면 <b>깨진 문서로 문제를 만든다</b>.
     */
    @Test
    @DisplayName("UTF-8이 아닌 파일은 거부한다 — 깨진 채로 문제를 만드는 것이 최악이다")
    void rejectsNonUtf8File() {
        Charset cp949 = Charset.forName("x-windows-949");
        byte[] bytes = "# 트랜잭션 격리 수준\n\n본문이다.".getBytes(cp949);

        assertThatThrownBy(() -> UploadedDocumentReader.fromFile(bytes, "메모장.md"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    @DisplayName("영문만 있는 파일은 인코딩이 달라도 통과한다 — 오탐으로 정상 문서를 막지 않는다")
    void doesNotFlagAsciiOnlyContent() {
        byte[] bytes = "# Transaction Isolation\n\nplain ascii body.".getBytes(Charset.forName("x-windows-949"));

        assertThat(UploadedDocumentReader.fromFile(bytes, "doc.md").title()).isEqualTo("Transaction Isolation");
    }
}
