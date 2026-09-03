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
 * <h2>2026-09-03 — 한 파일에 두 편</h2>
 *
 * <p>주제 한 편을 입문편·심화편으로 가르면서 칸이 하나 늘었다. <b>파일을 둘로 나누지 않은
 * 이유</b>는 근거 문서를 가리키는 유일한 통로가 <b>날짜</b>이기 때문이다
 * ({@code --document-date}). 파일을 나누면 이름 규칙이 하나 더 생기고, 그 규칙을 아는 곳과
 * 모르는 곳이 갈린다 — {@code --suffix}를 문서에 적용하지 않기로 한 것과 같은 판단이다.
 * 두 편은 같은 주기에 함께 태어나 함께 쓰이므로 한 봉투에 담는 편이 맞다.
 *
 * <p><b>{@code advancedDocument}는 없을 수 있다.</b> 2026-09-03 이전에 만든 파일 15개에는
 * 이 칸이 없고, 앞으로도 심화편 생성만 실패하는 날이 있을 수 있다. 읽는 쪽은 언제나
 * {@code null}을 각오해야 한다 — 없으면 고급 날에 입문편으로 폴백한다
 * ({@code DraftGeneratorCli.findSourceDocument}). 새 칸을 필수로 만들면 옛 파일 15개가
 * 통째로 파싱 실패하고, 그 순간 그 날짜들의 문제 생성이 조용히 폴백으로 떨어진다.
 *
 * @param note             파일을 직접 열어 본 사람을 위한 안내(기계는 읽지 않음)
 * @param date             생성 기준 날짜(한국 날짜) — 파일명과 같다
 * @param generatedAt      실제 생성 시각(ISO-8601, UTC)
 * @param domain           문서의 분야 — 두 편이 공유한다
 * @param model            실제 호출에 쓴 모델 ID — 초안에 기록해 품질 비교에 쓴다
 * @param document         입문편(검증 전 원본). 초급·중급 문제의 근거
 * @param advancedDocument 심화편(검증 전 원본). 고급 문제의 근거.
 *                         2026-09-03 이전 파일과 심화편 생성이 실패한 날에는 {@code null}
 */
public record GeneratedDocumentFile(
        String note,
        String date,
        String generatedAt,
        Domain domain,
        String model,
        GeneratedDocumentItem document,
        GeneratedDocumentItem advancedDocument
) {
}
