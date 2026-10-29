---
artifact: design
work_id: WORK-052
work: SQL 沙箱容器池
status: approved
created: 2026-10-28
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 09:45:14 +0800
approver_role: CTO
---

# SQL 沙箱容器池设计

> 按**实际落地代码**编写（2026-10-29）。路径与符号名均为仓库中的真实存在。

## 系统上下文

SQL 练习的调用链：`sandbox` 模块的 `SandboxService` → `DockerCodeRunner.run(request)`。
`DockerCodeRunner` 原本只有一条路：每次 `docker run` 起一次性容器。本项在它前面插一个池：
`SqlContainerPool.tryRun(request)` 返回 `Optional<CodeRun.Result>`，有值即返回，空则落回一次性路径。

`SqlContainerPool` 只被 `DockerCodeRunner` 使用（`@Component`，包内可见），不对外暴露端口。

## 组件与职责

| 组件 | 职责 |
| --- | --- |
| `SqlContainerPool` | 槽位队列（`BlockingQueue<Slot>`）、容器生命周期（`ensureContainer` / `destroy`）、执行（`execute`）、就绪等待（`waitReady`）、`ping` |
| `SandboxProperties` | `sqlPoolSize`、`sqlPoolAcquireTimeoutMs`、`sqlMemory`（512m→1g）、既有的 `sqlTimeoutMs` / `sqlCpus` / `sqlPidsLimit` / `maxOutputBytes` |
| `DockerCodeRunner` | SQL 请求（非 project 模式）先调 `sqlPool.tryRun`；`isSql(language) && !request.project()` |
| `SqlResultCodec` | XML 解析，两条路径共用——这是"结果不改"的结构保证 |

槽位模型：`Slot { int index; String container; }`，容器名为 `paideia-sql-pool-<index>`。
槽位被取出时容器归该请求独占（每容器并发 1），用完放回队列。

## 请求或事件流程

**热路径（命中池）**

1. `tryRun` 检查 `enabled()`（`sqlPoolSize > 0 && sandbox.enabled`）。
2. `idle.poll(sqlPoolAcquireTimeoutMs)` 取槽位；拿不到 → 返回 empty（回落）。
3. `ensureContainer`：槽位没有容器时 `docker rm -f <name>` 无条件清一次同名残留
   （幂等，防上一次进程留下的死容器占名），再 `docker run -d` 并 `waitReady` 等 mysqld ping 通。
   有容器则**不做健康检查**直接复用。
4. `execute`：`docker exec -i <container> sh -c RUN_SCRIPT|EXPLAIN_SCRIPT`，
   stdin 写两段帧（先数据集长度+内容，再用户 SQL 长度+内容），与一次性路径同构。
5. 读 stdout/stderr → `SqlResultCodec.parse` → 拼 `CodeRun.Result`。
6. `finally`：可复用则 `idle.offer(slot)`；不可复用则 `destroy(slot)` 后放回。

**容器内脚本**（`BOOT_SCRIPT` / `<RUN|EXPLAIN>_SCRIPT`）

- 启动：`cp -a /opt/datadir-base /tmp/data` → `chmod -R u+w` → `mysqld --skip-networking
  --socket=/tmp/m.sock ... &` → `sleep 100000` 保持容器存活。
- 执行：建库 + 建账号 + 授权 → 灌数据集 → 跑 SQL（或 `EXPLAIN FORMAT=TREE` 包一层）
  → 删库删号 → 输出 `--xml`。

## 接口与数据

无新接口。`CodeRun.Request` / `CodeRun.Result` / `SandboxService` 的对外形状不变。

配置（`application.yml` 的 `sandbox.*`）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `sql-pool-size` | 2 | **0 = 关闭池化**，全部走一次性容器 |
| `sql-pool-acquire-timeout-ms` | 1500 | 取不到槽位的最长等待，超时即回落 |
| `sql-memory` | 1g | 容器内存上限；`--memory-swap` 同值 |

## 持久化与迁移

无。池是进程内的内存结构，容器与临时库都在一次执行的生命周期内创建与销毁。
无 Flyway 迁移、无表结构变化、无种子数据。

## 失败处理与恢复

