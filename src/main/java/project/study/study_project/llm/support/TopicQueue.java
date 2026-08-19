package project.study.study_project.llm.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.TopicQueueFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 주제 대기열 — {@code generated/_topics.json}을 읽고, 다음 주제를 꺼내고, 쓴 것을 표시한다.
 *
 * <p><b>왜 생겼나(2026-08-19, 사용자 요청).</b> 배치가 분야만 던지니 분야 전체를 개괄하는 문서가
 * 나왔다. "오늘은 {@code @Transactional} 전파 속성을 파고 싶다"를 배치에 전달할 통로가 없었다 —
 * 있는 것은 워크플로 수동 실행의 {@code topic} 입력뿐이라 <b>매일 아침 사람이 버튼을 눌러야</b>
 * 했다. 예약 실행이 스스로 읽을 수 있는 자리가 필요했고, 그게 이 파일이다.
 *
 * <p><b>왜 손으로 관리하는 목록을 이제 와서 받아들였나.</b> {@code ClaudeDocumentGenerator}의
 * 주석에는 "큐레이션 목록은 결국 갱신되지 않는다"며 버린 기록이 남아 있다. 그 판단은 지금도
 * 옳지만 겨냥한 것이 달랐다 — 그때 버린 것은 <b>분야마다 수십 개를 미리 채워 두는 카탈로그</b>다.
 * 여기 있는 것은 대기열이라 두어 개만 있어도 동작하고, <b>비면 예전처럼 모델이 자동 선택</b>한다.
 * 즉 갱신을 게을리해도 파이프라인이 멈추지 않는다 — 그것이 카탈로그와 갈리는 지점이다.
 *
 * <p><b>왜 클래스로 뺐나.</b> {@code DraftGeneratorCli}에 열 줄 더 넣는 것으로도 되지만,
 * 그 파일은 이미 1,000줄이고 "읽기 → 고르기 → 되쓰기"는 파일 시스템을 타는 로직이라
 * <b>테스트로 못 박을 값어치</b>가 있다. 특히 되쓰기는 틀려도 조용하다 — 사용 표시가 안 되면
 * 같은 주제로 문서가 또 나오고, 그건 며칠 뒤에야 눈에 띈다.
 *
 * <p><b>실패해도 예외를 던지지 않는다.</b> 이 파일은 사람이 손으로 고치는 파일이라 언제든
 * 깨질 수 있는데, 쉼표 하나 때문에 그날 문서 생성이 통째로 실패하면 안 된다. 못 읽으면
 * "대기열이 비었다"로 보고 자동 선택으로 흘려보내되, <b>왜 못 읽었는지는 크게 알린다</b>
 * ({@link #problems()}) — 조용히 무시하면 대기열을 채워 놓고도 안 쓰이는 상태를 모른다.
 */
public final class TopicQueue {

    /** 대기열 파일 이름. 언더스코어로 시작하는 것은 배치 결과 파일과 구분하는 저장소 관례다. */
    public static final String FILE_NAME = "_topics.json";

    /** 파일을 새로 쓸 때 note가 비어 있으면 넣어 주는 설명 — 파일 자신이 사용법을 들고 있게 한다. */
    private static final String DEFAULT_NOTE =
            "다음에 개념 문서로 쓸 주제 대기열입니다. 위에서부터 하나씩 씁니다(4일 주기의 0일차). "
                    + "domain은 필수이고 Domain 상수명 그대로 적습니다. usedAt이 있으면 이미 쓴 항목이라 건너뜁니다. "
                    + "대기열이 비면 예전처럼 모델이 주제를 자동으로 고릅니다. 고친 뒤에는 커밋해야 다음 배치에 반영됩니다.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TopicQueueFile file;

    /** 읽는 동안 건너뛴 항목의 사유. 사람이 읽는 문장이다(요약 화면에 그대로 실린다). */
    private final List<String> problems;

    private TopicQueue(TopicQueueFile file, List<String> problems) {
        this.file = file;
        // 반드시 가변 목록으로 복사한다 — 사유는 읽는 시점보다 <고르는 시점>에 알게 되는 것이
        // 더 많아서(빈 주제, 잘못된 분야) next()가 여기에 덧붙인다. List.of()를 그대로 들고 있으면
        // 그 순간 UnsupportedOperationException으로 죽는다.
        this.problems = new ArrayList<>(problems);
    }

    /** 꺼내 온 주제 한 건. {@code index}는 되쓸 때 어느 줄에 도장을 찍을지 가리킨다. */
    public record Picked(int index, Domain domain, String topic) {
    }

    /* ── 읽기 ─────────────────────────────────────────────────── */

    /**
     * 대기열을 읽는다 — 파일이 없거나 깨져 있어도 <b>빈 대기열</b>을 돌려준다.
     *
     * <p>파일이 없는 것은 오류가 아니다. 아직 안 만들었거나, 주제를 다 쓰고 지웠을 수 있다.
     * 그 경우는 지금까지와 똑같이 모델이 주제를 고르면 된다.
     */
    public static TopicQueue read(Path dir) {
        Path path = dir.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return new TopicQueue(new TopicQueueFile(null, List.of()), List.of());
        }
        try {
            TopicQueueFile parsed = MAPPER.readValue(path.toFile(), TopicQueueFile.class);
            List<TopicQueueFile.Entry> topics =
                    parsed.topics() == null ? List.of() : parsed.topics();
            return new TopicQueue(new TopicQueueFile(parsed.note(), topics), findProblems(topics));
        } catch (Exception e) {
            // 쉼표 하나 빠뜨린 것으로 그날 생성이 죽으면 안 된다 — 대신 사유를 남긴다
            return new TopicQueue(new TopicQueueFile(null, List.of()),
                    List.of("`" + FILE_NAME + "`을 읽지 못했습니다(JSON 형식 확인): " + e.getMessage()));
        }
    }

    /* ── 고르기 ───────────────────────────────────────────────── */

    /**
     * 다음에 쓸 주제 — 아직 안 쓴 첫 항목. 없으면 {@code null}(자동 선택으로 흘려보낸다).
     *
     * <p><b>왜 위에서부터인가.</b> 적은 순서가 곧 공부하고 싶은 순서다. 무작위로 고르거나
     * "가장 부족한 분야 먼저" 같은 규칙을 얹으면, 사람이 순서를 정해 둔 의미가 사라진다.
     *
     * <p><b>분야가 없거나 잘못된 항목은 건너뛴다.</b> 문서의 분야는 그 문서로 만드는 사흘치
     * 문제의 분야까지 정한다({@code alignDomainWithDocument}). 여기서 주기 분야로 대충 메우면
     * "스프링 주제인데 운영체제 칸에 들어간 문서"가 생기고, 그 상태로 나흘이 간다.
     * 건너뛴 사유는 {@link #problems()}에 쌓여 요약 화면에 뜬다 — 조용히 사라지지 않는다.
     */
    public Picked next() {
        List<TopicQueueFile.Entry> topics = file.topics();
        for (int i = 0; i < topics.size(); i++) {
            TopicQueueFile.Entry entry = topics.get(i);
            if (!isUsable(entry)) {
                continue; // 왜 건너뛰는지는 read() 시점에 이미 problems()에 쌓아 뒀다
            }
            return new Picked(i, parseDomain(entry.domain()), entry.topic().trim());
        }
        return null;
    }

    /**
     * 형식이 잘못된 항목의 사유를 <b>파일 전체</b>에서 모은다 — 첫 번째 유효 항목에서 멈추지 않는다.
     *
     * <p><b>왜 전부 보나.</b> {@link #next}가 지나가는 길에만 사유를 남기면, 대기열 5번 항목의
     * 오타는 앞의 넷을 다 쓸 때까지(즉 <b>2주 뒤</b>) 아무도 모른다. 그때는 이미 "왜 내가 적은
     * 주제가 안 나오지?"를 겪은 뒤다. 오타는 적은 날 알아야 고칠 수 있다.
     */
    private static List<String> findProblems(List<TopicQueueFile.Entry> topics) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            TopicQueueFile.Entry entry = topics.get(i);
            if (entry == null || !entry.isPending() || isUsable(entry)) {
                continue;
            }
            if (entry.topic() == null || entry.topic().isBlank()) {
                problems.add("%d번 항목에 topic이 비어 있어 건너뜁니다.".formatted(i + 1));
            } else {
                problems.add("%d번 항목(\"%s\")의 domain이 \"%s\"라서 건너뜁니다 — Domain 상수명을 그대로 적어야 합니다."
                        .formatted(i + 1, entry.topic(), entry.domain()));
            }
        }
        return problems;
    }

    /** 쓸 수 있는 항목인지 — 아직 안 썼고, 주제가 있고, 분야가 아는 상수명이다. */
    private static boolean isUsable(TopicQueueFile.Entry entry) {
        return entry != null && entry.isPending()
                && entry.topic() != null && !entry.topic().isBlank()
                && parseDomain(entry.domain()) != null;
    }

    /** 아직 안 쓴 항목 수 — "남은 주제 3개"처럼 사람에게 알리는 용도. 형식 오류는 세지 않는다. */
    public int pendingCount() {
        return (int) file.topics().stream().filter(TopicQueue::isUsable).count();
    }

    /** 건너뛴 항목의 사유 목록 — 사람이 읽는 문장이다. 되쓰기 실패도 여기에 쌓인다. */
    public List<String> problems() {
        return problems;
    }

    /* ── 사용 표시(되쓰기) ────────────────────────────────────── */

    /**
     * 쓴 주제에 날짜 도장을 찍어 파일을 다시 쓴다. 성공하면 {@code true}.
     *
     * <p><b>왜 생성 전이 아니라 후에 찍나.</b> 먼저 찍으면 생성이 실패했을 때 주제만 소모된다 —
     * 요금도 안 나갔는데 그 주제는 영영 안 나온다. 반대로 나중에 찍으면 최악의 경우 같은 주제가
     * 한 번 더 나오는데, 그건 검수 화면에서 눈에 보이고 되돌리기도 쉽다. <b>조용히 사라지는
     * 쪽보다 눈에 띄게 중복되는 쪽</b>을 고른다.
     *
     * <p><b>왜 항목을 지우지 않고 도장만 찍나.</b> 지우면 언제 무엇을 공부했는지 기록이 사라진다.
     * 게다가 배치가 파일에서 줄을 없애면, 사람이 방금 손으로 추가한 줄과 부딪칠 때
     * 무엇이 사라졌는지 알 길이 없다(git diff에서 삭제로만 보인다).
     */
    public boolean markUsed(Path dir, Picked picked, LocalDate date) {
        try {
            List<TopicQueueFile.Entry> updated = new ArrayList<>(file.topics());
            if (picked.index() < 0 || picked.index() >= updated.size()) {
                return false; // 도달할 수 없는 경로(방어) — 인덱스는 이 클래스가 만든 값이다
            }
            updated.set(picked.index(), updated.get(picked.index()).usedOn(date.toString()));

            TopicQueueFile written = new TopicQueueFile(
                    file.note() == null || file.note().isBlank() ? DEFAULT_NOTE : file.note(), updated);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(FILE_NAME),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(written));
            return true;
        } catch (Exception e) {
            problems().add("사용 표시를 저장하지 못했습니다: " + e.getMessage());
            return false;
        }
    }

    /* ── 도우미 ───────────────────────────────────────────────── */

    /**
     * 분야 문자열 → 상수. 모르는 값이면 {@code null}(예외를 던지지 않는다).
     *
     * <p>대소문자와 앞뒤 공백은 봐준다 — 손으로 적는 파일에서 그것까지 틀렸다고 항목을 버리는
     * 것은 엄격한 게 아니라 불친절한 것이다. 반면 없는 상수명은 봐줄 수 없다: 비슷한 이름으로
     * 짐작해 붙이면 엉뚱한 분야에 문서가 들어가고, 그게 사흘치 문제까지 끌고 간다.
     */
    private static Domain parseDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Domain.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
