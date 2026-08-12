package project.study.study_project.llm.dto;

import project.study.study_project.global.common.Domain;

import java.util.List;

/**
 * {@code generated/_existing-questions.json}의 형태 — docs/14.
 *
 * <p>로컬 앱({@code ExistingQuestionsExporter})이 쓰고 클라우드의 생성 CLI가 읽는다.
 * 클라우드에는 DB가 없어 "이미 어떤 문제가 있는지"를 알 방법이 이 파일뿐이다.
 *
 * <p><b>이 파일만 오랫동안 손으로 관리됐다.</b> 형제 격인 {@code _rejection-notes.json}과
 * {@code _existing-documents.json}은 처음부터 내보내기 러너가 있었는데 이것만 없었다.
 * 시드 마이그레이션을 걷어낼 때 이 파일에 <b>이미 삭제된 문제 24건</b>이 그대로 남아 있는 것을
 * 보고 드러났다 — 없는 문제를 피하라고 지시하면 그 주제로 새 문제를 영영 못 만든다.
 *
 * <p>{@code domain}을 함께 싣는 이유: CLI가 <b>그날 만들 분야의 지문만</b> 골라 프롬프트에
 * 넣는다. 분야가 없으면 전 분야 지문이 통째로 실려 입력 토큰만 늘고 정작 회피 정확도는 떨어진다.
 *
 * @param note       파일을 직접 열어 본 사람을 위한 안내(기계는 읽지 않음)
 * @param exportedAt 내보낸 날짜 — 날짜 단위로만 기록한다(시각까지 넣으면 매 부팅 파일이 바뀐다)
 * @param questions  정식 problem 테이블의 지문(분야별 최신 순)
 */
public record ExistingQuestionsFile(
        String note,
        String exportedAt,
        List<Item> questions
) {
    /** 지문 한 건. CLI의 {@code ExistingQuestion} record와 필드 이름이 같아야 읽힌다. */
    public record Item(Domain domain, String question) {
    }
}
