---
artifact: tasks
work_id: WORK-052
work: SQL 沙箱容器池
status: approved
created: 2026-10-28
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 09:45:14 +0800
approver_role: CTO
---

# SQL 沙箱容器池实施任务

## 依据

- `requirements.md`（REQ-001…008 / AC-001…007）
- `proposal.md`（池化 + 每次独立库与受限账号；回落为设计的一部分）
- `design.md`（`SqlContainerPool` / `SandboxProperties` / `DockerCodeRunner` 的边界）
- 代码位置：`backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/run/`

## 全局约束

- 隔离以"每次执行一个库 + 一个受限账号"为准；口令只走 `MYSQL_PWD`，不上命令行。
- 容器参数不放松：`--network=none`、`--read-only`、`--cap-drop=ALL`、`--pids-limit`、`--ulimit`。
- 池不可用时必须回落到一次性容器，用户侧不新增报错。
- 不改判题口径、不改接口形状、不改配额；不新增表与迁移。
- 本机没有 Docker，池的真实行为只能在服务器上验证；单元测试只覆盖纯逻辑（脚本内容、开关、默认值）。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 池关掉时请求不走容器、直接回落 | TASK-002 | `SqlContainerPoolTest#关闭时回落` |
| 沙箱总开关关闭但池配了大小时仍然启用 | TASK-002 | `SqlContainerPoolTest#总开关关闭时不启用` |
| 用户能看到别人的临时库 | TASK-003 | `SqlContainerPoolTest#脚本包含隔离语句`；线上 `SHOW DATABASES`（AC-002） |
| 口令出现在容器内命令行（`ps` 可见） | TASK-003 | `SqlContainerPoolTest#口令不走命令行` |
| 容器内存 512m 导致 mysqld 被 OOM 静默杀掉 | TASK-001 | `SqlContainerPoolTest#容器内存默认 1g`；线上 512m 复现记录 |
| 启动脚本拼出 `&;`（sh 语法错，容器秒退） | TASK-003 | `SqlContainerPoolTest#启动脚本没有语法错` |
| 上一次进程留下的同名容器让 `docker run` 失败 | TASK-004 | 线上：删容器后下一次请求仍成功（AC-003） |
| `EXPLAIN` 绕开池或被包装两次 | TASK-003 | `SqlContainerPoolTest#计划模式脚本`；线上 AC-005 |
| 执行超时后把人留在坏容器上 | TASK-004 | 线上：执行超时 → 容器销毁 → 下一次重建（AC-003 同路径） |

## 任务

### TASK-001 | done | 配置项与默认值

- 对应：`REQ-001`、`REQ-004`、`REQ-008`
- 依赖：无
- 修改：`SandboxProperties.java`
- 接口：新增 `getSqlPoolSize()/setSqlPoolSize(int)`、`getSqlPoolAcquireTimeoutMs()/setSqlPoolAcquireTimeoutMs(long)`；`getSqlMemory()` 默认值改为 `1g`
- 测试：`SqlContainerPoolTest#容器内存默认 1g`、`DockerCommandTest`
- 步骤：
  1. 加 `sqlPoolSize`（默认 2，0 = 关闭）与 `sqlPoolAcquireTimeoutMs`（默认 1500），并在注释里写明"不排队、超时回落"的理由。
  2. `sqlMemory` 512m → 1g，注释写明 tmpfs 里的 datadir 计入容器内存、512m 会静默 OOM。
  3. 线上 `application.yml` 的 `sandbox.sql-memory` 同步改 1g。
- 验证：`SqlContainerPoolTest` 通过；线上 1g 下 mysqld 能起来（报告「性能拆解」）

### TASK-002 | done | 槽位队列与开关语义

- 对应：`REQ-004`、`REQ-005`
- 依赖：`TASK-001`
- 新建：`SqlContainerPool.java`（新建，无法复用 `DockerCommand`——池是长驻生命周期，`DockerCommand` 只描述一次性命令）
- 接口：`Optional<CodeRun.Result> tryRun(CodeRun.Request request)`、`boolean enabled()`
- 测试：`SqlContainerPoolTest#关闭时回落`、`#总开关关闭时不启用`
- 步骤：
  1. 构造时按 `sqlPoolSize` 建 `Slot(index)`，放入 `ArrayBlockingQueue`。
  2. `tryRun` 先查 `enabled()`；再 `poll(acquireTimeoutMs)`，拿不到直接返回 empty。
  3. `finally` 里可复用则 `idle.offer(slot)`，不可复用则 `destroy` 后放回。
