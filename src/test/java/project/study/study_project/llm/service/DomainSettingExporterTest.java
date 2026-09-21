package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.DomainSettingsFile;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DomainSettingExporterTest {

    @Autowired DomainSettingService service;
    @Autowired DomainSettingExporter exporter;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("켜짐·순서·이름·힌트를 그대로 싣는다 — 배치가 이 파일만 보고 돌 수 있어야 한다")
    void exportsEveryFieldTheBatchNeeds() {
        service.syncWithEnum();

        DomainSettingsFile file = exporter.snapshotForTest();

        assertThat(file.domains()).hasSize(Domain.values().length);
        DomainSettingsFile.Entry first = file.domains().get(0);
        assertThat(first.domain()).isEqualTo("NETWORK");
        assertThat(first.enabled()).isTrue();
        assertThat(first.sortOrder()).isZero();
        assertThat(first.displayName()).isEqualTo("네트워크");
    }

    @Test
    @DisplayName("꺼진 분야도 내보낸다 — 화면에서 다시 켤 때 이름·힌트가 남아 있어야 한다")
    void disabledDomainsAreExportedToo() {
        service.syncWithEnum();

        DomainSettingsFile file = exporter.snapshotForTest();

        assertThat(file.domains()).anyMatch(e -> e.domain().equals("CLOUD_INFRA") && !e.enabled());
    }
}
