package project.study.study_project.document.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 편을 slug만으로 짝짓는 규칙 — 2026-09-03, docs/15.
 *
 * <p>칸을 만들지 않고 slug 규칙에 기댄 결정이라, <b>그 규칙이 흔들리면 화면의 배지와 링크가
 * 통째로 사라진다</b>. 실패가 조용하다는 뜻이다 — 문서는 멀쩡히 뜨고 예외도 없다.
 * 그래서 순수 함수 세 개를 값 단위로 못 박는다.
 */
class DocumentEditionsTest {

    @Test
    @DisplayName("입문편 ↔ 심화편이 서로를 가리킨다 — 왕복이 성립해야 한 쌍이다")
    void pairsBothWays() {
        String beginner = "on-delete-referential-actions";
        String advanced = beginner + DocumentEditions.ADVANCED_SUFFIX;

        assertThat(DocumentEditions.counterpartSlugOf(beginner)).isEqualTo(advanced);
        assertThat(DocumentEditions.counterpartSlugOf(advanced)).isEqualTo(beginner);
        assertThat(DocumentEditions.isAdvanced(advanced)).isTrue();
        assertThat(DocumentEditions.isAdvanced(beginner)).isFalse();
        assertThat(DocumentEditions.labelOf(beginner)).isEqualTo("입문편");
        assertThat(DocumentEditions.labelOf(advanced)).isEqualTo("심화편");
    }

    /**
     * 꼬리만 남는 slug({@code "-advanced"})에서 {@code null}을 돌려주는지.
     *
     * <p>빈 문자열을 돌려주면 그것으로 문서를 조회하게 되고, 조회 자체는 조용히 0건이라
     * 아무도 모른 채 지나간다. 이런 slug는 검수자가 손으로 고치다 만들 수 있다.
     */
    @Test
    @DisplayName("짝을 만들 수 없는 slug에는 null — 빈 문자열로 조회하면 조용히 0건이 된다")
    void returnsNullWhenPairCannotBeDerived() {
        assertThat(DocumentEditions.counterpartSlugOf(null)).isNull();
        assertThat(DocumentEditions.counterpartSlugOf("")).isNull();
        assertThat(DocumentEditions.counterpartSlugOf("   ")).isNull();
        assertThat(DocumentEditions.counterpartSlugOf(DocumentEditions.ADVANCED_SUFFIX)).isNull();
    }
}
