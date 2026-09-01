package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.ProblemType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 근거 문서에 짝짓기 재료가 있는지 세는 규칙을 지킨다 — 2026-09-01 신설.
 *
 * <p>이 규칙이 지키려는 사고는 하나다: <b>표가 없는 문서로 짝짓기를 뽑으라고 시키면, 모델이
 * 문서 바깥의 제 지식으로 네 쌍을 지어낸다.</b> 요금은 다 나가고, 나온 문제는 "근거 문서를 읽고
 * 푼다"는 전제와 어긋난다. 그래서 <b>호출 전에</b> 막는다.
 *
 * <p>테스트가 문턱(4행) 자체보다 <b>세는 방법</b>에 집중하는 이유: 문턱은 프롬프트가 요구하는
 * 쌍 수를 따라 바뀔 수 있지만, "머리글을 데이터로 세지 않는다"·"코드블록을 표로 세지 않는다"가
 * 깨지면 검사가 조용히 무력해진다. 통과시키면 안 될 문서를 통과시키는 쪽이 훨씬 해롭다.
 */
class TypeMaterialRuleTest {

    /** 표 한 장짜리 문서를 만든다. {@code rows}는 <b>데이터 행</b> 수(머리글 제외). */
    private static String docWithGlossary(int rows) {
        StringBuilder md = new StringBuilder("""
                # 제목

                ## 무엇인가
                설명이다.

                ### 용어 한눈에

                | 용어 | 뜻 |
                |---|---|
                """);
        for (int i = 1; i <= rows; i++) {
            md.append("| 용어").append(i).append(" | 뜻").append(i).append(" |\n");
        }
        return md.toString();
    }

    @Nested
    @DisplayName("짝짓기 재료 판정")
    class Matching {

        @Test
        @DisplayName("표가 네 행이면 통과한다 — 프롬프트가 요구하는 쌍 수와 같다")
        void passesWithFourRows() {
            assertThat(TypeMaterialRule.missingMaterialOf(docWithGlossary(4), ProblemType.MATCHING))
                    .isNull();
        }

        @Test
        @DisplayName("세 행이면 막고, 사유에 실제 행 수를 담는다 — 검수자가 문서를 다시 안 열어도 알게")
        void blocksWithThreeRows() {
            String missing = TypeMaterialRule.missingMaterialOf(docWithGlossary(3), ProblemType.MATCHING);

            assertThat(missing).contains("3행").contains("4쌍");
        }

        @Test
        @DisplayName("표가 아예 없으면 막는다 — 08-11·08-12 같은 옛 문서가 이 경우다")
        void blocksWithoutTable() {
            String md = "# 제목\n\n## 무엇인가\n표 없이 문단만 있다.\n";

            assertThat(TypeMaterialRule.missingMaterialOf(md, ProblemType.MATCHING)).isNotNull();
        }

        @Test
        @DisplayName("두 절의 행을 합치되 겹쳐 세지 않는다 — 용어 표가 바탕 절 <안에> 놓인 문서")
        void sumsBothSectionsWithoutDoubleCounting() {
            String md = """
                    # 제목

                    ## 바탕이 되는 개념

                    | 계층 | 하는 일 |
                    |---|---|
                    | 물리 | 신호 |
                    | 링크 | 프레임 |

                    ### 용어 한눈에

                    | 용어 | 뜻 |
                    |---|---|
                    | 세션 | 대화 |
                    | 표현 | 형식 |
                    """;

            // 각각 2행이라 따로 보면 둘 다 미달인데, 합치면 4행이라 통과해야 한다.
            //
            // 여기서 7이 나오면 구역이 겹친 것이다 — '### 용어 한눈에'가 '## 바탕이 되는 개념'
            // 안에 있어 그 표를 양쪽에서 한 번씩 센 경우다. 합계가 부풀면 재료가 얇은 문서가
            // 통과하는데, 그게 이 검사가 막으려던 바로 그 경우라 조용히 무력해진다.
            assertThat(TypeMaterialRule.comparableRowsOf(md)).isEqualTo(4);
            assertThat(TypeMaterialRule.missingMaterialOf(md, ProblemType.MATCHING)).isNull();
        }
    }

    @Nested
    @DisplayName("세는 방법")
    class Counting {

        @Test
        @DisplayName("머리글과 구분선은 데이터 행이 아니다")
        void excludesHeaderAndDivider() {
            assertThat(TypeMaterialRule.comparableRowsOf(docWithGlossary(5))).isEqualTo(5);
        }

        @Test
        @DisplayName("코드블록 안의 파이프는 표가 아니다 — 로그 예시로 검사가 뚫리면 안 된다")
        void ignoresFencedCode() {
            String md = """
                    # 제목

                    ### 용어 한눈에

                    ```
                    | 이건 | 표가 |
                    |---|---|
                    | 아니라 | 코드다 |
                    | 로그 | 예시 |
                    | 네 | 줄 |
                    | 다섯 | 줄 |
                    ```
                    """;

            assertThat(TypeMaterialRule.comparableRowsOf(md)).isZero();
        }

        @Test
        @DisplayName("다른 절의 표는 세지 않는다 — 설정값 표까지 세면 재료 없는 문서가 통과한다")
        void ignoresOtherSections() {
            String md = """
                    # 제목

                    ## 실무에서는 이렇게 쓴다

                    | 설정 | 기본값 |
                    |---|---|
                    | a | 1 |
                    | b | 2 |
                    | c | 3 |
                    | d | 4 |
                    """;

            assertThat(TypeMaterialRule.comparableRowsOf(md)).isZero();
        }

        @Test
        @DisplayName("절 안의 소제목에서 끊기지 않는다 — 표가 소제목 뒤에 있어도 그 절의 몫이다")
        void keepsReadingPastSubHeadings() {
            String md = """
                    # 제목

                    ## 바탕이 되는 개념

                    ### 계층 표

                    | 계층 | 하는 일 |
                    |---|---|
                    | 물리 | 신호 |
                    | 링크 | 프레임 |
                    | 전송 | 포트 |
                    | 응용 | 요청 |

                    ## 왜 필요한가
                    여기부터는 다른 절이다.

                    | 세면 | 안 되는 표 |
                    |---|---|
                    | x | y |
                    """;

            assertThat(TypeMaterialRule.comparableRowsOf(md)).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("검사하지 않는 경우")
    class NotChecked {

        @Test
        @DisplayName("짝짓기가 아닌 유형은 통과시킨다 — 셀 수 있는 재료가 아직 없다")
        void otherTypesPass() {
            String bare = "# 제목\n\n표 없음\n";

            for (ProblemType type : ProblemType.values()) {
                if (type == ProblemType.MATCHING) {
                    continue;
                }
                assertThat(TypeMaterialRule.missingMaterialOf(bare, type))
                        .as("%s는 검사 대상이 아니다", type)
                        .isNull();
            }
        }

        @Test
        @DisplayName("문서가 없으면 막지 않는다 — 확신 없이 버리는 쪽이 더 나쁘다(폴백으로 도는 날)")
        void nullDocumentPasses() {
            assertThat(TypeMaterialRule.missingMaterialOf(null, ProblemType.MATCHING)).isNull();
        }
    }
}
