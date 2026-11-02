package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 배치로 보내기 한 번의 결과(docs/29 5장).
 *
 * @param status  결과 종류
 * @param message 사람이 읽을 한 줄 설명. 실패면 이유와 할 일
 * @param commit    push한 커밋 번호. 보내지 못했으면 빈 문자열
 * @param commitUrl 그 커밋의 GitHub 주소. origin이 GitHub가 아니거나 못 보냈으면 빈 문자열
 * @param files     이번에 커밋한 파일
 * @param details   막힌 이유·겹친 파일·충돌 파일 같은 목록
 */
public record GeneratedPublishResult(Status status, String message, String commit, String commitUrl,
                                     List<String> files, List<String> details) {

    public enum Status {
        /** 커밋하고 push했다 */
        PUBLISHED,
        /** 보낼 것이 없다 */
        NOTHING,
        /** 5장 점검에 걸려 아무것도 하지 않았다 */
        BLOCKED,
        /** rebase가 충돌해 누르기 전 상태로 되돌렸다 */
        CONFLICT,
        /** 커밋은 남기고 push만 실패했다. 다시 누르면 push만 한다 */
        PUSH_FAILED,
        /** 다른 보내기가 도는 중이다 */
        BUSY,
        /** 이 앱에서는 쓸 수 없다(저장소 없음 등) */
        UNAVAILABLE,
        /** 그 밖의 실패(시간 초과, 커밋 거부 등). 진행 중이던 rebase는 되돌렸다 */
        FAILED
    }

    public static GeneratedPublishResult of(Status status, String message, List<String> details) {
        return new GeneratedPublishResult(status, message, "", "", List.of(), details);
    }

    public boolean published() {
        return status == Status.PUBLISHED;
    }
}
