package io.github.xiaou61.sandbox.internal.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 拼装数据集 SQL（WORK-053；2026-11-03 改成"模型出数据"）。
 *
 * <p>要守的四条：① 产出必须能过上传白名单；② 值原样进 SQL（单引号转义）；③ 参数不合法时给
 * 可读的失败而不是抛异常；④ 数据里的错（缺列、类型不符、越界）必须被点出来——早先"工具自己
 * 编数据"的版本就是死在这里：它不知道外键指向谁，产出的行 JOIN 不上。
 */
class SqlDatasetBuildToolsTest {

    private static final ToolContext USER =
            new ToolContext(1L, "USER", Set.of(ToolScope.READ), ToolContext.Origin.INTERNAL);

    private static final SqlDatasetBuildTools.BuildDataset TOOL = new SqlDatasetBuildTools.BuildDataset();

    private static Map<String, Object> table(String name, List<String> columns, List<Map<String, Object>> rows) {
        return Map.of("name", name, "columns", columns, "rows", rows);
    }

    /** 允许 null 值，所以不用 Map.of。 */
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    private static ToolResult build(Object... tables) {
        return TOOL.call(USER, Map.of("tables", List.of(tables)));
    }

    @Test
    @DisplayName("模型给的数据原样进 SQL，且能过上传白名单")
    void outputPassesUploadGuard() {
        ToolResult result = build(table("orders",
                List.of("id:INT", "customer:VARCHAR(40)", "amount:DECIMAL(10,2)", "paid_at:DATE"),
                List.of(
                        row("id", 1, "customer", "alice", "amount", 240, "paid_at", "2025-03-02"),
                        row("id", 2, "customer", null, "amount", 130.5, "paid_at", "2025-03-15"))));

        assertThat(result.error()).isFalse();
        String sql = result.text();
        assertThat(sql).contains("CREATE TABLE orders");
        assertThat(sql).contains("'alice'");
        assertThat(sql).contains("240");
        assertThat(sql).contains("130.5");
        assertThat(sql).contains("NULL");
        assertThat(sql).contains("'2025-03-02'");
        // 白名单是上传的第 2 道关；这里直接用它断言，避免"工具能造、保存必失败"
        assertThat(SqlStatementGuard.check(sql)).isEmpty();
    }

    @Test
    @DisplayName("单引号自己转义，不拼坏 SQL")
    void escapesSingleQuote() {
        ToolResult result = build(table("customers", List.of("id:INT", "name:VARCHAR(40)"),
                List.of(row("id", 1, "name", "O'Brien"))));

        assertThat(result.error()).isFalse();
        assertThat(result.text()).contains("'O''Brien'");
    }

    @Test
    @DisplayName("缺列/多列点名到行，不静默补值")
    void rejectsMissingOrExtraColumn() {
        ToolResult missing = build(table("t", List.of("id:INT", "name:VARCHAR(20)"),
                List.of(row("id", 1))));
        assertThat(missing.error()).isTrue();
        assertThat(missing.text()).contains("第 1 行缺列 name");

        ToolResult extra = build(table("t", List.of("id:INT"),
                List.of(row("id", 1, "nmae", "typo"))));
        assertThat(extra.error()).isTrue();
        assertThat(extra.text()).contains("多余的列 nmae");
    }

    @Test
    @DisplayName("值与列类型不符就拒绝")
    void rejectsTypeMismatch() {
        ToolResult notNumber = build(table("t", List.of("id:INT"), List.of(row("id", "abc"))));
        assertThat(notNumber.error()).isTrue();
        assertThat(notNumber.text()).contains("不是数字");

        ToolResult notInteger = build(table("t", List.of("id:INT"), List.of(row("id", 1.5))));
        assertThat(notInteger.error()).isTrue();
        assertThat(notInteger.text()).contains("要是整数");

        ToolResult numberAsText = build(table("t", List.of("name:VARCHAR(20)"), List.of(row("name", 7))));
        assertThat(numberAsText.error()).isTrue();
        assertThat(numberAsText.text()).contains("要是字符串");

        ToolResult badDate = build(table("t", List.of("d:DATE"), List.of(row("d", "2025-13-01"))));
        assertThat(badDate.error()).isTrue();
        assertThat(badDate.text()).contains("日期不合法");
    }

    @Test
    @DisplayName("行数与表数越界就拒绝")
    void rejectsRanges() {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            tooMany.add(row("id", i));
        }
        ToolResult rows = build(table("t", List.of("id:INT"), tooMany));
        assertThat(rows.error()).isTrue();
        assertThat(rows.text()).contains("超过上限 50");

        assertThat(build(table("t", List.of("id:INT"), List.of())).error()).isTrue();
        assertThat(TOOL.call(USER, Map.of("tables", List.of())).error()).isTrue();
    }

    @Test
    @DisplayName("标识符与列类型不在白名单就拒绝")
    void rejectsBadIdentifiersAndTypes() {
        ToolResult badName = build(table("orders; DROP TABLE users", List.of("id:INT"),
                List.of(row("id", 1))));
        assertThat(badName.error()).isTrue();
        assertThat(badName.text()).contains("表名不合法");

        ToolResult badType = build(table("t", List.of("body:TEXT"), List.of(row("body", "x"))));
        assertThat(badType.error()).isTrue();
        assertThat(badType.text()).contains("列类型不支持");
    }
}
