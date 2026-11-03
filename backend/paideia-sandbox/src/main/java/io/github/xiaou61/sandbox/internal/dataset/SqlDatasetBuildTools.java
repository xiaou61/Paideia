package io.github.xiaou61.sandbox.internal.dataset;

import io.github.xiaou61.platform.PaideiaTool;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolDescriptor;
import io.github.xiaou61.platform.ToolResult;
import io.github.xiaou61.platform.ToolScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 生成一份练习数据集的 SQL（WORK-053；2026-11-03 改成"模型出数据，工具只拼装"）。
 *
 * <p><b>为什么不自己造数据</b>：早先按「列名 + 行号 + 类型」算哈希填值——外键指向谁、status 该是
 * 什么、日期该不该在今天之前，它一概不知道，产出的数据 JOIN 不上（2026-11-03 用户实测）。
 * 现在模型逐行给出数据，工具只做三件事：校验取值、转义、拼成 CREATE + INSERT。
 *
 * <p><b>为什么不干脆让模型写 SQL</b>：拼装与转义留在一处，产出必须能过
 * {@link SqlStatementGuard} 与保存时的沙箱干跑；模型只负责它擅长的语义（谁引用谁、坑留在哪）。
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

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATETIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    private static final int MAX_TABLES = 3;
    private static final int MAX_COLUMNS = 12;
    /** 模型要逐行给出数据：200 行会把一次工具调用撑到几万 token，50 行够教学用。 */
    private static final int MAX_ROWS = 50;

    private SqlDatasetBuildTools() {
    }

    private record Column(String name, String type) {
    }

    private record Table(String name, List<Column> columns, List<Map<String, Object>> rows) {
    }

    @Component
    public static class BuildDataset implements PaideiaTool {

        @Override
        public ToolDescriptor descriptor() {
            Map<String, Object> columnList = Map.of(
                    "type", "array",
                    "description", "列定义，如 [\"id:INT\", \"amount:DECIMAL(10,2)\"]",
                    "items", Map.of("type", "string"));
            Map<String, Object> rows = Map.of(
                    "type", "array",
                    "description", "每行一个对象：键是列名，值按列类型给（字符串给字符串、日期给 "
                            + "yyyy-MM-dd / yyyy-MM-dd HH:mm:ss、数字给数字；null 表示 NULL）。"
                            + "外键要指向真实存在的 id；日期不要超过今天；可以故意留几行对不上的数据做练习。",
                    "items", Map.of("type", "object"));
            Map<String, Object> table = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "description", "表名，如 orders"),
                            "columns", columnList,
                            "rows", rows));
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "tables", Map.of(
                                    "type", "array",
                                    "description", "表定义与数据，1–3 张",
                                    "items", table)),
                    "required", List.of("tables"));
            return new ToolDescriptor(
                    "planner_build_dataset",
                    "planner",
                    "把表结构和每行数据拼成数据集 SQL",
                    "用户要「造一个数据集」时用它：**你先逐行想好数据**（谁引用谁、status 取什么值、"
                            + "日期、要不要故意留几条对不上的行做练习），再把表结构与 rows 一起交给它。"
                            + "它只做校验与拼装，不替你编数据。**只返回 SQL 文本、不会保存**——"
                            + "用户要在 SQL 工作台点保存才会落库（保存时还要过白名单与沙箱干跑）。"
                            + "列写成 \"列名:类型\"；类型只支持 INT / BIGINT / TINYINT / "
                            + "DECIMAL(p,s) / VARCHAR(n) / DATE / DATETIME；"
                            + "最多 3 张表、每表 12 列、每表 1–50 行；标识符必须小写字母开头。",
                    schema, null, true, false, false, ToolScope.READ, false);
        }

        @Override
        public ToolResult call(ToolContext context, Map<String, Object> arguments) {
            Object rawTables = arguments.get("tables");
            if (!(rawTables instanceof List<?> tables) || tables.isEmpty()) {
                return ToolResult.fail("需要提供 tables（1–" + MAX_TABLES + " 张表的定义与数据）");
            }
            if (tables.size() > MAX_TABLES) {
                return ToolResult.fail("最多 " + MAX_TABLES + " 张表，这次给了 " + tables.size() + " 张");
            }
            try {
                return ToolResult.ok(build(parse(tables)));
            } catch (IllegalArgumentException bad) {
                return ToolResult.fail(bad.getMessage());
            }
        }
    }

    private static List<Table> parse(List<?> rawTables) {
        List<Table> parsed = new ArrayList<>();
        for (Object item : rawTables) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("每张表要是对象：{name, columns, rows}");
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
            if (!(raw.get("rows") instanceof List<?> rawRows) || rawRows.isEmpty()) {
                throw new IllegalArgumentException("表 " + name + " 没有数据：rows 要逐行给出对象");
            }
            if (rawRows.size() > MAX_ROWS) {
                throw new IllegalArgumentException("表 " + name + " 有 " + rawRows.size() + " 行，超过上限 " + MAX_ROWS);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < rawRows.size(); i++) {
                if (!(rawRows.get(i) instanceof Map<?, ?> rawRow)) {
                    throw new IllegalArgumentException("表 " + name + " 第 " + (i + 1) + " 行不是对象");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (Column column : columns) {
                    if (!rawRow.containsKey(column.name())) {
                        throw new IllegalArgumentException("表 " + name + " 第 " + (i + 1)
                                + " 行缺列 " + column.name() + "（NULL 也要写成 null，键不能省）");
                    }
                    row.put(column.name(), rawRow.get(column.name()));
                }
                for (Object key : rawRow.keySet()) {
                    String keyName = String.valueOf(key).trim().toLowerCase(Locale.ROOT);
                    if (columns.stream().noneMatch(column -> column.name().equals(keyName))) {
                        throw new IllegalArgumentException("表 " + name + " 第 " + (i + 1) + " 行有多余的列 " + key);
                    }
                }
                rows.add(row);
            }
            parsed.add(new Table(name, columns, rows));
        }
        return parsed;
    }

    private static String build(List<Table> tables) {
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
            sql.append(insert(table));
        }
        return sql.toString().trim() + "\n";
    }

    private static String insert(Table table) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.name()).append(" (");
        List<Column> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(columns.get(i).name());
        }
        sql.append(") VALUES\n");
        List<Map<String, Object>> rows = table.rows();
        for (int i = 0; i < rows.size(); i++) {
            sql.append("  (");
            for (int c = 0; c < columns.size(); c++) {
                Column column = columns.get(c);
                sql.append(c == 0 ? "" : ", ")
                        .append(literal(column, rows.get(i).get(column.name()),
                                "表 " + table.name() + " 第 " + (i + 1) + " 行"));
            }
            sql.append(i == rows.size() - 1 ? ");\n" : "),\n");
        }
        return sql.toString();
    }

    private static String literal(Column column, Object value, String where) {
        if (value == null) {
            return "NULL";
        }
        String type = column.type();
        if (type.startsWith("VARCHAR")) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException(where + " 的 " + column.name() + " 要是字符串");
            }
            return quoted(text, where, column.name());
        }
        if (type.equals("DATE") || type.equals("DATETIME")) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException(where + " 的 " + column.name() + " 要是日期字符串");
            }
            return quoted(temporal(text, type, where, column.name()), where, column.name());
        }
        return number(column, value, where);
    }

    private static String number(Column column, Object value, String where) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw new IllegalArgumentException(where + " 的 " + column.name() + " 要是数字");
        }
        BigDecimal number;
        try {
            number = new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(where + " 的 " + column.name() + " 不是数字：" + value);
        }
        if (column.type().startsWith("DECIMAL")) {
            return number.toPlainString();
        }
        if (number.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(where + " 的 " + column.name() + " 要是整数：" + value);
        }
        return number.toBigInteger().toString();
    }

    private static String temporal(String text, String type, String where, String column) {
        if (type.equals("DATE")) {
            if (!DATE.matcher(text).matches()) {
                throw new IllegalArgumentException(where + " 的 " + column + " 要是 yyyy-MM-dd：" + text);
            }
            try {
                LocalDate.parse(text);
            } catch (DateTimeParseException bad) {
                throw new IllegalArgumentException(where + " 的 " + column + " 日期不合法：" + text);
            }
            return text;
        }
        if (!DATETIME.matcher(text).matches()) {
            throw new IllegalArgumentException(where + " 的 " + column + " 要是 yyyy-MM-dd HH:mm:ss：" + text);
        }
        try {
            LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException bad) {
            throw new IllegalArgumentException(where + " 的 " + column + " 时间不合法：" + text);
        }
        return text;
    }

    /**
     * shortcut: 含换行或反斜杠的字符串直接拒绝，而不是按方言转义——MySQL 默认把 `\` 当转义符、
     * `NO_BACKSLASH_ESCAPES` 下又不是；练习数据用不到它。真需要时再按保存库的方言处理。
     */
    private static String quoted(String text, String where, String column) {
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(where + " 的 " + column + " 含换行或反斜杠：" + text);
        }
        return "'" + text.replace("'", "''") + "'";
    }
}
