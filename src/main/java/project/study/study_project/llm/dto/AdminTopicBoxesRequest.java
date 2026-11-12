package project.study.study_project.llm.dto;

import jakarta.validation.constraints.Size;

/**
 * 한 주제의 「글의 주제」 상자 두 편을 통째로 바꾸는 요청(V48).
 *
 * <p>분야·주제를 고치는 PATCH와 따로 둔 이유: 그 요청에 칸을 더하면 상자를 안 보내는
 * 기존 호출이 상자를 지운다. 이 요청은 두 칸이 곧 전부라 비운 칸은 "안 씀"으로 저장한다.
 *
 * @param beginner 입문편 상자 본문. 인용 기호 없이 문단만 쓴다
 * @param advanced 심화편 상자 본문
 */
public record AdminTopicBoxesRequest(
        @Size(max = 3000, message = "입문편 글의 주제는 3,000자를 넘을 수 없습니다.")
        String beginner,

        @Size(max = 3000, message = "심화편 글의 주제는 3,000자를 넘을 수 없습니다.")
        String advanced
) {
}
