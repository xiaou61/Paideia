---
artifact: tasks
work_id: WORK-056
work: Linux 判题式练习场
status: draft
created: 2026-10-29
updated: 2026-10-29
---

# Linux 判题式练习场实施任务

## 依据

- `requirements.md`（REQ-001…008 / AC-001…010，已签署）
- `proposal.md`（判题式，一次性容器，不池化、不终端）
- `design.md`（`SHELL_ENTRY`、协议块、判定口径、`shell` 围栏块契约）
- 代码位置：`backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/`、`frontend/packages/ui/src/markdown/renderers/`

## 全局约束

- **不复制加固参数表**：shell 走 `DockerCommand.of("shell", props, cidFile)` 的同一条组装路径，参数只在一处维护。
- 命令行里**不出现请求中的任何字符串**：入口是常量，`run.sh`/`judge.sh`/seed 全部经 stdin 分帧进入（与项目模式同一套协议）。
- 不新增表、不改既有 `run`/`sql` 题型行为、不改 `/sandbox/status` 的既有字段。
- 本机没有 Docker：单元测试只覆盖纯逻辑（命令组装、脚本内容、分帧字节、协议解析、校验），容器真实行为在 T7 线上验。
- 与 WORK-055 同时改 `SandboxProperties` / `DockerCommand`：只加分支，不动既有分支的取值；合并前先 `git log --all -- <文件>` 对一次。

## 接口块

字面一致，相邻任务按此实现：

```java
// SandboxProperties
public String getShellImage();                 public void setShellImage(String shellImage);
public String getShellMemory();                public void setShellMemory(String shellMemory);      // 默认 "256m"
public String getShellCpus();                  public void setShellCpus(String shellCpus);          // 默认 "0.5"
public int    getShellPidsLimit();             public void setShellPidsLimit(int shellPidsLimit);   // 默认 64
public long   getShellTimeoutMs();             public void setShellTimeoutMs(long shellTimeoutMs);  // 默认 15_000
public int    getShellDailyLimit();            public void setShellDailyLimit(int shellDailyLimit); // 默认 200
// 既有方法加分支：languages() / imageFor / memoryFor / cpusFor / pidsLimitFor / timeoutMsFor

// DockerCommand
static final String SHELL_ENTRY = """…design.md §接口与数据 的脚本…""";
static String scriptFor(String language)   // case "shell" -> SHELL_ENTRY

// ShellRunner
public record Seed(String path, String content) {}
public record Request(long userId, Long entryId, String caseId,
                      String script, String judge, List<Seed> seed) {}
public record Result(Verdict verdict, int runExit, int judgeExit,
                     String stdout, String stderr, String judgeStdout, String judgeStderr,
                     String diff, long durationMs, boolean truncated) {}
public enum Verdict { PASSED, FAILED, UNDETERMINED }
public Result run(Request request);

// SandboxController
@PostMapping("/shell/run") ShellRunView shellRun(@AuthenticationPrincipal SessionUser user,
                                                @Valid @RequestBody ShellRunRequest body);
record ShellRunRequest(Long entryId, String caseId, @NotBlank @Size(max = 20_000) String code,
                       @NotBlank @Size(max = 20_000) String judge,
                       @Size(max = 20) List<ProjectFile> seed) {}
record ShellRunView(String verdict, int runExit, int judgeExit, String stdout, String stderr,
                    String judgeStdout, String judgeStderr, String diff,
                    long durationMs, boolean truncated, int usedToday, int dailyLimit) {}
```

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| `shellImage` 留空时仍暴露 `shell`，前端渲染一个必然失败的运行按钮 | TASK-001 | `SandboxPropertiesTest#shellImage 为空时不暴露 shell` |
| 复用项目模式脚本时漏掉 `--network=none` / `--cap-drop=ALL`（复制粘贴改错） | TASK-002 | `DockerCommandTest#shell 命令含全部加固参数` |
| 学习者的 `echo '###RC'` 被当成判定结果 | TASK-002 | `DockerCommandTest#入口脚本把学习者输出重定向到文件` |
| 分帧字节数算错，seed 内容带换行被读成控制行 | TASK-003 | `ShellRunnerTest#分帧按字节写` |
| 协议块缺失时抛 500 而不是给出"未能判定" | TASK-003 | `ShellRunnerTest#缺协议块时为 undetermined` |
| 超时后容器仍在跑，占满两个槽位 | TASK-003 | `ShellRunnerTest#超时按 cidfile 强删` |
| 被并发闸挡下的请求扣了日配额 | TASK-004 | `SandboxControllerTest#拒绝时不记配额` |
| `seed` 路径带 `..` 写到 `/tmp` 之外 | TASK-004 | `SandboxControllerTest#seed 路径拒绝 ..` |
| 判定徽标把"未能判定"画成"未通过"，学习者以为自己做错了 | TASK-005 | `shell-challenge.test.tsx#三种徽标` |
| 题目 judge 写空，运行时永远通过 | TASK-006 | `check_shell_cases.py` |

