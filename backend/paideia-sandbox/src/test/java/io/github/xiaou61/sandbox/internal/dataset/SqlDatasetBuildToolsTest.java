package io.github.xiaou61.sandbox.internal.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 拼装数据集 SQL（WORK-053）。
 *
 * <p>三条要守的：① 产出必须能过上传的白名单（否则用户点了保存还是失败）；② 参数不合法时
 * 给可读的失败而不是抛异常；③ 同一份参数永远得到同一份 SQL（不用随机数）。
 */
class SqlDatasetBuildToolsTest {

    private static final ToolContext USER =
            new ToolContext(1L, "USER", Set.of(ToolScope.READ), ToolContext.Origin.INTERNAL);

    private static final SqlDatasetBuildTools.BuildDataset TOOL = new SqlDatasetBuildTools.BuildDataset();

    private static ToolResult build(Object tables, Object rows) {
        return TOOL.call(USER, Map.of("tables", tables, "rows", rows));
    }

    private static Map<String, Object> orders(int rows) {
        return Map.of("tables", List.of(Map.of(
                "name", "orders",
                "columns", List.of("id:INT", "customer:VARCHAR(40)", "amount:DECIMAL(10,2)", "paid_at:DATE"))),
                "rows", rows);
    }

    @Test
    @DisplayName("产出的 CREATE TABLE + INSERT 能过上传白名单")
    void outputPassesUploadGuard() {
        ToolResult result = TOOL.call(USER, orders(5));
        assertThat(result.error()).isFalse();
        String sql = result.text();
        assertThat(sql).contains("CREATE TABLE orders");
        assertThat(sql).contains("INSERT INTO orders");
        // 白名单是上传的第 2 道关；这里直接用它断言，避免"工具能造、保存必失败"
        assertThat(SqlStatementGuard.check(sql)).isEmpty();
    }

    @Test
    @DisplayName("行数与列数照参数来")
    void honoursRowsAndColumns() {
        String sql = TOOL.call(USER, orders(7)).text();
        assertThat(sql.split("\n")).filteredOn((line) -> line.trim().startsWith("(")).hasSize(7);
        assertThat(sql).contains("id INT PRIMARY KEY");
        assertThat(sql).contains("amount DECIMAL(10,2)");
    }

    @Test
    @DisplayName("同一份参数两次生成完全一致——没有随机数")
    void deterministic() {
        assertThat(TOOL.call(USER, orders(3)).text())
                .isEqualTo(TOOL.call(USER, orders(3)).text());
    }

    @Test
    @DisplayName("标识符不合法就拒绝，不拼进 SQL")
    void rejectsBadIdentifiers() {
        ToolResult bad = build(
                List.of(Map.of("name", "orders; DROP TABLE users", "columns", List.of("id:INT"))), 3);
        assertThat(bad.error()).isTrue();
        assertThat(bad.text()).contains("表名不合法");
    }

    @Test
    @DisplayName("类型不在白名单就拒绝")
    void rejectsUnknownType() {
        ToolResult bad = build(List.of(Map.of("name", "t", "columns", List.of("body:TEXT"))), 3);
        assertThat(bad.error()).isTrue();
        assertThat(bad.text()).contains("列类型不支持");
    }

    @Test
    @DisplayName("行数越界就拒绝")
    void rejectsRowCountOutOfRange() {
        assertThat(build(List.of(Map.of("name", "t", "columns", List.of("id:INT"))), 0).error()).isTrue();
        assertThat(build(List.of(Map.of("name", "t", "columns", List.of("id:INT"))), 500).error()).isTrue();
    }
}