| 失败 | 处理 |
| --- | --- |
| 池关闭 / 取槽位超时 | 返回 empty → 一次性容器 |
| 容器不存在或没在跑（`containerGone(stderr)`） | 本次结果作废、`destroy`、下一个请求重建；本请求回落到一次性容器 |
| 执行抛异常（mysqld 崩、OOM、`SandboxUnavailableException`） | 同上：销毁容器，回落 |
| 执行超时（`sqlTimeoutMs`） | `destroyForcibly` + 销毁容器——超时的查询可能还在容器里跑，不能让下一个人接着等 |
| 清理失败 | 脚本里 `DROP ... IF EXISTS` 保证幂等；泄漏由 AC-004 的连跑检查守 |

**为什么把健康检查从热路径拿掉**：这台机器上 `docker exec` 偶尔要 3 秒以上，
一次 ping 超时就把健康容器判死并重建，实测一半请求因此回落、比不做池化还慢。
健康改由真正的执行判定——exec 报"容器不存在/没在跑"才重建。

## 安全与权限

- **每次执行一个库 + 一个账号**：`GRANT ALL ON <库>.* TO '<账号>'@'localhost'`，
  用完 `DROP DATABASE IF EXISTS` + `DROP USER IF EXISTS`。这是池化的前置条件，不是优化项。
- **口令不上命令行**：走 `MYSQL_PWD` 环境变量。`-p<password>` 会把口令暴露在容器内的
  `ps` 里，同容器其他执行可见。
- **容器参数一个都没放松**：`--network=none`、`--read-only`、`--cap-drop=ALL`、
  `--security-opt=no-new-privileges`、`--pids-limit`、`--ulimit`、`--tmpfs /tmp`、
  `--secure-file-priv`、`--local-infile=0`、`--skip-log-bin` 全部保留。
- **不信任容器名**：建容器前按名字无条件删一次，避免旧容器（可能是 512m 时代起、mysqld 已死）
  让 `docker run --name` 失败且错误被吞。

## 可观测性

- 启动日志：`sql.pool.enabled size={} memory={} acquireTimeoutMs={}`。
- 异常事件：`sql.pool.container_gone`、`sql.pool.run_failed`（带容器名与原因）。
- 每次执行仍返回 `durationMs`、`exitCode`、超时/内存标记（与一次性路径同构），
  前端与审计看到的字段不变——"为什么这次慢了"能从 `durationMs` 与回落日志回答。

## 实施顺序

1. `SandboxProperties` 加三个键（含 `sqlMemory` 512m→1g）。
2. `SqlContainerPool`：槽位队列 + `ensureContainer` + `execute` + `destroy`。
3. `DockerCodeRunner` 插入池分支（拿不到就继续走原路）。
4. `SqlContainerPoolTest` 七条纯逻辑用例（脚本内容、口令形态、启动脚本语法、默认内存、开关）。
5. 部署：`application.yml` 的 `sql-memory=1g`，发版，线上实测。
6. 线上修掉三个只有真跑才暴露的缺陷（`&;`、同名残留容器、热路径健康检查）。

## 测试策略

- **单元**：`SqlContainerPoolTest` 7 条——关闭时回落、总开关关闭时不启用、脚本含隔离语句、
  口令不走 `-p`、计划模式只包第一段、启动脚本无 `&;`、默认内存 1g。
  这些都不连 Docker，跑在普通单测里。
- **线上**：本机没有 Docker，池的真实行为只能在服务器验证；报告里的 7 条 AC 全部是线上实测。
- **回归**：`DockerCommandTest` 跟着 `sqlMemory` 默认值调整；一次性路径的行为未改，
  它仍是池不可用时的兜底。

## 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001 | 常驻容器省掉三段固定成本；账号管理是主要剩余成本（约 100ms） |
| REQ-002 | `RUN_SCRIPT` 的建库/建号/授权/删库/删号；`execute` 的 `finally` |
| REQ-003 | 独立账号只被授权本库，`--skip-networking` 且 socket 独占 |
| REQ-004 | `tryRun` 的 empty 语义 + `DockerCodeRunner` 的回落分支 |
| REQ-005 | `Slot` 固定数量、`finally` 一定 `idle.offer`；容器名固定 |
| REQ-006 | `EXPLAIN_SCRIPT` 与 `RUN_SCRIPT` 同一个 `execute` 与同一个池 |
| REQ-007 | `ensureContainer` 的 `docker run` 参数 |
| REQ-008 | `execute` 组装的 `CodeRun.Result` 字段与一次性路径一致 |

## 待决定事项

无阻断项。开放项只有"是否用共享账号换那 100ms"——那是改 REQ-002 的范围问题，
需要用户明确同意，见提案「待决定事项」。
