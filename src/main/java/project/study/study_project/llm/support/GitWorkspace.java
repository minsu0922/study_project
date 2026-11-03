package project.study.study_project.llm.support;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 한 저장소 폴더에서 {@code git} 명령을 실행한다(docs/29).
 *
 * <p>앱 안에서 돌기 때문에 사람에게 무엇을 묻는 순간 요청이 멈춘다. 그래서 인증 창과
 * 편집기를 모두 끄고, 명령마다 시간 제한을 둔다.
 */
public class GitWorkspace {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final Path dir;
    private final Duration timeout;

    public GitWorkspace(Path dir) {
        this(dir, DEFAULT_TIMEOUT);
    }

    public GitWorkspace(Path dir, Duration timeout) {
        this.dir = dir;
        this.timeout = timeout;
    }

    public Path dir() {
        return dir;
    }

    /**
     * @param exit 종료 코드
     * @param out  표준 출력(UTF-8)
     * @param err  표준 오류(UTF-8)
     */
    public record Result(int exit, String out, String err) {

        public boolean ok() {
            return exit == 0;
        }

        /** 오류 출력의 마지막 줄. 화면에 이유로 보여 줄 때 쓴다. */
        public String lastErrorLine() {
            String text = err.isBlank() ? out : err;
            List<String> lines = text.strip().lines().filter(l -> !l.isBlank()).toList();
            return lines.isEmpty() ? "git 종료 코드 " + exit : lines.get(lines.size() - 1).strip();
        }
    }

    /** 시간 안에 끝나지 않은 명령. 프로세스는 이미 끝냈다. */
    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String command, Duration timeout) {
            super("git " + command + "이(가) " + timeout.toSeconds() + "초 안에 끝나지 않았습니다");
        }
    }

    public Result run(String... args) {
        return run(Arrays.asList(args));
    }

    public Result run(List<String> args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotepath=false"));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        Map<String, String> env = builder.environment();
        // 터미널 인증 질문과 Git Credential Manager 창을 끈다. 뜨면 앱 요청이 멈춘다
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");
        // rebase·commit이 편집기를 열지 않게 한다
        env.put("GIT_EDITOR", "true");
        env.put("GIT_SEQUENCE_EDITOR", "true");
        env.put("LC_ALL", "C");
        // 조회(status·diff)가 index.lock을 잡지 않게 한다. 배지가 status를 도는 순간 보내기의 add·commit이
        // 잠금에 막혀 실패하던 경로를 없앤다. 쓰기 명령이 꼭 잡아야 하는 잠금에는 영향이 없다
        env.put("GIT_OPTIONAL_LOCKS", "0");

        Path out = null;
        Path err = null;
        try {
            out = Files.createTempFile("git-out-", ".txt");
            err = Files.createTempFile("git-err-", ".txt");
            builder.redirectOutput(out.toFile());
            builder.redirectError(err.toFile());
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                throw new TimeoutException(args.isEmpty() ? "" : args.get(0), timeout);
            }
            return new Result(process.exitValue(),
                    Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("git을 실행하지 못했습니다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git 실행 중 중단됐습니다", e);
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    /** 실패하면 예외를 던진다. 실패가 곧 버그인 명령에만 쓴다. */
    public Result runOrThrow(String... args) {
        Result result = run(args);
        if (!result.ok()) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 실패: " + result.lastErrorLine());
        }
        return result;
    }

    /** {@code -z} 출력(NUL로 끊긴 목록)을 나눈다. */
    public static List<String> splitNul(String out) {
        return Arrays.stream(out.split("\0")).filter(s -> !s.isEmpty()).toList();
    }

    private static File nullDevice() {
        return new File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 임시 파일이라 남아도 된다
        }
    }
}
