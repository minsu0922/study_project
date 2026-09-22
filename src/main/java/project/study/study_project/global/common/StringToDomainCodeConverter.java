package project.study.study_project.global.common;

import org.springframework.core.convert.converter.Converter;

/**
 * Spring MVC 경로 변수·요청 파라미터 변환기 — String을 {@code DomainCode}로 변환.
 *
 * <p><b>언제 쓰이는가.</b> {@code GET /api/domains/{code}}의 경로 변수나
 * {@code @RequestParam String code}를 {@code DomainCode}에 바인딩할 때,
 * Spring이 이 변환기를 거친다({{@code FormatterRegistry}}에 등록되어야 함).
 *
 * <p><b>형식 오류 처리.</b> {@code DomainCode.of()}가 던지는 {@code IllegalArgumentException}은
 * 그대로 전파되고, Spring이 {@code MethodArgumentTypeMismatchException}으로 감싸서
 * HTTP 400 응답이 된다 — enum 변환 실패와 같은 결과.
 */
public class StringToDomainCodeConverter implements Converter<String, DomainCode> {

    @Override
    public DomainCode convert(String source) {
        // 비어 있거나 공백인 문자열은 null을 반환한다. 이는 enum의 StringToEnumConverterFactory 동작을 그대로
        // 따른 것이다. Task 3의 8개 선택 필터 파라미터(?domain=, ?level= 등)가 이 변환기를 거칠 때,
        // "없는 필터"를 나타내는 빈 값이 400 오류가 되지 않도록 한다 — 예전처럼 무시된다.
        if (source == null || source.trim().isEmpty()) {
            return null;
        }
        return DomainCode.of(source);
    }
}
