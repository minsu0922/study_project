package project.study.study_project.llm.support;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 코드 문제의 {@code Main.java}를 실제로 컴파일하고 실행해 결과를 돌려준다.
 *
 * <p>모델이 쓴 코드를 돌리는 자리다. 그래서 세 가지를 지킨다.
 * <ul>
 *   <li>실행은 <b>다른 프로세스</b>에서 한다. 같은 JVM에서 돌리면 무한 반복 하나에 배치가 멈춘다.
 *   <li>자식 프로세스의 환경변수를 <b>비운다</b>. API 키가 환경변수로 들어오기 때문이다.
 *   <li>시간과 메모리에 <b>상한</b>을 둔다.
 * </ul>
 * 이것만으로 격리가 끝나지는 않는다. 파일을 읽는 코드는 {@link CodeProblemRule}이 실행 전에 걸러 내고,
 * 워크플로는 API 키가 없는 스텝에서 이 클래스를 부른다(docs/22와 워크플로 주석).
 */
public class CodeRunner {

    static final long TIMEOUT_SECONDS = 5;
    private static final Pattern MAIN_FRAME = Pattern.compile("\\(Main\\.java:(\\d+)\\)");
    private static final Pattern EXCEPTION_HEAD =
            Pattern.compile("Exception in thread \"main\" ([\\w.$]+)");

    /**
     * @param compiled         컴파일됐는가
     * @param compileErrorLine 첫 컴파일 오류의 줄. 컴파일됐으면 0
     * @param stdout           표준 출력. 줄 끝 공백과 마지막 빈 줄은 걷어 낸다
     * @param exceptionType    잡히지 않은 예외의 클래스 이름(패키지 없이). 없으면 빈 문자열
     * @param exceptionLine    예외가 지나간 Main.java의 가장 안쪽 줄. 없으면 0
     * @param timedOut         시간 안에 끝나지 않았는가
     */
    public record Result(boolean compiled, int compileErrorLine, String stdout, String exceptionType,
                         int exceptionLine, boolean timedOut) {

        /** 컴파일되고, 시간 안에 예외 없이 끝났는가. */
        public boolean finishedCleanly() {
            return compiled && !timedOut && exceptionType.isEmpty();
        }

        static Result compileError(int line) {
            return new Result(false, line, "", "", 0, false);
        }
    }

    public Result run(String code) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("code-problem-");
            Path source = dir.resolve("Main.java");
            Files.writeString(source, code, StandardCharsets.UTF_8);
            int errorLine = compile(source, dir);
            if (errorLine != 0) {
                return Result.compileError(errorLine);
            }
            return execute(dir);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("코드를 실행하지 못했습니다: " + e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** @return 첫 오류의 줄 번호. 컴파일됐으면 0. 줄을 알 수 없는 오류는 -1 */
    private static int compile(Path source, Path dir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK의 컴파일러를 찾지 못했습니다(JRE로 실행 중인지 확인).");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, files, diagnostics,
                    List.of("-d", dir.toString(), "-proc:none", "-Xlint:none"), null,
                    files.getJavaFileObjects(source.toFile())).call();
            if (ok) {
                return 0;
            }
            return diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .mapToInt(d -> (int) d.getLineNumber())
                    .filter(line -> line > 0)
                    .min().orElse(-1);
        }
    }

    private static Result execute(Path dir) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-Xmx64m", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", dir.toString(), "Main");
        builder.directory(dir.toFile());
        // 부모의 환경변수를 넘기지 않는다. API 키와 토큰이 거기 있다
        builder.environment().clear();
        Path out = dir.resolve("stdout.txt");
        Path err = dir.resolve("stderr.txt");
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));

        Process process = builder.start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
        }
        String stdout = normalize(Files.readString(out, StandardCharsets.UTF_8));
        String stderr = Files.readString(err, StandardCharsets.UTF_8);
        return new Result(true, 0, stdout, exceptionTypeOf(stderr), exceptionLineOf(stderr), !finished);
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    /** 줄 끝 공백과 끝의 빈 줄을 걷는다. 윈도우와 리눅스의 줄바꿈 차이로 대조가 갈리면 안 된다. */
    public static String normalize(String output) {
        if (output == null) {
            return "";
        }
        return output.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(String::stripTrailing)
                .collect(java.util.stream.Collectors.joining("\n"))
                .stripTrailing();
    }

    static String exceptionTypeOf(String stderr) {
        Matcher m = EXCEPTION_HEAD.matcher(stderr);
        if (!m.find()) {
            return "";
        }
        String name = m.group(1);
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    /**
     * 스택에서 Main.java를 지나는 가장 안쪽 줄. 예외가 JDK 안에서 던져져도(예: {@code list.get(5)})
     * 학습자가 보는 것은 그 호출이 적힌 줄이다.
     */
    static int exceptionLineOf(String stderr) {
        if (exceptionTypeOf(stderr).isEmpty()) {
            return 0;
        }
        Matcher m = MAIN_FRAME.matcher(stderr);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 임시 폴더가 남아도 다음 실행에 영향이 없다
        }
    }
}
