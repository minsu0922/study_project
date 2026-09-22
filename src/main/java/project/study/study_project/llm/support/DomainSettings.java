package project.study.study_project.llm.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.dto.DomainSettingsFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 배치가 {@code generated/_domain-settings.json}을 읽는 자리 — 관리 화면이 정한 분야 설정을
 * 클라우드 실행에서 재현한다({@link TopicQueue}와 같은 자리·같은 꼴).
 *
 * <p><b>왜 예외를 던지지 않는가.</b> 이 파일은 관리 화면이 매번 내보내는 것이라 사람이 직접
 * 고칠 일은 드물지만, 그래도 저장소 파일인 이상 깨질 수 있다(수동 병합 충돌, 커밋 실수).
 * 이미 지불한 API 요금이 오타 하나로 버려지면 안 되므로, <b>모든 실패는 빈 설정으로
 * 떨어진다</b> — 부르는 쪽({@code DraftGeneratorCli})이 지금까지의 폴백(application.yml의
 * {@code batch-domains}, 그리고 그것마저 비면 {@code GenerationSchedule}의 전체 분야)으로
 * 이어 간다. {@link TopicQueue}처럼 "왜 못 읽었는지 크게 알리는" 장치({@code problems()})는
 * 여기 없다 — 이 파일은 사람이 손으로 고치는 대상이 아니라 관리 화면의 산출물이라, 깨졌다면
 * 그건 내보내기 쪽 버그이지 이 파일을 여는 사람이 당장 알아야 할 오타가 아니다.
 *
 * <h2>모르는 분야 이름은 그 줄만 버린다</h2>
 *
 * <p>{@link DomainSettingsFile.Entry#domain}이 {@link Domain} enum이 아니라 문자열인 이유는
 * 그 record의 Javadoc이 설명한다 — 요지는 "상수 하나가 enum에서 빠져도(2026-09-21의
 * {@code FRONTEND_CS}처럼) 그 줄만 걸러지고 나머지 분야는 살아야 한다"이다. 그 줄 단위
 * 판단이 이 클래스의 몫이라고 그 Javadoc이 못 박아 둔 자리가 바로 여기, {@link #parseDomain}과
 * 그것을 부르는 {@link #read}다. {@code IllegalArgumentException}을 여기서 잡지 않으면
 * 설정 전체가 죽고, 옛 이름 하나 때문에 켜 둔 다른 일곱 분야까지 배치에서 사라진다.
 *
 * <h2>힌트가 비면 내장값으로 — 오직 "완전히 빈" 경우에만</h2>
 *
 * <p>{@link #hints()}는 파일을 아예 못 읽었을 때만(=이 인스턴스가 {@link #isEmpty()}) {@link
 * DomainHints#BUILT_IN}으로 떨어진다. 파일은 읽었는데 힌트 칸을 전부 비워 둔 경우는 <b>다르게</b>
 * 다룬다 — {@link DomainHints#of}로 빈 맵을 만들어, 화면에서 힌트를 지운 사람의 의도를 그대로
 * 지킨다({@link DomainHints}의 "설정값이 오면 내장값은 쓰지 않는다" 참고). 이 구분을 안 두면
 * "화면에서 힌트를 지웠는데 다음 배치에 옛 힌트가 그대로 나간다"는 조용한 어긋남이 생긴다.
 */
public final class DomainSettings {

    /** 배치와 관리 화면 내보내기가 함께 가리키는 파일 이름. {@code DomainSettingExporter}가 빌려 쓴다. */
    public static final String FILE_NAME = "_domain-settings.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainSettingsFile file;

    private DomainSettings(DomainSettingsFile file) {
        this.file = file;
    }

    /** 완전히 빈 설정 — 파일이 없거나 깨졌을 때 쓰는 유일한 생성 경로. */
    private static DomainSettings empty() {
        return new DomainSettings(new DomainSettingsFile(null, List.of()));
    }

    /* ── 읽기 ─────────────────────────────────────────────────── */

    /**
     * 설정을 읽는다 — 파일이 없거나 깨져 있어도 항상 값을 돌려준다({@link #isEmpty()}가 true인
     * 인스턴스). 예외를 밖으로 던지지 않는 것이 이 메서드의 계약이다 — 그래야 부르는 쪽에서
     * try-catch 없이 바로 폴백 로직을 이어 쓸 수 있다.
     */
    public static DomainSettings read(Path dir) {
        Path path = dir.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            // 파일이 없는 것은 오류가 아니다 — 아직 관리 화면에서 한 번도 내보내지 않았을 수
            // 있다(TopicQueue.read의 같은 판단). 이 경우도 "지금까지처럼 yml로 도는" 예전 동작과
            // 같아야 하므로 빈 설정을 준다.
            return empty();
        }
        try {
            DomainSettingsFile parsed = MAPPER.readValue(path.toFile(), DomainSettingsFile.class);
            List<DomainSettingsFile.Entry> domains = parsed.domains() == null ? List.of() : parsed.domains();
            return new DomainSettings(new DomainSettingsFile(parsed.note(), domains));
        } catch (Exception e) {
            // 형식이 깨졌어도 그날 배치를 죽이면 안 된다 — 이미 낸 API 요금을 버리는 쪽이
            // 훨씬 큰 손해다(TopicQueue.read와 같은 판단, 다만 이 파일은 사람이 아니라 관리
            // 화면이 쓰는 파일이라 problems() 같은 "사람에게 알리는" 장치는 두지 않았다).
            return empty();
        }
    }

    /* ── 조회 ─────────────────────────────────────────────────── */

    /**
     * 켜진 분야를 {@code sortOrder} 순으로 준다 — 배치의 날짜 순환 후보 목록이 된다.
     *
     * <p>모르는 분야 이름(enum에서 빠진 옛 이름 등)은 그 줄만 건너뛴다 — 나머지 분야까지
     * 함께 버리면 옛 이름 하나가 그날 배치 전체의 후보를 비운다.
     */
    public List<Domain> batchDomains() {
        List<DomainSettingsFile.Entry> domains = file.domains();
        if (domains == null || domains.isEmpty()) {
            return List.of();
        }
        List<DomainSettingsFile.Entry> sorted = new ArrayList<>(domains);
        sorted.sort(Comparator.comparingInt(DomainSettingsFile.Entry::sortOrder));

        List<Domain> result = new ArrayList<>();
        for (DomainSettingsFile.Entry entry : sorted) {
            if (!entry.enabled()) {
                continue;
            }
            Domain domain = parseDomain(entry.domain());
            if (domain != null) {
                result.add(domain);
            }
            // domain == null → 모르는 이름. 예외를 던지지 않고 조용히 건너뛴다(클래스 상단 Javadoc).
        }
        return result;
    }

    /**
     * 모델에게 줄 분야 경계 힌트 — 설정이 완전히 비어 있을 때만 {@link DomainHints#BUILT_IN}으로
     * 떨어진다. 파일을 읽긴 했는데 힌트 칸이 비어 있는 경우는 그 빈 상태를 그대로 지킨다
     * (클래스 상단 "힌트가 비면 내장값으로" 참고).
     *
     * <p><b>{@link #batchDomains()}와 달리 꺼진(enabled=false) 분야의 힌트도 담는다.</b>
     * 의도적인 비대칭이다 — {@code --domain=}으로 사람이 분야를 콕 집어 실행하면 그 분야가
     * 순환 후보에서 꺼져 있어도 생성은 진행되는데({@code DraftGeneratorCli}), 그때도 힌트가
     * 나가야 프롬프트의 분야 경계 설명이 빠지지 않는다. 꺼진 분야를 여기서 걸러 버리면
     * "순환에서는 안 나오지만 지정하면 만들어지는" 분야만 힌트 없이 생성되는 조용한 결함이 된다.
     */
    public DomainHints hints() {
        if (isEmpty()) {
            return DomainHints.BUILT_IN;
        }
        Map<Domain, String> map = new EnumMap<>(Domain.class);
        for (DomainSettingsFile.Entry entry : file.domains()) {
            Domain domain = parseDomain(entry.domain());
            if (domain != null && entry.hint() != null) {
                map.put(domain, entry.hint());
            }
        }
        return DomainHints.of(map);
    }

    /** 파일을 못 읽었거나(없음·깨짐), 읽었어도 분야 줄이 하나도 없는가. */
    public boolean isEmpty() {
        return file.domains() == null || file.domains().isEmpty();
    }

    /* ── 도우미 ───────────────────────────────────────────────── */

    /**
     * 분야 문자열 → 상수. 모르는 값이면 {@code null}(예외를 던지지 않는다) — {@link
     * TopicQueue#parseDomain}과 같은 판단이다. 대소문자·앞뒤 공백은 봐주지만 없는 상수명은
     * 봐줄 수 없다: 비슷한 이름으로 짐작해 붙이면 엉뚱한 분야가 배치에 섞인다.
     */
    private static Domain parseDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Domain.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
