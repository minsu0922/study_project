package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.global.common.Domain;

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
    @DisplayName("파일이 없으면 빈 대기열이다 — 아직 안 만들었거나 다 쓴 상태이지 오류가 아니다")
    void emptyWhenFileMissing() {
        TopicQueue queue = TopicQueue.read(dir);

        assertThat(queue.next()).isNull();
        assertThat(queue.pendingCount()).isZero();
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
    @DisplayName("위에서부터 첫 번째 미사용 항목을 꺼낸다 — 적은 순서가 곧 공부하고 싶은 순서다")
    void picksFirstPendingInOrder() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "DATABASE", "topic": "복합 인덱스", "usedAt": "2026-08-15" },
                    { "domain": "BACKEND_FRAMEWORK", "topic": "@Transactional 전파 속성" },
                    { "domain": "NETWORK", "topic": "TIME_WAIT" }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        TopicQueue.Picked picked = queue.next();

        assertThat(picked.index()).isEqualTo(1);
        assertThat(picked.topic()).isEqualTo("@Transactional 전파 속성");
        assertThat(picked.domain()).isEqualTo(Domain.BACKEND_FRAMEWORK);
        assertThat(queue.pendingCount()).isEqualTo(2); // 이미 쓴 것은 세지 않는다
    }

    @Test
    @DisplayName("분야가 잘못된 항목은 건너뛰고 사유를 남긴다 — 대충 주기 분야로 메우면 나흘이 통째로 엉킨다")
    void skipsEntryWithUnknownDomain() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "SPRING", "topic": "빈 생명주기" },
                    { "domain": "OS", "topic": "컨텍스트 스위칭" }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        TopicQueue.Picked picked = queue.next();

        assertThat(picked.domain()).isEqualTo(Domain.OS);
        assertThat(queue.problems()).hasSize(1);
        // 몇 번째 항목이 왜 걸렸는지가 사유에 있어야 파일을 열어 고칠 수 있다
        assertThat(queue.problems().get(0)).contains("빈 생명주기").contains("SPRING");
    }

    /**
     * 첫 번째 유효 항목 <b>뒤에</b> 있는 오타도 잡아야 한다. 지나가는 길에만 검사하면
     * 5번 항목의 오타는 앞의 넷을 다 쓸 때까지(2주쯤) 아무도 모른다 — 그때는 이미
     * "왜 내가 적은 주제가 안 나오지?"를 겪은 뒤다.
     */
    @Test
    @DisplayName("뒤쪽 항목의 오타도 지금 알려 준다 — 다 쓴 뒤에 알면 이미 늦다")
    void reportsProblemsBehindTheFirstUsableEntry() throws Exception {
        write("""
                {
                  "topics": [
                    { "domain": "OS", "topic": "컨텍스트 스위칭" },
                    { "domain": "SPRING", "topic": "빈 생명주기" },
                    { "domain": "NETWORK", "topic": "  " }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);

        assertThat(queue.next().domain()).isEqualTo(Domain.OS);
        assertThat(queue.problems()).hasSize(2);
        assertThat(queue.pendingCount()).as("형식이 틀린 항목은 '남은 주제'에 세지 않는다").isEqualTo(1);
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

        assertThat(TopicQueue.read(dir).next().domain()).isEqualTo(Domain.BACKEND_FRAMEWORK);
    }

    @Test
    @DisplayName("전부 사용 표시가 돼 있으면 아무것도 꺼내지 않는다 — 자동 선택으로 흘려보낸다")
    void returnsNullWhenAllUsed() throws Exception {
        write("""
                { "topics": [ { "domain": "OS", "topic": "페이지 폴트", "usedAt": "2026-08-11" } ] }
                """);

        assertThat(TopicQueue.read(dir).next()).isNull();
    }

    /* ── 사용 표시(되쓰기) ────────────────────────────────────── */

    @Test
    @DisplayName("사용 표시를 하면 그 항목만 usedAt이 찍히고 나머지는 그대로다")
    void marksOnlyPickedEntry() throws Exception {
        write("""
                {
                  "note": "내가 적어 둔 설명",
                  "topics": [
                    { "domain": "OS", "topic": "페이지 폴트", "memo": "왜 느려지는지" },
                    { "domain": "NETWORK", "topic": "TIME_WAIT" }
                  ]
                }
                """);

        TopicQueue queue = TopicQueue.read(dir);
        assertThat(queue.markUsed(dir, queue.next(), LocalDate.of(2026, 8, 19))).isTrue();

        String saved = Files.readString(dir.resolve(TopicQueue.FILE_NAME));
        assertThat(saved).contains("\"usedAt\" : \"2026-08-19\"");
        assertThat(saved).contains("내가 적어 둔 설명");   // note를 배치가 지우면 사용법이 사라진다
        assertThat(saved).contains("왜 느려지는지");       // memo도 보존한다(사람 몫의 기록)
        assertThat(saved).contains("TIME_WAIT");           // 남은 항목은 손대지 않는다

        // 다시 읽으면 다음 주제로 넘어가 있어야 한다 — 이게 안 되면 같은 주제가 매 주기 나온다
        TopicQueue reread = TopicQueue.read(dir);
        assertThat(reread.next().topic()).isEqualTo("TIME_WAIT");
        assertThat(reread.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("note가 없던 파일에는 사용법 설명을 채워 넣는다 — 파일 자신이 쓰는 법을 들고 있게 한다")
    void fillsDefaultNoteWhenMissing() throws Exception {
        write("{ \"topics\": [ { \"domain\": \"OS\", \"topic\": \"페이지 폴트\" } ] }");

        TopicQueue queue = TopicQueue.read(dir);
        queue.markUsed(dir, queue.next(), LocalDate.of(2026, 8, 19));

        assertThat(Files.readString(dir.resolve(TopicQueue.FILE_NAME))).contains("usedAt이 있으면");
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
