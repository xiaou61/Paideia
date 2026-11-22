---
artifact: design
work_id: WORK-015
work: 代码执行沙箱与可运行练习
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-014, WORK-009]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 08:15:39 +0800
approver_role: CTO
---

# 设计 · 代码执行沙箱与可运行练习

## 系统上下文

```text
学习者浏览器
  └─ code-challenge (kind=run)
       ├─ GET  /api/v1/sandbox/status   → 能力（enabled / docker / runtime / languages）
       └─ POST /api/v1/sandbox/run      → { stdout, stderr, exitCode, durationMs, timedOut, oom, truncated }
                                             │
                                             ▼
                                    SandboxController
                                      ├─ 配额（当天次数）
                                      ├─ Semaphore(2)
                                      └─ CodeRunner
                                           └─ DockerCodeRunner → ProcessBuilder("docker", "run", ...)
                                                 └─ 一次性容器（无网络、只读根、非 root、cap 全丢）
```

判定不在服务端：客户端拿 `stdout` 与用例比对。服务端不存题目、不存源码。

## 组件与职责

| 组件 | 职责 | 不做什么 |
| --- | --- | --- |
| `paideia-sandbox`（模块） | 端口、Docker 实现、禁用实现、配置、配额与审计、控制器 | 不认识知识库/条目 |
| `DockerCodeRunner` | 起容器、喂代码、收输出、超时终止、分类失败 | 不判题、不存源码 |
| `DisabledCodeRunner` | 能力不可用时快速失败 | — |
| `SandboxQuotaService` | 按天计数、写审计 | 不扣积分（与 WORK-009 分开算） |
| `RunAuditMapper` | 插入审计行 | 不查源码 |
| 前端 `RunChallenge` | 编辑器、运行、结果呈现、用例比对 | 不直接调 docker |

## 请求或事件流程

### 运行

1. 前端确认 `status.enabled && status.ready`；否则按钮不渲染（显示原因）。
2. `POST /sandbox/run`，body：`{language, code, stdin?, caseId?}`（`caseId` 只进审计，不参与执行）。
3. 控制器：校验语言白名单、代码长度 ≤ 20000、stdin ≤ 4000；查当天次数；`tryAcquire` 信号量（拿不到返回 429）。
4. `DockerCodeRunner`：
   - 构造命令（见下）；
   - `ProcessBuilder.start()`，把代码写进 stdin（容器内 `sh -c` 读 stdin 落到 `/tmp`）；
   - 等 `timeoutMs`（默认 10000）；超时 `destroyForcibly()` 并标记 `timedOut`；
   - 分别读 stdout/stderr，各截断 64KB 并标记 `truncated`；
   - 退出码非 0 不算错误（编译失败就是非 0）。
5. 判定：退出码 137/`oom` 由 stderr 里的 `Killed` 与退出码推断为 `oom=true`。
6. 写审计行（成功与失败都写，被配额/并发拒绝也写 `rejected_reason`）。
7. 返回结果。

### 编译/执行命令

Java：

```sh
# 容器内
sh -c 'cat > /tmp/Main.java && cd /tmp && javac Main.java && java Main'
```

Python：

```sh
sh -c 'cat > /tmp/main.py && cd /tmp && python main.py'
```

**为什么不带 `-Xmx`**：容器内存上限就是边界；JVM 在 256m 容器里会自己按 cgroup 调堆。若实测 OOM 提示不清楚，再加 `-XX:MaxRAMPercentage=75`。

## 接口与数据

### `GET /api/v1/sandbox/status`

```json
{
  "enabled": true,
  "ready": true,
  "runtime": null,
  "languages": ["java", "python"],
  "dailyLimit": 50,
  "usedToday": 3,
  "reason": null
}
```

`ready=false` 时 `reason` 说明原因（`docker 不可用` / `已关闭` / `镜像缺失`）。

### `POST /api/v1/sandbox/run`

请求：

```json
{ "language": "java", "code": "public class Main { ... }", "stdin": "5\n", "caseId": "case-1" }
```

响应：

```json
{
  "stdout": "5\n",
  "stderr": "",
  "exitCode": 0,
  "durationMs": 812,
  "timedOut": false,
  "oom": false,
  "truncated": false
}
```

### 表 `code_runs`

