package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code generated/_topics.json}의 형태 — <b>개념 문서의 주제 범위 목록</b>.
 *
 * <p><b>한 줄은 "Spring", "Java" 같은 범위다</b>(V11). 배치는 문서일마다 범위 하나를 골라
 * 그 안에서 세부 주제를 정해 문서를 쓰고, 그 줄에 <b>마지막 사용일과 편수</b>를 적어 둔다.
 * 줄은 없어지지 않는다 — 다음 차례가 돌아오면 같은 범위에서 다른 주제를 캔다.
 *
 * <p><b>왜 DB가 아니라 파일도 있나.</b> 배치는 GitHub Actions 러너에서 돌고 거기에는 우리
 * MySQL이 없다. 중복 회피 목록·거절 사례가 이미 같은 이유로 저장소 파일이다(docs/14).
 * 다만 <b>방향이 반대</b>다: 다른 스냅샷은 앱이 쓰고 배치가 읽지만, 이 파일은 앱이 쓰고
 * <b>배치도 쓴다</b>(사용 기록). 그래서 앱이 켜질 때 되돌려 받는 길이 하나 더 있다.
 *
 * <p><b>손으로 고치는 파일이라 방어가 다르다.</b>
 * <ul>
 *   <li>{@code @JsonIgnoreProperties} — 메모용 필드를 하나 더 적어 넣어도 파일 전체가
 *       못 읽는 상태가 되면 안 된다. 그날 대기열이 통째로 사라지는 것이 훨씬 나쁘다.
 *   <li>{@code @JsonInclude(NON_NULL)} — 배치가 파일을 다시 쓸 때 {@code "memo": null} 같은
 *       빈 칸이 늘어나면 사람이 읽고 고치기 나쁘다.
 *   <li>{@code domain}이 {@link project.study.study_project.global.common.Domain}이 아니라
 *       <b>문자열</b>인 이유 — 오타 하나("SPRING")로 Jackson이 예외를 던지면 <b>멀쩡한 나머지
 *       범위까지</b> 못 읽는다. 문자열로 받아 항목 단위로 판정하고, 잘못된 것만 건너뛴다
 *       ({@link project.study.study_project.llm.support.TopicQueue}).
 * </ul>
 *
 * @param note   파일이 무엇인지 적어 두는 자리. 배치가 다시 쓸 때도 그대로 보존한다
 * @param topics 주제 범위 목록. 순서는 <b>사람이 정한 순서</b>이고, 아직 안 쓴 범위가 여럿일 때
 *               그 순서대로 차례가 온다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicQueueFile(String note, List<Entry> topics) {

    /**
     * 주제 범위 한 줄.
     *
     * @param id         DB {@code topic_queue.id}. 관리자 화면이 내보낸 줄에는 값이 있고,
     *                   <b>손으로 적어 넣은 줄에는 없다(null)</b>. 이 값이 파일 줄과 DB 행을
     *                   짝짓는 유일한 열쇠다 — 이름 글자로 짝지으면 화면에서 오타를 고친 순간
     *                   짝이 끊긴다. id 없는 줄은 기동 시 DB로 흡수되면서 번호를 받는다
     *                   ({@code TopicQueueSyncRunner}), 그래서 파일을 직접 고치는 사용법이 계속 산다
     * @param domain     분야 상수명({@code BACKEND_FRAMEWORK} 등). <b>필수</b> — 비우면 그 항목을
     *                   건너뛴다. 문서의 분야는 그 문서로 만드는 <b>사흘치 문제의 분야</b>까지
     *                   정하므로({@code DraftGeneratorCli.alignDomainWithDocument}), 비워 두면
     *                   범위와 분야가 어긋난 나흘이 통째로 나온다
     * @param topic      주제 범위. 분야보다는 좁고 한 편보다는 넓게 적는다
     *                   ("스프링·백엔드"(분야)와 "@Transactional 전파 속성"(한 편) 사이 —
     *                   예: "Spring 트랜잭션", "JVM 메모리")
     * @param memo       왜 이 범위를 넣었는지 — 배치는 읽지 않는다. 몇 주 뒤의 나를 위한 자리
     * @param lastUsedAt 이 범위로 <b>마지막으로</b> 문서를 만든 날({@code YYYY-MM-DD}). 배치가 적는다.
     *                   비어 있으면 아직 한 번도 안 쓴 범위라 다음 차례 1순위다
     * @param usedCount  이 범위로 만든 문서 편수. 범위가 말라 가는 것을 알아채는 신호다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(Long id, String domain, String topic, String memo,
                        String lastUsedAt, Integer usedCount) {

        /**
         * 아직 한 번도 안 쓴 범위인지. 공백만 있는 값도 "안 씀"으로 본다(손으로 지운 흔적).
         *
         * <p>{@code @JsonIgnore}가 없으면 <b>파일에 이 값이 딸려 나간다</b> — Jackson이
         * {@code isXxx()}를 속성으로 읽기 때문이다. 실제로 한 번 나갔다(2026-08-19).
         * 읽는 쪽은 모르는 필드를 무시하므로 동작에는 지장이 없지만, 이 파일은 사람이 손으로
         * 고치는 파일이라 <b>고쳐도 아무 효과가 없는 칸</b>이 보이는 것 자체가 함정이다.
         */
        @JsonIgnore
        public boolean isNeverUsed() {
            return lastUsedAt == null || lastUsedAt.isBlank();
        }

        /**
         * 이 범위를 한 번 더 썼다고 기록한 사본. record라 새로 만든다(원본을 고치지 않는다).
         *
         * <p>{@code id}를 그대로 옮기는 것이 중요하다 — 배치가 파일을 되쓸 때 번호가 사라지면
         * 앱은 그 줄을 "손으로 새로 적은 범위"로 보고 <b>같은 범위를 하나 더 만든다</b>.
         */
        public Entry usedOn(String date) {
            return new Entry(id, domain, topic, memo, date, (usedCount == null ? 0 : usedCount) + 1);
        }
    }
}
