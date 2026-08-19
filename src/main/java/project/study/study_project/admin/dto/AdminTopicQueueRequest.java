package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.study_project.global.common.Domain;

/**
 * 주제 대기열에 한 줄 추가하는 요청.
 *
 * <p><b>분야가 필수인 이유</b>는 업로드 생성({@code LlmDocumentGenerateRequest})과 같다.
 * 자동으로 골라 주면 편할 것 같지만, 문서의 분야는 그 문서로 만드는 <b>사흘치 문제의
 * 분야까지</b> 정하므로 어긋나면 나흘이 통째로 엉킨다. 주제를 적는 사람은 그게 어느 분야인지
 * 이미 알고 있으니 고르는 비용도 없다.
 *
 * @param topic 주제. 분야보다 좁게 적을수록 좋다 — 이 대기열이 생긴 이유가
 *              "분야만 던지면 개괄 문서가 나온다"였다
 * @param memo  왜 이 주제인지(선택). 배치는 읽지 않는다
 */
public record AdminTopicQueueRequest(

        @NotNull(message = "분야를 선택해 주세요.")
        Domain domain,

        @NotBlank(message = "주제를 입력해 주세요.")
        @Size(max = 200, message = "주제는 200자를 넘을 수 없습니다.")
        String topic,

        @Size(max = 500, message = "메모는 500자를 넘을 수 없습니다.")
        String memo
) {
}
