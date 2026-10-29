---
artifact: design
work_id: WORK-056
work: Linux 判题式练习场
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 17:02:06 +0800
approver_role: CTO
---

# Linux 判题式练习场设计

## 系统上下文

```text
浏览器（学习者端）
  └─ code-challenge 块 kind=shell
       └─ POST /api/v1/sandbox/shell/run
            └─ SandboxController.shellRun
                 ├─ RunSlot.acquire(userId)          全局 2 / 每人 1，拿不到即 429
                 ├─ SandboxQuotaService.requireQuota  shell 日上限，超了 429 且不启动容器
                 ├─ ShellRunner.run(...)             docker run 一次性容器
                 │    └─ DockerCommand.of("shell", props, cidFile)
                 └─ SandboxQuotaService.record(...)  写 code_run（language=shell）
```

容器内（一次性，`--rm`）：

```text
/tmp/in/{run.sh, judge.sh, work/**}     ← 宿主按分帧协议从 stdin 写入
/tmp/work/                              ← 学习者的工作目录（seed 拷进来）
entry.sh：快照 → run.sh → judge.sh → 快照 → 输出协议块
```

## 组件与职责

| 组件 | 位置 | 职责 | 不做什么 |
| --- | --- | --- | --- |
| `SandboxProperties` | `internal/run` | shell 的镜像、内存、CPU、pids、墙钟、日上限；`languages()` 开关 | 不做校验逻辑 |
| `DockerCommand` | `internal/run` | 组装 docker 命令行；`SHELL_ENTRY` 常量脚本 | 不解析结果 |
| `ShellRunner`（新） | `internal/run` | 分帧写 stdin、执行、超时强杀、解析协议块、按节截断 | 不碰配额与审计 |
| `SandboxQuotaService` | `internal/audit` | 按语言取限额；写审计 | 不启动容器 |
| `SandboxController` | `internal/web` | 端点、参数校验、`RunSlot`、把 `Result` 映射成 HTTP 语义 | 不拼命令行 |
| `shell-challenge.tsx`（新） | `packages/ui/.../renderers` | 编辑器、运行、判定徽标、输出、文件变更 | 不做判定逻辑 |

## 请求或事件流程

1. 前端取 `/sandbox/status`，`languages` 含 `shell` 才渲染运行按钮（否则整块降级为原始文本）。
2. 用户点运行 → 提交 `{entryId, caseId, code, judge, seed}`。
3. 控制器校验：`code` ≤ 20000 字符；`judge` 非空 ≤ 20000；`seed` ≤ 20 个文件、单文件 ≤ 64000、总量 ≤ 256000、路径匹配 `^[A-Za-z0-9_./-]+$` 且不含 `..`、不以 `/` 开头。
4. `RunSlot.acquire(userId)` → `quota.requireQuota(userId, "shell")` → `ShellRunner.run(...)`。
5. `ShellRunner` 把 `seed`（前缀 `work/`）、`run.sh`、`judge.sh` 按**已有的项目模式分帧协议**写进容器 stdin（首行文件数，之后每文件「路径一行、字节数一行、内容恰好 N 字节」），命令行的入口固定为 `sh -c SHELL_ENTRY`。
6. 容器执行完，stdout 是**只由 entry.sh 产出**的协议块（学习者脚本的 stdout/stderr 被重定向到文件，不会混进来）。
7. `ShellRunner` 解析：`###RC` 取两个退出码，`###OUT/###ERR/###JUDGE/###JUDGEERR/###DIFF` 取各节内容（每节已在容器内 `head -c` 截断）。
8. 控制器映射结果 → 写审计 → 返回。

## 接口与数据

### 容器内入口脚本（常量，`DockerCommand.SHELL_ENTRY`）

```sh
set -u
mkdir -p /tmp/work
cp -a /tmp/in/work/. /tmp/work/ 2>/dev/null || true
cd /tmp/work || exit 125

find . -printf '%M %s %p\n' 2>/dev/null | sort > /tmp/.before

sh /tmp/in/run.sh   > /tmp/.out 2> /tmp/.err;   rc=$?
sh /tmp/in/judge.sh > /tmp/.jout 2> /tmp/.jerr; jrc=$?

find . -printf '%M %s %p\n' 2>/dev/null | sort > /tmp/.after

echo '###RC';       echo "$rc $jrc"
echo '###OUT';      head -c 8000  /tmp/.out
echo '###ERR';      head -c 8000  /tmp/.err
echo '###JUDGE';    head -c 4000  /tmp/.jout
echo '###JUDGEERR'; head -c 4000  /tmp/.jerr
echo '###DIFF';     diff /tmp/.before /tmp/.after | head -c 8000
```

