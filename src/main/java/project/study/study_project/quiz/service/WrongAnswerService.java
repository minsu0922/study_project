package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.repository.SubmissionRepository;

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
        return PageResponse.from(page.map(this::toItem));
    }

    /** Submission → 표시용 DTO. 답 표기 변환(choiceId → 보기 text 등)은 AnswerDisplay 규칙을 쓴다. */
    private WrongAnswerItem toItem(Submission s) {
        Problem p = s.getProblem();
        return new WrongAnswerItem(
                p.getId(),
                p.getDomain(),
                p.getDifficulty(),
                p.getType(),
                p.getQuestion(),
                AnswerDisplay.userAnswerOf(p, s.getUserAnswer()),
                AnswerDisplay.correctAnswerOf(p),
                p.getExplanation(),
                s.getSubmittedAt()
        );
    }
}
