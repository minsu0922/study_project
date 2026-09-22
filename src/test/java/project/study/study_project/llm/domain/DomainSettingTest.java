package project.study.study_project.llm.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import project.study.study_project.TestDomains;
import project.study.study_project.global.common.DomainCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainSettingTest {

    @Test
    @DisplayName("고치면 값이 바뀐다 — 순서는 여기서 못 바꾼다")
    void editChangesEditableFields() {
        DomainSetting setting = DomainSetting.initial(TestDomains.NETWORK, true, 0, "네트워크", null);

        setting.edit(false, "네트워크 기초", "TCP 위주");

        assertThat(setting.isEnabled()).isFalse();
        assertThat(setting.getDisplayName()).isEqualTo("네트워크 기초");
        assertThat(setting.getHint()).isEqualTo("TCP 위주");
        assertThat(setting.getSortOrder()).isZero();   // edit은 순서를 건드리지 않는다
    }

    @Test
    @DisplayName("공백만 있는 힌트는 null로 저장한다 — 빈 칸과 '안 쓴 칸'을 한 가지로 만든다")
    void blankHintBecomesNull() {
        DomainSetting setting = DomainSetting.initial(TestDomains.OS, true, 1, "운영체제", "   ");

        assertThat(setting.getHint()).isNull();
    }

    @Test
    @DisplayName("화면 이름은 비울 수 없다 — 비면 목록에서 그 줄이 사라진 것처럼 보인다")
    void displayNameIsRequired() {
        DomainSetting setting = DomainSetting.initial(TestDomains.OS, true, 1, "운영체제", null);

        assertThatThrownBy(() -> setting.edit(true, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
