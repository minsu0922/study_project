package project.study.study_project.llm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import project.study.study_project.llm.domain.DraftStatus;
import project.study.study_project.llm.domain.GeneratedProblemDraft;
import project.study.study_project.llm.dto.LlmDraftResponse;
import project.study.study_project.llm.dto.LlmGenerateRequest;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
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

    private final ProblemGenerator problemGenerator;
    private final GeneratedProblemDraftRepository draftRepository;
    private final ProblemRepository problemRepository;
    private final AdminProblemService adminProblemService;
    private final ObjectMapper objectMapper;
    private final String model;

    public LlmProblemService(ProblemGenerator problemGenerator,
                             GeneratedProblemDraftRepository draftRepository,
                             ProblemRepository problemRepository,
                             AdminProblemService adminProblemService,
                             ObjectMapper objectMapper,
                             @org.springframework.beans.factory.annotation.Value("${llm.generation.model:claude-opus-4-8}") String model) {
        this.problemGenerator = problemGenerator;
        this.draftRepository = draftRepository;
        this.problemRepository = problemRepository;
        this.adminProblemService = adminProblemService;
        this.objectMapper = objectMapper;
        this.model = model;
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
        List<GeneratedProblemItem> items =
                problemGenerator.generate(domain, difficulty, type, request.count(), avoid);

        // 형식은 스키마가 보장하지만 "내용 규약"(정답 1개 등)은 모델이 어길 수 있다 —
        // 어긴 항목은 배치 전체를 실패시키지 않고 건너뛰며 로그만 남긴다(부분 성공 허용).
        List<GeneratedProblemDraft> drafts = new ArrayList<>();
        for (GeneratedProblemItem item : items) {
            toDraft(item, domain, difficulty, type).ifPresent(drafts::add);
        }
        return draftRepository.saveAll(drafts).stream().map(this::toResponse).toList();
    }

    /**
     * 가장 부족한 도메인×난이도 칸 선택. 한쪽만 지정된 경우(예: 도메인만 골랐음)는
     * 그 축을 고정하고 나머지 축에서만 최소를 찾는다.
     */
    private ScarceCell pickScarcestCell(Domain fixedDomain, Difficulty fixedDifficulty) {
        // 집계 결과를 맵으로 — 문제가 0개인 칸은 GROUP BY 결과에 아예 없으므로 getOrDefault(0)로 보정
        Map<Domain, Map<Difficulty, Long>> counts = new EnumMap<>(Domain.class);
        problemRepository.countGroupByDomainAndDifficulty().forEach(row ->
                counts.computeIfAbsent(row.getDomain(), d -> new EnumMap<>(Difficulty.class))
                        .put(row.getDifficulty(), row.getCnt()));

        Domain bestDomain = null;
        Difficulty bestDifficulty = null;
        long min = Long.MAX_VALUE;
        for (Domain d : fixedDomain != null ? new Domain[]{fixedDomain} : Domain.values()) {
            for (Difficulty diff : fixedDifficulty != null ? new Difficulty[]{fixedDifficulty} : Difficulty.values()) {
                long cnt = counts.getOrDefault(d, Map.of()).getOrDefault(diff, 0L);
                if (cnt < min) {
                    min = cnt;
                    bestDomain = d;
                    bestDifficulty = diff;
                }
            }
        }
        log.info("LLM 생성 대상 칸 선택: {}×{} (현재 {}문제)", bestDomain, bestDifficulty, min);
        return new ScarceCell(bestDomain, bestDifficulty);
    }

    private record ScarceCell(Domain domain, Difficulty difficulty) {
    }

    /** 중복 회피 목록 — 정식 문제(최신 50) + 아직 검수 안 된 같은 도메인 초안. */
    private List<String> buildAvoidList(Domain domain) {
        List<String> avoid = new ArrayList<>(
                problemRepository.findQuestionTextsByDomain(domain, PageRequest.of(0, AVOID_LIST_SIZE)));
        avoid.addAll(draftRepository.findPendingQuestionsByDomain(domain));
        return avoid;
    }

    /** 생성 항목 → 초안 엔티티. 내용 규약 위반은 Optional.empty()로 건너뛴다. */
    private java.util.Optional<GeneratedProblemDraft> toDraft(GeneratedProblemItem item,
                                                              Domain domain, Difficulty difficulty, ProblemType type) {
        String question = trimToNull(item.question());
        String answer = trimToNull(item.answer()); // 스키마상 빈 문자열로 오는 "값 없음"을 null로 정규화
        if (question == null) {
            log.warn("생성 항목 건너뜀: 지문 없음");
            return java.util.Optional.empty();
        }
        String choicesJson = null;
        if (type == ProblemType.MULTIPLE_CHOICE) {
            List<GeneratedProblemItem.GeneratedChoice> choices = item.choices();
            long correct = choices == null ? 0 : choices.stream().filter(GeneratedProblemItem.GeneratedChoice::correct).count();
            if (choices == null || choices.size() < 2 || correct != 1) {
                log.warn("생성 항목 건너뜀: 객관식 보기 규약 위반 (보기 {}개, 정답 {}개) — {}",
                        choices == null ? 0 : choices.size(), correct, snippet(question));
                return java.util.Optional.empty();
            }
            answer = null; // 객관식 answer는 저장 규칙상 null(docs/01)
            choicesJson = writeChoicesJson(choices);
        } else if (answer == null) {
            log.warn("생성 항목 건너뜀: {} 유형인데 answer 없음 — {}", type, snippet(question));
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(GeneratedProblemDraft.pending(
                domain, difficulty, type, question, answer, trimToNull(item.explanation()), choicesJson, model));
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
        return created;
    }

    @Transactional
    public void reject(Long draftId, String reason) {
        findDraft(draftId).reject(trimToNull(reason));
    }

    private GeneratedProblemDraft findDraft(Long id) {
        return draftRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.LLM_001));
    }

    /** 초안 → 관리자 등록 요청. 손 등록과 완전히 같은 형태로 변환해 같은 검증을 태운다. */
    private AdminProblemRequest toAdminRequest(GeneratedProblemDraft draft) {
        return new AdminProblemRequest(
                draft.getDomain(), draft.getDifficulty(), draft.getType(),
                draft.getQuestion(), draft.getAnswer(), draft.getExplanation(),
                readChoices(draft.getChoicesJson()));
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
                d.getQuestion(), d.getAnswer(), d.getExplanation(), readChoices(d.getChoicesJson()),
                d.getStatus(), d.getModel(), d.getRejectReason(), d.getApprovedProblemId(),
                d.getCreatedAt(), d.getReviewedAt());
    }

    private String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String snippet(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "…" : s;
    }
}