```sql
CREATE TABLE code_runs (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  user_id       BIGINT NOT NULL,
  entry_id      BIGINT NULL,
  case_id       VARCHAR(64) NULL,
  language      VARCHAR(16) NOT NULL,
  status        VARCHAR(24) NOT NULL,   -- ok / compile_error / runtime_error / timeout / oom / rejected / disabled
  exit_code     INT NULL,
  duration_ms   INT NOT NULL,
  output_bytes  INT NOT NULL,
  truncated     TINYINT(1) NOT NULL DEFAULT 0,
  code_hash     CHAR(64) NOT NULL,
  code_length   INT NOT NULL,
  created_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_code_runs_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

**不存源码**：只存 SHA-256 与长度。审计能回答"谁、何时、跑了什么语言、结果如何、用了多少资源"，不能还原代码。

配额：`SELECT COUNT(*) FROM code_runs WHERE user_id=? AND created_at >= ? AND status <> 'rejected'`——**被拒绝的不计入配额**，否则用户会因为重试被永久锁死。

## 持久化与迁移

- 新增 `V13__create_code_runs.sql`，只加一张表，不动既有表。
- 迁移编号**不写死假设**：按当前最大编号顺延，实施时先 `ls` 确认。
- 注销级联：`code_runs` 属用户数据，进 `AiUserDeletionListener`？**不进**——它与 AI 无关，但需要进账号删除的级联清单。实施时确认现有账号删除监听器的清单再决定；若不进，会留下孤儿行（审计保留 vs 隐私删除冲突，按项目既有"注销硬删"口径应当删）。

## 失败处理与恢复

| 情况 | HTTP / 结果 |
| --- | --- |
| 未登录 | 401（安全链） |
| 能力关闭 | 503 `SANDBOX_DISABLED` |
| 语言不支持 | 400 `INVALID_ARGUMENT` |
| 代码超长 / stdin 超长 | 400 |
| 当天次数用尽 | 429 `TOO_MANY_REQUESTS`，message 带"明天再来" |
| 并发已满 | 429，message 带"稍后再试" |
| 超时 | 200，`timedOut=true`（这是**运行结果**，不是接口错误） |
| 内存超限 | 200，`oom=true` |
| 编译失败 | 200，`exitCode≠0` + stderr |
| docker 起不来 | 503，并让能力探测转为 not ready |

**不重试**：执行是用户发起的，失败就告诉他原因；自动重试会重复占资源。

## 安全与权限

- 路径 `/api/v1/sandbox/**` 只需登录（不加 admin 规则，也不放 permitAll）。
- 请求体不含 userId；归属取自令牌（与全项目一致）。
- 容器参数（**顺序无关，但必须全部存在**）：
  `--rm --network=none --read-only --tmpfs /tmp:rw,size=64m,exec --user 65534:65534 --cap-drop=ALL --security-opt=no-new-privileges --memory=256m --memory-swap=256m --cpus=0.5 --pids-limit=64 --ulimit nofile=64 --ulimit nproc=64`
  可选 `--runtime=<configured>`。
- **没有 `-v` 挂载**：代码通过 stdin 进容器，不碰宿主文件系统。
- 镜像按 digest 引用；配置只允许"替换已有镜像名"，**不接受用户输入镜像**。
- 日志不写代码内容，只写 hash 与长度。
- 明确写入文档的**天花板**：容器共享宿主内核，内核漏洞可导致逃逸；要更强隔离需切 gVisor。

## 可观测性

- 审计表即观测面；不新增 Prometheus 指标（项目当前没有指标栈）。
- 日志：每次运行一行 `language= status= duration= exit=`，**无代码**。
- 能力端点暴露 `enabled/ready/reason/usedToday`，排障时先看它。

## 实施顺序

1. 迁移与审计（无执行）。
2. `CodeRunner` 端口 + 命令构造（纯函数）+ 单测。
3. `DockerCodeRunner` + 禁用实现 + 能力探测。
4. 控制器 + 配额 + 并发信号量。
5. 前端 `run` 题型 + 结果面板。
6. 部署文档 + 服务器上真机验证。

## 测试策略

| 层 | 用例 |
| --- | --- |
| 纯函数 | 命令构造包含全部加固标志；语言白名单；输出截断；超时/oom 分类 |
| 集成（MockMvc） | 未登录 401；禁用 503；第 51 次 429；语言不支持 400；审计行写入且**不含源码** |
| 端口层 | `CodeRunner` 用假实现断言控制器行为（不依赖 Docker） |
| 真机（服务器） | Java/Python 各一个样例；死循环超时；大内存分配 OOM；`curl` 出网失败 |
| 前端 | `run` 题型的解析、运行中态、四种结果呈现、用例比对 |

**本机没有 Docker**：端口层与集成测试全部用假 `CodeRunner`；真容器验证只能在服务器上做，报告里如实登记。

## 需求追踪

| 设计点 | 需求 / 验收 |
| --- | --- |
| 运行题型 | REQ-001 / AC-001 |
| 执行契约 | REQ-002 / AC-001 |
| 容器加固 | REQ-003 / AC-002、AC-008 |
| 资源上限 | REQ-004 / AC-003、AC-004 |
| 配额与信号量 | REQ-005 / AC-005、AC-006 |
| 审计表 | REQ-006 / AC-007 |
| 失败分类 | REQ-007 / AC-003、AC-004 |
| 客户端判定 | REQ-008 / AC-001 |
| 能力端点与关闭 | REQ-011 / AC-009 |
| 不影响部署 | REQ-010 / AC-010 |

## 已知限制与升级路径

1. **应用容器化后必须改架构**：`docker run` 需要宿主 Docker；若应用本身进容器，要改为独立 runner 服务（proposal 备选 A）。
2. **逃逸天花板**：加固容器挡不住内核漏洞；`paideia.sandbox.runtime=runsc` 预留给 gVisor。
3. **每次运行冷启动 ~0.3–1s**：镜像已在本地时主要是容器启动开销。若嫌慢，可加"预热池"，v1 不做。
4. **只支持单文件**：多文件项目需要工作区概念，不在本项。
