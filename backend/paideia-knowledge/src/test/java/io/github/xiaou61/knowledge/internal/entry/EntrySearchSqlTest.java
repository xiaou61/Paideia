package io.github.xiaou61.knowledge.internal.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * 检索 SQL 的守卫（2026-11-10）。
 *
 * <p><b>为什么要有这条测试</b>：检索从 MySQL 的**自然语言模式**改成 **BOOLEAN 短语模式**
 * （见 `SearchQuery.booleanMode` 的注释）。这一改动只有两个观察点：查询串怎么拼、
 * SQL 里写的是哪个模式。前者有 `SearchQueryTest` 盯着，后者原本只能靠"跑一遍搜索看看"——
 * 而回退是**静默的**：把 `IN BOOLEAN MODE` 删掉，功能照常工作，只是又开始"搜什么都有一堆
 * 结果"。这种回退必须由测试挡住。
 *
 * <p>这里读的是注解里的 SQL 文本，不连数据库：本机没有 MySQL，集成测试跑不了，
 * **但有守卫总比没有强**——它锁的正是"模式"这个唯一会被人手误删的关键词。
 */
class EntrySearchSqlTest {

    @Test
    void countSqlUsesBooleanModeWithBoundText() throws NoSuchMethodException {
        String sql = sqlOf("countPublished");
        assertThat(sql)
                .as("countPublished 必须用 BOOLEAN 模式，且检索串来自 SearchQuery.booleanMode 的绑定参数")
                .contains("IN BOOLEAN MODE")
                .contains("#{booleanText}")
                .doesNotContain("AGAINST(#{keyword})");
    }

    @Test
    void pageSqlUsesBooleanModeWithBoundText() throws NoSuchMethodException {
        String sql = sqlOf("pagePublished");
        assertThat(sql)
                .as("pagePublished 与 countPublished 必须同口径，否则总数和列表会对不上")
                .contains("IN BOOLEAN MODE")
                .contains("#{booleanText}")
                .doesNotContain("AGAINST(#{keyword})");
    }

    @Test
    void bothSearchesKeepThePublishedGate() throws NoSuchMethodException {
        // 改检索时最容易顺手删掉的是 status 过滤——那会把草稿漏给学习者
        assertThat(sqlOf("countPublished")).contains("status = 'published'");
        assertThat(sqlOf("pagePublished")).contains("status = 'published'");
    }

    private static String sqlOf(String methodName) throws NoSuchMethodException {
        for (var method : EntryMapper.class.getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            return String.join("\n", List.of(select.value()));
        }
        throw new NoSuchMethodException("EntryMapper 里没有带 @Select 的 " + methodName);
    }
}
