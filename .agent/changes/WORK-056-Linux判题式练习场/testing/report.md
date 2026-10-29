---
artifact: test-report
work_id: WORK-056
work: Linux 判题式练习场
status: passed
evidence: required
workflow: full
created: 2026-10-29
updated: 2026-10-29
verified_commit: 1cbe16b
---

# WORK-056 验证报告

**结论先说**：判题式 Linux 练习场已上线。`/sandbox/status` 的 `languages` 里出现 `shell`，
六道题在**线上接口**上跑通：正确解 `passed`、错误解 `failed` 且带回"哪里不对"，
死循环 15 秒被拦成 `undetermined`，并发第二次 429，跨次不可见，审计只存哈希。

**新语言没有复制加固参数表**：`shell` 走 `DockerCommand.of("shell", …)` 的同一条组装路径，
参数只有一份；`ShellRunnerTest` + `DockerCommandTest` 逐条断言了
`--network=none`、`--read-only`、`--cap-drop=ALL`、`--no-new-privileges`、`--user 65534`。

## 验证环境

| 项 | 值 |
| --- | --- |
| 线上 | `codenest-online`，4 核 / 15.9G |
| 镜像 | `paideia-sandbox-shell@sha256:27facf7575…66686`（150MB，debian:12-slim + GNU 工具 + systemd） |
| 测量点 | 本机（公网）→ `http://36.212.5.130:8081` |
| 配置 | `shell-image` 已写入 `/opt/paideia-app/application.yml`（备份 `application.yml.bak-<时间戳>`） |

**耗时口径**：服务端日志 `durationMs` 800–1070ms（容器启动 0.7–1.7s），端到端 5–7s
全部花在公网往返与本机到云端链路上——不是产品侧变慢，SQL/Java 走同一条路也一样。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 关掉就不出现 | 单测 `未配置shell镜像时语言清单里没有shell`；线上 `GET /sandbox/status` | 0 | passed | 单测：`shellImage` 为空时 `languages()` 不含 shell（`DockerCommandTest`）；线上配置后 `languages=['spring','shell','sql','python','java']`。**"关掉时接口 503"只有代码路径与单测覆盖，没有在线上摘配置复验**（怕影响同机其它验证） |
| AC-002 正确解判通过 | `POST /sandbox/shell/run`，`chmod 600 app/secrets.env` | 0 | passed | `verdict=passed`、`judgeExit=0`；`diff` 显示 `-rw-------` 覆盖了原来的 `-rw-r--r--` |
| AC-003 错误解判未通过并说清原因 | 同上，`chmod 644` | 0 | passed | `verdict=failed`、`judgeExit=1`、判定说明 `现在是 644，期望 600`（见下方缺陷 1） |
| AC-004 死循环被墙钟拦住 | `while :; do :; done` | 0 | passed | 服务端 `durationMs=15286`、`finished=false`；接口 `verdict=undetermined`、`reason='超时（超过 15 秒）：检查有没有死循环或等输入'`；容器无残留 |
| AC-005 无限输出不拖垮接口 | `yes` | 0 | passed | 200 返回、`stdout` 7999 字节（容器内每节 `head -c 8000` 兜住），请求不挂起 |
| AC-006 并发第二次被拒 | 两个并发 `POST /sandbox/shell/run` | 0 | passed | 状态码 `[200, 429]`；被拒的不计次（`usedToday` 只 +1） |
| AC-007 断网、非 root、根只读 | 容器内 `id -u`、`/dev/tcp/1.1.1.1/80`、`touch /etc/x` | 0 | passed | `uid=65534`；`bash: /dev/tcp/1.1.1.1/80: Network is unreachable`；`touch: cannot touch '/etc/x': Read-only file system`；`/tmp` 可写 |
| AC-008 跨次不可见 | 第一次写 `app/only-mine.txt`，第二次 `ls app` | 0 | passed | 第二次只看到 seed 里的两个文件，`test -f app/only-mine.txt` 输出「看不见」 |
| AC-009 unit 文件判错 | 六题真机跑：正确/错误解各一次 | 0 | passed | 12 关正确解 `passed`；把 `ExecStart=java -jar …` 写成相对路径后 `failed`，判定说明含 `ExecStart 必须是绝对路径` |
| AC-010 审计无明文 | `select language, code_hash, code_length from code_runs where language='shell'` | 0 | passed | 13 行，只有 64 位哈希与长度（如 `a971bbe8…b01 / 23`），没有脚本文本列 |

### 六道题的真机双跑（AC-002/003/009 的细化证据）

在服务器上按**生产隔离参数**逐题跑（`/tmp/shelltest2.py`，一次性脚本，不入库），每题各跑一次
正确解与错误解：

- `linux-04-shell-chmod`：正确解 passed / 错误解 failed
- `linux-06-shell-grep-redirect`：正确解 passed / 错误解 failed
- `linux-07-shell-awk-sum`：正确解 passed / 错误解 failed
- `linux-09-shell-graceful-stop`：正确解 passed / 错误解 failed
- `linux-12-shell-unit-file`：正确解 passed / 错误解 failed
- `linux-17-shell-quote-fix`：正确解 passed / 错误解 failed

不符预期用例 **0**。

## 单元与内容门禁

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `scripts/test-db.sh mvn -B -pl paideia-sandbox test`（在 `backend/` 下跑） | **116 通过 / 0 失败**（新增 `ShellRunnerTest` 9 条、`DockerCommandTest` 3 条 shell 断言、`SandboxProjectValidationTest` 3 条 seed 校验） |
| 前端 | `npx vitest run`（ui 包） | **239 通过 / 1 失败**；失败是 `tokens.test.ts` 的 `AnalyticsPage` 调色板 12 处（2026-10-28 起的既有欠账，与本项无关） |
| 前端 | `npx tsc --noEmit`（ui / knowledge / app） | 三个包 0 错误 |
| 内容 | `python scripts/check_shell_cases.py` | 154 个动手块 / 6 道 shell / **0 问题** |
| 内容 | `python scripts/check_quiz_explain.py` | 256 个 quiz 块 / 755 道题 / 0 问题 |

## 实施中修掉的两个真缺陷

1. **判定说明写到了 stdout，而界面只渲染 stderr。** 作者用 `echo "现在是 644，期望 600"` 时
   它进的是 judge 的 stdout，于是 `verdict=failed` 但页面上没有任何理由——正是"判了但不说为什么"。
   首次上线后在接口上实测到（`judgeStdout` 有内容、`judgeStderr` 为空），改成两段都显示，
   并补了一条回归测试 `判定脚本用 echo 写的说明也会显示`。
2. **判定脚本原来只说"失败"不说原因。** `test` 失败时零输出，学习者只能猜。六道题的 judge
   全部改成带人话（现在的值、期望的值、常见错法），判定逻辑不变（真机双跑复验仍全对）。

## 发版的副作用（必须记录）

这次发版从 `feat/work-056-shell` 分支构建，分支基线包含另一个会话已提交到 master 的
WORK-055（spring 语言）代码；而线上 `spring-image` 本来就配着，**因此 `spring` 语言随本次发版
一起上线**（`/sandbox/status` 里能看到 `spring`）。那是已提交的代码，不是未提交的工作区改动，
但它由本次发版首次带上线——如果 WORK-055 还没验收完，请以那边为准。

## 未验

- 「`shell-image` 留空时接口 503」只有单测与代码路径，没有在线上摘配置复验。
- 前端页面的视觉效果没做人工走查（只有组件测试与接口验证）；`e2e` 未写（判题块依赖真容器，
  而本机没有 Docker）。