要点：

- **`find -printf` 是 GNU 的**，这是选 debian 而不是 alpine 的直接原因（另一条是 `sed -i`/`ps` 列与教学里讲的一致）。
- 快照含权限位（`%M`）与大小（`%s`），所以 `chmod` 这种"内容没变、只有权限变"的改动也体现在 diff 里——这正是权限题要判的东西。
- 学习者脚本的 stdout/stderr 全部落文件，**协议块不可伪造**；它只能写到 `/tmp/work` 里，diff 会把这些文件如实列出来。
- 每节在容器内截断，所以最坏情况协议输出约 32KB，稳定在 `maxOutputBytes`（64KB）以内。
- `run.sh` 与 `judge.sh` 放在 `/tmp/in`，不在 `/tmp/work`，不污染 diff。

### 后端类型（新增）

```java
// internal/run/ShellRunner.java
@Component
public class ShellRunner {
    public record Seed(String path, String content) {}

    public record Request(long userId, Long entryId, String caseId,
                          String script, String judge, List<Seed> seed) {}

    public record Result(Verdict verdict, int runExit, int judgeExit,
                         String stdout, String stderr, String judgeStdout, String judgeStderr,
                         String diff, long durationMs, boolean truncated) {}

    public enum Verdict { PASSED, FAILED, UNDETERMINED }

    public Result run(Request request);
}
```

```java
// SandboxController 追加
@PostMapping("/shell/run")
ShellRunView shellRun(@AuthenticationPrincipal SessionUser user,
                      @Valid @RequestBody ShellRunRequest body);

record ShellRunRequest(Long entryId, String caseId,
                       @NotBlank @Size(max = 20_000) String code,
                       @NotBlank @Size(max = 20_000) String judge,
                       @Size(max = 20) List<ProjectFile> seed) {}

record ShellRunView(String verdict, int runExit, int judgeExit, String stdout, String stderr,
                    String judgeStdout, String judgeStderr, String diff,
                    long durationMs, boolean truncated, int usedToday, int dailyLimit) {}
```

`verdict` 取值固定为 `passed` / `failed` / `undetermined`（小写，前端按字符串匹配，不做枚举映射）。

### 判定口径

| 情况 | verdict | 说明 |
| --- | --- | --- |
| 协议块完整且 `judgeExit == 0` | `passed` | `runExit` 不影响结论：有些题本来就该让学习者看到失败的命令 |
| 协议块完整且 `judgeExit != 0` | `failed` | 把 `judgeStderr` 原样回给学习者 |
| 墙钟超时 | `undetermined` | 附 `reason=超时`；容器由 `docker rm -f <cidfile>` 补刀 |
| 输出被截断且协议块缺失 | `undetermined` | 附 `reason=输出超过上限，判定信息被截断` |
| `docker run` 失败（镜像不在、daemon 不可用） | HTTP 503 | 走既有 `SandboxUnavailableException` |

### 前端围栏块契约（`kind: "shell"`）

```json
{
  "id": "linux-04-chmod",
  "kind": "shell",
  "title": "把密钥文件收成只有属主可读",
  "seed": [{"path": "app/secrets.env", "content": "TOKEN=demo\n"}],
  "starter": "cd app\nchmod ??? secrets.env\n",
  "judge": "test \"$(stat -c %a app/secrets.env)\" = \"600\"",
  "explain": "640 让同组可读，密钥文件要 600：只有属主读写。"
}
```

校验（`parseCodeChallenge` 的 `shell` 分支）：`judge` 非空 ≤ 20000；`seed` 0–20 项、`path` 匹配 `^[A-Za-z0-9_./-]+$` 且不含 `..`、`content` ≤ 64000、总量 ≤ 256000；`starter` 可选 ≤ 20000。

## 持久化与迁移

零迁移。`code_run.language` 存 `"shell"`；`code_run` 已存 `code_hash` 与 `code_length`，不存明文（AC-010）。

## 失败处理与恢复

