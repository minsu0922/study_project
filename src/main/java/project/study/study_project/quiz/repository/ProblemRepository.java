package project.study.study_project.quiz.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Problem 저장소.
 *
 * <p>퀴즈 조회는 <b>네이티브 SQL + {@code ORDER BY RAND()}</b>를 쓴다. 이유와 트레이드오프:
 * <ul>
 *   <li><b>왜 랜덤인가</b>: 매번 같은 문제가 같은 순서로 나오면 "문제를 푸는" 게 아니라
 *       "순서를 외우는" 게 된다. 퀴즈 특성상 무작위 추출이 기본값으로 맞다.
 *   <li><b>왜 네이티브인가</b>: JPQL 표준에는 랜덤 정렬이 없다. 문서 조회처럼 Specification을
 *       쓸 수도 있지만 Sort로 RAND()를 표현할 수 없어, 필터+랜덤+LIMIT을 한 번에 처리하려면
 *       네이티브가 가장 단순하다.
 *   <li><b>ORDER BY RAND()의 비용</b>: 조건에 걸린 전체 행에 난수를 붙여 정렬하므로 O(N log N).
 *       문제 수가 수십만 건이 되면 느려진다 — 그때는 "랜덤 id 샘플링 후 IN 조회" 같은 기법으로
 *       바꾼다(로드맵 1에서 인덱스·쿼리 최적화와 함께 측정). MVP의 수백 문제 규모에선 충분히 빠르다.
 *   <li><b>동적 필터</b>: {@code (:x IS NULL OR col = :x)} 패턴 — 파라미터가 없으면 조건 자체가
 *       항상 참이 되어 무시된다. 필터가 3개뿐이라 이 정도 반복은 Specification 도입보다 싸다.
 *   <li><b>ESSAY 제외 고정</b>: 서술형은 MVP 채점 대상이 아니라서(문서 03) 필터와 무관하게
 *       퀴즈에 나오면 안 된다 → WHERE에 상수로 박아 실수 여지를 없앤다.
 * </ul>
 * enum 파라미터는 네이티브 쿼리라 자동 변환이 안 되므로 서비스에서 {@code name()} 문자열로 넘긴다.
 */
public interface ProblemRepository extends JpaRepository<Problem, Long> {

    @Query(value = """
            SELECT * FROM problem p
            WHERE (:domain     IS NULL OR p.domain     = :domain)
              AND (:difficulty IS NULL OR p.difficulty = :difficulty)
              AND (:type       IS NULL OR p.type       = :type)
              AND p.type <> 'ESSAY'
            ORDER BY RAND()
            LIMIT :size
            """, nativeQuery = true)
    List<Problem> findRandomForQuiz(
            @Param("domain") String domain,
            @Param("difficulty") String difficulty,
            @Param("type") String type,
            @Param("size") int size
    );

    /**
     * 관리자용 문제 목록 — 최신 등록 순, 분야·난이도·유형·근거 문서 필터(전부 선택).
     * 퀴즈 조회와 달리 랜덤이 아니고(관리 화면은 예측 가능한 순서가 편함),
     * ESSAY 제외도 없다(관리자는 전부 봐야 함). JPQL이라 enum 파라미터를 그대로 받는다.
     *
     * <p><b>난이도와 근거 문서를 2026-08-29에 더했다.</b> 그전에는 분야·유형만 있었고
     * 그마저 화면이 쓰지 않아, 문제 76개를 네 쪽에 걸쳐 눈으로 훑는 수밖에 없었다.
     * 실제로 자주 하는 질문은 "이 문서로 만든 문제가 몇 개고 난이도가 고른가"인데
     * 그 둘이 없으면 답할 수 없다.
     */
    @Query("""
            select p from Problem p
            where (:domain is null or p.domain = :domain)
              and (:difficulty is null or p.difficulty = :difficulty)
              and (:type is null or p.type = :type)
              and (:documentSlug is null or p.documentSlug = :documentSlug)
            order by p.id desc
            """)
    Page<Problem> findForAdmin(
            @Param("domain") Domain domain,
            @Param("difficulty") Difficulty difficulty,
            @Param("type") ProblemType type,
            @Param("documentSlug") String documentSlug,
            Pageable pageable
    );

