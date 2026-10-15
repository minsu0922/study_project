package project.study.study_project.admin.dto;

/**
 * 관리 콘솔 메뉴 배지에 쓰는 개수 전부 — {@code GET /api/admin/badge-counts}의 응답(2026-10-15).
 *
 * <p>예전에는 화면을 열 때마다 개수 API 여덟 개를 따로 불렀다. 콘솔에서 화면을 몇 번 옮기면
 * 요청 한도(분당 60회)에 걸려, 한 번에 모아 준다.
 *
 * @param problems         검수 대기 문제 초안(개념 + 코드)
 * @param conceptProblems  그중 개념 문제 — 검수함 탭 숫자
 * @param codeProblems     그중 코드 문제 — 검수함 탭 숫자
 * @param documents        검수 대기 문서 초안
 * @param topics           개념 문서 주제 수. 0이면 배치가 모델에게 주제를 맡긴다
 * @param reports          대기 중인 문제 제보
 * @param commentReports   대기 중인 게시글 신고
 * @param errata           답이 안 달린 토론방 오류 지적 글
 * @param untitled         제목 없는 문제
 * @param missingRationale 오답 설명이 빠진 문제
 */
public record AdminBadgeCounts(long problems, long conceptProblems, long codeProblems, long documents,
                               long topics, long reports, long commentReports, long errata,
                               long untitled, long missingRationale) {
}
