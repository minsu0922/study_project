package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.dto.DomainSettingsFile;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.util.List;

/**
 * 분야 설정을 {@code generated/_domain-settings.json}으로 내보낸다 — DB를 클라우드 배치까지
 * 나르는 다리({@code TopicQueueExporter}와 같은 역할, 다른 테이블).
 *
 * <p>배치는 GitHub Actions 러너에서 돌고 거기에는 우리 MySQL이 없다. 관리 화면에서 분야를
 * 켜고 끄거나 순서를 바꿔도, 이 파일을 통해 내보내고 <b>커밋해야만</b> 다음 배치 실행이 그
 * 변경을 본다 — {@link #NOTE}에 그 사실을 적어 둔다.
 *
 * <p><b>동기화(DomainSettingSyncRunner, {@code @Order(4)})보다 반드시 뒤에 돌아야 한다.</b>
 * 순서가 뒤집히면 아직 행이 하나도 없는 빈 테이블을 파일로 써 버리고, 그 빈 파일이 그대로
 * 커밋되면 클라우드 배치는 다음 실행까지 분야 설정이 하나도 없는 채로 하루를 돈다
 * ({@code DomainSettingSyncRunner}의 같은 주석 참고). 기존 내보내기들이 20·30·40·50을 이미
 * 쓰고 있어 그 뒤인 60에 둔다.
 *
 * <p>파일을 언제 쓰고 언제 안 쓰는지 같은 공통 규칙은 {@link SnapshotExporter}에 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.import.enabled", havingValue = "true", matchIfMissing = true)
@Order(60)
public class DomainSettingExporter extends SnapshotExporter {

    /**
     * 배치와 같은 파일을 가리킨다. Task 6이 {@code DomainSettings.FILE_NAME}을 만들면 그 값을
     * 빌려 쓰도록 바꾼다({@code TopicQueueExporter}가 {@code TopicQueue.FILE_NAME}을 빌려 쓰는
     * 것과 같은 꼴) — 지금은 그 클래스가 아직 없어 여기 직접 상수를 둔다.
     */
    static final String FILE_NAME = "_domain-settings.json";

    private static final String NOTE =
            "관리 화면의 '분야 설정'에서 내보낸 파일입니다. 배치는 enabled=true인 분야만 sortOrder "
                    + "순으로 날짜 순환 후보로 삼고, hint는 모델에게 주는 경계 설명으로 씁니다. "
                    + "꺼진 분야(enabled=false)도 이름·힌트가 남아 있으니 지우지 마세요 — 화면에서 "
                    + "다시 켤 때 그 값을 그대로 씁니다. 이 파일은 배치가 되쓰지 않는 읽기 전용 "
                    + "파일입니다 — 값을 바꾸려면 관리 화면에서 고치세요. 손으로 고친 내용은 다음 "
                    + "기동에 DB로 흡수되지 않고, 오히려 다음 내보내기 때 DB 값으로 덮어써집니다. "
                    + "이 파일이 갱신되면 커밋해야 다음 배치부터 반영됩니다.";

    private final DomainSettingRepository repository;

    public DomainSettingExporter(DomainSettingRepository repository, ObjectMapper objectMapper) {
        super(objectMapper);
        this.repository = repository;
    }

    @Override
    protected String fileName() {
        return FILE_NAME;
    }

    @Override
    protected String label() {
        return "분야 설정";
    }

    /**
     * 행이 하나도 없고 파일도 없으면 만들지 않는다({@code TopicQueueExporter}와 같은 판단) —
     * 동기화가 아직 한 번도 안 돌았거나 예외로 실패한 순간(부팅 도중)에 이 러너가 먼저 끼어들어
     * 빈 파일을 커밋 대상으로 만드는 것을 막는다. 파일이 이미 있다면 빈 목록으로라도 갱신해야
     * 화면에서 전부 지운 상태(이론상 없는 경우지만)가 배치에 그대로 전달된다.
     *
     * <p><b>꺼진 분야도 담는다</b> — {@code repository.findAll}이 아니라
     * {@code findAllByOrderBySortOrderAsc}를 그대로 쓰되 {@code enabled}로 거르지 않는다.
     * 관리 화면에서 분야를 다시 켤 때 이름·힌트가 파일에 남아 있어야 하고, 배치가 어떤 분야를
     * 후보에서 제외했는지도 이 파일 하나만 보고 알 수 있어야 한다(거르는 일은 Task 6의 리더 몫).
     */
    @Override
    protected Snapshot build(boolean fileExists) {
        List<DomainSettingsFile.Entry> entries = repository.findAllByOrderBySortOrderAsc().stream()
                .map(DomainSettingExporter::toEntry)
                .toList();

        if (entries.isEmpty() && !fileExists) {
            return null;
        }
        return new Snapshot(new DomainSettingsFile(NOTE, entries), entries.size() + "건");
    }

    /**
     * 테스트 전용 통로. {@code build}가 {@code protected}라 테스트 클래스(같은 패키지)에서만
     * 부를 수 있고, 그마저도 {@link Snapshot}이 아니라 실제로 파일에 실릴 {@link DomainSettingsFile}
     * 하나만 넘긴다 — 테스트가 {@code Snapshot.detail()} 같은 부수 정보까지 알 필요는 없다.
     * {@code fileExists}에 항상 {@code true}를 넘기는 것도 의도다: 테스트는 "지금 DB 상태가
     * 파일에 어떻게 반영되는가"만 보면 되고, 빈 테이블일 때 파일을 만들지 말지 같은 판단은
     * {@link #build(boolean)} 자체의 단위 관심사가 아니라 이 메서드가 대신 확정해 준다.
     */
    DomainSettingsFile snapshotForTest() {
        return (DomainSettingsFile) build(true).payload();
    }

    /**
     * 분야 설정이 바뀌면 <b>커밋된 뒤</b> 다시 내보낸다.
     *
     * <p>{@code AFTER_COMMIT}인 이유는 {@code TopicQueueExporter}의 같은 주석 그대로다 — 커밋
     * 전에 쓰면 아직 DB에 없는 상태가 파일에 남고, 롤백되면 <b>일어나지 않은 변경이 파일에
     * 남는다</b>. Task 9가 이 이벤트를 실제로 publish하기 전까지는 이 리스너를 통째로 돌려 볼
     * 수 없다 — 지금은 형태만 갖춰 둔다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainSettingChanged(DomainSettingChanged event) {
        exportQuietly("분야 설정 변경은 정상 처리됨");
    }

    /** 엔티티 → 파일 한 줄. */
    private static DomainSettingsFile.Entry toEntry(DomainSetting setting) {
        return new DomainSettingsFile.Entry(
                setting.getDomain().name(), setting.isEnabled(), setting.getSortOrder(),
                setting.getDisplayName(), setting.getHint());
    }
}
