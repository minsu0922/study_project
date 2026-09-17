package project.study.study_project.llm.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제목 형식 판정 테스트 — 문서 제목과 문제 제목이 같은 잣대를 쓰는 자리다(2026-09-17).
 *
 * <p><b>여기서 진짜로 지키는 것은 "멀쩡한 제목을 잡지 않는 것"이다.</b> 이 판정은 경고를
 * 만들고, 경고는 오탐이 섞이는 순간 통째로 무력해진다 — 사람이 매번 무시하는 습관이 생기고
 * 그때부터 진짜 문제도 지나친다. 그래서 잡아야 할 것만큼이나 <b>잡으면 안 되는 것</b>에
 * 예시를 들였다("선택지 하나", "안정성 평가", "3-way 핸드셰이크").
 */
class TitleStyleRuleTest {

    @Test
    @DisplayName("의문형 어미로 끝나면 물음으로 본다 — 물음표 없는 의문문이 그동안 새어 나갔다")
    void detectsQuestionForms() {
        List<String> questions = List.of(
                "데이터베이스가 지금 어느 단계까지 바뀌어 있는지를 무엇으로 아는가",
                "스레드를 하나 더 만들면 메모리에 무엇이 새로 생기는가",
                "이 상황의 원인으로 가장 적절한 것은?",
                "락을 먼저 풀어야 할까",
                "정말 그 값이 맞나요");

        assertThat(questions).allSatisfy(title -> assertThat(TitleStyleRule.isQuestionForm(title))
                .as("\"%s\"은 물음 꼴이다", title).isTrue());
    }

    /**
     * 이 목록이 이 클래스의 존재 이유에 가깝다. 어미를 넓게 잡고 싶어질 때 무엇이 함께
     * 걸리는지가 여기 적혀 있다 — {@code 나$}는 "하나"를, {@code 가$}는 "평가"를 끌고 온다.
     */
    @Test
    @DisplayName("명사구 제목은 잡지 않는다 — 오탐이 섞이면 경고 전체가 무력해진다")
    void leavesNounPhrasesAlone() {
        List<String> fine = List.of(
                "데이터베이스 마이그레이션 도구의 버전 관리 기법 (Flyway)",
                "장애 대응 절차에서 가장 먼저 보는 것 하나",
                "정렬 알고리즘의 안정성 평가",
                "static 필드에 여러 쓰레드가 동시에 쓸 때의 값 유실",
                "커넥션 풀이 고갈될 때의 대기 동작 (HikariCP)");

        assertThat(fine).allSatisfy(title -> {
            assertThat(TitleStyleRule.isQuestionForm(title))
                    .as("\"%s\"은 물음이 아니다", title).isFalse();
            assertThat(TitleStyleRule.hasDashSubtitle(title))
                    .as("\"%s\"에는 줄표 부연이 없다", title).isFalse();
        });
    }

    @Test
    @DisplayName("줄표 부연은 앞뒤 어느 쪽이 본체든 잡는다 — 줄표가 붙는 자리가 곧 부연이다")
    void detectsDashSubtitles() {
        assertThat(TitleStyleRule.hasDashSubtitle("캐시 쓰기 전략 — TTL은 시간으로, 무효화는 사건으로")).isTrue();
        assertThat(TitleStyleRule.hasDashSubtitle("TIME_WAIT – 2MSL을 더 기다리는 이유")).isTrue();
        assertThat(TitleStyleRule.hasDashSubtitle("커넥션 풀 고갈 - 대기와 타임아웃")).isTrue();
    }

    @Test
    @DisplayName("낱말 안의 붙임표는 부연이 아니다 — 3-way 핸드셰이크를 잡으면 안 된다")
    void allowsHyphenInsideWords() {
        assertThat(TitleStyleRule.hasDashSubtitle("TCP 3-way 핸드셰이크의 연결 수립 절차")).isFalse();
        assertThat(TitleStyleRule.hasDashSubtitle("Write-Through 방식의 쓰기 경로")).isFalse();
    }

    /**
     * 빈 제목을 여기서 판정하지 않는 이유: 부르는 쪽이 이미 "제목 없음"으로 잡는다
     * ({@code ProblemItemRule}, {@code DocumentDraftValidator}). 여기서 또 울리면 한 결함에
     * 메시지가 둘 뜬다.
     */
    @Test
    @DisplayName("빈 제목은 판정하지 않는다 — 부르는 쪽이 이미 잡는다")
    void ignoresBlankTitles() {
        assertThat(TitleStyleRule.isQuestionForm(null)).isFalse();
        assertThat(TitleStyleRule.isQuestionForm("")).isFalse();
        assertThat(TitleStyleRule.hasDashSubtitle(null)).isFalse();
        assertThat(TitleStyleRule.hasDashSubtitle("   ")).isFalse();
    }
}
