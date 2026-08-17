package project.study.study_project.llm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검수 → 스냅샷 갱신을 잇는 배선이 <b>조용히 끊기는 것</b>을 막는다.
 *
 * <p>이 배선이 끊겨도 앱은 멀쩡히 돌고 검수도 성공한다. 증상은 며칠 뒤에야 나타난다 —
 * 배치가 옛 회피 목록을 읽고 <b>이미 승인한 문제를 다시 만들어</b> 낸다.
 * 2026-08-17에 실제로 그 직전까지 갔다(파일 08-14, DB 08-17, 그 사이 승인 4문항).
 *
 * <p>스프링 컨텍스트를 띄우지 않고 <b>애너테이션만</b> 본다. 실제 이벤트 전파를 확인하려면
 * DB와 트랜잭션이 필요해 통합 테스트가 되는데, 여기서 지키려는 것은 "이벤트가 흐르는가"가
 * 아니라 <b>누군가 리스너를 지우거나 커밋 단계를 바꿔 놓는 것</b>이다.
 */
class ReviewSnapshotTriggerTest {

    private static final List<Class<?>> EXPORTERS = List.of(
            ExistingQuestionsExporter.class,
            RejectionNotesExporter.class,
            ExistingDocumentsExporter.class);

    /**
     * <b>커밋 단계가 핵심이다.</b> 그냥 {@code @EventListener}면 트랜잭션이 커밋되기 <b>전에</b>
     * 실행되어, 아직 DB에 반영되지 않은 상태를 찍는다 — 고치려던 "한 박자 늦음"이 그대로 남는다.
     * 게다가 그 뒤 롤백되면 <b>일어나지 않은 승인이 파일에 남는다</b>.
     */
    @Test
    @DisplayName("세 내보내기가 모두 커밋 뒤에 듣는다 — 커밋 전이면 반영되지 않은 상태를 찍는다")
    void everyExporterListensAfterCommit() {
        assertThat(EXPORTERS).allSatisfy(exporter -> {
            Method listener = Arrays.stream(exporter.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(TransactionalEventListener.class))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            exporter.getSimpleName() + "에 검수 신호를 듣는 리스너가 없다 — "
                                    + "검수해도 스냅샷이 갱신되지 않는다"));

            assertThat(listener.getAnnotation(TransactionalEventListener.class).phase())
                    .as("%s가 커밋 전에 실행되면 반영 전 상태를 찍는다", exporter.getSimpleName())
                    .isEqualTo(TransactionPhase.AFTER_COMMIT);

            assertThat(listener.getParameterTypes())
                    .as("%s의 리스너는 검수 신호를 받아야 한다", exporter.getSimpleName())
                    .containsExactly(ReviewCompleted.class);
        });
    }

    /**
     * 문제 쪽 스냅샷은 문서 검수에, 문서 쪽 스냅샷은 문제 검수에 반응하지 않아야 한다.
     * 바뀔 리 없는 파일을 다시 읽고 비교하는 비용은 작지만, 로그에 "변경 없음"이 두 배로
     * 쌓여 진짜 갱신이 묻힌다.
     */
    @Test
    @DisplayName("신호 종류가 둘로 갈려 있다 — 문제 검수에 문서 스냅샷까지 다시 찍지 않는다")
    void signalIsSplitByTarget() {
        assertThat(ReviewCompleted.Target.values())
                .containsExactly(ReviewCompleted.Target.PROBLEM, ReviewCompleted.Target.DOCUMENT);
        assertThat(ReviewCompleted.problem().target()).isEqualTo(ReviewCompleted.Target.PROBLEM);
        assertThat(ReviewCompleted.document().target()).isEqualTo(ReviewCompleted.Target.DOCUMENT);
    }
}
