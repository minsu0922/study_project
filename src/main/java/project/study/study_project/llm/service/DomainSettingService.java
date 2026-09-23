package project.study.study_project.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDomainCreateRequest;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;
import project.study.study_project.llm.repository.GeneratedDocumentDraftRepository;
import project.study.study_project.llm.repository.GeneratedProblemDraftRepository;
import project.study.study_project.llm.repository.TopicQueueItemRepository;
import project.study.study_project.llm.support.DefaultDomains;
import project.study.study_project.llm.support.DomainCatalog;
import project.study.study_project.llm.support.DomainEntry;
import project.study.study_project.llm.support.DomainHints;
import project.study.study_project.llm.support.GenerationSchedule;
import project.study.study_project.quiz.repository.ProblemRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code domain_setting} 등록부 서비스 — 빈 표를 기본 분야로 채우는 첫 시드, 관리자가 손으로
 * 하는 추가·삭제(6번 작업), 그리고 그 결과를 읽는 조회 셋(배치 후보·힌트·전체 목록).
 *
 * <h2>5번 작업(외래키) 전후로 이 서비스의 성격이 바뀌었다</h2>
 *
 * <p>외래키가 없던 시절에는 {@code Domain} enum이 코드가 아는 "분야가 몇 개인지"의 유일한
 * 진실이었고, 이 서비스는 그 enum을 따라 표를 맞추는 동기화기였다({@code syncWithDefaults} —
 * 없는 행을 만들고, enum에서 빠진 행은 지웠다). 이제는 외래키(V20)가 다섯 내용 표
 * (문제·문서·생성 문제 초안·생성 문서 초안·주제 대기열)를 이 표에 묶어 두고, {@code domain_setting}
 * 자체가 <b>등록부</b>가 됐다 — "분야가 몇 개인지"의 진실은 더 이상 코드가 아니라 이 표이고,
 * 관리자가 화면에서 직접 늘리고 줄인다({@link #create}·{@link #delete}).
 *
 * <p>그래서 예전의 "고아 행 정리"는 사라졌다. 외래키가 있으므로 어떤 분야든 내용이 하나라도
 * 있으면 행을 지울 수 없고, 관리자가 추가한 행이 "기본 목록에 없다"는 이유로 다음 기동에
 * 조용히 사라지는 일은 더 이상 있어서는 안 된다 — {@link #seedIfEmpty()} 클래스 Javadoc 참고.
 *
 * <h2>새 행의 초기값은 폴백 배치 목록에서 온다(시드에서만)</h2>
 *
 * <p>{@code llm.generation.batch-domains}는 원래 {@code LlmProblemService}가 "모델이 분야를
 * 알아서 고를 때"의 후보 목록으로 읽던 설정값이다(관리 화면이 생기기 전의 유일한 배치 분야
 * 설정). {@link #seedIfEmpty()}는 같은 값을 <b>표가 완전히 비어 있을 때만</b> 재사용한다.
 * 설정값이 비어 있으면(오타로 지워진 경우 등) 새 행이 전부 꺼진 채로 태어날 뿐, 예외를 던지지
 * 않는다 — {@code LlmProblemService}가 빈 목록을 "전체 후보"로 되돌리는 것과 달리, 여기서는
 * "일단 꺼 두고 관리자가 화면에서 켜게 한다"가 더 안전한 기본값이다(잘못 켜진 채 배치가 도는
 * 것보다 안 도는 쪽이 되돌리기 쉽다). {@link #create}로 관리자가 추가하는 행은 이 폴백을 아예
 * 보지 않는다 — 새 분야는 <b>항상</b> 꺼진 채로 태어난다(아래 {@link #create} Javadoc).
 */
@Slf4j
@Service
public class DomainSettingService implements DomainCatalog {

    /** 미리보기의 "오늘" 기준 — 워크플로가 KST로 변환해 배치에 넘기는 것과 맞춘다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DomainSettingRepository repository;

    /*
     * 아래 다섯 저장소는 6번 작업(등록부 추가·삭제)에서 오직 {@link #delete}의 사용량 확인
     * ({@link #describeUsage})에만 쓰인다 — 삭제하려는 분야를 문제·문서·초안·대기열 중 어느
     * 표가 쓰고 있는지, 몇 건인지를 저장을 "시도하기 전에" 미리 세어 400(DOMAIN_005) 메시지에
     * 싣는다. 외래키(V20, RESTRICT)가 최후의 방어선이지만 그건 500(DB 오류)으로 나온다.
     *
     * 각 저장소가 llm 패키지 바깥(quiz·document)에 있어도 문제가 없다 — LlmProblemService·
     * ExistingDocumentsExporter 등 llm.service 패키지의 다른 서비스도 이미 이 저장소들을
     * 그대로 가져다 쓴다(계층 경계가 애초에 이 방향으로 열려 있다).
     *
     * <p><b>{@code documentRepository}만 {@code @Lazy}를 붙인 이유.</b> {@code DocumentRepository}의
     * QueryDSL 구현체({@code DocumentRepositoryImpl})가 생성자로 {@link DomainCatalog}를 받는데,
     * 이 클래스가 바로 그 인터페이스의 구현체다 — 그대로 두면
     * {@code DomainSettingService → DocumentRepository(빈) → DocumentRepositoryImpl → DomainCatalog(=DomainSettingService)}
     * 순환이 생겨 스프링이 기동 자체를 거부한다({@code BeanCurrentlyInCreationException}, 실제로
     * 겪었다). {@code @Lazy}는 진짜 빈 대신 프록시를 넣어 두고 <b>처음 메서드를 부를 때</b>에야
     * 실제 빈을 찾으므로, 그 시점에는 두 빈이 이미 다 만들어져 있어 순환이 풀린다. 나머지 네
     * 저장소는 이런 상호 의존이 없어 그대로 둬도 된다.
     */
    private final ProblemRepository problemRepository;
    private final DocumentRepository documentRepository; // 생성자 인자 쪽에 @Lazy가 있다(아래)
    private final GeneratedProblemDraftRepository problemDraftRepository;
    private final GeneratedDocumentDraftRepository documentDraftRepository;
    private final TopicQueueItemRepository topicQueueItemRepository;

    /** 설정이 바뀌면 파일 내보내기를 깨운다 — 듣는 쪽은 {@link DomainSettingExporter}. */
    private final ApplicationEventPublisher events;

    /**
     * 새 행의 초기 {@code enabled}·{@code sortOrder}를 정하는 기준 목록.
     *
     * <p>기본값을 8개 이름 그대로 적어 둔다({@code LlmProblemService}의 같은 필드와 동일한
     * 이유) — 빈 문자열을 기본값으로 두면 Spring이 그것을 "빈 문자열 원소 1개짜리 목록"으로
     * 보고 {@link DomainCode}로 변환하려다 실패한다(빈 코드는 형식 검사에 걸린다). 실제 값은 application.yml의
     * {@code llm.generation.batch-domains}에서 온다.
     */
    private final List<DomainCode> fallbackBatchDomains;

    /**
     * 주기의 0일차로 삼을 날 — {@link #preview}가 배치와 같은 위상으로 계산하기 위한 값.
     *
     * <p><b>왜 문자열로 받나.</b> {@code DraftGeneratorCli}도 {@code String}으로 받아
     * {@link GenerationSchedule#parseAnchor}로 파싱한다. {@code List<DomainCode>}처럼 컨버터가
     * 있는 타입이 아니라 빈 값 처리에서 Spring이 애매하게 구는 일이 없다 —
     * {@code fallbackBatchDomains}가 빈 문자열 기본값을 못 쓰는 것과 같은 함정을 여기서는
     * 아예 피해 간다.
     *
     * <p><b>파싱 로직은 {@link GenerationSchedule#parseAnchor}로 합쳐져 있다</b>(2026-09-21).
     * 처음에는 {@code DraftGeneratorCli}의 같은 메서드를 그대로 복사해 두 벌로 뒀는데, 코드
     * 리뷰에서 그 중복이 지적받았다 — 한쪽만 규칙이 바뀌면 이 화면의 미리보기와 실제 배치가
     * <b>서로 다른 위상</b>을 계산하게 되고, 그것이 바로 이 화면이 없애려던 실패다.
     *
     * <p><b>거짓 미리보기를 만들면 안 된다.</b> 이 화면이 있는 이유가 "저장하기 전에 실제로
     * 무엇이 나올지 믿고 보는 것"인데, 다른 앵커로 계산하면 배치가 실제로 도는 위상과 달라져
     * 화면이 거짓말을 하게 된다(task-9-brief 룰링 2).
     */
    private final LocalDate cycleAnchor;

    public DomainSettingService(DomainSettingRepository repository,
                                 ProblemRepository problemRepository,
                                 @Lazy DocumentRepository documentRepository,
                                 GeneratedProblemDraftRepository problemDraftRepository,
                                 GeneratedDocumentDraftRepository documentDraftRepository,
                                 TopicQueueItemRepository topicQueueItemRepository,
                                 ApplicationEventPublisher events,
                                 @Value("${llm.generation.batch-domains:"
                                         + "NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,"
                                         + "LANGUAGE_RUNTIME,BACKEND_FRAMEWORK}")
                                 List<DomainCode> fallbackBatchDomains,
                                 @Value("${llm.generation.cycle-anchor:}") String rawCycleAnchor) {
        this.repository = repository;
        this.problemRepository = problemRepository;
        this.documentRepository = documentRepository;
        this.problemDraftRepository = problemDraftRepository;
        this.documentDraftRepository = documentDraftRepository;
        this.topicQueueItemRepository = topicQueueItemRepository;
        this.events = events;
        // null 방어만 한다 — LlmProblemService처럼 비었을 때 전체 목록으로 되돌리지 않는다.
        // 여기서 되돌리면 "설정을 지웠는데 모든 분야가 켜진 채로 태어난다"는, 의도와 정반대인
        // 결과가 조용히 생긴다. 빈 목록은 "전부 꺼진 채로 태어남"으로 그대로 흘러가야 안전하다.
        this.fallbackBatchDomains = fallbackBatchDomains == null ? List.of() : List.copyOf(fallbackBatchDomains);
        this.cycleAnchor = GenerationSchedule.parseAnchor(rawCycleAnchor);
    }

    /**
     * 순서 이동 방향. {@code TopicQueueService.Direction}을 빌려 쓰지 않는다 — 그쪽에는
     * {@code TOP}이 있는데(대기열이 60줄을 넘어가며 생긴 값), 이 목록은 열한 줄뿐이라 "찾아서
     * 맨 위로"가 필요할 규모가 아니다. 남의 enum을 빌리면 그쪽에 값이 늘 때마다 여기까지
     * 흔들린다(task-9-brief 룰링 1).
     */
    public enum Direction {
        UP, DOWN
    }

    /**
     * 미리보기 한 칸 — 날짜순으로 늘어놓아 "순서를 이렇게 바꾸면 앞으로 며칠이 이렇게 된다"를
     * 저장 전에 보여 준다({@link #preview}).
     *
     * @param date        그 날짜
     * @param documentDay 문서일인지. {@code true}면 {@code difficulty}는 없다
     * @param domain      그날 나올 분야 코드 문자열({@link DomainCode#value()})
     * @param difficulty  문제일의 난이도 이름. 문서일에는 {@code null} —
     *                    {@link GenerationSchedule.Plan#difficulty()}가 문서일에 null을 주는
     *                    그대로를 옮긴다(NPE를 피하려 여기서 값을 지어내지 않는다)
     */
    public record PreviewCell(LocalDate date, boolean documentDay, String domain, String difficulty) {
    }

    /**
     * 표가 <b>완전히 비어 있을 때만</b> 기본 분야 11개로 채운다 — 기동 시
     * {@code DomainSettingSyncRunner}가 부른다.
     *
     * <h2>행이 하나라도 있으면 아무 일도 하지 않는다</h2>
     *
     * <p>여기가 옛 {@code syncWithDefaults}와 가장 크게 갈라지는 자리다. 예전에는 "기본
     * 목록에는 있는데 행이 없는 분야"를 매번 찾아 만들고, "행은 있는데 기본 목록에 없는 이름"은
     * 지웠다. 등록부가 사람이 손으로 늘리고 줄이는 것으로 바뀐 지금 그 규칙을 그대로 두면
     * 사고가 난다 — 관리자가 {@link #create}로 {@code MESSAGING} 같은 새 분야를 추가해도, 다음
     * 기동에 이 메서드가 "기본 11개에 없는 이름"으로 보고 <b>그 행을 지워 버린다</b>. 그래서
     * 이 메서드는 표에 행이 <b>단 하나라도</b> 있으면 그 즉시 돌아온다 — 있는 행을 손보지도,
     * 없는 기본 분야를 채워 넣지도 않는다. "빈 표를 처음 채우는 일"만 한다.
     *
     * <p>표가 완전히 빌 수 있는 경우는 사실상 V19만 막 적용된 새 DB뿐이다(그마저 V20이 다섯
     * 내용 표의 고아 코드를 먼저 채워 넣으므로, 이 메서드가 실제로 11개를 처음부터 만드는 것은
     * 로컬에서 스키마를 완전히 새로 판 경우 정도다). 그래도 "언젠가 한 번은 채워야" 관리 화면과
     * 배치가 처음부터 같은 분야 목록을 본다.
     *
     * <h2>없는 행 — 폴백 목록이 초기값을 정한다</h2>
     *
     * <ul>
     *   <li>{@code enabled}: 폴백 목록에 들어 있으면 {@code true}, 아니면 {@code false}.
     *   <li>{@code sortOrder}: 폴백 목록에 있으면 그 안에서의 자리(0부터). 목록 밖이면
     *       <b>목록 길이부터</b> 이어서, {@link DefaultDomains#codes()} 순서(옛 enum 선언 순서)대로 번호를 매긴다 — 이렇게
     *       해야 목록 안팎을 합쳐도 값이 겹치는 행이 생기지 않는다.
     *   <li>{@code displayName}: {@link DefaultDomains#displayName}(옛 {@code Domain.getDisplayName()} 그대로).
     *   <li>{@code hint}: {@link DomainHints#BUILT_IN}의 {@link DomainHints#rawHintFor}
     *       — 코드에 박혀 있던 경계 설명을 그대로 초기값으로 준다({@code DomainHints} 클래스
     *       주석의 "내장값은 행을 처음 만들 때의 초기값으로만 쓰인다"가 바로 이 자리다).
     * </ul>
     */
    @Transactional
    public void seedIfEmpty() {
        if (repository.count() > 0) {
            // 행이 하나라도 있으면 손대지 않는다 — 이 메서드의 유일한 규칙.
            // 관리자가 추가한 행을 "기본 목록에 없다"는 이유로 지우던 옛 syncWithDefaults의
            // 동작이 바로 이 자리에서 사라졌다(클래스 Javadoc 참고).
            return;
        }

        // 폴백 목록 길이 다음부터 번호를 이어 붙이려면 순회 중에 값을 누적해야 하므로
        // for-each 바깥에 카운터를 둔다(스트림으로 짜면 이 누적 상태를 감추기 더 번거롭다).
        int nextOrderAfterFallback = fallbackBatchDomains.size();
        for (DomainCode domain : DefaultDomains.codes()) {
            int fallbackIndex = fallbackBatchDomains.indexOf(domain);
            boolean enabled = fallbackIndex >= 0;
            int sortOrder = enabled ? fallbackIndex : nextOrderAfterFallback++;
            repository.save(DomainSetting.initial(domain, enabled, sortOrder,
                    DefaultDomains.displayName(domain), DomainHints.BUILT_IN.rawHintFor(domain)));
            log.info("분야 설정: 빈 표를 채웁니다 — [{}] (enabled={}, sortOrder={})",
                    domain, enabled, sortOrder);
        }
    }

    /**
     * 배치 자동 선택 후보 — 켜진 것만, {@code sortOrder} 순.
     *
     * <p>이 순서가 곧 날짜 순환이 도는 순서다(Task 5 이후 관리 화면의 순서 이동이 이 값을
     * 고친다).
     */
    @Transactional(readOnly = true)
    public List<DomainCode> batchDomains() {
        return repository.findAllByOrderBySortOrderAsc().stream()
                .filter(DomainSetting::isEnabled)
                .map(DomainSetting::getDomain)
                .toList();
    }

    /**
     * 지금 저장된 힌트를 {@link DomainHints}로 묶어 준다.
     *
     * <p>{@link DomainHints#of}는 빠진 분야를 {@link DomainHints#BUILT_IN}으로 메우지 않는다
     * ({@code DomainHints} 클래스 주석) — 여기서 만드는 것이 곧 "지금 설정 화면이 들고 있는
     * 값 그대로"가 되어야 그 약속이 지켜진다. 꺼진 분야의 힌트도 그대로 담는다 — 이 메서드는
     * "화면에 지금 있는 값"을 그대로 보여 주는 자리이지, 배치가 쓸 값만 거르는 자리가 아니다
     * (거르는 일은 {@link #batchDomains()}의 몫).
     */
    @Transactional(readOnly = true)
    public DomainHints hints() {
        // 예전엔 EnumMap. DomainHints.of가 곧바로 조회 전용 HashMap으로 복사하므로 이 맵의
        // 순서는 밖으로 드러나지 않는다 — HashMap이면 충분하다.
        Map<DomainCode, String> map = new HashMap<>();
        for (DomainSetting setting : repository.findAllByOrderBySortOrderAsc()) {
            map.put(setting.getDomain(), setting.getHint());
        }
        return DomainHints.of(map);
    }

    /** 관리 화면 목록 — {@code sortOrder} 순 전체. */
    @Transactional(readOnly = true)
    public List<DomainSetting> findAll() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    /* ── DomainCatalog ───────────────────────────────────────────
     * 아래 네 메서드는 이 태스크(enum → DomainCode 치환)에서 인터페이스만 맞춰 둔 것이다. 아직
     * 부르는 곳이 없어 기존 동작은 하나도 바뀌지 않는다 — 호출부를 옮기는 일은 뒤 태스크의 몫이고,
     * 그때 각자 리뷰를 받는다. hints()는 위에 이미 있다(같은 시그니처라 그대로 인터페이스를 채운다).
     * DomainCatalog 주석대로 전부 호출 시점에 DB를 다시 읽는다 — 캐시하면 화면에서 고친 값이
     * 재시작 전까지 안 보인다.
     */

    /** 정렬 순서대로 전체 행. 힌트가 없는 행({@code null})은 빈 문자열로 — {@link DomainEntry} 계약. */
    @Override
    @Transactional(readOnly = true)
    public List<DomainEntry> all() {
        return repository.findAllByOrderBySortOrderAsc().stream()
                .map(s -> new DomainEntry(s.getDomain(), s.isEnabled(), s.getSortOrder(),
                        s.getDisplayName(), s.getHint() == null ? "" : s.getHint()))
                .toList();
    }

    /** 켜진 분야 코드 — {@link #batchDomains()}와 같은 값이다(이름만 인터페이스 쪽 이름). */
    @Override
    @Transactional(readOnly = true)
    public List<DomainCode> enabled() {
        return batchDomains();
    }

    /** 행이 없으면 코드 글자 그대로 — 화면에 빈칸이 뜨는 것보다 낫다({@link DefaultDomains#displayName}과 같은 판단). */
    @Override
    @Transactional(readOnly = true)
    public String displayName(DomainCode code) {
        return repository.findByDomain(code).map(DomainSetting::getDisplayName).orElse(code.value());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(DomainCode code) {
        return repository.existsById(code);
    }

    /* ── 관리 화면 변경(Task 9) ───────────────────────────────── */

    /**
     * 분야 하나의 켜짐 여부·이름·힌트를 고친다. 순서는 건드리지 않는다({@link #move}의 몫).
     *
     * <p>바뀐 뒤 {@link DomainSettingChanged}를 알려 파일을 다시 내보낸다
     * ({@link DomainSettingExporter}가 {@code AFTER_COMMIT}에 듣는다) — 그래서 <b>고칠 때마다
     * 커밋이 필요</b>하고, 커밋하지 않으면 클라우드 배치는 여전히 옛 값으로 돈다.
     *
     * <p><b>마지막으로 켜진 분야는 끌 수 없다</b>(최종 리뷰 Important 3). 전부 꺼진 상태를
     * 세 곳이 서로 다르게 읽었다 — CLI는 yml 8개로 폴백하고, 앱은 enum 전체로 넓히고, 화면은
     * "하나는 켜야 배치가 돈다"고 안내했다. 분야를 다 꺼서 배치를 멈추려 한 관리자는 배치가
     * 조용히 계속 도는 것을 보게 된다. 배치를 멈추는 스위치는 이미 {@code llm.generation.batch-enabled}
     * (워크플로의 {@code force}와 짝)로 따로 있으므로, 새 "정지 모드"를 만들기보다 이 상태 자체를
     * 못 만들게 막는 편이 단순하다 — 정지 수단이 둘이면 둘의 뜻이 어긋나는 일이 또 생긴다.
     *
     * @throws BusinessException DOMAIN_001 — 행이 없을 때. 정상 경로에서는 나지 않는다 — 관리
     *                            화면이 늘 {@link #findAll()}로 실제 있는 행만 보여 주므로,
     *                            이 예외는 그 사이 다른 창에서 같은 분야가 지워졌을 때만 난다
     * @throws BusinessException DOMAIN_002(400) — 이 변경으로 켜진 분야가 0개가 될 때
     */
    @Transactional
    public void edit(DomainCode domain, AdminDomainSettingRequest request) {
        DomainSetting setting = find(domain);
        if (!request.enabled() && noOtherEnabled(domain)) {
            // "변경 결과" 켜진 분야가 0개인지를 본다 — 이미 꺼진 분야의 이름만 고치는 요청은
            // 여기 오지 않는다(다른 켜진 분야가 있으므로). 판정은 고치기 <전에> 한다:
            // 예외가 나면 트랜잭션이 되돌리긴 하지만, 엔티티를 먼저 바꿔 두면 판정 조회가
            // 자동 플러시로 반쯤 바뀐 상태를 읽게 된다.
            throw new BusinessException(ErrorCode.DOMAIN_002);
        }
        setting.edit(request.enabled(), request.displayName(), request.hint());
        log.info("분야 설정 수정: [{}] enabled={}, displayName={} — 커밋해야 다음 배치부터 반영됩니다",
                domain, request.enabled(), request.displayName());
        events.publishEvent(new DomainSettingChanged());
    }

    /**
     * 순서 이동 — 이웃과 {@code sortOrder}를 맞바꾼다({@code TopicQueueService.move}와 같은 방식,
     * 같은 이유는 그 메서드 Javadoc 참고).
     *
     * <p><b>맨 위에서 더 올리거나 맨 아래에서 더 내리면 아무 일도 하지 않는다.</b> 오류로
     * 만들면 화면에서 버튼을 눌러 보는 것 자체가 무서워진다(task-9-brief) — 조용히 무시하는
     * 편이 "끝에 닿았다"는 사실을 자연스럽게 전달한다.
     */
    @Transactional
    public void move(DomainCode domain, Direction direction) {
        List<DomainSetting> all = repository.findAllByOrderBySortOrderAsc();
        int index = indexOf(all, domain);
        int target = direction == Direction.UP ? index - 1 : index + 1;
        if (index < 0 || target < 0 || target >= all.size()) {
            return; // 없는 분야이거나 이미 끝이다 — 할 일이 없다
        }

        DomainSetting item = all.get(index);
        DomainSetting neighbor = all.get(target);
        // 두 값을 <바꾸기 전에> 붙잡아 둔다 — TopicQueueService.move가 겪은 함정과 같다.
        // 맞바꾼 뒤에 비교하면 neighbor는 이미 내 값이라 아래 보정 조건이 항상 참이 된다.
        int mine = item.getSortOrder();
        int theirs = neighbor.getSortOrder();
        item.changeOrder(theirs);
        neighbor.changeOrder(mine);
        if (mine == theirs) {
            // 옛 데이터나 동기화 과정에서 순서값이 겹칠 수 있다 — 맞바꿔도 목록이 그대로면
            // "눌렀는데 안 움직인다"가 되므로 이웃을 한 칸 더 밀어 확실히 가른다.
            neighbor.changeOrder(mine + (direction == Direction.UP ? 1 : -1));
        }
        log.info("분야 설정 순서 이동: [{}] {} — 커밋해야 다음 배치부터 반영됩니다", domain, direction);
        events.publishEvent(new DomainSettingChanged());
    }

    /**
     * 주어진 순서로 앞으로 {@code days}일의 생성 계획을 미리 계산한다 — <b>저장하지 않는다.</b>
     *
     * <p>화면이 체크·순서를 바꾼 <b>그 자리에서</b>, 아직 저장 버튼을 누르기 전의 화면 상태를
     * 그대로 넘겨 부른다. 그래야 "저장하면 무슨 일이 벌어지는지"를 커밋하기 전에 눈으로 볼 수
     * 있다 — 순환이 분야 두 개에 갇혀 나머지가 개념 문서를 영영 못 받던 사고
     * ({@code DraftGeneratorCli} 주석 참고)가 저장 <b>후에야</b> 드러나던 것을 막으려는 화면이다.
     *
     * <p>앵커는 {@code application.yml}의 {@code llm.generation.cycle-anchor}를 그대로 쓴다
     * ({@link #cycleAnchor}) — 다른 위상으로 계산하면 배치가 실제로 도는 것과 다른, 거짓
     * 미리보기가 된다.
     *
     * @param domains 후보 분야 — <b>화면이 지금 들고 있는(아직 저장 안 한) 순서</b> 그대로
     * @param days    미리 볼 일수
     */
    @Transactional(readOnly = true)
    public List<PreviewCell> preview(List<DomainCode> domains, int days) {
        LocalDate today = LocalDate.now(KST);
        List<PreviewCell> cells = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            GenerationSchedule.Plan plan = GenerationSchedule.planFor(date, domains, cycleAnchor);
            // 문서일에는 plan.difficulty()가 null이다(GenerationSchedule.Plan Javadoc) —
            // 여기서 값을 지어내지 않고 그 null을 그대로 옮긴다. name()을 무조건 부르면
            // 문서일마다 NPE로 죽는다.
            String difficulty = plan.documentDay() ? null : plan.difficulty().name();
            cells.add(new PreviewCell(date, plan.documentDay(), plan.domain().value(), difficulty));
        }
        return cells;
    }

    /* ── 등록부 추가·삭제(6번 작업) ───────────────────────────── */

    /**
     * 새 분야를 등록부에 추가한다.
     *
     * <h2>새 행은 항상 꺼진 채로, 순서는 맨 끝에 생긴다</h2>
     *
     * <p>켜진 채로 태어나면 <b>관리자가 미처 힌트도 안 적어 둔 분야</b>가 바로 다음 날 배치
     * 순환에 끼어들어, 경계 설명 없이 모델이 알아서 분야를 해석하게 된다({@link DomainHints}
     * 클래스 Javadoc이 왜 힌트가 필요한지 적어 둔 바로 그 사고). 관리자가 이름·힌트를 다듬고
     * 화면에서 직접 켜야 순환에 들어간다 — {@link #seedIfEmpty()}가 폴백 목록에 <b>없는</b>
     * 기본 분야를 꺼진 채로 만드는 것과 같은 판단이다. 순서를 맨 끝에 두는 이유도 같다 —
     * 중간에 끼워 넣으면 이미 굳어진 날짜 순환에서 기존 분야들의 자리가 밀린다.
     *
     * <h2>코드 형식·중복</h2>
     *
     * <p>형식(대문자로 시작하는 대문자·숫자·밑줄 2~30자)은 {@link DomainCode} 값 타입이 요청
     * 역직렬화 단계에서 이미 막는다({@code AdminDomainCreateRequest} Javadoc) — 여기 다다르는
     * {@code request.code()}는 형식이 유효하다고 봐도 된다. 남은 것은 "이미 쓰는 코드인가"뿐이고,
     * 그건 DB만 아는 사실이라 여기서 확인한다.
     *
     * @throws BusinessException DOMAIN_004(400) — 이미 등록된 코드일 때
     */
    @Transactional
    public DomainSetting create(AdminDomainCreateRequest request) {
        if (repository.existsByDomain(request.code())) {
            throw new BusinessException(ErrorCode.DOMAIN_004);
        }
        int sortOrder = repository.findMaxSortOrder() + 1;
        DomainSetting setting = DomainSetting.initial(
                request.code(), false, sortOrder, request.displayName(), request.hint());
        repository.save(setting);
        log.info("분야 설정: [{}] 행을 새로 만들었습니다(관리자 추가, enabled=false, sortOrder={}) "
                        + "— 커밋해야 다음 배치부터 반영됩니다",
                request.code(), sortOrder);
        events.publishEvent(new DomainSettingChanged());
        return setting;
    }

    /**
     * 분야를 등록부에서 지운다.
     *
     * <h2>내용이 있으면 지울 수 없다</h2>
     *
     * <p>외래키(V20, ON DELETE 기본값인 RESTRICT)가 최후의 방어선이지만, 그대로 두면
     * {@code DataIntegrityViolationException} → 500(DB 오류)으로 떨어진다. 그래서 저장을
     * <b>시도하기 전에</b> 문제·문서·생성 문제 초안·생성 문서 초안(거절 포함)·주제 대기열
     * 다섯 표를 먼저 세고({@link #describeUsage}), 하나라도 걸리면 400(DOMAIN_005)으로 바꾸면서
     * <b>어느 표에 몇 건인지</b>를 메시지에 담는다 — "지울 수 없습니다"만으로는 관리자가 다음에
     * 뭘 해야 할지 알 수 없다.
     *
     * <p><b>거절된 초안도 센다.</b> 거절 사유는 다음 생성 프롬프트에 되먹이는 학습 자료라
     * ({@code GeneratedProblemDraftRepository#findRecentRejectionNotes}), 분야를 지우면 그
     * 되먹임 근거까지 함께 사라진다 — "화면에 안 보이니 안전하다"고 오해하기 쉬운 자리라
     * 브리핑에 명시됐다.
     *
     * <h2>마지막으로 켜진 분야도 지울 수 없다</h2>
     *
     * <p>{@link #edit}이 "마지막으로 켜진 분야는 끌 수 없다"고 막는 것과 같은 이유(DOMAIN_002
     * Javadoc 참고) — 삭제는 "끄기"보다 더 되돌리기 어려운 변경이라 이 규칙을 피해 갈 구멍이
     * 되면 안 된다.
     *
     * <h2>기본 11개도 특별 취급하지 않는다</h2>
     *
     * <p>{@link DefaultDomains}에 있는 코드인지 여부는 이 메서드가 아예 묻지 않는다 — 내용이
     * 없고 마지막 켜진 분야가 아니라면, 기본 분야든 관리자가 나중에 추가한 분야든 같은 규칙으로
     * 지워진다. 특별 취급을 넣는 순간 "이 열한 개는 못 지운다"는 코드가 어딘가에 박히고, 그
     * 목록은 다시 {@link DefaultDomains}처럼 본코드가 분야 이름을 아는 자리가 된다 — 이 태스크가
     * 없애려는 바로 그 결합이다.
     *
     * @throws BusinessException DOMAIN_001(404) — 행이 없을 때
     * @throws BusinessException DOMAIN_002(400) — 마지막으로 켜진 분야일 때
     * @throws BusinessException DOMAIN_005(400) — 다섯 표 중 하나라도 이 분야를 쓸 때
     */
    @Transactional
    public void delete(DomainCode domain) {
        DomainSetting setting = find(domain);
        if (setting.isEnabled() && noOtherEnabled(domain)) {
            throw new BusinessException(ErrorCode.DOMAIN_002);
        }
        String usage = describeUsage(domain);
        if (!usage.isEmpty()) {
            // usage는 "주제 대기열 4건"처럼 항상 "건"(받침 ㄴ)으로 끝나므로 주격 조사는 "이"로
            // 고정해도 문법이 어긋나지 않는다. 그 "이"(조사) 다음의 "이 분야"는 지시대명사 "이"다
            // — 글자는 같지만 역할이 다른 두 "이"가 나란히 온다(브리핑 예시 문구 그대로).
            throw new BusinessException(ErrorCode.DOMAIN_005,
                    "'" + setting.getDisplayName() + "'를 지울 수 없습니다 — " + usage
                            + "이 이 분야를 씁니다. 순환에서만 빼려면 체크를 끄세요.");
        }
        repository.delete(setting);
        log.info("분야 설정: [{}] 행을 지웠습니다(관리자 삭제) — 커밋해야 다음 배치부터 반영됩니다", domain);
        events.publishEvent(new DomainSettingChanged());
    }

    /* ── 도우미 ───────────────────────────────────────────────── */

    private DomainSetting find(DomainCode domain) {
        return repository.findByDomain(domain)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOMAIN_001));
    }

    /** {@code domain}을 빼고 켜진 분야가 하나도 없는지 — {@link #edit}·{@link #delete}의 "마지막 분야" 판정. */
    private boolean noOtherEnabled(DomainCode domain) {
        return repository.findAllByOrderBySortOrderAsc().stream()
                .noneMatch(s -> !s.getDomain().equals(domain) && s.isEnabled()); // record라 == 는 참조 비교 — equals로 값 비교
    }

    /**
     * {@link #delete}가 지우려는 분야를 다섯 표 중 누가, 몇 건 쓰고 있는지 사람이 읽을 문구로
     * 모은다. 0건인 표는 뺀다 — 관리자가 실제로 손댈 표만 보여 줘야 메시지가 다음 행동으로
     * 곧장 이어진다("문제 0건, 주제 대기열 4건이 씁니다" 같은 문구는 0건을 왜 적었는지부터
     * 되묻게 만든다).
     *
     * <p>순서를 <b>문제 → 문서 → 생성 문제 초안 → 생성 문서 초안 → 주제 대기열</b>로 고정한
     * 이유는 브리핑이 예시로 든 순서와 같다 — 매번 다른 순서로 나오면 같은 상황도 메시지가
     * 매번 다르게 보인다.
     *
     * @return 빈 문자열이면 어디서도 쓰지 않는다는 뜻. 아니면 {@code "문제 12건, 주제 대기열 4건"}
     *         꼴(뒤에 "이 분야를 씁니다."를 붙이는 것은 호출부의 몫)
     */
    private String describeUsage(DomainCode domain) {
        List<String> parts = new ArrayList<>();
        addIfPositive(parts, "문제", problemRepository.countByDomain(domain));
        addIfPositive(parts, "문서", documentRepository.countByDomain(domain));
        addIfPositive(parts, "생성 문제 초안", problemDraftRepository.countByDomain(domain));
        addIfPositive(parts, "생성 문서 초안", documentDraftRepository.countByDomain(domain));
        addIfPositive(parts, "주제 대기열", topicQueueItemRepository.countByDomain(domain));
        return String.join(", ", parts);
    }

    private void addIfPositive(List<String> parts, String label, long count) {
        if (count > 0) {
            parts.add(label + " " + count + "건");
        }
    }

    private int indexOf(List<DomainSetting> settings, DomainCode domain) {
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).getDomain().equals(domain)) { // == 이면 같은 코드라도 다른 인스턴스라 못 찾는다
                return i;
            }
        }
        return -1;
    }

    // cycle-anchor 파싱은 2026-09-21에 GenerationSchedule.parseAnchor로 합쳤다(위 cycleAnchor
    // 필드 Javadoc 참고) — DraftGeneratorCli와 이 서비스가 각자 같은 로직을 복사해 두 벌로
    // 두면, 한쪽만 바뀌었을 때 미리보기 화면과 배치가 서로 다른 위상을 계산하게 된다.
}
