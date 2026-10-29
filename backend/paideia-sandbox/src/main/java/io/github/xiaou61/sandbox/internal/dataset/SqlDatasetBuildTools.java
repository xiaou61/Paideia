package io.github.xiaou61.sandbox.internal.dataset;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 生成一份练习数据集的 SQL（WORK-053）。
 *
 * <p><b>为什么是"拼装"而不是再调一次模型</b>：造数据的模型调用已有专用路径
 * （`SqlDatasetGeneratorPort`）。在对话里再调一次等于同一次生成收两笔钱，还绕开那次
 * 模型调用的记账。这里让模型负责**问清楚要什么**（哪些表、哪些列、多少行），
 * 工具负责**确定性地拼出合法 SQL**——同一份参数永远得到同一份输出，可测试。
 *
 * <p><b>为什么只返回文本</b>：`ToolQuery.internal` 把内部工具硬约束为只读。落库仍走页面上的
 * 保存按钮（白名单 + 沙箱干跑），库里不会因为一句"帮我造个数据集"就多出东西。
 *
 * <p><b>为什么自己校验标识符</b>：模型可能把中文、空格或注入片段塞进表名列名。这里只放
 * `[a-z][a-z0-9_]{0,29}`，类型只放白名单——产出的 SQL 必须能过 {@link SqlStatementGuard}。
 */
