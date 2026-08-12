package project.study.study_project.llm.dto;

import java.util.List;

/**
 * {@code generated/_existing-documents.json}의 형태 — docs/15.
 *
 * <p>로컬 앱({@code ExistingDocumentsExporter})이 쓰고, 클라우드의 문서 생성 CLI가 읽는다.
 * 클라우드에는 DB가 없어서 "이미 어떤 문서가 있는지"를 알 방법이 이 파일뿐이다.
 *
 * <p>{@code _} 접두사는 흡수 대상(날짜 파일)과 섞이지 않기 위한 것이다
 * ({@link RejectionNotesFile}과 같은 규칙).
 *
 * @param note          파일을 직접 열어 본 사람을 위한 안내(기계는 읽지 않음)
 * @param exportedAt    내보낸 날짜. <b>시각까지 적지 않는</b> 이유는 아래 참고
 * @param titles        정식 문서 + 검수 대기 초안의 제목 — 주제 중복을 피하는 데 쓴다
 * @param tags          이미 쓰이는 태그 이름 — 뜻이 겹치는 태그가 새로 생기는 것을 막는다
 * @param rejectedSlugs 검수에서 <b>거절된</b> 문서의 slug(2단계). 배치가 문제를 만들 때
 *                      이 목록에 있는 문서는 근거로 쓰지 않는다 — 검수자가 "이건 아니다"라고
 *                      판단한 문서로 사흘간 문제를 만들면 그 사흘이 통째로 낭비이기 때문
 */
public record ExistingDocumentsFile(
        String note,
        String exportedAt,
        List<String> titles,
        List<String> tags,
        List<String> rejectedSlugs
) {
}
