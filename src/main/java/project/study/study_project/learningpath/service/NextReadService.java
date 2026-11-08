package project.study.study_project.learningpath.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.document.support.DocumentEditions;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.learningpath.dto.NextReadItem;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.DocumentRow;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.SlugContent;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.SlugTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 문서를 다 읽은 사람에게 다음에 읽을 글 두 편을 고른다.
 *
 * <p>고르는 순서는 둘이다.
 * <ol>
 *   <li>이 문서가 든 코스에서 뒤에 오는 단계. 관리자 코스가 분야 코스보다 먼저다(CourseCatalog 순서).
 *   <li>그래도 모자라면 태그가 겹치는 글. 드문 태그일수록 무겁게 센다.
 * </ol>
 * 태그만 쓰지 않는 이유: 태그가 굵다(2026-11 기준 78편에 21개, network 하나가 36편).
 * 태그만으로 고르면 "다음 글"이 아니라 "같은 분야의 아무 글"이 나온다.
 *
 * <p>주제 단위로 고른다. 입문편·심화편은 한 주제이므로 같은 주제의 다른 편은 고르지 않는다 —
 * 그 편은 화면의 「이어서 읽기」 카드가 이미 가리킨다. 심화편을 읽고 있으면 다음 주제도 심화편으로 잇는다.
 */
@Service
@RequiredArgsConstructor
public class NextReadService {

    static final int LIMIT = 2;

    /** 「핵심 요약」 절의 첫 항목 "- **제목** — 결론"에서 제목만 */
    private static final Pattern SUMMARY_HEAD = Pattern.compile("(?m)^## 핵심 요약\\s*$");
    private static final Pattern FIRST_BOLD_ITEM = Pattern.compile("(?m)^-\\s+\\*\\*([^*\\n]+)\\*\\*");

    private final CourseCatalog courseCatalog;
    private final LearningPathStatsRepository statsRepository;

    @Transactional(readOnly = true)
    public List<NextReadItem> nextReads(String slug) {
        List<DocumentRow> docs = statsRepository.findPublicDocuments();
        Map<String, DocumentRow> bySlug = docs.stream()
                .collect(Collectors.toMap(DocumentRow::getSlug, d -> d, (a, b) -> a, LinkedHashMap::new));
        if (!bySlug.containsKey(slug)) {
            throw new BusinessException(ErrorCode.DOC_001); // 숨긴 문서도 없는 문서와 같다
        }
        String myTopic = topicOf(slug);

        Map<String, String> picks = new LinkedHashMap<>(); // 주제 → 이유
        pickFromCourses(myTopic, picks);
        if (picks.size() < LIMIT) {
            pickByTags(slug, myTopic, bySlug, picks);
        }

        boolean advanced = DocumentEditions.isAdvanced(slug);
        Map<String, String> chosen = new LinkedHashMap<>(); // 보여 줄 slug → 이유
        picks.forEach((topic, reason) -> {
            String shown = editionToShow(topic, advanced, bySlug.keySet());
            if (shown != null) chosen.put(shown, reason);
        });
        if (chosen.isEmpty()) {
            return List.of();
        }

        Map<String, String> summaries = new HashMap<>(); // toMap은 null 값을 못 받는다
        for (SlugContent c : statsRepository.findContents(chosen.keySet())) {
            summaries.put(c.getSlug(), summaryOf(c.getContentMd()));
        }
        List<NextReadItem> items = new ArrayList<>();
        chosen.forEach((shown, reason) -> {
            String pair = DocumentEditions.counterpartSlugOf(shown);
            String edition = pair != null && bySlug.containsKey(pair) ? DocumentEditions.labelOf(shown) : null;
            items.add(new NextReadItem(shown, bySlug.get(shown).getTitle(), edition, reason, summaries.get(shown)));
        });
        return items;
    }

