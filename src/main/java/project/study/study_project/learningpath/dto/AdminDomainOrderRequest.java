package project.study.study_project.learningpath.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 분야 코스 순서 저장. 그 분야 문서 id를 원하는 순서대로 보낸다.
 * 일부만 보내면 나머지는 기본 규칙으로 뒤에 붙는다. 빈 목록이면 기본 순서로 되돌린다.
 */
public record AdminDomainOrderRequest(@NotNull @Size(max = 200) List<Long> documentIds) {
}
