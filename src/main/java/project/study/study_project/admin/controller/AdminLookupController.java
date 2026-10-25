package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminLookup;
import project.study.study_project.admin.dto.AdminLookup.Section;
import project.study.study_project.admin.service.AdminUserService;
import project.study.study_project.document.service.AdminDocumentService;
import project.study.study_project.global.common.SearchKeyword;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.AdminProblemService;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 상단 띠의 통합 검색({@link AdminLookup} 주석).
 *
 * <p>검색 조건을 따로 두지 않는다. 문제·문서·사용자는 각 목록 화면이 부르는 서비스를 그대로 불러
 * 첫 몇 줄만 받는다. 그래서 띠에서 본 결과와 "목록에서 모두 보기"로 간 결과가 늘 같다.
 * 초안만 저장소를 직접 부른다. 검수함은 상태를 하나씩 보는데 여기서는 승인 아닌 둘을 함께 봐야 해서다.
 */
@RestController
@RequiredArgsConstructor
public class AdminLookupController {

    /** 종류마다 이만큼만. 띠 아래 상자에 다 보여야 하고, 더 많으면 각 목록 화면에서 보는 편이 낫다 */
    static final int PER_KIND = 5;
    private static final Pattern NUMBER = Pattern.compile("#?\\d{1,18}");
    /**
     * 승인된 초안은 뺀다. 같은 제목의 문제가 따로 있어 결과에 두 번 뜨고, 고치려면 문제 쪽을 고쳐야 한다.
     */
    private static final List<DraftStatus> UNAPPROVED = List.of(DraftStatus.PENDING, DraftStatus.REJECTED);

    private final ProblemRepository problemRepository;
    private final GeneratedProblemDraftRepository draftRepository;
    private final AdminProblemService adminProblemService;
    private final AdminDocumentService adminDocumentService;
    private final AdminUserService adminUserService;

    @GetMapping("/api/admin/lookup")
    @Transactional(readOnly = true)
    public ApiResponse<AdminLookup> lookup(@RequestParam String q) {
        String word = q.strip();
        if (NUMBER.matcher(word).matches()) {
            long id = Long.parseLong(word.replace("#", ""));
            return ApiResponse.ok(new AdminLookup(
                    single(problemRepository.findById(id).map(AdminLookupController::toRow).stream().toList()),
                    single(draftRepository.findById(id).map(AdminLookupController::toRow).stream().toList()),
                    Section.empty(), Section.empty()));
        }
        String pattern = SearchKeyword.likePattern(word);
        if (pattern == null) {
            return ApiResponse.ok(new AdminLookup(Section.empty(), Section.empty(), Section.empty(), Section.empty()));
        }
        PageRequest top = PageRequest.of(0, PER_KIND);
        Page<GeneratedProblemDraft> drafts = draftRepository.findForReview(UNAPPROVED, null, null, null, null, null,
                pattern, null, PageRequest.of(0, PER_KIND, Sort.by(Sort.Direction.DESC, "id")));
        return ApiResponse.ok(new AdminLookup(
                section(adminProblemService.getProblems(null, null, null, null, word, top),
                        p -> new AdminLookup.Problem(p.id(), label(p.title(), p.question()))),
                new Section<>(drafts.stream().map(AdminLookupController::toRow).toList(), drafts.getTotalElements()),
                section(adminDocumentService.list(word, top),
                        d -> new AdminLookup.Document(d.id(), d.title(), d.hidden())),
                section(adminUserService.search(word, false, top),
                        u -> new AdminLookup.User(u.id(), u.username(), u.nickname()))));
    }

    private static <T> Section<T> single(List<T> items) {
        return new Section<>(items, items.size());
    }

    private static <S, T> Section<T> section(PageResponse<S> page, Function<S, T> toRow) {
        return new Section<>(page.content().stream().map(toRow).toList(), page.totalElements());
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
