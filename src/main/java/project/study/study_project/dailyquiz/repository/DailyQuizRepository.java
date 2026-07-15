package project.study.study_project.dailyquiz.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.study_project.dailyquiz.domain.DailyQuiz;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * DailyQuiz 저장소 — 오늘 세트 조회(2가지 무게) + 스트릭 계산용 완료 날짜 조회(docs/12).
 */
public interface DailyQuizRepository extends JpaRepository<DailyQuiz, Long> {

    /**
     * submit 훅(DailyQuizService.onSubmission)용 가벼운 단건 조회 — 항목은 LAZY로 두고
     * 필요할 때(connectSubmission의 순회) 트랜잭션 안에서 읽는다.
     * UNIQUE 제약(uk_dailyquiz_user_date)이 인덱스를 겸해 이 조회를 받쳐 준다.
     */
    Optional<DailyQuiz> findByUserIdAndQuizDate(Long userId, LocalDate quizDate);

    /**
     * 세트 화면용 조회 — 항목과 문제까지 fetch join으로 한 번에 읽는다(응답에 지문·보기가 필요).
     *
     * <p>컬렉션 fetch join이지만 페이징이 없어(단건) 안전하다 — ReviewItemRepository들이
     * 컬렉션 fetch join을 피한 이유는 페이징과의 충돌이었지, 컬렉션 자체가 아니다.
     * 중첩 fetch(i.problem)까지 걸어 "세트 1 + 항목 10 + 문제 10"을 쿼리 1번으로 끝낸다
     * (객관식 보기는 LAZY — default_batch_fetch_size가 IN 조회로 묶는다, Problem 주석 참고).
     * Hibernate 6는 fetch join의 루트 중복을 자동 제거하므로 distinct는 쓰지 않는다.
     *
     * <p><b>left join인 이유</b>: 문제 풀이 완전히 비어 "항목 0개 세트"가 저장된 극단 상황에서
     * inner join이면 세트가 조회되지 않아, 지연 생성이 매번 재생성을 시도하다 UNIQUE 위반으로
     * 무한 실패한다. left join은 빈 세트도 세트로 돌려줘 이 루프를 원천 차단한다.
     */
    @Query("""
            select d from DailyQuiz d
            left join fetch d.items i
            left join fetch i.problem
            where d.userId = :userId
              and d.quizDate = :quizDate
            """)
    Optional<DailyQuiz> findWithItems(@Param("userId") Long userId,
                                      @Param("quizDate") LocalDate quizDate);

    /**
     * 스트릭 계산 재료 — 완료된 세트의 날짜만 최신순으로. 연속 판정은 서비스가 한다
     * (SQL로 "연속"을 세려면 윈도우 함수 곡예가 필요한데, 하루 1행이라 1년 치가 365행 —
     * 자바 순회가 더 읽기 쉽고 충분히 싸다).
     * UNIQUE(user_id, quiz_date) 인덱스가 필터+정렬을 그대로 받는다.
     */
    @Query("""
            select d.quizDate from DailyQuiz d
            where d.userId = :userId
              and d.completedAt is not null
            order by d.quizDate desc
            """)
    List<LocalDate> findCompletedDatesDesc(@Param("userId") Long userId);
}
