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
import project.study.study_project.llm.client.RejectionNote;
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
        List<RejectionNote> calledRejectionNotes;
        /** 2단계에서 추가된 근거 문서 인자. 관리자 즉시 생성 경로는 항상 null이어야 한다. */
        project.study.study_project.llm.client.SourceDocument calledSourceDocument;

        @Override
        public List<GeneratedProblemItem> generate(Domain domain, Difficulty difficulty, ProblemType type,
                                                   int count, List<String> avoidQuestions,
                                                   List<RejectionNote> rejectionNotes,
                                                   project.study.study_project.llm.client.SourceDocument sourceDocument) {
            this.calledDomain = domain;
            this.calledDifficulty = difficulty;
            this.calledType = type;
            this.calledAvoid = avoidQuestions;
            this.calledRejectionNotes = rejectionNotes;
            this.calledSourceDocument = sourceDocument;
            return toReturn;
        }
    }

    /** 기본 후보 도메인 — 대부분의 테스트는 도메인을 명시하므로 값 자체는 중요하지 않다. */
    private static final List<Domain> DEFAULT_BATCH_DOMAINS =
            List.of(Domain.NETWORK, Domain.OS, Domain.DATABASE, Domain.BACKEND_FRAMEWORK);

    @BeforeEach
    void setUp() {
        fakeGenerator = new FakeGenerator();
        service = newService(DEFAULT_BATCH_DOMAINS);
        // saveAll은 받은 것을 그대로 돌려준다 — 저장 결과 검증은 인자 캡처로 한다
        lenient().when(draftRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(problemRepository.findQuestionTextsByDomain(any(), any())).thenReturn(List.of());
        lenient().when(draftRepository.findPendingQuestionsByDomain(any())).thenReturn(List.of());
        // 대기 초안 집계는 기본 "없음" — 필요한 테스트만 덮어쓴다
        lenient().when(draftRepository.countPendingGroupByDomainAndDifficulty()).thenReturn(List.of());
        // 거절 사례도 기본 "없음" — 되먹이기를 검증하는 테스트만 덮어쓴다
        lenient().when(draftRepository.findRecentRejectionNotes(any())).thenReturn(List.of());
    }

    /** 후보 도메인만 바꿔 서비스를 다시 만드는 헬퍼 — batch-domains 동작 검증용. */
    private LlmProblemService newService(List<Domain> batchDomains) {
        // ObjectMapper는 실물 사용 — JSON 직렬화가 이 서비스의 실제 책임이라 가짜로 대체하면 검증이 빈다
        return new LlmProblemService(fakeGenerator, draftRepository, problemRepository,
                adminProblemService, new ObjectMapper(), event -> { }, "test-model", batchDomains);
    }

    /** 거절 사례 조회 결과 행 — 인터페이스 프로젝션을 테스트에서 record로 흉내 낸다. */
    private record NoteRow(String q, String r) implements GeneratedProblemDraftRepository.RejectionNoteView {
        @Override public String getQuestion() { return q; }
        @Override public String getReason() { return r; }
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
        @DisplayName("과거 거절 사례가 생성기로 전달된다 — 검수 결과를 다음 생성에 되먹인다")
        void rejectionNotesAreFedBackToGenerator() {
            when(draftRepository.findRecentRejectionNotes(any())).thenReturn(List.of(
                    new NoteRow("다음 중 옳지 않은 것은?", "부정형 문제라 검수 비용이 크다"),
                    new NoteRow("Redis 기본 포트는?", "단순 암기 확인이라 원리를 묻지 않는다")));
            fakeGenerator.toReturn = List.of(mcItem("새 문제", 0));

            service.generate(new LlmGenerateRequest(Domain.NETWORK, Difficulty.BEGINNER, null, 1));

            assertThat(fakeGenerator.calledRejectionNotes)
                    .extracting(RejectionNote::reason)
                    .containsExactly("부정형 문제라 검수 비용이 크다", "단순 암기 확인이라 원리를 묻지 않는다");
            // 사유만 넘기면 모델이 무엇을 두고 한 말인지 모른다 — 지문도 함께 가야 한다
            assertThat(fakeGenerator.calledRejectionNotes)
                    .extracting(RejectionNote::question)
                    .containsExactly("다음 중 옳지 않은 것은?", "Redis 기본 포트는?");
        }

        @Test
        @DisplayName("거절 이력이 없으면 빈 목록이 전달된다 — 되먹임이 없다고 생성이 막히면 안 된다")
        void emptyRejectionNotesDoNotBlockGeneration() {
            fakeGenerator.toReturn = List.of(mcItem("새 문제", 0));

            service.generate(new LlmGenerateRequest(Domain.NETWORK, Difficulty.BEGINNER, null, 1));

            assertThat(fakeGenerator.calledRejectionNotes).isEmpty();
        }

        @Test
        @DisplayName("검수 대기 초안도 칸 개수에 합산된다 — 검수를 미뤄도 같은 칸만 반복해 뽑지 않는다")
        void pendingDraftsCountTowardCellSize() {
            // NETWORK 정식 문제: 초급 0, 중급 2, 고급 2. 초안만 보면 초급이 제일 비어 보인다.
            when(problemRepository.countGroupByDomainAndDifficulty()).thenReturn(List.of(
                    new CountRow(Domain.NETWORK, Difficulty.INTERMEDIATE, 2),
                    new CountRow(Domain.NETWORK, Difficulty.ADVANCED, 2)));
            // 그런데 초급에는 이미 검수 대기 초안 5건이 쌓여 있다 → 합산하면 초급이 가장 많은 칸이 된다
            when(draftRepository.countPendingGroupByDomainAndDifficulty()).thenReturn(List.of(
                    new CountRow(Domain.NETWORK, Difficulty.BEGINNER, 5)));
            fakeGenerator.toReturn = List.of(mcItem("네트워크 문제", 0));

            service.generate(new LlmGenerateRequest(Domain.NETWORK, null, null, 1));

            // 합산하지 않으면 BEGINNER(0건)가 뽑힌다 — 이 단정이 회귀를 막는다
            assertThat(fakeGenerator.calledDifficulty).isEqualTo(Difficulty.INTERMEDIATE);
        }

        @Test
        @DisplayName("도메인을 지정하지 않으면 batch-domains 후보 안에서만 고른다 — 후보 밖이 더 비어 있어도 뽑지 않는다")
        void autoPickStaysWithinBatchDomains() {
            service = newService(List.of(Domain.NETWORK, Domain.OS));
            // 후보인 NETWORK·OS는 문제가 꽉 차 있고, 후보가 아닌 FRONTEND_CS는 집계에 없다(=0건)
            when(problemRepository.countGroupByDomainAndDifficulty()).thenReturn(List.of(
                    new CountRow(Domain.NETWORK, Difficulty.BEGINNER, 10),
                    new CountRow(Domain.NETWORK, Difficulty.INTERMEDIATE, 10),
                    new CountRow(Domain.NETWORK, Difficulty.ADVANCED, 10),
                    new CountRow(Domain.OS, Difficulty.BEGINNER, 7),
                    new CountRow(Domain.OS, Difficulty.INTERMEDIATE, 10),
                    new CountRow(Domain.OS, Difficulty.ADVANCED, 10)));
            fakeGenerator.toReturn = List.of(mcItem("OS 문제", 0));

            service.generate(new LlmGenerateRequest(null, null, null, 1));

            // 가장 비어 있는 칸은 FRONTEND_CS(0건)지만 후보가 아니므로, 후보 중 최소인 OS×초급이 뽑힌다
            assertThat(fakeGenerator.calledDomain).isEqualTo(Domain.OS);
            assertThat(fakeGenerator.calledDifficulty).isEqualTo(Difficulty.BEGINNER);
        }

        @Test
        @DisplayName("도메인을 직접 지정하면 batch-domains 후보 밖이어도 생성한다 — 제한은 자동 선택에만 적용")
        void explicitDomainIgnoresBatchDomainFilter() {
            service = newService(List.of(Domain.NETWORK, Domain.OS)); // FRONTEND_CS는 후보가 아님
            fakeGenerator.toReturn = List.of(mcItem("브라우저 렌더링 문제", 0));

            service.generate(new LlmGenerateRequest(Domain.FRONTEND_CS, Difficulty.BEGINNER, null, 1));

            assertThat(fakeGenerator.calledDomain).isEqualTo(Domain.FRONTEND_CS);
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
                    "[{\"text\":\"REQUIRED\",\"correct\":true},{\"text\":\"REQUIRES_NEW\",\"correct\":false}]", // choicesJson
                    "test-model", "spring-transactional-propagation"); // model, documentSlug
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

        /* ── 복구(실수 거절 취소) ─────────────────────────────── */

        @Test
        @DisplayName("거절한 초안을 되돌리면 검수 대기로 돌아간다")
        void restoreRejectedDraft() {
            GeneratedProblemDraft draft = mcDraft();
            draft.reject("실수로 거절");
            when(draftRepository.findById(3L)).thenReturn(Optional.of(draft));

            service.restore(3L);

            assertThat(draft.getStatus()).isEqualTo(DraftStatus.PENDING);
            assertThat(draft.getReviewedAt()).as("미처리 상태로 돌아간다").isNull();
        }

        /**
         * 거절 사유를 남기는 것이 <b>복구 여부를 구분하는 유일한 단서</b>다.
         * status=PENDING인데 rejectReason이 있으면 "한 번 거절됐다가 복구된 초안"이라는 뜻 —
         * 컬럼을 새로 만들지 않고 이 조합으로 표현하기로 했으므로 지우면 그 정보가 사라진다.
         */
        @Test
        @DisplayName("복구해도 거절 사유는 남는다 — 왜 거절했었는지가 다음 판단의 재료다")
        void restoreKeepsRejectReason() {
            GeneratedProblemDraft draft = mcDraft();
            draft.reject("보기 3번이 사실과 다름");
            when(draftRepository.findById(4L)).thenReturn(Optional.of(draft));

            service.restore(4L);

            assertThat(draft.getRejectReason()).isEqualTo("보기 3번이 사실과 다름");
        }

        /**
         * <b>이 테스트가 복구 기능의 안전선이다.</b> 승인된 초안을 되돌릴 수 있으면 승인 버튼을
         * 또 누를 수 있고, 그러면 똑같은 문제가 두 개 등록된다 — 문제에는 문서의 slug 같은
         * 중복 방지 장치가 없어서 아무도 막아 주지 않는다.
         */
        @Test
        @DisplayName("승인된 초안은 되돌릴 수 없다 — 되돌리면 같은 문제가 두 번 등록된다")
        void cannotRestoreApprovedDraft() {
            GeneratedProblemDraft draft = mcDraft();
            draft.approve(42L);
            when(draftRepository.findById(5L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.restore(5L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LLM_002);

            assertThat(draft.getStatus()).as("실패했으면 상태가 그대로여야 한다")
                    .isEqualTo(DraftStatus.APPROVED);
        }

        @Test
        @DisplayName("검수 대기 중인 초안에 복구를 걸어도 막힌다 — 되돌릴 것이 없다")
        void cannotRestorePendingDraft() {
            when(draftRepository.findById(6L)).thenReturn(Optional.of(mcDraft()));

            assertThatThrownBy(() -> service.restore(6L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LLM_002);
        }

        @Test
        @DisplayName("복구한 뒤에는 다시 승인할 수 있다 — 복구의 목적 그 자체")
        void canApproveAfterRestore() {
            GeneratedProblemDraft draft = mcDraft();
            draft.reject("실수로 거절");
            when(draftRepository.findById(7L)).thenReturn(Optional.of(draft));
            when(adminProblemService.create(any())).thenReturn(new AdminProblemDetail(
                    99L, Domain.BACKEND_FRAMEWORK, Difficulty.INTERMEDIATE, ProblemType.MULTIPLE_CHOICE,
                    "@Transactional 전파 문제", null, "해설", LocalDateTime.now(), List.of()));

            service.restore(7L);
            service.approve(7L);

            assertThat(draft.getStatus()).isEqualTo(DraftStatus.APPROVED);
            assertThat(draft.getApprovedProblemId()).isEqualTo(99L);
        }
    }
}
