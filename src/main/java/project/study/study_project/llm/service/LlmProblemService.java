package project.study.study_project.llm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.ProblemGenerator;
import project.study.study_project.llm.client.RejectionNote;
import project.study.study_project.llm.client.SourceDocument;
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.dto.LlmDocumentGenerateRequest;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.dto.LlmGenerateRequest;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.support.ProblemItemRule;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 문제 생성·검수 서비스 — 문서 13, ADR-0006.
 *
 * <p>전체 흐름: 생성(부족 칸 선택 → Claude 호출 → PENDING 초안 저장) →
 * 검수(관리자 승인 → 정식 문제 등록 / 거절 → 이력만 남김).
 *
 * <p>가장 중요한 결정 — <b>승인은 {@link AdminProblemService#create}를 재사용</b>한다:
 * 관리자가 손으로 등록하는 문제와 AI가 만든 문제가 같은 문(같은 검증 규칙)을 통과해야
 * "AI 문제라서 규칙이 어긋난 채 들어왔다"는 경우가 구조적으로 불가능해진다.
 * 검증 로직을 복사하면 언젠가 한쪽만 고쳐져 어긋난다(단일 경로 원칙).
 */
@Slf4j
@Service
public class LlmProblemService {

    /** 중복 회피 목록에 넣을 기존 문제 수 상한 — 프롬프트 토큰 비용과의 균형점. */
    private static final int AVOID_LIST_SIZE = 50;

    /**
     * 프롬프트에 되먹일 거절 사례 수 상한(docs/14). 회피 목록(50)보다 적게 잡은 이유:
     * 회피 목록은 "빠지면 중복이 난다"라 많을수록 좋지만, 거절 사례는 <b>패턴을 보여주는 것</b>이
     * 목적이라 20건이면 반복되는 실수가 충분히 드러난다. 더 넣어 봐야 토큰만 늘고
     * 오래된 사례가 이미 고쳐진 프롬프트를 다시 지적하는 잡음이 된다.
     */
    private static final int REJECTION_NOTE_SIZE = 20;

    private final ProblemGenerator problemGenerator;
    private final GeneratedProblemDraftRepository draftRepository;
    private final ProblemRepository problemRepository;
    private final AdminProblemService adminProblemService;
    private final ObjectMapper objectMapper;

    /** 검수가 끝났음을 알린다 — 스냅샷 내보내기가 커밋 뒤에 듣는다({@link ReviewCompleted}). */
    private final ApplicationEventPublisher events;
    private final String model;
    /** 배치가 도메인을 "알아서 고를" 때의 후보 — 비어 있으면 전체를 후보로 본다(설정 누락 시 기능 정지 방지). */
    private final List<Domain> batchDomains;

    public LlmProblemService(ProblemGenerator problemGenerator,
                             GeneratedProblemDraftRepository draftRepository,
                             ProblemRepository problemRepository,
                             AdminProblemService adminProblemService,
                             ObjectMapper objectMapper,
                             ApplicationEventPublisher events,
                             @org.springframework.beans.factory.annotation.Value("${llm.generation.model:claude-opus-5}") String model,
                             // 기본값에 8개를 그대로 적어 둔다: 빈 문자열을 기본값으로 두면 Spring이 이를
                             // "빈 문자열 원소 1개"로 변환하려다 enum 변환에 실패할 수 있어서다.
                             @org.springframework.beans.factory.annotation.Value(
                                     "${llm.generation.batch-domains:NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,LANGUAGE_RUNTIME,BACKEND_FRAMEWORK}")
                             List<Domain> batchDomains) {
        this.problemGenerator = problemGenerator;
        this.draftRepository = draftRepository;
        this.problemRepository = problemRepository;
        this.adminProblemService = adminProblemService;
        this.objectMapper = objectMapper;
        this.events = events;
        this.model = model;
        this.batchDomains = batchDomains == null || batchDomains.isEmpty()
                ? List.of(Domain.values()) : List.copyOf(batchDomains);
    }

    /* ── 생성 ─────────────────────────────────────────────── */

    /**
     * 문제를 생성해 PENDING 초안으로 저장한다.
     *
     * <p>트랜잭션을 걸지 않은 이유: Claude 호출은 수십 초까지 걸릴 수 있는 외부 I/O라
     * 그동안 DB 커넥션을 물고 있으면 커넥션 풀이 말라붙는다(외부 호출은 트랜잭션 밖 원칙).
     * 저장은 호출이 끝난 뒤 짧게 — saveAll의 기본 트랜잭션이면 충분하다.
     */
    public List<LlmDraftResponse> generate(LlmGenerateRequest request) {
        // ESSAY 방어 — 자동채점 불가 유형은 생성 자체를 거부(등록 화면과 같은 규칙)
        ProblemType type = request.type() != null ? request.type() : ProblemType.MULTIPLE_CHOICE;
        if (type == ProblemType.ESSAY) {
            throw new BusinessException(ErrorCode.QUIZ_002, "서술형(ESSAY)은 자동채점 미지원이라 생성할 수 없습니다.");
        }

        // 도메인·난이도를 지정하지 않으면 "가장 부족한 칸"을 고른다(빈 칸 채우기 전략, docs/13)
        Domain domain = request.domain();
        Difficulty difficulty = request.difficulty();
        if (domain == null || difficulty == null) {
            ScarceCell cell = pickScarcestCell(domain, difficulty);
            domain = cell.domain();
            difficulty = cell.difficulty();
        }

        List<String> avoid = buildAvoidList(domain);
        // 관리자 수동 생성은 DB를 직접 볼 수 있으므로 거절 사례를 실시간으로 읽는다 —
        // 방금 거절한 문제가 바로 다음 생성에 반영된다. (배치는 DB가 없어 스냅샷 파일을 쓴다:
        // RejectionNotesExporter → generated/_rejection-notes.json → DraftGeneratorCli)
        List<RejectionNote> rejectionNotes = findRecentRejectionNotes();
        List<GeneratedProblemItem> items =
                problemGenerator.generate(domain, difficulty, type, request.count(), avoid, rejectionNotes);

        // 이 경로(칸 자동 선택 즉시 생성)는 근거 문서 없이 만든다(null).
        // 문서를 근거로 만드는 경로는 둘로 갈렸다: 4일 주기 배치와 아래 generateFromUpload.
        return saveDrafts(domain, difficulty, type, items, model, null).stream().map(this::toResponse).toList();
    }

    /**
     * <b>관리자가 올린 문서</b>를 근거로 문제를 생성해 PENDING 초안으로 저장한다 — 2026-08-18 신설.
     *
     * <p>전에는 이 자리에 "화면에서 문서를 골라 문제를 뽑는 기능은 지금 필요가 없다 — YAGNI"라고
     * 적혀 있었다. 필요가 생겨서 만든다. 다만 <b>새 경로를 통째로 만들지 않았다</b> —
     * 생성은 {@code problemGenerator}의 기존 오버로드가, 저장·검증은 {@link #saveDrafts}가,
     * 검수·승인·거절은 기존 화면이 그대로 한다. 실제로 새로 쓴 것은 이 메서드 열 줄과
     * 프롬프트의 절 지목 분기 하나뿐이다.
     *
     * <p><b>분야·난이도를 자동으로 고르지 않는다.</b> {@link #generate}는 비어 있으면 "가장 부족한
     * 칸"을 채우는데, 그 규칙을 여기 쓰면 올린 문서와 무관한 분야가 붙는다. 요청 DTO가
     * {@code @NotNull}로 막고 있고, 여기서 다시 고르지 않는 것으로 그 결정을 존중한다.
     *
     * <p><b>{@code documentSlug}는 {@code null}이다.</b> 이 값은 학습자 화면의 "개념 문서 읽기"
     * 링크에 쓰이는데, 올린 파일은 서비스에 등록된 문서가 아니라 갈 곳이 없다. 서버가 실재를
     * 확인해 걸러 주긴 하지만(3단계), 애초에 넣지 않는 쪽이 맞다.
     * <b>알려진 부작용</b>: 해설 끝의 "(문서의 ○○ 절을 다시 읽어 보라)"가 학습자에게는 갈 곳 없는
     * 문장이 된다. 프롬프트에서 그 지시를 빼면 배치 문제의 해설까지 나빠지므로 그대로 두고,
     * 검수 단계에서 사람이 지우는 쪽을 택했다.
     *
     * <p>{@link #generate}와 마찬가지로 트랜잭션을 걸지 않는다 — Claude 호출이 수십 초라
     * 그동안 DB 커넥션을 물고 있으면 커넥션 풀이 마른다.
     */
    public List<LlmDraftResponse> generateFromUpload(LlmDocumentGenerateRequest request, SourceDocument document) {
        ProblemType type = request.type() != null ? request.type() : ProblemType.MULTIPLE_CHOICE;
        if (type == ProblemType.ESSAY) {
            throw new BusinessException(ErrorCode.QUIZ_002, "서술형(ESSAY)은 자동채점 미지원이라 생성할 수 없습니다.");
        }

        Domain domain = request.domain();
        Difficulty difficulty = request.difficulty();

        // 중복 회피·거절 사례는 기존 경로와 똑같이 싣는다. 근거 문서가 다르다고 해서
        // "이미 있는 문제와 겹쳐도 된다"가 되는 것은 아니다 — 학습자에게는 같은 문제 목록이다.
        List<String> avoid = buildAvoidList(domain);
        List<RejectionNote> rejectionNotes = findRecentRejectionNotes();

        List<GeneratedProblemItem> items = problemGenerator.generate(
                domain, difficulty, type, request.count(), avoid, rejectionNotes, document);

        return saveDrafts(domain, difficulty, type, items, model, null).stream().map(this::toResponse).toList();
    }

    /**
     * 생성 항목들을 검증해 PENDING 초안으로 저장한다 — <b>관리자 버튼과 배치 파일 흡수가 공유하는 유일한 입구</b>.
     *
     * <p>이 메서드를 따로 뺀 이유(docs/14): 일일 배치가 GitHub Actions로 옮겨가면서 초안이 들어오는
     * 경로가 둘이 됐다 — ① 관리자가 버튼을 눌러 즉시 생성, ② 저장소의 JSON 파일을 기동 시 흡수.
     * 두 경로가 각자 검증하면 언젠가 한쪽만 규칙이 바뀌어 <b>파일로 들어온 문제만 규약을 어긴 채</b>
     * 검수함에 쌓인다. 승인 경로에서 AdminProblemService.create를 재사용한 것과 같은 원칙이다.
     *
     * <p>형식은 구조화 출력 스키마가 보장하지만 "내용 규약"(객관식 정답 정확히 1개 등)은 모델이
     * 어길 수 있다 — 어긴 항목은 배치 전체를 실패시키지 않고 건너뛰며 로그만 남긴다(부분 성공 허용).
     * 5문제 중 1개가 이상하다고 나머지 4개를 버리는 것은 손해다.
     *
     * @param model        초안에 기록할 모델 ID. 파일 흡수 시에는 <b>생성 당시</b>의 모델을 넘긴다 —
     *                     현재 설정값을 쓰면 모델을 교체한 뒤 흡수한 옛 파일이 새 모델 이름으로 기록돼
     *                     "모델별 승인율 비교"라는 model 컬럼의 존재 이유가 무너진다.
     * @param documentSlug 근거로 삼은 개념 문서의 slug(2단계). 문서 없이 만든 경우 {@code null}.
     *                     여기서 slug의 실재 여부를 확인하지 않는다 — 근거 문서가 아직 검수
     *                     대기라 {@code document} 테이블에 없을 수 있고, 그건 정상 상황이다(V9 주석)
     */
    public List<GeneratedProblemDraft> saveDrafts(Domain domain, Difficulty difficulty, ProblemType type,
                                                  List<GeneratedProblemItem> items, String model,
                                                  String documentSlug) {
        List<GeneratedProblemDraft> drafts = new ArrayList<>();
        for (GeneratedProblemItem item : items) {
            toDraft(item, domain, difficulty, type, model, documentSlug).ifPresent(drafts::add);
        }
        return draftRepository.saveAll(drafts);
    }

    /**
     * 가장 부족한 도메인×난이도 칸 선택. 한쪽만 지정된 경우(예: 도메인만 골랐음)는
     * 그 축을 고정하고 나머지 축에서만 최소를 찾는다.
     *
     * <p><b>정식 문제 + 검수 대기 초안을 합산</b>한다. 정식 문제만 세면 검수를 미루는 동안
     * 그 칸의 수가 늘지 않아 매일 같은 칸만 뽑히기 때문이다
     * (자세한 배경은 {@link GeneratedProblemDraftRepository#countPendingGroupByDomainAndDifficulty}).
     * 초안은 "아직 문제가 아니지만 이미 그 칸을 채우려고 만들어 둔 재고"라, 재고까지 세야
     * 다음 칸으로 넘어간다.
     *
     * <p>도메인 축을 지정하지 않은 경우 후보는 {@code llm.generation.batch-domains}로 제한된다
     * — 배치가 관심 밖 도메인을 채우는 데 예산을 쓰지 않게 하려는 것. 반대로 도메인을 명시하면
     * (관리자 화면에서 직접 고른 경우) 목록 밖이어도 그대로 생성한다.
     */
    private ScarceCell pickScarcestCell(Domain fixedDomain, Difficulty fixedDifficulty) {
        // 집계 결과를 맵으로 — 문제가 0개인 칸은 GROUP BY 결과에 아예 없으므로 getOrDefault(0)로 보정
        Map<Domain, Map<Difficulty, Long>> counts = new EnumMap<>(Domain.class);
        accumulate(counts, problemRepository.countGroupByDomainAndDifficulty());
        accumulate(counts, draftRepository.countPendingGroupByDomainAndDifficulty());

        // 도메인을 명시했으면 그 하나만, 아니면 설정된 후보 목록에서 고른다
        List<Domain> domainCandidates = fixedDomain != null ? List.of(fixedDomain) : batchDomains;

        Domain bestDomain = null;
        Difficulty bestDifficulty = null;
        long min = Long.MAX_VALUE;
        for (Domain d : domainCandidates) {
            for (Difficulty diff : fixedDifficulty != null ? new Difficulty[]{fixedDifficulty} : Difficulty.values()) {
                long cnt = counts.getOrDefault(d, Map.of()).getOrDefault(diff, 0L);
                if (cnt < min) {
                    min = cnt;
                    bestDomain = d;
                    bestDifficulty = diff;
                }
            }
        }
        log.info("LLM 생성 대상 칸 선택: {}×{} (정식+대기 {}건, 후보 도메인 {}개)",
                bestDomain, bestDifficulty, min, domainCandidates.size());
        return new ScarceCell(bestDomain, bestDifficulty);
    }

    /** 집계 행들을 도메인×난이도 맵에 더한다(merge) — 두 저장소의 결과를 같은 맵에 합치기 위한 것. */
    private void accumulate(Map<Domain, Map<Difficulty, Long>> counts,
                            List<ProblemRepository.DomainDifficultyCount> rows) {
        rows.forEach(row -> counts
                .computeIfAbsent(row.getDomain(), d -> new EnumMap<>(Difficulty.class))
                .merge(row.getDifficulty(), row.getCnt(), Long::sum));
    }

    private record ScarceCell(Domain domain, Difficulty difficulty) {
    }

    /**
     * 최근 거절 사례를 프롬프트용 형태로 읽는다 — 검수 결과를 다음 생성에 되먹이는 통로(docs/14).
     *
     * <p><b>공개(public)인 이유</b>: {@code RejectionNotesExporter}가 같은 목록을 스냅샷 파일로
     * 내보낸다. 조회 조건(거절 + 사유 있음 + 최신순 + 20건)이 두 곳에 따로 적히면 언젠가
     * 어긋나서 <b>로컬 생성과 배치 생성이 서로 다른 사례를 보게 된다</b> — 같은 메서드를 쓰게 한다.
     *
     * <p>{@code @Transactional}을 붙이지 않은 이유: 단일 조회라 Spring Data 저장소 메서드가
     * 자체적으로 걸어 주는 읽기 전용 트랜잭션이면 충분하다. 게다가 이 메서드는 같은 클래스의
     * {@link #generate}가 호출하는데, 자기 클래스 메서드 호출은 프록시를 거치지 않아
     * 애너테이션이 <b>조용히 무시된다</b> — 효과 없는 애너테이션은 붙이지 않는 편이 정직하다.
     */
    public List<RejectionNote> findRecentRejectionNotes() {
        return draftRepository.findRecentRejectionNotes(PageRequest.of(0, REJECTION_NOTE_SIZE)).stream()
                .map(v -> new RejectionNote(v.getQuestion(), v.getReason()))
                .toList();
    }

    /** 중복 회피 목록 — 정식 문제(최신 50) + 아직 검수 안 된 같은 도메인 초안. */
    private List<String> buildAvoidList(Domain domain) {
        List<String> avoid = new ArrayList<>(
                problemRepository.findQuestionTextsByDomain(domain, PageRequest.of(0, AVOID_LIST_SIZE)));
        avoid.addAll(draftRepository.findPendingQuestionsByDomain(domain));
        return avoid;
    }

    /**
     * 생성 항목 → 초안 엔티티. 내용 규약 위반은 Optional.empty()로 건너뛴다.
     *
     * <p><b>판정을 {@link ProblemItemRule}에 맡긴 이유</b>: 생성 배치({@code DraftGeneratorCli})가
     * "5개 요청했는데 2개만 쓸 만하다"를 경고하려면 <b>흡수와 같은 자</b>로 재야 한다.
     * 기준이 갈라지면 배치는 "다 멀쩡하다"고 하고 흡수는 절반을 버리는, 서로 다른 말을 하는
     * 상태가 된다 — 그러면 경고를 믿을 수 없어지고 결국 무시하게 된다.
     */
    private java.util.Optional<GeneratedProblemDraft> toDraft(GeneratedProblemItem item,
                                                              Domain domain, Difficulty difficulty, ProblemType type,
                                                              String model, String documentSlug) {
        String defect = ProblemItemRule.defectOf(item, type);
        if (defect != null) {
            log.warn("생성 항목 건너뜀: {} — {}", defect, ProblemItemRule.snippet(item));
            return java.util.Optional.empty();
        }

        // 객관식 answer는 저장 규칙상 null이다 — 정답은 보기 쪽에 있다(docs/01).
        // 그 외 유형은 스키마상 빈 문자열로 오는 "값 없음"을 여기서 null로 정규화한다.
        boolean multipleChoice = type == ProblemType.MULTIPLE_CHOICE;
        String answer = multipleChoice ? null : trimToNull(item.answer());
        String choicesJson = multipleChoice ? writeChoicesJson(item.choices()) : null;

        return java.util.Optional.of(GeneratedProblemDraft.pending(
                domain, difficulty, type, trimToNull(item.title()), trimToNull(item.question()), answer,
                trimToNull(item.explanation()), choicesJson, model, trimToNull(documentSlug)));
    }

    /* ── 검수(목록·승인·거절) ─────────────────────────────── */

    @Transactional(readOnly = true)
    public PageResponse<LlmDraftResponse> getDrafts(DraftStatus status, Pageable pageable) {
        DraftStatus target = status != null ? status : DraftStatus.PENDING;
        return PageResponse.from(
                draftRepository.findByStatusOrderByCreatedAtAsc(target, pageable).map(this::toResponse));
    }

    /** 관리자 대시보드 배지용 — 검수 대기 건수. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return draftRepository.countByStatus(DraftStatus.PENDING);
    }

    /**
     * 승인 — 초안을 정식 문제로 등록한다.
     * AdminProblemService.create가 타입별 규칙(QUIZ_004)을 다시 검증하므로,
     * 저장 시점 정규화를 통과했더라도 규칙에 어긋난 초안은 여기서 최종 차단된다.
     * 검증 실패 예외가 그대로 전파되어 트랜잭션이 롤백되므로 초안 상태도 PENDING으로 남는다
     * (승인 절반만 성공하는 어중간한 상태가 없다).
     */
    @Transactional
    public AdminProblemDetail approve(Long draftId) {
        GeneratedProblemDraft draft = findDraft(draftId);
        // 처리 여부를 등록 "전"에 검사한다 — 등록부터 하면 중복 승인 요청이 문제를 이중 등록한
        // 뒤에야 실패한다(같은 트랜잭션이라 롤백은 되지만, 검사가 앞서는 쪽이 명확하다).
        // 이 순서 결함은 단위 테스트(approveTwiceFails)가 잡아냈다.
        if (draft.getStatus() != DraftStatus.PENDING) {
            throw new BusinessException(ErrorCode.LLM_002);
        }
        AdminProblemDetail created = adminProblemService.create(toAdminRequest(draft));
        draft.approve(created.id()); // 엔티티도 같은 규칙을 방어(이중 안전장치)
        events.publishEvent(ReviewCompleted.problem());
        return created;
    }

    @Transactional
    public void reject(Long draftId, String reason) {
        findDraft(draftId).reject(trimToNull(reason));
        events.publishEvent(ReviewCompleted.problem());
    }

    /**
     * 복구 — 거절한 초안을 검수 대기로 되돌린다(실수 거절 취소용).
     *
     * <p>상태 전이 규칙과 그 근거는 전부 엔티티에 있다({@link GeneratedProblemDraft#restore()}).
     * 서비스가 여기서 다시 검사하지 않는 이유: 규칙이 두 곳에 있으면 언젠가 한쪽만 바뀐다.
     * 승인 쪽은 "등록 전에 상태를 본다"는 <b>순서</b> 때문에 서비스에도 검사가 있지만,
     * 복구는 다른 것을 만들지 않으므로 순서 문제가 없어 엔티티에만 둔다.
     */
    @Transactional
    public void restore(Long draftId) {
        GeneratedProblemDraft draft = findDraft(draftId);
        draft.restore();
        log.info("문제 초안 복구: #{} — 검수 대기로 되돌림", draft.getId());
        events.publishEvent(ReviewCompleted.problem());
    }

    private GeneratedProblemDraft findDraft(Long id) {
        return draftRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.LLM_001));
    }

    /** 초안 → 관리자 등록 요청. 손 등록과 완전히 같은 형태로 변환해 같은 검증을 태운다. */
    private AdminProblemRequest toAdminRequest(GeneratedProblemDraft draft) {
        return new AdminProblemRequest(
                draft.getDomain(), draft.getDifficulty(), draft.getType(), draft.getTitle(),
                draft.getQuestion(), draft.getAnswer(), draft.getExplanation(),
                readChoices(draft.getChoicesJson()), draft.getDocumentSlug());
    }

    /* ── JSON 직렬화 도우미 ───────────────────────────────── */

    /**
     * 보기 JSON의 저장 형태는 {@link AdminProblemRequest.ChoiceItem}의 직렬화 결과와 동일하다
     * ({@code [{"text":..,"correct":..}]}) — 승인 시 역직렬화만 하면 바로 등록 요청이 되도록
     * 처음부터 같은 모양으로 저장한다(변환 코드 최소화).
     */
    private String writeChoicesJson(List<GeneratedProblemItem.GeneratedChoice> choices) {
        List<AdminProblemRequest.ChoiceItem> items = choices.stream()
                .map(c -> new AdminProblemRequest.ChoiceItem(c.text(), c.correct()))
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            // record 직렬화는 실패할 수 없는 경로지만 검사 예외라 형식상 변환 — 발생하면 버그다
            throw new IllegalStateException("보기 JSON 직렬화 실패", e);
        }
    }

    private List<AdminProblemRequest.ChoiceItem> readChoices(String json) {
        if (json == null) {
            return null; // 객관식이 아닌 초안 — AdminProblemRequest도 choices=null 규약
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("보기 JSON 역직렬화 실패: " + json, e);
        }
    }

    private LlmDraftResponse toResponse(GeneratedProblemDraft d) {
        return new LlmDraftResponse(
                d.getId(), d.getDomain(), d.getDomain().getDisplayName(), d.getDifficulty(), d.getType(),
                d.getTitle(), d.getQuestion(), d.getAnswer(), d.getExplanation(), readChoices(d.getChoicesJson()),
                d.getStatus(), d.getModel(), d.getRejectReason(), d.getApprovedProblemId(),
                d.getDocumentSlug(), d.getCreatedAt(), d.getReviewedAt());
    }

    private String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
