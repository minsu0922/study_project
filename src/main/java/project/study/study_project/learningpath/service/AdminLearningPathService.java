package project.study.study_project.learningpath.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.document.support.DocumentEditions;
import project.study.study_project.domainsetting.support.DomainCatalog;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.learningpath.domain.DomainCourseOrder;
import project.study.study_project.learningpath.repository.DomainCourseOrderRepository;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.learningpath.domain.LearningPath;
import project.study.study_project.learningpath.domain.LearningPathStep;
import project.study.study_project.learningpath.dto.AdminLearningPathItem;
import project.study.study_project.learningpath.dto.AdminLearningPathRequest;
import project.study.study_project.learningpath.dto.AdminPathDocumentOption;
import project.study.study_project.learningpath.repository.LearningPathRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.AdminDocumentRow;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 코스 등록·수정·삭제 — 분야를 넘나드는 순서(예: 입문자 코스)를 사람이 정한다(docs/26).
 * 분야별·코드 코스는 규칙으로 만들어서 여기서 다루지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminLearningPathService {

    private final LearningPathRepository pathRepository;
    private final DocumentRepository documentRepository;
    private final LearningPathStatsRepository statsRepository;
    private final DomainCatalog domainCatalog;
    private final DomainCourseOrderRepository orderRepository;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public List<AdminLearningPathItem> list() {
        return pathRepository.findAllOrdered().stream().map(p -> toItem(p, false)).toList();
    }

    @Transactional(readOnly = true)
    public AdminLearningPathItem get(Long id) {
        return toItem(find(id), true);
    }

    /** 단계로 고를 수 있는 문서 전부 — 분야 순, 분야 안에서는 먼저 만든 순 */
    @Transactional(readOnly = true)
    public List<AdminPathDocumentOption> documentOptions() {
        List<AdminDocumentRow> docs = statsRepository.findAllDocumentsForAdmin();
        return toOptions(docs, docs);
    }

    /**
     * 분야 코스의 지금 순서 — 사용자 화면과 같은 규칙(CourseCatalog.orderedForDomain)으로 줄 세운다.
     * 내린 문서도 자리를 보여 준다. 사용자 코스에서는 빠진다.
     */
    @Transactional(readOnly = true)
    public List<AdminPathDocumentOption> domainOrder(DomainCode domain) {
        requireDomain(domain);
        List<AdminDocumentRow> all = statsRepository.findAllDocumentsForAdmin();
        List<AdminDocumentRow> mine = all.stream().filter(d -> d.getDomain().equals(domain)).toList();
        return toOptions(CourseCatalog.orderedForDomain(mine), all);
    }

    /**
     * 분야 코스 순서 저장 — 그 분야의 순번을 통째로 지우고 받은 순서대로 1부터 다시 쓴다.
     * 빈 목록이면 지우기만 해서 기본 순서로 돌아간다.
     */
    @Transactional
    public List<AdminPathDocumentOption> saveDomainOrder(DomainCode domain, List<Long> ids) {
        requireDomain(domain);
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ErrorCode.PATH_002, "같은 문서를 두 자리에 넣을 수 없습니다.");
        }
        Set<Long> domainDocs = statsRepository.findAllDocumentsForAdmin().stream()
                .filter(d -> d.getDomain().equals(domain)).map(AdminDocumentRow::getId).collect(Collectors.toSet());
        if (!domainDocs.containsAll(ids)) {
            throw new BusinessException(ErrorCode.PATH_002, "이 분야의 문서가 아닌 것이 들어 있습니다.");
        }
        orderRepository.deleteByDomain(domain);
        for (int i = 0; i < ids.size(); i++) {
            orderRepository.save(new DomainCourseOrder(ids.get(i), i + 1));
        }
        em.flush();
        return domainOrder(domain);
    }

    private void requireDomain(DomainCode domain) {
        if (!domainCatalog.exists(domain)) throw new BusinessException(ErrorCode.DOMAIN_003);
    }

    /** @param all 편 이름을 정할 때 짝 문서가 있는지 볼 범위 */
    private List<AdminPathDocumentOption> toOptions(List<AdminDocumentRow> docs, List<AdminDocumentRow> all) {
        Set<String> slugs = all.stream().map(AdminDocumentRow::getSlug).collect(Collectors.toSet());
        Map<String, Long> counts = problemCounts(docs.stream().map(AdminDocumentRow::getSlug).collect(Collectors.toSet()));
        return docs.stream().map(d -> {
            String pair = DocumentEditions.counterpartSlugOf(d.getSlug());
            String edition = pair != null && slugs.contains(pair) ? DocumentEditions.labelOf(d.getSlug()) : null;
            return new AdminPathDocumentOption(d.getId(), d.getSlug(), d.getTitle(), d.getDomain().value(),
                    domainCatalog.displayName(d.getDomain()), edition, d.isHidden(),
                    counts.getOrDefault(d.getSlug(), 0L), d.getSeq());
        }).toList();
    }

    @Transactional
    public AdminLearningPathItem create(AdminLearningPathRequest req) {
        List<Document> docs = documentsInOrder(req.documentIds());
        LearningPath path = LearningPath.create(req.title().strip(), req.description().strip(), req.sortOrder());
        path.addSteps(docs);
        applyPublished(path, req.published(), docs);
        return toItem(pathRepository.save(path), true);
    }

    @Transactional
    public AdminLearningPathItem update(Long id, AdminLearningPathRequest req) {
        LearningPath path = find(id);
        List<Document> docs = documentsInOrder(req.documentIds());
        path.update(req.title().strip(), req.description().strip(), req.sortOrder());
        path.clearSteps();
        // 지운 단계를 먼저 DB에 반영해야 같은 문서를 다시 넣을 때 (path_id, document_id) UNIQUE에 안 걸린다
        em.flush();
        path.addSteps(docs);
        applyPublished(path, req.published(), docs);
        return toItem(path, true);
    }

    /** 코스만 지운다. 사용자의 "시작한 코스" 행은 남지만 화면이 없는 키를 건너뛴다(V43 주석) */
    @Transactional
    public void delete(Long id) {
        pathRepository.delete(find(id));
    }

    private void applyPublished(LearningPath path, boolean published, List<Document> docs) {
        if (!published) {
            path.unpublish();
            return;
        }
        if (docs.stream().allMatch(Document::isHidden)) throw new BusinessException(ErrorCode.PATH_003);
        path.publish();
    }

    /** 요청 순서를 지켜 문서를 읽는다. 없는 id·같은 문서 두 번은 PATH_002 */
    private List<Document> documentsInOrder(List<Long> ids) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ErrorCode.PATH_002, "같은 문서를 두 단계에 넣을 수 없습니다.");
        }
        Map<Long, Document> found = documentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        if (found.size() != ids.size()) {
            throw new BusinessException(ErrorCode.PATH_002, "없는 문서가 단계에 들어 있습니다.");
        }
        return ids.stream().map(found::get).toList();
    }

    private LearningPath find(Long id) {
        return pathRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PATH_001));
    }

    private Map<String, Long> problemCounts(Set<String> slugs) {
        if (slugs.isEmpty()) return Map.of();
        return statsRepository.countProblemsBySlug(slugs).stream()
                .collect(Collectors.toMap(LearningPathStatsRepository.KeyCount::getKey,
                        LearningPathStatsRepository.KeyCount::getCnt));
    }

    private AdminLearningPathItem toItem(LearningPath p, boolean withSteps) {
        List<LearningPathStep> steps = p.getSteps();
        int visible = (int) steps.stream().filter(s -> !s.getDocument().isHidden()).count();
        List<AdminLearningPathItem.Step> stepItems = List.of();
        if (withSteps) {
            Map<String, Long> counts = problemCounts(
                    steps.stream().map(s -> s.getDocument().getSlug()).collect(Collectors.toSet()));
            stepItems = steps.stream().map(s -> {
                Document d = s.getDocument();
                return new AdminLearningPathItem.Step(s.getSeq(), d.getId(), d.getSlug(), d.getTitle(),
                        domainCatalog.displayName(d.getDomain()), d.isHidden(), counts.getOrDefault(d.getSlug(), 0L));
            }).toList();
        }
        return new AdminLearningPathItem(p.getId(), p.getTitle(), p.getDescription(), p.getSortOrder(),
                p.isPublished(), steps.size(), visible, p.getUpdatedAt(), stepItems);
    }
}
