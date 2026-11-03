package project.study.study_project.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import project.study.study_project.llm.dto.GeneratedDiff;
import project.study.study_project.llm.dto.GeneratedPending;
import project.study.study_project.llm.dto.GeneratedPublishResult;
import project.study.study_project.llm.dto.GeneratedPublishResult.Status;
import project.study.study_project.llm.support.GitWorkspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치로 보내기(docs/29 9장). 실제 저장소는 건드리지 않는다 — 임시 폴더에 원격 역할의 빈 저장소와
 * 복제본 둘(내 PC, 배치)을 만들어 쓴다.
 */
class GeneratedPublisherTest {

    @TempDir
    Path tmp;

    Path remote;
    Path local;
    Path other;
    GitWorkspace localGit;
    GitWorkspace otherGit;
    GeneratedPublisher publisher;

    @BeforeEach
    void setUp() throws IOException {
        remote = tmp.resolve("remote.git");
        Files.createDirectories(remote);
        new GitWorkspace(remote).runOrThrow("init", "-q", "--bare", "--initial-branch=main");

        local = clone("local");
        localGit = new GitWorkspace(local);
        write(local, "generated/_topics.json", "{\"topics\":[1]}\n");
        write(local, "generated/_existing-questions.json", "{\"q\":[]}\n");
        write(local, "generated/documents/2026-10-29.json", "{\"doc\":\"v1\"}\n");
        write(local, "generated/2026-11-02.json", "{\"batch\":1}\n");
        write(local, "src/A.java", "class A {}\n");
        write(local, "src/B.java", "class B {}\n");
        localGit.runOrThrow("add", "-A");
        localGit.runOrThrow("commit", "-q", "-m", "init");
        localGit.runOrThrow("push", "-q", "-u", "origin", "main");

        other = clone("other");
        otherGit = new GitWorkspace(other);

        publisher = new GeneratedPublisher(local.resolve("generated"));
    }

    @Test
    void 범위_밖_파일은_보내지_않는다() throws IOException {
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");
        write(local, "generated/2026-11-02.json", "{\"batch\":\"손으로 고침\"}\n");
        write(local, "src/A.java", "class A { int x; }\n");

        GeneratedPublishResult result = publisher.publish();

        assertThat(result.status()).isEqualTo(Status.PUBLISHED);
        assertThat(result.files()).containsExactly("generated/_topics.json");
        assertThat(committedFiles("origin/main")).containsExactly("generated/_topics.json");
        assertThat(dirtyFiles()).containsExactlyInAnyOrder("generated/2026-11-02.json", "src/A.java");
        assertThat(subject("origin/main")).isEqualTo(GeneratedPublisher.SUBJECT_PREFIX + "1개를 배치로 보낸다");
    }

    @Test
    void 스테이지해_둔_다른_파일은_커밋에_섞이지_않고_스테이지에_남는다() throws IOException {
        write(local, "src/A.java", "class A { int staged; }\n");
        localGit.runOrThrow("add", "src/A.java");
        write(local, "generated/_existing-questions.json", "{\"q\":[1]}\n");

        GeneratedPublishResult result = publisher.publish();

        assertThat(result.status()).isEqualTo(Status.PUBLISHED);
        assertThat(committedFiles("HEAD")).containsExactly("generated/_existing-questions.json");
        assertThat(GitWorkspace.splitNul(localGit.runOrThrow("diff", "--cached", "--name-only", "-z").out()))
                .containsExactly("src/A.java");
    }

    @Test
    void 새_파일도_보내고_종류와_줄_수를_알려_준다() throws IOException {
        write(local, "generated/_code-topics.json", "{\n\"a\":1\n}\n");
        write(local, "generated/documents/2026-10-29.json", "{\"doc\":\"v2\"}\n");

        GeneratedPending pending = publisher.pending();

        assertThat(pending.available()).isTrue();
        assertThat(pending.blockers()).isEmpty();
        assertThat(pending.files()).extracting(GeneratedPending.File::path)
                .containsExactly("generated/_code-topics.json", "generated/documents/2026-10-29.json");
        GeneratedPending.File added = pending.files().get(0);
        assertThat(added.kind()).isEqualTo(GeneratedPending.Kind.SNAPSHOT);
        assertThat(added.change()).isEqualTo(GeneratedPending.Change.ADDED);
        assertThat(added.added()).isEqualTo(3);
        GeneratedPending.File doc = pending.files().get(1);
        assertThat(doc.kind()).isEqualTo(GeneratedPending.Kind.SOURCE_DOCUMENT);
        assertThat(doc.added()).isEqualTo(1);
        assertThat(doc.deleted()).isEqualTo(1);
        assertThat(doc.modifiedAt()).isNotNull();

        assertThat(publisher.publish().status()).isEqualTo(Status.PUBLISHED);
        assertThat(publisher.pending().files()).isEmpty();
    }

