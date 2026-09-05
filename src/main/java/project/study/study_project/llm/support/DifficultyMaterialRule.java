package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.ClaudeProblemGenerator;

import java.util.List;

/**
 * 근거 문서에 <b>이 난이도가 캘 절이 있는지</b>를 API 호출 <b>전에</b> 판정한다 — 2026-09-05에 뽑아냈다.
 *
 * <h2>왜 클래스로 뽑았나</h2>
 *
 * <p>판정 자체는 원래 {@code DraftGeneratorCli.hasMaterialFor}에 있었다. 그런데 <b>배치에만</b>
 * 있었다. 관리자 화면의 "문서로 문제 만들기"({@code LlmProblemService.generateFromDocument})는
 * 유형 재료({@link TypeMaterialRule})만 보고 난이도 재료는 보지 않았다.
 *
 * <p>그래서 관리자가 <b>입문편을 고르고 난이도를 고급으로</b> 두면 그대로 통과했다. 프롬프트는
 * 고급에게 {@code ## 언제 깨지는가}·{@code ## 면접에서 이렇게 물어본다}를 캐라고 지목하는데
 * ({@link ClaudeProblemGenerator#SOURCE_SECTIONS}) 입문편에는 두 절이 <b>없다</b>. 없는 절을
 * 지목하면 모델은 멈추지 않고 <b>알아서 다른 절을 캔다</b> — 배치가 2026-08-15에 겪은 그 사고이고,
 * {@code hasMaterialFor}가 생긴 이유 자체다. 요금을 다 낸 뒤에야, 그것도 사람이 눈으로 읽어야
 * 알 수 있는 <b>조용한 품질 저하</b>다.
 *
 * <p>{@link TypeMaterialRule}이 유형에 대해 이미 같은 이유로 뽑혀 나왔다("규칙이 두 곳에 생기면
 * 관리 화면으로는 되는데 배치로는 막히는 어긋남이 나고, 그때 어느 쪽이 옳은지 아무도 모른다").
 * 난이도 쪽만 한쪽에 남아 있을 이유가 없다.
 *
 * <h2>편(입문/심화)이 아니라 절을 본다</h2>
 *
 * <p>slug의 {@code -advanced} 꼬리로 판정하고 싶어지지만 그러면 안 된다. 2026-09-03 이전
 * 문서 15편은 <b>한 편짜리</b>라 꼬리가 없는데 {@code ## 언제 깨지는가}를 실제로 갖고 있다
 * ({@code DraftGeneratorCli.editionFor} 주석의 판단과 같다). 꼬리로 막으면 그 문서들로는
 * 고급을 못 뽑는데, 재료는 멀쩡히 있다. <b>있는 것을 보고 판정하지, 이름으로 짐작하지 않는다.</b>
 *
 * <h2>"하나라도 있으면 통과"</h2>
 *
 * <p>중급 지시는 절 이름 외에 "본문 문장 안에서 판단한 대목"까지 재료로 인정한다. 두 절을 모두
 * 요구하면 재료가 멀쩡히 있는 문서까지 걸린다. 여기서 막으려는 것은 "조금 부족한 문서"가 아니라
 * <b>캘 곳이 하나도 없는 문서</b>다 — 문턱을 낮게 둬야 오탐이 안 난다.
 */
public final class DifficultyMaterialRule {

    private DifficultyMaterialRule() {
    }

    /**
     * 이 문서에 그 난이도가 캘 절이 하나라도 있는가.
     *
     * <p>{@code null}이 섞이면 <b>막지 않는다</b>. 판단 근거가 없는데 버리는 쪽이 더 나쁘다 —
     * 확신 없는 차단은 멀쩡한 문서를 조용히 폴백으로 보낸다.
     */
    public static boolean hasMaterialFor(String contentMd, Difficulty difficulty) {
        if (contentMd == null || difficulty == null) {
            return true;
        }
        List<String> sections = ClaudeProblemGenerator.SOURCE_SECTIONS.get(difficulty);
        if (sections == null || sections.isEmpty()) {
            return true; // 지목하는 절이 없는 난이도는 검사 대상이 아니다(방어)
        }
        return sections.stream().anyMatch(contentMd::contains);
    }

    /**
     * 재료가 없으면 <b>사람이 읽을 수 있는 사유</b>를, 있으면 {@code null} —
     * {@link TypeMaterialRule#missingMaterialOf}와 같은 모양이다.
     *
     * <p><b>왜 불리언 옆에 이걸 따로 두나.</b> 부르는 쪽 둘이 필요로 하는 것이 다르다.
     * 배치는 "쓸까 말까"만 알면 되고(폴백으로 간다), 관리자 화면은 <b>사람에게 무엇을 고치라고
     * 말해야 한다</b>. 사유 문구를 호출부가 각자 만들면 같은 상황에 두 가지 설명이 나간다.
     *
     * <p>메시지에 <b>어느 편을 고르라</b>고 적는다. 이 화면에서 실제로 벌어지는 실수는
     * 입문편과 심화편의 <b>제목이 완전히 같아서</b>(slug만 {@code -advanced}) 엉뚱한 쪽을 고르는
     * 것이라, "재료가 없습니다"까지만 말하면 무엇을 어떻게 바꿔야 하는지 알 수 없다.
     */
    public static String missingMaterialOf(String contentMd, Difficulty difficulty) {
        if (hasMaterialFor(contentMd, difficulty)) {
            return null;
        }
        List<String> sections = ClaudeProblemGenerator.SOURCE_SECTIONS.get(difficulty);
        return "%s 재료가 없습니다 — 이 문서에 %s 절이 하나도 없습니다. %s"
                .formatted(difficulty.getDisplayName(), String.join("·", sections), adviceFor(difficulty));
    }

    /**
     * 어느 편을 고르라는 한 줄 — 배치가 난이도별로 고르는 편과 같아야 한다
     * ({@code DraftGeneratorCli.editionFor}: 고급은 심화편, 초급·중급은 입문편).
     */
    private static String adviceFor(Difficulty difficulty) {
        return difficulty == Difficulty.ADVANCED
                ? "고급은 심화편에서 뽑습니다 — 같은 제목의 심화편을 고르세요."
                : "초급·중급은 입문편에서 뽑습니다 — 같은 제목의 입문편을 고르세요.";
    }
}
