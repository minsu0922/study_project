package project.study.study_project.llm.dto;

import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.QuestionKind;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.support.DraftCheck;

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
        /** 목록에 뜰 한 줄 제목 — 모델이 안 냈으면 {@code null}. 검수자가 그 자리에서 고칠 수 있게 함께 내린다. */
        String title,
        String question,
        String answer,
        String explanation,
        List<AdminProblemRequest.ChoiceItem> choices,
        DraftStatus status,
        String model,
        String rejectReason,
        Long approvedProblemId,
        /** 근거가 된 개념 문서의 slug — 검수자가 "무엇을 보고 낸 문제인지" 확인할 수 있게 함께 내린다. */
        String documentSlug,

        /**
         * 이 문제가 묻는 형태 — 2026-08-25. 유형을 도입하기 전 초안이면 {@code null}.
         *
         * <p>검수 화면이 배지로 보여 준다. 개수를 세는 것은 <b>화면 쪽</b>이다 — 목록에 나란히
         * 놓인 다섯 건을 보고 "상황형이 하나뿐"을 한눈에 알 수 있어야 하는데, 그건 배치 단위라
         * 항목 하나만 담는 이 DTO로는 말할 수 없다.
         */
        QuestionKind questionKind,

        /** 화면 표기용 한글 이름 — docs/02의 "enum은 영문, 표기는 한글" 규칙. {@code questionKind}가 없으면 null. */
        String questionKindLabel,

        /**
         * 자동 검증에서 걸린 항목 — 2026-08-25 신설. 없으면 빈 목록.
         *
         * <p>{@code LlmDocumentDraftResponse}에는 처음부터 있었는데 문제 쪽에는 없었다.
         * 그래서 관리 화면에서 만든 문제는 <b>아무 경고도 받지 않았다</b>(자세한 배경은
         * {@code LlmProblemService.toResponse}).
         *
         * <p><b>{@code blocked}가 없는 것이 문서 쪽과 다른 점이다.</b> 문서는 초안을 일단 저장하고
         * 승인 때 막지만, 문제는 규약 위반이면 <b>저장 자체가 안 된다</b>({@code ProblemItemRule.defectOf}).
         * 화면에 뜬 초안은 이미 차단을 통과한 것들이라 여기 담기는 것은 언제나 경고다 —
         * 없는 상태를 필드로 표현하면 "왜 늘 false지"가 남는다.
         */
        List<DraftCheck> checks,

        LocalDateTime createdAt,
        LocalDateTime reviewedAt
) {
}
