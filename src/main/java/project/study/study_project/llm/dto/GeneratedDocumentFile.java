package project.study.study_project.llm.dto;

import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.client.GeneratedDocumentItem;

/**
 * 생성된 개념 문서 파일({@code generated/documents/YYYY-MM-DD.json})의 형태 — docs/15.
 *
 * <p>{@link GeneratedBatchFile}(문제)과 같은 역할이다. 클라우드에서 만들어 저장소에 커밋하고
 * 로컬 앱이 기동할 때 검수 대기 초안으로 흡수한다.
 *
 * <p><b>왜 하위 디렉터리에 두는가</b>: 문제 파일({@code generated/YYYY-MM-DD.json})과 이름이
 * 겹치지 않아야 하는데, 접두사로 구분하면({@code doc-2026-08-12.json}) 기존 문제 흡수 코드가
 * 그 파일까지 읽으려다 파싱에 실패한다 — 형식이 다르기 때문. 디렉터리를 나누면
 * {@code Files.list()}가 하위 디렉터리를 {@code .json} 필터에서 자연스럽게 걸러 주므로
 * <b>기존 코드를 한 줄도 안 고치고</b> 분리된다. 이름 규칙보다 구조로 나누는 쪽이 안전하다.
 *
 * @param note        파일을 직접 열어 본 사람을 위한 안내(기계는 읽지 않음)
 * @param date        생성 기준 날짜(한국 날짜) — 파일명과 같다
 * @param generatedAt 실제 생성 시각(ISO-8601, UTC)
 * @param domain      문서의 분야
 * @param model       실제 호출에 쓴 모델 ID — 초안에 기록해 품질 비교에 쓴다
 * @param document    모델이 반환한 문서(검증 전 원본)
 */
public record GeneratedDocumentFile(
        String note,
        String date,
        String generatedAt,
        Domain domain,
        String model,
        GeneratedDocumentItem document
) {
}
