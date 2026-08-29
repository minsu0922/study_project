package project.study.study_project.llm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.llm.client.QuestionKind;
import project.study.study_project.llm.support.GenerationLimits;

/**
 * 문서 기반 문제 생성 요청 — 2026-08-18 신설, 2026-08-25에 입구가 셋이 됐다.
 *
 * <p>파일은 {@code multipart/form-data}의 별도 파트로 오므로 이 record에 없다. 여기 있는 것은
 * 나머지 폼 필드다.
 *
 * <p><b>입구가 셋인데 필드는 왜 한 record에 몰려 있나</b>(2026-08-25): {@code file}·{@code text}·
 * {@code slug}는 "무엇을 근거로 삼을지"를 말하는 <b>서로 배타적인 세 가지 방법</b>이고, 그 뒤로는
 * 전부 같은 {@link project.study.study_project.llm.client.SourceDocument} 하나로 합쳐져 흐른다.
 * 요청 DTO를 셋으로 쪼개면 분야·난이도·개수 검증이 세 곳에 복제되고, 언젠가 한쪽에만 규칙이
 * 붙는다 — 컨트롤러가 엔드포인트를 하나로 둔 것과 같은 판단이다.
 *
 * <p>"셋 중 정확히 하나"는 Bean Validation으로 잡지 않고 컨트롤러가 잡는다. {@code file}이
 * multipart 파트라 이 record 밖에 있어, 여기에 클래스 수준 제약을 걸어도 <b>셋 중 둘만</b> 볼 수
 * 있기 때문이다. 반쪽짜리 검증을 두면 "여기서 막아 주겠지"라는 오해가 생긴다.
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
 * @param slug       <b>이미 등록된 문서</b>의 slug — 셋째 입구(2026-08-25). 이 경로로 만든 문제만
 *                   초안에 근거 slug가 기록된다(올린 파일은 갈 곳이 없어 비운다)
 * @param questionKind 묻는 형태를 <b>지목</b>한다(2026-08-25). {@code null}이면 모델이 고른다.
 *                   중급에 다섯 형태를 열었더니 실물이 세 번 연속 상황형으로만 나와서 붙였다 —
 *                   재료가 가장 풍부한 쪽으로 모델이 늘 돌아가므로, 다른 형태는 사람이 지목해야 한다.
 *                   검수 뒤 한두 건을 메워 넣을 때 특히 그렇다(개수가 적으면 섞을 여지가 없다)
 */
public record LlmDocumentGenerateRequest(

        @NotNull(message = "분야를 선택해 주세요. 업로드 생성은 분야를 자동으로 고르지 않습니다.")
        Domain domain,

        @NotNull(message = "난이도를 선택해 주세요.")
        Difficulty difficulty,

        ProblemType type,

        // 상한·하한의 출처는 {@link GenerationLimits} 하나다(LlmGenerateRequest와 같은 이유).
        @Min(value = GenerationLimits.MIN_COUNT, message = "count는 " + GenerationLimits.MIN_COUNT + " 이상이어야 합니다.")
        @Max(value = GenerationLimits.MAX_COUNT, message = "count는 " + GenerationLimits.MAX_COUNT + " 이하여야 합니다.")
        int count,

        String text,

        String slug,

        QuestionKind questionKind
) {
}
