package project.study.study_project.llm.support;

import org.junit.jupiter.api.Test;
import project.study.study_project.llm.client.GeneratedProblemItem;
import project.study.study_project.llm.client.GeneratedProblemItem.GeneratedChoice;
import project.study.study_project.llm.support.ExplanationFormRule.FormIssue;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.study_project.llm.support.ExplanationFormRule.issuesOf;

class ExplanationFormRuleTest {

    private static final String ADV_SLUG = "port-number-program-demultiplexing-advanced";
    private static final String ADV_MD = """
            # 포트 번호
            ## 리슨 소켓 하나가 연결 수천 개를 받는 방식
            본문
            ## ~~어떤 때 통하지 않는가~~
            본문
            """;
    private static final String ADV_MD_WITH_EXCERPT = ADV_MD + DocumentEditionRule.BEGINNER_EXCERPT_HEADER
            + "## 실무에서는 이렇게 쓴다\n본문\n";

    private static final String BODY = """
            규칙: 연결 하나는 내 IP 주소·내 포트·상대 IP 주소·상대 포트 네 값으로 갈린다. 네 값 중 하나만 달라도 다른 연결이다.
            그림의 두 연결을 서버 쪽에서 적어 보자.
            첫 탭 연결: 서버 IP·443, 상대 IP는 PC 주소, 상대 포트 51000.
            둘째 탭 연결: 서버 IP·443, 상대 IP는 같은 PC 주소, 상대 포트 51001.
            네 값을 견준다: 서버 쪽 두 값과 상대 IP는 같고, 상대 포트만 다르다.
            그래서 두 연결을 가르는 값은 접속한 쪽의 포트 번호다.
            """;

    private static String withPointer(String edition, String section) {
        return BODY + "(%s의 '%s' 절을 다시 읽어 보라)".formatted(edition, section);
    }

    private static GeneratedProblemItem item(String explanation) {
        return item(explanation, "서버 쪽 포트는 두 연결 모두 443으로 같아 이 값으로는 두 연결을 가르지 못한다.");
    }

    private static GeneratedProblemItem item(String explanation, String thirdRationale) {
        return new GeneratedProblemItem("두 연결을 가르는 값은?", "", explanation, List.of(
                new GeneratedChoice("상대 포트", true, ""),
                new GeneratedChoice("서버 포트", false, "서버 쪽 포트는 두 연결 모두 443으로 같아 이 값으로는 가르지 못한다."),
                new GeneratedChoice("IP 주소", false, thirdRationale),
                new GeneratedChoice("도착 순서", false, "운영체제는 순서가 아니라 패킷에 적힌 네 값으로 연결을 찾는다.")));
    }

    private static List<String> criteria(GeneratedProblemItem item, String slug, String md) {
        return issuesOf(item, slug, md).stream().map(FormIssue::criterion).toList();
    }

    private static final String GOOD = withPointer("심화편", "리슨 소켓 하나가 연결 수천 개를 받는 방식");

    @Test
    void 형식을_다_지킨_해설은_걸리지_않는다() {
        assertThat(issuesOf(item(GOOD), ADV_SLUG, ADV_MD)).isEmpty();
    }

    @Test
    void 줄_그은_절_제목도_찾는다() {
        assertThat(criteria(item(withPointer("심화편", "어떤 때 통하지 않는가")), ADV_SLUG, ADV_MD)).isEmpty();
    }

    @Test
    void 문서에_없는_절을_안내하면_A4() {
        assertThat(criteria(item(withPointer("심화편", "없는 절")), ADV_SLUG, ADV_MD)).containsExactly("A4");
    }

    @Test
    void 근거와_다른_편을_안내하면_A4() {
        assertThat(criteria(item(withPointer("입문편", "리슨 소켓 하나가 연결 수천 개를 받는 방식")), ADV_SLUG, ADV_MD))
                .containsExactly("A4");
    }

    @Test
    void 입문편_발췌의_절을_안내하면_통과() {
        assertThat(criteria(item(withPointer("입문편", "실무에서는 이렇게 쓴다")), ADV_SLUG, ADV_MD_WITH_EXCERPT))
                .isEmpty();
    }

    @Test
    void 절_이름_뒤에_대목을_덧붙인_안내도_받는다() {
        String detailed = BODY + "(심화편의 '리슨 소켓 하나가 연결 수천 개를 받는 방식' 절의 네 값 표를 다시 읽어 보라)";
        assertThat(criteria(item(detailed), ADV_SLUG, ADV_MD)).isEmpty();
    }

