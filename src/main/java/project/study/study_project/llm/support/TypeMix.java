package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.ClaudeProblemGenerator;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 한 번의 생성에 넣을 문제 유형과 개수, 유형별 재료 절(2026-11-27, docs/superpowers/specs/2026-11-27-problem-type-mix-design.md).
 *
 * <p>재료 판정은 검수함 카드 표시와 같은 {@link ProblemTypeHints}를 쓴다. 두 곳이 다르게 세면
 * "카드는 짝짓기가 된다는데 배치는 안 낸" 어긋남이 생긴다.
 *
 * @param counts   유형별 개수. 0인 유형은 넣지 않는다. 순서는 ProblemType 선언 순서다
 * @param sections 객관식 밖 유형의 재료 절 이름
 */
public record TypeMix(Map<ProblemType, Integer> counts, Map<ProblemType, List<String>> sections) {

    /** 다른 유형이 넘칠 때 먼저 덜어 내는 순서. OX가 가장 가볍게 풀려 처음 온 사람에게 남긴다. */
    private static final List<ProblemType> DROP_ORDER = List.of(ProblemType.ORDERING, ProblemType.MATCHING, ProblemType.OX);

    public TypeMix {
        // new EnumMap<>(map)은 빈 일반 Map을 받으면 예외를 던진다 — 빈 EnumMap에 옮겨 담는다
        Map<ProblemType, Integer> c = new EnumMap<>(ProblemType.class);
        c.putAll(counts);
        Map<ProblemType, List<String>> s = new EnumMap<>(ProblemType.class);
        s.putAll(sections);
        counts = Collections.unmodifiableMap(c);
        sections = Collections.unmodifiableMap(s);
    }

    /**
     * 초급 세트의 구성. 재료가 있는 유형마다 1개, 나머지는 객관식이고 객관식은 최소 1개다.
     *
     * @param count     요청 개수
     * @param contentMd 초급 근거 본문
     */
    public static TypeMix forBeginner(int count, String contentMd) {
        Map<ProblemType, List<String>> found = new EnumMap<>(ProblemType.class);
        Set<String> othersSections = otherDifficultySections();
        for (ProblemTypeHints.Hint hint : ProblemTypeHints.of(contentMd)) {
            ProblemType type = ProblemType.valueOf(hint.type());
            // 중급·고급이 쓰는 절의 번호 목록을 초급이 쓰면 그 난이도의 재료를 하루 먼저 써 버린다
            List<String> usable = hint.sections().stream().filter(s -> !othersSections.contains(s)).toList();
            if (!usable.isEmpty()) {
                found.put(type, usable);
            }
        }
        for (ProblemType drop : DROP_ORDER) {
            if (found.size() <= Math.max(0, count - 1)) {
                break;
            }
            found.remove(drop);
        }

        Map<ProblemType, Integer> counts = new EnumMap<>(ProblemType.class);
        counts.put(ProblemType.MULTIPLE_CHOICE, count - found.size());
        found.keySet().forEach(t -> counts.put(t, 1));
        return new TypeMix(counts, found);
    }

    /**
     * 중급 세트의 구성. 순서 배열 재료가 있으면 순서 배열 1, 나머지는 객관식이다(사용자 결정, 2026-11-28).
     * 짝짓기·OX는 넣지 않는다. 심화편 용어 표는 같은 주기 초급의 짝짓기와, 「흔한 오해」는 고급 절과 겹친다.
     *
     * @param contentMd 중급 근거 본문(심화편)
     */
    public static TypeMix forIntermediate(int count, String contentMd) {
        Map<ProblemType, Integer> counts = new EnumMap<>(ProblemType.class);
        Map<ProblemType, List<String>> found = new EnumMap<>(ProblemType.class);
        if (count >= 2) {
            // 고급 절은 소제목째 지운 뒤 센다. 힌트의 절 이름은 바로 위 소제목이라 이름만으로는 못 거른다
            String own = withoutSections(contentMd, ClaudeProblemGenerator.SOURCE_SECTIONS.get(Difficulty.ADVANCED));
            ProblemTypeHints.of(own).stream()
                    .filter(h -> ProblemType.ORDERING.name().equals(h.type()))
                    .findFirst()
                    .ifPresent(h -> found.put(ProblemType.ORDERING, h.sections()));
        }
        counts.put(ProblemType.MULTIPLE_CHOICE, count - found.size());
        found.keySet().forEach(t -> counts.put(t, 1));
        return new TypeMix(counts, found);
    }

    /** headings(예 "## 어떤 때 통하지 않는가")로 시작하는 절을 같거나 높은 수준의 다음 소제목 전까지 지운다 */
    static String withoutSections(String contentMd, List<String> headings) {
        if (contentMd == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int skipLevel = 0;
        for (String line : contentMd.lines().toList()) {
            String s = line.strip();
            int level = s.startsWith("#") ? s.indexOf(' ') : -1;
            boolean heading = level > 0 && s.substring(0, level).chars().allMatch(c -> c == '#');
            if (heading && skipLevel > 0 && level <= skipLevel) {
                skipLevel = 0;
            }
            if (heading && skipLevel == 0 && headings.contains(s)) {
                skipLevel = level;
            }
            if (skipLevel == 0) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** 객관식 밖 유형이 하나라도 있으면 true. false면 지금까지처럼 객관식만 낸다. */
    public boolean isMixed() {
        return counts.keySet().stream().anyMatch(t -> t != ProblemType.MULTIPLE_CHOICE);
    }

    /** "객관식 4 · OX 1 · 짝짓기 1 · 순서 배열 1" — 프롬프트와 배치 요약이 같은 말을 쓴다. */
    public String summary() {
        return counts.entrySet().stream()
                .map(e -> e.getKey().getDisplayName() + " " + e.getValue())
                .collect(Collectors.joining(" · "));
    }

    private static Set<String> otherDifficultySections() {
        return Stream.of(Difficulty.INTERMEDIATE, Difficulty.ADVANCED)
                .flatMap(d -> ClaudeProblemGenerator.SOURCE_SECTIONS.get(d).stream())
                .map(h -> h.replaceFirst("^#+\\s+", "").strip())
                .collect(Collectors.toSet());
    }
}
