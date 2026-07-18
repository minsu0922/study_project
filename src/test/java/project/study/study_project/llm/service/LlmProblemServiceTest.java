package project.study.study_project.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemGenerator;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.dto.LlmGenerateRequest;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * LLM 문제 생성 서비스 단위 테스트 — docs/13의 핵심 규칙을 검증한다:
 * 부족 칸 선택, 중복 회피 목록 구성, 규약 위반 항목 스킵, 승인 변환, 상태 전이.
 *
 * <p><b>Claude를 부르지 않는다</b>: {@link ProblemGenerator}가 인터페이스인 이유가 이 테스트다.
 * 실제 API는 돈이 들고 응답이 매번 달라 단정(assert)이 불가능하므로, 정해진 문제를 돌려주는
 * 가짜(FakeGenerator)를 주입해 <b>서비스 로직만</b> 검증한다. Claude 호출 자체의 동작은
 * 수동 실행(관리자 버튼)으로 확인한다 — 외부 API의 실계약 검증을 단위 테스트로 흉내 내지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class LlmProblemServiceTest {

    @Mock
    private GeneratedProblemDraftRepository draftRepository;
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private AdminProblemService adminProblemService;

    private FakeGenerator fakeGenerator;
    private LlmProblemService service;

    /** 정해진 결과를 돌려주고 호출 인자를 기록하는 가짜 생성기 — Mockito mock보다 인자 검증이 읽기 쉽다. */
    static class FakeGenerator implements ProblemGenerator {
        List<GeneratedProblemItem> toReturn = List.of();
        Domain calledDomain;
        Difficulty calledDifficulty;
        ProblemType calledType;
        List<String> calledAvoid;

        @Override
        public List<GeneratedProblemItem> generate(Domain domain, Difficulty difficulty, ProblemType type,
                                                   int count, List<String> avoidQuestions) {
            this.calledDomain = domain;
            this.calledDifficulty = difficulty;
            this.calledType = type;
            this.calledAvoid = avoidQuestions;
            return toReturn;
        }
    }

    @BeforeEach
    void setUp() {
        fakeGenerator = new FakeGenerator();
        // ObjectMapper는 실물 사용 — JSON 직렬화가 이 서비스의 실제 책임이라 가짜로 대체하면 검증이 빈다
        service = new LlmProblemService(fakeGenerator, draftRepository, problemRepository,
                adminProblemService, new ObjectMapper(), "test-model");
        // saveAll은 받은 것을 그대로 돌려준다 — 저장 결과 검증은 인자 캡처로 한다
        lenient().when(draftRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(problemRepository.findQuestionTextsByDomain(any(), any())).thenReturn(List.of());
        lenient().when(draftRepository.findPendingQuestionsByDomain(any())).thenReturn(List.of());
    }

    /** GROUP BY 집계 결과 행 — 인터페이스 프로젝션을 테스트에서 record로 흉내 낸다. */
    private record CountRow(Domain d, Difficulty diff, long c) implements ProblemRepository.DomainDifficultyCount {
        @Override public Domain getDomain() { return d; }
        @Override public Difficulty getDifficulty() { return diff; }
        @Override public long getCnt() { return c; }
    }

    private static GeneratedProblemItem mcItem(String question, int correctIndex) {
        return new GeneratedProblemItem(question, "", "해설입니다.", List.of(
                new GeneratedProblemItem.GeneratedChoice("보기1", correctIndex == 0),
                new GeneratedProblemItem.GeneratedChoice("보기2", correctIndex == 1),
                new GeneratedProblemItem.GeneratedChoice("보기3", correctIndex == 2),
                new GeneratedProblemItem.GeneratedChoice("보기4", correctIndex == 3)));
    }

    @Nested
    @DisplayName("생성 — 부족 칸 선택과 중복 회피")
    class Generate {

        @Test
        @DisplayName("도메인만 지정하면 그 도메인 안에서 문제가 가장 적은 난이도를 고른다(누락 칸은 0으로 본다)")
        void picksScarcestDifficultyWithinFixedDomain() {
            // BACKEND_FRAMEWORK: 초급 5, 중급 2, 고급은 집계에 없음(=0문제) → 고급이 선택되어야 한다
            when(problemRepository.countGroupByDomainAndDifficulty()).thenReturn(List.of(
                    new CountRow(Domain.BACKEND_FRAMEWORK, Difficulty.BEGINNER, 5),
                    new CountRow(Domain.BACKEND_FRAMEWORK, Difficulty.INTERMEDIATE, 2)));
            fakeGenerator.toReturn = List.of(mcItem("Spring Bean 스코프 문제", 0));

            service.generate(new LlmGenerateRequest(Domain.BACKEND_FRAMEWORK, null, null, 1));

            assertThat(fakeGenerator.calledDomain).isEqualTo(Domain.BACKEND_FRAMEWORK);
            assertThat(fakeGenerator.calledDifficulty).isEqualTo(Difficulty.ADVANCED);
            assertThat(fakeGenerator.calledType).isEqualTo(ProblemType.MULTIPLE_CHOICE); // type 미지정 → 객관식
        }

        @Test
        @DisplayName("중복 회피 목록에는 기존 문제와 검수 대기 초안의 질문이 함께 들어간다")
        void avoidListIncludesProblemsAndPendingDrafts() {
            when(problemRepository.findQuestionTextsByDomain(any(), any())).thenReturn(List.of("기존 문제 질문"));
            when(draftRepository.findPendingQuestionsByDomain(Domain.NETWORK)).thenReturn(List.of("대기 초안 질문"));
            fakeGenerator.toReturn = List.of(mcItem("새 문제", 0));

            service.generate(new LlmGenerateRequest(Domain.NETWORK, Difficulty.BEGINNER, null, 1));

            assertThat(fakeGenerator.calledAvoid).containsExactlyInAnyOrder("기존 문제 질문", "대기 초안 질문");
        }

        @Test
        @DisplayName("서술형(ESSAY) 생성 요청은 QUIZ_002로 거부한다")
        void rejectsEssayType() {
            assertThatThrownBy(() -> service.generate(
                    new LlmGenerateRequest(Domain.NETWORK, Difficulty.BEGINNER, ProblemType.ESSAY, 1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.QUIZ_002);
        }
    }

    @Nested
    @DisplayName("생성 — 내용 규약 위반 항목 스킵")
    class SkipInvalid {

        @Test
        @DisplayName("객관식인데 정답 보기가 1개가 아니면 그 항목만 건너뛰고 나머지는 저장한다")
        @SuppressWarnings("unchecked")
        void skipsInvalidMultipleChoiceItem() {
            GeneratedProblemItem invalid = new GeneratedProblemItem("정답 2개짜리", "", "해설", List.of(
                    new GeneratedProblemItem.GeneratedChoice("A", true),
                    new GeneratedProblemItem.GeneratedChoice("B", true)));
            fakeGenerator.toReturn = List.of(mcItem("정상 문제", 1), invalid);

            service.generate(new LlmGenerateRequest(Domain.OS, Difficulty.BEGINNER, null, 2));

            ArgumentCaptor<List<GeneratedProblemDraft>> captor = ArgumentCaptor.forClass(List.class);
            org.mockito.Mockito.verify(draftRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getQuestion()).isEqualTo("정상 문제");
            // 객관식 answer는 빈 문자열로 와도 저장 규칙(docs/01)대로 null이어야 한다
            assertThat(captor.getValue().get(0).getAnswer()).isNull();
            assertThat(captor.getValue().get(0).getStatus()).isEqualTo(DraftStatus.PENDING);
        }

        @Test
        @DisplayName("OX인데 answer가 없으면 건너뛴다")
        @SuppressWarnings("unchecked")
        void skipsOxWithoutAnswer() {
            fakeGenerator.toReturn = List.of(
                    new GeneratedProblemItem("answer 없는 OX", "", "해설", List.of()),
                    new GeneratedProblemItem("정상 OX", "O", "해설", List.of()));

            service.generate(new LlmGenerateRequest(Domain.OS, Difficulty.BEGINNER, ProblemType.OX, 2));

            ArgumentCaptor<List<GeneratedProblemDraft>> captor = ArgumentCaptor.forClass(List.class);
            org.mockito.Mockito.verify(draftRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getQuestion()).isEqualTo("정상 OX");
        }
    }

    @Nested
    @DisplayName("검수 — 승인·거절 상태 전이")
    class Review {

        private GeneratedProblemDraft mcDraft() {
            // 저장 형태 그대로의 초안 — choices JSON은 AdminProblemRequest.ChoiceItem 직렬화 모양
            return GeneratedProblemDraft.pending(
                    Domain.BACKEND_FRAMEWORK, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                    "@Transactional 전파 문제", null, "REQUIRED가 기본값이다.",
                    "[{\"text\":\"REQUIRED\",\"correct\":true},{\"text\":\"REQUIRES_NEW\",\"correct\":false}]",
                    "test-model");
        }

        @Test
        @DisplayName("승인하면 손 등록과 같은 형태(AdminProblemRequest)로 변환해 등록하고, 초안은 APPROVED가 된다")
        void approveConvertsAndRegisters() {
            GeneratedProblemDraft draft = mcDraft();
            when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));
            AdminProblemDetail created = new AdminProblemDetail(99L, Domain.BACKEND_FRAMEWORK,
                    Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                    "@Transactional 전파 문제", null, "REQUIRED가 기본값이다.", LocalDateTime.now(), List.of());
            when(adminProblemService.create(any())).thenReturn(created);

            service.approve(1L);

            // 변환 검증 — 보기 JSON이 ChoiceItem 목록으로 복원되어 넘어가야 한다
            ArgumentCaptor<AdminProblemRequest> captor = ArgumentCaptor.forClass(AdminProblemRequest.class);
            org.mockito.Mockito.verify(adminProblemService).create(captor.capture());
            assertThat(captor.getValue().question()).isEqualTo("@Transactional 전파 문제");
            assertThat(captor.getValue().choices()).hasSize(2);
            assertThat(captor.getValue().choices().get(0).correct()).isTrue();

            // 상태 전이 검증 — 등록된 문제 id가 이력으로 남는다
            assertThat(draft.getStatus()).isEqualTo(DraftStatus.APPROVED);
            assertThat(draft.getApprovedProblemId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("이미 처리된 초안을 다시 승인하면 LLM_002 — 승인 버튼 중복 클릭 방어")
        void approveTwiceFails() {
            GeneratedProblemDraft draft = mcDraft();
            draft.reject("먼저 거절됨");
            when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.approve(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LLM_002);
        }

        @Test
        @DisplayName("거절하면 REJECTED + 사유가 남는다")
        void rejectKeepsReason() {
            GeneratedProblemDraft draft = mcDraft();
            when(draftRepository.findById(2L)).thenReturn(Optional.of(draft));

            service.reject(2L, "보기 3번이 사실과 다름");

            assertThat(draft.getStatus()).isEqualTo(DraftStatus.REJECTED);
            assertThat(draft.getRejectReason()).isEqualTo("보기 3번이 사실과 다름");
        }

        @Test
        @DisplayName("없는 초안이면 LLM_001")
        void draftNotFound() {
            when(draftRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approve(404L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LLM_001);
        }
    }
}
