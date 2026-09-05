package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.support.GenerationSchedule;
import project.study.study_project.llm.support.ProblemItemRule;
import project.study.study_project.llm.support.TopicQueue;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * 아래 날짜표 테스트들이 쓰는 앵커 = 에포크 = 앵커가 없던 시절의 위상(2026-09-02 신설).
     *
     * <p>실제 설정값(2026-09-03)으로 옮겨 적지 않았다. 이 값들은 <b>2026-08-13 스케줄링 버그를
     * 잡을 때 손으로 계산한 표</b>이고, 옛 규칙으로 되돌리면 실패하는 것까지 확인하며 만든
     * 증거다. 위상을 바꿔 다시 적으면 그 증거가 사라진다. 설정값이 제대로 배선됐는지는
     * {@link #configuredAnchorIsADocumentDay()}가 따로 본다.
     */
    private static final LocalDate ANCHOR = GenerationSchedule.DEFAULT_ANCHOR;

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

    /* ══ 문서를 만들 분야 (2026-08-13 발견한 버그) ══════════════ */

    /**
     * {@code batch-domains}와 같은 순서·개수. 실제 설정을 그대로 써야 버그가 재현된다 —
     * 후보가 8개일 때 "4의 배수 mod 8 = 0 아니면 4"라는 산술이 성립하기 때문이다.
     */
    private static final List<Domain> CANDIDATES = List.of(
            Domain.NETWORK, Domain.OS, Domain.DATABASE, Domain.DS_ALGORITHM,
            Domain.SYSTEM_DESIGN, Domain.SECURITY, Domain.LANGUAGE_RUNTIME, Domain.BACKEND_FRAMEWORK);

    /**
     * <b>이 프로젝트에서 실제로 터진 버그를 못 박는 테스트다.</b>
     *
     * <p>문서 생성만 옛 규칙({@code cellFor})을 쓰고 있었다. 문서일은 에포크일이 4의 배수인 날인데
     * {@code cellFor}는 분야를 {@code 에포크일 mod 8}로 고르므로, 나머지가 <b>0 아니면 4</b>밖에
     * 안 나온다 — 후보 8개 중 두 개만 무한 반복되고 나머지 여섯 분야는 개념 문서를 영영 못 받았다.
     *
     * <p>게다가 문제일에는 분야를 문서 쪽으로 맞추므로(정렬 장치) <b>문제까지 그 두 분야에 갇힌다.</b>
     * 즉 이 한 줄이 기능 전체의 커버리지를 25%로 떨어뜨리고 있었다.
     *
     * <p><b>왜 이 형태로 검사하나.</b> "{@code planFor}를 호출한다"를 확인하는 것으로는 부족하다 —
     * 그건 구현을 베끼는 테스트라 다음에 또 다른 함수로 갈아 끼우면 그대로 통과한다. 대신
     * <b>결과가 만족해야 할 성질</b>(한 바퀴에 모든 분야가 한 번씩)을 검사한다. 규칙을 어떻게
     * 구현하든 이 성질이 깨지면 실패한다.
     */
    @Test
    @DisplayName("한 바퀴 돌면 모든 분야가 개념 문서를 한 번씩 받는다 — 옛 규칙이면 8개 중 2개만 돈다")
    void documentDomainCoversEveryCandidateInOnePass() {
        LocalDate start = LocalDate.of(2026, 8, 11); // 실제 첫 주기의 0일차
        int onePass = GenerationSchedule.CYCLE_DAYS * CANDIDATES.size(); // 4 × 8 = 32일

        List<Domain> documentDomains = new ArrayList<>();
        for (int i = 0; i < onePass; i++) {
            LocalDate date = start.plusDays(i);
            if (GenerationSchedule.planFor(date, CANDIDATES, ANCHOR).documentDay()) {
                documentDomains.add(DraftGeneratorCli.documentDomain(date, CANDIDATES, null, ANCHOR));
            }
        }

        assertThat(documentDomains)
                .as("32일이면 문서일은 8번")
                .hasSize(CANDIDATES.size())
                .as("여덟 분야가 정확히 한 번씩 — 버그 상태에서는 NETWORK·SYSTEM_DESIGN만 4번씩 나왔다")
                .containsExactlyInAnyOrderElementsOf(CANDIDATES);
    }

    /**
     * 위 테스트가 성질을 본다면 이건 <b>실물 날짜</b>를 본다. 버그를 발견할 때 손으로 계산해 둔 표를
     * 그대로 옮긴 것이라, 실패했을 때 "어느 날 무엇이 나와야 하는데 무엇이 나왔다"가 바로 읽힌다.
     */
    @Test
    @DisplayName("문서일마다 주기가 정한 분야가 나온다 — 버그 당시엔 NETWORK·SYSTEM_DESIGN만 번갈아 나왔다")
    void documentDomainMatchesTheCycleOnRealDates() {
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 11), CANDIDATES, null, ANCHOR))
                .as("버그 당시 실제 값: SYSTEM_DESIGN").isEqualTo(Domain.OS);
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 15), CANDIDATES, null, ANCHOR))
                .as("버그 당시 실제 값: NETWORK").isEqualTo(Domain.DATABASE);
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 19), CANDIDATES, null, ANCHOR))
                .as("버그 당시 실제 값: SYSTEM_DESIGN").isEqualTo(Domain.DS_ALGORITHM);
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 23), CANDIDATES, null, ANCHOR))
                .as("버그 당시 실제 값: NETWORK").isEqualTo(Domain.SYSTEM_DESIGN);
    }

    /**
     * 문서와 문제가 <b>같은 분야</b>를 가리켜야 한 주기가 성립한다. 이 둘이 어긋난 것이 버그의 본질이라,
     * 짝이 맞는지를 직접 확인한다 — 어긋나면 정렬 장치가 매일 발동해 증상을 가려 버린다.
     */
    @Test
    @DisplayName("문서일의 분야 = 뒤따르는 사흘 문제의 분야 — 이 짝이 어긋난 것이 버그였다")
    void documentDomainMatchesTheProblemDaysThatFollow() {
        LocalDate documentDay = LocalDate.of(2026, 8, 15);
        Domain forDocument = DraftGeneratorCli.documentDomain(documentDay, CANDIDATES, null, ANCHOR);

        for (int i = 1; i <= 3; i++) {
            GenerationSchedule.Plan problemDay =
                    GenerationSchedule.planFor(documentDay.plusDays(i), CANDIDATES, ANCHOR);
            assertThat(problemDay.domain())
                    .as("%d일차 문제의 분야", i)
                    .isEqualTo(forDocument);
            assertThat(problemDay.documentDate())
                    .as("%d일차가 가리키는 근거 문서 날짜", i)
                    .isEqualTo(documentDay);
        }
    }

    @Test
    @DisplayName("수동으로 분야를 지정하면 주기를 무시한다 — 워크플로에서 직접 고른 값이 가장 세다")
    void manualDomainBeatsTheCycle() {
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 15), CANDIDATES, "SECURITY", ANCHOR))
                .isEqualTo(Domain.SECURITY);
    }

    @Test
    @DisplayName("빈 문자열은 지정 안 한 것으로 본다 — 워크플로 입력을 비우면 이렇게 넘어온다")
    void blankDomainFallsBackToTheCycle() {
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 15), CANDIDATES, "   ", ANCHOR))
                .isEqualTo(Domain.DATABASE);
    }

    /* ══ 근거 문서 지목 (--document-date) ═══════════════════════ */

    /**
     * 2026-08-29에 붙인 옵션. 배경은 {@code DraftGeneratorCli#findSourceDocument} 주석에 있다 —
     * 근거 문서가 {@code --date}의 주기에 묶여 있어, 주기의 세 날짜를 다 쓴 문서는 남은 난이도를
     * 채울 방법이 없었다.
     *
     * <p><b>왜 한 줄짜리를 테스트하나.</b> 틀렸을 때 아무도 안 죽기 때문이다. 옵션을 무시하면
     * 문제는 정상적으로 5개 나오고 job도 초록불인데, 근거만 <b>엉뚱한 문서</b>다. 파일 안의
     * {@code documentSlug}를 열어 봐야 알 수 있고, 그때는 이미 요금이 나간 뒤다.
     */
    @Test
    @DisplayName("문서를 지목하면 그 날짜를 쓴다 — 주기가 정한 값을 이긴다")
    void pinnedDocumentDateBeatsTheCycle() {
        // 2026-09-20은 주기상 근거 문서가 2026-09-19지만, 지목한 09-08을 따라야 한다
        GenerationSchedule.Plan plan = GenerationSchedule.planFor(LocalDate.of(2026, 9, 20), CANDIDATES, ANCHOR);

        assertThat(DraftGeneratorCli.resolveDocumentDate(
                Map.of(DraftGeneratorCli.DOCUMENT_DATE_OPT, "2026-09-08"), plan))
                .isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    @DisplayName("지목하지 않으면 주기가 정한 문서를 쓴다 — 예약 실행 경로는 그대로다")
    void unpinnedDocumentDateFollowsTheCycle() {
        GenerationSchedule.Plan plan = GenerationSchedule.planFor(LocalDate.of(2026, 9, 20), CANDIDATES, ANCHOR);

        assertThat(DraftGeneratorCli.resolveDocumentDate(Map.of(), plan))
                .isEqualTo(plan.documentDate());
    }

    /**
     * 워크플로는 입력이 비어도 {@code --document-date=}를 <b>항상</b> 붙여 보낸다. 빈 값을
     * "지정함"으로 세면 {@code LocalDate.parse("")}가 터져 예약 실행이 매일 실패한다.
     * 빈 값을 버리는 것은 {@code parseArgs}의 몫이라, 그 계약을 여기서 함께 못 박는다.
     */
    @Test
    @DisplayName("빈 값은 지정 안 한 것으로 본다 — 워크플로가 늘 붙여 보내는 형태")
    void blankDocumentDateIsIgnored() {
        Map<String, String> opts = DraftGeneratorCli.parseArgs(
                new String[]{"--date=2026-09-20", "--document-date="});

        assertThat(opts).doesNotContainKey(DraftGeneratorCli.DOCUMENT_DATE_OPT);
    }

    /* ══ 생성 개수 (--count) ════════════════════════════════════ */

    /**
     * 관리자 API는 {@code @Max(10)}으로 11을 거부하는데 이 진입점에는 상한이 없어,
     * 워크플로 수동 입력의 {@code 500}이 그대로 요금이 됐다. 검증이 없는 쪽이 하필
     * 사람이 손으로 숫자를 적어 넣는 쪽이었다(2026-08-29).
     */
    /** 난이도별 배분이 없던 시절과 같은 상황 — 그 경로가 그대로 도는지부터 지킨다. */
    private static int count(Map<String, String> opts, int fallback) {
        return DraftGeneratorCli.resolveCount(opts, null, null, fallback);
    }

    @Test
    @DisplayName("옵션이 없으면 설정값을 쓴다 — 예약 실행의 평소 경로")
    void countFallsBackToTheConfiguredValue() {
        assertThat(count(Map.of(), 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("옵션이 있으면 그 값을 쓴다")
    void countOptionWins() {
        assertThat(count(Map.of("count", "3"), 5)).isEqualTo(3);
    }

    @Test
    @DisplayName("상한을 넘으면 거부한다 — 잘라 쓰면 50을 적은 사람이 50이 나온 줄 안다")
    void countAboveTheCapIsRejected() {
        assertThatThrownBy(() -> count(Map.of("count", "500"), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--count");
    }

    @Test
    @DisplayName("0과 음수도 거부한다 — '만들지 않겠다'가 아니라 오타다")
    void countBelowOneIsRejected() {
        assertThatThrownBy(() -> count(Map.of("count", "0"), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> count(Map.of("count", "-1"), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("숫자가 아니면 무엇을 받았는지 알려 준다 — NumberFormatException만 뜨면 원인을 못 찾는다")
    void countMustBeANumber() {
        assertThatThrownBy(() -> count(Map.of("count", "다섯"), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다섯");
    }

    /**
     * <b>2026-09-05 — 난이도별 개수.</b>
     *
     * <p>초·중·고급이 모두 5개라 1:1:1로 쌓이던 것을 7·5·3으로 바꿨다. 여기서 지키는 것은
     * <b>해석 순서</b>다 — 옵션 &gt; 난이도별 &gt; {@code batch-count}. 순서가 뒤집히면 조용히
     * 틀린다: 난이도별이 옵션을 이기면 손으로 지목한 개수가 안 나오고, 폴백이 난이도별을 이기면
     * 설정을 고쳐도 늘 5개가 나온다. 둘 다 예외 없이 <b>그럴듯한 개수</b>가 나오므로
     * 사람이 파일을 세어 보기 전에는 모른다.
     */
    @Test
    @DisplayName("난이도별 개수를 쓴다 — 초급이 가장 많고 고급이 가장 적어야 한다")
    void countComesFromTheDifficultySpec() {
        String spec = "BEGINNER=7,INTERMEDIATE=5,ADVANCED=3";

        assertThat(DraftGeneratorCli.resolveCount(Map.of(), spec, Difficulty.BEGINNER, 5))
                .isEqualTo(7);
        assertThat(DraftGeneratorCli.resolveCount(Map.of(), spec, Difficulty.ADVANCED, 5))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("--count는 난이도별 배분을 이긴다 — 사람이 고른 것이 규칙을 이긴다")
    void countOptionBeatsTheDifficultySpec() {
        assertThat(DraftGeneratorCli.resolveCount(
                Map.of("count", "2"), "BEGINNER=7", Difficulty.BEGINNER, 5))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("배분에 없는 난이도는 batch-count로 내려간다 — 설정을 지운 사람이 지운 대로 돌아야 한다")
    void countFallsBackWhenTheDifficultyIsAbsent() {
        assertThat(DraftGeneratorCli.resolveCount(Map.of(), "BEGINNER=7", Difficulty.ADVANCED, 5))
                .isEqualTo(5);
        assertThat(DraftGeneratorCli.resolveCount(Map.of(), null, Difficulty.ADVANCED, 5))
                .isEqualTo(5);
    }

    /**
     * 옵션만 검증하면 {@code batch-count: 100}이라는 설정 오타가 예약 실행에서 매일 조용히
     * 통과한다. 어느 경로로 들어왔든 같은 문을 지나야 하고, 메시지는 <b>어디를 고쳐야 하는지</b>
     * 를 말해야 한다 — "--count가 잘못됐다"고 하면 손대지도 않은 옵션을 찾게 된다.
     */
    @Test
    @DisplayName("설정값이 범위를 벗어나도 거부하고, 고칠 곳을 알려 준다")
    void configuredCountIsCheckedToo() {
        assertThatThrownBy(() -> count(Map.of(), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-count");
    }

    /* ══ 결과 파일 이름 (--suffix) ══════════════════════════════ */

    /**
     * 2026-08-29에 붙인 옵션. 파일 이름이 곧 날짜였고 그 날짜가 곧 주기 순번이라,
     * <b>손으로 한 칸을 채우면 그 날짜의 예약 실행이 죽었다</b>. 보안 문서 두 편을 채우느라
     * 여덟 날짜를 쓴 결과 이후 30일 중 11일이 조용히 건너뛰기가 됐다.
     */
    @Test
    @DisplayName("접미사가 없으면 예전 그대로 — 예약 실행의 이름은 달라지지 않는다")
    void noSuffixKeepsThePlainDateName() {
        assertThat(DraftGeneratorCli.outFileName(LocalDate.of(2026, 8, 29), null))
                .isEqualTo("2026-08-29.json");
    }

    @Test
    @DisplayName("접미사를 주면 날짜 뒤에 붙는다 — 날짜가 앞이라 흡수 순서(이름 오름차순)가 유지된다")
    void suffixGoesAfterTheDate() {
        assertThat(DraftGeneratorCli.outFileName(LocalDate.of(2026, 8, 29), "csrf-beg"))
                .isEqualTo("2026-08-29-csrf-beg.json");
    }

    /**
     * 이 값은 워크플로의 수동 입력으로 들어와 <b>파일 경로가 된다</b>. 검증 없이 이어 붙이면
     * 저장소 밖에 쓰거나, {@code _}로 시작해 흡수에서 제외되는(그래서 영영 안 들어오는)
     * 파일을 만들 수 있다. 통과 목록 방식이라 새로운 공격 형태가 나와도 자동으로 막힌다.
     */
    @Test
    @DisplayName("경로를 벗어나는 접미사는 거부한다 — 이 값이 파일 경로가 되기 때문")
    void suffixCannotEscapeTheDirectory() {
        LocalDate date = LocalDate.of(2026, 8, 29);

        assertThatThrownBy(() -> DraftGeneratorCli.outFileName(date, "../../etc/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DraftGeneratorCli.outFileName(date, "a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DraftGeneratorCli.outFileName(date, "_hidden"))
                .as("_로 시작하면 흡수가 건너뛴다 — 만들어도 영영 안 들어온다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 접미사와 대문자·공백은 거부한다 — 조용히 고쳐 쓰면 찾는 파일과 실제 파일이 달라진다")
    void suffixMustBeLowercaseAndNonEmpty() {
        LocalDate date = LocalDate.of(2026, 8, 29);

        assertThatThrownBy(() -> DraftGeneratorCli.outFileName(date, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DraftGeneratorCli.outFileName(date, "XSS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DraftGeneratorCli.outFileName(date, "a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DraftGeneratorCli.outFileName(date, "-x"))
                .as("하이픈으로 시작하면 2026-08-29--x.json이 되어 읽기 나쁘다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 워크플로는 입력이 비어도 {@code --suffix=}를 항상 붙여 보낸다. 빈 값을 "지정함"으로 세면
     * 예약 실행이 매일 검증에 걸려 죽는다 — {@code --document-date}와 같은 계약이다.
     */
    @Test
    @DisplayName("빈 값은 지정 안 한 것으로 본다 — 워크플로가 늘 붙여 보내는 형태")
    void blankSuffixIsIgnored() {
        Map<String, String> opts = DraftGeneratorCli.parseArgs(
                new String[]{"--date=2026-08-29", "--suffix="});

        assertThat(opts).doesNotContainKey(DraftGeneratorCli.SUFFIX_OPT);
        assertThat(DraftGeneratorCli.outFileName(LocalDate.of(2026, 8, 29), opts.get(DraftGeneratorCli.SUFFIX_OPT)))
                .isEqualTo("2026-08-29.json");
    }

    /* ══ 스냅샷 낡음 경고 ═══════════════════════════════════════ */

    /**
     * 스냅샷 파일은 로컬 앱이 갱신하고 <b>사람이 커밋해야</b> 배치에 반영된다. 커밋을 잊으면
     * 배치가 옛 회피 목록으로 돌아 <b>이미 있는 문제가 또 나온다</b> — 에러는 안 난다.
     *
     * <p>배치는 저장소에 커밋된 파일을 읽으므로 그 파일의 {@code exportedAt}이 곧
     * <b>마지막으로 커밋된 시점</b>이다. 앱에 git을 심지 않고도 같은 것을 알 수 있다.
     */
    @Test
    @DisplayName("2주 넘게 그대로면 낡은 것으로 본다 — 커밋을 잊은 신호")
    void detectsStaleSnapshot() {
        LocalDate today = LocalDate.of(2026, 8, 30);
        assertThat(DraftGeneratorCli.isStaleSnapshot("2026-08-10", today))
                .as("20일 전").isTrue();
    }

    /**
     * 스냅샷은 <b>내용이 바뀔 때만</b> 갱신된다. 검수를 안 했으면 거절 사례도 안 늘어나므로
     * 며칠 그대로인 것은 정상이다 — 여기서 경고하면 매일 울리고, 그러면 사람이 경고를
     * 무시하게 된다(진짜 경고까지 함께 묻힌다).
     */
    @Test
    @DisplayName("2주 안이면 조용하다 — 며칠 그대로인 것은 정상이라 경고하면 안 된다")
    void freshSnapshotIsNotStale() {
        LocalDate today = LocalDate.of(2026, 8, 30);
        assertThat(DraftGeneratorCli.isStaleSnapshot("2026-08-30", today)).as("오늘").isFalse();
        assertThat(DraftGeneratorCli.isStaleSnapshot("2026-08-20", today)).as("10일 전").isFalse();
        assertThat(DraftGeneratorCli.isStaleSnapshot("2026-08-16", today))
                .as("경계 — 정확히 %d일 전은 아직 아니다", DraftGeneratorCli.SNAPSHOT_STALE_DAYS).isFalse();
    }

    /**
     * <b>확실할 때만 울린다.</b> 날짜를 못 읽는 것은 "오래됐다"는 증거가 아니다. 여기서 낡음으로
     * 처리하면 파일이 깨진 날마다 엉뚱한 경고가 뜨고, 사람은 곧 경고 전체를 흘려보내게 된다.
     */
    @Test
    @DisplayName("날짜를 읽을 수 없으면 경고하지 않는다 — 오탐이 경고를 무력화한다")
    void unreadableDateNeverWarns() {
        LocalDate today = LocalDate.of(2026, 8, 30);
        assertThat(DraftGeneratorCli.isStaleSnapshot(null, today)).as("필드 없음").isFalse();
        assertThat(DraftGeneratorCli.isStaleSnapshot("  ", today)).as("빈 값").isFalse();
        assertThat(DraftGeneratorCli.isStaleSnapshot("어제", today)).as("날짜가 아님").isFalse();
        assertThat(DraftGeneratorCli.isStaleSnapshot("2026-13-45", today)).as("있을 수 없는 날짜").isFalse();
    }

    @Test
    @DisplayName("ISO 시각 형태도 읽는다 — 스냅샷마다 날짜만 쓰기도, 시각까지 쓰기도 한다")
    void acceptsIsoTimestamp() {
        assertThat(DraftGeneratorCli.isStaleSnapshot("2026-08-01T22:07:51.460797951Z",
                LocalDate.of(2026, 8, 30))).isTrue();
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

    /* ══ 수확량 점검 ═══════════════════════════════════════════ */

    /**
     * <b>실제로 겪은 사고를 그대로 못 박는다.</b> 2026-08-14 배치는 5개를 요청해 3개를 받았고,
     * 그중 하나가 지문·해설이 빈 채 보기만 있는 껍데기였다. 실제로 검수함에 들어간 건 2개뿐인데
     * job은 초록불로 끝났다 — 당시 방어선이 "목록이 통째로 비었는가"만 봤기 때문이다.
     *
     * <p>이런 종류의 실패는 <b>사람이 나중에 세어 보고서야</b> 드러난다. 그래서 세는 일을
     * 코드가 하게 하고, 그 세는 코드가 옳은지를 여기서 못 박는다.
     */
    @Test
    @DisplayName("지문 없는 껍데기는 유효에서 빠진다 — 2026-08-14 배치 재현(5개 요청, 3개 응답, 2개 유효)")
    void countsHuskAsDefect() {
        List<GeneratedProblemItem> problems = List.of(
                multipleChoice("캐시 페네트레이션은?", "존재하지 않는 키는 캐시에 채울 값이 없다"),
                multipleChoice("핫 키는?", "샤딩만으로는 해결되지 않는다"),
                // 껍데기: 보기는 멀쩡한데 지문·해설이 빈 문자열이다(구조화 출력이 필수 필드를
                // 빈 값으로 채워 보낸 형태 — GeneratedProblemItem 주석)
                new GeneratedProblemItem("", "", "", fourChoices()));

        DraftGeneratorCli.YieldCheck yield =
                DraftGeneratorCli.checkYield(problems, 5, ProblemType.MULTIPLE_CHOICE, null);

        assertThat(yield.received()).isEqualTo(3);
        assertThat(yield.usable()).as("껍데기는 흡수 단계에서 버려지므로 유효가 아니다").isEqualTo(2);
        assertThat(yield.isShort()).isTrue();
        assertThat(yield.defects()).singleElement().asString()
                .contains("3번")
                .contains("지문이 비어 있음");
    }

    @Test
    @DisplayName("요청한 만큼 다 오면 부족이 아니다 — 평소의 성공 경로")
    void fullYieldIsNotShort() {
        List<GeneratedProblemItem> problems = List.of(
                multipleChoice("문제 1", goodExplanation()),
                multipleChoice("문제 2", goodExplanation()));

        DraftGeneratorCli.YieldCheck yield =
                DraftGeneratorCli.checkYield(problems, 2, ProblemType.MULTIPLE_CHOICE, null);

        assertThat(yield.usable()).isEqualTo(2);
        assertThat(yield.isShort()).isFalse();
        assertThat(yield.defects()).isEmpty();
        assertThat(yield.warnings()).as("규약도 분량도 지킨 결과에는 아무 소리도 나면 안 된다")
                .isEmpty();
    }

    /**
     * 모델이 요청보다 <b>많이</b> 주는 것은 부족이 아니다. 여기서 부족으로 판정하면 멀쩡한 날에
     * 경고가 뜨고, 그런 경고가 몇 번 반복되면 사람이 진짜 경고까지 무시하게 된다
     * (스냅샷 경고를 14일로 잡은 것과 같은 판단).
     */
    @Test
    @DisplayName("요청보다 많이 와도 부족이 아니다 — 오탐이 경고를 무력화한다")
    void extraItemsAreNotShort() {
        List<GeneratedProblemItem> problems = List.of(
                multipleChoice("문제 1", "해설 1"),
                multipleChoice("문제 2", "해설 2"),
                multipleChoice("문제 3", "해설 3"));

        assertThat(DraftGeneratorCli.checkYield(problems, 2, ProblemType.MULTIPLE_CHOICE, null)
                .isShort()).isFalse();
    }

    /**
     * 해설이 빈 문제는 <b>흡수를 통과한다</b>(퀴즈로는 성립하므로). 그래서 유효 개수에는 넣고
     * 경고만 따로 붙인다 — 여기서 유효에서 빼면 경고가 말하는 개수와 실제 검수함에 들어간
     * 개수가 어긋나서, 경고를 보고도 무엇을 믿어야 할지 알 수 없게 된다.
     */
    @Test
    @DisplayName("해설만 빈 문제는 유효로 세되 따로 알린다 — 흡수를 통과하기 때문")
    void blankExplanationCountsAsUsableButIsReported() {
        List<GeneratedProblemItem> problems = List.of(
                multipleChoice("해설이 있는 문제", goodExplanation()),
                multipleChoice("해설이 없는 문제", ""));

        DraftGeneratorCli.YieldCheck yield =
                DraftGeneratorCli.checkYield(problems, 2, ProblemType.MULTIPLE_CHOICE, null);

        assertThat(yield.usable()).isEqualTo(2);
        assertThat(yield.isShort()).isFalse();
        assertThat(yield.defects()).isEmpty();
        assertThat(yield.warnings()).singleElement().asString()
                .contains("해설 없음").contains("해설이 없는 문제");
    }

    @Test
    @DisplayName("객관식 정답이 1개가 아니면 유효에서 빠진다 — 흡수 규칙과 같은 자로 잰다")
    void countsChoiceRuleViolationAsDefect() {
        List<GeneratedProblemItem> problems = List.of(
                new GeneratedProblemItem("정답이 둘인 문제", "", "해설", List.of(
                        new GeneratedProblemItem.GeneratedChoice("가", true),
                        new GeneratedProblemItem.GeneratedChoice("나", true),
                        new GeneratedProblemItem.GeneratedChoice("다", false),
                        new GeneratedProblemItem.GeneratedChoice("라", false))));

        DraftGeneratorCli.YieldCheck yield =
                DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE, null);

        assertThat(yield.usable()).isZero();
        assertThat(yield.defects()).singleElement().asString()
                .contains("보기 4개, 정답 2개");
    }

    /* ── 품질 경고 ─────────────────────────────────────────────
     * 프롬프트에 숫자로 적어 둔 규칙이 지켜지지 않는데 아무도 모르던 것을 여기서 센다.
     * 전부 <경고>이고 흡수는 통과한다 — 멀쩡한 문제를 요금까지 내고 버릴 이유가 없다. */

    /**
     * 실측이 근거다. 프롬프트는 "해설은 400~700자"라고 적어 뒀는데 2026-08-13 배치는
     * 5개가 <b>전부</b> 359~395자였고, 검사하는 곳이 없어 아무도 몰랐다.
     */
    @Test
    @DisplayName("해설이 기준보다 짧으면 알린다 — 08-13 배치는 5개 전부 미달이었고 아무도 몰랐다")
    void warnsOnShortExplanation() {
        List<GeneratedProblemItem> problems = List.of(
                multipleChoice("짧은 해설", "가".repeat(ProblemItemRule.EXPLANATION_MIN - 1)));

        DraftGeneratorCli.YieldCheck yield =
                DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE, null);

        assertThat(yield.usable()).as("짧아도 퀴즈로는 성립하므로 버리지 않는다").isEqualTo(1);
        // 이 재료는 글자만 채운 해설이라 오답 인용 경고에도 걸린다(2026-08-25 신설).
        // 그건 이 테스트가 재는 것이 아니므로 걸러 낸다 — 여기서 세면 검사가 하나 늘 때마다
        // 상관없는 테스트가 따라 깨진다.
        // 숫자를 직접 적지 않는다 — EXPLANATION_MIN을 옮긴 날 이 테스트가 함께 따라와야 한다.
        // 2026-08-27에 실제로 400→200으로 옮겼고, 박아 둔 "399자"가 그때 깨졌다.
        assertThat(yield.warnings())
                .filteredOn(w -> w.contains("해설이 짧음"))
                .singleElement().asString()
                .contains("%d자".formatted(ProblemItemRule.EXPLANATION_MIN - 1));
    }

    /**
     * <b>사람 눈으로는 영영 안 걸리는 결함.</b> 정답 위치 편향을 고치면서 보기를 내보낼 때
     * 섞기로 했는데(커밋 9a4fd6b), 해설이 "2번 보기는"이라고 쓰면 섞인 뒤 엉뚱한 보기를
     * 가리킨다. 검수자는 <b>섞이기 전</b> 화면을 보므로 번호가 맞아 보이고, 학습자만
     * 어긋난 해설을 읽는다. 지금까지 사고가 안 난 것은 모델이 마침 번호를 안 썼기 때문이다.
     *
     * <p><b>2026-08-25에 경고에서 차단으로 올렸다.</b> 경고로 두면 검수자가 "번호 맞는데?" 하며
     * 그대로 승인한다 — 판단할 여지가 없는 결함이라(섞기로 한 이상 <b>반드시</b> 틀린다)
     * 사람에게 물을 것이 아니라 기계가 버려야 한다. 그래서 이 테스트도 경고가 아니라
     * <b>버려졌는지</b>를 잰다.
     */
    @Test
    @DisplayName("해설이 보기를 번호로 가리키면 버린다 — 판단할 여지가 없으니 검수자에게 묻지 않는다")
    void discardsWhenExplanationPointsAtChoiceNumbers() {
        String explanation = "가".repeat(400) + " 2번 보기는 UDP의 특성을 TCP로 착각한 것이다.";
        List<GeneratedProblemItem> problems = List.of(multipleChoice("번호를 가리키는 해설", explanation));

        DraftGeneratorCli.YieldCheck yield =
                DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE, null);

        assertThat(yield.usable()).as("흡수도 같은 자로 재므로 여기서 세면 안 된다").isZero();
        assertThat(yield.defects()).singleElement().asString()
                .contains("보기를 번호로 가리킴")
                .as("걸린 자리가 보여야 프롬프트의 어느 지시가 안 먹었는지 짚을 수 있다")
                .contains("2번 보기는");
        assertThat(yield.warnings())
                .as("버린 항목이 경고 목록에도 오르면 같은 문제를 두 번 세는 셈이다")
                .noneSatisfy(w -> assertThat(w).contains("번호로"));
    }

    /**
     * 2026-08-16 4번이 실제로 이랬다 — "MVCC가 ... 대가로 <b>문서가 든</b> 것은?"
     * 문제는 데일리 퀴즈·복습에서 문서와 떨어져 노출되므로, 그 자리에는 가리킬 문서가 없다.
     */
    @Test
    @DisplayName("지문이 근거 문서를 가리키면 알린다 — 문서와 떨어져 노출되면 무슨 문서인지 알 수 없다")
    void warnsWhenQuestionReferencesTheSourceDocument() {
        List<GeneratedProblemItem> problems = List.of(
                multipleChoice("MVCC가 대기 없이 읽는 대신 치르는 대가로 문서가 든 것은?", goodExplanation()));

        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings()).singleElement().asString().contains("근거 문서를 가리킴");
    }

    /**
     * "상황 없이 한두 문장"은 세어 볼 수 없어 길이로 대신한다. 08-16 초급 지문은
     * 45·97·124·145·149자였고, <b>45자짜리 하나만</b> 상황 없는 정의 문제였다.
     */
    @Test
    @DisplayName("초급 지문이 길면 알린다 — 상황 서술이 붙으면 길이부터 늘어난다")
    void warnsOnLongBeginnerQuestion() {
        // 2026-08-16 초급 1번의 실제 지문(149자). 초급인데 "~했다"로 배경을 서술한다.
        String longQuestion = "재고 관리 트랜잭션에서 `SELECT * FROM item WHERE price < 1000` 을 두 번 실행했다. "
                + "두 번째 결과에는 첫 번째에 없던 행 두 개가 더 들어 있었다"
                + "(다른 트랜잭션이 그 사이 새 행을 삽입하고 커밋했다). 이 상황에 해당하는 이상 현상은?";
        List<GeneratedProblemItem> problems = List.of(multipleChoice(longQuestion, goodExplanation()));

        // 이 실제 지문에는 백틱도 들어 있어 마크다운 경고가 함께 난다 — 그것까지 세면
        // 이 테스트가 무엇을 재는지 흐려지므로, 길이 경고만 골라 본다.
        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.BEGINNER).warnings())
                .filteredOn(w -> w.contains("초급 지문이 김"))
                .singleElement().asString().contains("149자");

        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.INTERMEDIATE).warnings())
                .as("같은 149자 지문도 중급에서는 정상이다 — 중급의 기준은 %d자"
                        .formatted(ProblemItemRule.INTERMEDIATE_QUESTION_MAX))
                .filteredOn(w -> w.contains("지문이 김"))
                .isEmpty();
    }

    /**
     * <b>2026-08-18 신설.</b> 보기 5개짜리가 조용히 통과한 실물에서 나왔다.
     *
     * <p>버리지 않는 것은 의도다({@code MIN_CHOICES} 주석 — 보기가 3개여도 퀴즈로는 성립하고,
     * 4개를 강제하면 멀쩡한 문제가 버려진다). 그런데 그 주석은 "개수 어긋남은 검수자가 눈으로
     * 볼 몫"이라 해 놓고 <b>알려 주는 장치가 없었다</b>. 해설 길이도 지문 길이도 세면서 보기
     * 개수만 안 센 것은 빠뜨린 것이지 판단이 아니었다.
     *
     * <p>모자란 쪽을 경고하지 않는 것도 함께 못 박는다 — 그건 "재료가 모자라면 적게 내라"가
     * 허용한 결과일 수 있어서, 경고하면 지시를 잘 따른 문항에 매번 울린다.
     */
    @Test
    @DisplayName("보기가 4개를 넘으면 알린다 — 버리지는 않지만 검수자는 알아야 한다")
    void warnsWhenChoiceCountExceedsExpected() {
        GeneratedProblemItem fiveChoices = withTitle(new GeneratedProblemItem("보기가 다섯 개인 문제는?", "",
                goodExplanation(), List.of(
                new GeneratedProblemItem.GeneratedChoice("보기1", true),
                new GeneratedProblemItem.GeneratedChoice("보기2", false),
                new GeneratedProblemItem.GeneratedChoice("보기3", false),
                new GeneratedProblemItem.GeneratedChoice("보기4", false),
                new GeneratedProblemItem.GeneratedChoice("보기5", false))));

        assertThat(DraftGeneratorCli.checkYield(List.of(fiveChoices), 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.INTERMEDIATE).warnings())
                .as("규약 위반이 아니라 통과했으므로, 알리지 않으면 아무도 모른다")
                // "보기가"로만 거르면 경고 뒤에 붙는 지문 조각("보기가 다섯 개인 문제는?")까지
                // 걸려 다른 경고가 함께 딸려 온다(2026-08-25에 실제로 그랬다). 문구로 좁힌다.
                .filteredOn(w -> w.contains("보기가 5개"))
                .singleElement().asString().contains("기준 4개");

        GeneratedProblemItem threeChoices = withTitle(new GeneratedProblemItem("보기가 세 개인 문제는?", "",
                goodExplanation(), List.of(
                new GeneratedProblemItem.GeneratedChoice("보기1", true),
                new GeneratedProblemItem.GeneratedChoice("보기2", false),
                new GeneratedProblemItem.GeneratedChoice("보기3", false))));

        assertThat(DraftGeneratorCli.checkYield(List.of(threeChoices), 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.INTERMEDIATE).warnings())
                .as("모자란 쪽은 '적게 내라'가 허용한 결과일 수 있다 — 경고하면 매번 울린다")
                .filteredOn(w -> w.contains("보기가"))
                .isEmpty();
    }

    /**
     * <b>2026-08-18 신설.</b> 전에는 중급 지문의 길이를 아예 재지 않았다 — 초급의 상한은
     * "상황이 붙었는지"를 재는 장치라 중급에는 필요 없다고 봤기 때문이다. 그런데 중급의 문제는
     * 상황의 <b>유무</b>가 아니라 <b>길이</b>였다. 사용자가 실물을 짚었다: 쓰레드와 static
     * 카운터를 대조하는 지문을 읽고 "사람은 무슨 상황인지 이해할 수가 없다".
     *
     * <p>길이는 결함의 <b>신호</b>일 뿐 결함 자체가 아니다. 진짜 결함("개념을 보여주려고
     * 지어낸 장치")은 기계가 판정할 수 없어 프롬프트 쪽에서 막고, 여기서는 검수자가 그 문항을
     * 들여다볼 이유만 만들어 준다. 그래서 차단이 아니라 경고다(초급과 같은 판단).
     */
    @Test
    @DisplayName("중급 지문이 상한을 넘으면 알린다 — 상황이 길면 무엇을 묻는지가 흐려진다")
    void warnsOnLongIntermediateQuestion() {
        String tooLong = "가".repeat(ProblemItemRule.INTERMEDIATE_QUESTION_MAX + 1) + "?";
        List<GeneratedProblemItem> problems = List.of(multipleChoice(tooLong, goodExplanation()));

        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.INTERMEDIATE).warnings())
                .filteredOn(w -> w.contains("중급 지문이 김"))
                .singleElement().asString()
                .contains("기준 %d자".formatted(ProblemItemRule.INTERMEDIATE_QUESTION_MAX));

        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.ADVANCED).warnings())
                .as("고급은 '이미 시도한 것'까지 적어야 해서 더 길다 — 같은 잣대를 대면 매번 울린다")
                .filteredOn(w -> w.contains("지문이 김"))
                .isEmpty();
    }

    /**
     * 보기 번호 지칭 검사의 <b>경계</b>. 처음 만든 정규식이 여기서 헛다리를 짚었다 —
     * 자바의 {@code \b}는 영숫자 기준이라 한글 앞에서도 경계가 잡혀서
     * "보기 <b>4개</b> 중"이 걸렸고, 맨 {@code [1-4]번}은 "<b>3번의</b> 왕복"을 잡았다.
     *
     * <p>이 경고는 "학습자에게만 어긋나 보인다"는 무서운 문구를 달고 나간다.
     * 헛울리면 다음부터 아무도 안 믿으므로, 놓치는 쪽을 택하고 그 선을 여기 못 박는다.
     */
    @Test
    @DisplayName("숫자가 보기를 가리키지 않으면 조용하다 — '보기 4개', '3번의 왕복'은 지칭이 아니다")
    void doesNotMistakePlainNumbersForChoiceReferences() {
        assertThat(choiceReferenceDefectsFor("네 보기 4개 중 하나만 옳다.")).isEmpty();
        assertThat(choiceReferenceDefectsFor("TCP는 3번의 왕복으로 연결을 맺는다.")).isEmpty();
        assertThat(choiceReferenceDefectsFor("격리 수준 4가지 중 2가 기본값이다.")).isEmpty();

        assertThat(choiceReferenceDefectsFor("2번 보기는 UDP의 특성이다.")).isNotEmpty();
        assertThat(choiceReferenceDefectsFor("보기 2번은 UDP의 특성이다.")).isNotEmpty();
        assertThat(choiceReferenceDefectsFor("첫 번째 보기는 정의가 다르다.")).isNotEmpty();
        assertThat(choiceReferenceDefectsFor("②는 격리 수준을 잘못 본 것이다."))
                .as("원문자는 해설에서 보기 말고 가리킬 것이 없다 — 단독으로도 잡는다")
                .isNotEmpty();
    }

    /**
     * <b>2026-08-17 평가에서 실제로 난 오탐.</b> 멀쩡한 해설 두 건이 걸렸는데, 걸린 자리는
     * 보기가 아니라 프롬프트가 <b>시킨</b> 마지막 줄이었다 — [해설] 절이 "다시 읽을 절을
     * 한 줄로 가리킨다"고 요구한다. 처음 정규식이 {@code 보기|선택지|항목}을 함께 받아
     * "2번 항목"을 보기 지칭으로 읽은 탓이다.
     *
     * <p>이 경고는 "학습자에게만 어긋나 보인다"는 무서운 문구를 달고 나간다.
     * 프롬프트를 지킨 해설이 매번 경고를 달면 다음부터 아무도 안 믿는다.
     */
    @Test
    @DisplayName("문서 항목을 가리키는 것은 보기 지칭이 아니다 — 프롬프트가 시킨 마지막 줄이 걸렸었다")
    void doesNotFlagReferencesToDocumentItems() {
        assertThat(choiceReferenceDefectsFor("(문서의 '언제 깨지는가' 2번 항목과 '흔한 오해 3'을 다시 읽어 보라)"))
                .isEmpty();
        assertThat(choiceReferenceDefectsFor("(문서의 '락 기반과 MVCC' 절과 '언제 깨지는가' 4번 항목을 다시 읽어 보라)"))
                .isEmpty();

        assertThat(choiceReferenceDefectsFor("\"복제 지연\"이라는 보기는 전제가 맞지 않는다."))
                .as("내용으로 인용한 것도 당연히 걸리면 안 된다")
                .isEmpty();
    }

    /**
     * 경고에 <b>걸린 자리</b>가 함께 실리는지. 2026-08-17 평가 실행에서 이게 없어
     * "해설이 보기를 번호로 가리킴 1건"만 뜨고, 정작 해설 500자 중 어디가 걸렸는지
     * 볼 방법이 없었다. 종류만 아는 경고는 결국 사람이 전문을 다시 읽게 만든다.
     */
    @Test
    @DisplayName("경고에 걸린 자리가 함께 실린다 — 종류만 알면 해설 전문을 다시 읽어야 한다")
    void warningCarriesTheOffendingSnippet() {
        assertThat(choiceReferenceDefectsFor("따라서 2번 보기는 UDP의 특성을 옮겨 온 것이다."))
                .singleElement().asString()
                .as("걸린 표현과 그 앞뒤가 보여야 어디를 고칠지 정해진다")
                .contains("2번 보기는")
                .as("전문이 아니라 조각이어야 한다 — 여러 건이 나면 목록이 안 읽힌다")
                .doesNotContain("해".repeat(60));
    }

    /** 해설 뒤에 붙여 분량 경고를 피하고, 보기 번호 지칭 경고만 남긴다. */
    /**
     * 보기 번호 지칭에 걸린 <b>차단</b> 목록. 2026-08-25에 경고에서 차단으로 올라가면서
     * {@code warnings()}가 아니라 {@code defects()}를 본다 — 걸린 항목은 이제 버려지므로
     * 경고 목록에는 아예 오르지 않는다.
     */
    private static List<String> choiceReferenceDefectsFor(String explanationTail) {
        List<GeneratedProblemItem> problems =
                List.of(multipleChoice("지문", goodExplanation() + " " + explanationTail));
        return DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE, null)
                .defects().stream().filter(w -> w.contains("번호로")).toList();
    }

    /**
     * <b>정답이 가장 긴 보기</b>인 상태. 실물 22문항 중 18개(82%)가 그랬다 —
     * 균등하면 25%다. 그 상태면 학습자가 지문을 읽지 않고 제일 긴 보기만 골라도 맞는다.
     *
     * <p>정답이 4번에 한 번도 없던 사고와 같은 종류인데, 고칠 방법이 다르다.
     * 위치는 내보낼 때 섞어서 없앴지만 <b>길이는 섞을 수 없다</b>.
     */
    @Test
    @DisplayName("정답이 유독 길면 알린다 — 실물 82%가 그랬고, 길이는 섞어서 고칠 수 없다")
    void warnsWhenTheCorrectChoiceIsTheLongest() {
        List<GeneratedProblemItem> problems = List.of(withTitle(new GeneratedProblemItem(
                "무엇인가?", "", goodExplanation(), List.of(
                new GeneratedProblemItem.GeneratedChoice("가".repeat(60), true),
                new GeneratedProblemItem.GeneratedChoice("나".repeat(30), false),
                new GeneratedProblemItem.GeneratedChoice("다".repeat(32), false),
                new GeneratedProblemItem.GeneratedChoice("라".repeat(34), false)))));

        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings()).singleElement().asString()
                .contains("정답이 가장 긴 보기")
                .as("몇 배인지 보여야 얼마나 줄일지 정해진다")
                .contains("2.00배");
    }

    /**
     * 문턱을 <b>둘 다</b> 넘어야 경고한다. 정답이 우연히 최장인 경우는 넷 중 하나꼴로 늘
     * 생기므로 그것만으로 울리면 4분의 1이 헛울리고, 비율만 보면 오답이 유독 긴
     * 멀쩡한 문제까지 걸린다.
     */
    @Test
    @DisplayName("정답이 최장이어도 편차가 작으면 조용하다 — 넷 중 하나는 늘 최장이다")
    void staysQuietWhenTheLongestAnswerIsOnlySlightlyLonger() {
        List<GeneratedProblemItem> problems = List.of(withTitle(new GeneratedProblemItem(
                "무엇인가?", "", goodExplanation(), List.of(
                new GeneratedProblemItem.GeneratedChoice("가".repeat(44), true),  // 1.26배
                new GeneratedProblemItem.GeneratedChoice("나".repeat(35), false),
                new GeneratedProblemItem.GeneratedChoice("다".repeat(40), false),
                new GeneratedProblemItem.GeneratedChoice("라".repeat(42), false)))));

        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings()).isEmpty();

        // 오답이 유독 긴 것은 찍히는 단서가 아니다 — 걸리면 안 된다.
        List<GeneratedProblemItem> longDistractor = List.of(withTitle(new GeneratedProblemItem(
                "무엇인가?", "", goodExplanation(), List.of(
                new GeneratedProblemItem.GeneratedChoice("가".repeat(30), true),
                new GeneratedProblemItem.GeneratedChoice("나".repeat(70), false),
                new GeneratedProblemItem.GeneratedChoice("다".repeat(32), false),
                new GeneratedProblemItem.GeneratedChoice("라".repeat(34), false)))));

        assertThat(DraftGeneratorCli.checkYield(longDistractor, 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings()).isEmpty();
    }

    /**
     * 지문·보기·해설은 화면에 <b>평문 그대로</b> 나간다({@code player.js}의 {@code escapeHtml}).
     * 백틱과 별표가 글자로 보인다. 문서 본문만 마크다운으로 렌더링되므로 헷갈리기 쉽다.
     */
    @Test
    @DisplayName("백틱·별표는 알린다, 줄바꿈·하이픈 목록은 놔둔다 — 후자는 화면에서 살릴 수 있다")
    void warnsOnMarkdownThatCannotBeRendered() {
        List<GeneratedProblemItem> withBacktick = List.of(
                multipleChoice("`SELECT * FROM item`을 두 번 실행하면?", goodExplanation()));
        assertThat(DraftGeneratorCli.checkYield(withBacktick, 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings()).singleElement().asString().contains("지문에 마크다운이 섞임");

        // 해설의 줄바꿈·하이픈 목록은 CSS(white-space: pre-wrap)로 살아난다 — 막으면 안 된다.
        List<GeneratedProblemItem> withList = List.of(multipleChoice("무엇인가?",
                goodExplanation() + "\n- 첫째 오답은 이런 오해다.\n- 둘째 오답은 저런 오해다."));
        assertThat(DraftGeneratorCli.checkYield(withList, 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings()).isEmpty();
    }

    /**
     * 오탐이 나면 경고가 매번 뜨고, 그러면 사람이 경고 자체를 안 보게 된다.
     * 이 저장소가 이미 겪은 실패 방식이라 정상 결과에 조용한지를 따로 못 박는다.
     */
    @Test
    @DisplayName("규약도 분량도 지킨 문제에는 경고가 없다 — 오탐이 경고를 무력화한다")
    void staysQuietOnGoodProblems() {
        List<GeneratedProblemItem> problems = List.of(
                multipleChoice("팬텀 리드란 무엇인가?", goodExplanation()));

        assertThat(DraftGeneratorCli.checkYield(problems, 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.BEGINNER).warnings()).isEmpty();
    }

    /* ── 근거 인용 ────────────────────────────────────────────
     * 판정 자체는 SourceQuoteRuleTest가 다 본다. 여기서 지키는 것은 <배선>이다 —
     * 규칙이 아무리 정확해도 수확량 점검이 부르지 않으면 Actions 요약에 한 줄도 안 뜨고,
     * 그러면 있으나 마나다. 문서를 넘기는 인자가 조용히 빠지는 것이 정확히 그 사고다. */

    @Test
    @DisplayName("근거 인용 경고가 수확량 점검을 타고 나온다 — 규칙만 있고 부르지 않으면 아무 데도 안 뜬다")
    void reportsSourceQuoteWarnings() {
        SourceDocument doc = new SourceDocument("time-wait", "TIME_WAIT",
                "# TIME_WAIT\n\n## 무엇인가\n연결을 닫은 쪽이 잠시 머무는 상태다.");
        GeneratedProblemItem strayQuote = withTitle(new GeneratedProblemItem(
                "TIME_WAIT이 오래 남으면 무엇이 마르는가?", "", goodExplanation(),
                List.of(new GeneratedProblemItem.GeneratedChoice("포트", true),
                        new GeneratedProblemItem.GeneratedChoice("메모리", false)),
                "이 문장은 저 문서에 없다."));

        assertThat(DraftGeneratorCli.checkYield(List.of(strayQuote), 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.BEGINNER, doc).warnings())
                .singleElement().asString().contains("문서에서 찾지 못함");

        assertThat(DraftGeneratorCli.checkYield(List.of(strayQuote), 1, ProblemType.MULTIPLE_CHOICE,
                Difficulty.BEGINNER).warnings())
                .as("문서를 안 넘긴 예전 호출은 그대로 조용해야 한다 — 폴백 날에 헛울리면 안 된다")
                .isEmpty();
    }

    /* ── 근거 문서에 오늘 캘 재료가 있는지 ─────────────────────
     * 2026-08-15 주기 재현. 문서는 멀쩡히 있고 본문도 비지 않았고 거절되지도 않았는데
     * <중급이 지목하는 두 절만> 없었다(절 이름을 문서 생성 뒤에 바꿔서, 커밋 2a5538c).
     * 기존 세 관문은 전부 통과하므로 아무도 몰랐고, 모델은 멈추는 대신 다른 절을 캔다 —
     * 그게 '언제 깨지는가'면 다음 날 고급이 빈손이 된다. 그 조용한 경로를 여기서 막는다. */

    @Test
    @DisplayName("오늘 난이도가 캘 절이 하나도 없으면 재료 없음 — 8/15 주기가 정확히 이 상태였다")
    void detectsMissingMaterialForDifficulty() {
        // 최상위 필수 절은 다 있지만 중급이 지목하는 두 곳이 없는 문서
        String doc = """
                # TIME_WAIT

                ## 무엇인가
                연결을 닫은 쪽이 잠시 머무는 상태다.

                ## 왜 필요한가
                늦게 도착한 패킷이 다음 연결을 오염시키지 않게 한다.

                ## 언제 깨지는가
                짧은 연결을 대량으로 열고 닫으면 포트가 마른다.

                ## 면접에서 이렇게 물어본다
                왜 2MSL인가?
                """;

        assertThat(DraftGeneratorCli.hasMaterialFor(doc, Difficulty.INTERMEDIATE))
                .as("'### 왜 이렇게 설계됐는가'도 '## 실무에서는 이렇게 쓴다'도 없다")
                .isFalse();
        assertThat(DraftGeneratorCli.hasMaterialFor(doc, Difficulty.BEGINNER))
                .as("초급 재료는 멀쩡하다 — 난이도별로 따로 봐야 한다")
                .isTrue();
        assertThat(DraftGeneratorCli.hasMaterialFor(doc, Difficulty.ADVANCED)).isTrue();
    }

    /**
     * 문턱을 "전부 있어야 통과"로 잡으면 재료가 멀쩡한 문서까지 폴백으로 버려진다.
     * 폴백이 잦아지면 근거 문서 구조(2단계) 자체가 헛돈다 — 오탐이 미탐보다 비싸다.
     */
    @Test
    @DisplayName("지목한 절 중 하나만 있어도 통과 — 전부 요구하면 멀쩡한 문서까지 폴백으로 버린다")
    void oneSectionIsEnough() {
        String onlySubheading = "## 무엇인가\n설명\n\n### 왜 이렇게 설계됐는가\n다른 선택지도 있었다.";
        String onlySection = "## 무엇인가\n설명\n\n## 실무에서는 이렇게 쓴다\n이렇게 쓴다.";

        assertThat(DraftGeneratorCli.hasMaterialFor(onlySubheading, Difficulty.INTERMEDIATE)).isTrue();
        assertThat(DraftGeneratorCli.hasMaterialFor(onlySection, Difficulty.INTERMEDIATE)).isTrue();
    }

    @Test
    @DisplayName("본문이나 난이도를 모르면 막지 않는다 — 확신 없이 버리면 그날 치가 근거 없이 날아간다")
    void doesNotBlockWhenNothingToJudge() {
        assertThat(DraftGeneratorCli.hasMaterialFor(null, Difficulty.BEGINNER)).isTrue();
        assertThat(DraftGeneratorCli.hasMaterialFor("## 무엇인가", null)).isTrue();
    }

    /* ══ 주제 대기열의 분야 우선순위(2026-08-19) ═══════════════ */

    /**
     * 대기열에 "@Transactional 전파 속성"을 적어 뒀는데 그날 주기가 운영체제 차례면,
     * 스프링 문서가 <b>운영체제 칸</b>에 들어간다. 그 어긋남은 한 편으로 끝나지 않는다 —
     * 이어지는 사흘의 문제가 그 문서를 근거로 만들어지므로 나흘이 통째로 엉킨다.
     * 근거 문서가 주기 분야를 이기는 것과 같은 원칙이다({@code alignDomainWithDocument}).
     */
    @Test
    @DisplayName("대기열 주제의 분야가 주기 분야를 이긴다 — 이름표보다 실제 내용이 우선이다")
    void topicQueueDomainBeatsCycleDomain() {
        TopicQueue.Picked picked =
                new TopicQueue.Picked(0, Domain.BACKEND_FRAMEWORK, "@Transactional 전파 속성");

        assertThat(DraftGeneratorCli.topicDomain(Domain.OS, null, picked))
                .isEqualTo(Domain.BACKEND_FRAMEWORK);
    }

    @Test
    @DisplayName("수동으로 분야를 지정하면 대기열보다 그것이 이긴다 — 사람의 가장 최근 의사 표시다")
    void manualDomainBeatsTopicQueue() {
        TopicQueue.Picked picked = new TopicQueue.Picked(0, Domain.BACKEND_FRAMEWORK, "AOP 프록시");

        assertThat(DraftGeneratorCli.topicDomain(Domain.OS, "OS", picked)).isEqualTo(Domain.OS);
    }

    @Test
    @DisplayName("대기열이 비면 주기 분야를 그대로 쓴다 — 대기열을 안 채워도 파이프라인은 예전대로 돈다")
    void keepsCycleDomainWhenQueueEmpty() {
        assertThat(DraftGeneratorCli.topicDomain(Domain.OS, null, null)).isEqualTo(Domain.OS);
    }

    /**
     * <b>2026-08-21 신설.</b> 목록 제목은 <b>버리지 않고 알리기만</b> 한다 — 제목이 없어도 퀴즈는
     * 성립하고(화면이 지문으로 대신한다), 검수자가 그 자리에서 고칠 수 있는 한 줄이라
     * 버리는 비용이 고치는 비용보다 훨씬 크다. 그래서 {@code defectOf}가 아니라 여기서 잰다.
     *
     * <p>그런데 알리지 않으면 제목 없는 문제가 그대로 쌓이고, 그러면 목록이 지문 조각으로
     * 채워져 이 컬럼(V13)을 만든 이유 자체가 사라진다. 보기 개수를 세지 않아 5개짜리가 조용히
     * 통과했던 것과 같은 실패 방식이다 — 검사하지 않는 규칙은 없는 규칙이다.
     */
    @Test
    @DisplayName("제목이 없으면 알린다 — 버리진 않지만 그대로 승인되면 목록이 지문 조각으로 찬다")
    void warnsWhenTitleIsMissing() {
        // 짧은 생성자는 title을 빈 문자열로 채운다 — "모델이 제목을 안 낸" 상태의 재현이다
        GeneratedProblemItem noTitle = new GeneratedProblemItem("무엇인가?", "", goodExplanation(), fourChoices());

        assertThat(DraftGeneratorCli.checkYield(List.of(noTitle), 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings())
                .as("규약 위반이 아니라 통과했으므로, 알리지 않으면 아무도 모른다")
                .filteredOn(w -> w.contains("제목"))
                .singleElement().asString().contains("[제목 없음]");
    }

    /**
     * 상한을 넘긴 제목은 목록에서 잘린다. 잘린 제목은 없느니만 못하다 — 앞부분만 보고 고르려던
     * 사람이 결국 문제를 열어 봐야 한다.
     *
     * <p>DB 컬럼은 120자인데(V13) 여기서 재는 값은 40자다. 둘이 다른 것은 의도다:
     * DB는 사고를 막는 선까지만 걸고 품질 기준은 여기서 잰다. 같게 맞추면 한 글자 넘겼다고
     * <b>저장 자체가 실패</b>한다.
     */
    @Test
    @DisplayName("제목이 상한을 넘으면 알린다 — 목록에서 잘린 제목은 고르는 데 도움이 안 된다")
    void warnsOnLongTitle() {
        GeneratedProblemItem longTitle = new GeneratedProblemItem("무엇인가?", "", goodExplanation(),
                fourChoices(), "", "제".repeat(ProblemItemRule.TITLE_MAX + 1));

        assertThat(DraftGeneratorCli.checkYield(List.of(longTitle), 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings())
                .filteredOn(w -> w.contains("제목"))
                .singleElement().asString()
                .contains("제목이 김")
                .as("몇 자인지 보여야 얼마나 줄일지 정해진다")
                .contains("%d자".formatted(ProblemItemRule.TITLE_MAX + 1));

        GeneratedProblemItem exact = new GeneratedProblemItem("무엇인가?", "", goodExplanation(),
                fourChoices(), "", "제".repeat(ProblemItemRule.TITLE_MAX));
        assertThat(DraftGeneratorCli.checkYield(List.of(exact), 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings())
                .as("딱 맞는 것은 지시를 지킨 것이다 — 걸리면 지시대로 쓴 제목이 매번 경고를 단다")
                .filteredOn(w -> w.contains("제목"))
                .isEmpty();
    }

    /**
     * 제목이 질문문이면 지문을 한 번 더 쓴 것이라 목록에서 아무것도 더 알려 주지 않는다.
     * "무엇이 원인인가?"가 열 줄 늘어선 목록에서는 어느 것을 풀지 정할 수 없다.
     *
     * <p>물음표 하나로 재는 것은 조잡해 보이지만, 이 저장소가 여러 번 확인한 것이 그것이다 —
     * <b>세어 볼 수 있는 기준만 실제로 지켜진다</b>. "명사구인가"는 기계가 판정할 수 없고,
     * 물음표는 판정할 수 있다. 명사구인데 물음표를 붙이는 경우는 없으므로 헛울리지도 않는다.
     */
    @Test
    @DisplayName("제목이 물음표로 끝나면 알린다 — 물음을 늘어놓은 목록에서는 고를 수가 없다")
    void warnsWhenTitleIsAQuestion() {
        GeneratedProblemItem questionTitle = new GeneratedProblemItem("무엇인가?", "", goodExplanation(),
                fourChoices(), "", "이 상황의 원인으로 가장 적절한 것은?");

        assertThat(DraftGeneratorCli.checkYield(List.of(questionTitle), 1, ProblemType.MULTIPLE_CHOICE, null)
                .warnings())
                .filteredOn(w -> w.contains("제목"))
                .singleElement().asString()
                .contains("물음표로 끝남")
                .as("어느 제목이 걸렸는지 보여야 고칠지 말지가 한눈에 정해진다")
                .contains("이 상황의 원인으로 가장 적절한 것은?");
    }

    /* ── 테스트 재료 ─────────────────────────────────────────── */

    /**
     * 품질 검사를 <b>통과하는</b> 해설. 길이를 여기서 한 번만 정해 두는 이유는,
     * 각 테스트가 제 숫자를 적으면 {@link ProblemItemRule#EXPLANATION_MIN}을 올린 날
     * 그 테스트들이 조용히 "경고가 나는 해설"로 바뀌기 때문이다.
     *
     * <p><b>2026-08-25에 길이만으로는 부족해졌다.</b> 해설 검사가 둘 늘었다 —
     * 오답을 몇 개나 짚었는가(따옴표 인용 개수), 그리고 다시 읽을 문서 절을 가리켰는가.
     * 글자만 채운 해설은 이제 그 둘에 걸린다. 이 헬퍼가 있는 이유가 바로 이 상황이므로
     * (검사가 늘 때마다 열세 개 테스트를 따라 고치지 않기 위해) 여기서 함께 만족시킨다.
     *
     * <p>인용문이 {@link #fourChoices()}의 오답 텍스트와 글자까지 같지는 않다. 검사가 세는 것은
     * <b>따옴표로 묶인 인용의 개수</b>이지 보기와의 일치가 아니기 때문이다 — 그 한계는
     * {@code QUOTED_CHOICE_REFERENCE} 주석에 적혀 있고, 여기서 그 사실을 한 번 더 드러낸다.
     */
    private static String goodExplanation() {
        String body = "정답인 이유는 원리가 이 상황에 그대로 적용되기 때문이다. "
                + "\"오답 보기 하나\"는 두 개념을 뒤섞은 오해다. "
                + "\"오답 보기 둘\"은 조건이 다른 경우를 옮겨 온 것이다. "
                + "\"오답 보기 셋\"은 원인과 결과를 뒤집어 읽은 것이다. "
                + "(문서의 '무엇인가' 절을 다시 읽어 보라) ";
        return body + "해".repeat(ProblemItemRule.EXPLANATION_MIN + 20 - body.length());
    }

    /**
     * 품질 검사를 <b>통과하는</b> 목록 제목. {@link #goodExplanation()}과 같은 이유로 한 곳에 둔다 —
     * 각 테스트가 제 문자열을 적으면 {@link ProblemItemRule#TITLE_MAX}를 줄인 날 조용히 경고가 난다.
     * 물음표로 끝나지 않는 명사구여야 한다는 것도 여기서 한 번만 지킨다.
     */
    private static String goodTitle() {
        return "제목";
    }

    private static GeneratedProblemItem multipleChoice(String question, String explanation) {
        // 객관식의 answer는 빈 문자열이 정상이다 — 정답은 보기 쪽에 있다(docs/01)
        return withTitle(new GeneratedProblemItem(question, "", explanation, fourChoices()));
    }

    /**
     * 제목만 채워 넣는다 — 제목과 무관한 테스트들이 "제목 없음" 경고에 걸리지 않게 한다.
     *
     * <p>이렇게 감싸는 이유: 짧은 생성자(제목 없는 4·5인자)는 <b>제목을 안 냈을 때</b>를
     * 재현하는 데 그대로 필요하다. 그 생성자를 없애 버리면 "제목 없음"을 검사할 방법이 사라진다.
     */
    private static GeneratedProblemItem withTitle(GeneratedProblemItem item) {
        return new GeneratedProblemItem(item.question(), item.answer(), item.explanation(),
                item.choices(), item.sourceQuote(), goodTitle());
    }

    private static List<GeneratedProblemItem.GeneratedChoice> fourChoices() {
        return List.of(
                new GeneratedProblemItem.GeneratedChoice("정답 보기", true),
                new GeneratedProblemItem.GeneratedChoice("오답 1", false),
                new GeneratedProblemItem.GeneratedChoice("오답 2", false),
                new GeneratedProblemItem.GeneratedChoice("오답 3", false));
    }

    /**
     * {@code --problem-type} — 2026-08-31 신설.
     *
     * <p><b>빈 값 처리가 이 옵션에서 가장 중요하다.</b> 워크플로의 수동 실행에서 아무것도 고르지
     * 않으면 {@code --problem-type=}가 그대로 넘어온다. 그걸 {@code valueOf("")}에 넣으면
     * 배치가 통째로 죽는데, 그건 <b>아무것도 안 고른 평범한 실행</b>에서 나는 사고다.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("--problem-type")
    class ResolveProblemType {

        @Test
        @DisplayName("비어 있으면 객관식 — 예약 실행은 이 옵션을 안 쓰므로 지금까지와 똑같이 동작한다")
        void defaultsToMultipleChoice() {
            assertThat(DraftGeneratorCli.resolveProblemType(null)).isEqualTo(ProblemType.MULTIPLE_CHOICE);
            assertThat(DraftGeneratorCli.resolveProblemType("")).isEqualTo(ProblemType.MULTIPLE_CHOICE);
            assertThat(DraftGeneratorCli.resolveProblemType("   ")).isEqualTo(ProblemType.MULTIPLE_CHOICE);
        }

        @Test
        @DisplayName("새 유형을 이름으로 고를 수 있다 — 소문자와 앞뒤 공백도 받는다")
        void acceptsNewTypes() {
            assertThat(DraftGeneratorCli.resolveProblemType("MATCHING")).isEqualTo(ProblemType.MATCHING);
            assertThat(DraftGeneratorCli.resolveProblemType(" ordering ")).isEqualTo(ProblemType.ORDERING);
            assertThat(DraftGeneratorCli.resolveProblemType("ox")).isEqualTo(ProblemType.OX);
        }

        @Test
        @DisplayName("서술형과 오타는 값을 읽는 자리에서 막는다 — API를 부르기 전에 끝내야 요금이 안 나간다")
        void rejectsUnusableValues() {
            assertThatThrownBy(() -> DraftGeneratorCli.resolveProblemType("ESSAY"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("자동채점");
            assertThatThrownBy(() -> DraftGeneratorCli.resolveProblemType("MATCHNG"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("MATCHING");
        }
    }

    /**
     * 근거 문서를 고르는 자리가 <b>유형 재료까지</b> 보는지 — 2026-09-01.
     *
     * <p>규칙 자체는 {@code TypeMaterialRuleTest}가 지킨다. 여기서 보는 것은 <b>이 자리에서 그
     * 규칙을 실제로 부르는가</b>다. 호출을 빠뜨려도 컴파일은 되고, 배치도 초록불로 끝난다 —
     * 대신 재료 없는 문서로 짝짓기 다섯 개가 요금을 다 쓰고 나온다. 조용한 실패라 테스트로만 잡힌다.
     *
     * <p>{@code null}을 돌려주는 것이 곧 두 갈래가 된다: 문서를 지목한 실행은 위쪽에서
     * <b>요금 0으로 실패</b>하고, 예약 실행은 폴백으로 간다. 그래서 이 한 줄이 두 동작을 다 정한다.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("근거 문서 고르기 — 유형 재료")
    class FindSourceDocumentMaterial {

        private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

        /** 초급이 캘 절({@code ## 무엇인가})은 있고, 표 행 수만 갈아 끼우는 문서를 만든다. */
        private String documentWith(int tableRows) {
            StringBuilder md = new StringBuilder("# 제목\n\n## 무엇인가\n- **용어** — 뜻\n\n### 용어 한눈에\n\n| 용어 | 뜻 |\n|---|---|\n");
            for (int i = 1; i <= tableRows; i++) {
                md.append("| 용어").append(i).append(" | 뜻").append(i).append(" |\n");
            }
            return md.toString();
        }

        private java.nio.file.Path writeDocument(java.nio.file.Path outDir, String contentMd) throws Exception {
            java.nio.file.Path docDir = outDir.resolve("documents");
            java.nio.file.Files.createDirectories(docDir);
            var file = new project.study.study_project.llm.dto.GeneratedDocumentFile(
                    "테스트", DATE.toString(), DATE + "T00:00:00Z", Domain.NETWORK, "test-model",
                    new project.study.study_project.llm.client.GeneratedDocumentItem(
                            "제목", "test-slug", contentMd, List.of("net")), null);
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValue(docDir.resolve(DATE + ".json").toFile(), file);
            return outDir;
        }

        @Test
        @DisplayName("표가 모자란 문서로 짝짓기를 부르면 문서를 쓰지 않는다 — 지목 실행은 이걸로 요금 0에 멈춘다")
        void rejectsDocumentWithoutMatchingMaterial(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
                throws Exception {
            java.nio.file.Path outDir = writeDocument(tmp, documentWith(2));

            assertThat(DraftGeneratorCli.findSourceDocument(
                    outDir, DATE, Difficulty.BEGINNER, ProblemType.MATCHING)).isNull();
        }

        @Test
        @DisplayName("같은 문서라도 객관식이면 그대로 쓴다 — 막는 것은 짝짓기 하나뿐이다")
        void keepsDocumentForOtherTypes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
                throws Exception {
            java.nio.file.Path outDir = writeDocument(tmp, documentWith(2));

            assertThat(DraftGeneratorCli.findSourceDocument(
                    outDir, DATE, Difficulty.BEGINNER, ProblemType.MULTIPLE_CHOICE)).isNotNull();
        }

        @Test
        @DisplayName("표가 넉넉하면 짝짓기도 그 문서를 쓴다")
        void keepsDocumentWithEnoughRows(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
                throws Exception {
            java.nio.file.Path outDir = writeDocument(tmp, documentWith(4));

            assertThat(DraftGeneratorCli.findSourceDocument(
                    outDir, DATE, Difficulty.BEGINNER, ProblemType.MATCHING)).isNotNull();
        }
    }

    /* ══ 설정 배선 — 규칙이 아니라 <그것을 쓰는 쪽>을 본다 ══════ */

    /**
     * <b>이 저장소가 두 번 데인 자리다.</b> 2026-08-13에는 {@code planFor}에 테스트가 촘촘했는데
     * 정작 그것을 부르는 쪽이 옛 규칙을 쓰고 있어 커버리지가 25%로 떨어져 있었고, 아무도 몰랐다.
     * 앵커도 같은 모양의 함정을 갖는다 — {@code application.yml}에 날짜를 적어 두고 코드가 그것을
     * 안 읽으면, 배치는 <b>오류 없이</b> 옛 위상으로 계속 돈다.
     *
     * <p>그래서 실물 설정 파일을 읽어 확인한다: 거기 적힌 앵커가 정말 문서일인가.
     * 앵커를 잘못 적었거나(예: 문제일 날짜), CLI가 그 키를 안 읽게 되면 여기서 걸린다.
     */
    @Test
    @DisplayName("application.yml에 적힌 앵커는 실제로 문서일이다 — 적어 두고 안 읽으면 조용히 옛 위상으로 돈다")
    void configuredAnchorIsADocumentDay() throws Exception {
        Map<String, Object> generation = DraftGeneratorCli.readGenerationConfig();
        Object raw = generation.get("cycle-anchor");

        assertThat(raw).as("cycle-anchor 키가 사라지면 위상이 조용히 에포크로 돌아간다").isNotNull();
        // 문자열로 읽혀야 한다 — YAML이 맨날짜를 Date로 바꾸면 CLI의 파싱이 ClassCastException으로 죽는다
        assertThat(raw).as("따옴표로 감싸 문자열로 둘 것").isInstanceOf(String.class);

        LocalDate anchor = LocalDate.parse((String) raw);
        assertThat(GenerationSchedule.planFor(anchor, CANDIDATES, anchor).documentDay())
                .as("앵커 %s", anchor).isTrue();
    }

    /**
     * 화면(관리 콘솔 배치 탭)과 배치가 <b>같은 앵커</b>를 봐야 한다. 어긋나면 화면이
     * "오늘은 문서일"이라고 띄우는데 배치는 고급 문제를 만든다 — 어느 쪽이 맞는지
     * 알아내려면 두 코드를 다 읽어야 한다.
     */
    @Test
    @DisplayName("AdminBatchService의 기본 앵커가 GenerationSchedule의 기본값과 같다")
    void adminDefaultAnchorMatchesTheScheduleDefault() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/project/study/study_project/admin/service/AdminBatchService.java"));

        assertThat(source)
                .as("@Value의 기본값이 DEFAULT_ANCHOR(%s)와 달라지면 설정이 없을 때 화면과 배치가 갈린다",
                        GenerationSchedule.DEFAULT_ANCHOR)
                .contains("${llm.generation.cycle-anchor:" + GenerationSchedule.DEFAULT_ANCHOR + "}");
    }

    /* ── 2026-09-03: 난이도가 어느 편을 근거로 삼는가 ─────────────
     * 문서 한 편이 입문편·심화편 두 편으로 갈렸다. 고급만 심화편을 쓰고 나머지는 입문편을 쓴다.
     * 이 매핑이 어긋나면 실패가 조용하다 — 문제는 정상적으로 만들어지고, 며칠 뒤 사람이
     * "고급인데 왜 정의를 묻지?"를 알아차릴 때까지 간다. */

    @Test
    @DisplayName("고급은 심화편을, 초급·중급은 입문편을 근거로 삼는다")
    void picksEditionByDifficulty() {
        var beginner = new project.study.study_project.llm.client.GeneratedDocumentItem(
                "입문편", "thread-memory", "# 입문편\n\n## 무엇인가\n정의.", List.of("os"));
        var advanced = new project.study.study_project.llm.client.GeneratedDocumentItem(
                "심화편", "thread-memory-advanced", "# 심화편\n\n## 언제 깨지는가\n조건.", List.of("os"));
        var file = new project.study.study_project.llm.dto.GeneratedDocumentFile(
                "테스트", "2026-09-07", "2026-09-07T00:00:00Z", Domain.OS, "test-model",
                beginner, advanced);

        assertThat(DraftGeneratorCli.editionFor(file, Difficulty.BEGINNER)).isSameAs(beginner);
        assertThat(DraftGeneratorCli.editionFor(file, Difficulty.INTERMEDIATE)).isSameAs(beginner);
        assertThat(DraftGeneratorCli.editionFor(file, Difficulty.ADVANCED))
                .as("고급 재료(## 언제 깨지는가)는 심화편에만 있다")
                .isSameAs(advanced);
    }

    /**
     * 2026-09-03 이전 파일 15개에는 심화편 칸이 없다. 여기서 {@code null}을 돌려주면
     * 그 날짜들의 고급이 근거 없는 폴백으로 떨어지는데, 옛 문서에는 {@code ## 언제 깨지는가}가
     * 실제로 들어 있으므로 <b>입문편(옛 단일 문서)을 주는 편이 낫다</b>.
     */
    @Test
    @DisplayName("심화편이 없으면 고급도 입문편으로 돌아간다 — 옛 파일 15개에는 그 칸이 없다")
    void fallsBackToBeginnerWhenNoAdvancedEdition() {
        var only = new project.study.study_project.llm.client.GeneratedDocumentItem(
                "옛 단일 문서", "cache-strategy", "# 옛 문서\n\n## 언제 깨지는가\n조건.", List.of("cache"));
        var file = new project.study.study_project.llm.dto.GeneratedDocumentFile(
                "테스트", "2026-08-12", "2026-08-12T00:00:00Z", Domain.SYSTEM_DESIGN, "test-model",
                only, null);

        assertThat(DraftGeneratorCli.editionFor(file, Difficulty.ADVANCED)).isSameAs(only);
    }
}
