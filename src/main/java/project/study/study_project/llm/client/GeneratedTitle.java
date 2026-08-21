package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 제목 백필용 구조화 출력 스키마 — 이미 있는 문제에 목록 제목을 붙일 때만 쓴다(V13).
 *
 * <p><b>왜 문제 생성 스키마를 재사용하지 않았나.</b> {@link GeneratedProblemItem}은 지문·보기·해설을
 * 전부 필수로 요구한다. 백필은 <b>이미 있는 문제</b>에 이름만 붙이는 일이라 그것들을 다시 받으면
 * 모델이 멀쩡한 문제를 새로 써 버릴 여지가 생긴다. 받을 것을 딱 두 개로 좁혀 두면
 * 그 사고가 구조적으로 불가능하다.
 *
 * <p><b>{@code problemId}를 모델에게 돌려받는 것이 이 스키마의 핵심이다.</b> 순서만 믿고
 * 배열 인덱스로 짝지으면, 모델이 한 건을 빠뜨리거나 순서를 바꾼 순간 <b>전부 한 칸씩 밀린다</b> —
 * "TCP 3-way 핸드셰이크" 문제에 "인덱스가 안 타는 조건"이라는 제목이 붙고, 아무 오류도 나지 않는다.
 * 사람이 33건을 하나씩 대조해야만 발견되는 종류의 사고다. id를 함께 받으면 짝짓기가 어긋날 자리가
 * 없고, 모르는 id는 그냥 버리면 된다.
 */
@JsonClassDescription("기존 문제들에 붙일 목록 제목")
public record GeneratedTitle(

        @JsonPropertyDescription("제목을 붙일 문제의 id. 입력으로 준 번호를 그대로 돌려준다")
        long problemId,

        @JsonPropertyDescription("목록에 뜰 한 줄 제목. 무엇에 관한 문제인지를 명사구로 적는다"
                + "(예: TIME_WAIT가 쌓여 포트가 마르는 이유). 물음표로 끝나는 문장이 아니다. 40자 이내")
        String title
) {
    /** 구조화 출력의 최상위 스키마 — 목록을 감싸는 봉투(최상위는 객체여야 해서 필요). */
    public record Batch(
            @JsonPropertyDescription("문제별 제목 목록")
            List<GeneratedTitle> titles
    ) {
    }
}
