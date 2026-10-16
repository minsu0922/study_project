package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminLookup;
import project.study.study_project.global.common.SearchKeyword;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 상단 띠의 통합 검색({@link AdminLookup} 주석). 읽기만 하고 저장소를 한 번씩 보는 것이 전부라
 * 서비스 층을 따로 두지 않았다.
 */
@RestController
@RequiredArgsConstructor
public class AdminLookupController {

    /** 종류마다 이만큼만. 띠 아래 상자에 다 보여야 하고, 더 많으면 각 목록 화면에서 찾는 편이 낫다 */
    static final int PER_KIND = 5;
    private static final Pattern NUMBER = Pattern.compile("#?\\d{1,18}");

    private final ProblemRepository problemRepository;
    private final GeneratedProblemDraftRepository draftRepository;
    private final UserRepository userRepository;

    @GetMapping("/api/admin/lookup")
    @Transactional(readOnly = true)
    public ApiResponse<AdminLookup> lookup(@RequestParam String q) {
        String word = q.strip();
        if (NUMBER.matcher(word).matches()) {
            long id = Long.parseLong(word.replace("#", ""));
            return ApiResponse.ok(new AdminLookup(
                    problemRepository.findById(id).map(AdminLookupController::toRow).stream().toList(),
                    draftRepository.findById(id).map(AdminLookupController::toRow).stream().toList(),
                    List.of()));
        }
        String pattern = SearchKeyword.likePattern(word);
        if (pattern == null) {
            return ApiResponse.ok(new AdminLookup(List.of(), List.of(), List.of()));
        }
        PageRequest top = PageRequest.of(0, PER_KIND);
        return ApiResponse.ok(new AdminLookup(
                problemRepository.searchForAdmin(pattern, top).stream().map(AdminLookupController::toRow).toList(),
                draftRepository.searchForAdmin(pattern, top).stream().map(AdminLookupController::toRow).toList(),
                userRepository.search(pattern, false, LocalDateTime.now(), top).stream()
                        .map(u -> new AdminLookup.User(u.getId(), u.getUsername(), u.getNickname())).toList()));
    }

    private static AdminLookup.Problem toRow(Problem p) {
        return new AdminLookup.Problem(p.getId(), label(p.getTitle(), p.getQuestion()));
    }

    private static AdminLookup.Draft toRow(GeneratedProblemDraft d) {
        return new AdminLookup.Draft(d.getId(), d.getStatus().name(), d.getCodeKind() != null,
                label(d.getTitle(), d.getQuestion()));
    }

    /** 제목이 빈 문제가 많아, 없으면 지문 앞부분을 보인다. 지문으로 찾았을 때 왜 걸렸는지도 보인다 */
    static String label(String title, String question) {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (question == null) {
            return null;
        }
        String flat = question.strip().replaceAll("\\s+", " ");
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }
}
