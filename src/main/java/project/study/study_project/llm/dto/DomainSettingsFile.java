package project.study.study_project.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import project.study.study_project.global.common.DomainCode;

import java.util.List;

/**
 * {@code generated/_domain-settings.json}의 형태 — <b>분야 설정</b> 스냅샷.
 *
 * <p><b>왜 DB가 아니라 파일도 있나.</b> 배치는 GitHub Actions 러너에서 돌고 거기에는 우리
 * MySQL이 없다. 대기열·중복 회피 목록이 이미 같은 이유로 저장소 파일이다 — 이 파일은 그
 * 목록에 "어떤 분야를 켜 뒀는가·순서·화면 이름·모델에게 줄 힌트"를 더한다. 배치는 이 파일만
 * 보고도 {@code batchDomains()}·{@code hints()}가 하던 일을 재현할 수 있어야 한다.
 *
 * <p><b>이 파일은 읽기 전용이다.</b> {@code TopicQueueFile}은 배치가 사용 기록을 적어 되쓰지만,
 * 분야 설정은 관리 화면에서만 바뀐다(DB가 원본) — 배치는 이 파일을 읽기만 하고, 앱이 관리
 * 화면 변경마다 다시 내보낸다. 그래서 손으로 적어 넣은 줄을 다음 기동에 DB로 흡수하는 길이
 * {@code TopicQueue}처럼 없다 — 있다면 배치 실행이 DB 원본을 조용히 덮어쓰는 셈이 된다.
 *
 * <p><b>손으로 열어 볼 파일이라 방어가 다르다</b>({@code TopicQueueFile}과 같은 이유):
 * <ul>
 *   <li>{@code @JsonIgnoreProperties} — 메모용 필드를 하나 더 적어 넣어도 파일 전체가
 *       못 읽는 상태가 되면 안 된다. 그날 배치가 분야 설정 없이 통째로 도는 것이 훨씬 나쁘다.
 *   <li>{@code @JsonInclude(NON_NULL)} — 힌트를 안 쓴 분야마다 {@code "hint": null}이 줄줄이
 *       찍히면 사람이 훑어보기 나쁘다.
 *   <li>{@code domain}이 {@link project.study.study_project.global.common.DomainCode}가 아니라
 *       <b>문자열</b>인 이유 — 오늘(2026-09-21) {@code FRONTEND_CS}가 enum에서 빠지면서,
 *       그 이름 하나로 이 테이블을 엔티티로 읽는 모든 조회가 {@code IllegalArgumentException}으로
 *       죽는 것을 실제로 봤다({@code DomainSettingRepository}의 네이티브 조회가 그 우회다).
 *       파일 형식에서까지 같은 함정을 반복하면 안 된다 — 상수명이 하나 틀리거나 오래돼도
 *       <b>그 줄만</b> 걸러지고 나머지 분야는 배치가 정상으로 읽어야 한다. 줄 단위로 걸러 내는
 *       판단은 이 record가 아니라 Task 6의 리더가 한다(이 record는 껍데기만 지킨다).
 * </ul>
 *
 * @param note    파일이 무엇인지, 배치가 어떻게 쓰는지, 그리고 커밋해야 반영된다는 안내
 * @param domains 분야 설정 전체 — {@code sortOrder} 순, <b>꺼진 분야도 포함</b>한다(관리
 *                화면에서 다시 켤 때 이름·힌트가 남아 있어야 하므로)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DomainSettingsFile(String note, List<Entry> domains) {

    /**
     * 분야 설정 한 줄.
     *
     * @param domain      분야 상수명({@code NETWORK} 등). {@link DomainCode}가 아니라 문자열인
     *                    이유는 이 record 상단 Javadoc 참고
     * @param enabled     배치 자동 선택 후보인가
     * @param sortOrder   날짜 순환 순서
     * @param displayName 화면에 뜨는 이름
     * @param hint        모델에게 주는 경계 설명. 안 쓰면 {@code null}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String domain, boolean enabled, int sortOrder, String displayName, String hint) {
    }
}
