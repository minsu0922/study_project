package project.study.study_project.bookmark.dto;

import java.util.List;

/** 물어본 id 가운데 내가 담아 둔 것만 추려 돌려준다. */
public record BookmarkState(List<Long> problemIds, List<Long> documentIds) {
}
