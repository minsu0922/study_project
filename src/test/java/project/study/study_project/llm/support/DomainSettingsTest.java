package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치가 설정을 읽는 길. <b>읽기에 실패해도 배치는 돌아야 한다</b> — 이미 지불한 API 요금이
 * 설정 파일 오타 하나로 버려지면 안 된다. 그래서 모든 실패는 "빈 설정"으로 떨어지고,
 * 부르는 쪽이 폴백(application.yml → enum 전체)으로 이어 간다.
 */
class DomainSettingsTest {

    @Test
    @DisplayName("파일을 읽어 켜진 분야를 순서대로 준다")
    void readsEnabledDomainsInOrder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), """
                {"note":"","domains":[
                  {"domain":"OS","enabled":true,"sortOrder":0,"displayName":"운영체제","hint":null},
                  {"domain":"NETWORK","enabled":true,"sortOrder":1,"displayName":"네트워크","hint":"TCP 위주"},
                  {"domain":"CLOUD_INFRA","enabled":false,"sortOrder":2,"displayName":"클라우드","hint":null}
                ]}""");

        DomainSettings settings = DomainSettings.read(dir);

        assertThat(settings.batchDomains()).containsExactly(TestDomains.OS, TestDomains.NETWORK);
        assertThat(settings.hints().rawHintFor(TestDomains.NETWORK)).isEqualTo("TCP 위주");
    }

    @Test
    @DisplayName("파일이 없으면 빈 설정 — 부르는 쪽이 yml 폴백으로 간다")
    void missingFileGivesEmptySettings(@TempDir Path dir) {
        DomainSettings settings = DomainSettings.read(dir);

        assertThat(settings.isEmpty()).isTrue();
        assertThat(settings.batchDomains()).isEmpty();
    }

    @Test
    @DisplayName("깨진 파일도 빈 설정 — 오타 하나로 그날 생성이 통째로 죽지 않는다")
    void brokenFileGivesEmptySettings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), "{ 이건 JSON이 아니다");

        assertThat(DomainSettings.read(dir).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("모르는 분야 이름은 그 줄만 버린다 — 나머지 설정은 살린다")
    void unknownDomainNameIsSkipped(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), """
                {"note":"","domains":[
                  {"domain":"FRONTEND_CS","enabled":true,"sortOrder":0,"displayName":"옛 분야","hint":null},
                  {"domain":"OS","enabled":true,"sortOrder":1,"displayName":"운영체제","hint":null}
                ]}""");

        assertThat(DomainSettings.read(dir).batchDomains()).containsExactly(TestDomains.OS);
    }

    @Test
    @DisplayName("빈 설정의 힌트는 내장값 — 설정이 없던 때와 같은 프롬프트가 나간다")
    void emptySettingsFallBackToBuiltInHints(@TempDir Path dir) {
        assertThat(DomainSettings.read(dir).hints().rawHintFor(TestDomains.SYSTEM_DESIGN))
                .isEqualTo(DomainHints.BUILT_IN.rawHintFor(TestDomains.SYSTEM_DESIGN));
    }
}
