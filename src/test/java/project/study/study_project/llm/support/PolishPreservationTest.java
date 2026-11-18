package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.study_project.llm.support.PolishPreservation.differences;

/**
 * 윤문 전후 대조 — 하나라도 다르면 배치가 그 편의 원본을 쓴다(설계 2026-11-18 "보존 대조").
 */
class PolishPreservationTest {

    private static final String ORIGINAL = """
            # 이름 풀이 과정

            ## 무엇인가

            리졸버는 받은 답을 300초 동안 저장해 두고 다시 쓴다. 대부분의 이름은 1시간이 지나면 바뀐다.
            TTL이 1시간이면 1시간 전에 낮춘다.

            ```bash
            nslookup shop.example.com
            ```

            ![이름 나무](/images/docs/dns-tree.svg)

            자세한 내용은 [RFC 1034](https://www.rfc-editor.org/rfc/rfc1034)에 있다.

            ## 용어 한눈에

            | 용어 | 한 줄 뜻 |
            |---|---|
            | 리졸버 | 대신 묻는 서버 |
            | TTL | 저장해 둬도 되는 초 |

            ## 참고 자료

            - RFC 1035
            """;

    @Test
    @DisplayName("문장만 바뀌면 통과한다")
    void sentenceOnlyChangePasses() {
        String polished = ORIGINAL.replace("저장해 두고 다시 쓴다", "저장했다가 다시 쓴다");

        assertThat(differences(ORIGINAL, polished)).isEmpty();
    }

    @Test
    @DisplayName("마지막 절을 빠뜨리면 소제목 대조에서 걸린다")
    void missingSectionFails() {
        String polished = ORIGINAL.substring(0, ORIGINAL.indexOf("## 참고 자료"));

        assertThat(differences(ORIGINAL, polished)).anySatisfy(d -> assertThat(d).contains("소제목"));
    }

    @Test
    @DisplayName("본문을 마크다운 펜스로 감싼 응답은 걸린다")
    void fencedResponseFails() {
        assertThat(differences(ORIGINAL, "```markdown\n" + ORIGINAL + "\n```")).isNotEmpty();
    }

    @Test
    @DisplayName("앞에 안내문을 붙인 응답은 첫 줄 대조에서 걸린다")
    void prefacedResponseFails() {
        assertThat(differences(ORIGINAL, "다음은 윤문본입니다.\n\n" + ORIGINAL))
                .anySatisfy(d -> assertThat(d).contains("첫 줄"));
    }

    @Test
    @DisplayName("수치가 바뀌면 걸린다")
    void changedNumberFails() {
        assertThat(differences(ORIGINAL, ORIGINAL.replace("300초", "600초")))
                .anySatisfy(d -> assertThat(d).contains("수치"));
    }

    @Test
    @DisplayName("같은 수치를 되풀이한 문장을 합쳐 횟수만 줄면 통과한다")
    void fewerRepeatsOfSameNumberPass() {
        String polished = ORIGINAL.replace("TTL이 1시간이면 1시간 전에 낮춘다.", "TTL이 1시간이면 그만큼 먼저 낮춘다.");

        assertThat(differences(ORIGINAL, polished)).isEmpty();
    }

    @Test
    @DisplayName("코드 블록이 바뀌면 걸린다")
    void changedCodeFails() {
        assertThat(differences(ORIGINAL, ORIGINAL.replace("nslookup shop", "dig shop")))
                .anySatisfy(d -> assertThat(d).contains("코드"));
    }

    @Test
    @DisplayName("그림 줄이 바뀌면 걸린다")
    void changedFigureFails() {
        assertThat(differences(ORIGINAL, ORIGINAL.replace("![이름 나무]", "![이름 나무 그림]")))
                .anySatisfy(d -> assertThat(d).contains("그림"));
    }

    @Test
    @DisplayName("링크가 바뀌면 걸린다")
    void changedLinkFails() {
        assertThat(differences(ORIGINAL, ORIGINAL.replace("[RFC 1034](", "[RFC 문서](")))
                .anySatisfy(d -> assertThat(d).contains("링크"));
    }

    @Test
    @DisplayName("용어 표의 용어가 바뀌면 걸린다")
    void changedGlossaryTermFails() {
        assertThat(differences(ORIGINAL, ORIGINAL.replace("| 리졸버 |", "| 대신 묻는 서버 |")))
                .anySatisfy(d -> assertThat(d).contains("용어"));
    }

    @Test
    @DisplayName("조건어가 빠지면 걸린다")
    void droppedConditionWordFails() {
        assertThat(differences(ORIGINAL, ORIGINAL.replace("대부분의 이름은", "이름은")))
                .anySatisfy(d -> assertThat(d).contains("대부분"));
    }

    @Test
    @DisplayName("조건어 목록은 정도 부사 15개다")
    void conditionWordsAreFifteen() {
        assertThat(PolishPreservation.CONDITION_WORDS).hasSize(15).doesNotContain("경우");
    }
}
