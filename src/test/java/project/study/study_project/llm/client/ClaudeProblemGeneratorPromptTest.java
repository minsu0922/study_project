package project.study.study_project.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문제 생성 프롬프트 조립 테스트 — 2단계 근거 문서 부분(docs/15).
 *
 * <p><b>Claude를 부르지 않는다.</b> 검증 대상은 "모델이 좋은 문제를 내는가"가 아니라
 * <b>우리가 준 재료가 실제로 프롬프트에 실리는가</b>다.
 *
 * <p>이 테스트가 지키는 것은 2단계 전체가 <b>조용히 무력해지는</b> 실패다. 근거 문서를 찾아
 * 읽는 데까지 성공해 놓고 프롬프트에 안 실으면, 모델은 그냥 평소처럼 자기 지식으로 문제를
 * 만들고 파일에는 {@code documentSlug}가 멀쩡히 박힌다 — 화면에는 "근거 문서: xss-and-csrf"
 * 링크까지 뜬다. 겉으로는 완벽히 동작하는데 실제로는 문서와 아무 상관 없는 문제인 상태다.
 */
class ClaudeProblemGeneratorPromptTest {

    private final ClaudeProblemGenerator generator = new ClaudeProblemGenerator("claude-opus-5");

    private static final SourceDocument DOC = new SourceDocument(
            "xss-and-csrf", "XSS와 CSRF — 브라우저를 믿으면 생기는 일",
            "# XSS와 CSRF\n\n## 왜 필요한가\n서버는 요청을 보낸 게 진짜 그 사용자인지 볼 수 없다.");

    @Test
    @DisplayName("근거 문서를 주면 본문 전문이 프롬프트에 실린다 — 안 실으면 모델은 평소대로 지어낸다")
    void includesSourceDocumentBody() {
        String prompt = prompt(Difficulty.BEGINNER, DOC);

        assertThat(prompt).contains("[근거 문서]");
        assertThat(prompt).as("제목만이 아니라 본문이 통째로 들어가야 한다")
                .contains("서버는 요청을 보낸 게 진짜 그 사용자인지 볼 수 없다");
        assertThat(prompt).contains(DOC.title());
    }

    @Test
    @DisplayName("'문서에 없는 것을 묻지 마라'와 '베껴 쓰지 마라'가 함께 실린다 — 한쪽만 있으면 반대쪽으로 망가진다")
    void constrainsToDocumentWithoutCopying() {
        String prompt = prompt(Difficulty.INTERMEDIATE, DOC);

        assertThat(prompt).as("범위를 닫지 않으면 학습자가 문서를 읽고도 못 푸는 문제가 나온다")
                .contains("문서에 없는 사실을 끌어와 문제를 내지 마라");
        assertThat(prompt).as("범위만 닫으면 이번엔 문장을 베껴 빈칸을 뚫는 문제가 나온다")
                .contains("그대로 베껴 빈칸을 뚫는 식은 금지");
    }

    /**
     * 같은 문서로 사흘 연속 문제를 만드는 구조라, 난이도 지시가 같으면 사흘 내내 비슷한 문제가 나온다.
     * 문서의 어느 층을 캘지가 난이도마다 실제로 달라야 그 사고를 막는다.
     */
    @Test
    @DisplayName("난이도마다 문서에서 캐낼 부분이 다르다 — 같으면 사흘 내내 비슷한 문제가 나온다")
    void picksDifferentPartOfDocumentPerDifficulty() {
        String beginner = prompt(Difficulty.BEGINNER, DOC);
        String intermediate = prompt(Difficulty.INTERMEDIATE, DOC);
        String advanced = prompt(Difficulty.ADVANCED, DOC);

        assertThat(beginner).contains("정의·용어 설명");
        assertThat(intermediate).contains("왜 이렇게 설계됐는가");
        assertThat(advanced).as("문서에 면접 질문 절을 넣어 둔 것이 여기서 회수된다")
                .contains("면접에서 이렇게 물어본다");

        assertThat(List.of(beginner, intermediate, advanced))
                .as("세 프롬프트가 서로 달라야 한다").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("근거 문서가 없으면 그 블록 자체를 넣지 않는다 — 빈 제목만 남으면 토큰 낭비이고 혼란스럽다")
    void omitsBlockWhenNoSourceDocument() {
        String prompt = prompt(Difficulty.BEGINNER, null);

        assertThat(prompt).doesNotContain("[근거 문서]");
        assertThat(prompt).doesNotContain("이번 난이도에서 쓸 부분");
        // 근거가 없어도 기본 조건은 그대로 실려야 한다
        assertThat(prompt).contains("분야:").contains("난이도:").contains("유형:");
    }

    private String prompt(Difficulty difficulty, SourceDocument source) {
        return generator.buildPrompt(Domain.SECURITY, difficulty, ProblemType.MULTIPLE_CHOICE,
                5, List.of(), List.of(), source);
    }
}
