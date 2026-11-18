package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 윤문 응답 스키마. 제목·식별자·태그는 윤문 대상이 아니라 본문만 받는다. */
@JsonClassDescription("윤문한 개념 문서")
public record PolishedDocument(
        @JsonPropertyDescription("윤문한 마크다운 본문 전체. 첫 줄은 원문과 같은 '# 제목'이다")
        String contentMd
) {
}