    @Test
    void 원격이_앞서_있으면_rebase한_뒤_보내고_사람이_고치던_파일은_그대로다() throws IOException {
        pushFromOther("generated/2026-11-03.json", "{\"batch\":2}\n");
        write(local, "src/B.java", "class B { int dirty; }\n");
        write(local, "src/A.java", "class A { int staged; }\n");
        localGit.runOrThrow("add", "src/A.java");
        write(local, "generated/_topics.json", "{\"topics\":[3]}\n");

        GeneratedPublishResult result = publisher.publish();

        assertThat(result.status()).isEqualTo(Status.PUBLISHED);
        assertThat(GitWorkspace.splitNul(localGit.runOrThrow("diff", "--cached", "--name-only", "-z").out()))
                .containsExactly("src/A.java");
        assertThat(localGit.runOrThrow("stash", "list").out()).isBlank();
        assertThat(localGit.runOrThrow("rev-parse", "HEAD~1").out().strip())
                .isEqualTo(otherGit.runOrThrow("rev-parse", "HEAD").out().strip());
        assertThat(Files.readString(local.resolve("src/B.java"))).isEqualTo("class B { int dirty; }\n");
        assertThat(Files.exists(local.resolve("generated/2026-11-03.json"))).isTrue();
    }

    @Test
    void rebase가_충돌하면_누르기_전_상태로_돌아온다() throws IOException {
        pushFromOther("generated/_topics.json", "{\"topics\":[\"배치\"]}\n");
        String before = head();
        write(local, "generated/_topics.json", "{\"topics\":[\"앱\"]}\n");
        write(local, "src/A.java", "class A { int staged; }\n");
        localGit.runOrThrow("add", "src/A.java");

        GeneratedPublishResult result = publisher.publish();

        assertThat(result.status()).as(result.toString()).isEqualTo(Status.CONFLICT);
        assertThat(result.details()).contains("generated/_topics.json");
        assertThat(head()).isEqualTo(before);
        assertThat(Files.readString(local.resolve("generated/_topics.json"))).isEqualTo("{\"topics\":[\"앱\"]}\n");
        assertThat(dirtyFiles()).containsExactlyInAnyOrder("generated/_topics.json", "src/A.java");
        assertThat(GitWorkspace.splitNul(localGit.runOrThrow("diff", "--cached", "--name-only", "-z").out()))
                .containsExactly("src/A.java");
        assertThat(Files.exists(local.resolve(".git/rebase-merge"))).isFalse();
    }

    @Test
    void main이_아니면_멈춘다() throws IOException {
        localGit.runOrThrow("checkout", "-q", "-b", "work");
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");

        assertBlockedWithoutCommit();
    }

    @Test
    void 진행_중인_git_작업이_있으면_멈춘다() throws IOException {
        Files.createDirectories(local.resolve(".git/rebase-merge"));
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");

        assertThat(publisher.pending().blockers()).anyMatch(b -> b.contains("rebase-merge"));
        assertBlockedWithoutCommit();
    }

    @Test
    void 사람이_안_올린_커밋이_있으면_멈춘다() throws IOException {
        write(local, "src/A.java", "class A { int mine; }\n");
        localGit.runOrThrow("commit", "-q", "-am", "feat: 작업 중");
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");

        GeneratedPublishResult result = assertBlockedWithoutCommit();
        assertThat(result.details()).anyMatch(d -> d.contains("feat: 작업 중"));
    }

    @Test
    void 원격이_바꾼_파일을_사람도_고치고_있으면_멈춘다() throws IOException {
        pushFromOther("src/A.java", "class A { int remote; }\n");
        write(local, "src/A.java", "class A { int local; }\n");
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");

        GeneratedPublishResult result = assertBlockedWithoutCommit();
        assertThat(result.details()).containsExactly("src/A.java");
    }

