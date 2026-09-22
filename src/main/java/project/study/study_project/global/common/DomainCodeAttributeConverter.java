package project.study.study_project.global.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA 속성 변환기 — {@code DomainCode}와 DB 컬럼(VARCHAR) 간 변환.
 *
 * <p><b>왜 직접 변환기를 만드는가.</b> 값 타입이 생기면 JPA가 자동으로 변환하지 않는다.
 * 명시적 변환기가 필요하고, {@code @Converter(autoApply = true)}로 모든 엔티티에
 * 자동 적용되게 한다.
 *
 * <p><b>null 처리.</b> DB에서 NULL 컬럼은 null을 반환하고, null {@code DomainCode}는
 * 컬럼에 NULL로 저장된다. JPA 표준 동작.
 */
@Converter(autoApply = true)
public class DomainCodeAttributeConverter implements AttributeConverter<DomainCode, String> {

    @Override
    public String convertToDatabaseColumn(DomainCode attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.value();
    }

    @Override
    public DomainCode convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return DomainCode.of(dbData);
    }
}
