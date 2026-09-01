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
 * @param title        목록에 뜰 한 줄 제목(선택). 비우면 화면이 지문으로 대신 보여 준다 —
 *                     그래서 필수로 만들지 않았다. 다만 손으로 등록할 때도 붙여 두는 편이 낫다
 * @param answer       객관식=비움(null), OX="O"/"X", 단답형=정답(복수는 | 구분),
 *                     짝짓기=비움(정답이 보기 행에 있다), 순서 배열=정답 순서의 seq 나열("3|2|1|4")
 *                     — docs/01 규칙 그대로
 * @param choices      {@code choice} 행을 쓰는 유형이 사용한다({@code ProblemType.usesChoiceRows}) —
 *                     객관식은 보기, 짝짓기는 <b>쌍</b>(text+matchText), 순서 배열은 배열할 항목.
 *                     순서(seq)는 배열 순서대로 서버가 1..N 부여
 * @param documentSlug 근거가 된 개념 문서의 slug(선택). LLM 초안 승인 시 초안의 값이 그대로 넘어오고,
 *                     관리자가 손으로 등록할 때도 문서를 붙일 수 있다.
 *                     형식 정규식을 걸지 않은 이유: 존재하지 않는 slug를 적어도 손해가
 *                     "링크가 안 뜬다"뿐이라(V9 주석), 오타 하나로 등록을 막을 이유가 없다
 */
public record AdminProblemRequest(

        @NotNull(message = "domain은 필수입니다.")
        Domain domain,

        @NotNull(message = "difficulty는 필수입니다.")
        Difficulty difficulty,

        @NotNull(message = "type은 필수입니다.")
        ProblemType type,

        @Size(max = 120, message = "title은 120자 이하여야 합니다.") // DB VARCHAR(120)
        String title,

        @NotBlank(message = "question은 필수입니다.")
        String question,

        @Size(max = 500, message = "answer는 500자 이하여야 합니다.") // DB VARCHAR(500)
        String answer,

        String explanation,

        @Valid // 중첩 객체(보기)의 애너테이션 검증까지 타고 들어가게 한다
        List<ChoiceItem> choices,

        @Size(max = 150, message = "documentSlug는 150자 이하여야 합니다.") // DB VARCHAR(150)
        String documentSlug
) {
    /** 보기 입력 항목 — 객관식의 보기, 짝짓기의 한 쌍, 순서 배열의 한 항목이 모두 이 모양이다. */
    public record ChoiceItem(
            @NotBlank(message = "보기 내용은 필수입니다.")
            @Size(max = 500, message = "보기는 500자 이하여야 합니다.")
            String text,

            boolean correct,

            /**
             * 이 <b>오답</b>이 왜 틀렸는지 한 줄. 정답 보기는 비운다({@code Choice.rationale}).
             *
             * <p>{@code @NotBlank}를 걸지 않은 이유가 둘이다. 정답 보기는 <b>비어 있는 것이
             * 정상</b>이고, 이 필드가 생기기 전에 만들어진 초안은 승인될 때 값이 없다.
             * 여기서 막으면 옛 초안이 <b>승인 순간 400으로 튕긴다</b> — 검수함에 쌓인 것을
             * 못 내보내게 된다. 품질 기준은 {@code ProblemItemRule}이 경고로 알린다.
             */
            @Size(max = 1000, message = "오답 설명은 1000자 이하여야 합니다.") // DB VARCHAR(1000)
            String rationale,

            /**
             * 짝짓기의 <b>오른쪽</b> 항목 — 이 항목의 {@code text}와 짝이다(V16).
             * 그 밖의 유형에서는 비운다. 값이 잘못 들어오면 {@code AdminProblemService}가 막는다.
             *
             * <p><b>맨 뒤에 붙인 이유</b>: 이 저장소가 새 필드를 더할 때마다 쓰는 방법이다
             * ({@code Choice.rationale}, {@code GeneratedProblemItem.title}). 앞이나 가운데에
             * 끼우면 {@code new ChoiceItem(text, correct, rationale)} 자리가 전부 어긋나는데,
             * 그중에는 <b>인자 개수는 맞고 뜻만 바뀌는</b> 호출이 섞여 컴파일러가 잡아 주지 않는다.
             */
            @Size(max = 500, message = "짝 설명은 500자 이하여야 합니다.") // DB VARCHAR(500)
            String matchText
    ) {
        /**
         * 설명 없이 만드는 편의 생성자 — 이 필드가 생기기 전 코드·테스트가 그대로 컴파일되게 한다.
         * 수동 등록 화면도 아직 이 값을 보내지 않으므로 그 경로가 이 생성자를 탄다.
         */
        public ChoiceItem(String text, boolean correct) {
            this(text, correct, null, null);
        }

        /** 짝 없이(객관식·순서 배열) 만드는 편의 생성자 — 위와 같은 이유로 남긴다. */
        public ChoiceItem(String text, boolean correct, String rationale) {
            this(text, correct, rationale, null);
        }

        /** 짝짓기 한 쌍 — 정답 보기 개념이 없으므로 {@code correct}는 {@code false}로 고정한다. */
        public static ChoiceItem pair(String text, String matchText) {
            return new ChoiceItem(text, false, null, matchText);
        }
    }
}
