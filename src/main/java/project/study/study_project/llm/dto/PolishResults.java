package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 배치 파일의 윤문 결과 칸(2026-11-26). 검수함이 그 편이 윤문을 거쳤는지, 어디를 원고로 뒀는지 보여 준다.
 * 전에는 이 결과가 Actions 요약에만 남아, 검수할 때 원고 그대로 나간 편을 알아볼 수 없었다.
 *
 * @param beginner 입문편 결과
 * @param advanced 심화편 결과. 심화편이 없으면 {@code null}
 */
public record PolishResults(Edition beginner, Edition advanced) {

    /**
     * @param applied 윤문본을 한 덩어리라도 썼는지
     * @param reason  편 전체를 원고로 둔 이유. 썼으면 {@code null}
     * @param kept    윤문본을 썼지만 원고로 둔 덩어리마다 사유와 자리. 없으면 빈 목록
     */
    public record Edition(boolean applied, String reason, List<String> kept) {
    }
}