    @Test
    void push가_실패하면_커밋을_남기고_다음에_push만_한다() throws IOException {
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");
        // fetch는 되고 push만 거부되게 한다. 원격의 push 주소만 없는 곳으로 바꾼다
        localGit.runOrThrow("remote", "set-url", "--push", "origin", tmp.resolve("nowhere.git").toString());

        GeneratedPublishResult failed = publisher.publish();

        assertThat(failed.status()).isEqualTo(Status.PUSH_FAILED);
        assertThat(subject("HEAD")).startsWith(GeneratedPublisher.SUBJECT_PREFIX);
        assertThat(publisher.pending().unpushedCommits()).isEqualTo(1);
        assertThat(publisher.pending().blockers()).isEmpty();

        localGit.runOrThrow("remote", "set-url", "--push", "origin", remote.toString());
        GeneratedPublishResult retried = publisher.publish();

        assertThat(retried.status()).isEqualTo(Status.PUBLISHED);
        assertThat(retried.files()).isEmpty();
        assertThat(subject("origin/main")).startsWith(GeneratedPublisher.SUBJECT_PREFIX);
    }

    @Test
    void 원격에_닿지_못하면_커밋하지_않고_실패로_알린다() throws IOException {
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");
        localGit.runOrThrow("remote", "set-url", "origin", tmp.resolve("nowhere.git").toString());
        String before = head();

        GeneratedPublishResult result = publisher.publish();

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(head()).isEqualTo(before);
        assertThat(dirtyFiles()).contains("generated/_topics.json");
    }

    @Test
    void rebase가_시간을_넘기면_되돌리고_사람이_고치던_파일을_꺼내_둔다() throws IOException {
        pushFromOther("generated/2026-11-03.json", "{\"batch\":2}\n");
        write(local, "src/B.java", "class B { int dirty; }\n");
        write(local, "generated/_topics.json", "{\"topics\":[3]}\n");
        // rebase 시작 전에 멈추는 훅으로 시간 초과를 만든다. 다른 git 명령은 제한 안에 끝난다
        // 훅은 먼저 폴더를 떠난다. 끊긴 git이 남긴 sleep이 임시 폴더를 붙잡으면 테스트 정리가 실패한다
        Files.writeString(local.resolve(".git/hooks/pre-rebase"), "#!/bin/sh\ncd /\nsleep 8\n");
        local.resolve(".git/hooks/pre-rebase").toFile().setExecutable(true);
        GeneratedPublisher slow = new GeneratedPublisher(local.resolve("generated"),
                java.time.Clock.systemUTC(), java.time.Duration.ofSeconds(4));

        GeneratedPublishResult result = slow.publish();

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(Files.exists(local.resolve(".git/rebase-merge"))).isFalse();
        assertThat(Files.exists(local.resolve(".git/rebase-apply"))).isFalse();
        assertThat(localGit.runOrThrow("stash", "list").out()).as("치워 둔 작업을 꺼냈다").isBlank();
        assertThat(Files.readString(local.resolve("src/B.java"))).isEqualTo("class B { int dirty; }\n");
        assertThat(subject("HEAD")).as("이번 커밋은 남겨 다음에 push만 한다").startsWith(GeneratedPublisher.SUBJECT_PREFIX);
        waitForOrphanHook();
    }

