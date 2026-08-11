package project.study.study_project.llm.dto;

import project.study.study_project.llm.client.RejectionNote;

import java.util.List;

/**
 * 거절 사례 스냅샷 파일({@code generated/_rejection-notes.json})의 형태 — docs/14.
 *
 * <p>거절 사유는 로컬 DB에만 쌓이는데 문제를 만드는 곳은 GitHub Actions다. 중복 회피 목록에서
 * 겪은 것과 <b>똑같은 경계 문제</b>라 해법도 같다 — 로컬 앱이 파일로 내보내고
 * ({@code RejectionNotesExporter}), 저장소에 커밋된 그 파일을 배치가 읽는다
 * ({@code DraftGeneratorCli}).
 *
 * <p>{@code notes}의 타입으로 {@link RejectionNote}를 그대로 쓴다 — 파일 전용 DTO를 따로 만들면
 * 변환 코드가 늘고 언젠가 한쪽만 필드가 바뀐다({@code GeneratedBatchFile}이 생성 항목을
 * 재사용한 것과 같은 이유).
 *
 * <p>파일명이 밑줄로 시작하는 이유: 흡수 대상인 날짜 파일(2026-08-12.json)과 섞이면 안 된다.
 * {@code DraftImportRunner}와 {@code DraftGeneratorCli} 모두 {@code _} 접두사 파일을 건너뛴다.
 *
 * @param note       파일을 직접 열어 본 사람을 위한 안내(기계는 읽지 않음)
 * @param exportedAt 내보낸 날짜. 언제 기준의 피드백인지 사람이 가늠하는 용도
 * @param notes      거절 사례 목록(최신순)
 */
public record RejectionNotesFile(
        String note,
        String exportedAt,
        List<RejectionNote> notes
) {
}
