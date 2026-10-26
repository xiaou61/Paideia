package io.github.xiaou61.sandbox.internal.dataset;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.sandbox.DatasetStorage;
import io.github.xiaou61.sandbox.internal.audit.SandboxQuotaService;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.CodeRunner;
import io.github.xiaou61.sandbox.internal.run.RunSlot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据集上传与校验（WORK-035 T-09）。
 *
 * <p><b>三道检查，顺序不能换</b>：
 * <ol>
 *   <li><b>大小</b>——最便宜，先挡掉明显过大的；</li>
 *   <li><b>语句白名单</b>——纯字符串判断，不起容器；</li>
 *   <li><b>沙箱干跑 + 抓结构</b>——最贵（3–5 秒 + 一次配额），只在前面都过了才做。</li>
 * </ol>
 * 顺序反过来会让一个 10 MB 的垃圾文件先烧掉一次容器启动。
 *
 * <p><b>为什么必须干跑</b>：不跑的话用户会拿到一个永远起不来的数据集，而发现时机是
 * **别人用它做题的时候**——那时既不知道是谁传的、也不知道错在哪。
 *
 * <p><b>干跑与抓结构在同一次运行里做完</b>：两次运行要各起一个容器（各 3–5 秒 + 各一次配额）。
 * 数据集已经灌好了，顺手查一次 `information_schema` 是免费的。
 *
 * <p><b>为什么先试后存而不是先存后试</b>：先存后试失败要回滚两处（表 + 存储），
 * 而回滚失败会留下一个"存在但不可用"的数据集。先试后存只有一处写入。
 */
@Service
public class SqlDatasetUploadService {

    private static final Logger log = LoggerFactory.getLogger(SqlDatasetUploadService.class);

    /** SQL 文件大小上限。512 KB 足够放几十万行的建表+插数，也挡住误传的大文件。 */
    static final int MAX_BYTES = 512 * 1024;

    /**
     * 干跑时附带的"取结构"查询。
     *
     * <p>它与用户的数据集在**同一个容器、同一个库**里跑，所以拿到的是真实的表结构。
     * 注意它必须排在数据集之后——容器脚本先灌数据集、再执行这里的 SQL。
     */
    private static final String PROBE = """
            SELECT c.TABLE_NAME AS tbl, c.COLUMN_NAME AS col, c.COLUMN_TYPE AS typ,
                   c.IS_NULLABLE AS nullable, c.COLUMN_KEY AS colkey
            FROM information_schema.COLUMNS c
            WHERE c.TABLE_SCHEMA = 'practice'
            ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION;
            """;

    private final SqlDatasetMapper mapper;
    private final DatasetStorage storage;
    private final CodeRunner runner;
    private final SandboxQuotaService quota;
    private final RunSlot slots;

    SqlDatasetUploadService(SqlDatasetMapper mapper, DatasetStorage storage, CodeRunner runner,
                            SandboxQuotaService quota, RunSlot slots) {
        this.mapper = mapper;
        this.storage = storage;
        this.runner = runner;
        this.quota = quota;
        this.slots = slots;
            }

    /**
     * 上传一个私人数据集。
     *
     * @return 新数据集的 key
     */
    @Transactional
    public String upload(long userId, String name, String sql) {
        validateInput(name, sql);

        // 第 2 道：白名单（纯字符串，不起容器）
        List<String> problems = SqlStatementGuard.check(sql);
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, String.join("；", problems));
        }

        // 第 3 道：干跑 + 抓结构。**在写入任何东西之前**——失败时不该留下半成品。
        String schemaJson = dryRunAndProbe(userId, sql);

        String key = UUID.randomUUID().toString();
        String storageId = storage.put(sql.getBytes(StandardCharsets.UTF_8), "application/sql");
        SqlDatasetSchemaCounts counts = SqlDatasetSchemaReader.count(schemaJson);

        mapper.insert(key, name.trim(), "private", userId, storageId, schemaJson,
                counts.tables(), countRows(sql), sql.getBytes(StandardCharsets.UTF_8).length,
                "draft", java.time.Instant.now());
        return key;
    }

    /**
     * 干跑：把 SQL 灌进一次性容器，同时抓表结构。
     *
     * <p>**用一次性 caseId 而不是先落表再试**：落表后失败要回滚两处，而回滚失败会留下
     * "存在但不可用"的数据集。这里跑完即弃。
     *
     * <p>它**占一次配额**：确实起了一个容器。这与"上传不是免费的"一致。
     *
     * @return 结构 JSON；抓不到时返回 null（数据集本身已经通过，结构缺失不该让上传失败）
     */
    private String dryRunAndProbe(long userId, String sql) {
        if (!runner.ready()) {
            throw new BizException(ErrorCode.SANDBOX_DISABLED,
                    runner.unavailableReason() == null ? "代码执行不可用" : runner.unavailableReason());
        }
        quota.requireQuota(userId, "sql");

        // 数据集作为 stdin，PROBE 作为用户 SQL：容器先灌数据集、再跑探测
        CodeRun.Result result;
        // 必须按 userId 取槽：上传干跑会真起一个容器（3–5 秒），用无参 acquire 会绕过
        // 「每人并发 1」的上限，一个用户可以并发发起多个上传把全局槽占满。
        try (RunSlot.Slot ignored = slots.acquire(userId)) {
            result = runner.run(new CodeRun.Request("sql", PROBE, sql, "dataset-upload", false));
        }

        if (result.exitCode() == 0 && !result.timedOut() && !result.outOfMemory()) {
            List<CodeRun.Table> tables = result.tables();
            return tables.isEmpty() ? null : SqlDatasetSchemaReader.toJson(tables.get(0));
        }
        // 数据集导入失败有专门退出码（9），与"SQL 本身写错"区分开
        if (result.exitCode() == 9) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "数据集导入失败：" + shorten(result.stderr()));
        }
        if (result.timedOut()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "数据集导入超时——数据量可能太大");
        }
        if (result.outOfMemory()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "数据集导入时内存超限——数据量太大");
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "数据集跑不通：" + shorten(result.stderr()));
    }

    /**
     * 行数：数 `INSERT` 语句里 `(...)` 组的个数太脆弱，这里**按语句数近似**。
     *
     * <p>它只用于界面展示"这个数据集大概多大"，不参与任何判定。精确行数要跑一次 COUNT，
     * 而那要再起一个容器——为展示一个数字不值得。
     */
    private static int countRows(String sql) {
        int count = 0;
        for (String statement : SqlStatementGuard.splitStatements(sql)) {
            if (statement.trim().toUpperCase(java.util.Locale.ROOT).startsWith("INSERT")
                    || statement.trim().toUpperCase(java.util.Locale.ROOT).startsWith("REPLACE")) {
                count++;
            }
        }
        return count;
    }

    private static void validateInput(String name, String sql) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "数据集需要一个名字");
        }
        if (name.trim().length() > 100) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "名字最长 100 个字符");
        }
        if (sql == null || sql.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "文件是空的");
        }
        int bytes = sql.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "文件太大（" + bytes / 1024 + " KB），上限 " + MAX_BYTES / 1024 + " KB");
        }
    }

    /** stderr 可能很长；只留前几行给用户看，完整内容进日志。 */
    private static String shorten(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "（没有错误输出）";
        }
        String trimmed = stderr.trim();
        log.debug("数据集干跑失败：{}", trimmed);
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }
}
