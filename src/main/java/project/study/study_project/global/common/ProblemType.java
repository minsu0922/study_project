package project.study.study_project.global.common;

/**
 * 문제 유형 — 문서 02-domain-enums 기준.
 * <p>자동채점 대상: {@link #MULTIPLE_CHOICE}, {@link #OX}, {@link #SHORT_ANSWER},
 * {@link #MATCHING}, {@link #ORDERING}. {@link #ESSAY}는 enum으로만 유지하고 채점/시드에서는 제외한다.
 *
 * <h2>2026-08-31 — 짝짓기·순서 배열 추가</h2>
 *
 * <p><b>유형을 늘리는 기준은 "입력 방식이 다른가"다.</b> 오류 찾기(잘못된 줄 고르기)·출력 예측·
 * 사례 고르기 같은 형태는 결국 보기 넷 중 하나를 누르거나 단어를 치는 것이라 유형이 아니라
 * {@code QuestionKind}(무엇을 묻는가) 쪽 축에 속한다. 새 로직이 필요한 것은 학습자가
 * <b>연결하거나 배열하는</b> 두 가지뿐이었다.
 *
 * <p><b>왜 이 둘을 골랐나 — 찍기 확률.</b> OX는 1/2, 객관식은 1/4다. 짝짓기와 순서 배열은
 * 네 항목 기준 1/24라 "몰라도 눌러서 맞는" 경로가 사실상 닫힌다. 게다가 객관식은 보기를
 * 보고 소거하는 요령이 통하는데, 이 둘은 백지에서 관계를 떠올려야 한다.
 *
 * <p><b>타입별 {@code answer} 규약</b>(docs/01, {@code Problem} 클래스 주석):
 * <ul>
 *   <li>{@link #MATCHING}: {@code null}. 정답이 {@code choice} 행 자체에 있다(text ↔ match_text)
 *   <li>{@link #ORDERING}: 정답 순서의 seq 나열(예 {@code "3|2|1|4"}). 순서는 행 <b>사이의</b>
 *       관계라 어느 한 행에도 담기지 않는다
 * </ul>
 */
public enum ProblemType {
    MULTIPLE_CHOICE("객관식", true),
    OX("OX", true),
    SHORT_ANSWER("단답형", true),
    MATCHING("짝짓기", true),
    ORDERING("순서 배열", true),
    ESSAY("서술형", false);

    private final String displayName;
    private final boolean autoScored;

    ProblemType(String displayName, boolean autoScored) {
        this.displayName = displayName;
        this.autoScored = autoScored;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** MVP 자동채점 지원 여부. false면 채점 요청 시 QUIZ_002. */
    public boolean isAutoScored() {
        return autoScored;
    }

    /**
     * 이 유형이 {@code choice} 행을 쓰는가 — 객관식의 보기, 짝짓기의 왼쪽 열, 순서 배열의 항목.
     *
     * <p><b>왜 enum에 두는가.</b> 이 판단이 필요한 곳이 넷이다(풀이용 DTO·복습 DTO·관리자 검증·
     * 초안 승인). 예전에는 {@code type == MULTIPLE_CHOICE} 한 줄이라 각자 적어도 문제가 없었는데,
     * 유형이 셋으로 늘면 그 조건이 길어지고 <b>한 곳만 빠뜨리는</b> 사고가 생긴다. 실제로 그 자리는
     * 조용하다 — 보기가 안 그려질 뿐 에러가 나지 않는다.
     *
     * <p>화면 표기가 아니라 <b>데이터 구조에 관한 사실</b>이라 enum에 둘 자격이 있다.
     * "보기를 섞을 것인가", "몇 개를 그릴 것인가" 같은 표현 규칙은 여전히 DTO 쪽 몫이다.
     */
    public boolean usesChoiceRows() {
        return this == MULTIPLE_CHOICE || this == MATCHING || this == ORDERING;
    }
}
