package project.study.study_project.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import project.study.study_project.llm.dto.GeneratedDiff;
import project.study.study_project.llm.dto.GeneratedPending;
import project.study.study_project.llm.dto.GeneratedPending.Change;
import project.study.study_project.llm.dto.GeneratedPending.Kind;
import project.study.study_project.llm.dto.GeneratedPublishResult;
import project.study.study_project.llm.dto.GeneratedPublishResult.Status;
import project.study.study_project.llm.dto.GeneratedPublishSummary;
import project.study.study_project.llm.support.GitWorkspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 앱이 고친 현황 파일·원문 문서 파일을 커밋해 배치가 읽는 원격 {@code main}에 보낸다(docs/29).
 *
 * <p>사용자가 작업 중인 저장소에서 git을 실행한다. 그래서 보내는 파일을 두 경로로 좁히고,
 * 사용자 작업이 섞이거나 망가질 수 있으면 아무것도 하지 않고 이유를 돌려준다(5장 점검 넷).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.publish.enabled", havingValue = "true", matchIfMissing = true)
public class GeneratedPublisher {

    static final String REMOTE = "origin";
    static final String BRANCH = "main";
    static final String UPSTREAM = REMOTE + "/" + BRANCH;
    /** 이 앱이 만든 커밋을 알아보는 표지. 바꾸면 push만 실패해 남은 옛 커밋을 남의 커밋으로 본다 */
    static final String SUBJECT_PREFIX = "chore(generated): 관리 콘솔에서 바뀐 파일 ";

    /** 진행 중인 git 작업의 표지 파일. 하나라도 있으면 사람이 무언가를 풀던 중이다 */
    private static final List<String> IN_PROGRESS_MARKERS =
            List.of("rebase-merge", "rebase-apply", "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD");

    /** 배지 요약을 다시 쓰는 시간. 화면을 옮길 때마다 git status가 돌지 않게 한다 */
    static final Duration SUMMARY_TTL = Duration.ofSeconds(15);

    private final Path importDir;
    private final Clock clock;
    private final Duration gitTimeout;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile CachedSummary cachedSummary;

    private record CachedSummary(GeneratedPublishSummary summary, Instant at) {
    }

    @Autowired
    public GeneratedPublisher(@Value("${llm.import.dir:generated}") String importDir) {
        this(Path.of(importDir));
    }

    GeneratedPublisher(Path importDir) {
        this(importDir, Clock.systemUTC());
    }

    GeneratedPublisher(Path importDir, Clock clock) {
        this(importDir, clock, GitWorkspace.DEFAULT_TIMEOUT);
    }

    /** @param gitTimeout git 명령 하나의 시간 제한. 테스트가 시간 초과 경로를 보려고 줄인다 */
    GeneratedPublisher(Path importDir, Clock clock, Duration gitTimeout) {
        this.importDir = importDir;
        this.clock = clock;
        this.gitTimeout = gitTimeout;
    }

    /** 배지용 요약. {@link #SUMMARY_TTL} 동안은 앞서 센 값을 돌려주고, 보낸 뒤에는 새로 센다. */
    public GeneratedPublishSummary summary() {
        CachedSummary cached = cachedSummary;
        Instant now = clock.instant();
        if (cached != null && cached.at().plus(SUMMARY_TTL).isAfter(now)) {
            return cached.summary();
        }
        GeneratedPublishSummary summary;
        try {
            summary = GeneratedPublishSummary.of(pending());
        } catch (GitWorkspace.TimeoutException | IllegalStateException e) {
            // 배지는 모든 화면이 부른다. git이 막혀도 다른 배지는 떠야 한다
            log.warn("보낼 변경을 세지 못했습니다: {}", e.getMessage());
            summary = GeneratedPublishSummary.UNAVAILABLE;
        }
        cachedSummary = new CachedSummary(summary, now);
        return summary;
    }

    /** 저장소 루트에서 도는 git과, 루트 기준 {@code generated/} 경로. */
    private record Repo(GitWorkspace git, String prefix) {

        List<String> pathspecs() {
            return List.of(":(glob)" + prefix + "/_*.json", ":(glob)" + prefix + "/documents/*.json");
        }
    }

    private record Resolved(Repo repo, String unavailableReason) {
    }

    // ---------------------------------------------------------------- 조회