    /**
     * 오늘의 퀴즈 "새 문제 칸"(docs/12): 이 사용자가 <b>한 번도 제출한 적 없는</b> 문제에서 무작위 추출.
     *
     * <p>"안 푼 문제"는 NOT EXISTS 상관 서브쿼리로 판정한다 — "내 제출 전체를 IN으로 넘기는"
     * 방식은 이력이 쌓일수록 파라미터가 무한히 커지지만, NOT EXISTS는 제출 테이블 쪽
     * 인덱스(user_id 선두)를 타고 문제당 존재 확인 한 번으로 끝난다.
     *
     * @param excludeIds 같은 세트에 이미 뽑힌 문제 id들(중복 방지). <b>빈 리스트를 넘기면
     *                   {@code NOT IN ()}이 SQL 문법 오류</b>이므로, 호출부(DailyQuizService)가
     *                   비었을 때 존재하지 않는 id(-1) 하나를 채워 넘기는 규약이다.
     */
    @Query(value = """
            SELECT * FROM problem p
            WHERE p.type <> 'ESSAY'
              AND p.id NOT IN (:excludeIds)
              AND NOT EXISTS (SELECT 1 FROM submission s
                              WHERE s.user_id = :userId AND s.problem_id = p.id)
            ORDER BY RAND()
            LIMIT :size
            """, nativeQuery = true)
    List<Problem> findRandomUnsolved(
            @Param("userId") Long userId,
            @Param("excludeIds") List<Long> excludeIds,
            @Param("size") int size
    );

    /**
     * 오늘의 퀴즈 "취약 칸"(domain 지정)과 "채움 칸"(domain=null) 공용 무작위 추출(docs/12).
     * 동적 필터 {@code (:x IS NULL OR ...)}와 excludeIds 규약은 위 쿼리들과 동일.
     */
    @Query(value = """
            SELECT * FROM problem p
            WHERE p.type <> 'ESSAY'
              AND (:domain IS NULL OR p.domain = :domain)
              AND p.id NOT IN (:excludeIds)
            ORDER BY RAND()
            LIMIT :size
            """, nativeQuery = true)
    List<Problem> findRandomExcluding(
            @Param("domain") String domain,
            @Param("excludeIds") List<Long> excludeIds,
            @Param("size") int size
    );

    /** 대시보드 현황판: 도메인×난이도별 문제 수. 인터페이스 프로젝션(별칭→getter 매핑)으로 받는다. */
    @Query("""
            select p.domain as domain, p.difficulty as difficulty, count(p) as cnt
            from Problem p
            group by p.domain, p.difficulty
            """)
    List<DomainDifficultyCount> countGroupByDomainAndDifficulty();

    /** {@link #countGroupByDomainAndDifficulty} 결과 행 — select 별칭과 getter 이름이 매핑 규약이다. */
    interface DomainDifficultyCount {
        Domain getDomain();
        Difficulty getDifficulty();
        long getCnt();
    }

    /**
     * LLM 문제 생성(docs/13)의 중복 회피용 — 해당 도메인의 최신 질문 텍스트만 뽑는다.
     * 엔티티 전체가 아니라 question 컬럼만 프로젝션하는 이유: 프롬프트에 넣을 문자열만
     * 필요한데 Problem을 통째로 로딩하면 TEXT 본문 + 보기(LAZY) 부담만 커진다.
     * 개수 제한은 호출부가 Pageable(예: 상위 50건)로 건다 — JPQL엔 LIMIT이 없다.
     */
    @Query("select p.question from Problem p where p.domain = :domain order by p.id desc")
    List<String> findQuestionTextsByDomain(@Param("domain") Domain domain, Pageable pageable);

