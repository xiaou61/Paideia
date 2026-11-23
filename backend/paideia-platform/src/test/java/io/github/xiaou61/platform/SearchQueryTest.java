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

    /**
     * 超长短语必须被截断（2026-11-22）。
     *
     * <p>MySQL 对短语里的词数有上限，超了直接抛 `SQLException: Too many words in a FTS
     * phrase or proximity search`，整个搜索接口 500。实测 128 字正常、150 字报错，所以这里
     * 钉住"每条短语不超过上限"，并且按**码点**截断（按 char 截会把 emoji 劈成半个代理对）。
     */
    @Test
    void booleanModeCapsOverlongPhrase() {
        String long_ = "长".repeat(150);
        String built = SearchQuery.booleanMode(long_);
        assertThat(built).isNotNull();
        String phrase = built.substring(2, built.length() - 1);
        assertThat(phrase.codePointCount(0, phrase.length()))
                .isEqualTo(SearchQuery.MAX_PHRASE_CODE_POINTS);
        // 截断之后仍然是一条合法短语，前后引号与 + 号都在
        assertThat(built).startsWith("+\"").endsWith("\"");

        // emoji 也是按码点算：100 个 emoji 保留 100 个，不会留下半个代理对
        String emoji = "🙂".repeat(150);
        String emojiPhrase = SearchQuery.booleanMode(emoji);
        assertThat(emojiPhrase).isNotNull();
        String kept = emojiPhrase.substring(2, emojiPhrase.length() - 1);
        assertThat(kept.codePointCount(0, kept.length())).isEqualTo(SearchQuery.MAX_PHRASE_CODE_POINTS);
        assertThat(Character.isHighSurrogate(kept.charAt(kept.length() - 1))).isFalse();
    }
}
