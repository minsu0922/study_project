package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.dto.DomainSettingsFile;
import project.study.study_project.llm.repository.DomainSettingRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 분야 설정 내보내기 테스트.
 *
 * <p>{@code TopicQueueExporterTest}와 같은 방식이다 — repository를 목으로 두고
 * {@code exporter.export(tempDir)}를 실제로 불러 파일을 쓰게 한 뒤 그 파일을 다시 읽어
 * 검증한다. 처음 버전은 {@code build(true).payload()}만 직접 불러 {@link SnapshotExporter}의
 * 뼈대(파일 존재 여부 판단·변경 없으면 안 쓰기·{@code @JsonInclude(NON_NULL)})를 하나도
 * 거치지 않았다 — 그 세 가지가 바로 이 설계가 의존하는 동작이라, 실제 쓰기 경로
 * ({@link DomainSettingExporter#export(Path)})를 타야 검증한 것이 된다.
 */
@ExtendWith(MockitoExtension.class)
class DomainSettingExporterTest {

    @Mock
    private DomainSettingRepository repository;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DomainSettingExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new DomainSettingExporter(repository, objectMapper);
    }

    @Test
    @DisplayName("켜짐·순서·이름·힌트를 그대로 싣는다 — 배치가 이 파일만 보고 돌 수 있어야 한다")
    void exportsEveryFieldTheBatchNeeds() throws Exception {
        given(setting(Domain.NETWORK, true, 0, "네트워크", "경계 설명"));

        assertThat(exporter.export(tempDir)).isTrue();

        DomainSettingsFile.Entry first = read().domains().get(0);
        assertThat(first.domain()).isEqualTo("NETWORK");
        assertThat(first.enabled()).isTrue();
        assertThat(first.sortOrder()).isZero();
        assertThat(first.displayName()).isEqualTo("네트워크");
        assertThat(first.hint()).isEqualTo("경계 설명");
    }

    @Test
    @DisplayName("꺼진 분야도 내보낸다 — 화면에서 다시 켤 때 이름·힌트가 남아 있어야 한다")
    void disabledDomainsAreExportedToo() throws Exception {
        given(setting(Domain.CLOUD_INFRA, false, 9, "클라우드·인프라", null));

        exporter.export(tempDir);

        assertThat(read().domains()).anyMatch(e -> e.domain().equals("CLOUD_INFRA") && !e.enabled());
    }

    /**
     * {@code @JsonInclude(NON_NULL)}이 실제로 먹는지 확인한다. {@code DomainSettingsFile.Entry}를
     * 직접 만들어 직렬화하면 이 어노테이션을 우회하는 것은 아니지만, {@code build(true).payload()}
     * 만 부르던 옛 테스트는 <b>파일로 한 번도 안 써 봤다</b> — Jackson 직렬화 설정이 실제로
     * 적용되는지는 진짜 파일 쓰기를 거쳐야 확인된다.
     */
    @Test
    @DisplayName("힌트가 없으면 \"hint\" 칸 자체를 뺀다 — null이 줄줄이 찍히면 사람이 읽고 고치기 나쁘다")
    void omitsMissingHint() throws Exception {
        given(setting(Domain.NETWORK, true, 0, "네트워크", null));

        exporter.export(tempDir);

        JsonNode entry = objectMapper.readTree(tempDir.resolve(DomainSettingExporter.FILE_NAME).toFile())
                .get("domains").get(0);
        assertThat(entry.has("hint")).isFalse();
    }

    @Test
    @DisplayName("행이 하나도 없고 파일도 없으면 만들지 않는다 — 커밋할 것도 없는 빈 파일은 혼란만 준다")
    void doesNotCreateEmptyFile() throws Exception {
        given();

        assertThat(exporter.export(tempDir)).isFalse();
        assertThat(Files.exists(tempDir.resolve(DomainSettingExporter.FILE_NAME))).isFalse();
    }

    /**
     * 앱은 {@code git push}를 하지 않는다 — 사람이 파일을 보고 직접 커밋해야 배치에 반영된다.
     * 내용이 같은데도 기동마다 다시 쓰면 "오늘 실제로 뭐가 바뀌었나"를 파일 수정 시각만으로는
     * 알아볼 수 없게 된다({@code SnapshotExporter} 클래스 Javadoc의 두 번째 규칙).
     */
    @Test
    @DisplayName("내용이 같으면 다시 쓰지 않는다 — 켤 때마다 파일이 바뀌면 진짜 변경을 못 알아본다")
    void doesNotRewriteWhenUnchanged() throws Exception {
        given(setting(Domain.NETWORK, true, 0, "네트워크", null));

        assertThat(exporter.export(tempDir)).isTrue();
        assertThat(exporter.export(tempDir)).as("두 번째 호출은 아무것도 하지 않아야 한다").isFalse();
    }

    /* ── 테스트 재료 ─────────────────────────────────────────── */

    private void given(DomainSetting... settings) {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(settings));
    }

    private DomainSettingsFile read() throws Exception {
        return objectMapper.readValue(
                tempDir.resolve(DomainSettingExporter.FILE_NAME).toFile(), DomainSettingsFile.class);
    }

    private DomainSetting setting(Domain domain, boolean enabled, int sortOrder, String displayName, String hint) {
        return DomainSetting.initial(domain, enabled, sortOrder, displayName, hint);
    }
}
