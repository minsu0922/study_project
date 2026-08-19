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
 * 개념 문서 주제 대기열 한 줄 — {@code topic_queue} 테이블(V10)과 대응.
 *
 * <p><b>이 줄의 일생</b>: 관리자 화면에서 태어나 → {@code generated/_topics.json}으로 내보내지고
 * → 배치가 그 주제로 문서를 만든 뒤 파일에 날짜를 찍고 → 앱이 켜질 때 그 날짜가 여기로
 * 돌아온다({@code TopicQueueSyncRunner}). 즉 <b>DB가 원본이고 파일은 사본</b>인데,
 * 사본에만 생기는 정보(언제 쓰였나)가 하나 있어 되돌아오는 길이 필요하다.
 *
 * <p><b>setter를 열지 않는 이유</b>는 다른 엔티티와 같다. 특히 {@link #markUsed(LocalDate)}는
 * <b>이미 쓴 항목에 다시 호출해도 조용히 넘어간다</b>(예외가 아니다) — 이 메서드는 사람이
 * 누르는 버튼이 아니라 파일을 훑는 동기화가 부르는 것이라, 같은 파일을 두 번 읽는 일이
 * 정상적으로 일어난다(앱을 두 번 켜면 그렇다). 여기서 예외를 던지면 부팅이 실패한다.
 *
 * <p><b>다 쓴 항목을 지우지 않는다.</b> 남겨 두면 "언제 무엇을 공부했나"가 그대로 기록이 되고,
 * 관리 화면에서 지난 주제를 훑어보며 다음 주제를 고를 수 있다. 대신 내보내기는 대기 중인
 * 것만 싣는다 — 파일의 목적은 "다음에 무엇을 쓸까"이지 기록 보관이 아니다.
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

    /** 이 주제로 문서를 만든 날. {@code null}이면 아직 대기 중. */
    @Column(name = "used_at")
    private LocalDate usedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private TopicQueueItem(Domain domain, String topic, String memo, int sortOrder, LocalDate usedAt) {
        this.domain = domain;
        this.topic = topic;
        this.memo = memo;
        this.sortOrder = sortOrder;
        this.usedAt = usedAt;
    }

    /** 관리자 화면에서 새로 추가하는 주제 — 항상 대기 상태로 태어난다. */
    public static TopicQueueItem pending(Domain domain, String topic, String memo, int sortOrder) {
        return new TopicQueueItem(domain, topic, memo, sortOrder, null);
    }

    /**
     * 파일에 손으로 적어 둔 줄을 그대로 들여올 때 쓴다({@code TopicQueueSyncRunner}).
     *
     * <p>{@code usedAt}을 받는 이유: 파일에는 이미 쓴 항목이 남아 있을 수 있다(배치가 찍고
     * 아직 앱을 안 켠 상태). 그것을 대기로 들여오면 <b>같은 주제로 문서를 한 번 더 만든다</b>.
     */
    public static TopicQueueItem imported(Domain domain, String topic, String memo,
                                          int sortOrder, LocalDate usedAt) {
        return new TopicQueueItem(domain, topic, memo, sortOrder, usedAt);
    }

    /** 아직 안 쓴 주제인가. */
    public boolean isPending() {
        return usedAt == null;
    }

    /**
     * 사용 표시 — 이미 표시된 항목이면 <b>아무 일도 하지 않는다</b>(멱등).
     *
     * <p>같은 파일을 여러 번 읽는 것이 정상 경로라서 그렇다. 부팅할 때마다 파일 전체를 훑는데,
     * 두 번째 부팅부터는 "이미 반영된 사용 표시"만 잔뜩 보게 된다. 여기서 예외를 던지면
     * 두 번째 부팅이 실패한다 — 초안 상태 전이(LLM_002)와 반대로 판단하는 이유다.
     */
    public void markUsed(LocalDate date) {
        if (usedAt == null) {
            this.usedAt = date;
        }
    }

    /** 순서 바꾸기 — 두 행의 값을 맞바꾸는 방식이라 서비스가 짝지어 호출한다. */
    public void changeOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
