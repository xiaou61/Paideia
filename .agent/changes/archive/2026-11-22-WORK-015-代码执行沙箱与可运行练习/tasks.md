---
artifact: tasks
work_id: WORK-015
work: 代码执行沙箱与可运行练习
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-014, WORK-009]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 08:15:40 +0800
approver_role: CTO
---

# 任务 · 代码执行沙箱与可运行练习

**排序原则**：先在**不需要 Docker** 的层把逻辑与边界钉死（迁移、端口、命令构造、配额、控制器），再接前端；真容器只在服务器上验。这样本机能覆盖 90% 的回归面，剩下的只靠一次真机检查。

**全局约束**：不新建独立服务；镜像钉 digest；服务端不存源码与用例；判定留在客户端；注销级联要覆盖新表。

## 任务

### TASK-001 | done | 迁移与审计表

- 对应：REQ-006；AC-007
- 依赖：无
- 修改：`backend/paideia-app/src/main/resources/db/migration/V13__create_code_runs.sql`、`backend/paideia-sandbox/.../audit/CodeRunMapper.java`
- 步骤：
  1. 先 `ls db/migration` 确认当前最大编号，按其顺延（不写死 13）。
  2. 建 `code_runs` 表（字段见设计），索引 `(user_id, created_at)`。
  3. Mapper：`insert`、`countToday`（排除 `rejected`）。
  4. 在账号注销级联清单里加入 `code_runs`。
- 验证：`mvn -B -pl paideia-app -am test -Dtest=PlatformContractTest`；启动后 `SHOW CREATE TABLE code_runs` 核对。

### TASK-002 | done | `CodeRunner` 端口与命令构造

- 对应：REQ-002、REQ-003、REQ-004；AC-001、AC-003、AC-004
- 依赖：无
- 修改：`paideia-sandbox/.../CodeRunner.java`、`SandboxProperties.java`、`DockerCommand.java`（纯函数）、`DockerCommandTest.java`
- 步骤：
  1. 定义 `record RunRequest(String language, String code, String stdin)` 与 `record RunResult(...)`。
  2. `DockerCommand.of(language, props)` 返回 `List<String>`；**每条加固标志都要有单测断言**（逐条 `contains`）。
  3. 语言白名单 `java|python`；未知语言抛 `INVALID_ARGUMENT`。
  4. 输出截断常量 64KB。
- 验证：`pnpm` 无关；`mvn -B -pl paideia-sandbox test`。

### TASK-003 | done | Docker 执行与能力探测

- 对应：REQ-003、REQ-007、REQ-010；AC-002、AC-003、AC-004、AC-008
- 依赖：`TASK-002`
- 修改：`DockerCodeRunner.java`、`DisabledCodeRunner.java`、`SandboxCapability.java`、`SandboxConfiguration.java`
- 步骤：
  1. `DockerCodeRunner`：`ProcessBuilder` 起容器，写 stdin，读 stdout/stderr，`waitFor(timeout)`，超时 `destroyForcibly`。
  2. 失败分类：超时、退出码 137 + stderr 含 `Killed` → `oom`、其余非 0 → 编译/运行错误。
  3. `SandboxCapability`：启动时 `docker version`（3s 超时）+ 检查镜像；失败置 `ready=false` 并记原因，**不阻止应用启动**。
  4. `enabled=false` 时装配 `DisabledCodeRunner`。
- 验证：`mvn -B -pl paideia-sandbox test`（用假进程/假命令断言分类，不依赖 Docker）。

### TASK-004 | done | 控制器、配额与并发

- 对应：REQ-005、REQ-011；AC-005、AC-006、AC-009
- 依赖：`TASK-001`、`TASK-003`
- 修改：`SandboxController.java`、`SandboxQuotaService.java`、`SecurityConfiguration.java`、`ErrorCode.java`
- 步骤：
  1. `GET /status` 返回 `enabled/ready/runtime/languages/dailyLimit/usedToday/reason`。
  2. `POST /run`：校验长度与语言 → 查配额 → `tryAcquire` → 执行 → 写审计 → 返回。
  3. 被配额/并发拒绝也写审计（`rejected`），且**不计入当天次数**。
  4. 新增 `SANDBOX_DISABLED(50300)`，映射 503。
  5. 安全规则：`/api/v1/sandbox/**` 需登录（不放 permitAll，也不要求 admin）。
- 验证：`mvn -B -pl paideia-app -am test -Dtest=SandboxIntegrationTest`（假 `CodeRunner`）：401 / 503 / 429 / 400 / 正常 200 / 审计不含源码。

### TASK-005 | done | 前端 `run` 题型

- 对应：REQ-001、REQ-008、REQ-009；AC-001、AC-009
- 依赖：`TASK-004`
- 修改：`packages/ui/src/markdown/renderers/schema.ts`、`code-challenge.tsx`、`context.tsx`、`packages/knowledge/src/client.ts`、`KnowledgeEntryPage.tsx`
- 步骤：
  1. schema 增 `RunChallenge`：`language / starter / cases[{id,name,stdin,expected,trim?}] / hidden?`；校验上限。
  2. 块内：textarea（等宽）、运行按钮、运行中态、结果列表（每用例通过/失败 + 实际输出 + 期望值仅在非 hidden 时显示）。
  3. 端口 `runCode` 从 context 注入（管理端预览不注入 → 按钮不渲染）。
  4. 判定：`stdout.trim()` 比较（可配 `trim: false`）；退出码非 0 直接失败。
  5. 成绩写本地进度（复用 `challenges` 记录）。
- 验证：`pnpm --filter @paideia/ui test`、`pnpm --filter @paideia/knowledge test`、两端 typecheck。

### TASK-006 | done | 部署文档与服务器真机验证

- 对应：REQ-003、REQ-010；AC-002、AC-003、AC-004、AC-008、AC-010
- 依赖：`TASK-005`
- 修改：`.agent/notes/dev-workflow.md`（沙箱一节）、`scripts/`（如需要，镜像准备命令）
- 步骤：
  1. 文档写清：所需镜像与 digest、`paideia.sandbox.*` 配置项、`runtime=runsc` 的切换方式、回滚（`enabled=false`）。
  2. 服务器上 `docker pull` 两个镜像；启动应用；`GET /status` 应为 `ready=true`。
  3. 真机验：Java/Python 样例返回正确 stdout；死循环超时；`curl https://example.com` 失败（无网络）；审计行写入且不含源码。
  4. 记录实测耗时与资源占用。
- 验证：上述 curl 与审计查询的实际输出，贴进 `testing/report.md`。

## 完成条件

- 本机：`mvn test` 新用例全绿；前端单测与 typecheck 全绿。
- 服务器：能力端点 `ready=true`；Java/Python 各一次成功运行；超时与无网络各一次被正确分类。
- 配额：第 51 次被拒且不计入次数；审计行可查、**无源码**。
- 关闭开关：`enabled=false` 时前端不显示运行按钮，接口 503，其他题型不受影响。
