package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude API <b>실제 호출</b> E2E 테스트 — 주간 CI(.github/workflows/llm-e2e.yml)에서 돈다.
 *
 * <p><b>왜 필요한가.</b> {@code LlmProblemServiceTest}는 가짜 생성기를 써서 우리 코드의 판단 로직
 * (부족 칸 선택·규약 위반 스킵 등)만 검증한다. 반대로 이 테스트가 지키는 것은 <b>우리가 고치지 않아도
 * 깨지는 것들</b>이다 — 모델 은퇴로 인한 404, 키 만료 401, 구조화 출력 스키마 규격 변경,
 * 지출 한도 초과 429, SDK 업그레이드로 인한 시그니처 변경.
 *
 * <p>이게 중요한 이유는 일일 배치({@code LlmGenerationScheduler})의 <b>실패가 조용하기</b> 때문이다.
 * 배치는 예외를 삼키고 로그만 남기므로(스케줄러 스레드 보호 목적), 위 사고가 나면 새벽에 로그 한 줄이
 * 찍히고 끝이다. 몇 주 뒤 "검수 대기함이 왜 비어 있지?" 할 때까지 아무도 모른다.
 * GitHub Actions는 실패 시 자동으로 메일을 보내므로, 주 1회 이 테스트가 화재경보기 역할을 한다.
 *
 * <p><b>비용 통제.</b> 문제를 <b>1개만</b> 만들고(1회 약 100원), 중복 회피 목록도 비워 입력 토큰을 줄인다.
 * 매 push마다 돌면 안 되므로 기존 ci-cd.yml에는 넣지 않고 별도 워크플로(수동 + 주 1회)로 분리했다.
 *
 * <p><b>키가 없으면 통째로 건너뛴다</b>({@code @EnabledIfEnvironmentVariable}).
 * 로컬에서 {@code gradlew build}를 돌려도 실패하지 않고 요금도 발생하지 않는다 —
 * 개발자가 "테스트가 깨져서" 혹은 "돈이 나갈까 봐" 빌드를 피하게 만들면 안 된다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다: 이 생성기는 DB·Redis를 쓰지 않으므로 직접 생성하는 편이
 * 훨씬 빠르고, CI 워크플로에서 MySQL·Redis 서비스 컨테이너를 띄울 필요도 없어진다.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+",
        disabledReason = "ANTHROPIC_API_KEY가 없어 실 API 테스트를 건너뜁니다(로컬 기본 동작).")
class ClaudeProblemGeneratorE2ETest {

    @Test
    @DisplayName("실제 Claude 호출로 객관식 1문제를 만들면 저장 규약(보기 4개·정답 1개)을 지킨 한국어 문제가 나온다")
    void generatesOneValidMultipleChoiceProblem() {
        // application.yml의 값을 그대로 읽는다 — 테스트에 모델 ID를 따로 적으면 설정만 바뀌었을 때
        // 엉뚱한 모델을 검사하게 되어 "은퇴 감시"라는 목적 자체가 무너진다.
        String model = configuredModel();

        List<GeneratedProblemItem> items = new ClaudeProblemGenerator(model).generate(
                Domain.NETWORK, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                1,               // 비용 통제 — 계약 검증에 1개면 충분하다
                List.of(),       // 중복 회피 목록 없음(입력 토큰 절약)
                List.of());      // 거절 사례 없음 — 이 테스트는 API 계약만 보므로 프롬프트를 최소로 둔다

        assertThat(items).as("모델이 요청한 개수만큼 문제를 반환해야 한다").hasSize(1);
        GeneratedProblemItem item = items.get(0);

        // 아래 단정들은 전부 "이게 깨지면 배치가 만든 초안이 버려진다"는 기준으로 골랐다
        // (LlmProblemService.toDraft가 규약 위반 항목을 건너뛰기 때문).
        assertThat(item.question()).as("지문").isNotBlank();
        assertThat(item.explanation()).as("해설 — 이 서비스의 핵심 가치").isNotBlank();
        assertThat(item.choices()).as("객관식 보기").hasSize(4);
        assertThat(item.choices()).as("정답 보기는 정확히 1개")
                .filteredOn(GeneratedProblemItem.GeneratedChoice::correct).hasSize(1);
        assertThat(item.choices()).allSatisfy(choice ->
                assertThat(choice.text()).as("빈 보기가 있으면 안 된다").isNotBlank());
        assertThat(item.question()).as("한국어로 출제되어야 한다").containsPattern("[가-힣]");

        // answer(객관식이면 빈 문자열)는 일부러 검증하지 않는다: 모델이 여기에 값을 넣더라도
        // LlmProblemService가 null로 정규화하므로 앱이 깨지지 않는다. 깨지지 않는 것까지 단정하면
        // 주간 알림이 헛울리고, 그러면 진짜 알림도 무시하게 된다.
    }

    /** application.yml에서 {@code llm.generation.model}을 읽는다. 테스트 리소스가 없어 본 설정이 그대로 잡힌다. */
    @SuppressWarnings("unchecked")
    private String configuredModel() {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> generation =
                    (Map<String, Object>) ((Map<String, Object>) root.get("llm")).get("generation");
            String model = (String) generation.get("model");
            assertThat(model).as("application.yml의 llm.generation.model").isNotBlank();
            return model;
        } catch (Exception e) {
            throw new IllegalStateException("application.yml에서 모델 ID를 읽지 못했습니다.", e);
        }
    }
}
