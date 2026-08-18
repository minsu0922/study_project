package project.study.study_project.llm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;

/**
 * 업로드 문서 기반 문제 생성 요청 — 2026-08-18 신설.
 *
 * <p>파일은 {@code multipart/form-data}의 별도 파트로 오므로 이 record에 없다. 여기 있는 것은
 * 나머지 폼 필드다.
 *
 * <p><b>{@link LlmGenerateRequest}와 갈라 둔 이유</b>: 분야·난이도가 <b>필수</b>다.
 * 기존 요청은 비워 두면 "가장 부족한 칸"을 자동으로 골라 주는데, 업로드에 그 규칙을 그대로 쓰면
 * <b>올린 문서 주제와 엉뚱한 분야</b>가 붙는다. 배치에서 이미 겪어 {@code alignDomainWithDocument}로
 * 막아 둔 함정인데, 거기서는 문서 파일에 분야가 기록돼 있어 맞출 수 있었다. 올린 파일에는
 * 그런 기록이 없으니 <b>사람이 정하는 수밖에 없다</b> — 파일을 보고 올리는 상황이라 어렵지도 않다.
 *
 * <p>같은 이유로 {@code @NotNull}을 붙였다. 필수라는 사실이 서비스 코드 안쪽에만 있으면
 * 화면에서 안 채우고 보낸 요청이 500이나 엉뚱한 결과로 나타난다.
 *
 * @param domain     분야 — 필수. 자동 선택 없음(위 설명)
 * @param difficulty 난이도 — 필수
 * @param type       유형 — null이면 객관식. 서술형은 서비스에서 거부(QUIZ_002)
 * @param count      생성 개수 — 상한 10. 기존 수동 생성과 같은 값으로 맞춘다
 * @param text       붙여넣기 본문. 파일 대신 쓰는 입구이고, 파일과 <b>동시에</b> 오면 거부한다
 */
public record LlmDocumentGenerateRequest(

        @NotNull(message = "분야를 선택해 주세요. 업로드 생성은 분야를 자동으로 고르지 않습니다.")
        Domain domain,

        @NotNull(message = "난이도를 선택해 주세요.")
        Difficulty difficulty,

        ProblemType type,

        @Min(value = 1, message = "count는 1 이상이어야 합니다.")
        @Max(value = 10, message = "count는 10 이하여야 합니다.")
        int count,

        String text
) {
}
