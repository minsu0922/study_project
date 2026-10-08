package project.study.study_project.discussion.dto;

/** 추천을 누르거나 거둔 뒤의 상태 — 화면이 숫자와 버튼을 다시 묻지 않고 바로 고친다. */
public record PostLikeState(long likeCount, boolean liked) {
}
