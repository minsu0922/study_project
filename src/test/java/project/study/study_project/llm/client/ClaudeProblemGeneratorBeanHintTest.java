package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.service.DomainSettingService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>스프링이 만든 생성기 빈</b>이 관리자 화면에서 고친 힌트를 싣는지 — 최종 리뷰 Important 2(2026-09-22).
 *
 * <h2>왜 이 테스트가 따로 있나</h2>
 *
 * <p>다른 프롬프트 테스트({@code ClaudeProblemGeneratorPromptTest}·{@code ClaudeDocumentGeneratorTest})는
 * 전부 생성기를 {@code new}로 만든다. 그래서 스프링 빈 경로는 한 번도 밟히지 않았고, 그 경로에
 * 버그가 있었다 — 빈이 한 인자 생성자로 만들어져 <b>늘 내장 힌트</b>를 썼다. 관리자 "생성 실행"·
 * 문서 업로드처럼 앱 안에서 생성하면 화면에서 고치거나 지운 힌트가 무시되고 옛 힌트가 나갔다.
 * 이 기능의 목적("재배포 없이 힌트를 고친다")이 앱 경로에서만 조용히 안 지켜진 셈이다.
 *
 * <p>같은 이유로 생성자 {@code @Autowired} 사고(커밋 9aaea73)도 프롬프트 테스트는 못 잡았다 —
 * 컨텍스트를 띄우는 이 테스트는 그 사고도 함께 잡는다.
 *
 * <h2>무엇을 보나</h2>
 *
 * <ul>
 *   <li>힌트를 바꾼 <b>직후</b>의 {@code buildPrompt}가 새 힌트를 싣는다 — 빈이 DB를 읽는가.
 *   <li>한 번 더 바꾸면 그 값으로 또 바뀐다 — 첫 호출에서 읽어 굳히지 않는가(굳히면 재시작해야 반영된다).
 * </ul>
 *
 * <p>힌트 문구는 내장값에 절대 없을 표지({@code 빈경로-표지})로 둔다. 옛 코드(내장 힌트 고정)라면
 * 이 표지가 프롬프트에 나올 길이 없어 첫 단언에서 바로 실패한다.
 *
 * <p>{@code @Transactional}이라 힌트 변경은 테스트 끝에 되돌려지고, 커밋이 없으니 파일 내보내기
 * ({@code DomainSettingExporter}, {@code AFTER_COMMIT})도 불리지 않는다.
 */
@SpringBootTest
@Transactional
class ClaudeProblemGeneratorBeanHintTest {

    @Autowired
    private ClaudeProblemGenerator problemGenerator;

    @Autowired
    private ClaudeDocumentGenerator documentGenerator;

    @Autowired
    private DomainSettingService domainSettingService;

    @Test
    @DisplayName("스프링 빈 문제 생성기는 화면에서 고친 힌트를 바로 다음 프롬프트에 싣는다")
    void problemGeneratorBeanReadsHintAtUseTime() {
        editHint(Domain.NETWORK, "빈경로-표지 첫째");

        assertThat(problemPrompt())
                .as("빈이 내장 힌트로 만들어졌다면 이 표지는 나올 수 없다")
                .contains("(빈경로-표지 첫째)");

        editHint(Domain.NETWORK, "빈경로-표지 둘째");

        assertThat(problemPrompt())
                .as("첫 호출에서 읽어 굳혔다면 재시작 전까지 첫째가 남는다")
                .contains("(빈경로-표지 둘째)")
                .doesNotContain("빈경로-표지 첫째");
    }

    @Test
    @DisplayName("스프링 빈 문서 생성기도 같은 공급자를 호출 시점에 읽는다")
    void documentGeneratorBeanReadsHintAtUseTime() {
        editHint(Domain.NETWORK, "빈경로-표지 문서");

        String prompt = documentGenerator.buildPrompt(Domain.NETWORK, null, List.of(), List.of());

        assertThat(prompt).contains("(빈경로-표지 문서)");
    }

    /** 8인자 — {@code ClaudeProblemGeneratorPromptTest}의 호출 모양과 같다. */
    private String problemPrompt() {
        return problemGenerator.buildPrompt(Domain.NETWORK, Difficulty.BEGINNER,
                ProblemType.MULTIPLE_CHOICE, 1, List.of(), List.of(), null, null);
    }

    /** 켜짐·이름은 그대로 두고 힌트만 바꾼다 — 화면의 "저장" 버튼과 같은 경로({@code DomainSettingService.edit}). */
    private void editHint(Domain domain, String hint) {
        DomainSetting current = domainSettingService.findAll().stream()
                .filter(s -> s.getDomain() == domain)
                .findFirst()
                .orElseThrow();
        domainSettingService.edit(domain,
                new AdminDomainSettingRequest(current.isEnabled(), current.getDisplayName(), hint));
    }
}
