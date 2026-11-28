package project.study.study_project.admin.dto;

/** 문서를 주제에 잇는 요청. {@code topicId}가 null이면 연결을 푼다. */
public record AdminDocumentTopicRequest(Long topicId) {
}
