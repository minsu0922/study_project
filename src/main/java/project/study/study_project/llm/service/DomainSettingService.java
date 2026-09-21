package project.study.study_project.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.DomainSetting;
import project.study.study_project.llm.repository.DomainSettingRepository;
import project.study.study_project.llm.support.DomainHints;

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
public class DomainSettingService {

    private final DomainSettingRepository repository;

    /**
     * 새 행의 초기 {@code enabled}·{@code sortOrder}를 정하는 기준 목록.
     *
     * <p>기본값을 8개 이름 그대로 적어 둔다({@code LlmProblemService}의 같은 필드와 동일한
     * 이유) — 빈 문자열을 기본값으로 두면 Spring이 그것을 "빈 문자열 원소 1개짜리 목록"으로
     * 보고 {@link Domain}으로 변환하려다 실패한다. 실제 값은 application.yml의
     * {@code llm.generation.batch-domains}에서 온다.
     */
    private final List<Domain> fallbackBatchDomains;

    public DomainSettingService(DomainSettingRepository repository,
                                 @Value("${llm.generation.batch-domains:"
                                         + "NETWORK,OS,DATABASE,DS_ALGORITHM,SYSTEM_DESIGN,SECURITY,"
                                         + "LANGUAGE_RUNTIME,BACKEND_FRAMEWORK}")
                                 List<Domain> fallbackBatchDomains) {
        this.repository = repository;
        // null 방어만 한다 — LlmProblemService처럼 비었을 때 전체 목록으로 되돌리지 않는다.
        // 여기서 되돌리면 "설정을 지웠는데 모든 분야가 켜진 채로 태어난다"는, 의도와 정반대인
        // 결과가 조용히 생긴다. 빈 목록은 "전부 꺼진 채로 태어남"으로 그대로 흘러가야 안전하다.
        this.fallbackBatchDomains = fallbackBatchDomains == null ? List.of() : List.copyOf(fallbackBatchDomains);
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

    /** 관리 화면 목록 — {@code sortOrder} 순 전체. */
    @Transactional(readOnly = true)
    public List<DomainSetting> findAll() {
        return repository.findAllByOrderBySortOrderAsc();
    }
}