## 任务

### TASK-001 | todo | 配置项与语言开关

- 对应：`REQ-001`、`REQ-006`
- 依赖：无
- 修改：`SandboxProperties.java`、`SandboxQuotaService.java`
- 接口：见「接口块」第一段；`SandboxQuotaService` 增加 `private static boolean isShell(String)` 与 `private int limitFor(String language)`，`remaining/usedToday/requireQuota` 三处按语言取限额
- 测试：`SandboxPropertiesTest`（新增 shell 开关与取值分支）、`SandboxQuotaServiceTest`（shell 走自己的上限，且不影响 java/python 的 50）
- 步骤：
  1. 加 6 个字段与注释：`shellImage` 为空 = 整体关闭（理由：渲染一个必然失败的按钮比不渲染更糟，与既有 `imageFor` 口径一致）。
  2. `languages()` / `imageFor` / `memoryFor` / `cpusFor` / `pidsLimitFor` / `timeoutMsFor` 各加一个 `"shell"` 分支，**不改** java/python/sql/spring 的取值。
  3. `SandboxQuotaService`：把 `isSql` 那三个分支泛化成"有独立上限的语言"（sql 500 / shell 200），其余走通用 50。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-sandbox test` → 0 failed；`languages()` 在 `shellImage` 为空时不含 shell。

### TASK-002 | todo | 容器入口脚本

- 对应：`REQ-002`、`REQ-004`、`REQ-005`
- 依赖：`TASK-001`
- 修改：`DockerCommand.java`
- 接口：`SHELL_ENTRY` 常量；`scriptFor("shell")` 返回它；`of(...)` 对 shell 沿用既有 sql 以外的分支（非 root 65534、tmpfs 64m、pids 64、nofile/nproc 64）
- 测试：`DockerCommandTest#shell 命令含全部加固参数`、`#入口脚本把学习者输出重定向到文件`、`#shell 走同一处组装`
- 步骤：
  1. 按 design.md 的脚本原文加常量，注释写明三条理由：`find -printf` 是 GNU 特性（选 debian 的原因）、快照含权限位、每节在容器内截断。
  2. `scriptFor` 加分支；确认 `of()` 里 `sql`/`spring` 的布尔分支对 shell 均为 false（不改它们）。
  3. 分帧沿用 `projectScriptFor` 的思路，但**shell 的路径前缀是 `work/`**（seed 进 `/tmp/in/work`，脚本进 `/tmp/in` 根）。
- 验证：`DockerCommandTest` 断言命令行包含 `--network=none`、`--read-only`、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、`--user 65534:65534`、`--pids-limit=64`、`--stop-timeout=0`、`--cidfile`。

### TASK-003 | todo | ShellRunner：执行与解析

- 对应：`REQ-002`、`REQ-003`、`REQ-004`
- 依赖：`TASK-002`
- 新建：`internal/run/ShellRunner.java`
- 接口：见「接口块」第三段
- 测试：`ShellRunnerTest`（分帧、五种解析、超时、截断）
- 步骤：
  1. 组装 stdin：`1 + 1 + seed.size()` 帧，先 `run.sh` 与 `judge.sh`，再把 seed 路径加 `work/` 前缀。按 UTF-8 字节数写 `<len>\n`，内容原样跟在其后。
  2. 起进程（`docker run` 参数来自 `DockerCommand.of`），读 stdout/stderr，`waitFor(timeoutMsFor("shell"))`。
  3. 超时：`destroyForcibly()` + 读 `--cidfile` 里的 id 执行 `docker rm -f`（对空文件容错）。
  4. 解析：按 `###RC / ###OUT / ###ERR / ###JUDGE / ###JUDGEERR / ###DIFF` 切块；缺 `###RC` → `UNDETERMINED`；`judgeExit == 0` → `PASSED` 否则 `FAILED`。
  5. 截断：若原始输出长度触及 `maxOutputBytes`，把 `truncated` 置 true（各节已在容器内 head -c，不会把协议块切坏）。
- 验证：`ShellRunnerTest` 全绿；其中 `#缺协议块时为 undetermined` 与 `#超时按 cidfile 强删` 必须能反向失败（去掉对应分支会红）。

### TASK-004 | todo | 端点与配额接线

- 对应：`REQ-001`、`REQ-003`、`REQ-006`
- 依赖：`TASK-003`
- 修改：`SandboxController.java`
- 接口：见「接口块」第四段
- 测试：`SandboxControllerTest#关闭时 503`、`#配额超限 429 且不起容器`、`#seed 路径拒绝 ..`、`#拒绝时不记配额`
- 步骤：
  1. 语言不可用（`shellImage` 空）→ `SandboxUnavailableException`（503），与既有语言同一口径。
  2. `RunSlot.acquire(userId)` → `requireQuota(userId, "shell")` → `run` → `record(...)`；任一闸拒绝都在启动容器之前返回。
  3. 响应里带 `usedToday`/`dailyLimit`（取自 `usedToday(userId, "shell")` 与 `getShellDailyLimit()`）。
  4. 审计 `code` 传 `script + "\n--judge--\n" + judge`（只存哈希与长度）。