    /**
     * 관리 화면의 근거 문서 필터에 채울 slug 목록 — <b>문제에 실제로 붙어 있는</b> 것만.
     *
     * <p>등록 문서 목록을 그대로 쓰지 않는 이유가 둘이다. 하나는 문제가 한 건도 없는 문서를
     * 고르면 빈 목록이 나온다는 것. 다른 하나는 <b>둘이 어긋날 수 있다</b>는 것이다 —
     * 문제의 slug는 연관관계가 아니라 문자열이라(V9 주석), 문서 쪽 slug를 고친 뒤에도
     * 옛 값을 가리키는 문제가 남는다. 그런 문제를 찾으려면 문제 쪽에서 세는 수밖에 없다.
     */
    @Query("""
            select distinct p.documentSlug from Problem p
            where p.documentSlug is not null
            order by p.documentSlug
            """)
    List<String> findDistinctDocumentSlugs();

    /**
     * 분야 + 지문만 최신순으로 — 클라우드 배치가 읽을 중복 회피 스냅샷을 만드는 데 쓴다(docs/14).
     *
     * <p>{@link #findQuestionTextsByDomain}과 달리 <b>분야를 함께</b> 뽑고 전 분야를 한 번에
     * 가져온다. 스냅샷은 어느 분야가 뽑힐지 모르는 상태에서 미리 만들어 두는 것이라
     * 분야별로 8번 조회하는 대신 한 번에 읽고 메모리에서 나눈다(부팅당 1회, 수백 건 규모).
     *
     * <p>{@code order by p.id desc}가 중요하다 — 내보내기는 <b>내용이 같으면 파일을 다시 쓰지
     * 않는데</b>, 순서가 흔들리면 같은 목록도 매번 바뀐 것으로 보여 앱을 켤 때마다 git이
     * 변경으로 인식한다. id 역순이면 "새로 승인된 문제가 앞에" 오는 의미까지 맞는다.
     */
    @Query("select p.domain as domain, p.question as question from Problem p order by p.id desc")
    List<DomainQuestion> findAllDomainQuestions();

    /** 스냅샷용 프로젝션 — 엔티티를 통째로 읽지 않기 위해(본문 TEXT·보기 LAZY 부담 회피). */
    interface DomainQuestion {
        Domain getDomain();

        String getQuestion();
    }

    /**
     * 목록 제목이 아직 없는 문제 — 제목 백필(V13)이 채울 대상.
     *
     * <p><b>엔티티로 읽는다.</b> 위 프로젝션들과 다른 선택인데, 여기는 읽고 끝이 아니라
     * <b>제목을 써 넣어야</b> 하기 때문이다. 프로젝션으로 읽으면 영속 상태가 아니라
     * 변경 감지가 안 걸리고, 결국 id로 다시 조회하게 된다. 보기(LAZY)는 건드리지 않으므로
     * 로딩 부담도 지문 TEXT뿐이다.
     *
     * <p>{@code order by p.id}로 오래된 것부터 — 여러 번 나눠 부를 때 같은 순서를 보장한다.
     * 채워진 것은 다음 호출의 조건에서 저절로 빠지므로 페이지 번호를 들고 다닐 필요가 없다.
     */
    @Query("select p from Problem p where p.title is null order by p.id")
    List<Problem> findWithoutTitle(Pageable pageable);

    /** 관리 화면 배지·백필 버튼 안내용 — 제목이 없는 문제가 몇 건 남았는가. */
    long countByTitleIsNull();

    /**
     * 오답 설명이 빠진 객관식 문제 — 오답 설명 채우기(V15)가 채울 대상.
     *
     * <p>{@link #findWithoutTitle}과 같은 이유로 <b>엔티티</b>로 읽는다: 읽고 끝이 아니라
     * 값을 써 넣어야 하므로 영속 상태여야 변경 감지가 걸린다.
     *
     * <p><b>"하나라도 빈 것"을 대상으로 삼는다</b>(전부 빈 것이 아니라). 세 오답 중 둘만 채워진
     * 문제가 생길 수 있기 때문이다 — 모델이 한 건을 빠뜨리면 정확히 그 상태가 된다. 전부 빈 것만
     * 집어 오면 그런 문제는 <b>영원히 절반인 채로</b> 남고, 학습자 화면에는 보기마다 설명이
     * 있다 없다 한다. 이미 채워진 보기는 {@code Choice.fillRationaleIfAbsent}가 건너뛰므로
     * 대상을 넓게 잡아도 덮어쓸 걱정이 없다.
     *
     * <p>{@code distinct}가 필요한 이유: 오답 셋이 다 비어 있으면 조인 결과가 세 줄이 되어
     * 같은 문제가 세 번 실린다. 그러면 상한 10건이 실제로는 3~4문제가 된다.
     *
     * <p>객관식만 본다. OX·단답형에는 보기 행 자체가 없어 채울 자리가 없다.
     *
     * <p>{@code order by p.id}로 오래된 것부터 — 여러 번 나눠 부를 때 같은 순서를 보장한다.
     * 채워진 것은 다음 호출의 조건에서 저절로 빠지므로 페이지 번호를 들고 다닐 필요가 없다.
     */
    @Query("""
            select distinct p from Problem p
            join p.choices c
            where p.type = project.study.study_project.global.common.ProblemType.MULTIPLE_CHOICE
              and c.correct = false
              and c.rationale is null
            order by p.id
            """)
    List<Problem> findWithMissingRationale(Pageable pageable);