    @Test
    void 편을_나누기_전_문서의_안내는_본문_전체에서_찾는다() {
        String old = BODY + "(문서의 '실무에서는 이렇게 쓴다' 절에서 예시를 다시 읽어 보라)";
        assertThat(criteria(item(old), ADV_SLUG, ADV_MD_WITH_EXCERPT)).isEmpty();
    }

    @Test
    void 걸음_중간의_그래서는_맺음으로_보지_않는다() {
        String mid = GOOD.replace("네 값을 견준다:", "그래서 네 값을 견준다:");
        assertThat(criteria(item(mid), ADV_SLUG, ADV_MD)).isEmpty();
    }

    @Test
    void 부제가_붙은_절은_앞부분_이름으로_찾는다() {
        String md = ADV_MD + "## 바탕이 되는 개념 — 소켓은 무엇인가\n본문\n";
        assertThat(criteria(item(withPointer("심화편", "바탕이 되는 개념")), ADV_SLUG, md)).isEmpty();
    }

    @Test
    void 안내_줄이_없으면_A4() {
        assertThat(criteria(item(BODY), null, null)).containsExactly("A4");
    }

    @Test
    void 근거_문서가_없으면_절이_있는지는_보지_않는다() {
        assertThat(criteria(item(withPointer("심화편", "없는 절")), null, null)).isEmpty();
    }

    @Test
    void 규칙으로_시작하지_않으면_B1() {
        assertThat(criteria(item(GOOD.replaceFirst("규칙: ", "정답은 ")), ADV_SLUG, ADV_MD)).containsExactly("B1");
    }

    @Test
    void 걸음이_둘뿐이면_B3() {
        String two = """
                규칙: 연결 하나는 내 IP 주소·내 포트·상대 IP 주소·상대 포트 네 값으로 갈린다. 네 값 중 하나만 달라도 다른 연결이다. 두 탭은 같은 PC에서 같은 서버로 접속한다.
                첫 탭 연결: 서버 IP·443, 상대 IP는 PC 주소, 상대 포트 51000으로 서버에 닿는다.
                둘째 탭 연결: 서버 IP·443, 상대 IP는 같은 PC 주소, 상대 포트 51001로 서버에 닿는다.
                그래서 두 연결을 가르는 값은 접속한 쪽의 포트 번호다.
                (심화편의 '리슨 소켓 하나가 연결 수천 개를 받는 방식' 절을 다시 읽어 보라)""";
        assertThat(criteria(item(two), ADV_SLUG, ADV_MD)).containsExactly("B3");
    }

    @Test
    void 그래서로_맺지_않으면_B5() {
        String noSo = GOOD.replace("그래서 두 연결을 가르는 값은", "결국 두 연결을 가르는 값은");
        assertThat(criteria(item(noSo), ADV_SLUG, ADV_MD)).containsExactly("B5");
    }

    @Test
    void 그래서_뒤에_다른_줄이_있으면_B5() {
        String after = GOOD.replace("(심화편의", "덧붙여 포트는 운영체제가 고른다.\n(심화편의");
        assertThat(criteria(item(after), ADV_SLUG, ADV_MD)).containsExactly("B5");
    }

    @Test
    void 오답_설명이_짧으면_C2_보기_위치를_적는다() {
        List<FormIssue> issues = issuesOf(item(GOOD, "틀렸다"), ADV_SLUG, ADV_MD);
        assertThat(issues).extracting(FormIssue::criterion).containsExactly("C2");
        assertThat(issues.get(0).message()).contains("3");
    }

    @Test
    void 해설이_150자_미만이면_C4() {
        String shortOne = """
                규칙: 연결은 네 값으로 갈린다.
                첫 탭: 51000.
                둘째 탭: 51001.
                견준다: 포트만 다르다.
                그래서 상대 포트다.
                (심화편의 '리슨 소켓 하나가 연결 수천 개를 받는 방식' 절을 다시 읽어 보라)""";
        assertThat(criteria(item(shortOne), ADV_SLUG, ADV_MD)).containsExactly("C4");
    }

    @Test
    void 해설이_비면_해설_기준은_보지_않는다() {
        assertThat(criteria(item(""), ADV_SLUG, ADV_MD)).isEmpty();
    }

    @Test
    void passes는_걸린_것이_없을_때만_참() {
        assertThat(ExplanationFormRule.passes(item(GOOD), ADV_SLUG, ADV_MD)).isTrue();
        assertThat(ExplanationFormRule.passes(item(GOOD, "틀렸다"), ADV_SLUG, ADV_MD)).isFalse();
    }
}