- 验证：`scripts/test-db.sh mvn -B -pl paideia-sandbox test`；`SandboxControllerTest` 四条新用例绿。

### TASK-005 | todo | 前端题型与组件

- 对应：`REQ-007`
- 依赖：`TASK-004`（契约已定即可并行）
- 修改：`renderers/schema.ts`、`renderers/index.ts`、新增 `renderers/shell-challenge.tsx`
- 接口：`ShellChallenge { id, kind: 'shell', title, seed?: SeedFile[], starter?, judge, explain? }`；`SeedFile { path: string, content: string }`
- 测试：`blocks.test.tsx#shell 合法块`、`#缺 judge 不合法`、`#seed 路径带 .. 不合法`；`shell-challenge.test.tsx#语言不可用时不渲染运行按钮`、`#三种徽标`
- 步骤：
  1. schema 加 `shell` 分支，校验与 design.md 的前端契约一致（≤20 文件、单文件 ≤64000、总 ≤256000、路径正则）。
  2. 组件：文本域编辑器（沿用 run 题型的外观）、运行按钮、`passed/failed/undetermined` 徽标、stdout/stderr/judgeStderr 分区、`diff` 预格式展示。
  3. 注册表加 `{ language: ['shell-challenge'], component }`；`streaming` 模式不带（与既有块一致）。
- 验证：`pnpm --filter @paideia/ui test` 绿；`tsc --noEmit` 0 错误。

### TASK-006 | todo | 内容：6 道题与结构校验

- 对应：`REQ-005`、`REQ-008`
- 依赖：`TASK-005`
- 修改：`content/reading/linux-from-zero/04、06、07、09、17、12` 六关各加一个 `shell-challenge` 块；新增 `scripts/check_shell_cases.py`
- 接口：围栏块 JSON 见 design.md「前端围栏块契约」
- 测试：`python scripts/check_shell_cases.py`（`judge` 非空、`seed` 路径合法、`id` 全仓唯一、`starter` 含 TODO 或可替换点）
- 步骤：
  1. 六道题分别命中：权限（`chmod` 落到 600）、管道与重定向（把错误行筛进文件）、文本处理（`awk` 求和）、信号（先 TERM 再 KILL 的脚本）、Shell 脚本（修掉未加引号的 `rm`）、systemd unit（`systemd-analyze verify` 通过）。
  2. 每题的 `judge` 只读状态、不依赖时间与随机数；`explain` 解释判定在查什么。
  3. 校验脚本按 `check_quiz_explain.py` 的写法（全仓扫描、退出码 0/1）。
- 验证：校验脚本 0 处问题；`node scripts/import-course.mjs --dir content/reading/linux-from-zero` 幂等（第二次 `created 0 / updated 0`）。

### TASK-007 | todo | 部署与线上真跑

- 对应：`AC-001`…`AC-010` 里只能在真机验的部分
- 依赖：`TASK-004`、`TASK-005`、`TASK-006`
- 修改：部署配置（`PAIDEIA_SANDBOX_SHELL_IMAGE`）、部署说明（预拉镜像）、`updates.md`
- 接口：环境变量取值 `debian:12-slim@sha256:…`（执行时按实际拉取的 digest 填）
- 测试：线上逐条跑 AC
- 步骤：
  1. 服务器 `docker pull` 目标镜像并记 digest；把 `--format` 得到的 RepoDigest 写进配置。
  2. 发版；`GET /actuator/health` 200；`/sandbox/status` 的 `languages` 含 `shell`。
  3. 逐条验：通过（AC-002）、未通过（AC-003）、死循环超时（AC-004）、`yes` 截断（AC-005）、并发拒绝且不扣配额（AC-006）、容器内 `curl` 失败且 `id` 非 root（AC-007）、跨次隔离（AC-008）、unit 文件判错（AC-009）、审计表只有哈希（AC-010）。
  4. 关掉 `shellImage` 复验 AC-001（按钮消失、接口 503），再打开。
- 验证：把每条 AC 的实测输出写进 `.agent/testing/` 的报告；没有新鲜输出的条目写"未验"，不得写通过。

## 完成条件

- 七个任务的验证条目全部有新鲜输出；`AC-001`…`AC-010` 各有实测证据或明确标注未验。
- `scripts/test-db.sh mvn -B -pl paideia-sandbox test` 与前端 `ui` 包测试全绿。
- 内容侧 `check_shell_cases.py` 0 问题，导入幂等。
- 未新增表与迁移；既有 `run`/`sql` 题型的测试一条未改。
