package project.study.study_project.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDomainSettingRequest;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;
import project.study.study_project.llm.support.DomainHints;
import project.study.study_project.llm.support.DomainHintsProvider;
import project.study.study_project.llm.support.GenerationSchedule;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code domain_setting} 테이블을 {@link Domain} enum에 맞춰 두는 서비스 — 동기화, 그리고
 * 그 결과를 읽는 조회 셋(배치 후보·힌트·전체 목록).
 *
 * <h2>왜 행을 손으로 만들지 않나</h2>
 *
 * <p>{@code DomainSetting} 클래스 주석에 적었듯, enum 상수가 늘거나 줄 때마다 마이그레이션을
 * 새로 쓰게 만들면 <b>깜빡한 상수는 행 없이 조용히 배치에서 빠진다</b>. 화면에는 그 분야가
 * 멀쩡히 보이는데(enum에는 있으므로) 배치 후보 목록에서만 빠져 있어서, 증상이 "왜 저 분야만
 * 문제가 안 늘지"로만 드러나고 원인을 찾기 어렵다. 그래서 {@link #syncWithEnum()}이 기동마다
 * enum을 진실로 삼아 행을 맞춘다.
 *
 * <h2>새 행의 초기값은 폴백 배치 목록에서 온다</h2>
 *
 * <p>{@code llm.generation.batch-domains}는 원래 {@code LlmProblemService}가 "모델이 분야를
 * 알아서 고를 때"의 후보 목록으로 읽던 설정값이다(관리 화면이 생기기 전의 유일한 배치 분야
 * 설정). 이 서비스는 같은 값을 <b>새 행이 태어날 때만</b> 재사용한다 — 이미 있는 행은 이
 * 목록이 바뀌어도 절대 따라 바뀌지 않는다(아래 {@link #syncWithEnum()} 참고). 설정값이 비어
 * 있으면(오타로 지워진 경우 등) 새 행이 전부 꺼진 채로 태어날 뿐, 예외를 던지지 않는다 —
 * {@code LlmProblemService}가 빈 목록을 "전체 후보"로 되돌리는 것과 달리, 여기서는 "일단
 * 꺼 두고 관리자가 화면에서 켜게 한다"가 더 안전한 기본값이다(잘못 켜진 채 배치가 도는 것보다
 * 안 도는 쪽이 되돌리기 쉽다).
 */
@Slf4j
@Service
public class DomainSettingService implements DomainHintsProvider {

    /** 미리보기의 "오늘" 기준 — 워크플로가 KST로 변환해 배치에 넘기는 것과 맞춘다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DomainSettingRepository repository;

    /** 설정이 바뀌면 파일 내보내기를 깨운다 — 듣는 쪽은 {@link DomainSettingExporter}. */
    private final ApplicationEventPublisher events;

    /**
     * 새 행의 초기 {@code enabled}·{@code sortOrder}를 정하는 기준 목록.
     *
     * <p>기본값을 8개 이름 그대로 적어 둔다({@code LlmProblemService}의 같은 필드와 동일한
     * 이유) — 빈 문자열을 기본값으로 두면 Spring이 그것을 "빈 문자열 원소 1개짜리 목록"으로
     * 보고 {@link Domain}으로 변환하려다 실패한다. 실제 값은 application.yml의
     * {@code llm.generation.batch-domains}에서 온다.
     */
    private final List<Domain> fallbackBatchDomains;

    /**
     * 주기의 0일차로 삼을 날 — {@link #preview}가 배치와 같은 위상으로 계산하기 위한 값.
     *
     * <p><b>왜 문자열로 받나.</b> {@code DraftGeneratorCli}도 {@code String}으로 받아
     * {@link GenerationSchedule#parseAnchor}로 파싱한다. {@code List<Domain>}처럼 컨버터가
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
                                 ApplicationEventPublisher events,
                                 @Value("${llm.generation.batch-domains:"
                                         + "NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,"
                                         + "LANGUAGE_RUNTIME,BACKEND_FRAMEWORK}")
                                 List<Domain> fallbackBatchDomains,
                                 @Value("${llm.generation.cycle-anchor:}") String rawCycleAnchor) {
        this.repository = repository;
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
     * @param domain      그날 나올 분야 이름({@link Domain#name()})
     * @param difficulty  문제일의 난이도 이름. 문서일에는 {@code null} —
     *                    {@link GenerationSchedule.Plan#difficulty()}가 문서일에 null을 주는
     *                    그대로를 옮긴다(NPE를 피하려 여기서 값을 지어내지 않는다)
     */
    public record PreviewCell(LocalDate date, boolean documentDay, String domain, String difficulty) {
    }

    /**
     * enum과 테이블을 맞춘다 — 기동 시 {@code DomainSettingSyncRunner}가 부른다.
     *
     * <h2>있는 행은 절대 건드리지 않는다</h2>
     *
     * <p>여기서 가장 중요한 규칙이다. 관리자가 화면에서 이름·힌트·켜짐 여부를 고쳐 뒀는데
     * 기동할 때마다 폴백 목록 기준으로 되돌리면, 그 화면은 아무도 못 믿게 된다. 그래서 이
     * 메서드는 <b>없는 행을 만들고 고아 행을 지우는 일만</b> 하고, 존재가 확인된 행은
     * {@code enabled}·{@code sortOrder}·{@code displayName}·{@code hint} 어느 것도 다시 쓰지 않는다.
     *
     * <h2>없는 행 — 폴백 목록이 초기값을 정한다</h2>
     *
     * <ul>
     *   <li>{@code enabled}: 폴백 목록에 들어 있으면 {@code true}, 아니면 {@code false}.
     *   <li>{@code sortOrder}: 폴백 목록에 있으면 그 안에서의 자리(0부터). 목록 밖이면
     *       <b>목록 길이부터</b> 이어서, {@link Domain} 선언 순서대로 번호를 매긴다 — 이렇게
     *       해야 목록 안팎을 합쳐도 값이 겹치는 행이 생기지 않는다.
     *   <li>{@code displayName}: {@code domain.getDisplayName()}.
     *   <li>{@code hint}: {@link DomainHints#BUILT_IN}의 {@link DomainHints#rawHintFor}
     *       — 코드에 박혀 있던 경계 설명을 그대로 초기값으로 준다({@code DomainHints} 클래스
     *       주석의 "내장값은 행을 처음 만들 때의 초기값으로만 쓰인다"가 바로 이 자리다).
     * </ul>
     *
     * <h2>고아 행 — enum에서 빠진 이름을 가진 행은 지운다</h2>
     *
     * <p>2026-09-21에 {@code FRONTEND_CS}를 실제로 지운 적이 있고, 앞으로도 enum에서 상수가
     * 빠지는 일은 또 생긴다. 그 행이 그대로 남으면 관리 화면 목록에 "고를 수도, 지울 수도
     * 없는 뜻 없는 줄"이 하나 계속 낀다. 조회 자체가 enum 변환을 타면 그 행 때문에 죽으므로
     * (변환은 {@code @Enumerated(EnumType.STRING)}이 {@code Enum.valueOf}로 한다),
     * {@link DomainSettingRepository#findAllDomainNamesNative}로 변환 없이 문자열만 읽어
     * 비교하고, 지우기도 {@link DomainSettingRepository#deleteByDomainNameNative}로 한다.
     */
    @Transactional
    public void syncWithEnum() {
        // 존재 여부만 필요하므로 엔티티로 읽지 않는다 — 고아 행이 섞여 있으면 엔티티 변환이
        // 그 자리에서 터진다(위 Javadoc 참고). 문자열 집합으로만 다룬다.
        Set<String> existingNames = new HashSet<>(repository.findAllDomainNamesNative());

        // 1) enum에는 있는데 행이 없는 분야 — 새로 만든다.
        //    폴백 목록 길이 다음부터 번호를 이어 붙이려면 순회 중에 값을 누적해야 하므로
        //    for-each 바깥에 카운터를 둔다(스트림으로 짜면 이 누적 상태를 감추기 더 번거롭다).
        int nextOrderAfterFallback = fallbackBatchDomains.size();
        for (Domain domain : Domain.values()) {
            if (existingNames.contains(domain.name())) {
                continue; // 있는 행은 손대지 않는다 — 이 메서드의 첫 번째 규칙
            }
            int fallbackIndex = fallbackBatchDomains.indexOf(domain);
            boolean enabled = fallbackIndex >= 0;
            int sortOrder = enabled ? fallbackIndex : nextOrderAfterFallback++;
            repository.save(DomainSetting.initial(domain, enabled, sortOrder,
                    domain.getDisplayName(), DomainHints.BUILT_IN.rawHintFor(domain)));
            log.info("분야 설정: [{}] 행이 없어 새로 만들었습니다 (enabled={}, sortOrder={})",
                    domain, enabled, sortOrder);
        }

        // 2) 행은 있는데 enum에 없는 이름 — 지운다. 새로 만든 행은 전부 유효한 이름이므로
        //    동기화 시작 시점의 existingNames만 봐도 충분하다(다시 조회할 필요 없음).
        Set<String> validNames = new HashSet<>();
        for (Domain domain : Domain.values()) {
            validNames.add(domain.name());
        }
        for (String name : existingNames) {
            if (!validNames.contains(name)) {
                repository.deleteByDomainNameNative(name);
                log.info("분야 설정: enum에서 빠진 '{}' 행을 지웠습니다", name);
            }
        }
    }

    /**
     * 배치 자동 선택 후보 — 켜진 것만, {@code sortOrder} 순.
     *
     * <p>이 순서가 곧 날짜 순환이 도는 순서다(Task 5 이후 관리 화면의 순서 이동이 이 값을
     * 고친다).
     */
    @Transactional(readOnly = true)
    public List<Domain> batchDomains() {
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
        Map<Domain, String> map = new EnumMap<>(Domain.class);
        for (DomainSetting setting : repository.findAllByOrderBySortOrderAsc()) {
            map.put(setting.getDomain(), setting.getHint());
        }
        return DomainHints.of(map);
    }

    /**
     * 앱 안의 두 생성기({@code ClaudeProblemGenerator}·{@code ClaudeDocumentGenerator})가
     * 프롬프트를 짤 때마다 부르는 자리 — {@link #hints()}와 같은 값이다.
     *
     * <p><b>왜 매번 DB를 읽나.</b> 생성기는 스프링이 기동 때 한 번 만드는 빈이라, 여기서 얻은
     * 값을 붙잡아 두면 관리자가 화면에서 힌트를 고쳐도 재시작 전까지 옛 힌트가 나간다. 호출은
     * 생성 한 번(수십 초짜리 유료 API 호출)마다 한두 번뿐이라 열한 줄짜리 조회를 캐시할 까닭이
     * 없다. 생성기가 이 서비스 타입을 직접 모르게 하려고 {@link DomainHintsProvider}로 받는다
     * (그 인터페이스 주석 참고).
     */
    @Override
    @Transactional(readOnly = true)
    public DomainHints current() {
        return hints();
    }

    /** 관리 화면 목록 — {@code sortOrder} 순 전체. */
    @Transactional(readOnly = true)
    public List<DomainSetting> findAll() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    /* ── 관리 화면 변경(Task 9) ───────────────────────────────── */

    /**
     * 분야 하나의 켜짐 여부·이름·힌트를 고친다. 순서는 건드리지 않는다({@link #move}의 몫).
     *
     * <p>바뀐 뒤 {@link DomainSettingChanged}를 알려 파일을 다시 내보낸다
     * ({@link DomainSettingExporter}가 {@code AFTER_COMMIT}에 듣는다) — 그래서 <b>고칠 때마다
     * 커밋이 필요</b>하고, 커밋하지 않으면 클라우드 배치는 여전히 옛 값으로 돈다.
     *
     * @throws BusinessException DOMAIN_001 — enum에는 있는데 행이 없을 때. {@code syncWithEnum}이
     *                            기동마다 전체 분야에 행을 맞춰 두므로 정상 경로에서는 나지 않는다
     */
    @Transactional
    public void edit(Domain domain, AdminDomainSettingRequest request) {
        DomainSetting setting = find(domain);
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
    public void move(Domain domain, Direction direction) {
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
    public List<PreviewCell> preview(List<Domain> domains, int days) {
        LocalDate today = LocalDate.now(KST);
        List<PreviewCell> cells = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            GenerationSchedule.Plan plan = GenerationSchedule.planFor(date, domains, cycleAnchor);
            // 문서일에는 plan.difficulty()가 null이다(GenerationSchedule.Plan Javadoc) —
            // 여기서 값을 지어내지 않고 그 null을 그대로 옮긴다. name()을 무조건 부르면
            // 문서일마다 NPE로 죽는다.
            String difficulty = plan.documentDay() ? null : plan.difficulty().name();
            cells.add(new PreviewCell(date, plan.documentDay(), plan.domain().name(), difficulty));
        }
        return cells;
    }

    /* ── 도우미 ───────────────────────────────────────────────── */

    private DomainSetting find(Domain domain) {
        return repository.findByDomain(domain)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOMAIN_001));
    }

    private int indexOf(List<DomainSetting> settings, Domain domain) {
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).getDomain() == domain) {
                return i;
            }
        }
        return -1;
    }

    // cycle-anchor 파싱은 2026-09-21에 GenerationSchedule.parseAnchor로 합쳤다(위 cycleAnchor
    // 필드 Javadoc 참고) — DraftGeneratorCli와 이 서비스가 각자 같은 로직을 복사해 두 벌로
    // 두면, 한쪽만 바뀌었을 때 미리보기 화면과 배치가 서로 다른 위상을 계산하게 된다.
}
