package io.github.xiaou61.sandbox.internal.dataset;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import io.github.xiaou61.sandbox.internal.run.SqlDatasets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 数据集结构查询（WORK-035 T-16）。
 *
 * <p><b>为什么需要这个工具</b>：schema 可能有几十张表、上百个列。整段塞进系统提示词
 * 既贵又浪费上下文——而助手只在"要写 SQL 时"才需要它。按需取符合现有的 `find_tools` 分层。
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li>**只读**：`readOnly = true`，`ToolQuery.internal` 也只放行只读工具；</li>
 *   <li>**不返回数据行**：只回表名、列名、类型、主键、可空。数据行可能包含用户的私有内容，
 *       而"写 SQL 需要知道结构"不需要看到数据；</li>
 *   <li>**内部自己再判一次权限**：工具拿到的是 `context.userId`，走同一个
 *       `SqlDatasets.schema(userId, key)`。**不能假设模型只会传自己有权的 key**——
 *       模型可能被提示词注入或单纯猜错。</li>
 * </ol>
 */
public final class SqlDatasetTools {

    private SqlDatasetTools() {
    }

    @Component
    public static class DescribeDataset implements PaideiaTool {

        private final SqlDatasets datasets;

        public DescribeDataset(SqlDatasets datasets) {
            this.datasets = datasets;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "sql_describe_dataset", "sql", "查看数据集表结构",
                    "给数据集 key 返回它的表、列、类型与主键。**不返回数据行**。"
                            + "用户问「这个库有哪些表」「写一句查客户的 SQL」时用它了解结构。"
                            + "只能查当前调用者自己能访问的数据集。",
                    Map.of("type", "object", "properties", Map.of(
                                    "key", Map.of("type", "string",
                                            "description", "数据集 key（如 orders-public）")),
                            "required", List.of("key")),
                    null, true, false, true, ToolScope.READ, true);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Object raw = arguments.get("key");
            if (!(raw instanceof String key) || key.isBlank()) {
                return ToolResult.fail("需要提供数据集 key");
            }
            String schema = datasets.schema(context.userId(), key);
            if (schema == null) {
                // 与"无权访问"同一句：区分了就给出一个可枚举的 oracle
                return ToolResult.ok("没有找到这个数据集（或你没有访问权限）。");
            }
            return ToolResult.ok("数据集 " + key + " 的结构：\n" + schema);
        }
    }

    /**
     * 读取数据集的完整 SQL（WORK-060）。
     *
     * <p><b>为什么需要它</b>：用户说「给 orders 表再加 5 行」时，模型必须看到原来的
     * CREATE + INSERT 才能在不丢数据的前提下产出完整新 SQL。只给结构（列名/类型）不够。
     *
     * <p><b>为什么不在页面上下文里常驻 SQL</b>：那会让用户在 SQL 页的每一次普通提问都把
     * 整份数据行送进模型。只在用户明确点「加」时调用这个工具，暴露面小得多。
     *
     * <p>仍然只读、仍然自己判权限；隐藏内置集拒绝（与目录、schema 端点同一口径）。
     */
    @Component
    public static class ReadDatasetSql implements PaideiaTool {

        /** 一份 SQL 最多回多少字符；超出部分只给提示，不把上下文挤爆。 */
        static final int MAX_CHARS = 20_000;

        private final SqlDatasets datasets;

        public ReadDatasetSql(SqlDatasets datasets) {
            this.datasets = datasets;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "sql_read_dataset", "sql", "读取数据集 SQL",
                    "返回一个数据集的建表与插数 SQL（**包含数据行**）。"
                            + "**只在用户明确要求修改某个数据集时调用**（例如「给 orders 表再加 5 行」"
                            + "「在这个数据集里加一张表」）。用户只是想写查询时不要调用——"
                            + "看结构用 sql_describe_dataset 就够了。"
                            + "只能读取当前调用者能访问的数据集；隐藏集与别人的私人集都读不到。",
                    Map.of("type", "object", "properties", Map.of(
                                    "key", Map.of("type", "string",
                                            "description", "数据集 key（如 orders-public）")),
                            "required", List.of("key")),
                    null, true, false, true, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Object raw = arguments.get("key");
            if (!(raw instanceof String key) || key.isBlank()) {
                return ToolResult.fail("需要提供数据集 key");
            }
            // 隐藏内置集：目录不展示它，读 SQL 也拒绝——否则模型猜到 key 就能把隐藏集整份拿走
            if (datasets.isBuiltin(key) && !datasets.publicKeys().contains(key)) {
                return ToolResult.ok("没有找到这个数据集（或你没有访问权限）。");
            }
            String sql = datasets.sql(context.userId(), key);
            if (sql == null) {
                return ToolResult.ok("没有找到这个数据集（或你没有访问权限）。");
            }
            if (sql.length() > MAX_CHARS) {
                return ToolResult.ok("数据集 " + key + " 的 SQL（前 " + MAX_CHARS + " 字符，后面还有 "
                        + (sql.length() - MAX_CHARS) + " 字符没显示）：\n" + sql.substring(0, MAX_CHARS));
            }
            return ToolResult.ok("数据集 " + key + " 的 SQL：\n" + sql);
        }
    }
}