    public GeneratedPending pending() {
        Resolved resolved = resolve();
        if (resolved.repo() == null) {
            return GeneratedPending.unavailable(resolved.unavailableReason());
        }
        Repo repo = resolved.repo();
        List<String> blockers = new ArrayList<>(stateBlockers(repo));
        Unpushed unpushed = unpushed(repo);
        blockers.addAll(unpushed.blockers());
        return new GeneratedPending(true, "", scopeFiles(repo), unpushed.own(), blockers);
    }

    // ---------------------------------------------------------------- 내용 보기

    /** 화면에 싣는 줄 수 상한. 현황 파일은 수백 줄이 바뀌기도 해 다 실으면 화면이 멈춘다 */
    static final int DIFF_MAX_LINES = 400;

    /**
     * 보낼 파일 하나의 바뀐 줄. 경로는 보낼 파일 목록에 있어야 한다 — 화면이 넘긴 경로로
     * 저장소의 아무 파일이나 읽지 못하게 한다.
     */
    public GeneratedDiff diff(String path) {
        Resolved resolved = resolve();
        if (resolved.repo() == null) {
            return GeneratedDiff.error(path, resolved.unavailableReason());
        }
        Repo repo = resolved.repo();
        GeneratedPending.File file = scopeFiles(repo).stream()
                .filter(f -> f.path().equals(path))
                .findFirst().orElse(null);
        if (file == null) {
            return GeneratedDiff.error(path, "보낼 파일 목록에 없습니다. 이미 보냈거나 다시 바뀌었을 수 있습니다.");
        }
        List<String> lines;
        if (file.change() == Change.ADDED && !tracked(repo, path)) {
            try {
                lines = Files.readString(repo.git().dir().resolve(path), StandardCharsets.UTF_8)
                        .lines().map(l -> "+" + l).toList();
            } catch (IOException e) {
                return GeneratedDiff.error(path, "파일을 읽지 못했습니다: " + e.getMessage());
            }
        } else {
            String out = repo.git().runOrThrow("diff", "HEAD", "--no-color", "--no-ext-diff", "-U1", "--", path).out();
            // diff --git·index·---·+++ 머리는 화면에 쓸모가 없다. 첫 @@부터 싣는다
            lines = out.lines()
                    .map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l)
                    .dropWhile(l -> !l.startsWith("@@"))
                    .filter(l -> !l.startsWith("\\ No newline"))
                    .toList();
        }
        boolean truncated = lines.size() > DIFF_MAX_LINES;
        return new GeneratedDiff(path, truncated ? lines.subList(0, DIFF_MAX_LINES) : lines, truncated, "");
    }

    private static boolean tracked(Repo repo, String path) {
        return repo.git().run("ls-files", "--error-unmatch", "--", path).ok();
    }

    // ---------------------------------------------------------------- 보내기

    public GeneratedPublishResult publish() {
        if (!lock.tryLock()) {
            return GeneratedPublishResult.of(Status.BUSY, "다른 보내기가 도는 중입니다. 잠시 뒤 다시 누르세요.", List.of());
        }
        try {
            Resolved resolved = resolve();
            if (resolved.repo() == null) {
                return GeneratedPublishResult.of(Status.UNAVAILABLE, resolved.unavailableReason(), List.of());
            }
            return publish(resolved.repo());
        } catch (GitWorkspace.TimeoutException | IllegalStateException e) {
            log.warn("배치로 보내기 실패: {}", e.getMessage());
            return GeneratedPublishResult.of(Status.FAILED, e.getMessage(), List.of());
        } finally {
            cachedSummary = null;
            lock.unlock();
        }
    }

    private GeneratedPublishResult publish(Repo repo) {
        GitWorkspace git = repo.git();
        List<String> blockers = stateBlockers(repo);
        if (!blockers.isEmpty()) {
            return GeneratedPublishResult.of(Status.BLOCKED, blockers.get(0), blockers);
        }
        GitWorkspace.Result fetch = git.run("fetch", "--quiet", REMOTE, BRANCH);
        if (!fetch.ok()) {
            // 아직 아무것도 커밋하지 않았다. PUSH_FAILED("커밋은 남김")가 아니다
            return GeneratedPublishResult.of(Status.FAILED,
                    "원격(origin)에 닿지 못해 보내지 않았습니다: " + fetch.lastErrorLine(), List.of());
        }
        Unpushed unpushed = unpushed(repo);
        if (!unpushed.blockers().isEmpty()) {
            return GeneratedPublishResult.of(Status.BLOCKED,
                    "아직 올리지 않은 커밋이 있어 함께 나갑니다. 먼저 push하세요.", unpushed.blockers());
        }
        List<String> paths = scopeFiles(repo).stream().map(GeneratedPending.File::path).toList();
        List<String> overlap = overlapWithIncoming(repo, paths);
        if (!overlap.isEmpty()) {
            return GeneratedPublishResult.of(Status.BLOCKED,
                    "원격의 새 커밋이 지금 고치고 있는 파일을 바꿨습니다. 먼저 pull하세요.", overlap);
        }
        if (paths.isEmpty() && unpushed.own() == 0) {
            return GeneratedPublishResult.of(Status.NOTHING, "보낼 변경이 없습니다.", List.of());
        }

        boolean committedNow = false;
        if (!paths.isEmpty()) {
            GitWorkspace.Result commit = commit(repo, paths);
            if (!commit.ok()) {
                git.run(withPaths(List.of("reset", "-q", "--"), paths));
                return GeneratedPublishResult.of(Status.FAILED, "커밋하지 못했습니다: " + commit.lastErrorLine(), List.of());
            }
            committedNow = true;
        }
        return rebaseAndPush(repo, paths, committedNow);
    }

    /**
     * 원격이 앞서 있으면 rebase한 뒤 push한다. push가 거부되면(그 사이 배치가 push함) 한 번 더 한다.
     */
    private GeneratedPublishResult rebaseAndPush(Repo repo, List<String> paths, boolean committedNow) {
        GitWorkspace git = repo.git();
        String lastError = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt == 2) {
                GitWorkspace.Result fetch = git.run("fetch", "--quiet", REMOTE, BRANCH);
                List<String> overlap = fetch.ok() ? overlapWithIncoming(repo, List.of()) : List.of();
                if (!fetch.ok() || !overlap.isEmpty()) {
                    break;
                }
            }
            if (behind(repo) > 0) {
                boolean stashed = stashTracked(git);
                GitWorkspace.Result rebase;
                try {
                    rebase = git.run("rebase", "-q", UPSTREAM);
                } catch (RuntimeException e) {
                    // 시간 초과로 rebase를 끊었으면 도중 상태로 남는다. 되돌리고 치워 둔 작업을 꺼낸다.
                    // 이번 커밋은 남긴다. 다음에 누르면 push만 다시 한다
                    abortRebaseIfInProgress(repo);
                    if (stashed) {
                        popStash(git);
                    }
                    throw e;
                }
                if (!rebase.ok()) {
                    List<String> conflicts = GitWorkspace.splitNul(
                            git.run("diff", "--name-only", "--diff-filter=U", "-z").out());
                    git.run("rebase", "--abort");
                    String restore = stashed ? popStash(git) : "";
                    if (committedNow) {
                        undoCommit(repo, paths);
                    }
                    if (!restore.isEmpty()) {
                        conflicts = new ArrayList<>(conflicts);
                        conflicts.add(restore);
                    }
                    log.info("배치로 보내기: rebase 충돌로 되돌림 {}", conflicts);
                    return GeneratedPublishResult.of(Status.CONFLICT,
                            "배치가 같은 파일을 바꿔 충돌했습니다. 누르기 전 상태로 되돌렸습니다. pull한 뒤 충돌을 풀어 주세요.",
                            conflicts);
                }
                String restore = stashed ? popStash(git) : "";
                if (!restore.isEmpty()) {
                    // 겹친 파일은 점검 4가 미리 막아서 여기 올 일은 없다. 와도 사람 작업은 stash에 남아 있다
                    log.warn("배치로 보내기: {}", restore);
                    return GeneratedPublishResult.of(Status.FAILED, restore, List.of());
                }
            }
            GitWorkspace.Result push = git.run("push", "-q", REMOTE, "HEAD:" + BRANCH);
            if (push.ok()) {
                String hash = git.runOrThrow("rev-parse", "HEAD").out().strip();
                log.info("배치로 보냄: {} 파일 {}개", hash, paths.size());
                return new GeneratedPublishResult(Status.PUBLISHED,
                        paths.isEmpty() ? "앞서 남은 커밋을 보냈습니다." : "파일 " + paths.size() + "개를 보냈습니다.",
                        hash, commitUrl(git.run("remote", "get-url", REMOTE).out().strip(), hash), paths, List.of());
            }
            lastError = push.lastErrorLine();
        }
        log.warn("배치로 보내기: push 실패, 커밋은 남김 — {}", lastError);
        return new GeneratedPublishResult(Status.PUSH_FAILED,
                "커밋은 남기고 push만 실패했습니다. 다시 누르면 push만 합니다." + (lastError.isEmpty() ? "" : " (" + lastError + ")"),
                "", "", paths, List.of());
    }

    private static final java.util.regex.Pattern GITHUB_REMOTE =
            java.util.regex.Pattern.compile("github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?/?$");

    /** origin 주소(https·ssh 둘 다)에서 GitHub 커밋 주소를 만든다. GitHub가 아니면 빈 문자열. */
    static String commitUrl(String remoteUrl, String hash) {
        java.util.regex.Matcher m = GITHUB_REMOTE.matcher(remoteUrl);
        return m.find() ? "https://github.com/" + m.group(1) + "/commit/" + hash : "";
    }

    /** 그 파일만 커밋한다. 사람이 스테이지해 둔 다른 파일은 {@code --only}가 커밋에서 뺀다. */
    private GitWorkspace.Result commit(Repo repo, List<String> paths) {
        GitWorkspace git = repo.git();
        git.runOrThrow(withPaths(List.of("add", "-A", "--"), paths).toArray(String[]::new));
        StringBuilder message = new StringBuilder(SUBJECT_PREFIX + paths.size() + "개를 배치로 보낸다\n\n");
        paths.forEach(p -> message.append("- ").append(p).append('\n'));
        Path file = null;
        try {
            file = Files.createTempFile("publish-msg-", ".txt");
            Files.writeString(file, message, StandardCharsets.UTF_8);
            return git.run(withPaths(List.of("commit", "-q", "--only", "-F", file.toString(), "--"), paths));
        } catch (IOException e) {
            throw new IllegalStateException("커밋 메시지를 쓰지 못했습니다: " + e.getMessage(), e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // 임시 파일
                }
            }
        }
    }

    /**
     * rebase 전에 사람이 고치던 추적 파일을 치워 둔다. {@code rebase --autostash}를 쓰지 않는 이유:
     * 되돌릴 때 스테이지 상태를 살리지 않아, 사람이 {@code git add}해 둔 파일이 전부 풀린다.
     * 추적 안 된 파일은 그대로 둔다. 원격이 같은 경로를 바꾸면 점검 4가 먼저 막는다.
     *
     * @return 치운 것이 있는가
     */
    private static boolean stashTracked(GitWorkspace git) {
        if (git.runOrThrow("status", "--porcelain=v1", "--untracked-files=no").out().isBlank()) {
            return false;
        }
        git.runOrThrow("stash", "push", "-q", "-m", "배치로 보내기 중 임시 보관");
        return true;
    }

    /** @return 실패하면 사람이 읽을 설명. 되돌렸으면 빈 문자열 */
    private static String popStash(GitWorkspace git) {
        GitWorkspace.Result pop = git.run("stash", "pop", "-q", "--index");
        return pop.ok() ? "" : "고치던 파일을 되돌리지 못해 git stash에 남겨 두었습니다(git stash pop --index로 꺼내세요).";
    }

    /**
     * 이번에 만든 커밋을 풀어 누르기 전으로 돌린다.
     * {@code --mixed}로 풀면 사람이 스테이지해 둔 다른 파일까지 풀리므로, soft로 풀고 우리 파일만 내린다.
     */
    private void undoCommit(Repo repo, List<String> paths) {
        GitWorkspace git = repo.git();
        git.runOrThrow("reset", "-q", "--soft", "HEAD~1");
        git.run(withPaths(List.of("reset", "-q", "--"), paths));
    }

    // ---------------------------------------------------------------- 점검

    /** 점검 1·2: main 브랜치인가, 사람이 풀던 git 작업이 없는가. */
    private List<String> stateBlockers(Repo repo) {
        GitWorkspace git = repo.git();
        List<String> blockers = new ArrayList<>();
        GitWorkspace.Result branch = git.run("symbolic-ref", "--short", "-q", "HEAD");
        String name = branch.out().strip();
        if (!branch.ok() || !BRANCH.equals(name)) {
            blockers.add("지금 브랜치가 main이 아닙니다(" + (name.isEmpty() ? "브랜치 없음" : name) + "). main에서 보내세요.");
        }
        for (String marker : inProgressMarkers(repo)) {
            blockers.add("진행 중인 git 작업이 있습니다(" + marker + "). 끝낸 뒤 보내세요.");
        }
        return blockers;
    }

    /** 지금 남아 있는 진행 중 작업 표지. 배지가 화면마다 부르므로 git을 한 번만 실행한다 */
    private static List<String> inProgressMarkers(Repo repo) {
        GitWorkspace git = repo.git();
        List<String> args = new ArrayList<>(List.of("rev-parse"));
        IN_PROGRESS_MARKERS.forEach(m -> {
            args.add("--git-path");
            args.add(m);
        });
        List<String> paths = git.run(args).out().lines().map(String::strip).toList();
        List<String> found = new ArrayList<>();
        for (int i = 0; i < IN_PROGRESS_MARKERS.size() && i < paths.size(); i++) {
            if (!paths.get(i).isEmpty() && Files.exists(git.dir().resolve(paths.get(i)))) {
                found.add(IN_PROGRESS_MARKERS.get(i));
            }
        }
        return found;
    }

    /** rebase가 도중에 멈춰 있으면 되돌린다. 없으면 아무것도 하지 않는다 */
    private static void abortRebaseIfInProgress(Repo repo) {
        List<String> markers = inProgressMarkers(repo);
        if (markers.contains("rebase-merge") || markers.contains("rebase-apply")) {
            repo.git().run("rebase", "--abort");
        }
    }

    /**
     * @param own      이 앱이 만들고 push하지 못한 커밋 수
     * @param blockers 점검 3: 사람이 만든, 아직 안 올린 커밋
     */
    private record Unpushed(int own, List<String> blockers) {
    }

    private Unpushed unpushed(Repo repo) {
        GitWorkspace git = repo.git();
        if (!git.run("rev-parse", "--verify", "-q", "refs/remotes/" + UPSTREAM).ok()) {
            return new Unpushed(0, List.of(UPSTREAM + "을 모릅니다. git fetch를 먼저 하세요."));
        }
        int own = 0;
        List<String> foreign = new ArrayList<>();
        for (String line : git.runOrThrow("log", "--format=%h %s", UPSTREAM + "..HEAD").out().lines().toList()) {
            int space = line.indexOf(' ');
            String subject = space < 0 ? "" : line.substring(space + 1);
            if (subject.startsWith(SUBJECT_PREFIX)) {
                own++;
            } else {
                foreign.add("안 올린 커밋: " + line);
            }
        }
        return new Unpushed(own, foreign);
    }

    /**
     * 점검 4: 원격의 새 커밋이 바꾼 파일 중, 사람이 지금 고치고 있는 파일.
     * 보낼 파일은 빼고 센다 — 그쪽이 겹치면 rebase 충돌로 따로 처리한다.
     */
    private List<String> overlapWithIncoming(Repo repo, List<String> sending) {
        if (behind(repo) == 0) {
            return List.of();
        }
        GitWorkspace git = repo.git();
        Set<String> incoming = new LinkedHashSet<>(GitWorkspace.splitNul(
                git.runOrThrow("diff", "--name-only", "-z", "HEAD..." + UPSTREAM).out()));
        Set<String> dirty = new LinkedHashSet<>();
        for (StatusEntry entry : status(git, List.of())) {
            dirty.add(entry.path());
            if (entry.origPath() != null) {
                dirty.add(entry.origPath());
            }
        }
        sending.forEach(dirty::remove);
        dirty.retainAll(incoming);
        return List.copyOf(dirty);
    }

    private int behind(Repo repo) {
        return Integer.parseInt(repo.git().runOrThrow("rev-list", "--count", "HEAD.." + UPSTREAM).out().strip());
    }

    // ---------------------------------------------------------------- 파일 목록

    private List<GeneratedPending.File> scopeFiles(Repo repo) {
        GitWorkspace git = repo.git();
        List<StatusEntry> entries = status(git, repo.pathspecs());
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, int[]> lines = numstat(git, repo.pathspecs());
        String documentsPrefix = repo.prefix() + "/documents/";
        List<GeneratedPending.File> files = new ArrayList<>();
        for (StatusEntry entry : entries) {
            Path file = git.dir().resolve(entry.path());
            int[] counts = lines.get(entry.path());
            if (counts == null && entry.change() == Change.ADDED) {
                counts = new int[]{lineCount(file), 0};
            }
            files.add(new GeneratedPending.File(entry.path(),
                    entry.path().startsWith(documentsPrefix) ? Kind.SOURCE_DOCUMENT : Kind.SNAPSHOT,
                    entry.change(),
                    counts == null ? 0 : counts[0],
                    counts == null ? 0 : counts[1],
                    modifiedAt(file)));
        }
        files.sort(Comparator.comparing(GeneratedPending.File::path));
        return files;
    }

    private record StatusEntry(String path, String origPath, Change change) {
    }

    /** {@code status --porcelain -z} 한 줄은 "XY 경로"다. 이름 바꾸기(R·C)는 다음 칸에 옛 경로가 온다. */
    private static List<StatusEntry> status(GitWorkspace git, List<String> pathspecs) {
        List<String> parts = GitWorkspace.splitNul(git.runOrThrow(withPaths(
                List.of("status", "--porcelain=v1", "-z", "--untracked-files=all", "--"), pathspecs)
                .toArray(String[]::new)).out());
        List<StatusEntry> entries = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (part.length() < 4) {
                continue;
            }
            char x = part.charAt(0);
            char y = part.charAt(1);
            String path = part.substring(3);
            String orig = null;
            if ((x == 'R' || x == 'C') && i + 1 < parts.size()) {
                orig = parts.get(++i);
            }
            Change change = x == '?' || x == 'A' ? Change.ADDED
                    : x == 'D' || y == 'D' ? Change.DELETED
                    : Change.MODIFIED;
            entries.add(new StatusEntry(path, orig, change));
        }
        return entries;
    }

    /** 경로 → {더한 줄, 뺀 줄}. 추적 중인 파일만 나온다. */
    private static Map<String, int[]> numstat(GitWorkspace git, List<String> pathspecs) {
        Map<String, int[]> result = new HashMap<>();
        GitWorkspace.Result diff = git.run(withPaths(List.of("diff", "HEAD", "--numstat", "-z", "--"), pathspecs));
        if (!diff.ok()) {
            return result;
        }
        for (String part : GitWorkspace.splitNul(diff.out())) {
            String[] cols = part.split("\t", 3);
            if (cols.length == 3) {
                result.put(cols[2], new int[]{parseCount(cols[0]), parseCount(cols[1])});
            }
        }
        return result;
    }

    private static int parseCount(String value) {
        return "-".equals(value) ? 0 : Integer.parseInt(value.strip());
    }

    private static int lineCount(Path file) {
        try {
            return (int) Files.readString(file, StandardCharsets.UTF_8).lines().count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Instant modifiedAt(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toInstant() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 저장소 찾기

    private Resolved resolve() {
        if (!Files.isDirectory(importDir)) {
            return new Resolved(null, "파일 폴더가 없습니다: " + importDir.toAbsolutePath());
        }
        GitWorkspace here = new GitWorkspace(importDir);
        GitWorkspace.Result top = here.run("rev-parse", "--show-toplevel");
        if (!top.ok()) {
            return new Resolved(null, "git 저장소가 아닙니다: " + importDir.toAbsolutePath());
        }
        try {
            Path root = Path.of(top.out().strip()).toRealPath();
            Path relative = root.relativize(importDir.toRealPath());
            String prefix = relative.toString().replace('\\', '/');
            if (prefix.isEmpty() || prefix.startsWith("..")) {
                return new Resolved(null, "파일 폴더가 저장소 루트 아래에 있지 않습니다: " + importDir.toAbsolutePath());
            }
            GitWorkspace git = new GitWorkspace(root, gitTimeout);
            if (!git.run("remote", "get-url", REMOTE).ok()) {
                return new Resolved(null, "origin 원격이 없습니다.");
            }
            return new Resolved(new Repo(git, prefix), "");
        } catch (IOException e) {
            return new Resolved(null, "저장소 경로를 읽지 못했습니다: " + e.getMessage());
        }
    }

    private static List<String> withPaths(List<String> head, List<String> paths) {
        List<String> args = new ArrayList<>(head);
        args.addAll(paths);
        return args;
    }
}
