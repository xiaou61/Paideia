package io.github.xiaou61.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 检索判定与 LIKE 转义的纯逻辑测试。
 *
 * <p>两条必须钉住的边界：**单个汉字搜不到**（ngram 的固有行为，必须退回 LIKE）与
 * **通配符被转义**（用户输入的 `%` 是字面量，不能当通配符用）。
 */
class SearchQueryTest {

    @Test
    void normalizeTrimsAndCollapsesBlankToNull() {
        assertThat(SearchQuery.normalize(null)).isNull();
        assertThat(SearchQuery.normalize("")).isNull();
        assertThat(SearchQuery.normalize("   ")).isNull();
        assertThat(SearchQuery.normalize("  并发  ")).isEqualTo("并发");
    }

    @Test
    void singleChineseCharacterFallsBackToLike() {
        // ngram 分词长度是 2，单字查询在全文索引上永远召回为零——必须走 LIKE
        assertThat(SearchQuery.usesFulltext("锁")).isFalse();
        assertThat(SearchQuery.usesFulltext("a")).isFalse();
        assertThat(SearchQuery.usesFulltext(null)).isFalse();
    }

    @Test
    void twoCharacterQueryUsesFulltext() {
        assertThat(SearchQuery.usesFulltext("并发")).isTrue();
        assertThat(SearchQuery.usesFulltext("ab")).isTrue();
    }

    @Test
    void likePatternEscapesWildcardsAndBackslash() {
        assertThat(SearchQuery.likePattern("100%")).isEqualTo("%100\\%%");
        assertThat(SearchQuery.likePattern("a_b")).isEqualTo("%a\\_b%");
        assertThat(SearchQuery.likePattern("a\\b")).isEqualTo("%a\\\\b%");
        assertThat(SearchQuery.likePattern("并发")).isEqualTo("%并发%");
    }

    /**
     * BOOLEAN 模式串（2026-11-10）。
     *
     * <p>钉住三件事：单个词就是一条短语；多个词要求**同时**出现；用户的引号/反斜杠不会被
     * 当成语法。第一条正是修掉"搜什么都有一堆结果"的那个改动。
     */
    @Test
    void booleanModeQuotesEachTermAsPhrase() {
        assertThat(SearchQuery.booleanMode("联合索引")).isEqualTo("+\"联合索引\"");
        assertThat(SearchQuery.booleanMode("MySQL 索引")).isEqualTo("+\"MySQL\" +\"索引\"");
        assertThat(SearchQuery.booleanMode("  并发   编程 ")).isEqualTo("+\"并发\" +\"编程\"");
    }

    @Test
    void booleanModeDropsTermsTooShortForNgram() {
        // 单字在 ngram 上召回恒为零，留着会让整条查询"永远没有结果"
        assertThat(SearchQuery.booleanMode("锁")).isNull();
        assertThat(SearchQuery.booleanMode("a")).isNull();
        // 混在长查询里的单字被丢掉，其余照常
        assertThat(SearchQuery.booleanMode("a 并发")).isEqualTo("+\"并发\"");
        assertThat(SearchQuery.booleanMode("   ")).isNull();
        assertThat(SearchQuery.booleanMode(null)).isNull();
    }

    @Test
    void booleanModeNeutralisesOperatorsAndQuotes() {
        assertThat(SearchQuery.booleanMode("\"并发\"")).isEqualTo("+\"并发\"");
        assertThat(SearchQuery.booleanMode("-并发")).isEqualTo("+\"-并发\"");
        assertThat(SearchQuery.booleanMode("a\\\\b_c")).isEqualTo("+\"ab_c\"");
    }
}
