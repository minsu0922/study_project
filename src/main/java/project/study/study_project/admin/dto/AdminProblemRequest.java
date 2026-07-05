package project.study.study_project.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

import java.util.List;

/**
 * 관리자 문제 등록/수정 요청 바디.
 *
 * <p>검증이 두 겹인 이유:
 * <ul>
 *   <li><b>여기(애너테이션)</b>: 타입과 무관한 형식 규칙 — 필수값, 길이. 스프링이 자동 검사.
 *   <li><b>서비스(AdminProblemService)</b>: 타입에 따라 달라지는 규칙 — "객관식이면 보기 2개 이상 +
 *       정답 1개", "OX면 answer가 O/X" 등. 애너테이션은 "type 값에 따라 다른 필드의 규칙이 바뀌는"
 *       조건부 검증을 표현할 수 없어서 코드로 검사한다(QUIZ_004).
 * </ul>
 *
 * @param answer  객관식=비움(null), OX="O"/"X", 단답형=정답(복수는 | 구분) — docs/01 규칙 그대로
 * @param choices 객관식만 사용. 순서(seq)는 배열 순서대로 서버가 1..N 부여
 */
public record AdminProblemRequest(

        @NotNull(message = "domain은 필수입니다.")
        Domain domain,

        @NotNull(message = "difficulty는 필수입니다.")
        Difficulty difficulty,

        @NotNull(message = "type은 필수입니다.")
        ProblemType type,

        @NotBlank(message = "question은 필수입니다.")
        String question,

        @Size(max = 500, message = "answer는 500자 이하여야 합니다.") // DB VARCHAR(500)
        String answer,

        String explanation,

        @Valid // 중첩 객체(보기)의 애너테이션 검증까지 타고 들어가게 한다
        List<ChoiceItem> choices
) {
    /** 객관식 보기 입력 항목. */
    public record ChoiceItem(
            @NotBlank(message = "보기 내용은 필수입니다.")
            @Size(max = 500, message = "보기는 500자 이하여야 합니다.")
            String text,

            boolean correct
    ) {
    }
}
