package project.study.study_project.llm.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DomainSettingService#edit}·{@link DomainSettingService#move}가 낸
 * {@link DomainSettingChanged}가 <b>실제로 {@link DomainSettingExporter}까지 닿는지</b> 확인한다
 * — Task 9 전까지는 아무도 이 이벤트를 publish하지 않아 그 리스너를 통째로 돌려 볼 방법이
 * 없었다({@code DomainSettingExporter.onDomainSettingChanged} Javadoc).
 *
 * <h2>왜 클래스에 {@code @Transactional}을 안 두나</h2>
 *
 * <p>{@code AdminDomainSettingIntegrationTest}처럼 클래스를 {@code @Transactional}로 감싸면
 * 테스트 프레임워크가 트랜잭션을 <b>절대 커밋하지 않고 끝에 롤백</b>한다 — 그러면
 * {@code AFTER_COMMIT} 리스너는 영영 불리지 않는다. 그 통합 테스트가 확인하는 것은 "요청이
 * 서비스에 닿는가"이지 "커밋 뒤 내보내기가 실제로 도는가"가 아니다. 여기서는 그 반대를
 * 본다 — 서비스 메서드 자체의 {@code @Transactional}이 실제로 커밋되게 두고, 그 뒤에
 * 내보내기 파일이 바뀌는지를 확인한다.
 *
 * <h2>왜 실제 DB 행을 건드리고도 안전한가</h2>
 *
 * <p>{@code llm.import.dir}을 테스트 전용 임시 폴더로 돌려 실제 저장소의
 * {@code generated/_domain-settings.json}은 건드리지 않는다. DB 쪽은 {@link #restoreOriginal}이
 * {@link #setUp}에서 찍어 둔 원래 값으로 테스트가 끝나면 되돌린다({@code @AfterEach}) — 커밋을
 * 막을 수 없는 대신 <b>원상 복구도 커밋</b>해 흔적을 남기지 않는다.
 */
@SpringBootTest(properties = {
        "llm.import.dir=build/test-domain-setting-changed-export",
        "ratelimit.enabled=false"})
class DomainSettingChangedExportIntegrationTest {

    private static final Path DIR = Path.of("build/test-domain-setting-changed-export");
    private static final DomainCode TARGET = TestDomains.NETWORK;

    @Autowired
    private DomainSettingService domainSettingService;
    @Autowired
    private DomainSettingRepository repository;

    private boolean originalEnabled;
    private String originalDisplayName;
    private String originalHint;

    @BeforeEach
    void setUp() throws Exception {
        DomainSetting original = repository.findByDomain(TARGET).orElseThrow();
        originalEnabled = original.isEnabled();
        originalDisplayName = original.getDisplayName();
        originalHint = original.getHint();

        clearDir();
    }

    /** 원래 값으로 되돌린다 — 이 되돌리기도 커밋되므로 테스트가 끝나면 DB는 시작 전과 같다. */
    @AfterEach
    void restoreOriginal() {
        domainSettingService.edit(TARGET,
                new AdminDomainSettingRequest(originalEnabled, originalDisplayName, originalHint));
    }

    @Test
    @DisplayName("edit이 낸 신호가 커밋 뒤 실제로 내보내기 파일을 갱신한다")
    void editTriggersRealExport() throws Exception {
        String probe = "이벤트 배선 확인용 힌트 — " + System.nanoTime();

        domainSettingService.edit(TARGET, new AdminDomainSettingRequest(true, "네트워크", probe));

        // AFTER_COMMIT 리스너는 서비스 메서드가 반환하며 트랜잭션이 커밋된 <직후> 같은 스레드에서
        // 동기로 돈다(SimpleTransactionScope 기본 전파, 별도 스레드/큐를 쓰지 않는다) — 그래서
        // 여기서 곧바로 파일을 읽어도 된다. 만약 비동기로 바뀌면 이 단언이 그 사실을 곧장 잡아낸다.
        Path file = DIR.resolve(DomainSettingExporter.FILE_NAME);
        assertThat(file).exists();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains(probe);
    }

    private void clearDir() throws Exception {
        if (!Files.isDirectory(DIR)) {
            return;
        }
        try (var paths = Files.walk(DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // 지우지 못해도 테스트를 멈추지 않는다 — 단언이 대신 말해 준다.
                }
            });
        }
    }
}