public final class SqlDatasetBuildTools {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,29}");

    /** 够写教学数据集，又不放 TEXT/BLOB 这种能塞几十 MB 的类型。 */
    private static final Pattern TYPE = Pattern.compile(
            "(INT|BIGINT|TINYINT|DECIMAL\\(\\d{1,2},\\d{1,2}\\)|VARCHAR\\(\\d{1,3}\\)|DATE|DATETIME)");

    private static final List<String> WORDS = List.of("alpha", "beta", "gamma", "delta", "epsilon");

    private static final int MAX_TABLES = 3;
    private static final int MAX_COLUMNS = 12;
    private static final int MAX_ROWS = 200;

    private SqlDatasetBuildTools() {
    }

    private record Column(String name, String type) {
    }

    private record Table(String name, List<Column> columns) {
    }

    @Component
    public static class BuildDataset implements PaideiaTool {

        @Override
        public ToolDescriptor descriptor() {
            Map<String, Object> columnList = Map.of(
                    "type", "array",
                    "description", "列定义，如 [\"id:INT\", \"amount:DECIMAL(10,2)\"]",
                    "items", Map.of("type", "string"));
            Map<String, Object> table = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "description", "表名，如 orders"),
                            "columns", columnList));
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "tables", Map.of(
                                    "type", "array",
                                    "description", "表定义，1–3 张",
                                    "items", table),
                            "rows", Map.of("type", "integer", "description", "每张表插入多少行，1–200")),
                    "required", List.of("tables", "rows"));
            return new ToolDescriptor(
                    "planner_build_dataset",
                    "planner",
                    "拼一份练习数据集的 SQL",
                    "用户要「造一个数据集」时用它：先把表、列、行数问清楚，再用这个工具拼出"
                            + "CREATE TABLE + INSERT。**它只返回 SQL 文本、不会保存**——"
                            + "用户要在自己的 SQL 工作台点保存才会落库（保存时还要过白名单与沙箱干跑）。"
                            + "列写成 \"列名:类型\"；类型只支持 INT / BIGINT / TINYINT / "
                            + "DECIMAL(p,s) / VARCHAR(n) / DATE / DATETIME；"
                            + "最多 3 张表、每表 12 列、行数 1–200；标识符必须小写字母开头。",
                    schema, null, true, false, false, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Integer rows = intOf(arguments.get("rows"));
            if (rows == null || rows < 1 || rows > MAX_ROWS) {
                return ToolResult.fail("rows 必须在 1–" + MAX_ROWS + " 之间");
            }
            Object rawTables = arguments.get("tables");
            if (!(rawTables instanceof List<?> tables) || tables.isEmpty()) {
                return ToolResult.fail("需要提供 tables（1–" + MAX_TABLES + " 张表的定义）");
            }
            if (tables.size() > MAX_TABLES) {
                return ToolResult.fail("最多 " + MAX_TABLES + " 张表，这次给了 " + tables.size() + " 张");
            }
            try {
                return ToolResult.ok(build(parse(tables), rows));
            } catch (IllegalArgumentException bad) {
                return ToolResult.fail(bad.getMessage());
            }
        }
    }

    private static List<Table> parse(List<?> rawTables) {
        List<Table> parsed = new ArrayList<>();
        for (Object item : rawTables) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("每张表要是对象：{name, columns}");
            }
            String name = String.valueOf(raw.get("name")).trim().toLowerCase(Locale.ROOT);
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("表名不合法：" + name + "（小写字母开头，只允许字母数字下划线）");
            }
            if (!(raw.get("columns") instanceof List<?> rawColumns) || rawColumns.isEmpty()) {
                throw new IllegalArgumentException("表 " + name + " 没有列");
            }
            if (rawColumns.size() > MAX_COLUMNS) {
                throw new IllegalArgumentException("表 " + name + " 的列超过 " + MAX_COLUMNS + " 个");
            }
            List<Column> columns = new ArrayList<>();
            for (Object rawColumn : rawColumns) {
                String text = String.valueOf(rawColumn);
                int split = text.indexOf(':');
                if (split <= 0 || split == text.length() - 1) {
                    throw new IllegalArgumentException("列要写成 \"名字:类型\"，实际是 " + text);
                }
                String columnName = text.substring(0, split).trim().toLowerCase(Locale.ROOT);
                String type = text.substring(split + 1).trim().toUpperCase(Locale.ROOT);
                if (!IDENTIFIER.matcher(columnName).matches()) {
                    throw new IllegalArgumentException("列名不合法：" + columnName);
                }
                if (!TYPE.matcher(type).matches()) {
                    throw new IllegalArgumentException("列类型不支持：" + type);
                }
                columns.add(new Column(columnName, type));
            }
            parsed.add(new Table(name, columns));
        }
        return parsed;
    }

    private static String build(List<Table> tables, int rows) {
        StringBuilder sql = new StringBuilder();
        for (Table table : tables) {
            sql.append("CREATE TABLE ").append(table.name()).append(" (\n");
            List<Column> columns = table.columns();
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i);
                sql.append("  ").append(column.name()).append(' ').append(column.type());
                if ("id".equals(column.name())) {
                    sql.append(" PRIMARY KEY");
                }
                sql.append(i == columns.size() - 1 ? "\n" : ",\n");
            }
            sql.append(");\n");
        }
        for (Table table : tables) {
            sql.append(insert(table, rows));
        }
        return sql.toString().trim() + "\n";
    }

    private static String insert(Table table, int rows) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.name()).append(" (");
        List<Column> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(columns.get(i).name());
        }
        sql.append(") VALUES\n");
        for (int row = 1; row <= rows; row++) {
            sql.append("  (");
            for (int i = 0; i < columns.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append(valueOf(columns.get(i), row));
            }
            sql.append(row == rows ? ");\n" : "),\n");
        }
        return sql.toString();
    }

    /**
     * 值由「列名 + 行号 + 类型」决定，**不用随机数**：同一份参数永远得到同一份 SQL，
     * 测试与"再生成一次"都不会漂移。
     */
    private static String valueOf(Column column, int row) {
        int hash = Math.abs(31 * (column.name().hashCode() * 31 + row) + column.type().hashCode());
        String type = column.type();
        if ("TINYINT".equals(type)) {
            return String.valueOf(hash % 2);
        }
        if ("INT".equals(type) || "BIGINT".equals(type)) {
            return String.valueOf(hash % 1000 + 1);
        }
        if (type.startsWith("DECIMAL")) {
            return String.format(Locale.ROOT, "%.2f", (hash % 100_000) / 100.0);
        }
        if (type.startsWith("VARCHAR")) {
            return "'" + WORDS.get(hash % WORDS.size()) + "-" + (hash % 100) + "'";
        }
        LocalDate date = LocalDate.of(2026, 1, 1).plusDays(hash % 365);
        if ("DATE".equals(type)) {
            return "'" + date + "'";
        }
        return String.format(Locale.ROOT, "'%s 09:%02d:00'", date, hash % 60);
    }

    private static Integer intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
