package project.study.study_project.learningpath.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.DocumentRow;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 분야별 코스의 단계 순서 규칙 — 쿼리 없이 순수하게 본다. */
class CourseCatalogTest {

    private static final DomainCode NETWORK = DomainCode.of("NETWORK");

    @Test
    @DisplayName("주제마다 입문편 다음 심화편, 주제끼리는 먼저 만든 문서가 앞이다")
    void ordersTopicsByFirstCreatedAndBeginnerFirst() {
        // id 순: tcp 심화편이 입문편보다 먼저 만들어진 경우까지 섞는다
        List<DocumentRow> docs = List.of(
                row(1, "tcp-advanced", "TCP"),
                row(2, "dns", "DNS"),
                row(3, "tcp", "TCP"),
                row(4, "http", "HTTP"),
                row(5, "http-advanced", "HTTP"));
        Set<String> slugs = docs.stream().map(DocumentRow::getSlug).collect(Collectors.toSet());

        Course course = CourseCatalog.domainCourse(NETWORK, "네트워크", docs, slugs);

        assertThat(course.key().toString()).isEqualTo("domain:NETWORK");
        assertThat(course.title()).isEqualTo("네트워크 코스");
        assertThat(course.steps()).extracting(Course.Step::seq, Course.Step::documentSlug, Course.Step::edition)
                .containsExactly(
                        tuple(1, "tcp", "입문편"),
                        tuple(2, "tcp-advanced", "심화편"),
                        tuple(3, "dns", null),          // 짝이 없으면 편 이름을 달지 않는다
                        tuple(4, "http", "입문편"),
                        tuple(5, "http-advanced", "심화편"));
    }

    @Test
    @DisplayName("순번을 정한 문서가 순번대로 앞에 서고, 나머지는 기본 규칙으로 뒤에 붙는다")
    void manualOrderComesFirstThenDefault() {
        List<DocumentRow> docs = List.of(
                row(1, "tcp", "TCP", null),
                row(2, "tcp-advanced", "TCP", 3),
                row(3, "dns", "DNS", null),
                row(4, "http", "HTTP", 1),
                row(5, "udp", "UDP", 2),
                row(6, "new-doc", "새 문서", null));   // 나중에 공개된 문서 — 손대지 않아도 뒤에 붙는다

        assertThat(CourseCatalog.orderedForDomain(docs)).extracting(DocumentRow::getSlug)
                .containsExactly("http", "udp", "tcp-advanced", "tcp", "dns", "new-doc");
    }

    private static DocumentRow row(long id, String slug, String title) {
        return row(id, slug, title, null);
    }

    private static DocumentRow row(long id, String slug, String title, Integer seq) {
        return new DocumentRow() {
            public Long getId() { return id; }
            public DomainCode getDomain() { return NETWORK; }
            public String getSlug() { return slug; }
            public String getTitle() { return title; }
            public Integer getSeq() { return seq; }
        };
    }
}
