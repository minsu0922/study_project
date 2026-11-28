package project.study.study_project.map.dto;

import java.util.List;

/** 백엔드 기술 지도 — 분야마다 점(주제)이 있고, 점마다 문서와 내 진도가 붙는다. */
public record MapResponse(List<DomainNode> domains) {

    public record DomainNode(String code, String label, List<TopicNode> topics) {
    }

    public record TopicNode(Long id, String title, List<DocLink> documents,
                            long problemTotal, long attempted, long solved) {
    }

    /** @param edition "BEGINNER" 또는 "ADVANCED" */
    public record DocLink(String slug, String title, String edition) {
    }
}
