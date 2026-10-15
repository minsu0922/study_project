package project.study.study_project.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주간 코드 문제 배치의 상태 — {@code GET /api/admin/batch-status/code}의 응답(2026-10-15 신설).
 *
 * <p>일일 배치({@link AdminBatchStatus})와 따로 둔 이유: 주기·주제·결과 파일이 모두 다르다.
 * 한 응답에 넣으면 4일 주기 달력을 그리는 값들 사이에 주 1회 배치의 값이 섞인다.
 *
 * @param topicCount 코드 문제 주제 수. 0이면 배치가 요금 없이 건너뛴다
 * @param nextTopic  다음 실행이 쓸 주제. 주제가 없으면 {@code null}
 * @param nextRunAt  다음 예약 실행 시각(KST). 워크플로 cron과 같은 값이다
 * @param runs       최근 실행 결과 — 결과 파일이 있는 날짜만, 최신 순
 */
public record AdminCodeBatchStatus(long topicCount, String nextTopic, LocalDateTime nextRunAt, List<Run> runs) {

    /**
     * 한 날짜의 실행 결과. 난이도별로 나뉜 파일 셋을 합친 값이다.
     *
     * @param problems 실행 검증을 통과해 파일에 실린 문제 수
     * @param findings 검수 지적 수(의견 제외). 검수를 안 돌린 파일만 있으면 {@code null}
     * @param imported 그 날짜 파일이 모두 이 PC의 DB에 들어왔나
     */
    public record Run(String date, String topic, int problems, Integer findings, boolean imported) {
    }
}
