package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 제목 백필 결과 — 관리 화면이 "무엇이 어떻게 됐는지"를 그 자리에서 보여 주는 데 쓴다.
 *
 * <p><b>채운 제목을 그대로 실어 보내는 이유</b>: 이 작업은 모델이 지은 이름을 <b>사람 확인 없이</b>
 * DB에 넣는다. 건수만 알려 주면 결과를 보려고 목록을 다시 열어 한 줄씩 찾아야 하는데,
 * 그러면 대개 확인하지 않는다. 방금 붙은 제목이 눈앞에 뜨면 이상한 것이 있을 때 바로 눈에 띈다.
 *
 * @param targeted  이번에 대상으로 삼은 문제 수(한 번에 처리하는 상한까지)
 * @param filled    실제로 제목이 채워진 수. {@code targeted}보다 작으면 모델이 빠뜨린 것이 있다 —
 *                  그 문제는 제목이 여전히 NULL이라 다음 실행이 다시 집어 온다
 * @param remaining 이 작업 뒤에도 제목이 없는 문제 수. 0이 아니면 버튼을 한 번 더 누르면 된다
 * @param titles    채워진 제목들(문제 id와 짝)
 */
public record TitleBackfillResponse(
        int targeted,
        int filled,
        long remaining,
        List<Filled> titles
) {
    public record Filled(Long problemId, String title) {
    }
}
