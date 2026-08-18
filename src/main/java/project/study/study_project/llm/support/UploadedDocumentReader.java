package project.study.study_project.llm.support;

import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.SourceDocument;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 관리자가 올린 문서를 문제 생성의 근거({@link SourceDocument})로 바꾼다 — 2026-08-18 신설.
 *
 * <p><b>왜 이 변환에 클래스를 하나 쓰는가.</b> 하는 일은 "바이트를 문자열로 바꾸고 제목을
 * 뽑는 것"뿐이라 컨트롤러에 인라인으로 써도 된다. 그렇게 하지 않은 이유는 <b>여기 있는 검사가
 * 전부 돈이 걸린 검사</b>이기 때문이다 — 빈 파일이 그대로 통과하면 모델에게 빈 문서를 주고
 * 요금을 내며, 상한이 없으면 실수로 올린 큰 파일이 한 번에 크게 나간다. 컨트롤러에 흩어 두면
 * 나중에 "붙여넣기 경로에는 상한이 없는" 식으로 갈라진다. 입구가 둘(파일·텍스트)이라
 * <b>합류 지점을 하나 만들어 두는 것</b>이 이 클래스의 존재 이유다.
 *
 * <p><b>static 유틸인 이유</b>: 입력만으로 결과가 정해지는 순수 함수라 주입받을 상태가 없다.
 * {@code DocumentDraftValidator}와 같은 판단이다.
 */
public final class UploadedDocumentReader {

    /**
     * 본문 길이 상한.
     *
     * <p><b>왜 20,000자인가.</b> 우리 개념 문서가 편당 6,000자 안팎이니 세 편 분량이다.
     * 입력 토큰은 싸지만($5/1M) 상한이 없으면 실수 한 번의 비용에 천장이 없다 —
     * 200쪽 PDF를 텍스트로 붙여넣는 사고는 실제로 일어난다.
     *
     * <p>상한을 넘길 때 <b>잘라서 진행하지 않고 거부하는</b> 이유: 잘리면 뒷부분이 사라진 것을
     * 아무도 모른 채 "이 문서로 만든 문제"가 나온다. 조용한 실패라 검수에서도 안 걸린다.
     * 사람이 문서를 나눠 올리게 하는 편이 낫다.
     */
    public static final int MAX_LENGTH = 20_000;

    /** 파일 이름에서 확장자를 떼는 패턴 — 제목 후보로 쓸 때만 필요하다. */
    private static final Pattern EXTENSION = Pattern.compile("\\.[^.]{1,10}$");

    /** 본문 첫 번째 {@code # H1}. 제목의 1순위 후보. */
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");

    /** 제목을 어디서도 못 찾았을 때. 프롬프트에 빈 제목이 실리는 것보다 낫다. */
    private static final String FALLBACK_TITLE = "업로드 문서";

    private UploadedDocumentReader() {
    }

    /**
     * 업로드 바이트를 근거 문서로 바꾼다.
     *
     * <p><b>인코딩을 UTF-8로 못 박은 이유</b>: 윈도우 메모장이 저장한 CP949 파일을 UTF-8로 읽으면
     * 예외가 아니라 <b>깨진 글자</b>가 나온다. 자동 감지를 붙이는 대안도 있었지만, 라이브러리를
     * 새로 들이는 값에 비해 얻는 것이 적다 — 지금 다루는 문서는 대부분 마크다운이고 UTF-8이다.
     * 대신 아래에서 깨짐을 <b>감지해 거부</b>한다. 조용히 깨진 문서로 문제를 만드는 것이 최악이다.
     *
     * @param bytes        업로드된 파일 내용. 붙여넣기 경로는 {@link #fromText}를 쓴다
     * @param originalName 원본 파일 이름. 본문에 {@code # H1}이 없을 때 제목으로 쓴다
     */
    public static SourceDocument fromFile(byte[] bytes, String originalName) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "빈 파일입니다. 내용이 있는 문서를 올려 주세요.");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        // U+FFFD(대체 문자)는 UTF-8이 아닌 바이트를 UTF-8로 읽었다는 신호다. 한 글자만 나와도
        // 문서 전체가 CP949일 가능성이 크므로 거부한다 — 깨진 채로 문제를 만드는 것보다 낫다.
        if (text.indexOf('�') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "UTF-8로 읽을 수 없는 파일입니다. 편집기에서 UTF-8로 다시 저장한 뒤 올려 주세요.");
        }
        return build(text, titleFromFileName(originalName));
    }

    /** 붙여넣기 경로. 파일명이 없으므로 제목은 본문에서만 찾는다. */
    public static SourceDocument fromText(String text) {
        return build(text == null ? "" : text, null);
    }

    /* ── 내부 ────────────────────────────────────────────────── */

    private static SourceDocument build(String rawText, String fileNameTitle) {
        String text = rawText.strip();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "내용이 비어 있습니다.");
        }
        if (text.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "문서가 너무 깁니다: %,d자 (상한 %,d자). 나눠서 올려 주세요.".formatted(text.length(), MAX_LENGTH));
        }

        String title = resolveTitle(text, fileNameTitle);
        // slug는 프롬프트에도 초안에도 쓰지 않는다. record가 요구하는 자리라 넣을 뿐이고,
        // 진짜 이유는 저장 단계에서 documentSlug를 null로 넣기 때문이다(LlmProblemService 주석).
        // 여기에 그럴듯한 slug를 만들어 두면 나중에 누군가 그걸 저장에 갖다 쓰고, 학습자 화면에
        // 존재하지 않는 문서로 가는 링크가 생긴다. 눈에 띄는 값을 넣어 오용을 막는다.
        return new SourceDocument("uploaded", title, text, SourceDocument.Kind.UPLOADED);
    }

    /**
     * 제목: 본문 {@code # H1} → 파일명 → 고정 문구 순.
     *
     * <p>H1을 먼저 보는 이유: 파일명은 {@code doc(1)_final_v3.md}처럼 내용과 무관한 경우가 흔한데,
     * 이 제목은 프롬프트에 "제목: ○○"으로 실려 <b>모델이 주제를 파악하는 첫 단서</b>가 된다.
     */
    private static String resolveTitle(String text, String fileNameTitle) {
        Matcher m = H1.matcher(text);
        if (m.find()) {
            String heading = m.group(1).strip();
            if (!heading.isEmpty()) {
                return heading;
            }
        }
        return fileNameTitle != null && !fileNameTitle.isBlank() ? fileNameTitle : FALLBACK_TITLE;
    }

    /** 파일명에서 확장자를 뗀 것. 경로가 섞여 와도 마지막 조각만 쓴다(브라우저·OS마다 다르다). */
    private static String titleFromFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return null;
        }
        String name = originalName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return EXTENSION.matcher(name).replaceFirst("").strip();
    }
}
