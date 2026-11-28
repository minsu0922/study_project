package project.study.study_project.map.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.domainsetting.support.DomainCatalog;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.KeyCount;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.KeyProgress;
import project.study.study_project.llm.client.ClaudeDocumentGenerator;
import project.study.study_project.llm.client.DocumentEdition;
import project.study.study_project.map.dto.MapResponse;
import project.study.study_project.map.dto.MapResponse.DocLink;
import project.study.study_project.map.dto.MapResponse.DomainNode;
import project.study.study_project.map.dto.MapResponse.TopicNode;
import project.study.study_project.map.repository.MapQueryRepository;
import project.study.study_project.map.repository.MapQueryRepository.DocRow;
import project.study.study_project.map.repository.MapQueryRepository.TopicRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 지도 조립 — 쿼리는 네 번(주제, 문서, 문제 수, 내 진도)이고 주제 수와 상관없다. */
@Service
@RequiredArgsConstructor
public class MapService {

    private static final int MAX_TITLE = 24;

    private final MapQueryRepository mapRepository;
    private final LearningPathStatsRepository statsRepository;
    private final DomainCatalog domainCatalog;

    /** @param userId 비로그인이면 null — 진도 쿼리를 건너뛰고 0으로 채운다 */
    @Transactional(readOnly = true)
    public MapResponse map(Long userId) {
        List<DomainCode> domains = domainCatalog.enabled();
        List<TopicRow> topics = domains.isEmpty() ? List.of() : mapRepository.conceptTopics(domains);
        List<Long> topicIds = topics.stream().map(TopicRow::getId).toList();
        List<DocRow> docs = topicIds.isEmpty() ? List.of() : mapRepository.linkedDocuments(topicIds);
        List<String> slugs = docs.stream().map(DocRow::getSlug).toList();

        Map<String, Long> totalBySlug = slugs.isEmpty() ? Map.of()
                : statsRepository.countProblemsBySlug(slugs).stream()
                        .collect(Collectors.toMap(KeyCount::getKey, KeyCount::getCnt));
        Map<String, KeyProgress> mineBySlug = userId == null || slugs.isEmpty() ? Map.of()
                : statsRepository.progressBySlug(userId, slugs).stream()
                        .collect(Collectors.toMap(KeyProgress::getKey, Function.identity()));

        Map<Long, List<DocRow>> docsByTopic = docs.stream().collect(Collectors.groupingBy(DocRow::getTopicId));
        // DomainCode는 record라 equals로 묶인다 — 분야 순서는 아래에서 등록부 순서로 다시 뽑는다
        Map<DomainCode, List<TopicNode>> nodesByDomain = new HashMap<>();
        for (TopicRow t : topics) {
            List<DocRow> mine = docsByTopic.getOrDefault(t.getId(), List.of());
            long total = 0, attempted = 0, solved = 0;
            List<DocLink> links = new ArrayList<>();
            for (DocRow d : mine) {
                total += totalBySlug.getOrDefault(d.getSlug(), 0L);
                KeyProgress p = mineBySlug.get(d.getSlug());
                if (p != null) {
                    attempted += p.getAttempted();
                    solved += p.getSolved();
                }
                links.add(new DocLink(d.getSlug(), d.getTitle(), editionOf(d)));
            }
            nodesByDomain.computeIfAbsent(t.getDomain(), k -> new ArrayList<>())
                    .add(new TopicNode(t.getId(), titleOf(t, mine), links, total, attempted, solved));
        }

        List<DomainNode> result = new ArrayList<>();
        for (DomainCode code : domains) {
            List<TopicNode> nodes = nodesByDomain.get(code);
            if (nodes != null) {
                result.add(new DomainNode(code.value(), domainCatalog.displayName(code), nodes));
            }
        }
        return new MapResponse(result);
    }

    private static String editionOf(DocRow d) {
        DocumentEdition e = ClaudeDocumentGenerator.editionOf(d.getContentMd());
        return e == DocumentEdition.ADVANCED ? "ADVANCED" : "BEGINNER";
    }

    /** 입문편 제목, 없으면 첫 문서 제목, 문서가 없으면 줄인 주제 문장 */
    private static String titleOf(TopicRow topic, List<DocRow> docs) {
        if (docs.isEmpty()) {
            return shorten(topic.getTopic());
        }
        return docs.stream().filter(d -> "BEGINNER".equals(editionOf(d))).findFirst()
                .orElse(docs.get(0)).getTitle();
    }

    /** 구분자(" — ", " (", "—") 중 처음 나오는 곳 앞까지 자르고, 그래도 24자를 넘으면 24자 + 줄임표 */
    static String shorten(String topic) {
        String s = topic;
        int cut = -1;
        for (String sep : new String[]{" — ", " (", "—"}) {
            int i = s.indexOf(sep);
            if (i > 0 && (cut < 0 || i < cut)) {
                cut = i;
            }
        }
        if (cut > 0) {
            s = s.substring(0, cut);
        }
        s = s.strip();
        return s.length() > MAX_TITLE ? s.substring(0, MAX_TITLE) + "…" : s;
    }
}
