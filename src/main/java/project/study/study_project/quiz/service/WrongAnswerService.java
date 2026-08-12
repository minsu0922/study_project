package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.repository.SubmissionRepository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 오답노트 서비스 — 내 오답을 문제당 최신 1건으로 집계해 반환. 스펙은 docs/03, 설계는 ADR-0002.
 *
 * <p>별도 오답 테이블 없이 Submission을 조회로 유도한다(파생 뷰). "다시 풀어서 맞힌 문제"도
 * 과거에 틀린 적이 있으면 목록에 남는다 — MVP는 "틀린 적 있는 문제 = 복습 대상"으로 취급하고,
 * 복습 완료 처리 같은 상태 관리는 로드맵 4(ReviewItem 분리)의 몫이다.
 */
@Service
@RequiredArgsConstructor
public class WrongAnswerService {

    private final SubmissionRepository submissionRepository;
    /** 근거 문서 링크의 실재 확인용(docs/15 3단계). */
    private final DocumentRepository documentRepository;

    /**
     * 오답노트 페이지 조회.
     *
     * <p>{@code @Transactional(readOnly = true)}: Problem은 fetch join으로 함께 오지만,
     * 객관식 답 표기 변환이 보기(choices — LAZY 컬렉션)에 접근하므로 트랜잭션 경계 안에서
     * DTO 조립까지 끝낸다. 보기 로딩의 N+1은 default_batch_fetch_size로 완화(퀴즈 조회와 동일).
     *
     * @param userId JWT에서 꺼낸 사용자 id — 남의 오답노트는 구조적으로 조회 불가
     * @param domain 도메인 필터(선택)
     */
    @Transactional(readOnly = true)
    public PageResponse<WrongAnswerItem> getWrongAnswers(Long userId, Domain domain, Pageable pageable) {
        Page<Submission> page = submissionRepository.findLatestWrongAnswers(userId, domain, pageable);
        // 이 페이지에 실제로 존재하는 근거 문서 slug를 <한 번에> 조회해 둔다(아래 메서드 주석).
        Set<String> existingSlugs = findExistingDocumentSlugs(page.getContent());
        return PageResponse.from(page.map(s -> toItem(s, existingSlugs)));
    }

    /**
     * 이 페이지의 문제들이 가리키는 근거 문서 중 <b>실제로 있는 것</b>의 slug 집합.
     *
     * <p><b>왜 미리 한 번에 조회하나.</b> 항목마다 {@code existsBySlug}를 부르면 한 화면(20건)에
     * 쿼리가 20번 나간다 — 전형적인 N+1이다. slug를 모아 {@code IN} 한 방으로 묻고 집합으로
     * 만들어 두면 변환 단계는 메모리 조회만 한다.
     *
     * <p>slug를 가진 문제가 하나도 없으면 <b>조회 자체를 하지 않는다</b>. 지금은 대부분의 문제가
     * 근거 문서 없이 만들어진 옛 문제라 이 경우가 흔하고, 게다가 빈 목록으로 {@code IN ()}을
     * 만들면 문법 오류가 난다.
     */
    private Set<String> findExistingDocumentSlugs(List<Submission> submissions) {
        Set<String> slugs = submissions.stream()
                .map(s -> s.getProblem().getDocumentSlug())
                .filter(slug -> slug != null && !slug.isBlank())
                .collect(Collectors.toSet());
        if (slugs.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(documentRepository.findExistingSlugs(slugs));
    }

    /** Submission → 표시용 DTO. 답 표기 변환(choiceId → 보기 text 등)은 AnswerDisplay 규칙을 쓴다. */
    private WrongAnswerItem toItem(Submission s, Set<String> existingSlugs) {
        Problem p = s.getProblem();
        String slug = p.getDocumentSlug();
        return new WrongAnswerItem(
                p.getId(),
                p.getDomain(),
                p.getDifficulty(),
                p.getType(),
                p.getQuestion(),
                AnswerDisplay.userAnswerOf(p, s.getUserAnswer()),
                AnswerDisplay.correctAnswerOf(p),
                p.getExplanation(),
                s.getSubmittedAt(),
                // 존재하지 않는 문서를 가리키면 null — 화면이 죽은 링크를 걸지 않게 한다
                existingSlugs.contains(slug) ? slug : null
        );
    }
}
