package project.study.study_project.llm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

/**
 * 관리자 수동 생성 요청 바디.
 *
 * @param domain     분야 — <b>null이면 "가장 부족한 칸 자동 선택"</b>(difficulty와 함께 결정됨, docs/13)
 * @param difficulty 난이도 — domain과 같은 규칙
 * @param type       유형 — null이면 객관식. 서술형은 서비스에서 거부(QUIZ_002)
 * @param count      생성 개수 — 상한 10: 호출당 비용·응답 시간을 예측 가능하게 묶는다.
 *                   더 필요하면 여러 번 누르면 된다(한 방에 많이보다 실패 단위가 작은 쪽이 낫다)
 */
public record LlmGenerateRequest(
        Domain domain,
        Difficulty difficulty,
        ProblemType type,

        @Min(value = 1, message = "count는 1 이상이어야 합니다.")
        @Max(value = 10, message = "count는 10 이하여야 합니다.")
        int count
) {
}
