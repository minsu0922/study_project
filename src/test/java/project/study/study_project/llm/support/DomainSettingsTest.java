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

    /**
     * <b>Task 7(2026-09-22)에서 뜻이 바뀐 테스트다.</b> 예전 이름은 {@code unknownDomainNameIsSkipped}
     * 였고 {@code FRONTEND_CS}(기본 11개에서 빠진, 형식은 멀쩡한 옛 분야명)를 썼다. 그런데
     * {@link DomainSettings#parseDomain}이 이제 "기본 분야에 있는가"를 더 이상 묻지 않는다 —
     * 등록부가 DB로 넘어간 지금, 이 파일에 적힌 분야는 <b>파일이 곧 등록부</b>라 그 자체로
     * 유효하다({@code parseDomain} Javadoc 참고). 그래서 {@code FRONTEND_CS}는 더 이상 걸러지지
     * 않는다 — 지금 이 클래스가 여전히 걸러야 하는 것은 <b>형식 자체가 틀린</b> 값(대문자로
     * 시작하지 않거나 하이픈이 섞인 값 등)뿐이라, 그 경우로 바꿔 "한 줄이 깨져도 나머지는
     * 산다"는 같은 성질을 계속 지킨다.
     */
    @Test
    @DisplayName("형식이 틀린 분야 이름은 그 줄만 버린다 — 나머지 설정은 살린다")
    void malformedDomainNameIsSkipped(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), """
                {"note":"","domains":[
                  {"domain":"frontend-cs","enabled":true,"sortOrder":0,"displayName":"형식 오류","hint":null},
                  {"domain":"OS","enabled":true,"sortOrder":1,"displayName":"운영체제","hint":null}
                ]}""");

        assertThat(DomainSettings.read(dir).batchDomains()).containsExactly(TestDomains.OS);
    }

    /**
     * Task 7의 핵심 성질 — 화면에서 추가한 분야(기본 11개에 없는 코드)가 배치까지 닿는지를
     * 이 클래스 수준에서 직접 본다. {@code MESSAGING}은 {@link TestDomains}에도 없는, <b>이
     * 파일에만</b> 적힌 새 분야다 — 그런데도 걸러지지 않고 그대로 읽혀야 한다.
     */
    @Test
    @DisplayName("파일에 새 분야가 있으면 배치가 그 분야를 안다 — 화면에서 추가한 분야가 배치까지 닿는다")
    void fileDefinedDomainIsKnownToBatch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DomainSettings.FILE_NAME), """
                {"note":"","domains":[
                  {"domain":"MESSAGING","enabled":true,"sortOrder":0,"displayName":"메시징·비동기","hint":"큐·이벤트"},
                  {"domain":"OS","enabled":true,"sortOrder":1,"displayName":"운영체제","hint":null}
                ]}""");
        DomainSettings s = DomainSettings.read(dir);
        assertThat(s.enabled()).containsExactly(DomainCode.of("MESSAGING"), TestDomains.OS);
        assertThat(s.displayName(DomainCode.of("MESSAGING"))).isEqualTo("메시징·비동기");
    }

    @Test
    @DisplayName("빈 설정의 힌트는 내장값 — 설정이 없던 때와 같은 프롬프트가 나간다")
    void emptySettingsFallBackToBuiltInHints(@TempDir Path dir) {
        assertThat(DomainSettings.read(dir).hints().rawHintFor(TestDomains.SYSTEM_DESIGN))
                .isEqualTo(DomainHints.BUILT_IN.rawHintFor(TestDomains.SYSTEM_DESIGN));
    }
}
