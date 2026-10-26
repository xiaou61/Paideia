package io.github.xiaou61.sandbox.internal.dataset;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.sandbox.SqlDatasetGeneratorPort;
import io.github.xiaou61.sandbox.SqlQuestionPort;
import io.github.xiaou61.sandbox.internal.audit.SandboxQuotaService;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.CodeRunner;
import io.github.xiaou61.sandbox.internal.run.RunSlot;
import io.github.xiaou61.sandbox.internal.run.SqlDatasets;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * AI 出题与造数据（WORK-035 T-12 / T-13）。
 *
 * <p><b>出题为什么必须真跑参考解</b>：期望结果集只能来自实际执行。让模型自己说"结果应该是
 * 3 行"等于把判分权交给一个会编的东西——那种题一旦判错，用户会以为自己写错了。
 *
 * <p><b>造数据为什么必须干跑</b>：不跑的话用户会拿到一个永远起不来的数据集，
 * 而发现时机是**别人用它做题的时候**。
 */
@Service
public class SqlAiService {

    /** 参考解跑出来的结果集最多留多少行：题目通常只有几行，超出说明题出得不合适。 */
    private static final int MAX_EXPECTED_ROWS = 200;

    private final SqlQuestionPort questions;
    private final SqlDatasetGeneratorPort generators;
    private final SqlDatasets datasets;
    private final SqlDatasetUploadService uploads;
    private final CodeRunner runner;
    private final SandboxQuotaService quota;
    private final RunSlot slots;

    SqlAiService(SqlQuestionPort questions, SqlDatasetGeneratorPort generators,
                 SqlDatasets datasets, SqlDatasetUploadService uploads,
                 CodeRunner runner, SandboxQuotaService quota, RunSlot slots) {
        this.questions = questions;
        this.generators = generators;
        this.datasets = datasets;
        this.uploads = uploads;
        this.runner = runner;
        this.quota = quota;
        this.slots = slots;
    }

    /**
     * 现场出一道题，并把它在数据集上真跑一遍。
     *
     * @return 题目 + 期望结果集；模型不可用或跑不通时抛可读错误
     */
    public GeneratedQuestion generateQuestion(long userId, String datasetKey, String goal) {
        String schema = datasets.schema(userId, datasetKey);
        if (schema == null) {
            // 与"无权访问"同一句：区分了就给出可枚举的 oracle
            throw new BizException(ErrorCode.NOT_FOUND, "没有这个数据集");
        }

        SqlQuestionPort.Result generated =
                questions.generate(new SqlQuestionPort.Request(datasetKey, schema, goal));
        if (generated.questions().isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "AI 这次没有出题成功（可能没配置凭据或上游超时），可以先自己写一句试试");
        }
        SqlQuestionPort.Question question = generated.questions().get(0);

        // 参考解必须先过语句门禁再执行：这是模型产出的 SQL，和用户上传同一条信任边界。
        // 门禁原本只在上传路径生效，这里不过就等于让模型绕过白名单直接跑任意语句。
        SqlStatementGuard.check(question.referenceSql());

        // 参考解必须在同一个数据集上真跑：期望结果集只能来自实际执行
        CodeRun.Result result = runOn(userId, datasetKey, question.referenceSql());
        if (result.exitCode() != 0 || result.timedOut() || result.outOfMemory()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "AI 的参考解在这个数据集上跑不通，这题没法判分：" + shorten(result.stderr()));
        }
        List<CodeRun.Table> tables = result.tables();
        if (tables.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "AI 的参考解没有返回结果集，这题没法判分");
        }
        CodeRun.Table expected = tables.get(0);
        if (expected.rows().size() > MAX_EXPECTED_ROWS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "AI 的参考解返回了 " + expected.rows().size() + " 行，题目范围太大，换个方向再试");
        }

        return new GeneratedQuestion(question.stem(), question.criteria(), question.explain(),
                expected.columns(), expected.rows());
    }

    /**
     * 让 AI 造一个数据集并直接保存为私人数据集。
     *
     * <p>**必须过上传的同一套检查**（白名单 + 干跑）：复用 `SqlDatasetUploadService`，
     * 不为 AI 开一条更宽松的路。
     */
    public String generateDataset(long userId, String topic) {
        SqlDatasetGeneratorPort.Result generated =
                generators.generate(new SqlDatasetGeneratorPort.Request(topic));
        if (generated.sql() == null || generated.sql().isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "AI 这次没有生成数据集（可能没配置凭据或上游超时）");
        }
        // 走上传流程：白名单、大小、干跑、抓结构，一条都不少
        String name = generated.name() == null || generated.name().isBlank()
                ? "AI 生成的数据集"
                : generated.name();
        return uploads.upload(userId, name, generated.sql());
    }

    private CodeRun.Result runOn(long userId, String datasetKey, String sql) {
        if (!runner.ready()) {
            throw new BizException(ErrorCode.SANDBOX_DISABLED,
                    runner.unavailableReason() == null ? "代码执行不可用" : runner.unavailableReason());
        }
        quota.requireQuota(userId, "sql");
        CodeRun.Result result;
        try (RunSlot.Slot ignored = slots.acquire(userId)) {
            result = runner.run(new CodeRun.Request("sql", sql, datasets.sql(userId, datasetKey), "ai-question", false));
        }
        // 执行完必须记账：不记录的话 requireQuota 永远看到同一个已用数，
        // 这条路径可以无限触发容器而不消耗配额。
        quota.record(userId, null, "ai-question", "sql",
                SandboxQuotaService.statusOf(result), result.exitCode(), result.durationMs(),
                result.stdout().length() + result.stderr().length(), result.truncated(), sql);
        return result;
    }

    private static String shorten(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "（没有错误输出）";
        }
        String trimmed = stderr.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }

    /** 一道生成的题。**期望结果集随题下发**，判题在客户端（与服务端不存答案同一条纪律）。 */
    public record GeneratedQuestion(String stem, String criteria, String explain,
                                    List<String> columns, List<List<String>> rows) {
    }
}
