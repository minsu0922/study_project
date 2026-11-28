package project.study.study_project.map.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import project.study.study_project.document.domain.Document;
import project.study.study_project.global.common.DomainCode;

import java.util.Collection;
import java.util.List;

/** 지도에 쓰는 읽기 쿼리 — 개념 주제와, 그 주제에 이어진 공개 문서. */
public interface MapQueryRepository extends Repository<Document, Long> {

    @Query("select t.id as id, t.domain as domain, t.topic as topic from TopicQueueItem t "
            + "where t.kind = project.study.study_project.llm.domain.TopicKind.CONCEPT and t.domain in :domains "
            + "order by t.sortOrder asc, t.id asc")
    List<TopicRow> conceptTopics(@Param("domains") Collection<DomainCode> domains);

    @Query("select d.topicId as topicId, d.slug as slug, d.title as title, d.contentMd as contentMd "
            + "from Document d where d.hidden = false and d.topicId in :topicIds order by d.id asc")
    List<DocRow> linkedDocuments(@Param("topicIds") Collection<Long> topicIds);

    interface TopicRow {
        Long getId();

        DomainCode getDomain();

        String getTopic();
    }

    interface DocRow {
        Long getTopicId();

        String getSlug();

        String getTitle();

        String getContentMd();
    }
}
