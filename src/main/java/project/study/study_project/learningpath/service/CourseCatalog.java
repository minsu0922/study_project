package project.study.study_project.learningpath.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.document.support.DocumentEditions;
import project.study.study_project.domainsetting.support.DomainCatalog;
import project.study.study_project.domainsetting.support.DomainEntry;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.learningpath.domain.LearningPath;
import project.study.study_project.learningpath.domain.LearningPathStep;
import project.study.study_project.learningpath.domain.PathKey;
import project.study.study_project.learningpath.repository.LearningPathRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.DocumentRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 코스 목록을 만든다 — 관리자 코스(저장됨) + 분야별 코스·자바 코드 코스(규칙으로 만듦). 설계는 docs/26.
 *
 * <p>자동 코스를 저장하지 않는 이유: 문서가 새로 공개되면 그 분야 코스에 저절로 붙어야 한다.
 * 저장해 두면 공개할 때마다 코스를 고치는 길이 하나 더 생기고, 빠뜨리면 조용히 어긋난다.
 * 공개 문서가 수십 편이라 요청마다 다시 만들어도 싸다.
 */
@Service
@RequiredArgsConstructor
public class CourseCatalog {

    /** 코드 문제 코스는 지금 자바뿐이다(problem.code_language). 다른 언어가 생기면 여기 더한다 */
    static final String CODE_LANGUAGE = "java";

    private final LearningPathRepository pathRepository;
    private final LearningPathStatsRepository statsRepository;
    private final DomainCatalog domainCatalog;

    /** 사용자에게 보이는 코스 전부 — 관리자 코스, 분야별 코스(분야 순서), 코드 코스 순. 단계 없는 코스는 뺀다 */
    @Transactional(readOnly = true)
    public List<Course> all() {
        List<DocumentRow> docs = statsRepository.findPublicDocuments();
        Set<String> publicSlugs = docs.stream().map(DocumentRow::getSlug).collect(Collectors.toSet());

        List<Course> courses = new ArrayList<>();
        for (LearningPath p : pathRepository.findPublished()) {
            customCourse(p, publicSlugs).ifPresent(courses::add);
        }
        Map<DomainCode, List<DocumentRow>> byDomain = docs.stream()
                .collect(Collectors.groupingBy(DocumentRow::getDomain, LinkedHashMap::new, Collectors.toList()));
        for (DomainEntry d : domainCatalog.all()) {
            List<DocumentRow> mine = byDomain.get(d.code());
            if (mine != null) courses.add(domainCourse(d.code(), d.displayName(), mine, publicSlugs));
        }
        codeCourse().ifPresent(courses::add);
        return courses;
    }

    /**
     * 키 하나로 코스를 찾는다. 없는 분야·비공개 관리자 코스·단계 없는 코스는 빈 값이다.
     * 관리자 코스는 {@code includeUnpublished}면 비공개도 준다(관리 콘솔의 미리 보기).
     */
    @Transactional(readOnly = true)
    public Optional<Course> find(PathKey key, boolean includeUnpublished) {
        return switch (key.kind()) {
            case CUSTOM -> pathRepository.findById(key.customId())
                    .filter(p -> includeUnpublished || p.isPublished())
                    .flatMap(p -> customCourse(p, publicSlugs()));
            case DOMAIN -> {
                DomainCode code = DomainCode.of(key.value());
                if (!domainCatalog.exists(code)) yield Optional.empty();
                List<DocumentRow> docs = statsRepository.findPublicDocuments();
                List<DocumentRow> mine = docs.stream().filter(d -> d.getDomain().equals(code)).toList();
                Set<String> slugs = docs.stream().map(DocumentRow::getSlug).collect(Collectors.toSet());
                yield mine.isEmpty() ? Optional.empty()
                        : Optional.of(domainCourse(code, domainCatalog.displayName(code), mine, slugs));
            }
            case CODE -> CODE_LANGUAGE.equals(key.value()) ? codeCourse() : Optional.empty();
        };
    }

    public Optional<Course> find(PathKey key) {
        return find(key, false);
    }

    private Set<String> publicSlugs() {
        return statsRepository.findPublicDocuments().stream().map(DocumentRow::getSlug).collect(Collectors.toSet());
    }

