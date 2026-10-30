package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기열 메모를 모델용 작성 지시로 읽는 규칙 테스트(2026-10-29).
 *
 * <p>지키는 것은 두 가지다. 형식을 갖춘 메모만 모델에 실리고(옛 사람용 메모가 새지 않게),
 * 심화편에는 "설명할 것"이 빠진다(입문편과 같은 글이 또 나오지 않게).
 */
class TopicBriefTest {

    private static final String FULL = """
            핵심 개념: 인덱스, 풀 스캔, 정렬된 사본
            설명할 것: 인덱스가 없으면 DB는 어떻게 찾나 / 왜 쓰기는 느려지나
            다루지 않을 것: B+트리 구조
            실물: 50만 건에서 1.8초가 16ms로 줄었다
            """;

    @Test
    @DisplayName("이름표가 붙은 네 칸을 읽는다")
    void parsesLabeledLines() {
        TopicBrief brief = TopicBrief.parse(FULL);

        assertThat(brief).isNotNull();
        assertThat(brief.concepts()).isEqualTo("인덱스, 풀 스캔, 정렬된 사본");
        assertThat(brief.questions()).isEqualTo("인덱스가 없으면 DB는 어떻게 찾나 / 왜 쓰기는 느려지나");
        assertThat(brief.outOfScope()).isEqualTo("B+트리 구조");
        assertThat(brief.facts()).isEqualTo("50만 건에서 1.8초가 16ms로 줄었다");
    }

    @Test
    @DisplayName("필수 두 칸 중 하나라도 없으면 지시가 아니다 — 옛 사람용 메모는 모델에 싣지 않는다")
    void rejectsMemoWithoutRequiredLabels() {
        assertThat(TopicBrief.parse("정보처리기사 대비. 증상을 붙였다")).isNull();
        assertThat(TopicBrief.parse("핵심 개념: 인덱스")).isNull();
        assertThat(TopicBrief.parse(null)).isNull();
        assertThat(TopicBrief.parse("  ")).isNull();
    }

    @Test
    @DisplayName("선택 칸은 비어도 된다")
    void optionalLinesMayBeMissing() {
        TopicBrief brief = TopicBrief.parse("핵심 개념: 기본키\n설명할 것: 왜 번호가 필요한가");

        assertThat(brief.outOfScope()).isNull();
        assertThat(brief.facts()).isNull();
        assertThat(brief.forAdvanced()).doesNotContain("다루지 않을 것").doesNotContain("실물");
    }

    @Test
    @DisplayName("뒤 편 주제는 심화편에만 실린다 — 질문 목록이 없는 심화편이 뒤 주제로 넓어지지 않게")
    void laterTopicsGoToAdvancedOnly() {
        TopicBrief brief = TopicBrief.parse(FULL).withLaterTopics(java.util.List.of("B+트리 인덱스의 생김새", "트랜잭션"));

        assertThat(brief.forAdvanced()).contains("뒤 편이 다룰 주제: B+트리 인덱스의 생김새, 트랜잭션");
        assertThat(brief.forBeginner()).doesNotContain("뒤 편이 다룰 주제");
    }

    @Test
    @DisplayName("입문편에는 네 칸이 모두, 심화편에는 '설명할 것'만 빠지고 실린다")
    void advancedOmitsQuestions() {
        TopicBrief brief = TopicBrief.parse(FULL);

        assertThat(brief.forBeginner())
                .contains("핵심 개념: 인덱스").contains("설명할 것: ")
                .contains("다루지 않을 것: B+트리").contains("실물: 50만");
        assertThat(brief.forAdvanced())
                .contains("핵심 개념: 인덱스").contains("다루지 않을 것: B+트리").contains("실물: 50만")
                .doesNotContain("설명할 것");
    }
}
