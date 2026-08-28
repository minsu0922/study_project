package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 오답 설명 채우기 결과 — 관리 화면이 "무엇이 어떻게 됐는지"를 그 자리에서 보여 주는 데 쓴다(V15).
 *
 * <p><b>채운 설명을 그대로 실어 보내는 이유</b>는 {@link TitleBackfillResponse}와 같다.
 * 이 작업은 모델이 쓴 글을 <b>사람 확인 없이</b> DB에 넣는다. 건수만 알려 주면 결과를 보려고
 * 목록을 다시 열어 문제를 하나씩 찾아야 하는데, 그러면 대개 확인하지 않는다.
 *
 * <p>여기서는 한 걸음 더 간다 — <b>보기 원문을 함께 싣는다</b>. 설명만 죽 늘어놓으면
 * "그럴듯한 문장 서른 개"로 보여서 눈이 미끄러진다. 어느 보기에 붙은 말인지가 옆에 있어야
 * 짝이 어긋난 것을 알아볼 수 있고, 짝이 어긋나는 것이 이 작업의 유일한 사고 유형이다
 * ({@code GeneratedRationale} 주석).
 *
 * @param targeted            이번에 대상으로 삼은 문제 수(한 번에 처리하는 상한까지)
 * @param filled              실제로 설명이 채워진 <b>보기</b> 수. 단위가 문제가 아니라 보기인 것에 주의 —
 *                            문제 하나에 오답이 셋이면 3이 오른다
 * @param remaining           이 작업 뒤에도 설명이 빠진 문제 수. 0이 아니면 버튼을 한 번 더 누르면 된다
 * @param rationales          채워진 설명들(보기 원문과 짝)
 * @param explanationsToCheck 해설이 오답 보기를 <b>인용하는</b> 문제 id들. 여기 실린 문제는
 *                            같은 말이 해설과 오답 설명 양쪽에 있게 되므로 사람이 해설을 다듬어야 한다.
 *                            <b>자동으로 고치지 않는다</b> — 해설을 덮어쓰는 것은 되돌릴 수 없고,
 *                            되풀이는 읽기에 거슬릴 뿐 틀린 내용이 아니다
 */
public record RationaleBackfillResponse(
        int targeted,
        int filled,
        long remaining,
        List<Filled> rationales,
        List<Long> explanationsToCheck
) {
    /**
     * 채워진 설명 한 줄.
     *
     * @param choiceText 설명이 붙은 보기의 원문. 짝이 맞는지를 눈으로 확인하는 유일한 수단이다
     */
    public record Filled(Long problemId, Long choiceId, String choiceText, String rationale) {
    }
}
