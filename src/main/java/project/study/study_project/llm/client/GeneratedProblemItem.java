package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Claude 구조화 출력(Structured Output)용 응답 스키마 — 문서 13.
 *
 * <p>이 record가 곧 "붕어빵 틀"이다: SDK가 record 정의에서 JSON 스키마를 자동 생성해
 * API에 보내고, 모델은 <b>이 스키마에 맞는 JSON만</b> 반환하도록 강제된다.
 * 그래서 "응답 형식이 매번 달라 파싱이 깨지는" 문제가 원천적으로 없다.
 *
 * <p>스키마 제약상 모든 필드가 필수(required)라 "값 없음"을 null 대신 빈 값으로 받는다:
 * 객관식의 answer는 빈 문자열 {@code ""}, OX/단답형의 choices는 빈 배열 {@code []}.
 * (구조화 출력은 nullable 필드 표현이 제한적이라, 프롬프트 규칙 + 서비스에서 정규화하는
 * 쪽이 스키마를 비트는 것보다 단순하다.) null 변환은 LlmProblemService가 담당.
 *
 * <p>{@code @JsonPropertyDescription}은 사람용 주석이 아니라 <b>모델에게 전달되는 필드 설명</b>이다
 * — 스키마에 description으로 포함되어 모델이 각 필드를 올바르게 채우도록 유도한다.
 */
@JsonClassDescription("CS 학습 퀴즈 문제 목록")
public record GeneratedProblemItem(

        @JsonPropertyDescription("문제 지문. 한국어. 코드가 필요하면 지문 안에 포함")
        String question,

        @JsonPropertyDescription("채점 기준값. 객관식이면 빈 문자열, OX면 O 또는 X, 단답형이면 정답(복수 정답은 |로 구분)")
        String answer,

        @JsonPropertyDescription("해설. 왜 정답인지 근거를 설명하고, 객관식이면 나머지 보기가 왜 틀렸는지도 포함")
        String explanation,

        @JsonPropertyDescription("객관식 보기 목록. 객관식이면 정확히 4개(정답 1개), 그 외 유형이면 빈 배열")
        List<GeneratedChoice> choices
) {
    /** 객관식 보기 한 개. */
    public record GeneratedChoice(
            @JsonPropertyDescription("보기 내용")
            String text,

            @JsonPropertyDescription("이 보기가 정답이면 true. 문제당 정확히 1개만 true")
            boolean correct
    ) {
    }

    /** 구조화 출력의 최상위 스키마 — 문제 배열을 감싸는 봉투(최상위는 객체여야 해서 필요). */
    public record Batch(
            @JsonPropertyDescription("생성된 문제 목록")
            List<GeneratedProblemItem> problems
    ) {
    }
}
