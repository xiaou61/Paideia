package io.github.xiaou61.sandbox.internal.dataset;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 数据集 SQL 的语句白名单（WORK-035 T-09）。
 *
 * <p><b>为什么必须自己解析而不是靠 MySQL 拒</b>：`LOAD DATA`、`CREATE TRIGGER`、
 * `CREATE PROCEDURE`、`CREATE EVENT` 在 MySQL 里**都是合法语句**。沙箱本身有隔离，
 * 但数据集内容会被**别人的运行**反复执行——一旦里面藏了 `TRIGGER`，它在每次别人跑 SQL
 * 时都执行一次，等于把"一次性上传"变成"持续执行"。
 *
 * <p><b>为什么是白名单而不是黑名单</b>：MySQL 的语句种类会随版本增加，黑名单永远会漏。
 * 白名单漏掉的后果是"用户抱怨某个合法写法被拒"，可以补；黑名单漏掉的后果是"任意逻辑被执行"。
 *
 * <p><b>只做词法层判断，不做完整解析</b>：完整 SQL 解析器是一个大依赖，而这里要挡的是
 * "语句类型"这一层——`CREATE TABLE` 里能写的列定义不会带来执行能力。剩下的风险由
 * "干跑 + 沙箱隔离"兜住（见 {@link SqlDatasetValidator}）。
 */
public final class SqlStatementGuard {

    /** 允许的语句开头（已归一化：去注释、转大写、压空白）。 */
    private static final Set<String> ALLOWED = Set.of(
            "CREATE TABLE", "CREATE TEMPORARY TABLE",
            "CREATE INDEX", "CREATE UNIQUE INDEX", "CREATE VIEW",
            "ALTER TABLE",
            "INSERT", "INSERT IGNORE", "REPLACE");

    /**
     * 明确拒绝的片段。即使语句以允许的开头起步，只要体内出现这些就拒——
     * 它们能在**别的语句执行时**跑代码，或读宿主文件。
     */
    private static final List<String> FORBIDDEN = List.of(
            "TRIGGER", "PROCEDURE", "FUNCTION", "EVENT", "LOAD DATA", "LOAD_FILE",
            "INTO OUTFILE", "INTO DUMPFILE", "GRANT", "SET GLOBAL", "INSTALL PLUGIN");

    private SqlStatementGuard() {
    }

    /**
     * 检查一段数据集 SQL。
     *
     * @return 违规说明的列表；空表示通过
     */
    public static List<String> check(String sql) {
        List<String> problems = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            problems.add("内容是空的");
            return problems;
        }
        String withoutComments = stripComments(sql);
        String normalized = withoutComments.toUpperCase(Locale.ROOT);

        for (String forbidden : FORBIDDEN) {
            if (normalized.contains(forbidden)) {
                problems.add("不允许出现 " + forbidden + "：它会在别人执行时运行，或能读写宿主文件");
            }
        }

        List<String> statements = splitStatements(withoutComments);
        if (statements.isEmpty()) {
            problems.add("没有找到任何语句");
        }
        for (String statement : statements) {
            String head = head(statement);
            boolean allowed = ALLOWED.stream().anyMatch(head::startsWith);
            if (!allowed) {
                problems.add("不支持的语句：" + firstLine(statement) + "（只允许建表/建索引/建视图/插入）");
            }
        }
        return problems;
    }

    /**
     * 去掉 `--` 行注释与 `/* *\/` 块注释。
     *
     * <p>**必须先去掉再判**：否则 `/* TRIGGER *\/` 这种写法会误报，而
     * `-- x\nDROP ...` 会漏报（因为判开头时看到的是注释）。
     */
    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            // 行注释：-- 到行尾
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            // 块注释：/* 到 */
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                // `/*!50000 ... */` 是 MySQL 的**可执行**注释（版本条件指令），不是注释。
                // 把它当注释剔掉，校验看到的就没有这条语句，而真正执行的是原始 SQL——
                // 等于给白名单开了个后门。直接判为非法。
                if (index + 2 < sql.length() && sql.charAt(index + 2) == '!') {
                    throw new BizException(ErrorCode.INVALID_ARGUMENT,
                            "不支持 MySQL 的可执行注释（/*! ... */）");
                }
                index += 2;
                while (index + 1 < sql.length() && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
                    index++;
                }
                index += 2;
                continue;
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    /**
     * 按分号切语句。
     *
     * <p>**不处理引号内的分号**：数据集是建表与插数，值里带分号的情况极罕见；
     * 真出现时会被切成两段，第二段判不过——那是"拒绝一个合法输入"，不是安全问题。
     * 完整处理要写一个小型词法器，代价与收益不成比例。
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String part : sql.split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    /** 归一化语句开头：压空白、转大写。 */
    private static String head(String statement) {
        return statement.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String firstLine(String statement) {
        String trimmed = statement.trim();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }
}
