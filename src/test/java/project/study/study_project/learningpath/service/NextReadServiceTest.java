package project.study.study_project.learningpath.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.learningpath.domain.PathKey;
import project.study.study_project.learningpath.dto.NextReadItem;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.DocumentRow;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.SlugContent;
import project.study.study_project.learningpath.repository.LearningPathStatsRepository.SlugTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class NextReadServiceTest {

    @Mock
    CourseCatalog courseCatalog;
    @Mock
    LearningPathStatsRepository statsRepository;
    @InjectMocks
    NextReadService service;

    record Doc(Long getId, DomainCode getDomain, String getSlug, String getTitle, Integer getSeq)
            implements DocumentRow {
    }

    record Tagged(String getSlug, String getName) implements SlugTag {
    }

    record Content(String getSlug, String getContentMd) implements SlugContent {
    }

    private static final DomainCode NET = DomainCode.of("NETWORK");
    private final List<DocumentRow> docs = new ArrayList<>();

    private void doc(String slug) {
        docs.add(new Doc((long) docs.size() + 1, NET, slug, "제목 " + slug, null));
    }

    private static Course course(String title, String... slugs) {
        List<Course.Step> steps = new ArrayList<>();
        for (String s : slugs) steps.add(Course.Step.document(steps.size() + 1, "제목 " + s, null, s));
        return new Course(PathKey.domain("NETWORK"), title, "", steps);
    }

    private void givenDocs(String... slugs) {
        for (String s : slugs) doc(s);
        given(statsRepository.findPublicDocuments()).willReturn(docs);
        lenient().when(statsRepository.findContents(anyCollection())).thenReturn(List.of());
    }

    @Test
    @DisplayName("코스에서 뒤에 오는 주제 둘을 고른다 — 같은 주제의 다른 편은 건너뛴다")
    void picksNextTopicsInCourse() {
        givenDocs("packet", "packet-advanced", "nat", "nat-advanced", "subnet", "port");
        given(courseCatalog.all()).willReturn(List.of(
                course("네트워크 코스", "packet", "packet-advanced", "nat", "nat-advanced", "subnet", "port")));

        List<NextReadItem> items = service.nextReads("packet");

        assertThat(items).extracting(NextReadItem::slug).containsExactly("nat", "subnet");
        assertThat(items).extracting(NextReadItem::reason)
                .containsExactly("네트워크 코스 · 다음 단계", "네트워크 코스 · 5단계");
        assertThat(items.get(0).edition()).as("짝이 공개돼 있으면 편 이름을 붙인다").isEqualTo("입문편");
        assertThat(items.get(1).edition()).as("한 편짜리 문서에 편 이름을 달면 거짓말이 된다").isNull();
    }

    @Test
    @DisplayName("심화편을 읽는 중이면 다음 주제도 심화편으로 잇는다 — 심화편이 없으면 입문편")
    void advancedReaderGetsAdvancedEdition() {
        givenDocs("packet", "packet-advanced", "nat", "nat-advanced", "subnet");
        given(courseCatalog.all()).willReturn(List.of(course("네트워크 코스", "packet", "nat", "subnet")));

        assertThat(service.nextReads("packet-advanced")).extracting(NextReadItem::slug)
                .containsExactly("nat-advanced", "subnet");
    }

    @Test
    @DisplayName("코스 끝이면 태그로 채운다 — 드문 태그가 겹치는 글이 먼저다")
    void fillsByRareTagsAtCourseEnd() {
        givenDocs("a", "b", "c", "d", "last");
        given(courseCatalog.all()).willReturn(List.of(course("코스", "a", "b", "c", "d", "last")));
        given(statsRepository.findPublicSlugTags()).willReturn(List.of(
                new Tagged("last", "network"), new Tagged("last", "tcp"),
                new Tagged("a", "network"), new Tagged("b", "network"), new Tagged("c", "network"),
                new Tagged("d", "network"), new Tagged("d", "tcp"),
                new Tagged("b", "http")));

        List<NextReadItem> items = service.nextReads("last");

        assertThat(items).extracting(NextReadItem::slug)
                .as("tcp가 겹치는 d가 network만 겹치는 a보다 앞이다. a·b·c는 점수가 같아 먼저 만든 a")
                .containsExactly("d", "a");
        assertThat(items.get(0).reason()).isEqualTo("#tcp 태그가 같은 글");
    }

    @Test
    @DisplayName("코스로 하나만 채우면 나머지 하나를 태그로 채운다")
    void mixesCourseAndTags() {
        givenDocs("x", "a", "b");
        given(courseCatalog.all()).willReturn(List.of(course("코스", "a", "x", "b")));
        given(statsRepository.findPublicSlugTags()).willReturn(List.of(
                new Tagged("x", "os"), new Tagged("a", "os")));

        assertThat(service.nextReads("x")).extracting(NextReadItem::slug, NextReadItem::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("b", "코스 · 다음 단계"),
                        org.assertj.core.groups.Tuple.tuple("a", "#os 태그가 같은 글"));
    }

    @Test
    @DisplayName("숨긴 문서·없는 문서는 404다 — 본문 조회와 같은 기준")
    void hiddenDocumentIsNotFound() {
        givenDocs("a");

        assertThatThrownBy(() -> service.nextReads("hidden")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("카드 요약은 「핵심 요약」 첫 항목의 굵은 제목이다")
    void summaryIsFirstBoldItemOfKeySummary() {
        String md = """
                # 제목

                > **글의 주제**

                ## 핵심 요약

                - **큰 데이터는 잘게 나눠 보낸다** — 결론.
                - **둘째** — 결론.

                ## 바탕이 되는 개념
                """;
        assertThat(NextReadService.summaryOf(md)).isEqualTo("큰 데이터는 잘게 나눠 보낸다");
        assertThat(NextReadService.summaryOf("# 제목\n\n## 핵심 요약\n\n문장만 있다.\n\n## 다음\n\n- **엉뚱한 항목** — x"))
                .as("다음 절의 항목을 요약으로 집으면 안 된다").isNull();
        assertThat(NextReadService.summaryOf("# 요약 절 없음")).isNull();
    }

    @Test
    @DisplayName("고른 문서의 요약을 카드에 싣는다")
    void attachesSummary() {
        givenDocs("x", "y");
        given(courseCatalog.all()).willReturn(List.of(course("코스", "x", "y")));
        given(statsRepository.findContents(Set.of("y"))).willReturn(List.of(
                new Content("y", "## 핵심 요약\n\n- **와이 요약** — 결론.\n")));
        lenient().when(statsRepository.findPublicSlugTags()).thenReturn(List.of());

        assertThat(service.nextReads("x")).extracting(NextReadItem::summary).containsExactly("와이 요약");
    }
}
