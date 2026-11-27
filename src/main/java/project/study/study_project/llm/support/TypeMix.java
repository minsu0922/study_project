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
        counts = Collections.unmodifiableMap(new EnumMap<>(counts));
        sections = Collections.unmodifiableMap(new EnumMap<>(sections));
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
