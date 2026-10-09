package project.study.study_project.llm.cli;

import org.yaml.snakeyaml.Yaml;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.support.BatchCountRule;
import project.study.study_project.domainsetting.support.DefaultDomains;
import project.study.study_project.domainsetting.support.DomainEntry;
import project.study.study_project.llm.support.DomainSettings;
import project.study.study_project.llm.support.GenerationSchedule;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 배치 설정과 명령줄 인자를 읽고, 오늘 무엇을 몇 개 만들지 정한다.
 *
 * <p>{@link DraftGeneratorCli}에서 역할별로 떼어 냈다(2026-09-29). 동작은 그대로다.
 */
final class BatchOptions {

    private BatchOptions() {
    }

    /** 결과 파일이 쌓이는 기본 디렉터리 — 저장소 루트 기준 상대 경로. */
    static final String DEFAULT_OUT_DIR = "generated";

    /** 개념 문서 결과가 쌓이는 하위 디렉터리. 문제 파일과 형식이 달라 폴더로 분리한다(docs/15). */
    static final String DOCUMENT_SUBDIR = "documents";

    /** 근거 문서를 지목하는 옵션 이름. 이름을 세 곳에서 문자열로 쓰게 되어 상수로 뽑았다. */
    static final String DOCUMENT_DATE_OPT = "document-date";

    /** 결과 파일 이름에 붙일 접미사 옵션. */
    static final String SUFFIX_OPT = "suffix";

    /**
     * 문제 유형 옵션 이름 — {@code --problem-type}(2026-08-31 신설).
     *
     * <p><b>왜 {@code --type}이 아닌가.</b> 그 이름은 이미 <b>"문제를 만들 것인가 문서를 만들
     * 것인가"</b>에 쓰이고 있다({@link #decideAction}). 같은 이름을 나눠 쓰면 워크플로에서
     * {@code --type=OX}라고 적었을 때 <b>문서일 판정</b>이 이상해진다 — 그것도 조용히,
     * "오늘은 쉬는 날"이라는 정상 종료로 끝난다. 요금이 안 나가서 사고가 났다는 것조차 늦게 안다.
     *
     * <p>기존 이름을 바꾸는 대신 새 이름을 길게 짓는 쪽을 택했다. {@code --type}은 워크플로
     * 입력·문서·손에 익은 명령에 이미 퍼져 있어, 바꾸면 그 전부를 같은 날 고쳐야 한다.
     */
    static final String PROBLEM_TYPE_OPT = "problem-type";

