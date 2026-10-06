package project.study.study_project.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 검색어 → LIKE 패턴. 검색 칸이 있는 목록이 모두 이 규칙을 쓴다. */
class SearchKeywordTest {

    @Test
    @DisplayName("비었거나 공백뿐이면 null — 조건을 걸지 않는다")
    void blankMeansNoFilter() {
        assertThat(SearchKeyword.likePattern(null)).isNull();
        assertThat(SearchKeyword.likePattern("")).isNull();
        assertThat(SearchKeyword.likePattern("   ")).isNull();
    }

    @Test
    @DisplayName("앞뒤 공백을 떼고 %로 감싼다")
    void wrapsTrimmedWord() {
        assertThat(SearchKeyword.likePattern("  캐시 ")).isEqualTo("%캐시%");
        // 낱말 사이의 공백은 검색어의 일부다.
        assertThat(SearchKeyword.likePattern("TTL 상한")).isEqualTo("%TTL 상한%");
    }

    /** 이스케이프하지 않으면 "%" 한 글자로 모든 줄이 나오고 "_"는 아무 글자 하나에 맞는다. */
    @Test
    @DisplayName("사용자가 친 %, _, !는 글자 그대로 찾도록 이스케이프한다")
    void escapesWildcards() {
        assertThat(SearchKeyword.likePattern("%")).isEqualTo("%!%%");
        assertThat(SearchKeyword.likePattern("a_b")).isEqualTo("%a!_b%");
        assertThat(SearchKeyword.likePattern("100%!")).isEqualTo("%100!%!!%");
    }

    @Test
    @DisplayName("50자를 넘으면 앞 50자만 쓴다")
    void capsLength() {
        String pattern = SearchKeyword.likePattern("가".repeat(80));
        assertThat(pattern).isEqualTo("%" + "가".repeat(SearchKeyword.MAX_LENGTH) + "%");
    }

    @Test
    @DisplayName("trimToNull — 공백뿐인 입력은 안 적은 것으로 본다")
    void trimToNull() {
        assertThat(Texts.trimToNull(null)).isNull();
        assertThat(Texts.trimToNull(" \t ")).isNull();
        assertThat(Texts.trimToNull(" 메모 ")).isEqualTo("메모");
    }
}
