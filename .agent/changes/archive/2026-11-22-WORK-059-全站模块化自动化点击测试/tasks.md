---
artifact: tasks
work_id: WORK-059
work: 全站模块化自动化点击测试（ego-browser）
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 09:18:30 +0800
approver_role: CTO
---

# 全站模块化自动化点击测试实施任务

## 依据

- `requirements.md`（REQ-001…012 / AC-001…010）：18 个模块、57 条路由、线上运行、只读/写入分档。
- `proposal.md`：脚本化 ego 点击，不做 LLM 探索，不扩 Playwright。
- `design.md`：`scripts/ego/` 组件、harness 接口、报告格式、安全守卫。

## 全局约束

- 只改 `scripts/ego/`、`.agent/testing/`、`.gitignore`、必要时的 `data-testid` 属性与文档；不改产品逻辑、样式、接口。
- 线上只使用 `e2e-learner` 与 `admin` 两个账号；口令只在 `.secrets/ego.local.json`。
- 写入用例必须带 `e2e-click-<runid>-` 前缀，且只能在 `--write` 下执行。
- 每条用例失败不中断整轮；清理失败必须让退出码非零。
- 报告与截图不得出现主机名、IP、端口、账号、令牌。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 新增路由没登记，测试悄悄漏掉 | TASK-002 | `check-map.test.mjs`：临时 router 多一条路径必须非零退出 |
| 场景文件存在但一条用例都没有 | TASK-002 | `check-map.test.mjs`：空场景必须失败 |
| 同一路由被登记到两个模块 | TASK-002 | `check-map.test.mjs`：重复归属必须失败 |
| 只读轮次仍创建业务数据 | TASK-008 | `notes` 场景 `write: true`，不带 `--write` 时跳过且无新条目 |
| 删除时误删真实数据 | TASK-008 | `assertSafeTarget` 对不含前缀的标题抛错 |
| 清理失败被静默吞掉 | TASK-008 | 人为让清理抛错，退出码必须为 2 |
| 凭据或地址进入报告 | TASK-003、TASK-010 | 报告字段白名单 + 提交前 `rg` 扫描 |
| 动态内容导致假红 | TASK-004…007 | 断言「有内容或明确空态」，不写死具体条目标题 |
| 单条失败导致整轮中断 | TASK-003 | runner 对失败用例继续执行下一条 |
| 选择器匹配到多个元素 | TASK-003 | 错误信息带选择器与用例名，不用坐标兜底 |

## 任务

### TASK-001 | done | ego 线上探针

- 对应：`REQ-003`、`REQ-006`
- 依赖：无
- 新建：`.agent/notes/ego-click-probe.md`（结论记录）
- 步骤：
  1. 用 `ego-browser nodejs` 打开线上登录页，截图并 `snapshot()`，确认登录表单的可选选择器。
  2. 用 `e2e-learner` 登录，进入 `/knowledge`，点开一条条目，截图。
  3. 记录：可用的选择器写法、登录后等待条件、页面是否有白屏或控制台可见错误、耗时。
- 验证：笔记里每条结论都有对应的命令输出或截图路径；不写口令。

### TASK-002 | done | 模块清单与覆盖闸门

- 对应：`REQ-001`、`REQ-004`、`AC-001`、`AC-003`
- 依赖：TASK-001
- 新建：`scripts/ego/modules.mjs`、`scripts/ego/check-map.mjs`、`scripts/ego/check-map.test.mjs`
- 接口：
  - `modules.mjs` 导出 `modules: Array<{ id, name, side, routes: string[], scenario: string, minCases: number }>`
  - `check-map.mjs` 接受 `--app-router <path>`、`--admin-router <path>`、`--map <path>`，默认指向仓库真实文件；失败退出码 1
- 步骤：
  1. 把 18 个模块与 57 条路由写进 `modules.mjs`，每条路由字面来自两个 `router.tsx`。
  2. 写解析与比对：路由未登记、重复归属、场景文件缺失、场景用例数不足，逐条打印。
  3. 写 `check-map.test.mjs`：用临时目录造三组 router 与场景，断言通过/失败/重复三种结果。
- 验证：`node --test scripts/ego/check-map.test.mjs` → 3 passed；`node scripts/ego/check-map.mjs` → 0。

### TASK-003 | done | harness、runner 与报告

- 对应：`REQ-003`、`REQ-007`、`REQ-009`、`REQ-010`、`REQ-012`、`AC-002`、`AC-004`、`AC-007`、`AC-010`
- 依赖：TASK-002
- 新建：`scripts/ego/harness.mjs`、`scripts/ego/run.mjs`
- 接口：严格按 `design.md` 的 `createSession` / `session.goto` / `session.click` / `session.clickRole` / `session.fill` / `session.expectText` / `session.expectNoText` / `session.expectUrl` / `session.step` / `session.shot` / `session.safeName` / `session.assertSafeTarget`
- 步骤：
  1. 实现会话与登录：学习者 `p1`、管理员 `p2`，各登录一次；登录失败整轮退出。
  2. 实现断言与步骤记录：失败带步骤名、URL；截图写 `.agent/testing/.artifacts/<runid>/`。
  3. 实现 CLI 与汇总：`--module` / `--all` / `--read-only` / `--write`；失败继续；写 Markdown 与 JSON。
  4. 用 `--module home --read-only` 跑通第一条真实用例。
- 验证：`ego-browser nodejs < scripts/ego/run.mjs --module home --read-only` → 退出码 0，`.agent/testing/runs/` 新增一份报告。

### TASK-004 | done | 学习者场景 L1–L4

