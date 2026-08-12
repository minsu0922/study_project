package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.ExistingQuestionsFile;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 기존 지문 스냅샷 내보내기 테스트 — docs/14.
 *
 * <p>이 러너는 <b>없어서 사고가 났던</b> 기능이다. 그동안 이 파일은 손으로 만들어져 아무도
 * 갱신하지 않았고, 시드 마이그레이션을 걷어낼 때 이미 삭제된 문제 24건이 그대로 남아 있는 것이
 * 드러났다 — 없는 문제를 피하라고 하면 그 주제로 새 문제를 영영 못 만든다.
 *
 * <p>형제들과 같은 규칙을 지킨다: <b>내용이 같으면 다시 쓰지 않는다</b>. 이게 깨지면 앱을 켤
 * 때마다 파일이 바뀌어 "커밋할 게 항상 있는" 상태가 되고, 정작 진짜 새 문제가 생긴 날을
 * 알아볼 수 없게 된다.
 */
@ExtendWith(MockitoExtension.class)
class ExistingQuestionsExporterTest {

    @Mock
    private ProblemRepository problemRepository;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExistingQuestionsExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new ExistingQuestionsExporter(problemRepository, objectMapper);
    }

    @Test
    @DisplayName("지문을 분야와 함께 내보낸다 — 분야가 없으면 CLI가 그날 분야만 골라낼 수 없다")
    void writesQuestionsWithDomain() throws Exception {
        given(row(Domain.NETWORK, "TCP 3-way handshake의 순서는?"),
                row(Domain.OS, "페이지 폴트가 발생하는 시점은?"));

        assertThat(exporter.export(tempDir)).isTrue();

        ExistingQuestionsFile snapshot = read();
        assertThat(snapshot.questions()).hasSize(2);
        assertThat(snapshot.questions().get(0).domain()).isEqualTo(Domain.NETWORK);
        assertThat(snapshot.questions().get(0).question()).isEqualTo("TCP 3-way handshake의 순서는?");
    }

    /**
     * 상한이 없으면 문제가 쌓일수록 저장소에 커밋되는 파일이 무한정 커진다.
     * 한 분야가 폭주해도 다른 분야의 자리를 빼앗지 않도록 <b>분야별</b>로 자른다.
     */
    @Test
    @DisplayName("분야별로 상한까지만 자른다 — 한 분야가 파일을 독차지하지 않는다")
    void limitsPerDomain() throws Exception {
        List<ProblemRepository.DomainQuestion> rows = new ArrayList<>();
        for (int i = 0; i < ExistingQuestionsExporter.PER_DOMAIN_LIMIT + 20; i++) {
            rows.add(row(Domain.NETWORK, "네트워크 문제 " + i));
        }
        rows.add(row(Domain.OS, "운영체제 문제"));
        given(rows.toArray(new ProblemRepository.DomainQuestion[0]));

        exporter.export(tempDir);

        List<ExistingQuestionsFile.Item> items = read().questions();
        assertThat(items).hasSize(ExistingQuestionsExporter.PER_DOMAIN_LIMIT + 1);
        assertThat(items).filteredOn(i -> i.domain() == Domain.NETWORK)
                .hasSize(ExistingQuestionsExporter.PER_DOMAIN_LIMIT);
        assertThat(items).filteredOn(i -> i.domain() == Domain.OS)
                .as("상한에 걸린 분야 때문에 다른 분야가 밀려나면 안 된다").hasSize(1);
    }

    @Test
    @DisplayName("조회 순서(최신순)를 그대로 유지한다 — 다시 정렬하면 같은 내용도 바뀐 것처럼 보인다")
    void keepsQueryOrder() throws Exception {
        given(row(Domain.NETWORK, "최신"), row(Domain.OS, "중간"), row(Domain.NETWORK, "오래됨"));

        exporter.export(tempDir);

        assertThat(read().questions()).extracting(ExistingQuestionsFile.Item::question)
                .containsExactly("최신", "중간", "오래됨");
    }

    @Test
    @DisplayName("내용이 같으면 다시 쓰지 않는다 — 앱을 켤 때마다 git이 변경으로 보면 안 된다")
    void doesNotRewriteWhenUnchanged() throws Exception {
        given(row(Domain.NETWORK, "질문"));

        assertThat(exporter.export(tempDir)).as("처음에는 쓴다").isTrue();
        String first = Files.readString(tempDir.resolve(ExistingQuestionsExporter.FILE_NAME));

        assertThat(exporter.export(tempDir)).as("두 번째는 쓰지 않는다").isFalse();
        assertThat(Files.readString(tempDir.resolve(ExistingQuestionsExporter.FILE_NAME)))
                .isEqualTo(first);
    }

    @Test
    @DisplayName("문제가 새로 승인되면 다시 쓴다")
    void rewritesWhenQuestionsChanged() throws Exception {
        given(row(Domain.NETWORK, "질문1"));
        exporter.export(tempDir);

        given(row(Domain.NETWORK, "질문2"), row(Domain.NETWORK, "질문1"));

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(read().questions()).hasSize(2);
    }

    /**
     * DB를 초기화하면 정식 문제가 0건이 되는데, 그때 <b>옛 목록이 파일에 남아 있으면</b>
     * 배치가 존재하지도 않는 문제를 피하려 든다. 실제로 시드를 걷어낼 때 이 상태였다 —
     * 그래서 "0건이면 아무것도 안 한다"가 아니라 "0건으로 갱신한다"가 맞다.
     */
    @Test
    @DisplayName("문제가 0건이 되면 기존 파일을 빈 목록으로 갱신한다 — 없는 문제를 피하게 두면 안 된다")
    void clearsFileWhenNoProblemsRemain() throws Exception {
        given(row(Domain.NETWORK, "곧 삭제될 문제"));
        exporter.export(tempDir);

        given(); // 문제 전부 삭제됨

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(read().questions()).isEmpty();
    }

    @Test
    @DisplayName("문제가 0건이고 파일도 없으면 만들지 않는다 — 커밋할 것도 없는데 새 파일만 생긴다")
    void doesNotCreateFileWhenNothingExists() throws Exception {
        given();

        assertThat(exporter.export(tempDir)).isFalse();
        assertThat(tempDir.resolve(ExistingQuestionsExporter.FILE_NAME)).doesNotExist();
    }

    @Test
    @DisplayName("기존 파일이 깨져 있으면 새로 쓴다 — 손으로 편집하다 깨뜨려도 복구된다")
    void rewritesWhenExistingFileIsBroken() throws Exception {
        Files.writeString(tempDir.resolve(ExistingQuestionsExporter.FILE_NAME), "{ 깨진 JSON");
        given(row(Domain.NETWORK, "질문"));

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(read().questions()).hasSize(1);
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private void given(ProblemRepository.DomainQuestion... rows) {
        when(problemRepository.findAllDomainQuestions()).thenReturn(List.of(rows));
    }

    /** 프로젝션 인터페이스의 가벼운 구현 — Mockito mock보다 읽기 쉽다. */
    private ProblemRepository.DomainQuestion row(Domain domain, String question) {
        return new ProblemRepository.DomainQuestion() {
            @Override
            public Domain getDomain() {
                return domain;
            }

            @Override
            public String getQuestion() {
                return question;
            }
        };
    }

    private ExistingQuestionsFile read() throws Exception {
        return objectMapper.readValue(
                tempDir.resolve(ExistingQuestionsExporter.FILE_NAME).toFile(), ExistingQuestionsFile.class);
    }
}
