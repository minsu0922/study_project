package project.study.study_project.llm.client;

/**
 * 개념 문서의 편(編) — 한 주제를 두 편으로 나눈 결과물의 종류다(2026-09-03, docs/15).
 *
 * <p><b>왜 생겼나.</b> 문서 한 편이 초급·중급·고급 재료를 모두 담아야 했고, 그 요구가 전부
 * "몇 개 이상"이라는 하한이라 입문자가 읽을 수 없는 밀도로 굳었다. 09-03 실물에서
 * 레지스터·시그널이 각각 12·13번 나오면서 정의는 한 번도 없었던 것이 그 결과다.
 * 사람이 읽을 글과 고급 문제의 재료를 <b>한 문서에서 겨루게 두지 않는다</b>는 것이 이 분리의 뜻이다.
 *
 * <p><b>왜 enum인가.</b> boolean {@code advanced}로도 되지만, 호출부에서
 * {@code generate(domain, topic, titles, tags, true)}의 {@code true}가 무엇인지 읽히지 않는다.
 * 편에 따라 갈리는 값이 프롬프트·필수 절·분량 상한 셋이라 앞으로도 늘어날 자리다.
 *
 * <p><b>값을 여기에 담지 않은 이유</b>: 필수 절은 {@link ClaudeDocumentGenerator}가, 분량 기준은
 * {@code DocumentDraftValidator}가 각자 들고 있다. 이 enum에 전부 모으면 "쓰는 쪽(client)"과
 * "받아들이는 쪽(support)"의 상수가 한 파일에 섞여 의존 방향이 흐려진다 —
 * {@code REQUIRED_SECTIONS}를 생성기에 두고 검증기가 꺼내 쓰기로 한 것과 같은 판단이다.
 */
public enum DocumentEdition {

    /**
     * 입문편 — 이 주제를 오늘 처음 보는 사람이 첫 줄부터 끝까지 막히지 않고 읽는 글.
     * 초급(1일차)·중급(2일차) 문제의 근거가 된다.
     */
    BEGINNER("입문편"),

    /**
     * 심화편 — 입문편을 읽고 온 사람을 전제한다. 배경 설명을 다시 쓰지 않는 대신
     * 지면을 전부 "언제 깨지는가"에 쓴다. 고급(3일차) 문제의 근거가 된다.
     */
    ADVANCED("심화편");

    private final String displayName;

    DocumentEdition(String displayName) {
        this.displayName = displayName;
    }

    /** 로그·검수 화면에 그대로 찍는 한국어 이름. */
    public String getDisplayName() {
        return displayName;
    }
}
