package io.github.xiaou61.sandbox.internal.web;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.sandbox.internal.audit.SandboxQuotaService;
import io.github.xiaou61.sandbox.internal.run.CodeRun;
import io.github.xiaou61.sandbox.internal.run.CodeRunner;
import io.github.xiaou61.sandbox.internal.run.RunSlot;
import io.github.xiaou61.sandbox.internal.run.SandboxProperties;
import io.github.xiaou61.sandbox.internal.run.SqlDatasets;
import io.github.xiaou61.sandbox.internal.trace.SandboxTraceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代码执行端点（WORK-015）。
 *
 * <p><b>权限口径</b>：挂在 {@code /api/v1/sandbox/**}，只需"已登录"——它既不是管理功能，
 * 也不能免登录（执行别人写的代码要能追溯）。归属一律取自令牌，请求体里没有 userId。
 *
 * <p><b>它不判题</b>：只回实际输出。期望值留在客户端，所以服务端没有题库、也没有"隐藏用例"，
 * 也就不会出现"服务端知道答案但用户可以猜到"这类问题。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/sandbox")
class SandboxController {

    /** 项目模式允许的文件名：只认相对路径与安全字符集（无空格、无引号、无 `$`）。 */
    private static final java.util.regex.Pattern PROJECT_PATH =
            java.util.regex.Pattern.compile("[A-Za-z0-9_./-]+");

    private final SandboxProperties properties;
    private final CodeRunner runner;
    private final SandboxQuotaService quota;
    private final RunSlot slots;
    private final SandboxTraceService trace;
    private final SqlDatasets datasets;
    private final io.github.xiaou61.sandbox.internal.run.ShellRunner shellRunner;

    SandboxController(SandboxProperties properties, CodeRunner runner,
                      SandboxQuotaService quota, RunSlot slots,
                      SandboxTraceService trace, SqlDatasets datasets,
                      io.github.xiaou61.sandbox.internal.run.ShellRunner shellRunner) {
        this.properties = properties;
        this.runner = runner;
        this.quota = quota;
        this.slots = slots;
        this.trace = trace;
        this.datasets = datasets;
        this.shellRunner = shellRunner;
    }

    /**
     * 能力与用量。
     *
     * <p>前端先问它，再决定要不要渲染「运行」：能力不可用时按钮根本不该存在，
     * 而不是点了才收到一个 503。
     */
    @GetMapping("/status")
    ApiResponse<StatusView> status() {
        long userId = currentUserId();
        boolean ready = runner.ready();
        // 配额按语言分档：SQL 20 次/天，其它 50 次/天（WORK-032 U-09）。
        // 两个数**分开给**——合成一个会让用户以为额度是共用的。
        return ApiResponse.ok(new StatusView(
                properties.isEnabled(),
                ready,
                properties.getRuntime().isBlank() ? null : properties.getRuntime(),
                List.copyOf(properties.languages()),
                properties.getDailyLimit(),
                quota.usedToday(userId),
                properties.getSqlDailyLimit(),
                quota.usedToday(userId, "sql"),
                // 选择器要的清单：内置公开集 + 自己的私人集 + 已发布的社区集。
                // **隐藏集不在这里**——它进了选择器，用户跑一句 SELECT * 就拿到内容。
                // 运行端点仍认全部内置 key（题面本来就明文写着它）。
                datasets.options(userId),
                ready ? null : runner.unavailableReason()));
    }

    /**
     * 数据集的结构（WORK-035 T-03）。
     *
     * <p>给编辑器补全与「表结构」侧栏用。**只读、不含数据行**，所以它不占配额、不起容器。
     *
     * <p>**隐藏集在这里也被拒**：返回 404 而不是空对象。虽然 key 在课程题面里是明文，
     * 但这个端点的用途是"渲染一个选择器里的结构"，隐藏集不在选择器里，给它结构没有意义，
     * 而返回结构等于确认了 key 有效。
     */
    @GetMapping("/datasets/{key}/schema")
    ApiResponse<String> datasetSchema(@PathVariable String key) {
        if (datasets.publicKeys().contains(key) == false
                && datasets.options(currentUserId()).stream().noneMatch(option -> option.key().equals(key))) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有这个数据集");
        }
        String schema = datasets.schema(currentUserId(), key);
        if (schema == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "这个数据集还没有结构快照");
        }
        return ApiResponse.ok(schema);
    }

    /**
     * 项目模式校验（WORK-038）。返回空列表表示"不是项目模式"。
     *
     * <p>**路径是信任边界**：它会被拼进容器的 shell 脚本里写文件，所以只允许相对路径与
     * 安全字符集（无空格、无引号、无 `$`），并拒绝 `..`。三个上限（文件数 / 单文件 / 总量）
     * 都在这里挡，超了直接拒绝而不是截断——截断后的代码编译不过，报错会指向用户看不懂的地方。
     */
    List<CodeRun.FileEntry> validateProject(RunRequest body, String language) {
        if (body.files() == null || body.files().isEmpty()) {
            return List.of();
        }
        if ("sql".equals(language)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "SQL 不支持项目模式");
        }
        return validateFiles(body.files());
    }

    /**
     * 文件列表校验（项目模式与 Linux 练习共用）。
     *
     * <p>判题题的初始文件（seed）和项目模式的源码是同一条信任边界：都会被拼进容器的写文件
     * 脚本里，所以只允许相对路径与安全字符集，并拒绝 `..`。
     */
    List<CodeRun.FileEntry> validateFiles(List<ProjectFile> files) {
        if (files.size() > properties.getMaxProjectFiles()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "一次最多提交 " + properties.getMaxProjectFiles() + " 个文件");
        }
        List<CodeRun.FileEntry> entries = new java.util.ArrayList<>();
        int total = 0;
        for (ProjectFile file : files) {
            String path = file.path();
            if (!PROJECT_PATH.matcher(path).matches() || path.startsWith("/") || path.contains("..")) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "文件名不合法：" + path);
            }
            String content = file.content() == null ? "" : file.content();
            if (content.length() > properties.getMaxProjectFileChars()) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        path + " 超过 " + properties.getMaxProjectFileChars() + " 字符");
            }
            total += content.length();
            entries.add(new CodeRun.FileEntry(path, content));
        }
        if (total > properties.getMaxProjectTotalChars()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "全部文件合计超过 " + properties.getMaxProjectTotalChars() + " 字符");
        }
        return List.copyOf(entries);
    }

    @PostMapping("/run")
    ApiResponse<RunView> run(@Valid @RequestBody RunRequest body) {
        long userId = currentUserId();
        String language = body.language().trim().toLowerCase(java.util.Locale.ROOT);

        if (!properties.isSupported(language)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "只支持 " + String.join(" / ", properties.languages()));
        }
        List<CodeRun.FileEntry> files = validateProject(body, language);
        boolean project = !files.isEmpty();
        // Spring 项目（WORK-055）：骨架在镜像里，用户必须提交文件覆盖它。放行单文件模式会
        // 落到一个"没有 pom 的 mvn"上，报错信息对用户毫无意义。
        if ("spring".equals(language) && !project) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "Spring 项目必须以项目模式提交（需要 files）");
        }
        // 项目模式下 code 只进审计：真正的执行内容是 files。
        String code = body.code() == null ? "" : body.code();
        if (!project && code.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "代码不能为空");
        }
        if (code.length() > properties.getMaxCodeChars()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "代码超过 " + properties.getMaxCodeChars() + " 字符");
        }
        // SQL 的"输入"是**数据集 key**，不是自由文本：内容由服务端按白名单取。
        // 隐藏数据集因此不随题面下发——客户端只拿到 key，拿不到数据与期望结果。
        String stdin;
        if ("sql".equals(language)) {
            String dataset = body.dataset();
            if (dataset == null || dataset.isBlank()) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "SQL 运行必须指定数据集");
            }
            // 必须带 userId：私人集只有本人能跑（WORK-035 的安全边界）。
            // 无权访问与不存在都返回同一句文案——区分了就给出一个可枚举的 oracle。
            stdin = datasets.sql(userId, dataset);
            if (stdin == null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知数据集：" + dataset);
            }
        } else {
            stdin = body.stdin() == null ? "" : body.stdin();
            if (stdin.length() > properties.getMaxStdinChars()) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "输入超过 " + properties.getMaxStdinChars() + " 字符");
            }
        }
        if (!runner.ready()) {
            quota.record(userId, body.entryId(), body.caseId(), language,
                    CodeRun.Status.DISABLED, null, 0, 0, false, code);
            throw new BizException(ErrorCode.SANDBOX_DISABLED,
                    runner.unavailableReason() == null ? "代码执行不可用" : runner.unavailableReason());
        }

        // 配额超了也要留审计：否则用户说"我明明没跑几次"时无从对账。
        // 审计写 rejected，而 countSince 排除 rejected —— 重试不会把自己永久锁死。
        try {
            quota.requireQuota(userId, language);
        } catch (BizException rejected) {
            quota.record(userId, body.entryId(), body.caseId(), language,
                    CodeRun.Status.REJECTED, null, 0, 0, false, code);
            throw rejected;
        }

        // explain 只对 SQL 有意义（WORK-035 T-05）：别的语言没有"执行计划"这个概念，
        // 静默忽略会让调用方以为拿到了计划而其实跑了一遍代码。
        boolean explain = body.explain() != null && body.explain();
        if (explain && !"sql".equals(language)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "只有 SQL 支持查看执行计划");
        }

        CodeRun.Result result;
        try (RunSlot.Slot ignored = slots.acquire(userId)) {
            result = runner.run(new CodeRun.Request(language, code, stdin, body.caseId(), explain, files));
        } catch (BizException rejected) {
            // 并发被拒：写一条 rejected 审计（不计入次数，见 countSince）
            quota.record(userId, body.entryId(), body.caseId(), language,
                    CodeRun.Status.REJECTED, null, 0, 0, false, code);
            throw rejected;
        }

        int outputBytes = result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        quota.record(userId, body.entryId(), body.caseId(), language,
                SandboxQuotaService.statusOf(result), result.exitCode(), result.durationMs(),
                outputBytes, result.truncated(), code);

        return ApiResponse.ok(new RunView(result.stdout(), result.stderr(), result.exitCode(),
                result.durationMs(), result.timedOut(), result.outOfMemory(), result.truncated(),
                result.tables().stream()
                        .map(table -> new TableView(table.statementIndex(), table.columns(), table.rows()))
                        .toList()));
    }

    /**
     * 逐步录制（WORK-023 REQ-001）。
     *
     * <p>与 `/run` **同一套鉴权、配额、并发槽与审计**：录制跑的也是用户代码，
     * 只是多记了一些东西。不另开口子——那会让"配额"变成一张可以绕过的纸。
     *
     * <p>只支持 Python：Java 要 JDI + 双 JVM + 调试端口，一期明确不做（REQ 非目标）。
     * 不支持时返回 **422 + 明确原因**，前端据此退回"一次性运行"（REQ-012）。
     */
    @PostMapping("/trace")
    ApiResponse<TraceView> trace(@Valid @RequestBody RunRequest body) {
        long userId = currentUserId();
        String language = body.language().trim().toLowerCase(java.util.Locale.ROOT);

        if (!properties.isSupported(language)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "只支持 java 与 python");
        }
        // 逐步录制只支持单文件：项目模式（files）不参与
        if (body.code() == null || body.code().isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "代码不能为空");
        }
        if (body.code().length() > properties.getMaxCodeChars()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "代码超过 " + properties.getMaxCodeChars() + " 字符");
        }
        String stdin = body.stdin() == null ? "" : body.stdin();
        if (stdin.length() > properties.getMaxStdinChars()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "输入超过 " + properties.getMaxStdinChars() + " 字符");
        }
        if (!runner.ready()) {
            throw new BizException(ErrorCode.SANDBOX_DISABLED,
                    runner.unavailableReason() == null ? "代码执行不可用" : runner.unavailableReason());
        }

        try {
            quota.requireQuota(userId);
        } catch (BizException rejected) {
            quota.record(userId, body.entryId(), body.caseId(), language,
                    CodeRun.Status.REJECTED, null, 0, 0, false, body.code());
            throw rejected;
        }

        SandboxTraceService.Trace result;
        try (RunSlot.Slot ignored = slots.acquire(userId)) {
            result = trace.trace(language, body.code(), stdin);
        } catch (UnsupportedOperationException unsupported) {
            // 降级不是错误：前端会退回"一次性运行"，所以给可读的原因而不是 500
            throw new BizException(ErrorCode.INVALID_ARGUMENT, unsupported.getMessage());
        } catch (BizException rejected) {
            quota.record(userId, body.entryId(), body.caseId(), language,
                    CodeRun.Status.REJECTED, null, 0, 0, false, body.code());
            throw rejected;
        }

        // 超时与否看 reason：录制器在超时被杀时会把 "time_limit" 写进最后一条能读到的 end 事件
        boolean timedOut = "time_limit".equals(result.reason());
        quota.record(userId, body.entryId(), body.caseId(), language,
                timedOut ? CodeRun.Status.TIMEOUT : CodeRun.Status.OK,
                null, result.durationMs(), 0, result.truncated(), body.code());
        return ApiResponse.ok(new TraceView(result.steps(), result.output(),
                result.reason(), result.truncated(), result.durationMs()));
    }

    record TraceView(List<SandboxTraceService.TraceStep> steps, String output,
                     String reason, boolean truncated, long durationMs) {
    }

    /**
     * Linux 判题练习（WORK-056）。
     *
     * <p><b>为什么单独一个端点</b>：结论不是"输出等于什么"，而是"judge 退出码是不是 0"。
     * 塞进 {@code /run} 会把返回形状与判定语义都变成方言——`/run` 的契约是"只回实际输出，
     * 判定在客户端"，而这里判定必须在服务端：**判定脚本不能发给客户端**。
     *
     * <p>与 `/run` 同一套并发闸、配额与审计；被判题需要的三样东西由这一个请求带齐。
     */
    @PostMapping("/shell/run")
    ApiResponse<ShellRunView> shellRun(@Valid @RequestBody ShellRunRequest body) {
        long userId = currentUserId();
        if (!properties.isSupported("shell")) {
            throw new BizException(ErrorCode.SANDBOX_DISABLED, "Linux 练习不可用（未配置镜像）");
        }
        List<CodeRun.FileEntry> seed = body.seed() == null || body.seed().isEmpty()
                ? List.of()
                : validateFiles(body.seed());
        String code = body.code();
        String judge = body.judge();

        if (!runner.ready()) {
            quota.record(userId, body.entryId(), body.caseId(), "shell",
                    CodeRun.Status.DISABLED, null, 0, 0, false, code);
            throw new BizException(ErrorCode.SANDBOX_DISABLED,
                    runner.unavailableReason() == null ? "代码执行不可用" : runner.unavailableReason());
        }
        // 配额与并发都**在起容器之前**拒绝；被拒的不计入次数（见 countSince 排除 REJECTED）
        try {
            quota.requireQuota(userId, "shell");
        } catch (BizException rejected) {
            quota.record(userId, body.entryId(), body.caseId(), "shell",
                    CodeRun.Status.REJECTED, null, 0, 0, false, code);
            throw rejected;
        }

        io.github.xiaou61.sandbox.internal.run.ShellRunner.Result result;
        try (RunSlot.Slot ignored = slots.acquire(userId)) {
            List<io.github.xiaou61.sandbox.internal.run.ShellRunner.Seed> seeds = seed.stream()
                    .map(file -> new io.github.xiaou61.sandbox.internal.run.ShellRunner.Seed(
                            file.path(), file.content()))
                    .toList();
            result = shellRunner.run(code, judge, seeds, body.caseId());
        } catch (BizException rejected) {
            quota.record(userId, body.entryId(), body.caseId(), "shell",
                    CodeRun.Status.REJECTED, null, 0, 0, false, code);
            throw rejected;
        }

        // `failed` 是**正常结果**（学习者答错了），不是错误：审计记 OK，退出码记 judge 的。
        CodeRun.Status status = switch (result.verdict()) {
            case "passed", "failed" -> CodeRun.Status.OK;
            default -> result.reason() != null && result.reason().startsWith("超时")
                    ? CodeRun.Status.TIMEOUT
                    : CodeRun.Status.RUNTIME_ERROR;
        };
        int outputBytes = (result.stdout() + result.stderr() + result.judgeStderr() + result.diff())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // 审计只存哈希与长度：判定脚本和正文一起进哈希，便于回溯"哪一版题"
        quota.record(userId, body.entryId(), body.caseId(), "shell", status,
                "undetermined".equals(result.verdict()) ? null : result.judgeExit(),
                result.durationMs(), outputBytes, result.truncated(),
                code + "\n--judge--\n" + judge);

        return ApiResponse.ok(new ShellRunView(result.verdict(), result.runExit(), result.judgeExit(),
                result.stdout(), result.stderr(), result.judgeStdout(), result.judgeStderr(),
                result.diff(), result.durationMs(), result.truncated(), result.reason(),
                quota.usedToday(userId, "shell"), properties.getShellDailyLimit()));
    }

    record ShellRunRequest(@Positive Long entryId,
                           @Size(max = 64) String caseId,
                           @NotBlank @Size(max = 20_000) String code,
                           // 判定脚本随题下发；它只进容器，不回传给客户端
                           @NotBlank @Size(max = 20_000) String judge,
                           @Size(max = 20) List<ProjectFile> seed) {
    }

    record ShellRunView(String verdict, int runExit, int judgeExit, String stdout, String stderr,
                        String judgeStdout, String judgeStderr, String diff,
                        long durationMs, boolean truncated, String reason,
                        int usedToday, int dailyLimit) {
    }

    record StatusView(boolean enabled, boolean ready, String runtime, List<String> languages,
                      int dailyLimit, int usedToday,
                      // SQL 单独一档（WORK-032 U-09）：额度不与其它语言共用
                      int sqlDailyLimit, int sqlUsedToday,
                      // 可选数据集（WORK-035）：key + 名字 + 来源。内容仍由服务端持有。
                      List<SqlDatasets.DatasetOption> datasets,
                      String reason) {
    }

    record RunRequest(
            @NotBlank @Size(max = 16) String language,
            // 项目模式下 code 可以为空：内容都在 files 里，code 只进审计。
            @Size(max = 20_000) String code,
            @Size(max = 4_000) String stdin,
            // SQL 专用：数据集 key，服务端按白名单解析成建表与插数
            @Size(max = 64) String dataset,
            // code_runs.entry_id 是 BIGINT UNSIGNED：负数会让审计 INSERT 报 Out of range，
            // 而那时代码已经真的跑完了 —— 除了 500，countSince 还会漏记，等于送了一条免配额通道。
            @Positive Long entryId,
            @Size(max = 64) String caseId,
            // SQL 专用（WORK-035 T-05）：true 时跑 EXPLAIN FORMAT=TREE 而不是执行语句。
            // 可空：老客户端不带这个字段时按 false 处理。
            Boolean explain,
            // 项目模式（WORK-038）：多文件。非空时 code 不参与执行；路径与总量在这里校验。
            @Size(max = 20) List<ProjectFile> files) {
    }

    /** 项目模式的一个文件。 */
    record ProjectFile(@NotBlank @Size(max = 200) String path,
                       @Size(max = 64_000) String content) {
    }

    /**
     * 令牌主体 → 账号 id。合法令牌必然是数字（账号主键自增），非数字只可能来自伪造或版本错配。
     */
    private static long currentUserId() {
        try {
            return Long.parseLong(io.github.xiaou61.security.CurrentUser.subjectId());
        } catch (NumberFormatException cause) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "令牌主体不是有效的账号标识");
        }
    }

    /** 一个结果集（WORK-032）。只有 SQL 会返回；其它语言是空数组。 */
    record TableView(int statementIndex, List<String> columns, List<List<String>> rows) {
    }

    record RunView(String stdout, String stderr, int exitCode, long durationMs,
                   boolean timedOut, boolean outOfMemory, boolean truncated,
                   List<TableView> tables) {
    }
}
