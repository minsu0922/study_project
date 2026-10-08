package project.study.study_project.quiz.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.dto.StudyTrendDay;
import project.study.study_project.quiz.dto.SubmissionHistoryItem;
import project.study.study_project.quiz.repository.SubmissionRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 내 제출 기록을 시간 축으로 읽는다 — 날짜별 추이와 풀이 이력. 둘 다 읽기 전용이다. */
@Service
@RequiredArgsConstructor
public class StudyHistoryService {

    public static final int DEFAULT_TREND_DAYS = 30;
    /** 추이 상한. 막대 하나가 하루라 석 달을 넘기면 폰 화면에서 막대가 선이 된다. */
    private static final int MAX_TREND_DAYS = 90;

    private final SubmissionRepository submissionRepository;

    /**
     * 오늘을 포함한 최근 {@code days}일의 날짜별 제출 수·정답 수.
     *
     * <p><b>안 푼 날도 0으로 채워 준다.</b> 집계 결과에는 제출이 있던 날만 나오는데,
     * 그대로 그리면 쉰 날이 사라져 매일 푼 것처럼 보인다.
     */
    @Transactional(readOnly = true)
    public List<StudyTrendDay> trend(Long userId, int days) {
        int span = Math.min(Math.max(days, 1), MAX_TREND_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);

        Map<LocalDate, SubmissionRepository.DailyStat> byDate = submissionRepository
                .aggregateDaily(userId, from.atStartOfDay()).stream()
                .collect(Collectors.toMap(s -> s.getDay().toLocalDate(), s -> s));

        List<StudyTrendDay> result = new ArrayList<>(span);
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            SubmissionRepository.DailyStat stat = byDate.get(d);
            result.add(stat == null
                    ? new StudyTrendDay(d, 0, 0)
                    : new StudyTrendDay(d, stat.getAttempts(), stat.getCorrectCount()));
        }
        return result;
    }

    /**
     * 풀이 이력 — 맞힌 것까지 포함해 최신순.
     *
     * @param correct {@code null}이면 전부, 아니면 맞힌 것만 또는 틀린 것만
     */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionHistoryItem> history(Long userId, Boolean correct, Pageable pageable) {
        return PageResponse.from(submissionRepository.findHistory(userId, correct, pageable)
                .map(SubmissionHistoryItem::from));
    }
}
