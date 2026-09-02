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
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.repository.DocumentRepository;
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
import project.study.study_project.llm.support.DraftCheck;
import project.study.study_project.llm.support.ProblemItemRule;
import project.study.study_project.llm.support.TypeMaterialRule;
import project.study.study_project.quiz.repository.ProblemRepository;
import project.study.study_project.report.service.ProblemReportService;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

    /**
     * 학습자 제보에서 온 사례임을 프롬프트에서 구분하는 표시(V17).
     *
     * <p>제보를 <b>거절 사례와 같은 통로</b>로 흘려보내면서 접두사만 붙였다. 파일을 새로 만들지
     * 않은 이유: 그러면 {@code DraftGeneratorCli}가 파일을 하나 더 읽고
     * {@code ClaudeProblemGenerator}가 블록을 하나 더 조립해야 하는데, 그렇게 얻는 것은
     * "어디서 온 지적인가"의 구분뿐이고 그 구분은 문장 앞의 여섯 글자로도 된다.
     *
     * <p>구분 자체는 필요하다. 거절은 <b>검수에서 걸린</b> 것이고 제보는 <b>검수를 통과하고도
     * 틀린</b> 것이라, 모델에게 후자는 더 무거운 신호다.
     */
    private static final String REPORT_NOTE_PREFIX = "[출제 후 제보] ";

    private final ProblemGenerator problemGenerator;
    private final GeneratedProblemDraftRepository draftRepository;
    private final ProblemRepository problemRepository;
    private final AdminProblemService adminProblemService;
    /**
     * 등록된 문서를 근거로 삼는 셋째 입구용(2026-08-25). 조회 전용이라 {@code DocumentService}가 아닌
     * 저장소를 직접 쓴다 — 그쪽의 반환형은 화면용 DTO라 본문 전문이 없거나 태그·라벨이 딸려 오는데,
     * 여기서 필요한 것은 프롬프트에 실을 <b>제목과 본문</b>뿐이다.
     */
    private final DocumentRepository documentRepository;
    /**
     * 학습자 제보를 되먹임에 합류시키는 통로(V17). {@code null}을 허용한다 —
     * 이 클래스를 직접 생성하는 테스트가 제보와 무관한 경로를 볼 때 가짜를 하나 더 만들지
     * 않아도 되게 한 것이다(documentRepository를 null로 넘기는 것과 같은 판단).
     */
    private final ProblemReportService reportService;
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
                             DocumentRepository documentRepository,
                             ProblemReportService reportService,
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
        this.documentRepository = documentRepository;
        this.reportService = reportService;
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
        // 문서를 근거로 만드는 경로는 둘로 갈렸다: 4일 주기 배치와 아래 generateFromDocument.
        return saveDrafts(domain, difficulty, type, items, model, null).stream().map(this::toResponse).toList();
    }

    /**
     * <b>이미 등록된 문서</b>를 프롬프트에 실을 그릇으로 바꾼다 — 2026-08-25 신설.
     *
     * <p>컨트롤러가 아니라 여기서 문서를 읽는 이유: 파일·붙여넣기는 HTTP 요청이 들고 온 것이라
     * 컨트롤러가 풀어내는 게 맞지만, 등록 문서는 <b>DB에 있는 것</b>이라 저장소 접근이 필요하다.
     * 컨트롤러에 저장소를 물리면 "웹 계층은 서비스만 부른다"는 경계가 여기서부터 무너진다.
     *
     * <p>{@link SourceDocument.Kind#GENERATED}로 감싼다(3-인자 생성자의 기본값). 등록된 문서는
     * 우리 프롬프트가 쓴 것이라 {@code ## 무엇인가}·{@code ## 언제 깨지는가} 같은 <b>약속된 절이
     * 실제로 있고</b>, 그래야 난이도별 "이 절을 캐라" 지시가 작동한다. 관리자가 손으로 등록한
     * 문서라면 절이 없을 수도 있지만, 그때는 모델이 절을 못 찾아 문제 수가 적게 나올 뿐
     * 조용히 틀린 결과가 되지는 않는다 — 검수에서 걸린다.
     *
     * @throws BusinessException slug에 해당하는 문서가 없을 때(DOC_001) — 화면의 드롭다운이
     *                           오래돼 방금 지운 문서를 가리키는 경우가 실제로 생긴다
     */
    @Transactional(readOnly = true)
    public SourceDocument findRegisteredDocument(String slug) {
        Document document = documentRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001,
                        "근거로 삼을 문서를 찾을 수 없습니다: " + slug));
        return new SourceDocument(document.getSlug(), document.getTitle(), document.getContentMd());
    }

    /**
     * <b>주어진 문서</b>를 근거로 문제를 생성해 PENDING 초안으로 저장한다 — 2026-08-18 신설.
     *
     * <p>전에는 이 자리에 "화면에서 문서를 골라 문제를 뽑는 기능은 지금 필요가 없다 — YAGNI"라고
     * 적혀 있었다. 필요가 생겨서 만든다. 다만 <b>새 경로를 통째로 만들지 않았다</b> —
     * 생성은 {@code problemGenerator}의 기존 오버로드가, 저장·검증은 {@link #saveDrafts}가,
     * 검수·승인·거절은 기존 화면이 그대로 한다. 실제로 새로 쓴 것은 이 메서드 열 줄과
     * 프롬프트의 절 지목 분기 하나뿐이다.
     *
     * <p><b>분야·난이도를 자동으로 고르지 않는다.</b> {@link #generate}는 비어 있으면 "가장 부족한
     * 칸"을 채우는데, 그 규칙을 여기 쓰면 근거 문서와 무관한 분야가 붙는다. 요청 DTO가
     * {@code @NotNull}로 막고 있고, 여기서 다시 고르지 않는 것으로 그 결정을 존중한다.
     *
     * <h2>2026-08-25 — 근거 slug를 요청이 아니라 문서가 정하게 바꿨다</h2>
     *
     * <p>전에는 {@code documentSlug}에 무조건 {@code null}을 넣었다. 이 경로가 <b>업로드 전용</b>
     * 이었기 때문이다 — 올린 파일은 서비스에 등록된 문서가 아니라 학습자 화면의 "개념 문서 읽기"가
     * 갈 곳이 없다. 그런데 등록 문서를 골라 뽑는 셋째 입구가 생기면서 그 가정이 깨졌다.
     * 여기서는 <b>갈 곳이 있다</b>. slug를 비우면 새로 만든 문제가 근거 문서와 이어지지 않는다.
     *
     * <p><b>요청에 "slug를 기록할지" 플래그를 두지 않은 이유</b>: 그 답은 요청의 속성이 아니라
     * <b>문서 자체의 속성</b>이다({@link SourceDocument.Kind}가 같은 이유로 record 안에 있다).
     * 플래그로 빼면 문서와 플래그가 따로 흘러 언젠가 짝이 어긋난다 — 올린 파일에 slug를 기록하는
     * 사고가 나고, 그 결과는 500이 아니라 <b>영원히 404가 나는 링크</b>라 눈에 잘 띄지도 않는다.
     * 그래서 {@code kind}에서 그대로 유도한다: {@code GENERATED}면 갈 곳이 있으니 기록하고,
     * {@code UPLOADED}면 없으니 비운다.
     *
     * <p><b>업로드 경로의 알려진 부작용은 그대로다</b>: 해설 끝의 "(문서의 ○○ 절을 다시 읽어 보라)"가
     * 학습자에게 갈 곳 없는 문장이 된다. 프롬프트에서 그 지시를 빼면 배치 문제의 해설까지
     * 나빠지므로 그대로 두고, 검수 단계에서 사람이 지우는 쪽을 택했다.
     *
     * <p>{@link #generate}와 마찬가지로 트랜잭션을 걸지 않는다 — Claude 호출이 수십 초라
     * 그동안 DB 커넥션을 물고 있으면 커넥션 풀이 마른다.
     */
    public List<LlmDraftResponse> generateFromDocument(LlmDocumentGenerateRequest request, SourceDocument document) {
        ProblemType type = request.type() != null ? request.type() : ProblemType.MULTIPLE_CHOICE;
        if (type == ProblemType.ESSAY) {
            throw new BusinessException(ErrorCode.QUIZ_002, "서술형(ESSAY)은 자동채점 미지원이라 생성할 수 없습니다.");
        }

        // 문서에 이 유형을 낼 재료가 있는지 <호출 전에> 본다(2026-09-01). 배치(DraftGeneratorCli)와
        // 같은 규칙을 같은 클래스에서 부른다 — 규칙이 두 곳에 생기면 "관리 화면으로는 되는데
        // 배치로는 막히는" 어긋남이 나고, 그때 어느 쪽이 옳은지 아무도 모른다.
        //
        // 배치와 달리 여기서는 폴백이 없다. 사람이 문서를 골라 버튼을 누른 실행이라, 근거 없는
        // 문제를 대신 만들어 주는 것은 요청과 다른 일을 하는 것이다(요금까지 쓰면서).
        // 올린 문서는 검사하지 않는다. 우리 양식이 아니라 "## 바탕이 되는 개념" 같은 절이 없고,
        // 없다고 재료가 없는 것도 아니다(SourceDocument.Kind 주석). 여기서 막으면 관리자가 표가
        // 그득한 문서를 올려도 짝짓기를 못 뽑는다 — 검사가 도우려던 사람을 막는 셈이다.
        if (document.kind() == SourceDocument.Kind.GENERATED) {
            String missing = TypeMaterialRule.missingMaterialOf(document.contentMd(), type);
            if (missing != null) {
                throw new BusinessException(ErrorCode.QUIZ_004, missing);
            }
        }

        Domain domain = request.domain();
        Difficulty difficulty = request.difficulty();

        // 중복 회피·거절 사례는 기존 경로와 똑같이 싣는다. 근거 문서가 다르다고 해서
        // "이미 있는 문제와 겹쳐도 된다"가 되는 것은 아니다 — 학습자에게는 같은 문제 목록이다.
        List<String> avoid = buildAvoidList(domain);
        List<RejectionNote> rejectionNotes = findRecentRejectionNotes();

        // 요청에 형태가 지목돼 있으면 그대로 넘긴다(2026-08-25). null이면 모델이 고른다.
        List<GeneratedProblemItem> items = problemGenerator.generate(
                domain, difficulty, type, request.count(), avoid, rejectionNotes, document,
                request.questionKind());

        // 등록 문서만 근거 slug를 남긴다(위 주석). 올린 파일은 가리킬 문서가 없어 비운다.
        String documentSlug = document.kind() == SourceDocument.Kind.GENERATED ? document.slug() : null;

        return saveDrafts(domain, difficulty, type, items, model, documentSlug)
                .stream().map(this::toResponse).toList();
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
        List<RejectionNote> rejected = draftRepository.findRecentRejectionNotes(PageRequest.of(0, REJECTION_NOTE_SIZE)).stream()
                .map(v -> new RejectionNote(v.getQuestion(), v.getReason()))
                .toList();

        // 학습자 제보(V17)를 같은 목록에 합친다. 되먹임 통로를 하나로 유지하면 수동 생성·배치
        // 스냅샷·프롬프트 조립 세 경로가 자동으로 따라온다 — 새 통로를 파면 세 곳을 다 고쳐야 한다.
        if (reportService == null) {
            return rejected;   // 이 클래스를 직접 만든 테스트(필드 주석 참고)
        }
        List<RejectionNote> reported = reportService.findAcceptedFeedback().stream()
                .map(n -> new RejectionNote(n.question(), REPORT_NOTE_PREFIX + n.reason()))
                .toList();

        // 제보를 <앞에> 둔다. 프롬프트가 길어져 뒤가 잘리는 날, 남아야 하는 것은 검수를 통과하고도
        // 틀린 사례다 — 거절 사례는 이미 검수가 한 번 걸러 낸 종류의 실수다.
        return Stream.concat(reported.stream(), rejected.stream()).toList();
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

        // 객관식·짝짓기의 answer는 저장 규칙상 null이다 — 정답이 보기 행 쪽에 있다(docs/01).
        // 객관식은 is_correct에, 짝짓기는 한 행의 text↔match_text 짝 자체에 있다(V16).
        // 그 외 유형은 스키마상 빈 문자열로 오는 "값 없음"을 여기서 null로 정규화한다.
        boolean answerInRows = type == ProblemType.MULTIPLE_CHOICE || type == ProblemType.MATCHING;
        String answer = answerInRows ? null : trimToNull(item.answer());
        // 행을 쓰는 유형이면 보기 JSON을 남긴다. 예전에는 객관식만 봤는데, 그 조건을 그대로 두면
        // 짝짓기·순서 배열 초안이 <항목 없이> 저장된다 — 승인 때 "보기가 없다"로 튕기고,
        // 그때는 이미 요금을 다 낸 뒤다.
        String choicesJson = type.usesChoiceRows() ? writeChoicesJson(item.choices()) : null;

        return java.util.Optional.of(GeneratedProblemDraft.pending(
                domain, difficulty, type, trimToNull(item.title()), trimToNull(item.question()), answer,
                trimToNull(item.explanation()), choicesJson, model, trimToNull(documentSlug),
                item.questionKind()));
    }

    /* ── 검수(목록·승인·거절) ─────────────────────────────── */

    /**
     * 검수 목록 — 상태(기본 PENDING)에 분야·난이도·근거 문서를 선택으로 더한다(2026-08-29).
     *
     * <p>셋 다 {@code null}이면 예전과 같은 목록이다. 검수의 실제 단위가 "이 문서로 만든 것들"이라
     * 좁혀 보는 길이 필요했다 — 자세한 배경은 {@code findForReview}의 주석에 있다.
     */
    @Transactional(readOnly = true)
    public PageResponse<LlmDraftResponse> getDrafts(DraftStatus status, Domain domain, Difficulty difficulty,
                                                    String documentSlug, Pageable pageable) {
        DraftStatus target = status != null ? status : DraftStatus.PENDING;
        return PageResponse.from(draftRepository
                .findForReview(target, domain, difficulty, trimToNull(documentSlug), pageable)
                .map(this::toResponse));
    }

    /**
     * 검수 화면 필터에 채울 근거 문서 목록 — 그 상태에 <b>실제로 있는</b> slug만 돌려준다.
     * 고를 수 있는 것만 보여 줘야 고르고 나서 "0건"을 보지 않는다.
     */
    @Transactional(readOnly = true)
    public List<String> getReviewDocumentSlugs(DraftStatus status) {
        return draftRepository.findDocumentSlugsByStatus(status != null ? status : DraftStatus.PENDING);
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
     * ({@code [{"text":..,"correct":..,"rationale":..}]}) — 승인 시 역직렬화만 하면 바로 등록
     * 요청이 되도록 처음부터 같은 모양으로 저장한다(변환 코드 최소화).
     *
     * <p><b>초안의 보기를 JSON으로 둔 결정이 여기서 값을 한다</b>(2026-08-27). 오답 설명을
     * 더하면서 정식 쪽은 마이그레이션(V15)이 필요했는데, 초안 쪽은 필드 하나가 늘 뿐이다.
     * 옛 초안의 JSON에는 {@code rationale} 키가 아예 없지만 역직렬화하면 {@code null}이 되고,
     * 그 값은 "설명 없는 오답"이라는 뜻으로 그대로 통한다 — 검수함에 쌓인 것을 버리지 않아도 된다.
     */
    private String writeChoicesJson(List<GeneratedProblemItem.GeneratedChoice> choices) {
        List<AdminProblemRequest.ChoiceItem> items = choices.stream()
                // matchText는 짝짓기에서만 값이 있다(2026-08-31). 빈 문자열을 null로 눕히는 이유:
                // AdminProblemService가 "짝이 비었나"를 isBlank로 보므로 뜻은 같지만, 저장된 JSON을
                // 눈으로 볼 때 <짝짓기가 아닌 초안>에 빈 칸이 줄줄이 남는 것이 읽기에 나쁘다.
                .map(c -> new AdminProblemRequest.ChoiceItem(
                        c.text(), c.correct(), c.rationale(), trimToNull(c.matchText())))
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

    /**
     * 초안 엔티티 → 검수 화면 응답. 검사 결과를 <b>여기서 그 자리에 계산해</b> 함께 내린다.
     *
     * <h2>2026-08-25 — 품질 경고가 검수 화면에 처음 뜬다</h2>
     *
     * <p>{@link ProblemItemRule#qualityWarningsOf}는 2026-08-13부터 있었는데, 부르는 곳이
     * {@code DraftGeneratorCli}와 {@code PromptEvalCli}<b>뿐</b>이었다. 즉 <b>배치로 만든 문제만</b>
     * 검사를 받았고 그 결과도 Actions 요약에만 찍혔다. 관리 화면에서 만든 문제는 아무 검사도
     * 받지 않았고, 검수함에는 경고가 뜰 자리조차 없었다.
     *
     * <p>실물로 확인된 구멍이다 — 2026-08-25 파일럿 5문제의 해설은 396·422·438·452·521자였고,
     * <b>396자짜리가 하한(400)에 미달인데 아무도 몰랐다</b>. 게다가 일일 배치를 꺼 둔 지금은
     * 모든 생성이 화면 경로라, 검사가 사실상 하나도 돌지 않는 상태였다.
     *
     * <p><b>왜 저장하지 않고 조회할 때마다 계산하나.</b> 문서 초안이 이미 그렇게 한다
     * ({@code LlmDocumentService.toResponse}). 검사 결과를 저장하면 규칙을 고쳐도 이미 대기 중인
     * 초안은 옛 판정을 달고 있다 — 규칙을 자주 고치는 파이프라인이라 "지금 규칙으로 다시 본다"가
     * 맞다. 비용도 문제가 안 된다: 정규식 몇 개이고 목록은 한 번에 20~50건이다.
     *
     * <p><b>엔티티를 다시 {@code GeneratedProblemItem}으로 되돌려 재는 것</b>이 어색해 보일 수
     * 있다. 그래도 규칙을 엔티티용으로 한 벌 더 쓰는 것보다 낫다 — 판정 기준이 두 곳에 생기면
     * 언젠가 갈라지고, 그러면 배치와 화면이 같은 문제를 두고 다른 말을 한다(이 클래스가
     * {@code ProblemItemRule}에 판정을 맡긴 것과 같은 이유).
     */
    private LlmDraftResponse toResponse(GeneratedProblemDraft d) {
        List<AdminProblemRequest.ChoiceItem> choices = readChoices(d.getChoicesJson());
        // 이미 처리된 초안에는 검사를 돌리지 않는다. 승인·거절이 끝난 것에 경고를 달아 봐야
        // 고칠 방법이 없고, 목록이 지난 경고로 채워지면 정작 봐야 할 대기 건이 묻힌다
        // (LlmDocumentService.toResponse와 같은 판단).
        List<DraftCheck> checks = d.getStatus() == DraftStatus.PENDING
                ? ProblemItemRule.checksOf(toItem(d, choices), d.getDifficulty(),
                        d.getDocumentSlug() != null, d.getType())
                : List.of();

        return new LlmDraftResponse(
                d.getId(), d.getDomain(), d.getDomain().getDisplayName(), d.getDifficulty(), d.getType(),
                d.getTitle(), d.getQuestion(), d.getAnswer(), d.getExplanation(), choices,
                d.getStatus(), d.getModel(), d.getRejectReason(), d.getApprovedProblemId(),
                d.getDocumentSlug(), d.getQuestionKind(),
                d.getQuestionKind() == null ? null : d.getQuestionKind().getLabel(),
                checks, d.getCreatedAt(), d.getReviewedAt());
    }

    /**
     * 저장된 초안을 검사기가 읽는 모양으로 되돌린다 — {@link #toResponse} 주석의 마지막 문단 참고.
     *
     * <p>{@code sourceQuote}는 복원하지 않는다(빈 문자열). 초안 테이블에 그 값을 저장하지 않기
     * 때문이다 — 인용 검사는 <b>근거 문서를 손에 들고</b> 해야 하는데 검수 시점에는 문서를
     * 다시 읽어야 하고, 그 검사는 이미 생성 시점에 배치가 했다({@code SourceQuoteRule} 주석).
     * 여기서 재는 것은 문서 없이도 잴 수 있는 것들뿐이다.
     */
    private GeneratedProblemItem toItem(GeneratedProblemDraft d,
                                        List<AdminProblemRequest.ChoiceItem> choices) {
        // rationale까지 되돌려야 한다 — 검수 화면의 "오답 설명 없음" 경고가 이 값을 센다.
        // 빠뜨리면 설명을 제대로 채운 초안이 매번 경고를 달고 나온다.
        // matchText도 같은 이유로 되돌린다(2026-08-31) — 빠뜨리면 짝짓기 초안이 화면에서
        // "오른쪽이 빈 쌍이 있다"는 경고를 항상 달게 된다. 멀쩡한 초안에 뜨는 경고다.
        List<GeneratedProblemItem.GeneratedChoice> items = choices == null ? List.of()
                : choices.stream()
                .map(c -> new GeneratedProblemItem.GeneratedChoice(
                        c.text(), c.correct(), c.rationale(), c.matchText()))
                .toList();
        return new GeneratedProblemItem(d.getQuestion(), d.getAnswer(), d.getExplanation(),
                items, "", d.getTitle(), d.getQuestionKind());
    }

    private String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
