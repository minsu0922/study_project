package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Claude 구조화 출력(Structured Output)용 응답 스키마 — 문서 13.
 *
 * <p>이 record가 곧 "붕어빵 틀"이다: SDK가 record 정의에서 JSON 스키마를 자동 생성해
 * API에 보내고, 모델은 <b>이 스키마에 맞는 JSON만</b> 반환하도록 강제된다.
 * 그래서 "응답 형식이 매번 달라 파싱이 깨지는" 문제가 원천적으로 없다.
 *
 * <p>스키마 제약상 모든 필드가 필수(required)라 "값 없음"을 null 대신 빈 값으로 받는다:
 * 객관식의 answer는 빈 문자열 {@code ""}, OX/단답형의 choices는 빈 배열 {@code []},
 * 근거 문서 없이 만든 날의 sourceQuote는 빈 문자열 {@code ""}.
 * (구조화 출력은 nullable 필드 표현이 제한적이라, 프롬프트 규칙 + 서비스에서 정규화하는
 * 쪽이 스키마를 비트는 것보다 단순하다.) null 변환은 LlmProblemService가 담당.
 *
 * <p>{@code @JsonPropertyDescription}은 사람용 주석이 아니라 <b>모델에게 전달되는 필드 설명</b>이다
 * — 스키마에 description으로 포함되어 모델이 각 필드를 올바르게 채우도록 유도한다.
 */
