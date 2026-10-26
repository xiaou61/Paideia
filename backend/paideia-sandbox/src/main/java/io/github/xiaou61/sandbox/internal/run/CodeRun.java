package io.github.xiaou61.sandbox.internal.run;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.List;

/**
 * 执行一次运行请求 / 结果（WORK-015）。
 *
 * <p>这几个 record 是**模块的对外契约**：控制器、Docker 实现、测试里的假实现都只认它们。
 * 加字段时想清楚"服务端到底该不该知道这件事"——比如判定需要的"期望输出"**刻意不在这里**，
 * 它在客户端，服务端只回实际输出。
 */
public final class CodeRun {

    private CodeRun() {
    }

    /**
     * 一次运行请求。`caseId` 只进审计，不参与执行。
     *
     * <p>`explain` 是 SQL 专用（WORK-035 T-05）：为真时不执行语句，而是把它包进
     * `EXPLAIN FORMAT=TREE` 跑，回的是执行计划。**写操作在计划模式下不会真的落数据**，
     * 所以调用方不能把它当成"跑一遍看看"。
     */
    public record Request(String language, String code, String stdin, String caseId, boolean explain) {

        public Request {
            if (explain) {
                // 脚本只给第一段加 EXPLAIN 前缀，第二条语句会被**真实执行**。
                // 这里就挡住：计划模式只接受单条语句，且去掉结尾分号
                // （`EXPLAIN ... SELECT ...;` 本身也是语法错误）。
                String sql = code == null ? "" : code.strip();
                while (sql.endsWith(";")) {
                    sql = sql.substring(0, sql.length() - 1).stripTrailing();
                }
                if (sql.contains(";")) {
                    throw new BizException(ErrorCode.INVALID_ARGUMENT, "看执行计划时只能有一条 SQL 语句");
                }
                code = sql;
            }
        }

        /** 不带 explain 的形态：Java/Python 与既有调用点用它。 */
        public Request(String language, String code, String stdin, String caseId) {
            this(language, code, stdin, caseId, false);
        }
    }

    /**
     * 一次运行结果。
     *
     * <p>`timedOut` / `oom` 是**结果**不是错误：程序崩了也是有价值的反馈，接口仍然 200。
     * 只有"根本没能执行"（配额、并发、沙箱不可用）才是错误。
     */
    /**
     * 录制结果（WORK-023）。
     *
     * <p>`events` 是录制器写在 stderr 上的原始行（每行一个 JSON）。**不在这里解析**：
     * 解析要能容忍"超时被杀、最后一行不完整"，那是服务层的事，执行器只负责搬运。
     */
    public record TraceResult(
            String stdout,
            String events,
            long durationMs,
            boolean timedOut) {
    }

    public record Result(
            String stdout,
            String stderr,
            int exitCode,
            long durationMs,
            boolean timedOut,
            boolean outOfMemory,
            boolean truncated,
            List<Table> tables) {

        /**
         * 没有结构化结果时的形态（Java/Python）。保留这个重载，老调用点不必改动：
         * 只有 SQL 会产出 {@code tables}，其它语言的 stdout 就是全部。
         */
        public Result(String stdout, String stderr, int exitCode, long durationMs,
                      boolean timedOut, boolean outOfMemory, boolean truncated) {
            this(stdout, stderr, exitCode, durationMs, timedOut, outOfMemory, truncated, List.of());
        }
    }

    /**
     * 一个结果集（WORK-032）。
     *
     * <p>**值一律是字符串**：类型信息来自题目声明，归一化在判题侧做。JSON 数字在两侧各转一次
     * 就会丢精度——`DECIMAL` 的 92.50 一旦变成浮点，那个尾零再也还不出。
     *
     * <p>{@code statementIndex} 是 SQL 里第几条语句（从 0 数）。`BEGIN`/`COMMIT` 这类不产生
     * 结果集的语句不占位，所以它不连续。
     */
    public record Table(int statementIndex, List<String> columns, List<List<String>> rows) {
    }

    /** 审计用的状态分类。值是存进库的，**改名等于改数据**。 */
    public enum Status {
        OK,
        COMPILE_ERROR,
        RUNTIME_ERROR,
        TIMEOUT,
        OUT_OF_MEMORY,
        /** 结果集超过行数或字节上限（WORK-032）。与超时不同：查询跑完了，只是结果太大。 */
        OVER_LIMIT,
        REJECTED,
        DISABLED
    }
}
