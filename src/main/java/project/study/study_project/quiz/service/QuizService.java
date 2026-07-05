package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizProblemItem;
import project.study.study_project.quiz.dto.QuizResponse;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.List;

/**
 * 퀴즈 조회 서비스 — 필터(도메인·난이도·유형)에 맞는 문제를 무작위로 N개 뽑는다. 스펙은 docs/03.
 *
 * <p>{@code @Transactional(readOnly = true)}인 이유는 문서 서비스와 동일:
 * open-in-view=false라 LAZY 컬렉션(객관식 보기)은 트랜잭션 안에서만 읽을 수 있으므로,
 * DTO 변환까지 트랜잭션 경계 안에서 끝낸다.
 */
@Service
@RequiredArgsConstructor
public class QuizService {

    /** 스펙(docs/03)의 size 기본값/상한. 컨트롤러가 아닌 여기 두는 이유: 정책은 서비스 책임. */
    public static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    private final ProblemRepository problemRepository;

    /**
     * 필터로 문제 N개 무작위 조회(풀이용 — 정답/해설 미포함).
     *
     * @param type ESSAY(서술형)를 요청하면 {@link ErrorCode#QUIZ_002}(400).
     *             서술형은 MVP 자동채점 대상이 아니라 풀 수 없는 문제를 내려주면 안 되기 때문.
     * @param size 요청 개수. 1~50으로 보정(clamp) — 범위 밖이면 에러 대신 조용히 경계값으로 맞춘다.
     *             조회 API에서 "51개 요청"은 악의보다 실수에 가까워, 굳이 400으로 튕겨
     *             클라이언트 재시도를 강제할 이유가 없다고 판단(트레이드오프: 명시성 ↓, 편의성 ↑).
     */
    @Transactional(readOnly = true)
    public QuizResponse getQuiz(Domain domain, Difficulty level, ProblemType type, int size) {
        if (type != null && !type.isAutoScored()) {
            throw new BusinessException(ErrorCode.QUIZ_002);
        }
        int limit = Math.min(Math.max(size, 1), MAX_SIZE);

        // 네이티브 쿼리는 enum을 자동 변환하지 못하므로 name() 문자열로 넘긴다(리포지토리 주석 참고).
        List<Problem> problems = problemRepository.findRandomForQuiz(
                domain == null ? null : domain.name(),
                level == null ? null : level.name(),
                type == null ? null : type.name(),
                limit
        );
        return new QuizResponse(problems.stream().map(QuizProblemItem::from).toList());
    }
}
