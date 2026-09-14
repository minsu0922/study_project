package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.DocumentEdition;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;

/**
 * 어느 난이도가 <b>어느 편을 근거로 삼는지</b> — 2026-09-14에 뽑아냈다.
 *
 * <h2>왜 클래스로 뽑았나</h2>
 *
 * <p>판정은 원래 {@code DraftGeneratorCli.editionFor}에 있었다. 그런데 <b>배치에만</b> 있었다.
 * 관리 화면의 배치 현황({@code AdminBatchService.sourceOf})은 난이도를 보지 않고 늘
 * {@code parsed.document()}, 즉 <b>입문편 slug만</b> 찍었다.
 *
 * <p>그래서 화면과 실제가 어긋나 있었다. 2026-09-14 실행 로그가 그 증거다 —
 * 배치는 {@code why-constructor-injection-in-spring-advanced}로 돌았는데 화면은
 * {@code why-constructor-injection-in-spring}이라고 적고 있었다. 이 화면이 존재하는 이유가
 * <b>"설정과 실제가 어긋난 것을 한눈에 보는 것"</b>인데(batch.html 주석) 정작 그 줄이 어긋났다.
 *
 * <p>곁가지로 드러난 것: 2026-09-05에 붙인 "(심화편)" 표시가 <b>한 번도 뜬 적이 없다</b>.
 * 서버가 입문편 slug만 보내니 {@code editionOfSlug}가 늘 빈 문자열을 돌려줬다. 표시가 안 뜨는
 * 것은 "오늘은 입문편이구나"로 읽히므로, 없는 것보다 나빴다.
 *
 * <p>{@link DifficultyMaterialRule}과 {@link TypeMaterialRule}이 같은 이유로 먼저 뽑혀 나왔다
 * ("규칙이 두 곳에 생기면 관리 화면으로는 되는데 배치로는 막히는 어긋남이 나고, 그때 어느
 * 쪽이 옳은지 아무도 모른다"). 편 선택만 한쪽에 남아 있을 이유가 없다.
 *
 * <h2>폴백까지 여기에 둔다</h2>
 *
 * <p>"중급·고급은 심화편"이라는 규칙만 나누고 폴백은 각자 두는 안은 버렸다. 폴백이야말로
 * 갈라지기 쉬운 자리다 — 2026-09-03 이전 문서 15편에는 심화편 칸이 아예 없고, 심화편 생성만
 * 실패한 날도 있다. 한쪽이 "없으면 입문편", 다른 쪽이 "없으면 근거 없음"으로 갈리면
 * 화면은 폴백이라 하고 배치는 입문편으로 도는 상태가 된다.
 */
public final class DocumentEditionRule {

    private DocumentEditionRule() {
    }

    /**
     * 이 난이도가 읽어야 할 편.
     *
     * <p><b>2026-09-14에 중급이 심화편으로 옮겨 왔다.</b> 그전에는 고급만 심화편이었는데,
     * 그러면 심화편이 3문제만 떠받쳐 재료가 3.6배 남았다. 남는 지면을 모델이 이론으로 채우면서
     * 글이 계속 어려워졌고, 중급을 붙여 <b>바닥</b>을 만들었다 — 같은 문서로 중급도 내야 하면
     * 너무 어려워지는 순간 중급을 만들 수 없다. 자세한 사정은
     * {@code ClaudeDocumentGenerator.ADVANCED_REQUIRED_SECTIONS} 주석에 있다.
     *
     * <p>이 매핑은 {@code ClaudeProblemGenerator.SOURCE_SECTIONS}가 난이도별로 지목하는 절과
     * 짝이다 — 초급이 캘 절은 입문편에, 중급·고급이 캘 절은 심화편에 있다. 한쪽만 고치면
     * <b>없는 절을 지목하는 상태</b>가 되고, 없는 절을 지목하면 모델은 오류를 내지 않고
     * 조용히 아무 데나 캔다(2026-08-15 사고).
     */
    public static DocumentEdition editionFor(Difficulty difficulty) {
        return difficulty == Difficulty.BEGINNER ? DocumentEdition.BEGINNER : DocumentEdition.ADVANCED;
    }

    /**
     * 그 난이도가 실제로 읽을 문서 — 심화편이 없으면 입문편으로 돌아간다.
     *
     * <p><b>왜 {@code null}이 아니라 입문편인가.</b> 2026-09-03 이전 파일 15편에는 심화편 칸이
     * 없는데, 그 옛 문서들은 <b>한 편에 모든 재료가 들어 있던</b> 시절의 것이라
     * {@code ## 언제 깨지는가}가 실제로 그 안에 있다. {@code null}을 돌려주면 그날이 근거 없는
     * 폴백으로 떨어지는데, 쓸 수 있는 문서를 두고 버리는 셈이다.
     *
     * <p>정말 재료가 없으면 바로 다음 검사({@link DifficultyMaterialRule})가 걸러 준다.
     * 여기서 미리 판단하면 그 검사와 판정이 둘로 갈린다.
     *
     * @param difficulty {@code null}이면(문서일) 입문편을 돌려준다 — 그날은 읽는 것이 아니라 만든다
     * @return 읽을 문서. 파일에 입문편조차 없으면 {@code null}
     */
    public static GeneratedDocumentItem pick(GeneratedDocumentFile file, Difficulty difficulty) {
        if (file == null) {
            return null;
        }
        if (difficulty != null && editionFor(difficulty) == DocumentEdition.ADVANCED
                && hasBody(file.advancedDocument())) {
            return file.advancedDocument();
        }
        return file.document();
    }

    /** 칸은 있는데 본문이 빈 경우가 있다(심화편 생성만 실패한 날) — 그때도 없는 것으로 본다. */
    private static boolean hasBody(GeneratedDocumentItem item) {
        return item != null && item.contentMd() != null && !item.contentMd().isBlank();
    }
}
