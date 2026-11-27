package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemTypeHintsTest {

    private static String summary(String md) {
        return ProblemTypeHints.of(md).stream()
                .map(h -> h.type() + ":" + h.count() + ":" + h.sections())
                .reduce((a, b) -> a + " / " + b).orElse("");
    }

    @Test
    @DisplayName("4단계 이상 번호 목록은 순서 배열 재료이고, 절 이름은 목록 위 소제목이다")
    void orderingNeedsFourSteps() {
        String md = """
                ## 짧은 목록

                1. 가
                2. 나
                3. 다

                ## 만드는 순서

                1. 가

                2. 나
                3. 다
                4. 라
                ## 다음 절
                """;

        assertThat(summary(md)).isEqualTo("ORDERING:1:[만드는 순서]");
    }

    @Test
    @DisplayName("한 절에 목록이 둘이면 둘로 세고 절 이름은 한 번만 적는다")
    void twoListsInOneSection() {
        String md = "## 절\n\n1. a\n2. b\n3. c\n4. d\n\n사이 문단\n\n1. a\n2. b\n3. c\n4. d\n";

        assertThat(summary(md)).isEqualTo("ORDERING:2:[절]");
    }

    @Test
    @DisplayName("코드 블록 안의 번호 줄과 표는 세지 않는다")
    void ignoresCodeBlocks() {
        String md = "## 예제\n\n```\n1. a\n2. b\n3. c\n4. d\n```\n";

        assertThat(ProblemTypeHints.of(md)).isEmpty();
    }

    @Test
    @DisplayName("「용어 한눈에」 표는 머리 행과 구분 행을 빼고 4행 이상이면 짝짓기 재료다")
    void matchingCountsGlossaryRows() {
        String md = """
                ## 용어 한눈에

                | 용어 | 한 줄 뜻 |
                |---|---|
                | 서버 | 요청에 답하는 컴퓨터 |
                | 인스턴스 | 가상 서버 한 대 |
                | 중지 | CPU·메모리를 돌려준다 |
                | 종료 | 영구히 없앤다 |

                ## 다른 절

                | 항목 | 값 |
                |---|---|
                | a | b |
                """;

        assertThat(summary(md)).isEqualTo("MATCHING:4:[용어 한눈에]");
    }

    @Test
    @DisplayName("3행짜리 용어 표는 짝짓기로 치지 않는다")
    void smallGlossaryIsNotMatching() {
        String md = "### 용어 한눈에\n\n| 용어 | 뜻 |\n|---|---|\n| a | 1 |\n| b | 2 |\n| c | 3 |\n";

        assertThat(ProblemTypeHints.of(md)).isEmpty();
    }

    @Test
    @DisplayName("오해 절의 줄 그은 소제목과 굵은 따옴표 줄을 OX 재료로 센다")
    void oxCountsBothMythFormats() {
        String md = """
                ## 자주 하는 오해

                **"CPU를 안 쓰면 요금도 안 나온다."**

                설명.

                ### ~~"중지하면 0원이다"~~

                설명.

                ## 심화편 정리

                **"이건 오해 절 밖이다."**
                """;

        assertThat(summary(md)).isEqualTo("OX:2:[자주 하는 오해]");
    }

    @Test
    @DisplayName("심화편의 「흔한 오해」 소절도 하위 소제목까지 센다")
    void advancedMythSubsection() {
        String md = "### 흔한 오해\n\n#### ~~\"하나\"~~\n\n본문\n\n#### ~~\"둘\"~~\n\n본문\n\n## 다음\n";

        assertThat(summary(md)).isEqualTo("OX:2:[흔한 오해]");
    }

    @Test
    @DisplayName("빈 본문과 재료 없는 본문은 빈 목록이다")
    void emptyWhenNothing() {
        assertThat(ProblemTypeHints.of(null)).isEmpty();
        assertThat(ProblemTypeHints.of("## 절\n\n문단만 있다.")).isEqualTo(List.of());
    }
}
