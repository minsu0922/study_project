package project.study.study_project.llm.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 일괄 승인 요청 바디 — 검수함에서 체크한 초안 ID들.
 *
 * <p><b>왜 ID 목록을 받나(= "지금 검수 대기인 것 전부 승인"이 아니라)</b>:
 * 서버가 "PENDING 전부"를 스스로 골라 승인하면, 화면을 연 뒤 배치가 새로 만들어 넣은 초안까지
 * <b>사람이 읽지도 않은 채</b> 정식 문제가 된다. 검수는 "사람이 봤다"가 전부인 작업이라
 * 본 것만 골라 보내게 한다. 화면에서 체크한 것 = 눈으로 지나간 것.
 *
 * @param ids 승인할 초안 ID. 상한 50은 {@code LlmGenerateRequest.count}의 상한 10과 같은 이유 —
 *            실패 단위를 예측 가능하게 묶는다. 여기서는 이유가 하나 더 있다: 승인 한 건마다
 *            트랜잭션이 커밋되고 그때마다 스냅샷 내보내기가 깨어난다
 *            ({@link project.study.study_project.llm.service.ReviewCompleted}).
 *            수백 건을 한 요청에 넣으면 파일 쓰기가 그만큼 반복돼 응답이 하염없이 늘어진다.
 *            한 번 검수에 50건을 넘길 일도 실제로는 없다(일일 배치가 하루 5~10건을 만든다).
 */
public record LlmBulkApproveRequest(

        @NotEmpty(message = "승인할 초안을 하나 이상 선택해 주세요.")
        @Size(max = 50, message = "한 번에 최대 50건까지 승인할 수 있습니다.")
        List<Long> ids
) {
}