    /** 코스마다 이 주제 뒤에 오는 주제를 차례로 담는다. 첫 코스에서 다 못 채우면 다음 코스로 넘어간다 */
    private void pickFromCourses(String myTopic, Map<String, String> picks) {
        for (Course course : courseCatalog.all()) {
            List<Course.Step> steps = course.steps().stream().filter(Course.Step::isDocument).toList();
            int at = -1;
            for (int i = 0; i < steps.size(); i++) {
                if (topicOf(steps.get(i).documentSlug()).equals(myTopic)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) continue;
            boolean first = true;
            for (int i = at + 1; i < steps.size() && picks.size() < LIMIT; i++) {
                Course.Step step = steps.get(i);
                String topic = topicOf(step.documentSlug());
                if (topic.equals(myTopic) || picks.containsKey(topic)) continue;
                picks.put(topic, course.title() + " · " + (first ? "다음 단계" : step.seq() + "단계"));
                first = false;
            }
            if (picks.size() >= LIMIT) return;
        }
    }

    /**
     * 태그가 겹치는 주제로 채운다. 겹친 태그마다 log(전체 문서 수 / 그 태그가 붙은 문서 수)를 더한다 —
     * 36편에 붙은 network보다 6편에 붙은 tcp가 훨씬 무겁다. 점수가 같으면 먼저 만든 문서가 앞이다.
     */
    private void pickByTags(String slug, String myTopic, Map<String, DocumentRow> bySlug, Map<String, String> picks) {
        Map<String, Set<String>> tagsByTopic = new HashMap<>();
        Map<String, Set<String>> topicsByTag = new HashMap<>();
        for (SlugTag row : statsRepository.findPublicSlugTags()) {
            String topic = topicOf(row.getSlug());
            tagsByTopic.computeIfAbsent(topic, k -> new HashSet<>()).add(row.getName());
            topicsByTag.computeIfAbsent(row.getName(), k -> new HashSet<>()).add(topic);
        }
        Set<String> mine = tagsByTopic.getOrDefault(myTopic, Set.of());
        if (mine.isEmpty()) return;
        long topicCount = bySlug.keySet().stream().map(NextReadService::topicOf).distinct().count();

        record Scored(String topic, double score, String rarestTag, int order) {
        }
        List<String> topicsInOrder = bySlug.keySet().stream().map(NextReadService::topicOf).distinct().toList();
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < topicsInOrder.size(); i++) {
            String topic = topicsInOrder.get(i);
            if (topic.equals(myTopic) || picks.containsKey(topic)) continue;
            double score = 0;
            String rarest = null;
            int rarestCount = Integer.MAX_VALUE;
            for (String tag : tagsByTopic.getOrDefault(topic, Set.of())) {
                if (!mine.contains(tag)) continue;
                int df = topicsByTag.get(tag).size();
                score += Math.log((double) topicCount / df);
                if (df < rarestCount) {
                    rarestCount = df;
                    rarest = tag;
                }
            }
            if (rarest != null) scored.add(new Scored(topic, score, rarest, i));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::order));
        for (Scored s : scored) {
            if (picks.size() >= LIMIT) return;
            picks.put(s.topic(), "#" + s.rarestTag() + " 태그가 같은 글");
        }
    }

    /** 심화편을 읽는 중이면 그 주제의 심화편을, 아니면 입문편(한 편짜리면 그 문서)을 보여 준다 */
    static String editionToShow(String topic, boolean advanced, Set<String> publicSlugs) {
        String adv = topic + DocumentEditions.ADVANCED_SUFFIX;
        if (advanced && publicSlugs.contains(adv)) return adv;
        if (publicSlugs.contains(topic)) return topic;
        return publicSlugs.contains(adv) ? adv : null;
    }

    /** 입문편·심화편을 한 주제로 묶는 열쇠 — 입문편 slug */
    static String topicOf(String slug) {
        String head = DocumentEditions.isAdvanced(slug) ? DocumentEditions.counterpartSlugOf(slug) : null;
        return head != null ? head : slug; // 꼬리만 남는 이상한 slug는 그대로 한 주제로 둔다
    }

    /** 「핵심 요약」 첫 항목의 굵은 제목. 절이 없거나 그 절 안에 항목이 없으면 null */
    static String summaryOf(String contentMd) {
        if (contentMd == null) return null;
        Matcher head = SUMMARY_HEAD.matcher(contentMd);
        if (!head.find()) return null;
        int next = contentMd.indexOf("\n## ", head.end());
        String section = contentMd.substring(head.end(), next < 0 ? contentMd.length() : next);
        Matcher item = FIRST_BOLD_ITEM.matcher(section);
        return item.find() ? item.group(1).strip() : null;
    }
}
