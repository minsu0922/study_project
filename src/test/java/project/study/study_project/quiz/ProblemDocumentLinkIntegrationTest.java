package project.study.study_project.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.dto.QuizSubmitResponse;
import project.study.study_project.quiz.dto.WrongAnswerItem;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.quiz.service.WrongAnswerService;
import project.study.study_project.user.domain.User;
import project.study.study_project.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문제 → 개념 문서 링크 통합 테스트 — docs/15 3단계.
 *
 * <p>이 기능의 핵심은 링크를 <b>거는 것</b>이 아니라 <b>죽은 링크를 안 거는 것</b>이다.
 * 문제의 {@code document_slug}는 FK가 아니라 이름표라(V9) 가리키는 문서가 없을 수 있고,
 * 특히 <b>문제는 승인했는데 근거 문서는 아직 검수 대기</b>인 상황이 정상적으로 자주 생긴다.
 * 확인 없이 링크를 걸면 학습자가 눌렀을 때 "문서를 찾을 수 없습니다"를 만난다.
 *
 * <p>실제 DB가 필요하다 — slug 실재 확인이 진짜 조회이기 때문에 단위 테스트로는 볼 수 없다.
 */
@SpringBootTest
@Transactional
class ProblemDocumentLinkIntegrationTest {

    @Autowired
    private QuizService quizService;
    @Autowired
    private WrongAnswerService wrongAnswerService;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminDocumentService adminDocumentService;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .username("doclink" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("bcrypt-not-needed-here")
                .build());
        userId = user.getId();
    }

    @Test
    @DisplayName("근거 문서가 실제로 있으면 채점 응답에 slug가 실린다")
    void submitCarriesDocumentSlugWhenDocumentExists() {
        String slug = createDocument();
        Problem problem = createProblem(slug);

        QuizSubmitResponse response = submit(problem, "O");

        assertThat(response.documentSlug()).isEqualTo(slug);
    }

    /**
     * <b>이 테스트가 3단계의 핵심이다.</b> 문제에 slug는 박혀 있지만 그 문서가 아직 정식 문서가
     * 아닌 상황 — 문제만 먼저 승인하면 바로 이렇게 된다. 여기서 slug를 그대로 내려보내면
     * 학습자는 정답 화면에서 링크를 보고 눌렀다가 빈 페이지를 만난다.
     */
    @Test
    @DisplayName("근거 문서가 아직 없으면 slug를 내려보내지 않는다 — 죽은 링크를 보여주지 않기 위해")
    void submitOmitsSlugWhenDocumentDoesNotExist() {
        Problem problem = createProblem("not-approved-yet-" + UUID.randomUUID().toString().substring(0, 8));

        QuizSubmitResponse response = submit(problem, "O");

        assertThat(response.documentSlug())
                .as("문제에 slug가 박혀 있어도 문서가 없으면 null이어야 한다")
                .isNull();
    }

    @Test
    @DisplayName("근거 문서 없이 만든 문제는 당연히 null — 기존 문제 대부분이 이 경우다")
    void submitReturnsNullForProblemWithoutSlug() {
        Problem problem = createProblem(null);

        assertThat(submit(problem, "O").documentSlug()).isNull();
    }

    /**
     * 오답노트는 목록이라 항목마다 존재 확인을 하면 N+1이 된다. 여기서는 결과의 옳고 그름만
     * 보지만, 구현이 slug를 모아 한 번에 묻도록 되어 있어야 이 테스트가 통과하면서도 느려지지 않는다.
     */
    @Test
    @DisplayName("오답노트에도 같은 규칙이 적용된다 — 있는 문서만 링크, 없는 문서는 null")
    void wrongAnswerListAppliesSameRule() {
        String slug = createDocument();
        Problem withDocument = createProblem(slug);
        Problem withDeadSlug = createProblem("missing-" + UUID.randomUUID().toString().substring(0, 8));

        submit(withDocument, "X");  // 정답이 "O"라 오답 → 오답노트에 오른다
        submit(withDeadSlug, "X");

        List<WrongAnswerItem> items = wrongAnswerService
                .getWrongAnswers(userId, null, PageRequest.of(0, 20)).content();

        assertThat(items).hasSize(2);
        assertThat(slugOf(items, withDocument.getId())).isEqualTo(slug);
        assertThat(slugOf(items, withDeadSlug.getId())).isNull();
    }

    /**
     * 이 테스트가 없어서 오답노트가 통째로 깨진 채 지나갔다(2026-08-20 발견).
     *
     * <p>위 테스트는 "slug 있음"과 "죽은 slug" 두 경우만 봤는데, 정작 <b>가장 흔한 경우</b>인
     * "slug가 아예 null"이 빠져 있었다. 근거 문서 없이 만든 옛 문제들이 여기 해당하고,
     * 그런 문제가 오답에 하나라도 섞이면 {@code Set.of().contains(null)}이 NPE를 던져
     * 오답노트 전체가 500이 됐다. 흔한 경우일수록 테스트에서 빠지기 쉽다는 실례다.
     */
    @Test
    @DisplayName("근거 문서 없이 만든 문제도 오답노트에 정상적으로 나온다 — slug가 null인 경우")
    void wrongAnswerListHandlesNullSlug() {
        Problem withoutDocument = createProblem(null);
        submit(withoutDocument, "X");

        List<WrongAnswerItem> items = wrongAnswerService
                .getWrongAnswers(userId, null, PageRequest.of(0, 20)).content();

        assertThat(items).hasSize(1);
        assertThat(slugOf(items, withoutDocument.getId())).isNull();
    }

    /**
     * null slug 하나가 목록 전체를 죽이지 않는지 — 위 테스트가 "혼자 있을 때"를 본다면
     * 이건 "섞여 있을 때"를 본다. 실제 사고가 난 모양이 이쪽이었다(대부분 null인데 하나만 slug 있음).
     */
    @Test
    @DisplayName("slug 있는 문제와 없는 문제가 섞여 있어도 목록이 죽지 않는다")
    void wrongAnswerListMixesNullAndPresentSlugs() {
        String slug = createDocument();
        Problem withDocument = createProblem(slug);
        Problem withoutDocument = createProblem(null);

        submit(withDocument, "X");
        submit(withoutDocument, "X");

        List<WrongAnswerItem> items = wrongAnswerService
                .getWrongAnswers(userId, null, PageRequest.of(0, 20)).content();

        assertThat(items).hasSize(2);
        assertThat(slugOf(items, withDocument.getId())).isEqualTo(slug);
        assertThat(slugOf(items, withoutDocument.getId())).isNull();
    }

    /* ── 도우미 ──────────────────────────────────────────────── */

    private String slugOf(List<WrongAnswerItem> items, Long problemId) {
        return items.stream()
                .filter(i -> i.problemId().equals(problemId))
                .findFirst().orElseThrow()
                .documentSlug();
    }

    private QuizSubmitResponse submit(Problem problem, String answer) {
        return quizService.submit(userId, new QuizSubmitRequest(problem.getId(), answer));
    }

    /** 정식 문서를 만든다 — 관리자 등록 경로를 그대로 써서 실제와 같은 상태를 만든다. */
    private String createDocument() {
        String slug = "doclink-" + UUID.randomUUID().toString().substring(0, 8);
        adminDocumentService.create(new AdminDocumentRequest(
                Domain.NETWORK, "TCP 3-way 핸드셰이크", slug, "# TCP\n\n본문", null, List.of()));
        return slug;
    }

    /** OX 문제 하나 — 정답은 "O". 링크 규칙은 문제 유형과 무관하므로 가장 단순한 것을 쓴다. */
    private Problem createProblem(String documentSlug) {
        return problemRepository.save(Problem.create(
                Domain.NETWORK, Difficulty.BEGINNER, ProblemType.OX, "TCP의 연결 지향 성질",
                "TCP는 연결 지향 프로토콜이다. " + UUID.randomUUID(), "O", "해설", documentSlug));
    }
}
