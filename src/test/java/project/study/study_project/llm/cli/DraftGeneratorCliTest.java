package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Domain;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 중단 스위치의 진리표 테스트 — docs/14.
 *
 * <p><b>두 줄짜리 판단을 왜 테스트하나.</b> 이 로직이 틀렸을 때의 증상이 조용하기 때문이다.
 * {@code ||}를 {@code &&}로 잘못 쓰면 "켜 뒀는데도 매일 아무것도 안 하는" 상태가 되는데,
 * 워크플로는 초록불로 끝나고 에러도 없다 — 몇 주 뒤 "왜 문제가 안 들어오지?" 할 때까지 모른다.
 * 이 프로젝트가 이미 똑같은 방식으로 한 번 당했다(배치를 켰는데 초안 0건, docs/14).
 *
 * <p>그때의 교훈은 "조용히 아무것도 안 하는 상태를 만들 수 있는 코드는 크기와 무관하게
 * 못 박아 둔다"였고, 이 테스트가 그 실행이다.
 */
class DraftGeneratorCliTest {

    @Test
    @DisplayName("배치가 켜져 있으면 생성한다 — 평소의 예약 실행 경로")
    void generatesWhenEnabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(true, false)).isTrue();
    }

    @Test
    @DisplayName("배치를 끄면 생성하지 않는다 — 스위치의 본래 목적")
    void doesNotGenerateWhenDisabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(false, false)).isFalse();
    }

    @Test
    @DisplayName("꺼져 있어도 force면 생성한다 — 설정을 되돌리는 것을 잊는 사고를 막는 예외 구멍")
    void forceOverridesDisabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(false, true)).isTrue();
    }

    @Test
    @DisplayName("켜져 있는데 force를 줘도 평소와 같다 — force는 켜는 스위치가 아니라 우회로일 뿐")
    void forceIsHarmlessWhenEnabled() {
        assertThat(DraftGeneratorCli.shouldGenerate(true, true)).isTrue();
    }

    /* ══ 2단계: 주기 분야와 근거 문서 분야가 어긋날 때 ══════════ */

    /**
     * 실제로 겪을 상황이라 테스트로 못 박는다. 2026-08-11에 손으로 만든 문서(캐시 전략,
     * SYSTEM_DESIGN)가 OS 주기의 근거로 잡히는 배치가 예정돼 있었다.
     *
     * <p>맞추지 않으면 "운영체제 문제를 내라"와 "이 캐시 문서 안에서만 내라"가 한 프롬프트에
     * 함께 실린다. <b>모델은 오류를 내지 않는다</b> — 둘 중 하나를 무시하거나 섞은 문제를
     * 만들어 낸다. 조용히 결과만 나빠지는 종류라 사람이 알아채기까지 오래 걸린다.
     */
    @Test
    @DisplayName("주기 분야와 문서 분야가 다르면 문서 쪽으로 맞춘다 — 근거 문서가 곧 그 주기의 주제다")
    void alignsDomainWithDocumentWhenTheyDiffer() {
        assertThat(DraftGeneratorCli.alignDomainWithDocument(Domain.OS, Domain.SYSTEM_DESIGN))
                .isEqualTo(Domain.SYSTEM_DESIGN);
    }

    @Test
    @DisplayName("문서에 분야가 없으면(옛 형식) 주기 분야를 그대로 쓴다 — 알 수 없는 값 때문에 멀쩡한 분야를 버리지 않는다")
    void keepsPlanDomainWhenDocumentDomainMissing() {
        assertThat(DraftGeneratorCli.alignDomainWithDocument(Domain.OS, null))
                .isEqualTo(Domain.OS);
    }

    @Test
    @DisplayName("둘이 같으면 그대로 — 정상 주기에서는 아무 일도 일어나지 않는다")
    void keepsDomainWhenAlreadyMatching() {
        assertThat(DraftGeneratorCli.alignDomainWithDocument(Domain.SECURITY, Domain.SECURITY))
                .isEqualTo(Domain.SECURITY);
    }

    /* ══ 무엇을 만들지 결정 (batch-type) ═══════════════════════ */

    @Test
    @DisplayName("auto: 문서일엔 문서, 나머지 날엔 문제 — 기본 동작")
    void autoFollowsCycle() {
        assertThat(DraftGeneratorCli.decideAction(null, "auto", true))
                .isEqualTo(DraftGeneratorCli.BatchAction.DOCUMENT);
        assertThat(DraftGeneratorCli.decideAction(null, "auto", false))
                .isEqualTo(DraftGeneratorCli.BatchAction.PROBLEM);
    }

    @Test
    @DisplayName("설정이 비어 있어도 auto로 본다 — 설정 키가 없다고 배치가 멈추면 안 된다")
    void missingConfigBehavesAsAuto() {
        assertThat(DraftGeneratorCli.decideAction(null, null, true))
                .isEqualTo(DraftGeneratorCli.BatchAction.DOCUMENT);
        assertThat(DraftGeneratorCli.decideAction(null, "  ", false))
                .isEqualTo(DraftGeneratorCli.BatchAction.PROBLEM);
    }

    @Test
    @DisplayName("problem: 문서일에도 문제를 만든다 — 문서 생성을 완전히 끄는 스위치")
    void problemModeNeverMakesDocuments() {
        assertThat(DraftGeneratorCli.decideAction(null, "problem", true))
                .isEqualTo(DraftGeneratorCli.BatchAction.PROBLEM);
        assertThat(DraftGeneratorCli.decideAction(null, "problem", false))
                .isEqualTo(DraftGeneratorCli.BatchAction.PROBLEM);
    }

    /**
     * "매일 문서"가 아니라 "문서일에만"인 것이 핵심이다. 매일 만들면 요금이 네 배가 된다 —
     * 이 스위치는 "한쪽만 보고 싶다"는 도구이지 주기를 바꾸는 도구가 아니다.
     */
    @Test
    @DisplayName("document: 문서일에만 만들고 나머지 사흘은 아무것도 안 한다(요금 0)")
    void documentModeSkipsProblemDays() {
        assertThat(DraftGeneratorCli.decideAction(null, "document", true))
                .isEqualTo(DraftGeneratorCli.BatchAction.DOCUMENT);
        assertThat(DraftGeneratorCli.decideAction(null, "document", false))
                .as("쉬는 날은 실패가 아니라 정상 종료다").isEqualTo(DraftGeneratorCli.BatchAction.SKIP);
    }

    /**
     * 사람이 워크플로에서 직접 고른 값이 설정보다 세야 한다. 막히면 버그처럼 보이고,
     * 수동 실행은 그 한 번만 유효해 되돌리기를 잊는 사고도 없다.
     */
    @Test
    @DisplayName("수동 지정이 설정을 이긴다 — 문서만 만들도록 설정해 뒀어도 '문제'를 고르면 문제가 나온다")
    void manualTypeBeatsConfig() {
        assertThat(DraftGeneratorCli.decideAction("problem", "document", true))
                .isEqualTo(DraftGeneratorCli.BatchAction.PROBLEM);
        assertThat(DraftGeneratorCli.decideAction("document", "problem", false))
                .isEqualTo(DraftGeneratorCli.BatchAction.DOCUMENT);
    }

    @Test
    @DisplayName("수동 지정이 auto면 지정 안 한 것과 같다 — 워크플로가 빈 값으로 바꿔 넘기지만 직접 호출도 받아 준다")
    void manualAutoFallsThroughToConfig() {
        assertThat(DraftGeneratorCli.decideAction("auto", "problem", true))
                .isEqualTo(DraftGeneratorCli.BatchAction.PROBLEM);
        assertThat(DraftGeneratorCli.decideAction("auto", null, true))
                .isEqualTo(DraftGeneratorCli.BatchAction.DOCUMENT);
    }

    @Test
    @DisplayName("모르는 값은 auto로 본다 — 오타 하나로 배치가 통째로 멈추면 몇 주 뒤에야 알아차린다")
    void unknownValueFallsBackToAuto() {
        assertThat(DraftGeneratorCli.decideAction(null, "problems", true))
                .as("problem의 오타").isEqualTo(DraftGeneratorCli.BatchAction.DOCUMENT);
        assertThat(DraftGeneratorCli.decideAction(null, "DOCUMENT", false))
                .as("대소문자는 오타가 아니다 — 받아 준다").isEqualTo(DraftGeneratorCli.BatchAction.SKIP);
    }

    /**
     * <b>설정이 조용히 무시되는 것을 막는 테스트다.</b> 위 {@code decideAction}이 아무리 옳아도
     * {@code application.yml}의 키 이름이 코드가 읽는 이름과 다르면 값은 영영 전달되지 않는다.
     * 그때 배치는 오류 없이 auto로 돌기 때문에(모르는 값 = auto), 사용자는 "설정했는데 왜
     * 그대로지?"를 한참 뒤에야 알아차린다. 키 이름과 허용 값을 함께 못 박아 둔다.
     */
    @Test
    @DisplayName("application.yml의 batch-type 키가 CLI가 읽는 이름·값과 일치한다")
    @SuppressWarnings("unchecked")
    void configKeyMatchesWhatCliReads() throws Exception {
        Map<String, Object> generation;
        try (InputStream in = DraftGeneratorCli.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            generation = (Map<String, Object>) ((Map<String, Object>) root.get("llm")).get("generation");
        }

        assertThat(generation)
                .as("CLI는 llm.generation.batch-type을 읽는다 — 이름이 바뀌면 설정이 무시된다")
                .containsKey("batch-type");
        assertThat(String.valueOf(generation.get("batch-type")))
                .as("허용 값 밖이면 auto로 취급되어 설정한 의미가 사라진다")
                .isIn("auto", "problem", "document");
    }
}
