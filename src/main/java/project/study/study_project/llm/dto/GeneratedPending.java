package project.study.study_project.llm.dto;

import java.time.Instant;
import java.util.List;

/**
 * 배치로 보낼 변경 — docs/29 3장 범위 안에서 커밋되지 않은 파일과, 보내기를 막는 이유.
 *
 * @param available         이 앱에서 보내기를 쓸 수 있는가(저장소·origin이 있는가)
 * @param unavailableReason 쓸 수 없으면 그 이유. 쓸 수 있으면 빈 문자열
 * @param files             보낼 파일
 * @param unpushedCommits   앞서 보내다 push만 실패한 커밋 수. 다음 보내기에 함께 나간다
 * @param blockers          5장 점검에 걸린 이유. 비어 있어야 보낼 수 있다(원격 대조는 보낼 때 한다)
 */
public record GeneratedPending(boolean available, String unavailableReason, List<File> files,
                               int unpushedCommits, List<String> blockers) {

    public static GeneratedPending unavailable(String reason) {
        return new GeneratedPending(false, reason, List.of(), 0, List.of());
    }

    public boolean hasSomethingToSend() {
        return !files.isEmpty() || unpushedCommits > 0;
    }

    public enum Kind { SNAPSHOT, SOURCE_DOCUMENT }

    public enum Change { MODIFIED, ADDED, DELETED }

    /**
     * @param path       저장소 루트 기준 경로(예: {@code generated/_topics.json})
     * @param kind       현황 파일인가 원문 문서인가
     * @param change     바뀐 모양
     * @param added      더한 줄 수
     * @param deleted    뺀 줄 수
     * @param modifiedAt 파일이 바뀐 시각. 지운 파일은 null
     */
    public record File(String path, Kind kind, Change change, int added, int deleted, Instant modifiedAt) {
    }
}
