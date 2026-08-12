package project.study.study_project.llm.support;

/**
 * 문서 초안 검증 결과 한 줄 — {@link DocumentDraftValidator}가 만들고 검수 화면이 그대로 보여 준다.
 *
 * <p><b>왜 boolean이 아니라 목록인가.</b> "통과/실패"만 돌려주면 검수자는 무엇이 문제인지
 * 모른 채 문서 전체를 다시 읽어야 한다. 실제로 걸리는 것들(제목 불일치, 절 누락, 분량 초과)은
 * 전부 <b>어디가 왜</b>를 짚어 줄 수 있는 종류라, 판정과 함께 문장을 돌려주는 편이 훨씬 쓸모 있다.
 *
 * @param severity {@link Severity#BLOCKING}이면 승인 자체가 막히고, {@link Severity#WARNING}은
 *                 화면에 표시만 되고 승인은 가능하다
 * @param message  검수자가 읽을 한국어 설명 — 그대로 화면에 뿌린다
 */
public record DocumentCheck(Severity severity, String message) {

    /**
     * 심각도.
     *
     * <p><b>두 단계로 나눈 이유.</b> 전부 반려로 처리하면 분량이 조금 넘쳤다는 이유로 잘 쓴 문서가
     * 버려진다(실측: 1편 2,645자/지시 2,000자, 2편 4,578자/지시 3,500자 — 둘 다 30% 넘게
     * 초과했고, 프롬프트 문구로는 못 잡는다는 것이 두 편으로 확인됐다).
     * 반대로 전부 경고로 두면 필수 절이 통째로 빠진 문서도 승인 버튼 한 번에 정식 문서가 된다.
     * <b>사람이 판단할 수 있는 것은 경고, 판단해 봐야 답이 하나인 것은 차단</b>이 기준이다.
     */
    public enum Severity {
        /** 승인 불가. 고치려면 문서를 다시 뽑아야 하는 결함(필수 절 누락, HTML 태그, 형식 위반). */
        BLOCKING,
        /** 승인은 가능하되 검수자가 보고 판단할 것(제목 불일치, 권장 분량 초과). */
        WARNING
    }

    public static DocumentCheck blocking(String message) {
        return new DocumentCheck(Severity.BLOCKING, message);
    }

    public static DocumentCheck warning(String message) {
        return new DocumentCheck(Severity.WARNING, message);
    }

    public boolean isBlocking() {
        return severity == Severity.BLOCKING;
    }
}
