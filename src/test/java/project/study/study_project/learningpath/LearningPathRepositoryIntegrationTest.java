package project.study.study_project.learningpath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.learningpath.domain.LearningPath;
import project.study.study_project.learningpath.domain.LearningPathStep;
import project.study.study_project.learningpath.repository.LearningPathRepository;
import project.study.study_project.learningpath.repository.UserLearningPathRepository;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V43 테이블 — 단계 순서, 순서 다시 쓰기, 문서 삭제 시 단계 정리, "시작한 코스" 기록. 실제 MySQL로 본다. */
@SpringBootTest
@Transactional
class LearningPathRepositoryIntegrationTest {

    @Autowired
    private LearningPathRepository pathRepository;
    @Autowired
    private UserLearningPathRepository userPathRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private TestFixtures fixtures;
    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("단계는 넣은 순서로 1부터 번호가 붙고, 다시 쓰면 새 순서로 매겨진다")
    void stepsKeepOrderAndCanBeRewritten() {
        Document a = doc("a"), b = doc("b"), c = doc("c");
        LearningPath path = LearningPath.create("입문자 코스", "설명", 0);
        path.addSteps(List.of(a, b, c));
        pathRepository.save(path);
        em.flush();
        em.clear();

        assertThat(slugsOf(path.getId())).containsExactly(a.getSlug(), b.getSlug(), c.getSlug());

        LearningPath loaded = pathRepository.findById(path.getId()).orElseThrow();
        loaded.clearSteps();
        em.flush();   // 비운 뒤 flush하지 않으면 (path_id, document_id) UNIQUE에 걸린다(LearningPath.clearSteps)
        loaded.addSteps(List.of(c, a));
        em.flush();
        em.clear();

        LearningPath rewritten = pathRepository.findById(path.getId()).orElseThrow();
        assertThat(rewritten.getSteps()).extracting(LearningPathStep::getSeq).containsExactly(1, 2);
        assertThat(slugsOf(path.getId())).containsExactly(c.getSlug(), a.getSlug());
    }

    @Test
    @DisplayName("문서를 지우면 그 단계만 빠지고 코스는 남는다")
    void deletingDocumentDropsOnlyItsStep() {
        Document a = doc("a"), b = doc("b");
        LearningPath path = LearningPath.create("코스", "설명", 0);
        path.addSteps(List.of(a, b));
        pathRepository.save(path);
        em.flush();
        em.clear();

        em.createQuery("delete from Document d where d.id = :id").setParameter("id", a.getId()).executeUpdate();
        em.clear();

        assertThat(pathRepository.findById(path.getId())).isPresent();
        assertThat(slugsOf(path.getId())).containsExactly(b.getSlug());
    }

    @Test
    @DisplayName("공개 목록에는 공개한 코스만 순서대로 나온다")
    void publishedOnlyInOrder() {
        LearningPath second = LearningPath.create("둘째", "설명", 2);
        LearningPath first = LearningPath.create("첫째", "설명", 1);
        LearningPath draft = LearningPath.create("만드는 중", "설명", 0);
        second.publish();
        first.publish();
        pathRepository.saveAll(List.of(second, first, draft));

        assertThat(pathRepository.findPublished())
                .filteredOn(p -> List.of(first.getId(), second.getId(), draft.getId()).contains(p.getId()))
                .extracting(LearningPath::getTitle).containsExactly("첫째", "둘째");
    }

    @Test
    @DisplayName("코스를 두 번 시작해도 한 행이고, 마지막 학습 시각만 당겨진다")
    void touchIsIdempotent() {
        User user = fixtures.user(Role.USER);
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 1, 9, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 2, 9, 0);
        userPathRepository.touch(user.getId(), "domain:NETWORK", t1);
        userPathRepository.touch(user.getId(), "domain:NETWORK", t2);
        userPathRepository.touch(user.getId(), "code:java", t1);
        em.clear();

        var mine = userPathRepository.findMine(user.getId());
        assertThat(mine).extracting(u -> u.getPathKey()).containsExactly("domain:NETWORK", "code:java");
        assertThat(mine.get(0).getStartedAt()).isEqualTo(t1);
        assertThat(mine.get(0).getLastStudiedAt()).isEqualTo(t2);
    }

    private List<String> slugsOf(Long pathId) {
        return em.createQuery("""
                        select s.document.slug from LearningPathStep s
                        where s.path.id = :id order by s.seq""", String.class)
                .setParameter("id", pathId).getResultList();
    }

    private Document doc(String name) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        return documentRepository.save(Document.create(
                TestDomains.NETWORK, name + key, name + "-" + key, "본문", null, Set.of()));
    }
}
