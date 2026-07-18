package project.study.study_project.llm.dto;

import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.DraftStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 검수 화면용 초안 응답. 보기(choices)는 엔티티의 JSON 문자열이 아니라
 * <b>파싱된 목록</b>으로 내려준다 — 프론트가 JSON 안의 JSON을 또 파싱하게 하지 않는다.
 * (JSON 파싱은 서비스가 담당하므로 이 DTO에는 from(entity) 팩터리를 두지 않았다 —
 * ObjectMapper 없이 만들 수 없기 때문. 조립은 LlmProblemService.toResponse 참고)
 *
 * @param domainLabel 화면 표기용 한글 이름 — docs/02의 "enum은 영문, 표기는 한글" 규칙
 */
public record LlmDraftResponse(
        Long id,
        Domain domain,
        String domainLabel,
        Difficulty difficulty,
        ProblemType type,
        String question,
        String answer,
        String explanation,
        List<AdminProblemRequest.ChoiceItem> choices,
        DraftStatus status,
        String model,
        String rejectReason,
        Long approvedProblemId,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt
) {
}
