---
artifact: test-report
work_id: WORK-015
work: 代码执行沙箱与可运行练习
status: passed
evidence: required
verified_commit: a71b411
verified_at: 2026-10-18
accepted_by: xiaou61
accepted_at: 2026-10-29 13:17:50 +0800
---

# 代码执行沙箱验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 本机 | Windows / Git Bash，JDK 25、Maven、Node 24。**没有 Docker**——所以真容器只在服务器验 |
| 服务器 | `codenest-online`（4C/16G），Docker 已有，`paideia` systemd 单元，端口 8081 |
| 镜像 | `eclipse-temurin:21-jdk-alpine`（digest `sha256:0bfc69a4…`）、`python:3.12-alpine` |
| 测试库 | 服务器 MySQL `paideia_test`，公网直连 |
| 部署 | `scripts/release.ps1 -SkipWeb`（只改后端）；配置写进服务器 `application.yml` 的 `paideia:` 段 |

## 结论：**通过**

练习现在能真的跑代码：Java 与 Python 在一次性加固容器里执行，只回 stdout / stderr / 退出码 / 耗时，判定留在客户端。**服务端不存源码、不存用例、不做判题**。

三条值得单独记：

1. **没有网络是真的没有**：容器带 `--network=none`，`urllib` 访问外网直接 `BLOCKED URLError`。这条比"相信配置"重要——它是唯一能证明"跑别人的代码也不能被当代理"的证据。
2. **超时精确到墙钟**：死循环在 10005ms 被终止（上限 10000），`timedOut=true`；API 全程正常响应。
3. **被拒的请求也写审计但不计配额**：`countSince` 排除 `rejected`/`disabled`，否则用户点满 50 次之后每次重试都会被永久挡住。这条是集成测试里唯一先红后修的（第一版忘了写 `rejected` 行）。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 能跑（Java/Python） | 服务器 `curl POST /api/v1/sandbox/run` | 0 | passed | Python `print(sum(range(5)))` → `stdout="10\n"`、exit 0、992ms；Java 打印 42 → `stdout="42\n"`、exit 0、4139ms |
| AC-002 无网络 | 同上 | 0 | passed | `urllib.request.urlopen("https://example.com")` → `stdout="BLOCKED URLError\n"`、exit 0 |
| AC-003 超时终止 | 同上 | 0 | passed | `while True: pass` → `timedOut=true`、`durationMs=10005`、exit -1 |
| AC-004 内存上限 | 服务器 `docker run -m 256m` + `SandboxQuotaService.statusOf` 单测 | 0 | passed | 退出码 137 + `Killed` → `OUT_OF_MEMORY`（映射在 `statusOf` 里，有集成用例 `timeoutIsAResultNotAnError`） |
| AC-005 并发保护 | `SandboxIntegrationTest` + `RunSlot` 实现 | 0 | passed | 信号量 2、`tryAcquire(2s)` 失败抛 429；不排队 |
| AC-006 配额与计费口径 | `SandboxIntegrationTest#quotaExhaustion` | 0 | passed | 第 51 次 429「明天再来」；被拒 2 次后有效计数仍为 **50**，`rejected` 行有 2 条 |
| AC-007 审计可用且无源码 | `SandboxIntegrationTest#returnsOutputAndAuditsWithoutSource` + 服务器 SQL | 0 | passed | 审计行含 language/status/exit/duration/code_length/code_hash；`code_hash` 64 位且不含源码标记 |
| AC-008 隔离证明 | `DockerCommandTest` + 服务器网络用例 | 0 | passed | 6 条单测逐条断言 `--network=none / --read-only / --user 65534 / --cap-drop=ALL / --no-new-privileges / --pids-limit`，且**断言命令行里没有任何 `-v`/`--mount`** |
| AC-009 关闭开关 | `pnpm --filter @paideia/app test:e2e -- source-reading.spec.ts` | 0 | passed | 本地（默认关闭）运行题不渲染按钮、显示「没有启用「运行」」，小测等其他块照常 |
| AC-010 不影响既有部署 | 服务器 `systemctl` + 线上 e2e | 0 | passed | 服务 active、健康检查通过；线上学习者端 e2e 27 passed / 10 skipped（7 条失败为既有种子依赖，见下） |
| 编译失败分类 | 服务器 `curl` | 0 | passed | `public class Main{ not java }` → `exitCode=1`、stderr 带 javac 报错、`status=RUNTIME_ERROR` |
| 前端运行面板 | `pnpm --filter @paideia/ui test` | 0 | passed | 4 条：无端口不渲染按钮、通过/失败、超时算失败、期望与实际都显示 |
| 前端判定 | 同上 + schema 测试 | 0 | passed | `judgeRun`：退出码非 0 / 超时 / OOM 一律失败；`trim:false` 逐字符比较 |

## 实际跑过的数

- 后端：`paideia-sandbox` 单测 **6 passed**；`SandboxIntegrationTest` **8 passed**
- 前端：`pnpm -r test` **236 passed**（ui 144 / knowledge 28 / assistant 25 / core 22 / auth 13 / desktop 4）
- 学习者端 e2e（本地）：**45 passed**
- 服务器真机：5 次运行，耗时 0.99s / 4.14s / 10.00s（超时）/ 7.38s / 2.80s
- 迁移：`V13__create_code_runs.sql`，服务器 `Successfully validated 13 migrations`

## 如实登记的未覆盖项与偏差

1. **线上 e2e 有 7 条失败，与本项无关**：它们断言的是本地种子内容（`credits-balance` 期望 0、带表格的种子条目、助手对话历史），线上库里没有那些数据。改动前后都存在，本轮未修。
2. **本机没有 Docker**：`DockerCodeRunner` 的真实进程路径在本机无法验证，只验了命令构造（单测）与服务器真机。若将来改这段代码，**必须重新上服务器验**。
3. **gVisor 未启用**：`runtime` 配置留好了但没装 `runsc`。当前隔离是"加固容器"，挡不住内核漏洞——这是写进文档的天花板。
4. **内存上限只验到分类映射**，没有专门造一个真的 OOM 程序（Python 在 256m 里触发 OOM 需要构造大分配，且不同基础镜像的 OOM 提示不一致）。`statusOf` 的映射有单测，但没有端到端的"真 OOM"证据。
5. **应用容器化的前提未满足**：现方案依赖宿主 Docker socket。若把应用打进容器，必须改成独立 runner 服务（design 已登记）。
6. **审计表在服务器上是 `paideia` 生产库**，验证产生的 5 行记录留在了生产库里（`e2e-learner` 的运行记录）。这是真机验证的正常留痕，未清理。
7. 前端**没有**针对"真沙箱返回"的 e2e（本机跑不了 Docker）；运行面板只在组件层用假端口验过。
