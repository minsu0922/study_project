package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.dto.ExistingDocumentsFile;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DomainTitle.labeled}가 관리자 화면이 실제로 쓰는 길에서도 새 이름을 쓰는지 — Task 4
 * 1차 코드 리뷰에서 지적받은 네 곳 중 하나(나머지 셋은 {@code DomainRenameIntegrationTest}에 있다).
 *
 * <h2>왜 별도 클래스인가</h2>
 *
 * <p>{@code DomainTitle.labeled(DomainCatalog)}의 유일한 호출부는
 * {@link ExistingDocumentsExporter}이고, 그 클래스가 실제로 파일을 쓰는 {@code export(Path)}는
 * <b>패키지 전용</b>이다(테스트 전용 시그니처 — 그 메서드 Javadoc 참고. 앱 코드는 설정된
 * 디렉터리로만 도는 {@code run(ApplicationArguments)}를 쓴다). {@code DomainRenameIntegrationTest}는
 * 생성기의 패키지 전용 {@code buildPrompt}에 닿으려고 {@code llm.client}에 있으므로, 이 클래스는
 * 그와 반대 이유로 {@code llm.service}에 둔다 — 두 패키지 전용 메서드를 한 클래스에서 동시에
 * 호출할 방법이 없다.
 *
 * <h2>왜 문자열을 직접 넘기지 않고 내보내기를 거치는가</h2>
 *
 * <p>{@code labeled(catalog)}에 이름을 문자열로 만들어 넘기는 테스트는 "인자로 준 문자열이
 * 그대로 나온다"만 증명한다 — 그 이름이 <b>카탈로그(=DB)에서 왔다</b>는 것은 증명하지 못한다.
 * 여기서는 관리자 화면이 실제로 문서를 승인하는 길({@link AdminDocumentService#create})로
 * 문서를 만들고, 스냅샷을 내보내는 실제 길({@link ExistingDocumentsExporter#export})을 태워
 * 나온 파일을 읽는다 — 누군가 이 메서드를 {@code DefaultDomains.displayName}으로 되돌리면
 * 이 테스트가 옛 이름으로 실패한다.
 */
@SpringBootTest
@Transactional
class DomainRenameDocumentTitleIntegrationTest {

    @Autowired
    private DomainSettingService settings;
    @Autowired
    private ExistingDocumentsExporter exporter;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("이름을 바꾸면 기존 문서 스냅샷 제목의 [분야] 표시도 새 이름이다")
    void exportedTitleUsesRenamedDomain() throws Exception {
        rename(TestDomains.DS_ALGORITHM, "자료구조 실전");
        String slug = "domain-rename-title-" + UUID.randomUUID().toString().substring(0, 8);
        adminDocumentService.create(new AdminDocumentRequest(
                TestDomains.DS_ALGORITHM, "정렬 알고리즘 비교", slug, "본문입니다.", null, List.of()));

        assertThat(exporter.export(tempDir))
                .as("문서가 방금 생겼으니 스냅샷이 갱신돼야 한다")
                .isTrue();

        ExistingDocumentsFile file = objectMapper.readValue(
                tempDir.resolve(ExistingDocumentsExporter.FILE_NAME).toFile(), ExistingDocumentsFile.class);

        assertThat(file.titles())
                .as("labeled()가 DefaultDomains 고정값으로 되돌아가면 '[자료구조]'로 나온다")
                .contains("[자료구조 실전] 정렬 알고리즘 비교");
    }

    /** 켜짐·힌트는 그대로 두고 이름만 바꾼다 — 화면의 "저장" 버튼과 같은 경로. */
    private void rename(DomainCode code, String name) {
        DomainSetting s = settings.findAll().stream()
                .filter(r -> r.getDomain().equals(code)).findFirst().orElseThrow();
        settings.edit(code, new AdminDomainSettingRequest(s.isEnabled(), name, s.getHint()));
    }
}
