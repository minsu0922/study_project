package project.study.study_project.llm.support;

import project.study.study_project.global.common.Difficulty;
import project.study.study_project.llm.client.DocumentEdition;
import project.study.study_project.llm.client.GeneratedDocumentItem;
import project.study.study_project.llm.dto.GeneratedDocumentFile;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * 중급이 <b>입문편에서 함께 가져갈 절</b> — 2026-09-17 신설.
     *
     * <p>{@code ClaudeProblemGenerator.SOURCE_SECTIONS}의 중급 목록 2·3번과 같은 이름이다.
     * 그 목록은 "지목해도 되는 이름"이고 여기는 "실제로 오려 붙일 이름"이라 쓰임이 다르지만,
     * <b>갈라지면 안 된다</b> — 프롬프트가 지목한 절이 본문에 없으면 모델은 오류를 내지 않고
     * 조용히 아무 데나 캔다(2026-08-15 사고). 둘이 같은지는 테스트가 대조한다.
     */
    public static final List<String> BEGINNER_SECTIONS_FOR_INTERMEDIATE =
            List.of("### 왜 이렇게 설계됐는가", "## 실무에서는 이렇게 쓴다");

    /**
     * 오려 붙인 자리에 다는 표시 — 모델이 <b>같은 주제의 입문편</b>이라는 것을 알아야 한다.
     *
     * <p>{@code public}인 이유: 문제 생성 프롬프트가 이 문구를 그대로 인용한다("이 표시 아래는
     * 입문편이다"). 해설이 절을 가리킬 때 편을 밝히게 하려면 <b>어디까지가 어느 편인지</b>를
     * 모델이 가릴 수 있어야 하고, 그 기준이 이 한 줄이다. 두 곳에 따로 적으면 갈라진다.
     */
    public static final String BEGINNER_EXCERPT_HEADER = "\n\n--- 같은 주제 입문편에서 가져온 부분 ---\n\n";

    /**
     * 그 난이도가 읽을 <b>본문</b> — 중급이면 심화편에 입문편의 중급 절 둘을 붙여 돌려준다(2026-09-17).
     *
     * <h2>왜 붙이나</h2>
     *
     * <p><b>중급만 재료가 말랐다.</b> 2026-09-17 실물(Flyway)을 재 보니 난이도별로 캘 수 있는
     * 분량이 이렇게 갈렸다 — 초급 3,185자(7문제 요청), 중급 <b>908자</b>(5문제), 고급 4,623자(3문제).
     * 문제당 재료가 초급 455자, 고급 1,541자인데 중급만 182자다.
     *
     * <p>그 결과가 두 갈래로 나왔다. ① 5개를 못 채워 <b>뒤쪽 항목이 빈 지문으로</b> 돌아온다
     * (09-17에 3개, 09-13에 2개, 09-14에 1개 — 늘 뒤쪽 번호다). ② 모자란 만큼 모델이 이웃 절로
     * 넘어간다. 09-17 실물 1번 문제의 해설이 {@code ### 애플리케이션 프로세스 밖에서만 만나는 것}을
     * 가리키는데, 그건 <b>고급 절</b>({@code ## 어떤 때 통하지 않는가}) 아래 소제목이다.
     * 고급 재료로 만든 중급 문제라 지문이 오케스트레이터·테넌트 배치로 시작한다.
     *
     * <p><b>입문편에 그 재료가 놀고 있었다.</b> 2026-09-14에 중급을 심화편으로 옮기면서
     * {@code ### 왜 이렇게 설계됐는가}(608자)와 {@code ## 실무에서는 이렇게 쓴다}(867자)가
     * 아무도 읽지 않는 절이 됐다. 둘을 붙이면 908 → 2,383자로 2.6배가 되고, <b>쉬운 재료가
     * 섞여 난이도도 함께 내려간다</b> — 입문편 문장은 처음 보는 사람 눈높이로 쓰인 것이다.
     *
     * <h2>왜 편을 되돌리지 않았나</h2>
     *
     * <p>중급을 입문편으로 되돌리는 안이 가장 간단하다(한 줄). 버린 이유는 2026-09-14에 중급을
     * 심화편에 붙인 목적이 <b>심화편의 바닥</b>이었기 때문이다 — 심화편이 고급 3문제만 떠받치면
     * 재료가 3.6배 남고, 남는 지면을 모델이 이론으로 채워 글이 계속 어려워진다. 되돌리면
     * 그 문제가 그대로 돌아온다. 붙이는 쪽은 바닥을 유지하면서 재료만 늘린다.
     *
     * <h2>왜 본문을 합치나 — 문서를 둘로 넘기지 않고</h2>
     *
     * <p>{@code SourceDocument}를 두 편으로 늘리는 안은 버렸다. 그 타입은 배치·관리 화면·업로드
     * 세 경로가 함께 쓰고, {@code SourceQuoteRule}(근거 한 줄이 본문에 실제로 있는지)과
     * {@code TypeMaterialRule}이 전부 <b>본문 문자열 하나</b>를 전제로 판정한다. 한 자리를 위해
     * 그 전제를 흔들면 세 경로의 검증이 같이 흔들린다. 문자열을 합치면 그 모든 검사가
     * <b>그대로</b> 통한다 — 오려 붙인 문장도 근거로 인용할 수 있어야 하므로 그게 맞기도 하다.
     *
     * @return 합친 본문. 중급이 아니거나 붙일 것이 없으면 {@code pick}이 고른 편의 본문 그대로
     */
    public static String bodyFor(GeneratedDocumentFile file, Difficulty difficulty) {
        GeneratedDocumentItem picked = pick(file, difficulty);
        if (!hasBody(picked)) {
            return null;
        }
        String body = picked.contentMd();
        if (difficulty != Difficulty.INTERMEDIATE || file == null) {
            return body;
        }
        GeneratedDocumentItem beginner = file.document();
        // 심화편으로 못 가서 입문편을 읽는 날(옛 문서·심화편 실패)에는 붙일 것이 없다 —
        // 이미 그 절들이 본문 안에 있다. 여기서 또 붙이면 같은 글이 두 번 실린다.
        if (!hasBody(beginner) || beginner.contentMd().equals(body)) {
            return body;
        }
        String excerpt = excerptOf(beginner.contentMd());
        return excerpt.isEmpty() ? body : body + BEGINNER_EXCERPT_HEADER + excerpt;
    }

    /**
     * 입문편에서 중급 절만 오려 낸다. 없는 절은 조용히 건너뛴다 — 옛 문서에는 이름이 다르거나
     * 아예 없는데, 그렇다고 오늘 배치를 멈출 이유는 없다(있는 것만 붙이면 그만이다).
     *
     * <p>자르는 끝은 <b>다음 제목 줄</b>이다. {@code ### 왜 이렇게 설계됐는가}는 본론 절 안의
     * 소제목이라 {@code ##}까지 기다리면 그 뒤 본문을 통째로 끌고 온다.
     */
    private static String excerptOf(String beginnerBody) {
        StringBuilder sb = new StringBuilder();
        for (String heading : BEGINNER_SECTIONS_FOR_INTERMEDIATE) {
            int start = beginnerBody.indexOf("\n" + heading);
            if (start < 0) {
                continue;
            }
            start++; // 앞의 개행은 빼고 제목 줄부터
            Matcher next = NEXT_HEADING.matcher(beginnerBody);
            int end = next.find(start + heading.length()) ? next.start() : beginnerBody.length();
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(beginnerBody, start, end).append("\n");
        }
        return sb.toString().strip();
    }

    /** 다음 제목 줄({@code #}~{@code ###}) — 오려 낼 끝을 여기서 끊는다. */
    private static final Pattern NEXT_HEADING = Pattern.compile("(?m)^#{1,3} ");

    /** 칸은 있는데 본문이 빈 경우가 있다(심화편 생성만 실패한 날) — 그때도 없는 것으로 본다. */
    private static boolean hasBody(GeneratedDocumentItem item) {
        return item != null && item.contentMd() != null && !item.contentMd().isBlank();
    }
}
