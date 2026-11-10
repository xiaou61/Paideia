package io.github.xiaou61.platform;

/**
 * 检索串的归一化与"走不走全文索引"的判定。
 *
 * <p>纯函数，不碰数据库——这是本项目里少数几处**必须能单测**的逻辑，因为它同时决定
 * 召回率（中文能不能搜到）与安全性（LIKE 通配符要不要转义）。
 *
 * <p><b>为什么需要它</b>：带 `WITH PARSER ngram` 的全文索引按固定长度切词（默认 2）。
 * 长度小于该值的查询（例如单个汉字）在全文索引上**召回为零**，不是"结果少"而是"永远搜不到"。
 * 所以短查询必须退回 `LIKE`，否则用户搜一个字会得到空结果并以为功能坏了。
 *
 * <p><b>为什么在平台模块</b>：它最初长在知识库模块的 `internal.search` 里。WORK-006 的
 * 助手记忆要用同一套判定（"说过的话"那一路同样是中文全文检索 + 短查询退 LIKE），而**跨模块
 * 引用别人的 internal 包被 Modulith 边界禁止**。与其复制一份"LIKE 通配符转义"，让信任边界上
 * 的逻辑留两个各自漂移的版本，不如按既有纪律提升到共享模块——第二个消费者出现时才提升，
 * 正是这个项目一贯的做法。
 */
public final class SearchQuery {

    /** MySQL ngram 解析器的默认分词长度。短于此长度的查询走 LIKE。 */
    public static final int NGRAM_TOKEN_SIZE = 2;

    private SearchQuery() {
    }

    /** 去空白；空串与 null 都归一为 null（表示"没有检索条件"）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 是否长到能走全文索引。null 视为否。 */
    public static boolean usesFulltext(String normalized) {
        return normalized != null
                && normalized.codePointCount(0, normalized.length()) >= NGRAM_TOKEN_SIZE;
    }

    /**
     * 构造 BOOLEAN 模式的检索串，用于 `MATCH … AGAINST(? IN BOOLEAN MODE)`（2026-11-10）。
     *
     * <p><b>为什么不再用自然语言模式</b>：ngram 解析器把中文切成二字片段，自然语言模式的
     * 语义是"**任意一个片段命中就算命中**"。于是搜「不存在的词啊」会因为里面有"存在"这个词
     * 召回 209 条，搜 `qqqqq` 也能出 2 条——用户看到的是"搜什么都有一堆不相干结果"。
     *
     * <p>这里改成：按空白拆词、**每个词加引号做短语匹配**，词与词之间用 `+` 要求同时出现。
     * 效果是"我打的每个词都得在正文里挨着出现"，这才是搜索框该有的行为。
     *
     * <p>三条防御：
     * <ul>
     *   <li>引号在 BOOLEAN 语法里是短语定界符，反斜杠是转义符——词里出现就删掉，
     *       否则用户能构造出 `-foo` 之类的操作符（参数是绑定的，不会有注入，但会得到意外结果）；</li>
     *   <li>长度小于 ngram 词元（2）的词会被丢弃：它在全文索引上召回恒为零，留着会让整条
     *       查询变成"永远没有结果"；</li>
     *   <li>全被丢弃时返回 null，调用方退回 LIKE（单字查询仍走老路）。</li>
     * </ul>
     */
    public static String booleanMode(String normalized) {
        if (normalized == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(normalized.length() + 8);
        int terms = 0;
        for (String raw : normalized.split("[\\s\\u3000]+")) {
            String term = sanitizeTerm(raw);
            if (term.codePointCount(0, term.length()) < NGRAM_TOKEN_SIZE) {
                continue;
            }
            if (terms > 0) {
                out.append(' ');
            }
            out.append("+\"").append(term).append('"');
            terms++;
        }
        return terms == 0 ? null : out.toString();
    }

    /** 去掉 BOOLEAN 语法里有特殊含义的字符（短语定界引号与转义反斜杠）。 */
    private static String sanitizeTerm(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current != '"' && current != '\\') {
                out.append(current);
            }
        }
        return out.toString();
    }

    /**
     * 构造 LIKE 用的模式串，并**转义 LIKE 的通配符**。
     *
     * <p>不转义的话，用户搜 `%` 会匹配全部条目、搜 `_` 会匹配任意单字——不是 SQL 注入
     * （参数仍是绑定的），而是"用户的字面输入被当成通配符"，属于信任边界上的输入处理，
     * 不能省。
     *
     * <p>依赖 MySQL 的默认 LIKE 转义字符是反斜杠（未设 `NO_BACKSLASH_ESCAPES`）。
     * 若将来改了这个 sql_mode，这里要显式写成 `ESCAPE` 子句。
     */
    public static String likePattern(String normalized) {
        StringBuilder out = new StringBuilder(normalized.length() + 2).append('%');
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '\\' || current == '%' || current == '_') {
                out.append('\\');
            }
            out.append(current);
        }
        return out.append('%').toString();
    }
}
