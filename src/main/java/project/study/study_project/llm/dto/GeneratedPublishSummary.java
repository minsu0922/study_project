package project.study.study_project.llm.dto;

import java.time.Instant;

/**
 * 상단 띠의 "보낼 변경" 칩에 쓰는 요약(docs/29 6장).
 *
 * @param available 이 앱에서 보내기를 쓸 수 있는가. false면 칩을 숨긴다
 * @param count     보낼 파일 수 + push만 실패해 남은 커밋 수
 * @param oldestAt  보낼 파일 중 가장 먼저 바뀐 시각. 없으면 null
 */
public record GeneratedPublishSummary(boolean available, int count, Instant oldestAt) {

    public static final GeneratedPublishSummary UNAVAILABLE = new GeneratedPublishSummary(false, 0, null);

    public static GeneratedPublishSummary of(GeneratedPending pending) {
        if (!pending.available()) {
            return UNAVAILABLE;
        }
        Instant oldest = pending.files().stream()
                .map(GeneratedPending.File::modifiedAt)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        return new GeneratedPublishSummary(true, pending.files().size() + pending.unpushedCommits(), oldest);
    }
}
