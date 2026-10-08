package project.study.study_project.admin.audit;

import java.util.Map;

/**
 * 처리 기록의 주소 틀을 사람이 읽는 말로 바꾼다.
 *
 * <p>여기 없는 주소도 기록은 남는다 — 화면에는 주소 그대로 보인다.
 * 이름표가 빠졌다고 기록이 빠지면, 새 API를 만들 때마다 이 표를 고쳐야만 기록이 남는 구조가 된다.
 */
final class AdminActionLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("POST /api/admin/users/{id}/suspend", "사용자 정지"),
            Map.entry("POST /api/admin/users/{id}/unsuspend", "사용자 정지 해제"),
            Map.entry("POST /api/admin/users/{id}/reset-nickname", "닉네임 초기화"),
            Map.entry("POST /api/admin/users/{id}/role", "권한 변경"),
            Map.entry("DELETE /api/admin/users/{id}", "강제 탈퇴"),
            Map.entry("POST /api/admin/problems", "문제 등록"),
            Map.entry("PUT /api/admin/problems/{id}", "문제 수정"),
            Map.entry("DELETE /api/admin/problems/{id}", "문제 삭제"),
            Map.entry("POST /api/admin/problems/{id}/hide", "문제 내리기"),
            Map.entry("POST /api/admin/problems/{id}/show", "문제 올리기"),
            Map.entry("POST /api/admin/problems/{id}/revisions/{revisionId}/restore", "문제 되돌리기"),
            Map.entry("POST /api/admin/documents", "문서 등록"),
            Map.entry("PUT /api/admin/documents/{id}", "문서 수정"),
            Map.entry("DELETE /api/admin/documents/{id}", "문서 삭제"),
            Map.entry("POST /api/admin/documents/{id}/hide", "문서 내리기"),
            Map.entry("POST /api/admin/documents/{id}/show", "문서 올리기"),
            Map.entry("POST /api/admin/documents/{id}/revisions/{revisionId}/restore", "문서 되돌리기"),
            Map.entry("POST /api/admin/comments/{id}/hide", "댓글 가리기"),
            Map.entry("POST /api/admin/comments/{id}/restore", "댓글 되살리기"),
            Map.entry("POST /api/admin/posts/{id}/hide", "글 가리기"),
            Map.entry("POST /api/admin/posts/{id}/restore", "글 되살리기"),
            Map.entry("POST /api/admin/comment-reports/{id}/dismiss", "토론 신고 기각"),
            Map.entry("POST /api/admin/reports/{id}/accept", "문제 제보 인정"),
            Map.entry("POST /api/admin/reports/{id}/dismiss", "문제 제보 기각"),
            Map.entry("POST /api/admin/llm-problems/{id}/approve", "문제 초안 승인"),
            Map.entry("POST /api/admin/llm-problems/approve-batch", "문제 초안 일괄 승인"),
            Map.entry("POST /api/admin/llm-problems/{id}/reject", "문제 초안 거절"),
            Map.entry("POST /api/admin/llm-problems/{id}/restore", "문제 초안 복구"),
            Map.entry("POST /api/admin/llm-problems/generate", "문제 초안 생성"),
            Map.entry("POST /api/admin/llm-problems/generate-from-document", "문서로 문제 초안 생성"),
            Map.entry("POST /api/admin/llm-documents/{id}/approve", "문서 초안 승인"),
            Map.entry("POST /api/admin/llm-documents/{id}/reject", "문서 초안 거절"),
            Map.entry("POST /api/admin/llm-documents/{id}/restore", "문서 초안 복구"),
            Map.entry("POST /api/admin/problems/backfill-titles", "제목 채우기"),
            Map.entry("POST /api/admin/problems/backfill-rationales", "오답 설명 채우기")
    );

    private AdminActionLabels() {
    }

    static String of(String method, String pattern) {
        String key = method + " " + pattern;
        return LABELS.getOrDefault(key, key);
    }
}
