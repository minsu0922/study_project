package project.study.study_project.llm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.global.common.Domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 개념 문서 <b>주제 범위</b> 한 줄 — {@code topic_queue} 테이블(V10·V11)과 대응.
 *
 * <p><b>한 줄은 "Spring", "Java" 같은 범위다</b>(한 편짜리 주제가 아니다, V11). 배치는 문서일마다
 * 범위 하나를 골라 <b>그 안에서</b> 세부 주제를 정해 문서를 쓴다. 그러니 이 줄은 쓴다고
 * 없어지지 않는다 — 우물처럼 계속 퍼 올린다.
 *
 * <p><b>이 줄의 일생</b>: 관리자 화면에서 태어나 → {@code generated/_topics.json}으로 내보내지고
 * → 배치가 이 범위로 문서를 만든 뒤 파일에 날짜와 편수를 적고 → 앱이 켜질 때 그 값이 여기로
 * 돌아온다({@code TopicQueueSyncRunner}). 즉 <b>DB가 원본이고 파일은 사본</b>인데,
 * 사본에만 생기는 정보(언제·몇 번 쓰였나)가 있어 되돌아오는 길이 필요하다.
 *
 * <p><b>{@link #lastUsedAt}이 다음 차례를 정한다.</b> 규칙은 "아직 안 쓴 범위 먼저, 그다음은
 * 가장 오래 안 쓴 범위". 그래서 새로 넣은 범위가 곧바로 차례를 받고, 여러 범위가 골고루 돈다.
 * 순서값({@link #sortOrder})은 그 둘이 같을 때의 기준이다 — 처음 여러 개를 넣었을 때는
 * 전부 "안 쓴 범위"라 사람이 정한 순서가 그대로 지켜진다.
 *
 * <p><b>{@link #usedCount}는 우물이 마르는 것을 알아채기 위한 숫자다.</b> Spring으로 스무 편을
 * 쓰면 남는 주제가 억지스러워지는데, 문서는 계속 나오므로 <b>실패가 조용하다</b>.
 * 화면에 편수가 보여야 범위를 갈아 끼울 판단을 한다.
 *
 * <p><b>setter를 열지 않는 이유</b>는 다른 엔티티와 같다. 특히 {@link #recordUse(LocalDate, Integer)}는
 * <b>같은 내용으로 다시 불러도 아무 일도 하지 않는다</b> — 이 메서드는 사람이 누르는 버튼이
 * 아니라 파일을 훑는 동기화가 부르는 것이라, 같은 파일을 두 번 읽는 일이 정상적으로 일어난다
 * (앱을 두 번 켜면 그렇다). 여기서 편수를 무조건 올리면 <b>켤 때마다 숫자가 불어난다</b>.
 */
@Entity
@Table(name = "topic_queue")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TopicQueueItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Domain domain;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(length = 500)
    private String memo;

    /** 대기열 순서. 작을수록 먼저 쓴다. 값이 겹쳐도 정렬만 흔들릴 뿐이라 UNIQUE는 없다(V10). */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 이 범위로 마지막으로 문서를 만든 날. {@code null}이면 아직 한 번도 안 썼다(다음 차례 1순위). */
    @Column(name = "last_used_at")
    private LocalDate lastUsedAt;

    /** 이 범위로 만든 문서 편수. 우물이 말라 가는 것을 알아채는 유일한 신호다. */
    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private TopicQueueItem(Domain domain, String topic, String memo, int sortOrder,
                           LocalDate lastUsedAt, int usedCount) {
        this.domain = domain;
        this.topic = topic;
        this.memo = memo;
        this.sortOrder = sortOrder;
        this.lastUsedAt = lastUsedAt;
        this.usedCount = usedCount;
    }

    /** 관리자 화면에서 새로 추가하는 범위 — 아직 한 번도 안 쓴 상태로 태어난다. */
    public static TopicQueueItem fresh(Domain domain, String topic, String memo, int sortOrder) {
        return new TopicQueueItem(domain, topic, memo, sortOrder, null, 0);
    }

    /**
     * 파일에 손으로 적어 둔 줄을 그대로 들여올 때 쓴다({@code TopicQueueSyncRunner}).
     *
     * <p>사용 기록까지 받는 이유: 파일에는 이미 몇 번 쓴 범위가 남아 있을 수 있다(배치가 적고
     * 아직 앱을 안 켠 상태). 기록을 버리고 새것처럼 들여오면 <b>그 범위가 곧바로 다음 차례가
     * 되어</b> 순환이 한쪽으로 쏠린다.
     */
    public static TopicQueueItem imported(Domain domain, String topic, String memo,
                                          int sortOrder, LocalDate lastUsedAt, int usedCount) {
        return new TopicQueueItem(domain, topic, memo, sortOrder, lastUsedAt, usedCount);
    }

    /** 아직 한 번도 안 쓴 범위인가 — 다음 차례를 정할 때 이쪽이 먼저다. */
    public boolean isNeverUsed() {
        return lastUsedAt == null;
    }

    /**
     * 배치가 남긴 사용 기록을 반영한다 — <b>같은 값을 다시 받으면 아무 일도 하지 않는다</b>.
     *
     * <p>같은 파일을 여러 번 읽는 것이 정상 경로라서 그렇다. 부팅할 때마다 파일 전체를 훑는데,
     * 두 번째 부팅부터는 이미 반영된 기록만 다시 보게 된다. 여기서 편수를 무조건 올리면
     * <b>앱을 켤 때마다 숫자가 불어나</b> "우물이 말랐나"를 판단하는 근거가 망가진다.
     *
     * <p>판단 기준은 <b>날짜</b>다: 파일 쪽이 더 최근일 때만 받아들인다. 편수는 그때 함께 온
     * 값으로 맞춘다(배치가 DB 값에 1을 더해 적어 보낸 것이라 늘 이쪽이 크거나 같다).
     *
     * @param count 파일에 적힌 편수. {@code null}이면(옛 파일) 지금 값에 1을 더한다
     */
    public void recordUse(LocalDate date, Integer count) {
        if (date == null || (lastUsedAt != null && !date.isAfter(lastUsedAt))) {
            return;
        }
        this.lastUsedAt = date;
        this.usedCount = count != null ? Math.max(count, usedCount) : usedCount + 1;
    }

    /**
     * 사용 기록을 지워 <b>아직 안 쓴 범위</b>로 되돌린다 — 2026-09-05 신설.
     *
     * <p><b>왜 필요한가.</b> 기록은 배치가 <b>문서를 만들기로 정한 시점</b>에 찍힌다. 그 뒤에
     * 문서가 검수에서 거절되거나 코퍼스를 다시 세우면서 지워지면, 실제로는 아무것도 없는데
     * 기록만 남는다. 실물이 그랬다 — {@code 기본키와 외래키} 범위에 2026-09-07 사용 기록이
     * 있는데 그 날짜의 문서 파일도, 문서 스냅샷의 항목도, 거절 기록도 없었다.
     *
     * <p>그 상태가 특히 나쁜 이유는 <b>차례가 영원히 안 돌아온다</b>는 것이다. 다음 차례 규칙이
     * "안 쓴 것 먼저, 그다음 가장 오래 안 쓴 것"이라, 안 쓴 범위가 수십 개 남아 있는 동안
     * 한 번 찍힌 범위는 계속 뒤로 밀린다. 지금 대기열 67줄 중 63줄이 안 쓴 상태다.
     *
     * <p><b>왜 {@link #recordUse}로는 못 되돌리나.</b> 그쪽은 날짜를 <b>앞으로만</b> 옮긴다
     * (같은 파일을 두 번 읽어도 편수가 안 불어나게 하려는 장치다). 그래서 파일의 값을 지우고
     * 동기화해도 DB는 그대로다 — 되돌리는 문은 따로 열어야 한다.
     *
     * <p><b>편수도 함께 0으로 돌린다.</b> 날짜만 지우면 "한 번도 안 썼는데 1편"이라는 앞뒤가
     * 안 맞는 줄이 남고, 화면은 그 숫자를 그대로 보여 준다. 되돌린다면 끝까지 되돌린다.
     */
    public void clearUsage() {
        this.lastUsedAt = null;
        this.usedCount = 0;
    }

    /** 순서 바꾸기 — 두 행의 값을 맞바꾸는 방식이라 서비스가 짝지어 호출한다. */
    public void changeOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
