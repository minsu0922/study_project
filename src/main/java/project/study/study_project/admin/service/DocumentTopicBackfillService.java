package project.study.study_project.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.DocumentTopicBackfillResult;
import project.study.study_project.admin.dto.DocumentTopicBackfillResult.Unlinked;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.domain.TopicQueueItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;
import project.study.study_project.llm.dto.TopicUsageFile;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.FileFormats;
import project.study.study_project.llm.support.TopicQueue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 주제 id를 적기 전에 나간 문서를 주제에 잇는다.
 *
 * <p>사용 기록의 (주제, 사용 날짜)로 그날 문서 파일을 찾고, 파일에 적힌 입문편·심화편 slug로 문서를 찾는다.
 * 같은 주제로 다시 이어 써도 결과가 같아서 몇 번 돌려도 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTopicBackfillService {

    private static final int ALREADY_LINKED = -1;

    private final TopicQueueItemRepository topicRepository;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    @Value("${llm.import.dir:generated}")
    private String importDir;

    @Transactional
    public DocumentTopicBackfillResult backfill() {
        List<Unlinked> unlinked = new ArrayList<>();
        int linked = 0;
        Path base = Path.of(importDir);
        for (TopicUsageFile.Usage usage : readUsage(base)) {
            if (usage.id() == null || usage.lastUsedAt() == null) {
                continue;   // 번호나 사용 날짜가 없으면 어느 파일인지 알 수 없다
            }
            Unlinked problem = null;
            TopicQueueItem topic = topicRepository.findById(usage.id()).orElse(null);
            GeneratedDocumentFile file = null;
            if (topic == null) {
                problem = unlinked(usage, "TOPIC_MISSING");
            } else {
                Path path = base.resolve("documents").resolve(usage.lastUsedAt() + ".json");
                file = Files.exists(path) ? readFile(path) : null;
                if (file == null) {
                    problem = unlinked(usage, "FILE_MISSING");
                } else if (!sameDomain(file, usage, topic)) {
                    problem = unlinked(usage, "DOMAIN_MISMATCH");
                }
            }
            if (problem != null) {
                unlinked.add(problem);
                continue;
            }
            int[] results = {link(topic.getId(), file.document()), link(topic.getId(), file.advancedDocument())};
            int count = 0;
            boolean alreadyLinked = false;
            for (int result : results) {
                if (result == ALREADY_LINKED) {
                    alreadyLinked = true;
                } else {
                    count += result;
                }
            }
            if (alreadyLinked) {
                unlinked.add(unlinked(usage, "ALREADY_LINKED"));
            } else if (count == 0) {
                unlinked.add(unlinked(usage, "DOCUMENT_MISSING"));
            }
            linked += count;
        }
        return new DocumentTopicBackfillResult(linked, unlinked);
    }

    /** 손으로 잇는다. topicId가 null이면 푼다. 문서 본문은 건드리지 않으므로 캐시를 비우지 않는다 */
    @Transactional
    public void linkTopic(Long documentId, Long topicId) {
        var document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001));
        if (topicId != null && !topicRepository.existsById(topicId)) {
            throw new BusinessException(ErrorCode.TOPIC_001);
        }
        document.linkTopic(topicId);
    }

    /** 기록에 분야가 없으면 대기열 줄의 분야와 견준다 */
    private static boolean sameDomain(GeneratedDocumentFile file, TopicUsageFile.Usage usage, TopicQueueItem topic) {
        String expected = usage.domain() != null ? usage.domain().trim() : topic.getDomain().value();
        return file.domain() != null && file.domain().value().equals(expected);
    }

    /** 이은 문서는 1, 못 찾으면 0, 이미 다른 주제에 이어져 있으면 ALREADY_LINKED. 손으로 이은 것을 덮지 않는다 */
    private int link(Long topicId, GeneratedDocumentItem item) {
        if (item == null || item.slug() == null) {
            return 0;
        }
        return documentRepository.findBySlug(item.slug()).map(document -> {
            if (document.getTopicId() != null && !document.getTopicId().equals(topicId)) {
                return ALREADY_LINKED;
            }
            document.linkTopic(topicId);
            return 1;
        }).orElse(0);
    }

    private static Unlinked unlinked(TopicUsageFile.Usage usage, String reason) {
        return new Unlinked(usage.id(), usage.lastUsedAt(), reason);
    }

    private List<TopicUsageFile.Usage> readUsage(Path base) {
        Path file = base.resolve(TopicQueue.USAGE_FILE_NAME);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            TopicUsageFile usage = FileFormats.read(objectMapper, file, FileFormats.Kind.TOPIC_USAGE, TopicUsageFile.class);
            return usage.usage() == null ? List.of() : usage.usage();
        } catch (IOException e) {
            log.error("주제 사용 기록 파일을 읽지 못했습니다: {}", file, e);
            throw new BusinessException(ErrorCode.COMMON_500);
        }
    }

    private GeneratedDocumentFile readFile(Path path) {
        try {
            return FileFormats.read(objectMapper, path, FileFormats.Kind.DOCUMENTS, GeneratedDocumentFile.class);
        } catch (IOException e) {
            log.warn("문서 파일을 읽지 못해 건너뜁니다: {} — {}", path.getFileName(), e.getMessage());
            return null;
        }
    }
}