- 验证：`mvn -pl paideia-sandbox test` → 0 failed

### TASK-003 | done | 容器内脚本：隔离 + 执行 + 清理

- 对应：`REQ-002`、`REQ-003`、`REQ-006`、`REQ-007`
- 依赖：`TASK-002`
- 修改：`SqlContainerPool.java` 的 `BOOT_SCRIPT` / `RUN_SCRIPT` / `EXPLAIN_SCRIPT`
- 接口：脚本以 `sh -c` 执行，stdin 两段帧 `<len>\n<dataset><len>\n<code>`（与一次性路径同构）
- 测试：`SqlContainerPoolTest#脚本包含隔离语句`、`#口令不走命令行`、`#计划模式脚本`、`#启动脚本没有语法错`
- 步骤：
  1. `BOOT_SCRIPT`：拷基准 datadir、`chmod`、后台起 `mysqld`（**`&` 后不接 `;`**）、`sleep` 保活。
  2. `RUN_SCRIPT`：建库 + 建账号 + 授权本库 → 灌数据集 → `MYSQL_PWD` 跑 `--xml` → 删库删号。
  3. `EXPLAIN_SCRIPT`：只把第一段用户 SQL 包成 `EXPLAIN FORMAT=TREE`，其余完全一致。
- 验证：`SqlContainerPoolTest` 7 条全绿；线上 AC-002/AC-004/AC-005

### TASK-004 | done | 容器生命周期与失败恢复

- 对应：`REQ-004`、`REQ-005`
- 依赖：`TASK-003`
- 修改：`SqlContainerPool.java` 的 `ensureContainer` / `waitReady` / `ping` / `destroy` / `containerGone`
- 接口：`ensureContainer(Slot)`、`boolean containerGone(String stderr)`
- 测试：线上 AC-003（`docker kill paideia-sql-pool-0` 后下一次请求成功并重建）
- 步骤：
  1. 建容器前按名字 `docker rm -f` 无条件清一次同名残留（幂等），再 `docker run -d`。
  2. `waitReady` 轮询 mysqld 就绪；失败抛 `SandboxUnavailableException` 由 `tryRun` 转 empty。
  3. `containerGone(stderr)` 命中"容器不存在/没在跑"→ 本次结果作废、销毁容器。
  4. 执行超时 → `destroyForcibly` + 销毁容器（超时查询可能还在跑）。
- 验证：线上杀容器后 `0.399s / exitCode=0`，`docker ps` 显示已重建（报告「验收矩阵」AC-003）

### TASK-005 | done | 与一次性路径接线

- 对应：`REQ-004`、`REQ-008`
- 依赖：`TASK-002`
- 修改：`DockerCodeRunner.java`
- 接口：`SqlContainerPool` 由构造器注入（`@Component` + 包内可见）
- 测试：既有 `DockerCommandTest`；线上 AC-006（池不可用时 `durationMs≈2800` 仍成功）
- 步骤：
  1. `run()` 开头判断 `isSql(request.language()) && !request.project()` 才进池。
  2. `tryRun` 有值即返回；空则继续走原有一次性容器逻辑（原代码不动）。
- 验证：`mvn -pl paideia-sandbox test` → 0 failed；线上回落路径实测成功

### TASK-006 | done | 线上验证与缺陷修复

- 对应：`AC-001`…`AC-007`
- 依赖：`TASK-004`、`TASK-005`
- 修改：无（只修被实测暴露的缺陷）
- 测试：`testing/report.md` 的验收矩阵
- 步骤：
  1. 发版后连跑 5 次测 p50；连跑 20 次查泄漏；`SHOW DATABASES` 查隔离；`docker kill` 查重建；`explain:true` 查 EXPLAIN。
  2. 修掉实测暴露的三处：`sql-memory` 1g、启动脚本 `&;`、同名残留容器 + 热路径健康检查。
- 验证：报告 `status: passed`，7 条 AC 全部有实测证据

## 完成条件

- `SqlContainerPoolTest` 7 条与 `paideia-sandbox` 全部单测通过（0 failed）。
- 线上 7 条 AC 均有实测证据，记入 `testing/report.md`。
- 池关闭（`sql-pool-size=0`）时行为与改造前完全一致——这是回滚开关，必须可用。
- 无残留库与残留账号：连续 20 次执行后容器内 0 db / 0 user。
