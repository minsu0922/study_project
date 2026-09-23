package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주제 대기열 테스트 — {@code generated/_topics.json}(2026-08-19 신설).
 *
 * <p><b>왜 촘촘히 테스트하나.</b> 이 기능의 실패는 전부 조용하다. 파일을 못 읽어도, 항목을
 * 잘못 건너뛰어도, 사용 표시가 안 돼도 배치는 초록불로 끝난다 — 예전처럼 모델이 주제를 고른
 * 문서가 나오기 때문이다. <b>사람이 대기열을 채워 놓고도 안 쓰이는 것을 모르는</b> 상태가
 * 가장 나쁜데, 그게 이 코드가 틀렸을 때의 기본 증상이다.
 *
 * <p>게다가 이 파일은 <b>사람이 손으로 고친다</b>. 쉼표 하나, 오타 하나가 실제로 들어온다는
 * 전제로 짠 방어라, 그 방어가 살아 있는지도 함께 못 박는다.
 */
class TopicQueueTest {

    @TempDir
    Path dir;

    /* ── 읽기 ─────────────────────────────────────────────────── */

    @Test
    @DisplayName("파일이 없으면 빈 목록이다 — 아직 안 만들었을 뿐이지 오류가 아니다")
    void emptyWhenFileMissing() {
        TopicQueue queue = TopicQueue.read(dir);

        assertThat(queue.next()).isNull();
        assertThat(queue.size()).isZero();
        assertThat(queue.problems()).isEmpty(); // 없는 것은 사유로 남길 일도 아니다
    }

    @Test
    @DisplayName("JSON이 깨져 있어도 예외 없이 빈 대기열 + 사유를 남긴다 — 쉼표 하나로 그날 생성이 죽으면 안 된다")
    void survivesBrokenJson() throws Exception {
        write("{ \"topics\": [ { \"domain\": \"OS\", ");

        TopicQueue queue = TopicQueue.read(dir);

        assertThat(queue.next()).isNull();
        assertThat(queue.problems()).hasSize(1);
        assertThat(queue.problems().get(0)).contains(TopicQueue.FILE_NAME);
    }

