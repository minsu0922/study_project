package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 오답 설명 채우기용 구조화 출력 스키마 — 이미 승인된 문제의 <b>빈 오답 설명 칸</b>을 채울 때만 쓴다(V15).
 *
 * <p>{@link GeneratedTitle}과 같은 처방을 그대로 따른다. 이유도 같다.
 *
 * <h2>왜 문제 생성 스키마({@link GeneratedProblemItem})를 재사용하지 않나</h2>
 *
 * <p>저쪽은 지문·보기·해설을 전부 <b>필수로</b> 요구한다. 여기서 하는 일은 이미 있는 문제의
 * 빈 칸 하나를 채우는 것이라, 그것들을 다시 받으면 모델이 <b>멀쩡한 문제를 새로 써 버릴</b>
 * 여지가 생긴다. 실제로 그렇게 되기 쉽다 — 문제 전체를 보여 주면서 "고쳐라"가 아닌 지시를
 * 내리는 것은 모델에게 부자연스러운 요구이기 때문이다. 받을 것을 두 개로 좁혀 두면
 * 그 사고가 <b>구조적으로 불가능</b>하다. 지문을 새로 써 봐야 담을 자리가 없다.
 *
 * <h2>{@code choiceId}를 돌려받는 것이 이 스키마의 핵심이다</h2>
 *
 * <p>배열 순서로 짝지으면 모델이 한 보기를 빠뜨리거나 순서를 바꾼 순간 <b>전부 한 칸씩 밀린다</b>.
 * "쿠키가 자동으로 실린다"는 보기에 "토큰을 검증하지 않는다"는 설명이 붙고, <b>아무 오류도 나지
 * 않는다.</b> 화면은 그 어긋난 짝을 그대로 그리고, 학습자만 이상하다고 느낀다.
 *
 * <p>제목 백필 때는 이 사고가 문제 단위(33건)였지만 여기서는 <b>보기 단위</b>라 더 잘게 어긋난다.
 * 한 문제 안에서 두 오답의 설명이 서로 바뀌면 둘 다 그럴듯해 보여서 검수에서도 놓치기 쉽다.
 * 그래서 문제 id가 아니라 <b>보기 id</b>를 짝짓기 열쇠로 삼는다.
 *
 * @see project.study.study_project.llm.service.ChoiceRationaleBackfillService
 */
@JsonClassDescription("기존 문제의 오답 보기에 붙일 '왜 틀렸는지' 설명")
public record GeneratedRationale(

        @JsonPropertyDescription("설명을 붙일 보기의 id. 입력으로 준 번호를 그대로 돌려준다")
        long choiceId,

        @JsonPropertyDescription("이 보기가 왜 틀렸는지 한 줄. 어떤 오해에서 비롯되는 선택인지를 밝힌다. "
                + "다른 보기를 번호로 가리키지 마라 — 보기 순서는 학습자에게 나갈 때마다 다시 섞인다. "
                + "그 보기 하나만 놓고 읽어도 뜻이 통해야 한다")
        String rationale
) {
    /** 구조화 출력의 최상위 스키마 — 목록을 감싸는 봉투(최상위는 객체여야 해서 필요). */
    public record Batch(
            @JsonPropertyDescription("보기별 오답 설명 목록")
            List<GeneratedRationale> rationales
    ) {
    }
}
