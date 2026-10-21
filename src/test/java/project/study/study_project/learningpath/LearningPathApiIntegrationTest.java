package project.study.study_project.learningpath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.TestFixtures;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.learningpath.domain.LearningPath;
import project.study.study_project.learningpath.repository.LearningPathRepository;
import project.study.study_project.quiz.domain.Problem;
import project.study.study_project.quiz.dto.QuizSubmitRequest;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.quiz.service.QuizService;
import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로드맵 API를 HTTP로 — 공개 여부, 진도, 단계 세트 순서, 시작·그만두기, 이상한 키. */
@SpringBootTest(properties = "ratelimit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class LearningPathApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private LearningPathRepository pathRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ProblemRepository problemRepository;
    @Autowired
    private QuizService quizService;

    @Test
    @DisplayName("비로그인도 목록·상세·단계 문제를 본다. 진도는 0이다")
    void anonymousCanBrowse() throws Exception {
        Document d = doc();
        problem(d.getSlug(), Difficulty.BEGINNER);
        String key = "custom:" + published(d).getId();

        mockMvc.perform(get("/api/learning-paths")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].key", hasItem(key)));
        mockMvc.perform(get("/api/learning-paths/" + key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.data.summary.solved").value(0))
                .andExpect(jsonPath("$.data.summary.started").value(false))
                .andExpect(jsonPath("$.data.steps[0].documentSlug").value(d.getSlug()))
                .andExpect(jsonPath("$.data.steps[0].total").value(1));
        mockMvc.perform(get("/api/learning-paths/" + key + "/steps/1/problems")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problems.length()").value(1));
    }

    @Test
    @DisplayName("단계 세트는 못 맞힌 문제가 먼저, 그 안에서 쉬운 문제가 먼저다. 받으면 코스가 시작된다")
    void stepSetOrdersUnsolvedFirstAndStartsCourse() throws Exception {
        Document d = doc();
        Problem hard = problem(d.getSlug(), Difficulty.ADVANCED);
        Problem easySolved = problem(d.getSlug(), Difficulty.BEGINNER);
        Problem easy = problem(d.getSlug(), Difficulty.BEGINNER);
        String key = "custom:" + published(d).getId();
        User me = fixtures.user(Role.USER);
        quizService.submit(me.getId(), new QuizSubmitRequest(easySolved.getId(), "O"));

        mockMvc.perform(get("/api/learning-paths/" + key + "/steps/1/problems")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problems[0].id").value(easy.getId()))
                .andExpect(jsonPath("$.data.problems[1].id").value(hard.getId()))
                .andExpect(jsonPath("$.data.problems[2].id").value(easySolved.getId()));

        mockMvc.perform(get("/api/me/learning-paths/current").header(HttpHeaders.AUTHORIZATION, fixtures.bearer(me)))
                .andExpect(jsonPath("$.data.key").value(key))
                .andExpect(jsonPath("$.data.solved").value(1))
                .andExpect(jsonPath("$.data.nextSeq").value(1));
    }

    @Test
    @DisplayName("시작하면 목록 맨 앞에 시작한 코스로 오고, 그만두면 빠진다")
    void startAndStop() throws Exception {
        Document d = doc();
        problem(d.getSlug(), Difficulty.BEGINNER);
        String key = "custom:" + published(d).getId();
        User me = fixtures.user(Role.USER);
        String auth = fixtures.bearer(me);

        mockMvc.perform(post("/api/me/learning-paths/" + key).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/learning-paths").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.data[0].key").value(key))
                .andExpect(jsonPath("$.data[0].started").value(true));

        mockMvc.perform(delete("/api/me/learning-paths/" + key).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me/learning-paths/current").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("이상한 키·없는 코스·없는 단계는 404, 시작은 로그인이 필요하다")
    void rejectsBadKeys() throws Exception {
        Document d = doc();
        String key = "custom:" + published(d).getId();
        mockMvc.perform(get("/api/learning-paths/nonsense")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/learning-paths/domain:NO_SUCH")).andExpect(status().isNotFound());
        // 키 검사가 분야 코드 규칙(두 글자 이상)보다 느슨해서 500이 나던 자리(2026-10-21)
        mockMvc.perform(get("/api/learning-paths/domain:A")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/learning-paths/domain:A/steps/1/problems")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/learning-paths/" + key + "/steps/9/problems")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/me/learning-paths/" + key)).andExpect(status().isUnauthorized());
    }

    private LearningPath published(Document... docs) {
        LearningPath p = LearningPath.create("코스 " + UUID.randomUUID(), "설명", 0);
        p.addSteps(List.of(docs));
        p.publish();
        return pathRepository.save(p);
    }

    private Problem problem(String slug, Difficulty difficulty) {
        return problemRepository.save(Problem.create(TestDomains.NETWORK, difficulty, ProblemType.OX,
                null, "지문 " + UUID.randomUUID(), "O", "해설", slug));
    }

    private Document doc() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        return documentRepository.save(Document.create(TestDomains.NETWORK, "문서 " + key, "doc-" + key, "본문", null, Set.of()));
    }
}
