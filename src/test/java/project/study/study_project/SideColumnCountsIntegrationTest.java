package project.study.study_project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.discussion.repository.PostRepository;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.DocumentFacets;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.global.common.DomainCount;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.domain.Submission;
import project.study.study_project.quiz.repository.SubmissionRepository;
import project.study.study_project.quiz.service.WrongAnswerService;
import project.study.study_project.review.domain.ReviewItem;
import project.study.study_project.review.dto.ReviewCounts;
import project.study.study_project.review.repository.ReviewItemRepository;
import project.study.study_project.review.service.ReviewService;
import project.study.study_project.tag.domain.Tag;
import project.study.study_project.tag.repository.TagRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서 목록·커뮤니티·복습·오답노트 옆 기둥의 수. 테스트 DB에 다른 테스트의 데이터가 남아 있을 수 있어 늘어난 만큼만 본다.
 * MySQL이 필요하다.
 */
@SpringBootTest(properties = "llm.import.enabled=false")
@Transactional
class SideColumnCountsIntegrationTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private PostService postService;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private SubmissionRepository submissionRepository;
    @Autowired
    private WrongAnswerService wrongAnswerService;
    @Autowired
    private ReviewItemRepository reviewItemRepository;
    @Autowired
    private ReviewService reviewService;

    @Test
    @DisplayName("문서 수는 공개 문서만 분야별·태그별로 센다")
    void documentFacetsCountPublicOnly() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        Tag tag = tagRepository.save(Tag.of("zq" + key));
        long networkBefore = domainCount(documentService.getFacets().domains(), TestDomains.NETWORK.value());

        documentRepository.save(Document.create(TestDomains.NETWORK, "공개 " + key, "a-" + key, "본문", null, Set.of(tag)));
        documentRepository.save(Document.create(TestDomains.NETWORK, "공개2 " + key, "b-" + key, "본문", null, Set.of(tag)));
        Document hidden = documentRepository.save(
                Document.create(TestDomains.NETWORK, "내림 " + key, "c-" + key, "본문", null, Set.of(tag)));
        hidden.hide();
        documentRepository.flush();

        DocumentFacets facets = documentService.getFacets();
        assertThat(domainCount(facets.domains(), TestDomains.NETWORK.value())).isEqualTo(networkBefore + 2);
        assertThat(facets.tags()).contains(new DocumentFacets.TagCount("zq" + key, 2));
    }

    @Test
    @DisplayName("커뮤니티 수는 분야별로 보이는 글만 센다")
    void postDomainCountsVisibleOnly() {
        long before = domainCount(postService.domainCounts(), TestDomains.NETWORK.value());
        Problem problem = fixtures.problem();
        Long writer = fixtures.user(Role.USER).getId();
        fixtures.post(problem, writer);
        fixtures.post(problem, writer);
        Post hidden = fixtures.post(problem, writer);
        hidden.hide();
        postRepository.flush();

        assertThat(domainCount(postService.domainCounts(), TestDomains.NETWORK.value())).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("오답노트 수는 틀린 적 있는 문제를 분야별로 한 번씩 센다")
    void wrongAnswerDomainCountsDistinctProblems() {
        User user = fixtures.user(Role.USER);
        Problem twiceWrong = fixtures.problem();
        Problem right = fixtures.problem();
        submissionRepository.save(Submission.of(user.getId(), twiceWrong, "X", false));
        submissionRepository.save(Submission.of(user.getId(), twiceWrong, "X", false));
        submissionRepository.save(Submission.of(user.getId(), right, "O", true));
        submissionRepository.flush();

        assertThat(wrongAnswerService.domainCounts(user.getId()))
                .containsExactly(new DomainCount(TestDomains.NETWORK.value(), 1));
    }

    @Test
    @DisplayName("복습 수는 분야별 합과 상태별 수를 함께 낸다")
    void reviewCountsByDomainAndStatus() {
        User user = fixtures.user(Role.USER);
        LocalDateTime later = LocalDateTime.now().plusDays(1);
        reviewItemRepository.save(ReviewItem.firstWrong(user.getId(), fixtures.problem(), later));
        reviewItemRepository.save(ReviewItem.firstWrong(user.getId(), fixtures.problem(), later));
        ReviewItem done = reviewItemRepository.save(ReviewItem.firstWrong(user.getId(), fixtures.problem(), later));
        done.graduate(later);
        reviewItemRepository.flush();

        ReviewCounts counts = reviewService.getCounts(user.getId());
        assertThat(counts.domains()).containsExactly(new DomainCount(TestDomains.NETWORK.value(), 3));
        assertThat(counts.learning()).isEqualTo(2);
        assertThat(counts.graduated()).isEqualTo(1);
    }

    private static long domainCount(List<DomainCount> rows, String domain) {
        return rows.stream().filter(r -> r.domain().equals(domain)).mapToLong(DomainCount::count).sum();
    }
}