- **超时**：`ShellRunner` 用 `properties.timeoutMsFor("shell")`（默认 15 秒）`waitFor`；超时后 `destroyForcibly`，再按 `--cidfile` 里记的容器 id 执行 `docker rm -f`（既有做法，`--rm` 只在容器自己退出时生效）。
- **容器启动失败 / 镜像缺失**：`docker run` 非 0 且 stdout 无协议块 → 视为不可用，503，**不写**审计里的 `passed/failed`（写 `ERROR`）。
- **协议块解析失败**：`undetermined`，把已收到的原始输出回给前端（便于自查），不抛 500。
- **学习者脚本自杀容器**（`kill -9 1`、`exec` 破坏 entry）：协议块缺失 → `undetermined`。

## 安全与权限

- 一条都不能少的加固参数（`DockerCommand.of` 里已固定，shell 走同一条组装）：`--network=none`、`--read-only`、`--tmpfs /tmp:rw,size=64m,exec`、`--user 65534:65534`、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、`--memory`/`--memory-swap` 相等、`--cpus`、`--pids-limit=64`、`--ulimit nofile=64`、`--ulimit nproc=64`、`--stop-timeout=0`、`--cidfile`。
- 端点需要登录；`RunSlot` 每人 1 防"一个账号占满两个槽"。
- 日上限 `shellDailyLimit`（默认 200）是熔断器，不是预算。
- `--network=none` 让"提交 `curl`"这类题在教学上表现为"确实连不上"——这是内容里要写清的既有事实，不是缺陷。

## 可观测性

- 审计 `code_run`：`language=shell`、`status`、退出码（judge 的）、耗时、输出字节数、是否截断、`code_hash`（= `sha256(script + "\n--judge--\n" + judge)`）。
- 现有 `/sandbox/status` 已返回按语言的今日用量，shell 走同一个 `usedToday(userId, "shell")`，不新增字段。
- 不做专门的指标埋点：现有条数足够回答"这个功能有没有人用"。

## 实施顺序

1. T1 配置与开关（`shellImage` 为空即关闭）→ 可先上线一个"完全不可见"的状态。
2. T2 容器命令与入口脚本。
3. T3 `ShellRunner`。
4. T4 端点。
5. T5 前端 schema + 组件。
6. T6 内容 6 道题 + 结构校验。
7. T7 部署与线上真跑（镜像预拉、配置项、6 道题各跑一次）。

## 测试策略

| 层 | 测什么 | 位置 |
| --- | --- | --- |
| 单测 | `languages()` 随 `shellImage` 开关；资源取值分支 | `SandboxPropertiesTest`（既有） |
| 单测 | 命令行含全部加固参数；入口脚本是常量且含四个 `###` 标记；分帧字节数正确 | `DockerCommandTest` |
| 单测 | 协议块解析：正常/缺 `###RC`/截断/超时；`verdict` 三分支 | `ShellRunnerTest` |
| 单测 | 控制器：关闭时 503；配额超限 429 且不启动容器；`seed` 路径校验拒绝 `..` | `SandboxControllerTest`（既有 + 新用例） |
| 单测 | schema：`shell` 块合法/非法（缺 judge、路径带 `..`、超量） | `blocks.test.tsx` |
| 组件 | 语言不可用时不渲染运行按钮；`passed/failed/undetermined` 三种徽标 | `shell-challenge.test.tsx` |
| 内容 | 每题 `judge` 非空、`seed` 路径合法、`id` 全局唯一 | `scripts/check_shell_cases.py` |
| 真机 | AC-002/003/004/005/007/008/009（本机无 Docker） | 线上一次（T7） |

## 需求追踪

| REQ | 设计落点 | 任务 |
| --- | --- | --- |
| REQ-001 | `languages()` / `imageFor` / `timeoutMsFor` | T1 |
| REQ-002 | `SHELL_ENTRY` + 分帧复用 | T2 / T3 |
| REQ-003 | 判定口径表 | T3 / T4 |
| REQ-004 | 协议块 + 分节截断 + diff | T2 / T3 / T5 |
| REQ-005 | `seed` → `/tmp/in/work/**` | T2 / T3 |
| REQ-006 | `RunSlot` + `requireQuota(…,"shell")` | T4 |
| REQ-007 | schema `shell` + `shell-challenge.tsx` | T5 |
| REQ-008 | 6 道题 + 校验脚本 | T6 |

## 待决定事项

无。
