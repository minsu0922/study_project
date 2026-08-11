package project.study.study_project.llm.client;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Claude 구조화 출력용 개념 문서 스키마 — 설계 docs/15.
 *
 * <p>{@link GeneratedProblemItem}과 같은 원리다: 이 record가 곧 응답의 "붕어빵 틀"이고,
 * SDK가 여기서 JSON 스키마를 만들어 API에 보내므로 모델은 이 형태로만 답할 수 있다.
 * 그래서 파싱 실패 처리 코드가 없다.
 *
 * <p>{@code @JsonPropertyDescription}은 사람용 주석이 아니라 <b>모델에게 전달되는 필드 설명</b>이다.
 * 특히 slug처럼 형식 규칙이 있는 필드는 여기 적는 편이 시스템 프롬프트에 적는 것보다 잘 지켜진다
 * — 값을 채우는 바로 그 자리에 규칙이 붙어 있기 때문.
 *
 * <p><b>왜 문서 필드가 이 네 개인가</b>: {@code document} 테이블(V1)의 컬럼과 1:1로 맞췄다.
 * 승인 시 기존 문서 등록 경로에 그대로 흘려보내기 위해서다(문제 승인이
 * AdminProblemService.create를 재사용하는 것과 같은 단일 경로 원칙).
 * {@code source}는 넣지 않았다 — AI가 만든 문서에 출처를 적게 하면 <b>그럴듯한 가짜 URL</b>을
 * 지어낼 위험이 크고, 실제 출처는 검수자가 확인해 채우는 편이 안전하다.
 */
@JsonClassDescription("백엔드 CS 개념 문서")
public record GeneratedDocumentItem(

        @JsonPropertyDescription("문서 제목. 한국어. 200자 이내. 예: 'TCP 3-way 핸드셰이크와 연결 종료'")
        String title,

        @JsonPropertyDescription("URL에 쓸 영문 식별자. 소문자 알파벳·숫자·하이픈만 사용하고 공백은 하이픈으로. "
                + "150자 이내. 제목을 영어로 옮겨 짓는다. 예: 'tcp-3-way-handshake'")
        String slug,

        @JsonPropertyDescription("마크다운 본문. 시스템 프롬프트의 문서 구조를 따른다. HTML 태그는 절대 쓰지 않는다")
        String contentMd,

        @JsonPropertyDescription("검색용 태그 2~4개. 소문자 영문. 제시된 기존 태그를 우선 재사용한다. 예: ['tcp','network']")
        List<String> tags
) {
    // 문제 생성({@code GeneratedProblemItem.Batch})과 달리 감싸는 봉투가 없다.
    // 봉투가 필요했던 이유는 "JSON 최상위는 객체여야 하는데 문제는 목록"이었기 때문이고,
    // 문서는 그 자체가 이미 객체라 봉투를 씌울 이유가 없다.
    //
    // 실제로 봉투를 만들었다가 SDK 스키마 검증에서 막혔다:
    //   "#/properties/document: Expected exactly one of 'type' or 'anyOf' or '$ref'"
    // 단일 객체 필드에 @JsonPropertyDescription을 붙이면 스키마 생성기가 설명을 얹으려고
    // allOf로 감싸는데, SDK 검증기는 type/anyOf/$ref 중 하나만 허용한다.
    // (배열 필드는 {"type":"array", ...}가 되어 이 문제가 없다 — 그래서 문제 쪽은 통과했다.)
}