    /** 관리자 코스 — 정한 순서대로. 숨긴 문서의 단계는 건너뛰고 번호를 다시 매긴다 */
    private Optional<Course> customCourse(LearningPath p, Set<String> publicSlugs) {
        List<Course.Step> steps = new ArrayList<>();
        for (LearningPathStep s : p.getSteps()) {
            var d = s.getDocument();
            if (d.isHidden()) continue;
            steps.add(Course.Step.document(steps.size() + 1, d.getTitle(), editionOf(d.getSlug(), publicSlugs), d.getSlug()));
        }
        return steps.isEmpty() ? Optional.empty()
                : Optional.of(new Course(PathKey.custom(p.getId()), p.getTitle(), p.getDescription(), steps));
    }

    /**
     * 분야별 코스 — 사람이 순번을 정한 문서(V44)가 순번대로 앞에 선다. 나머지는 기본 규칙으로 뒤에 붙는다.
     * 기본 규칙: 주제마다 입문편 다음 심화편, 주제끼리는 먼저 만든 문서가 앞(행은 id 오름차순으로 온다).
     */
    static Course domainCourse(DomainCode domain, String name, List<DocumentRow> docs, Set<String> publicSlugs) {
        List<Course.Step> steps = new ArrayList<>();
        for (DocumentRow d : orderedForDomain(docs)) {
            steps.add(Course.Step.document(steps.size() + 1, d.getTitle(), editionOf(d.getSlug(), publicSlugs), d.getSlug()));
        }
        String description = name + " 개념 문서 " + steps.size() + "편을 주제마다 입문편부터 차례로 읽고 풀어요.";
        return new Course(PathKey.domain(domain.value()), name + " 코스", description, steps);
    }

    /**
     * 분야 코스의 문서 순서. 관리 콘솔도 같은 순서를 보여 줘야 해서 따로 뺐다(AdminLearningPathService).
     *
     * @param docs 한 분야의 문서, id 오름차순
     */
    static <T extends DocumentRow> List<T> orderedForDomain(List<T> docs) {
        Map<String, List<T>> byTopic = new LinkedHashMap<>();
        for (T d : docs) {
            String topic = DocumentEditions.isAdvanced(d.getSlug())
                    ? DocumentEditions.counterpartSlugOf(d.getSlug()) : d.getSlug();
            byTopic.computeIfAbsent(topic, k -> new ArrayList<>()).add(d);
        }
        List<T> byDefault = new ArrayList<>();
        for (List<T> topic : byTopic.values()) {
            topic.stream()
                    .sorted(Comparator.comparing((T d) -> DocumentEditions.isAdvanced(d.getSlug())))
                    .forEach(byDefault::add);
        }
        // 안정 정렬이라 순번 없는 문서끼리는 위 기본 순서가 그대로 남는다
        return byDefault.stream()
                .sorted(Comparator.comparing((T d) -> d.getSeq() == null)
                        .thenComparing(d -> d.getSeq() == null ? 0 : d.getSeq()))
                .toList();
    }

    /** 자바 코드 코스 — 문제가 있는 난이도만 초급 → 중급 → 고급 */
    private Optional<Course> codeCourse() {
        Map<Difficulty, Long> counts = new EnumMap<>(Difficulty.class);
        statsRepository.countCodeProblemsByDifficulty(CODE_LANGUAGE)
                .forEach(r -> counts.put(r.getDifficulty(), r.getCnt()));
        List<Course.Step> steps = new ArrayList<>();
        for (Difficulty d : Difficulty.values()) {
            if (counts.getOrDefault(d, 0L) > 0) steps.add(Course.Step.code(steps.size() + 1, d));
        }
        return steps.isEmpty() ? Optional.empty() : Optional.of(new Course(PathKey.code(CODE_LANGUAGE),
                "자바 코드 문제", "코드를 읽고 실행 결과·빈칸·오류 지점을 맞히는 문제를 쉬운 것부터 풀어요.", steps));
    }

    /** 짝 문서가 공개돼 있을 때만 편 이름을 붙인다 — 한 편짜리 문서에 "입문편"을 달면 거짓말이 된다 */
    private static String editionOf(String slug, Set<String> publicSlugs) {
        String pair = DocumentEditions.counterpartSlugOf(slug);
        return pair != null && publicSlugs.contains(pair) ? DocumentEditions.labelOf(slug) : null;
    }
}