- 对应：`REQ-002`、`AC-003`
- 依赖：TASK-003
- 新建：`scripts/ego/scenarios/identity.mjs`、`knowledge.mjs`、`notes.mjs`、`home.mjs`
- 步骤：
  1. `identity`：登录态可见、账号菜单、`/profile` 可读、`/settings/mcp` 可达、未登录访问受保护页被导向登录。
  2. `home`：首页加载、导航四组可展开、每组的入口至少能进一个。
  3. `knowledge`：分类 → 条目 → 正文 → 刷新仍在；不存在的条目给错误提示。
  4. `notes`：新建 → 改一次 → 刷新仍在 → 删除（`write: true`）。
- 验证：`--module home,knowledge,notes,identity --read-only` 全绿（notes 的写入用例跳过）；`--module notes --write` 创建并删除。

### TASK-005 | done | 学习者场景 L5–L8

- 对应：`REQ-002`、`AC-003`
- 依赖：TASK-003
- 新建：`scripts/ego/scenarios/assistant.mjs`、`learn.mjs`、`progress.mjs`、`camp.mjs`
- 步骤：
  1. `assistant`：打开面板、发一条无工具消息（或断言余额不足的确定提示）、记忆面板可开、窄屏不撑破。
  2. `learn`：轨道列表 → 关卡地图 → 任意一关正文。
  3. `progress`：四块都有数据或空态；「问问」只预填不发送。
  4. `camp`：开营或明确空态；作答页能进；未答完不能交卷。
- 验证：四个模块只读全绿。

### TASK-006 | done | 学习者场景 L9–L12

- 对应：`REQ-002`、`AC-003`
- 依赖：TASK-003
- 新建：`scripts/ego/scenarios/graph.mjs`、`sql-practice.mjs`、`job.mjs`、`content.mjs`
- 步骤：
  1. `graph`：图谱页有节点或空态；点一个节点出详情。
  2. `sql-practice`：编辑器可输入、可执行一条 `SELECT 1`、结果表出现。
  3. `job`：简历列表 → 新建 → 编辑 → 刷新仍在 → 预览；面试页可发起或明确余额不足。
  4. `content`：速递有内容或空态、书籍列表 → 详情、源码列表 → 条目。
- 验证：四个模块只读全绿；简历写入用例在 `--write` 下创建后删除。

### TASK-007 | done | 管理端场景 A1–A6

- 对应：`REQ-002`、`AC-003`
- 依赖：TASK-003
- 新建：`scripts/ego/scenarios/admin-knowledge.mjs`、`admin-users.mjs`、`admin-ai.mjs`、`admin-ops.mjs`、`admin-analytics.mjs`、`admin-datasets.mjs`
- 步骤：
  1. `admin-knowledge`：分类/条目/概念三页可开；新建一条带前缀的条目并删除（`write: true`）。
  2. `admin-users`：用户列表、反馈列表可开；只读，不点删除。
  3. `admin-ai`：AI 运行列表/详情/反馈、积分审计可开。
  4. `admin-ops`：运维总览、事件列表可开；不关闭事件。
  5. `admin-analytics`：概览与运营数据图表区可开。
  6. `admin-datasets`：数据集列表、报告页可开。
- 验证：六个模块只读全绿；`admin-knowledge --write` 创建并删除。

### TASK-008 | done | 写入模式与清理守卫

- 对应：`REQ-005`、`REQ-008`、`AC-005`、`AC-008`
- 依赖：TASK-004、TASK-006、TASK-007
- 修改：`scripts/ego/harness.mjs`、`scripts/ego/run.mjs`、涉及写入的场景
- 步骤：
  1. `--write` 才执行 `write: true`；只读轮次打印跳过数与原因。
  2. `safeName()` 统一加 `e2e-click-<runid>-`；`assertSafeTarget()` 在删除前校验。
  3. 清理放 `finally`；失败标记 `cleanup_failed`，退出码 2。
  4. 连续跑两次 `--module notes --write`，第二次不因第一次残留失败。
- 验证：`--module notes --write` 两次均绿；只读轮次前后笔记数量不变。

### TASK-009 | done | 全量只读跑与稳定化

- 对应：`AC-002`、`AC-004`、`AC-007`
- 依赖：TASK-004、TASK-005、TASK-006、TASK-007
- 修改：不稳定的场景与选择器
- 步骤：
  1. `--all --read-only` 跑完 18 个模块，记录总耗时与失败明细。
  2. 逐条修：选择器歧义、动态内容断言、等待条件。
  3. 人为改错一条断言，确认报告给出模块/用例/步骤/URL/截图；改回。
- 验证：`--all --read-only` 全绿；报告在 `.agent/testing/runs/`。

### TASK-010 | done | 文档、配置样例与台账

- 对应：`REQ-006`、`AC-006`、`AC-010`
- 依赖：TASK-009
- 新建：`scripts/ego/README.md`、`.secrets/ego.local.example.json`；修改 `.gitignore`
- 步骤：
  1. README 写：安装检查、配置字段、单模块/全量/只读/写入命令、加模块三步、失败怎么看。
  2. 样例配置只留空字段与说明；`.gitignore` 加 `.agent/testing/.artifacts/`。
  3. 提交前扫描 `git diff --cached`：无口令、令牌、主机名、IP、端口。
- 验证：按 README 从零跑通 `--module home --read-only`；扫描命令无命中。

## 完成条件

- AC-001…010 全部有本轮真实证据。
- 18 个模块全部登记，`check-map` 与自测通过。
- `--all --read-only` 完整跑完并产出报告。
- 至少一个写入模块验证「创建 → 刷新 → 删除」与二次运行无残留。
- README 能让下一个人不读代码就跑起来。