    /**
     * 접미사에 허용하는 글자.
     *
     * <p>이 값은 <b>파일 이름이 된다</b>. 워크플로의 수동 입력으로 들어오므로 검증 없이 이어 붙이면
     * {@code ../../}로 저장소 밖에 쓰거나 {@code _}로 시작해 흡수에서 제외되는 파일을 만들 수 있다.
     * 그래서 통과 목록(소문자·숫자·하이픈)으로 좁힌다 — 막을 것을 나열하는 방식은 언제나
     * 빠뜨리는 것이 생긴다. 첫 글자를 하이픈이 아니게 한 것은 {@code 2026-08-29--x.json}처럼
     * 읽기 나쁜 이름을 막으려는 것이다.
     */
    static final Pattern SUFFIX_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,29}");

    /** 한국 날짜 기준 — 워크플로는 UTC로 도니까 변환하지 않으면 하루 어긋난다. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** Batch API 옵션 — 워크플로가 켠다. 로컬 실행은 기본이 바로 호출이라 결과를 곧장 본다. */
    static final String BATCH_API_OPT = "batch-api";

    /** 오늘 배치가 할 일. {@link #decideAction}이 결정한다. */
    enum BatchAction {
        /** 개념 문서 한 편을 만든다. */
        DOCUMENT,
        /** 문제를 만든다. */
        PROBLEM,
        /** 아무것도 만들지 않고 정상 종료한다(요금 0). */
        SKIP
    }

    /**
     * 오늘 무엇을 만들지 정한다 — 우선순위는 <b>수동 지정 &gt; 설정 &gt; 날짜 주기</b>다.
     *
     * <p><b>수동이 가장 세다</b>: 사람이 워크플로에서 "오늘 문서 뽑아"라고 눌렀는데 주기나 설정이
     * 막으면 그건 버그처럼 보인다. 수동 실행은 그 한 번만 유효하고 저장소 설정을 건드리지 않으므로
     * 되돌리는 것을 잊어 사고가 나지도 않는다({@code force} 스위치와 같은 판단).
     *
     * <p><b>설정({@code llm.generation.batch-type})의 뜻</b>
     * <ul>
     *   <li>{@code auto}(기본): 4일 주기 그대로 — 0일차 문서, 나머지 문제
     *   <li>{@code problem}: 문서일에도 문제를 만든다. <b>2단계가 사실상 꺼진다</b> —
     *       문서를 안 만드니 근거로 삼을 문서가 안 생기고, 배치는 계속 폴백으로 돈다
     *   <li>{@code document}: 문서일에만 문서를 만들고 <b>나머지 사흘은 아무것도 안 한다</b>.
     *       "매일 문서"가 아닌 이유는 그러면 요금이 네 배가 되기 때문 — 매일 원한다면
     *       주기 길이를 바꿔야 하고, 그건 이 스위치가 다룰 문제가 아니다
     * </ul>
     *
     * <p><b>모르는 값이 오면 auto로 본다.</b> 오타 하나로 배치가 통째로 멈추는 것보다
     * 평소대로 도는 편이 낫다 — 이 프로젝트가 겪은 사고는 "안 도는 걸 몇 주 뒤에 알아차린" 쪽이었다.
     *
     * @param requestedType  수동 실행의 {@code --type}. 비어 있으면 지정 안 한 것(예약 실행이 그렇다)
     * @param configuredType {@code application.yml}의 {@code llm.generation.batch-type}
     * @param documentDay    날짜 주기가 "오늘은 문서일"이라고 했는지
     */
    static BatchAction decideAction(String requestedType, String configuredType, boolean documentDay) {
        // ① 수동 지정 — auto는 "지정 안 함"과 같은 뜻이라 아래로 흘려보낸다(워크플로가 빈 값으로
        //    바꿔 넘기지만, 사람이 직접 CLI를 부를 때를 위해 여기서도 받아 준다)
        if (isSet(requestedType) && !"auto".equalsIgnoreCase(requestedType.trim())) {
            return "document".equalsIgnoreCase(requestedType.trim())
                    ? BatchAction.DOCUMENT : BatchAction.PROBLEM;
        }

        // ② 설정
        if (isSet(configuredType)) {
            String type = configuredType.trim();
            if ("problem".equalsIgnoreCase(type)) {
                return BatchAction.PROBLEM;
            }
            if ("document".equalsIgnoreCase(type)) {
                return documentDay ? BatchAction.DOCUMENT : BatchAction.SKIP;
            }
        }

        // ③ 날짜 주기(auto)
        return documentDay ? BatchAction.DOCUMENT : BatchAction.PROBLEM;
    }

    static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /** 기준 날짜 — 한국 기준. 문제·문서 두 흐름이 같은 규칙을 쓰도록 한 곳에 둔다. */
    static LocalDate resolveDate(Map<String, String> opts) {
        return opts.containsKey("date")
                ? LocalDate.parse(opts.get("date"))
                : LocalDate.now(KST);
    }

    /**
     * 만들 개수 — <b>{@code --count} &gt; 난이도별 설정 &gt; {@code batch-count}</b> 순으로 정한다.
     *
     * <h2>왜 검증이 필요한가(2026-08-29)</h2>
     *
     * <p>예전에는 {@code Integer.parseInt(opts.get("count"))} 한 줄이었다. 관리자 API는
     * {@code @Max(10)}으로 11을 거부하는데 <b>이쪽에는 상한이 없어</b> 워크플로 수동 입력의
     * {@code 500}이 그대로 요금이 됐다. 검증이 없는 쪽이 하필 사람이 손으로 숫자를 적는 쪽이었다.
     *
     * <p><b>설정값도 같이 잰다.</b> 옵션만 검증하면 {@code batch-count: 100}이라는 오타가
     * 예약 실행에서 매일 조용히 통과한다. 어느 경로로 들어왔든 이 문을 지나게 두고,
     * 메시지에 출처를 적어 어디를 고쳐야 하는지 알린다.
     *
     * <p>범위를 벗어나면 잘라 쓰지 않고 던진다({@link GenerationLimits} 참고). 호출 전이라
     * 요금은 0이고, job이 빨간불로 끝나 메일이 온다 — 조용히 10개가 나오는 것보다 낫다.
     *
     * <h2>2026-09-05 — 난이도별 개수가 끼어들었다</h2>
     *
     * <p>초·중·고급이 모두 같은 5개라 1:1:1로 쌓였는데, 원하는 비율은 <b>초급이 가장 많고
     * 고급이 가장 적은</b> 쪽이다. 배분 자체의 근거와 대안 검토는 {@link BatchCountRule}에 적었다.
     *
     * <p><b>{@code --count}가 여전히 가장 세다.</b> 손으로 지목하는 실행은 "오늘은 이만큼"이라는
     * 뜻이 분명하고, 그 자리에서 난이도별 배분이 덮어써 버리면 <b>적은 대로 안 나오는</b> 옵션이
     * 된다. 이 저장소가 지목 인자에 늘 두는 판단이다 — 사람이 고른 것이 규칙을 이긴다
     * ({@code ClaudeProblemGenerator}가 형태 지목을 받으면 배분 규칙을 끄는 것과 같다).
     *
     * @param countSpec  난이도별 배분 문자열({@code batch-count-by-difficulty}). {@code null} 가능
     * @param difficulty 오늘 난이도. {@code null}이면 난이도별 값을 찾지 않는다
     * @param fallback   둘 다 없을 때 쓸 값(설정에서 읽은 {@code batch-count})
     * @throws IllegalArgumentException 숫자가 아니거나 허용 범위 밖일 때
     */
    static int resolveCount(Map<String, String> opts, String countSpec,
                            Difficulty difficulty, int fallback) {
        String raw = opts.get("count");
        if (raw != null) {
            int count;
            try {
                count = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--count는 숫자여야 합니다(받은 값: \"%s\")".formatted(raw));
            }
            BatchCountRule.requireInRange(count, "--count");
            return count;
        }

        // 난이도별 값이 있으면 그것, 없으면 batch-count. 범위 검사는 규칙 안에서 이미 하지만
        // (난이도별 항목마다) 폴백으로 내려온 값은 아직 아무도 안 쟀으므로 여기서 한 번 더 본다.
        int count = BatchCountRule.countFor(countSpec, difficulty, fallback);
        if (count == fallback) {
            BatchCountRule.requireInRange(count, "application.yml의 llm.generation.batch-count");
        }
        return count;
    }

    /**
     * {@code --problem-type} 해석 — 비었으면 <b>객관식</b>(2026-08-31 신설).
     *
     * <p><b>왜 기본값이 객관식인가.</b> 4일 주기(문서 1일 + 초·중·고급 3일)는 지금 잘 돌고 있고,
     * 거기에 유형 축을 하나 더 끼우면 한 바퀴가 12일이 되어 <b>검수 리듬이 통째로 바뀐다</b>.
     * 새 유형은 실물을 몇 번 보고 나서 주기에 넣어도 늦지 않다 — 먼저 <b>고를 수 있게</b>만 한다.
     * 예약 실행은 이 옵션을 비워 두므로 지금까지와 똑같이 동작한다.
     *
     * <p><b>"지정 안 함"이 두 모습으로 온다.</b> 예약 실행은 이 옵션을 아예 안 써서
     * {@code --problem-type=}가 빈 값으로 오고, 수동 실행은 드롭다운 기본 선택지인
     * {@code AUTO}가 온다. 둘 다 뜻은 같으므로 여기서 함께 받아 준다 — 빈 값을
     * {@code valueOf("")}에 넣으면 <b>아무것도 안 고른 평범한 실행</b>에서 배치가 죽는다.
     *
     * <p><b>왜 워크플로가 아니라 여기서 AUTO를 푸나.</b> 워크플로에서
     * {@code ${{ inputs.problem_type == 'AUTO' && '' || inputs.problem_type }}}로 바꿔 넘기는
     * 방법이 있어 보이지만, 그 관용구는 <b>동작하지 않는다</b> — GitHub 식에서 빈 문자열은
     * 거짓값이라 {@code && ''}의 결과가 {@code ||}를 타고 원래 값으로 되돌아온다.
     * {@code --type}의 {@code auto}를 {@link #decideAction}이 직접 받아 주는 것과 같은 이유다.
     *
     * <p>서술형은 여기서 막는다. {@code ClaudeProblemGenerator.typeRule}도 막지만, 그건
     * <b>API를 부르기 직전</b>이라 그 전에 근거 문서를 읽고 중복 목록을 만드는 일을 다 한 뒤다.
     * 값이 잘못된 것은 값을 읽는 자리에서 걸러야 한다.
     */
    static ProblemType resolveProblemType(String raw) {
        // 빈 값(예약 실행)과 AUTO(수동 실행의 기본 선택지)는 같은 뜻이다 — 위 주석 참고.
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.trim())) {
            return ProblemType.MULTIPLE_CHOICE;
        }
        ProblemType type;
        try {
            type = ProblemType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "--problem-type이 알 수 없는 값입니다(받은 값: \"%s\"). 쓸 수 있는 값: %s"
                            .formatted(raw, generatableTypes()));
        }
        if (!type.isAutoScored()) {
            throw new IllegalArgumentException(
                    "--problem-type=%s는 자동채점 대상이 아니라 생성할 수 없습니다. 쓸 수 있는 값: %s"
                            .formatted(type, generatableTypes()));
        }
        return type;
    }

    /** 오류 메시지에 넣을 "쓸 수 있는 값" 목록 — enum에서 뽑아 쓰므로 유형이 늘면 저절로 따라온다. */
    static String generatableTypes() {
        return Arrays.stream(ProblemType.values())
                .filter(ProblemType::isAutoScored)
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * 결과 파일 이름 — 접미사가 없으면 예전 그대로 {@code {날짜}.json}.
     *
     * <h2>왜 접미사가 필요한가(2026-08-29)</h2>
     *
     * <p>파일 이름이 곧 날짜였고, 그 날짜가 곧 주기 순번이었다. 그래서 <b>사람이 손으로 한 칸을
     * 채우면 그 날짜의 예약 실행이 죽었다</b> — 같은 이름이면 멱등성 검사가 건너뛰기 때문이다.
     * 보안 문서 두 편을 채우느라 여덟 날짜를 쓴 결과, 이후 30일 중 11일이 조용히 건너뛰기가 되고
     * 그 사이에 만들 문서 두 편은 문제를 한 건도 못 받는 상태가 됐다.
     *
     * <p>접미사를 붙이면 주기가 만드는 이름({@code 2026-09-25.json})과 손으로 채운 이름
     * ({@code 2026-08-29-csrf-beg.json})이 서로 다른 공간에 산다. 날짜를 앞에 두는 것은
     * 흡수가 이름 오름차순 = 오래된 순으로 읽기 때문이다({@code DraftImportRunner.scan}).
     *
     * <p><b>멱등성은 그대로다.</b> 같은 접미사로 두 번 부르면 여전히 건너뛴다 — 두 번 눌러
     * 요금이 두 번 나가는 것을 막는 장치는 손대지 않았다. "다른 결과를 원하면 다른 이름"이라는
     * 규칙이 되었을 뿐이다.
     *
     * @param suffix {@code null}이면 접미사 없음
     * @throws IllegalArgumentException 접미사가 허용 글자를 벗어날 때 — 조용히 고쳐 쓰지 않는다.
     *                                  이름을 마음대로 바꾸면 사람이 찾는 파일과 실제 파일이 달라진다
     */
    static String outFileName(LocalDate date, String suffix) {
        if (suffix == null) {
            return date + ".json";
        }
        if (!SUFFIX_PATTERN.matcher(suffix).matches()) {
            throw new IllegalArgumentException(
                    "--suffix는 소문자·숫자·하이픈만 쓸 수 있고 30자를 넘을 수 없습니다(받은 값: \"%s\")"
                            .formatted(suffix));
        }
        return date + "-" + suffix + ".json";
    }

    /**
     * 근거 문서 파일의 날짜 — {@code --document-date}가 있으면 그것, 없으면 주기가 정한 값.
     *
     * <p>이 한 줄을 굳이 메서드로 뺀 이유는 <b>테스트하려고</b>다. 옵션을 무시하거나 반대로
     * 예약 실행에서까지 옵션 쪽을 보는 실수는 증상이 조용하다 — 둘 다 문제는 정상적으로
     * 나오고, 다만 <b>엉뚱한 문서를 근거로</b> 나온다. 파일 안의 {@code documentSlug}를
     * 들여다봐야 알 수 있는 종류라 못 박아 둔다({@code shouldGenerate}와 같은 이유).
     *
     * @param plan 날짜 순환이 계산한 계획. 옵션이 없을 때의 기본값을 여기서 가져온다
     */
    static LocalDate resolveDocumentDate(Map<String, String> opts, GenerationSchedule.Plan plan) {
        return opts.containsKey(DOCUMENT_DATE_OPT)
                ? LocalDate.parse(opts.get(DOCUMENT_DATE_OPT))
                : plan.documentDate();
    }

    /* ── 중단 스위치 ─────────────────────────────────────────── */

    /**
     * 생성을 진행할지 판단한다 — {@code batch-enabled}가 꺼져 있어도 {@code force}면 진행.
     *
     * <p><b>왜 force라는 예외 구멍을 두는가.</b> 스위치가 절대적이면, 꺼 둔 상태에서 문제 하나를
     * 급히 만들려 할 때 "설정을 true로 커밋 → 실행 → 다시 false로 커밋"을 해야 한다. 그 과정에서
     * 되돌리기를 잊으면 <b>끈 줄 알았던 배치가 계속 돈다</b> — 스위치를 둔 목적이 무너진다.
     * force는 수동 실행(workflow_dispatch)에서만 켤 수 있고 저장소 설정을 건드리지 않으므로,
     * "한 번만 예외"가 영구 변경으로 새는 일이 없다. 예약 실행은 force를 넘기지 않는다.
     *
     * <p><b>두 줄짜리인데 왜 테스트하는가.</b> 이 판단이 틀리면 증상이 조용하다. {@code &&}를
     * {@code ||}로 잘못 쓰면 "꺼도 계속 도는" 또는 "켜도 안 도는" 상태가 되는데, 후자는 배치가
     * 그냥 매일 조용히 아무것도 안 할 뿐이라 몇 주 뒤에야 알게 된다 — 이 프로젝트가 이미 한 번
     * 겪은 종류의 사고다(docs/14 "왜 옮겼나"). 진리표를 테스트로 못 박아 둔다.
     */
    static boolean shouldGenerate(boolean batchEnabled, boolean force) {
        return batchEnabled || force;
    }

    /* ── 설정·인자 파싱 ───────────────────────────────────────── */

    /**
     * 클래스패스의 application.yml에서 {@code llm.generation} 블록을 읽는다.
     * Spring 없이 설정을 읽어야 해서 snakeyaml을 직접 쓴다(E2E 테스트와 같은 방식).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readGenerationConfig() throws Exception {
        try (InputStream in = DraftGeneratorCli.class.getResourceAsStream("/application.yml")) {
            if (in == null) {
                throw new IllegalStateException("클래스패스에서 application.yml을 찾을 수 없습니다.");
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> llm = (Map<String, Object>) root.get("llm");
            return (Map<String, Object>) llm.get("generation");
        }
    }

    /**
     * 결과 디렉터리 — {@code --out} 아니면 {@link #DEFAULT_OUT_DIR}.
     *
     * <p>원래는 이 계산이 216번째 줄(문제 흐름)과 640번째 줄(문서 흐름) 두 곳에 따로 있었다.
     * Task 6에서 분야 설정 파일({@code DomainSettings.read})을 <b>후보 분야를 정하는 자리</b>
     * (기존 216번째 줄보다 앞선 지점)에서 읽어야 하면서 세 번째 계산 자리가 필요해졌는데,
     * 문자열 기본값 {@code "generated"}를 또 하드코딩하는 대신 이 헬퍼 하나로 모았다 —
     * 나중에 기본 출력 경로가 바뀔 때 고칠 자리가 하나뿐이어야 한다.
     */
    static Path resolveOutDir(Map<String, String> opts) {
        return Path.of(opts.getOrDefault("out", DEFAULT_OUT_DIR));
    }

    /**
     * 날짜 순환의 후보 분야("이번 실행의 전체") — 분야 설정 파일이 먼저, yml {@code batch-domains}는
     * <b>파일이 없을 때만</b>, 그것마저 없으면 {@link DefaultDomains#codes()}(task-7-brief 표).
     *
     * <h2>Task 7 — 넓히는 책임이 여기로 왔다(2026-09-22)</h2>
     *
     * <p>예전에는 이 메서드가 빈 목록을 그대로 돌려줘도 됐다 — {@code GenerationSchedule.planFor}가
     * 받는 쪽에서 빈 목록을 조용히 {@code DefaultDomains.codes()}로 넓혀 줬기 때문이다. 그 폴백을
     * {@code GenerationSchedule}에서 없앴다({@link GenerationSchedule#requireCandidates} 참고) —
     * "전체"의 뜻이 앱과 배치에서 다른데, 그 클래스가 하나(옛 11개)로 고정해 넓히면 관리자가
     * 화면에서 새로 추가한 분야가 배치에서 계속 빠진다. 그래서 <b>이 메서드가 직접</b> 세 단계로
     * 넓힌다:
     * <ol>
     *   <li>파일 없음·깨짐·빈 배열({@link DomainSettings#isEmpty()}) → yml {@code batch-domains}.
     *       그것도 비어 있으면 {@link DefaultDomains#codes()} — 관리 화면을 한 번도 안 쓴 저장소가
     *       예전처럼 돌게 하는 자리다.
     *   <li>파일은 있고 켜진 분야가 1개 이상 → 그 목록 그대로(켜진 것만, {@code sortOrder} 순).
     *   <li>파일은 있는데 켜진 분야가 0개 → <b>파일의 모든 항목</b>(꺼진 것도)으로 넓힌다.
     *       <b>yml도, 옛 11개도 아니다</b> — 파일이 배치의 등록부이므로, 등록되지 않은 분야를
     *       지어내지 않으면서도 순환이 완전히 비지는 않게 한다. 관리 화면은 마지막 분야를 끄지
     *       못하게 막으므로(DOMAIN_002) 이 경우는 사람이 파일을 손으로 고쳤을 때만 생긴다.
     * </ol>
     *
     * <p>배치를 멈추는 수단은 {@code batch-enabled} 하나다 — "분야를 전부 끄면 멈춘다"는 뜻을
     * 여기서 만들지 않는다(두 번째 정지 수단이 생기면 둘의 뜻이 또 어긋난다).
     */
    static List<DomainCode> resolveBatchDomains(DomainSettings settings, String ymlBatchDomains) {
        if (settings.isEmpty()) {
            List<DomainCode> yml = parseDomains(ymlBatchDomains);
            return yml.isEmpty() ? DefaultDomains.codes() : yml;
        }
        List<DomainCode> enabled = settings.batchDomains();
        if (!enabled.isEmpty()) {
            return enabled;
        }
        // 파일은 있는데 켜진 분야가 0개 — 파일의 모든 항목(꺼진 것 포함)으로 넓힌다.
        // settings.all()도 이론상 비었을 수 있다(모든 줄이 형식 오류) — 그 경우 GenerationSchedule이
        // 빈 목록을 프로그래밍 오류로 보고 예외를 던진다. 파일 전체가 깨진 극단적 상황이라
        // 조용히 넘기기보다 큰 소리로 죽는 편이 낫다(클래스 상단 "실패하면 반드시 죽는다" 원칙).
        return settings.all().stream().map(DomainEntry::code).toList();
    }

    /** yml {@code batch-domains} 문자열("NETWORK,OS,...") → 코드 목록. 형식만 본다 — 후보 목록 자신을 만드는 자리라 비교할 "전체"가 없다. */
    static List<DomainCode> parseDomains(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(DomainCode::of).toList();
    }

    /**
     * 사람이 적은 분야 이름({@code --domain=}) → 코드, <b>candidates 안에 있는지까지</b> 확인한다.
     * <b>옛 enum의 {@code valueOf}와 같은 엄격함</b>을 지킨다: candidates 밖이면
     * {@link IllegalArgumentException}으로 실행을 멈춘다 — 요금이 나가기 전에 끊는 것이 목적이라
     * (task-7-brief 표의 "요금을 쓰기 전에 실패한다") 여기서 절대 조용히 넘어가면 안 된다.
     *
     * <h2>Task 7 — "기본 11개"가 아니라 candidates(2026-09-22)</h2>
     *
     * <p>예전에는 {@link DefaultDomains#isKnown}으로 고정된 기본 11개와만 비교했다. 그러면 관리
     * 화면에서 새 분야를 추가하고 {@code --domain=MESSAGING}으로 수동 실행해도 "알 수 없는
     * 분야"로 막힌다 — 화면에서 늘린 분야가 배치까지 닿아야 한다는 이 작업의 목표와 반대다.
     * 지금은 부르는 쪽이 넘긴 candidates(이번 실행의 "전체" — {@link #resolveBatchDomains}가
     * 정한 파일/yml/기본값 순 폴백)와 비교한다.
     *
     * <p>대소문자는 예전처럼 봐주지 않는다(옛 enum의 {@code valueOf}도 봐주지 않았다 — 봐주는
     * 것은 파일을 읽는 {@code TopicQueue}·{@code DomainSettings}뿐이다).
     */
    static DomainCode knownDomain(String raw, List<DomainCode> candidates) {
        DomainCode code = DomainCode.of(raw);
        if (!candidates.contains(code)) {
            throw new IllegalArgumentException("알 수 없는 분야입니다: " + raw);
        }
        return code;
    }

    // cycle-anchor 파싱은 2026-09-21에 GenerationSchedule.parseAnchor로 옮겼다. Task 9의
    // DomainSettingService(관리 화면 미리보기)가 같은 로직을 그대로 복사해 두 벌이 됐던 것을
    // 코드 리뷰에서 지적받았다 — 한쪽만 규칙이 바뀌면 미리보기 화면과 실제 배치가 서로 다른
    // 위상으로 계산하게 되는데, 그 어긋남이야말로 그 화면이 없애려던 실패 그 자체다.
    // GenerationSchedule이 이미 DEFAULT_ANCHOR와 주기 계산을 들고 있고 Spring 의존이 없어
    // 자연스러운 자리라 그쪽으로 합쳤다. 호출부(main()의 "설정 읽기" 단계, cycle-anchor를
    // 읽는 자리)는 GenerationSchedule.parseAnchor를 직접 부른다 — 규칙과 그 이유(오타를
    // 조용히 넘기지 않는 이유 포함)는 그 메서드 Javadoc에 있다.

    /** {@code --key=value} 형태만 받는다. 빈 값(--domain=)은 "지정 안 함"으로 본다 — 워크플로 입력이 비면 그렇게 온다. */
    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new java.util.HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                continue;
            }
            String[] kv = arg.substring(2).split("=", 2);
            if (!kv[1].isBlank()) {
                opts.put(kv[0], kv[1].trim());
            }
        }
        return opts;
    }
}
