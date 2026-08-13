package project.study.study_project.llm.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.support.GenerationSchedule;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
            if (GenerationSchedule.planFor(date, CANDIDATES).documentDay()) {
                documentDomains.add(DraftGeneratorCli.documentDomain(date, CANDIDATES, null));
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
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 11), CANDIDATES, null))
                .as("버그 당시 실제 값: SYSTEM_DESIGN").isEqualTo(Domain.OS);
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 15), CANDIDATES, null))
                .as("버그 당시 실제 값: NETWORK").isEqualTo(Domain.DATABASE);
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 19), CANDIDATES, null))
                .as("버그 당시 실제 값: SYSTEM_DESIGN").isEqualTo(Domain.DS_ALGORITHM);
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 23), CANDIDATES, null))
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
        Domain forDocument = DraftGeneratorCli.documentDomain(documentDay, CANDIDATES, null);

        for (int i = 1; i <= 3; i++) {
            GenerationSchedule.Plan problemDay =
                    GenerationSchedule.planFor(documentDay.plusDays(i), CANDIDATES);
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
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 15), CANDIDATES, "SECURITY"))
                .isEqualTo(Domain.SECURITY);
    }

    @Test
    @DisplayName("빈 문자열은 지정 안 한 것으로 본다 — 워크플로 입력을 비우면 이렇게 넘어온다")
    void blankDomainFallsBackToTheCycle() {
        assertThat(DraftGeneratorCli.documentDomain(LocalDate.of(2026, 8, 15), CANDIDATES, "   "))
                .isEqualTo(Domain.DATABASE);
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
}