    /** 끊긴 git이 남긴 훅(sleep 8)이 끝나기를 기다린다. Windows는 쓰는 중인 파일이 있는 폴더를 못 지운다 */
    private static void waitForOrphanHook() {
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void 보낼_것이_없으면_아무것도_하지_않는다() {
        String before = head();

        assertThat(publisher.publish().status()).isEqualTo(Status.NOTHING);
        assertThat(head()).isEqualTo(before);
    }

    @Test
    void 저장소가_아니면_쓸_수_없다고_답한다() throws IOException {
        Path plain = Files.createDirectories(tmp.resolve("plain/generated"));
        GeneratedPublisher outside = new GeneratedPublisher(plain);

        assertThat(outside.pending().available()).isFalse();
        assertThat(outside.publish().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void 배지_요약은_15초_동안_다시_세지_않고_보낸_뒤에는_새로_센다() throws IOException {
        MutableClock clock = new MutableClock();
        GeneratedPublisher timed = new GeneratedPublisher(local.resolve("generated"), clock);
        write(local, "generated/_topics.json", "{\"topics\":[2]}\n");
        assertThat(timed.summary().count()).isEqualTo(1);
        assertThat(timed.summary().oldestAt()).isNotNull();

        write(local, "generated/_existing-questions.json", "{\"q\":[9]}\n");
        assertThat(timed.summary().count()).isEqualTo(1);

        clock.advance(GeneratedPublisher.SUMMARY_TTL.plusSeconds(1));
        assertThat(timed.summary().count()).isEqualTo(2);

        assertThat(timed.publish().status()).isEqualTo(Status.PUBLISHED);
        assertThat(timed.summary().count()).isZero();
    }

    @Test
    void 고친_파일의_바뀐_줄을_보여_준다() throws IOException {
        write(local, "generated/_topics.json", "{\"topics\":[1]}\n{\"memo\":\"새 주제\"}\n");

        GeneratedDiff diff = publisher.diff("generated/_topics.json");

        assertThat(diff.error()).isEmpty();
        assertThat(diff.lines().get(0)).startsWith("@@");
        assertThat(diff.lines()).contains("+{\"memo\":\"새 주제\"}");
        assertThat(diff.truncated()).isFalse();
    }

    @Test
    void 새_파일은_모든_줄을_더한_줄로_보여_준다() throws IOException {
        write(local, "generated/_code-topics.json", "{\n\"a\":1\n}\n");

        assertThat(publisher.diff("generated/_code-topics.json").lines()).containsExactly("+{", "+\"a\":1", "+}");
    }

    @Test
    void 보낼_파일_목록에_없는_경로는_읽지_않는다() throws IOException {
        write(local, "src/A.java", "class A { String secret; }\n");

        GeneratedDiff outside = publisher.diff("src/A.java");
        GeneratedDiff unchanged = publisher.diff("generated/_existing-questions.json");

        assertThat(outside.lines()).isEmpty();
        assertThat(outside.error()).contains("보낼 파일 목록에 없습니다");
        assertThat(unchanged.error()).isNotEmpty();
    }

    @Test
    void 너무_길면_앞부분만_싣는다() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < GeneratedPublisher.DIFF_MAX_LINES + 50; i++) {
            big.append("line ").append(i).append('\n');
        }
        write(local, "generated/_rejection-notes.json", big.toString());

        GeneratedDiff diff = publisher.diff("generated/_rejection-notes.json");

        assertThat(diff.truncated()).isTrue();
        assertThat(diff.lines()).hasSize(GeneratedPublisher.DIFF_MAX_LINES);
    }

    @Test
    void GitHub_원격이면_커밋_주소를_만든다() {
        assertThat(GeneratedPublisher.commitUrl("https://github.com/minsu/study_project.git", "abc"))
                .isEqualTo("https://github.com/minsu/study_project/commit/abc");
        assertThat(GeneratedPublisher.commitUrl("git@github.com:minsu/study_project.git", "abc"))
                .isEqualTo("https://github.com/minsu/study_project/commit/abc");
        assertThat(GeneratedPublisher.commitUrl("https://github.com/minsu/study_project", "abc"))
                .isEqualTo("https://github.com/minsu/study_project/commit/abc");
        assertThat(GeneratedPublisher.commitUrl(tmp.resolve("remote.git").toString(), "abc")).isEmpty();
    }

    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-11-02T00:00:00Z");

        void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    // ---------------------------------------------------------------- 도우미

    private GeneratedPublishResult assertBlockedWithoutCommit() {
        String before = head();
        GeneratedPublishResult result = publisher.publish();
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
        assertThat(head()).isEqualTo(before);
        assertThat(dirtyFiles()).contains("generated/_topics.json");
        return result;
    }

    private Path clone(String name) {
        Path dir = tmp.resolve(name);
        // 줄바꿈 설정은 복제할 때 넘긴다. 복제 뒤에 바꾸면 이미 CRLF로 풀린 파일이 전부 바뀐 것으로 잡힌다
        new GitWorkspace(tmp).runOrThrow("clone", "-q", "-c", "core.autocrlf=false", remote.toString(), dir.toString());
        GitWorkspace git = new GitWorkspace(dir);
        git.runOrThrow("config", "user.name", "tester");
        git.runOrThrow("config", "user.email", "tester@example.com");
        git.runOrThrow("config", "commit.gpgsign", "false");
        return dir;
    }

    private void pushFromOther(String path, String content) throws IOException {
        otherGit.runOrThrow("pull", "-q", "origin", "main");
        write(other, path, content);
        otherGit.runOrThrow("add", "-A");
        otherGit.runOrThrow("commit", "-q", "-m", "chore(llm): 배치");
        otherGit.runOrThrow("push", "-q", "origin", "main");
    }

    private String head() {
        return localGit.runOrThrow("rev-parse", "HEAD").out().strip();
    }

    private String subject(String rev) {
        return localGit.runOrThrow("log", "-1", "--format=%s", rev).out().strip();
    }

    private List<String> committedFiles(String rev) {
        return GitWorkspace.splitNul(localGit.runOrThrow("show", "--name-only", "-z", "--format=", rev).out())
                .stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> dirtyFiles() {
        return GitWorkspace.splitNul(localGit.runOrThrow("status", "--porcelain=v1", "-z", "--untracked-files=all").out())
                .stream().map(s -> s.substring(3)).toList();
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
