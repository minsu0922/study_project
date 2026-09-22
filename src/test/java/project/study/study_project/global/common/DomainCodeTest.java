package project.study.study_project.global.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"NETWORK", "OS", "DS_ALGORITHM", "A1", "ABCDEFGHIJKLMNOPQRSTUVWXYZ_123"})
    @DisplayName("대문자로 시작하는 대문자·숫자·밑줄 2~30자는 받는다")
    void acceptsValidCodes(String raw) {
        assertThat(DomainCode.of(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "network", "Network", "1ABC", "_ABC", "AB-C", "AB C",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234"})
    @DisplayName("소문자·숫자 시작·기호·1자·31자는 거부한다 — 소문자를 막는 이유는 대소문자를 구별 않는 정렬 규칙")
    void rejectsInvalidCodes(String raw) {
        assertThatThrownBy(() -> DomainCode.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null은 거부한다")
    void rejectsNull() {
        assertThatThrownBy(() -> DomainCode.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JSON에는 문자열 하나로 나간다 — 지금 API의 \"domain\": \"NETWORK\" 모양이 그대로여야 한다")
    void serializesAsPlainString() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(om.writeValueAsString(DomainCode.of("NETWORK"))).isEqualTo("\"NETWORK\"");
        assertThat(om.readValue("\"OS\"", DomainCode.class)).isEqualTo(DomainCode.of("OS"));
    }

    @Test
    @DisplayName("JPA 변환기는 코드 글자를 그대로 저장한다 — 컬럼 값이 enum 시절과 같아야 데이터 이전이 없다")
    void attributeConverterRoundTrips() {
        DomainCodeAttributeConverter c = new DomainCodeAttributeConverter();
        assertThat(c.convertToDatabaseColumn(DomainCode.of("SECURITY"))).isEqualTo("SECURITY");
        assertThat(c.convertToEntityAttribute("SECURITY")).isEqualTo(DomainCode.of("SECURITY"));
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("요청 파라미터 변환기는 빈 문자열을 null로 변환한다 — ?domain=은 필터 없음을 뜻한다")
    void converterHandlesEmptyString() {
        StringToDomainCodeConverter converter = new StringToDomainCodeConverter();
        assertThat(converter.convert("")).isNull();
    }

    @Test
    @DisplayName("요청 파라미터 변환기는 공백만 있는 문자열을 null로 변환한다")
    void converterHandlesBlankString() {
        StringToDomainCodeConverter converter = new StringToDomainCodeConverter();
        assertThat(converter.convert("   ")).isNull();
    }

    @Test
    @DisplayName("요청 파라미터 변환기는 유효한 코드를 변환한다")
    void converterHandlesValidCode() {
        StringToDomainCodeConverter converter = new StringToDomainCodeConverter();
        assertThat(converter.convert("NETWORK")).isEqualTo(DomainCode.of("NETWORK"));
    }

    @Test
    @DisplayName("요청 파라미터 변환기는 소문자 코드를 거부한다")
    void converterRejectsLowercaseCode() {
        StringToDomainCodeConverter converter = new StringToDomainCodeConverter();
        assertThatThrownBy(() -> converter.convert("network")).isInstanceOf(IllegalArgumentException.class);
    }
}
