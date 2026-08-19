package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code generated/_topics.json}의 형태 — <b>사람이 직접 적는 주제 대기열</b>(2026-08-19 신설).
 *
 * <p><b>왜 필요한가.</b> 배치는 분야({@code 스프링·백엔드})만 던지고 주제는 모델이 골랐다.
 * 그러면 분야를 통째로 개괄하는 문서가 나온다 — 학습자가 오늘 파고들고 싶은 주제와 무관하게.
 * 이 파일은 "다음에 이걸 써라"를 미리 줄 세워 두는 자리다.
 *
 * <p><b>왜 DB가 아니라 파일인가.</b> 배치는 GitHub Actions 러너에서 돌고 거기에는 우리 MySQL이
 * 없다. 중복 회피 목록·거절 사례가 이미 같은 이유로 저장소 파일이다(docs/14). 주제 대기열도
 * 같은 경계를 넘어야 하므로 같은 방식을 쓴다 — 다만 <b>방향이 반대</b>다. 다른 스냅샷은 앱이
 * 쓰고 배치가 읽지만, 이 파일은 <b>사람이 쓰고 배치가 읽고 지운다</b>.
 *
 * <p><b>손으로 고치는 파일이라 방어가 다르다.</b>
 * <ul>
 *   <li>{@code @JsonIgnoreProperties} — 메모용 필드를 하나 더 적어 넣어도 파일 전체가
 *       못 읽는 상태가 되면 안 된다. 그날 주제가 통째로 사라지는 것이 훨씬 나쁘다.
 *   <li>{@code @JsonInclude(NON_NULL)} — 배치가 파일을 다시 쓸 때 {@code "memo": null} 같은
 *       빈 칸이 늘어나면 사람이 읽고 고치기 나쁘다.
 *   <li>{@code domain}이 {@link project.study.study_project.global.common.Domain}이 아니라
 *       <b>문자열</b>인 이유 — 오타 하나("SPRING")로 Jackson이 예외를 던지면 <b>멀쩡한 나머지
 *       주제까지</b> 못 읽는다. 문자열로 받아 항목 단위로 판정하고, 잘못된 것만 건너뛴다
 *       ({@link project.study.study_project.llm.support.TopicQueue}).
 * </ul>
 *
 * @param note   파일이 무엇인지 적어 두는 자리. 배치가 다시 쓸 때도 그대로 보존한다
 * @param topics 대기열. <b>위에서부터</b> 하나씩 쓴다(적은 순서가 곧 학습 순서)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicQueueFile(String note, List<Entry> topics) {

    /**
     * 대기열 한 줄.
     *
     * @param domain 분야 상수명({@code BACKEND_FRAMEWORK} 등). <b>필수</b> — 비우면 그 항목을
     *               건너뛴다. 문서의 분야는 그 문서로 만드는 <b>사흘치 문제의 분야</b>까지
     *               정하므로({@code DraftGeneratorCli.alignDomainWithDocument}), 비워 두면
     *               주제와 분야가 어긋난 나흘이 통째로 나온다
     * @param topic  주제. 분야보다 좁게 적을수록 좋다("스프링 트랜잭션"이 아니라
     *               "@Transactional 전파 속성")
     * @param memo   왜 이 주제를 넣었는지 — 배치는 읽지 않는다. 몇 주 뒤의 나를 위한 자리다
     * @param usedAt 이 주제로 문서를 만든 날({@code YYYY-MM-DD}). 배치가 채운다.
     *               <b>값이 있으면 다시 쓰지 않는다</b> — 지우지 않고 남기는 이유는 언제 무엇을
     *               공부했는지가 그대로 기록이 되기 때문(거절 사유를 지우지 않는 것과 같은 판단)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String domain, String topic, String memo, String usedAt) {

        /** 아직 안 쓴 항목인지. 공백만 있는 {@code usedAt}도 "안 씀"으로 본다(손으로 지운 흔적). */
        public boolean isPending() {
            return usedAt == null || usedAt.isBlank();
        }

        /** 이 항목에 사용 날짜를 찍은 사본. record라 새로 만든다(원본을 고치지 않는다). */
        public Entry usedOn(String date) {
            return new Entry(domain, topic, memo, date);
        }
    }
}