    @Test
    @DisplayName("모르는 필드가 있어도 읽는다 — 사람이 메모를 하나 더 적었다고 대기열이 통째로 사라지면 안 된다")
    void ignoresUnknownFields() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "OS", "topic": "컨텍스트 스위칭", "priority": "high", "언제": "다음주" }
                  ]
                }
                """);

        assertThat(TopicQueue.read(dir).next().topic()).isEqualTo("컨텍스트 스위칭");
    }

    /* ── 고르기 ───────────────────────────────────────────────── */

    @Test
    @DisplayName("아직 안 쓴 범위가 먼저다 — 새로 넣은 범위가 한 바퀴를 기다리면 안 된다")
    void prefersNeverUsedRange() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "DATABASE", "topic": "인덱스", "lastUsedAt": "2026-08-15", "usedCount": 2 },
                    { "domain": "BACKEND_FRAMEWORK", "topic": "Spring 트랜잭션" },
                    { "domain": "NETWORK", "topic": "TCP" }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        TopicQueue.Picked picked = queue.next();

        assertThat(picked.index()).isEqualTo(1);
        assertThat(picked.topic()).isEqualTo("Spring 트랜잭션");
        assertThat(picked.domain()).isEqualTo(TestDomains.BACKEND_FRAMEWORK);
        assertThat(queue.size()).as("범위는 쓴다고 없어지지 않는다").isEqualTo(3);
    }

    /**
     * 전부 한 번씩 쓰인 뒤의 규칙이다. 이게 없으면 순환이 성립하지 않고, 맨 위 범위만
     * 계속 걸린다 — 여러 범위를 적어 둔 뜻이 사라진다.
     */
    @Test
    @DisplayName("전부 쓴 상태면 가장 오래 안 쓴 범위를 고른다 — 골고루 돌게 하는 규칙")
    void picksLeastRecentlyUsedWhenAllUsed() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "DATABASE", "topic": "인덱스", "lastUsedAt": "2026-08-15" },
                    { "domain": "BACKEND_FRAMEWORK", "topic": "Spring 트랜잭션", "lastUsedAt": "2026-08-07" },
                    { "domain": "NETWORK", "topic": "TCP", "lastUsedAt": "2026-08-19" }
                  ]
                }
                """);

        assertThat(TopicQueue.read(dir).next().topic()).isEqualTo("Spring 트랜잭션");
    }

    @Test
    @DisplayName("차례가 같으면 적어 둔 순서가 이긴다 — 사람이 정한 순서를 뒤 항목이 밀어내면 안 된다")
    void breaksTieByFileOrder() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "OS", "topic": "프로세스와 스레드", "lastUsedAt": "2026-08-10" },
                    { "domain": "NETWORK", "topic": "TCP", "lastUsedAt": "2026-08-10" }
                  ]
                }
                """);

        assertThat(TopicQueue.read(dir).next().topic()).isEqualTo("프로세스와 스레드");
    }

    /**
     * <b>Task 7(2026-09-22)에서 뜻이 바뀐 테스트다.</b> 예전 이름은
     * {@code skipsEntryWithUnknownDomain}이었고 "SPRING"(형식은 맞지만 기본 11개에 없는 이름)을
     * 썼다. 그런데 {@link TopicQueue#parseDomain}이 이제 "기본 분야에 있는가"를 안 묻는다 —
     * 관리자가 화면에서 추가한 새 분야도 형식만 맞으면 이 대기열에서 통해야 하기 때문이다
     * ({@code parseDomain} Javadoc 참고). 그래서 "SPRING"은 더 이상 걸러지지 않는다 — 지금 이
     * 클래스가 여전히 걸러야 하는 것은 <b>형식 자체가 틀린</b> 값(소문자·하이픈 등)뿐이라,
     * 그 경우로 바꿔 같은 성질("건너뛰고 사유를 남긴다")을 계속 지킨다. "등록되지 않은 분야"를
     * 막는 일은 이제 저장하는 쪽의 몫이다({@code TopicQueueService.adopt}의
     * {@code domainCatalog.exists} 확인 참고).
     */
    @Test
    @DisplayName("형식이 틀린 분야는 건너뛰고 사유를 남긴다 — 대충 주기 분야로 메우면 나흘이 통째로 엉킨다")
    void skipsEntryWithMalformedDomain() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "spring-boot", "topic": "빈 생명주기" },
                    { "domain": "OS", "topic": "컨텍스트 스위칭" }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        TopicQueue.Picked picked = queue.next();

        assertThat(picked.domain()).isEqualTo(TestDomains.OS);
        assertThat(queue.problems()).hasSize(1);
        // 몇 번째 항목이 왜 걸렸는지가 사유에 있어야 파일을 열어 고칠 수 있다
        assertThat(queue.problems().get(0)).contains("빈 생명주기").contains("spring-boot");
        // 사람에게 <이제 없는 용어>로 적으라고 시키면 안 된다(머지 전 점검 1). enum이 사라졌으므로
        // "Domain 상수명"이 아니라 분야 설정 화면에 그대로 뜨는 말인 "분야 코드"여야 한다.
        assertThat(queue.problems().get(0)).contains("분야 코드").doesNotContain("상수명");
    }

    /**
     * 첫 번째 유효 항목 <b>뒤에</b> 있는 오타도 잡아야 한다. 지나가는 길에만 검사하면
     * 5번 항목의 오타는 앞의 넷을 다 쓸 때까지(2주쯤) 아무도 모른다 — 그때는 이미
     * "왜 내가 적은 주제가 안 나오지?"를 겪은 뒤다.
     *
     * <p>Task 7에서 "SPRING" → "spring-boot"(형식이 틀린 값)로 바꿨다 — 위
     * {@link #skipsEntryWithMalformedDomain}과 같은 이유다.
     */
    @Test
    @DisplayName("뒤쪽 항목의 오타도 지금 알려 준다 — 다 쓴 뒤에 알면 이미 늦다")
    void reportsProblemsBehindTheFirstUsableEntry() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "OS", "topic": "컨텍스트 스위칭" },
                    { "domain": "spring-boot", "topic": "빈 생명주기" },
                    { "domain": "NETWORK", "topic": "  " }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);

        assertThat(queue.next().domain()).isEqualTo(TestDomains.OS);
        assertThat(queue.problems()).hasSize(2);
        assertThat(queue.size()).as("형식이 틀린 항목은 개수에 넣지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("분야가 비어 있어도 건너뛴다 — 문서의 분야는 이어지는 사흘치 문제의 분야까지 정한다")
    void skipsEntryWithoutDomain() throws Exception {
        write("""
                { "topics": [ { "topic": "빈 생명주기" }, { "domain": "OS", "topic": "페이지 폴트" } ] }
                """);

        assertThat(TopicQueue.read(dir).next().topic()).isEqualTo("페이지 폴트");
    }

    @Test
    @DisplayName("분야는 소문자·공백을 봐준다 — 손으로 적는 파일에서 그것까지 버리는 건 엄격한 게 아니라 불친절한 것이다")
    void acceptsLowercaseDomain() throws Exception {
        write("{ \"topics\": [ { \"domain\": \" backend_framework \", \"topic\": \"AOP 프록시\" } ] }");

        assertThat(TopicQueue.read(dir).next().domain()).isEqualTo(TestDomains.BACKEND_FRAMEWORK);
    }

    /* ── 사용 기록(되쓰기) ────────────────────────────────────── */

    @Test
    @DisplayName("사용 기록은 고른 줄에만 남고, 다음 차례는 다른 범위로 넘어간다")
    void recordsUseOnPickedEntryOnly() throws Exception {
        write("""
                {
                  "note": "내가 적어 둔 설명",
                  "topics": [
                    { "domain": "OS", "topic": "메모리 관리", "memo": "왜 느려지는지" },
                    { "domain": "NETWORK", "topic": "TCP" }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        assertThat(queue.markUsed(dir, queue.next(), LocalDate.of(2026, 8, 19))).isTrue();

        String saved = Files.readString(dir.resolve(TopicQueue.FILE_NAME));
        assertThat(saved).contains("\"lastUsedAt\" : \"2026-08-19\"");
        assertThat(saved).contains("\"usedCount\" : 1");
        assertThat(saved).contains("내가 적어 둔 설명");   // note를 배치가 지우면 사용법이 사라진다
        assertThat(saved).contains("왜 느려지는지");       // memo도 보존한다(사람 몫의 기록)

        // 다시 읽으면 아직 안 쓴 범위가 차례를 받는다 — 이게 안 되면 한 범위만 계속 걸린다
        TopicQueue reread = TopicQueue.read(dir);
        assertThat(reread.next().topic()).isEqualTo("TCP");
        assertThat(reread.size()).as("쓴 범위도 목록에 남는다").isEqualTo(2);
    }

    /**
     * 편수는 우물이 마르는 것을 알아채는 유일한 신호다. 쓸 때마다 늘지 않으면
     * "Spring으로 벌써 스무 편"을 영영 모른다.
     */
    @Test
    @DisplayName("같은 범위를 다시 쓰면 편수가 하나 늘어난다")
    void incrementsUsedCount() throws Exception {
        write("""
                { "topics": [ { "domain": "OS", "topic": "메모리 관리", "lastUsedAt": "2026-08-11", "usedCount": 3 } ] }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        queue.markUsed(dir, queue.next(), LocalDate.of(2026, 8, 19));

        assertThat(Files.readString(dir.resolve(TopicQueue.FILE_NAME))).contains("\"usedCount\" : 4");
    }

    @Test
    @DisplayName("note가 없던 파일에는 사용법 설명을 채워 넣는다 — 파일 자신이 쓰는 법을 들고 있게 한다")
    void fillsDefaultNoteWhenMissing() throws Exception {
        write("{ \"topics\": [ { \"domain\": \"OS\", \"topic\": \"메모리 관리\" } ] }");

        TopicQueue queue = TopicQueue.read(dir);
        queue.markUsed(dir, queue.next(), LocalDate.of(2026, 8, 19));

        assertThat(Files.readString(dir.resolve(TopicQueue.FILE_NAME))).contains("가장 오래 안 쓴 범위");
    }

    /* ── 실물 파일 ────────────────────────────────────────────── */

    /**
     * 저장소에 커밋된 <b>실제</b> 대기열이 읽히는지 본다.
     *
     * <p><b>왜 실물까지 보나.</b> 이 파일은 사람이 손으로 고치고, 틀려도 배치는 초록불로 끝난다 —
     * 분야 상수명을 하나 잘못 적으면 그 주제만 조용히 건너뛰어지고, 몇 주 뒤 "왜 내가 적은
     * 주제가 안 나오지?"에서야 알게 된다. 커밋 전에 잡을 수 있는 종류의 실수라 CI에 맡긴다.
     *
     * <p>남은 주제 개수는 검사하지 않는다 — 다 쓰면 0이 되는 것이 정상이고,
     * 그때마다 테스트가 깨지면 그 테스트는 곧 지워진다.
     */
    @Test
    @DisplayName("저장소의 generated/_topics.json에 건너뛸 항목이 없다 — 분야 오타는 조용히 사라진다")
    void repositoryTopicFileHasNoSkippedEntry() {
        assertThat(TopicQueue.read(Path.of("generated")).problems()).isEmpty();
    }

    private void write(String json) throws Exception {
        Files.writeString(dir.resolve(TopicQueue.FILE_NAME), json);
    }
}