@JsonClassDescription("CS 학습 퀴즈 문제 목록")
public record GeneratedProblemItem(

        @JsonPropertyDescription("문제 지문. 한국어. 코드가 필요하면 지문 안에 포함")
        String question,

        @JsonPropertyDescription("채점 기준값. 객관식이면 빈 문자열, OX면 O 또는 X, 단답형이면 정답(복수 정답은 |로 구분)")
        String answer,

        @JsonPropertyDescription("해설. <왜 정답인지>의 근거만 쓴다. 오답이 왜 틀렸는지는 "
                + "각 보기의 rationale에 적으므로 여기서 되풀이하지 않는다")
        String explanation,

        @JsonPropertyDescription("객관식 보기 목록. 객관식이면 정확히 4개(정답 1개), 그 외 유형이면 빈 배열")
        List<GeneratedChoice> choices,

        @JsonPropertyDescription("이 문제의 근거가 된 문서 원문 한 줄. 문서에서 그대로 복사해 옮긴다"
                + "(요약·수정 금지). 근거 문서 없이 만든 문제면 빈 문자열")
        String sourceQuote,

        @JsonPropertyDescription("문제 목록에 뜰 한 줄 제목. 무엇에 관한 문제인지를 명사구로 적는다"
                + "(예: TIME_WAIT가 쌓여 포트가 마르는 이유). 상황 서술이나 물음표로 끝나는 문장이 아니다. "
                + "40자 이내")
        String title,

        @JsonPropertyDescription("이 문제가 묻는 형태. SITUATION=실무 장면을 주고 원인·판단을 묻는다, "
                + "COMPARISON=두 방식을 나란히 놓고 무엇이 가르는지 묻는다, "
                + "CAUSE=왜 그렇게 하는가를 직접 묻는다, "
                + "JUDGMENT=진술 넷 중 옳거나 틀린 것을 고른다, "
                + "SEQUENCE=단계가 있는 동작의 순서를 묻는다")
        QuestionKind questionKind
) {
    /**
     * 인용 없이 만드는 편의 생성자 — 인용을 도입하기 전 코드와 테스트가 그대로 컴파일되게 한다.
     *
     * <p>{@link SourceDocument}가 {@code Kind}를 덧붙일 때 쓴 것과 같은 처방이다. 새 필드를
     * 뒤에 붙이고 짧은 생성자를 남기면, 이 필드와 상관없는 곳(OX·단답형 테스트, 규약 검증
     * 테스트)이 전부 손대지 않아도 된다 — 손대야 할 곳이 많을수록 정작 봐야 할 변경이 묻힌다.
     */
    public GeneratedProblemItem(String question, String answer, String explanation,
                                List<GeneratedChoice> choices) {
        this(question, answer, explanation, choices, "");
    }

    /**
     * 제목 없이 만드는 편의 생성자 — 위와 같은 이유로 남긴다(2026-08-21에 {@code title} 추가).
     *
     * <p><b>{@code title}을 맨 뒤에 붙인 것은 호환 때문만이 아니다.</b> record 필드 순서가 곧
     * 구조화 출력 스키마의 속성 순서이고, 모델은 그 순서대로 값을 쓴다. 지문·보기·해설을
     * 다 쓴 뒤에 제목을 붙이면 <b>자기가 방금 만든 문제를 보고</b> 이름을 짓는다.
     * 앞에 두면 제목을 먼저 정하고 거기에 맞춰 문제를 쓰게 되는데, 그건 순서가 거꾸로다.
     */
    public GeneratedProblemItem(String question, String answer, String explanation,
                                List<GeneratedChoice> choices, String sourceQuote) {
        this(question, answer, explanation, choices, sourceQuote, "");
    }

    /**
     * 유형 없이 만드는 편의 생성자 — 위 둘과 같은 이유로 남긴다(2026-08-25에 {@code questionKind} 추가).
     *
     * <p><b>{@code title}보다도 뒤에 붙인 이유</b>는 앞의 두 필드와 같다 — record 필드 순서가 곧
     * 구조화 출력 스키마의 속성 순서이고 모델은 그 순서대로 쓴다. 유형을 맨 앞에 두면
     * <b>형태를 먼저 정하고 거기 맞춰 문제를 쓰게</b> 되는데, 그러면 "이 재료로 무엇을 물을까"가
     * 아니라 "비교형을 써야 하니 비교할 것을 찾자"가 된다. 문서에 없는 대조를 지어내는 지름길이다.
     * 지문·보기·해설을 다 쓴 뒤에 <b>자기가 방금 만든 문제를 보고</b> 형태를 적게 한다.
     *
     * <p>{@code null}이 되는 경우: 이 생성자를 쓴 테스트, 그리고 유형을 도입하기 <b>전에</b>
     * 만들어져 이미 저장된 초안. 검사 쪽은 {@code null}을 "선언 안 함"으로 보고 조용히 넘어간다 —
     * 옛 초안이 갑자기 경고를 달고 나오면 검수자가 경고를 안 보게 된다.
     */
    public GeneratedProblemItem(String question, String answer, String explanation,
                                List<GeneratedChoice> choices, String sourceQuote, String title) {
        this(question, answer, explanation, choices, sourceQuote, title, null);
    }

    /**
     * 객관식 보기 한 개.
     *
     * <p><b>{@code rationale}이 맨 뒤인 것은 이 record에서도 순서가 곧 생성 순서이기 때문이다.</b>
     * 모델은 보기 문장을 쓰고 → 정답인지 정한 뒤 → 왜 틀렸는지를 적는다. 설명을 앞에 두면
     * "이런 오해를 담아야지"를 먼저 정하고 거기 맞춰 보기를 쓰게 되는데, 그러면 지문의
     * 조건과 무관한 오해가 들어온다. 바깥 record가 {@code title}·{@code questionKind}를
     * 맨 뒤에 붙인 것과 같은 판단이다.
     */
    public record GeneratedChoice(
            @JsonPropertyDescription("보기 내용")
            String text,

            @JsonPropertyDescription("이 보기가 정답이면 true. 문제당 정확히 1개만 true")
            boolean correct,

            @JsonPropertyDescription("이 보기가 <오답일 때만> 왜 틀렸는지 한 줄로 적는다"
                    + "(어떤 오해에서 비롯되는지를 밝힌다). 정답 보기면 빈 문자열 — "
                    + "정답의 근거는 explanation이 맡는다. 보기를 번호로 가리키지 마라")
            String rationale
    ) {
        /**
         * 설명 없이 만드는 편의 생성자 — 이 필드가 생기기 전 테스트가 그대로 컴파일되게 한다.
         * 바깥 record의 짧은 생성자들과 같은 이유다.
         */
        public GeneratedChoice(String text, boolean correct) {
            this(text, correct, "");
        }
    }

    /** 구조화 출력의 최상위 스키마 — 문제 배열을 감싸는 봉투(최상위는 객체여야 해서 필요). */
    public record Batch(
            @JsonPropertyDescription("생성된 문제 목록")
            List<GeneratedProblemItem> problems
    ) {
    }
}
