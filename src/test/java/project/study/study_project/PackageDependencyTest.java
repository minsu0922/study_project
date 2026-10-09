package project.study.study_project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최상위 패키지끼리 서로 기대지 않는지 — 소스의 import를 읽어 본다.
 *
 * <p>순환은 한 줄의 import로 생기고 컴파일도 테스트도 멀쩡하다. 생기면 한쪽을 고칠 때 다른 쪽을
 * 함께 열어야 하고, 둘을 따로 떼어 낼 수 없게 된다. 끊는 법은 두 가지를 써 왔다 — 불리는 쪽에
 * 인터페이스를 두거나(SubmissionListener, AccountDataCleaner), 이벤트로 알린다(PasswordChanged).
 *
 * <p>도구(ArchUnit)를 들이지 않은 이유: 보는 것이 "최상위 패키지 사이의 import" 하나뿐이다.
 */
class PackageDependencyTest {

    private static final String BASE = "project.study.study_project";
    private static final Path MAIN = Path.of("src/main/java", BASE.split("\\."));
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?" + Pattern.quote(BASE) + "\\.(\\w+)\\.", Pattern.MULTILINE);

    @Test
    @DisplayName("최상위 패키지 사이에 순환이 없다")
    void topLevelPackagesHaveNoCycle() throws IOException {
        Map<String, Set<String>> graph = dependencies();

        List<String> cycles = new ArrayList<>();
        for (String from : graph.keySet()) {
            for (String to : graph.get(from)) {
                // 양쪽에서 한 번씩 잡히지 않게 이름순으로 한 번만 적는다
                if (from.compareTo(to) < 0 && reaches(graph, to, from, new TreeSet<>())) {
                    cycles.add(from + " ↔ " + to);
                }
            }
        }

        assertThat(cycles).as("서로 기대는 패키지").isEmpty();
    }

    @Test
    @DisplayName("공용 패키지(global)는 기능 패키지를 부르지 않는다")
    void globalDependsOnNothing() throws IOException {
        assertThat(dependencies().getOrDefault("global", Set.of())).isEmpty();
    }

    /** 최상위 패키지 → 그 패키지가 import하는 다른 최상위 패키지들. */
    private Map<String, Set<String>> dependencies() throws IOException {
        Map<String, Set<String>> graph = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path relative = MAIN.relativize(file);
                if (relative.getNameCount() < 2) {
                    continue;   // 루트의 StudyProjectApplication
                }
                String from = relative.getName(0).toString();
                Matcher matcher = IMPORT.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (!matcher.group(1).equals(from)) {
                        graph.computeIfAbsent(from, k -> new TreeSet<>()).add(matcher.group(1));
                    }
                }
            }
        }
        return graph;
    }

    private boolean reaches(Map<String, Set<String>> graph, String from, String target, Set<String> seen) {
        if (from.equals(target)) {
            return true;
        }
        if (!seen.add(from)) {
            return false;
        }
        return graph.getOrDefault(from, Set.of()).stream().anyMatch(next -> reaches(graph, next, target, seen));
    }
}