    /** 관리 화면 카드 안내용 — 오답 설명이 빠진 문제가 몇 건 남았는가(조건은 위 쿼리와 같다). */
    @Query("""
            select count(distinct p) from Problem p
            join p.choices c
            where p.type = project.study.study_project.global.common.ProblemType.MULTIPLE_CHOICE
              and c.correct = false
              and c.rationale is null
            """)
    long countWithMissingRationale();

    /* ── 학습자 문제 목록(docs/18) ─────────────────────────────── */

    /**
     * 문제 목록 화면 한 판 — <b>로그인 사용자 기준으로 개인화</b>된 프로젝션(2026-08-29).
     *
     * <h2>왜 상관 서브쿼리 넷인가</h2>
     *
     * <p>한 줄에 필요한 것이 문제 자체(제목·분야·난이도·유형)와 <b>그 사용자와의 관계</b>
     * (맞힌 적 있나 · 시도한 적 있나 · 마지막이 언제인가 · 복습할 때인가)다. 관계 쪽을 조인으로
     * 붙이면 제출이 여럿인 문제가 여러 줄이 되어 {@code distinct}와 페이징이 부딪힌다
     * (오답 설명 조회에서 이미 겪은 함정이다 — 그쪽은 상한 10건이라 티가 났지만
     * 여기는 페이지 수가 조용히 틀어진다). 서브쿼리는 문제당 정확히 한 줄을 보장한다.
     *
     * <p>비용은 문제당 인덱스 조회 네 번인데, 한 판이 20줄이라 80번이다.
     * {@code submission(user_id, problem_id)}과 {@code review_item(user_id, status, next_review_at)}
     * 인덱스가 그대로 받는다.
     *
     * <h2>정렬을 "미풀이 우선"으로 하지 않은 이유</h2>
     *
     * <p>첫 스펙은 <b>미풀이 우선 → 도메인 → 난이도</b>였다. 그러면 한 문제를 풀고 목록으로
     * 돌아올 때마다 그 줄이 뒤로 밀려 <b>목록 전체가 한 칸 당겨진다</b> — 무한 스크롤을 뺀
     * 이유(위치를 잃는다)와 똑같은 일이 정렬 쪽에서 생긴다(docs/18 §2.4).
     *
     * <p>그런데 "미풀이 우선"이 하려던 일은 <b>상태 필터가 이미 한다</b>(전체/안 푼/틀린/복습).
     * 정렬을 도메인 → 난이도 → id로 고정하면 무엇을 풀든 줄이 제자리에 있고, "안 푼 것만
     * 보고 싶다"는 필터 칩 한 번이면 된다. 같은 목적에 장치가 둘일 필요가 없다.
     *
     * <h2>난이도를 CASE로 정렬하는 이유</h2>
     *
     * <p>{@code order by p.difficulty}로 두면 <b>고급이 맨 앞에 온다</b>. 난이도가
     * {@code EnumType.STRING}이라 DB에는 글자로 저장되고, 정렬도 글자순
     * (ADVANCED → BEGINNER → INTERMEDIATE)이 되기 때문이다. 화면을 띄워 보고 알았다 —
     * 컴파일도 되고 테스트도 통과하며, 다만 초급을 찾는 사람이 고급 열 줄을 지나쳐야 한다.
     *
     * <p>선언 순서(초·중·고)를 쓰려면 {@code ORDINAL}로 저장하는 방법도 있지만, 그러면
     * enum에 값을 끼워 넣는 순간 기존 데이터가 통째로 밀린다. 저장 형식은 글자로 두고
     * <b>정렬에서만</b> 순서를 매긴다.
     *
     * <p>분야는 글자순 그대로 둔다. 사이드바 목록 순서와는 다르지만, 분야는 <b>고르는 것</b>이라
     * 목록 안에서의 순서가 판단에 쓰이지 않는다. CASE를 열두 줄 더 쓸 값어치가 없다.
     *
     * @param userId    로그인 사용자. 이 화면은 인증 필수라 null이 오지 않는다
     * @param state     {@code null}이면 상태를 가리지 않는다. 값은 {@code SolveState} 이름
     *                  ({@code CORRECT}/{@code WRONG}/{@code UNSOLVED})
     * @param onlyDue   {@code true}면 지금 복습 차례인 문제만
     * @param now       복습 차례 판정 기준 시각. 호출부가 넘긴다 — 쿼리 안에서 현재 시각을
     *                  읽으면 같은 요청 안에서도 값이 흔들려 테스트가 불가능해진다
     */
    @Query("""
            select p.id as id, p.title as title, p.domain as domain,
                   p.difficulty as difficulty, p.type as type,
                   (select max(s.submittedAt) from Submission s
                     where s.userId = :userId and s.problem = p) as lastAttemptedAt,
                   (select count(s) from Submission s
                     where s.userId = :userId and s.problem = p and s.correct = true) as correctCount,
                   (select count(s) from Submission s
                     where s.userId = :userId and s.problem = p) as attemptCount,
                   (select count(r) from ReviewItem r
                     where r.userId = :userId and r.problem = p
                       and r.status = project.study.study_project.review.domain.ReviewStatus.LEARNING
                       and r.nextReviewAt <= :now) as dueCount
            from Problem p
            where (:domain is null or p.domain = :domain)
              and (:difficulty is null or p.difficulty = :difficulty)
              and (:state is null
                   or (:state = 'CORRECT'
                       and exists (select 1 from Submission s
                                    where s.userId = :userId and s.problem = p and s.correct = true))
                   or (:state = 'WRONG'
                       and exists (select 1 from Submission s
                                    where s.userId = :userId and s.problem = p)
                       and not exists (select 1 from Submission s
                                        where s.userId = :userId and s.problem = p and s.correct = true))
                   or (:state = 'UNSOLVED'
                       and not exists (select 1 from Submission s
                                        where s.userId = :userId and s.problem = p)))
              and (:onlyDue = false
                   or exists (select 1 from ReviewItem r
                               where r.userId = :userId and r.problem = p
                                 and r.status = project.study.study_project.review.domain.ReviewStatus.LEARNING
                                 and r.nextReviewAt <= :now))
            order by p.domain,
                     case p.difficulty
                          when project.study.study_project.global.common.Difficulty.BEGINNER then 1
                          when project.study.study_project.global.common.Difficulty.INTERMEDIATE then 2
                          else 3
                     end,
                     p.id
            """)
    Page<ProblemListRow> findListForUser(
            @Param("userId") Long userId,
            @Param("domain") Domain domain,
            @Param("difficulty") Difficulty difficulty,
            @Param("state") String state,
            @Param("onlyDue") boolean onlyDue,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /**
     * 위 조회의 한 줄. 상태는 <b>개수로 받아 자바에서 판정</b>한다 —
     * CASE 식으로 문자열을 만들어 내려받으면 그 문자열과 {@code SolveState} enum이 두 곳에서
     * 따로 살게 되고, 언젠가 한쪽만 바뀐다.
     */
    interface ProblemListRow {
        Long getId();
        String getTitle();
        Domain getDomain();
        Difficulty getDifficulty();
        ProblemType getType();
        LocalDateTime getLastAttemptedAt();
        long getCorrectCount();
        long getAttemptCount();
        long getDueCount();
    }
}
