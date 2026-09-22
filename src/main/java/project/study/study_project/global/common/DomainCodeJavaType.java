package project.study.study_project.global.common;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.AbstractClassJavaType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators;

import java.sql.Types;

/**
 * Hibernate에게 {@link DomainCode}를 "VARCHAR 한 칸"으로 알려 주는 타입 설명 — <b>기본키 칸 전용</b>.
 *
 * <h2>왜 변환기({@link DomainCodeAttributeConverter})만으로 안 되나</h2>
 *
 * <p>JPA 명세는 {@code AttributeConverter}를 {@code @Id} 속성에 적용하지 않는다. autoApply는
 * 물론이고 {@code @Convert}를 직접 붙여도 Hibernate 6.6은 기본키에서 무시한다(직접 확인함 —
 * {@code domain_setting.domain}에 붙였더니 "Could not determine recommended JdbcType for Java type
 * DomainCode"로 기동이 실패했다). 다른 엔티티의 {@code domain} 칸은 일반 속성이라 변환기로
 * 충분하지만, {@code DomainSetting}은 분야 코드 자체가 기본키라 이 길이 필요하다.
 *
 * <p>enum 시절에는 Hibernate가 enum을 원래 알고 있어서({@code @Enumerated(STRING)}) 이 문제가
 * 없었다. 값 타입으로 바꾸면서 Hibernate가 모르는 타입이 기본키에 들어간 것이 원인이다.
 *
 * <p>대안이었던 {@code @EmbeddedId}/{@code @IdClass}는 테이블 모양이나 엔티티 모양을 바꿔야 해서
 * 버렸다. 이 클래스는 저장 값을 변환기와 <b>똑같이</b> 만든다 — 코드 문자열 그대로, 읽을 때는
 * {@link DomainCode#of}로 형식 검사. 그래서 같은 컬럼 값이 두 경로에서 다르게 읽히는 일이 없다.
 */
public class DomainCodeJavaType extends AbstractClassJavaType<DomainCode> {

    public DomainCodeJavaType() {
        super(DomainCode.class);
    }

    @Override
    public JdbcType getRecommendedJdbcType(JdbcTypeIndicators indicators) {
        return indicators.getJdbcType(Types.VARCHAR);
    }

    @Override
    public String toString(DomainCode value) {
        return value.value();
    }

    @Override
    public DomainCode fromString(CharSequence string) {
        return string == null ? null : DomainCode.of(string.toString());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X> X unwrap(DomainCode value, Class<X> type, WrapperOptions options) {
        if (value == null) {
            return null;
        }
        if (DomainCode.class.isAssignableFrom(type)) {
            return (X) value;
        }
        if (String.class.isAssignableFrom(type)) {
            return (X) value.value();
        }
        throw unknownUnwrap(type);
    }

    @Override
    public <X> DomainCode wrap(X value, WrapperOptions options) {
        if (value == null) {
            return null;
        }
        if (value instanceof DomainCode code) {
            return code;
        }
        if (value instanceof String raw) {
            return DomainCode.of(raw);
        }
        throw unknownWrap(value.getClass());
    }
}
