package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminLookup;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.repository.ProblemRepository;

/**
 * 상단 띠의 "번호로 이동" — 번호 하나로 문제와 문제 초안을 함께 찾는다({@link AdminLookup} 주석).
 * 읽기만 하고 두 저장소를 한 번씩 보는 것이 전부라 서비스 층을 따로 두지 않았다.
 */
@RestController
@RequiredArgsConstructor
public class AdminLookupController {

    private final ProblemRepository problemRepository;
    private final GeneratedProblemDraftRepository draftRepository;

    @GetMapping("/api/admin/lookup")
    @Transactional(readOnly = true)
    public ApiResponse<AdminLookup> lookup(@RequestParam long id) {
        AdminLookup.Problem problem = problemRepository.findById(id)
                .map(p -> new AdminLookup.Problem(p.getId(), p.getTitle()))
                .orElse(null);
        AdminLookup.Draft draft = draftRepository.findById(id)
                .map(d -> new AdminLookup.Draft(d.getId(), d.getStatus().name(), d.getCodeKind() != null, d.getTitle()))
                .orElse(null);
        return ApiResponse.ok(new AdminLookup(problem, draft));
    }
}
