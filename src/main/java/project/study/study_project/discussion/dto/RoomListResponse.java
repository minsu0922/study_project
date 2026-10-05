package project.study.study_project.discussion.dto;

import java.util.List;

/** 토론방 목록 한 쪽. */
public record RoomListResponse(boolean hasNext, List<RoomItem> rooms) {
}
