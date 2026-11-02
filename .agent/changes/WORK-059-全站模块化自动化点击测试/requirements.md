---
artifact: requirements
work_id: WORK-059
work: 全站模块化自动化点击测试（ego-browser）
status: approved
mode: strict
mode_reason: 直接驱动线上生产实例并执行写操作、使用专用账号凭据、覆盖全部 18 个模块；属于跨模块、数据安全与部署验证工作
workflow: full
depends_on: []
related_to: [WORK-017, WORK-053]
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 09:18:30 +0800
approver_role: CTO
---

# 全站模块化自动化点击测试需求

## 目标

用 **ego-browser（ego lite）** 驱动真实 Chromium，对线上实例做**按模块组织的自动化点击**：
每个模块有固定的场景脚本，逐条点击用户真正会走的路径，断言可见结果，留下可复查的证据。

要同时成立四件事：

1. **真的点**：不是调接口、不是只断言 DOM，而是打开线上页面、点按钮、填表单、看结果。
2. **分模块**：18 个模块各自一个场景文件，失败能直接定位到模块与步骤。
3. **可叠加**：新增页面或功能时，漏登记、漏场景会让一个校验脚本失败，而不是靠人记得补。
4. **线上安全**：只用一个专用学习者账号与一个管理员账号；写入数据全部带运行前缀并清理；破坏性操作不碰真实数据。

用户原话：「分模块进行全方位的自动化测试」「要求测到每一个功能」「其实我更想要的是自动化点击，就是你用 ego lite 进行自动化点击」。
2026-11-02 裁决：**跑线上**、**框架 + 全部 18 个模块一起做**。

## 非目标

- **不替代 Playwright**：字段级契约、接口拒绝矩阵、组件单测仍归现有套件。ego 只证明「用户点得通、看得到」。
- **不做视觉像素比对**：截图只作失败证据，不做基线 diff。
- **不做性能测试**：耗时只记录，不设性能门槛。
- **不接 CI**：仓库目前没有 workflow；本项只提供一条本地命令，接 CI 另开工作项。
- **不碰破坏性管理操作**：不删除真实用户、不调整真实积分、不关闭真实运维事件、不删除非本次创建的知识条目。
- **不测真模型输出质量**：AI 相关用例只验证「有确定状态」（成功、余额不足、未配置三选一），不判断回答内容好不好。

## 已确认事实

| 项 | 事实 | 依据 |
| --- | --- | --- |
| ego-browser 已安装且可脚本化 | `ego-browser skill` 输出 v2.2.0（windows）；支持 `taskSpace` / `page.goto` / `page.click` / `page.fill` / `page.waitForSelector` / `page.snapshot` / `page.screenshot` / `page.evaluate` | 本机执行 `ego-browser skill` |
| ego 不是 Playwright | 官方明确禁止推断 `locator()` / `getByRole()` / `expect()` / `route()`；选择器用 `loc=role:...` / CSS / ref | ego-browser 使用指南 |
| 线上实例可达 | 学习者端 `/`、管理端 `/admin/` 均 HTTP 200，`/actuator/health` 为 `UP` | 2026-11-02 实测 |
| 线上账号 | `admin` 与 `e2e-learner` 两个账号；口令在被忽略的本地配置里，不在仓库 | `.agent/notes/dev-workflow.md` |
| 学习者端路由 | 36 条（含 9 条带参数） | `frontend/apps/app/src/router.tsx` |
| 管理端路由 | 21 条 | `frontend/apps/admin/src/router.tsx` |
| 现有 Playwright 覆盖 | 学习者端 25 个 spec、约 61 条；管理端 11 个 spec、约 7 条 | `frontend/apps/*/e2e/` |
| 现有真实空洞 | 简历 `versions` / `optimize` / `print` 三条路由没有任何 e2e 走到 | 全仓 grep 结果 |
| 测试台账规范 | 每次运行追加一个文件；线上目标只写 `online`，不写主机名/IP/端口；不记令牌与账号标识 | `.agent/testing/README.md` |
| 本地敏感配置约定 | `.secrets/` 与 `*.local.*` 已被 `.gitignore` 忽略 | `.gitignore` 第 8–12 行 |

## 用户决定

- **2026-11-02**：新建工作项，按模块做全方位自动化测试。
- **2026-11-02**：用 ego lite 做自动化点击，而不是继续扩 Playwright。
- **2026-11-02**：运行环境选**全部线上**（不是本地 test 库）。
- **2026-11-02**：第一批就是**框架 + 全部 18 个模块**，不先做小范围试点。

## 术语与边界

| 词 | 含义 | 不是什么 |
| --- | --- | --- |
| 自动化点击 | ego-browser 驱动真实浏览器，按固定步骤点击并断言可见结果 | 不是接口测试、不是 DOM 快照对比 |
| 模块 | 一组用户能完成的事，对应若干路由与一个场景文件 | 不是 Maven 模块，也不是文件夹 |
| 场景 | 一个 `.mjs` 文件，含若干条用例，每条用例是一串点击步骤 | 不是 LLM 自由探索 |
| 只读模式 | 跳过所有 `write: true` 的用例 | 不等于零副作用（打开条目会记一次浏览） |
| 写入模式 | 允许创建/修改/删除本次运行自己创建的数据 | 不允许碰既有真实数据 |

## 模块划分（18 个）

### 学习者端 12 个

| # | 模块 | 路由 |
| --- | --- | --- |
| L1 | 身份与账号 | `/login` `/register` `/oauth/callback` `/profile` `/settings/mcp` |
| L2 | 首页与导航 | `/` |
| L3 | 知识库 | `/knowledge` `/knowledge/entries` `/knowledge/categories/:categoryId` `/knowledge/entries/:id` |
| L4 | 笔记与分享 | `/notes` `/notes/new` `/share/:shareId` |
| L5 | 助手与记忆 | 全站壳（不占独立路由） |
| L6 | 学习线与课程 | `/learn` `/learn/:slug` |
| L7 | 学习记录与复习 | `/progress` `/review` `/my-assessments` |
| L8 | 训练营 | `/camp` `/camp/:id` |
| L9 | 知识图谱 | `/graph` |
| L10 | SQL 练习 | `/practice/sql` |
| L11 | 求职 | `/interview` `/resume` `/resume/new` `/resume/create` `/resume/:id` `/resume/:id/optimize` `/resume/:id/preview` `/resume/versions/:versionId/print` |
| L12 | 内容消费 | `/digest` `/books` `/books/:slug` `/source` `/source/:project` `/source/:project/:entryId` |

### 管理端 6 个

| # | 模块 | 路由 |
| --- | --- | --- |
| A1 | 知识库管理 | `/knowledge` `/knowledge/categories` `/knowledge/entries` `/knowledge/concepts` |
| A2 | 用户与反馈 | `/users` `/reports` |
| A3 | AI 观测与积分 | `/ai-runs` `/ai-runs/overview` `/ai-runs/list` `/ai-runs/feedback` `/ai-runs/screen` `/ai-credits` `/ai-credits/audit` |
| A4 | 运维 | `/ops` `/ops/panel` `/ops/incidents` |
| A5 | 运营数据 | `/` `/analytics` |
| A6 | SQL 数据集 | `/sql-datasets` `/sql-datasets/list` `/sql-datasets/reports` |

## 功能需求

- **REQ-001 唯一模块清单**：18 个模块、全部 57 条路由（36 + 21）登记在一个机器可读清单里；每条路由必须且只能属于一个模块。
- **REQ-002 每模块一个场景**：每个模块一个 `scripts/ego/scenarios/<module>.mjs`，至少覆盖：主链路、空态或首用、入口可达；有权限/写入/失败面的模块还要覆盖对应轴。
- **REQ-003 单模块与全量命令**：一条命令能只跑一个模块，也能按顺序跑全部；两者都输出通过/失败/跳过数与耗时。
- **REQ-004 覆盖闸门**：校验脚本解析两个 `router.tsx`，发现未登记路由、重复归属、缺失场景文件时以非零退出。
- **REQ-005 线上安全**：写入用例只使用运行前缀 `e2e-click-<runid>-`；结束时删除本次创建的数据；任何失败都不得留下无法识别的残留。
- **REQ-006 凭据不入库**：账号口令只从被忽略的 `.secrets/ego.local.json` 读取；报告与截图不得出现口令、令牌、主机名、IP、端口。
- **REQ-007 失败可诊断**：失败用例记录模块、用例名、最后一步、当前 URL、失败截图路径；报告不因单条失败中断整轮。
- **REQ-008 只读/写入分档**：默认只读；写入用例必须显式 `--write` 才执行；只读轮次不创建任何业务数据。
- **REQ-009 幂等与可重复**：同一模块连续跑两次结果一致；登录一次复用会话；不使用固定 `sleep`，一律等可观察条件。
- **REQ-010 台账**：每次运行追加 `.agent/testing/runs/YYYY-MM-DDTHHmm-ego-<target>.md`，含 commit、是否 dirty、环境、通过/失败/跳过、耗时、失败明细。
- **REQ-011 不重复既有测试**：字段级断言、接口拒绝矩阵、组件单测不在 ego 场景里重写；ego 只做用户可见流程。
- **REQ-012 可读的场景写法**：一条用例的标题是「用户动作 + 可观察结果」；步骤失败时能看出停在哪一步。

## 质量要求

- 单模块只读运行 ≤ 90 秒；全量只读运行如实记录耗时，不设硬上限。
- 任何一条用例失败不影响后续模块继续跑。
- 报告中的结论只能来自本轮真实执行；跳过的用例必须写明原因。
- 不修改产品行为；如需 `data-testid`，只允许加属性，不改逻辑与样式。

## 约束与依赖

- 线上只有一个学习者专用账号与一个管理员账号；不新建账号（注册需要邮箱验证码）。
- AI 用例会产生真实积分消耗，每个模块最多 1 次真实模型调用；其余用「确定状态」断言。
- 线上数据不可回滚，只能靠「只创建自己的、结束时删除」控制影响。
- 依赖 ego-browser v2.2.0 的页面 API；不引入 Playwright 或其他浏览器驱动。

## 验收标准

- **AC-001**：`node scripts/ego/check-map.mjs` 通过；故意在 `router.tsx` 加一条未登记路由后，该命令非零退出（反向验证）。
- **AC-002**：`ego-browser nodejs < scripts/ego/run.mjs --module knowledge --read-only` 在线上跑通并产出报告。
- **AC-003**：18 个模块全部有场景文件，每个至少 3 条用例；不适用的轴在场景文件头写明理由。
- **AC-004**：`--all --read-only` 能完整跑完，输出 18 个模块的通过/失败/跳过与总耗时。
- **AC-005**：`--write` 轮次创建的每条数据都带 `e2e-click-<runid>-` 前缀，结束后在界面上确认已删除。
- **AC-006**：报告与截图不含口令、令牌、主机名、IP、端口（提交前人工扫一遍）。
- **AC-007**：人为制造一条失败（例如把断言文案改错），报告给出模块、用例、最后一步、URL 与截图路径。
- **AC-008**：同一模块连续跑两次，第二次不因第一次残留而失败。
- **AC-009**：全部改动只涉及 `scripts/ego/`、`.agent/testing/`、必要的 `data-testid` 属性与文档，不改产品逻辑。
- **AC-010**：`.agent/testing/runs/` 里能按时间看到每次运行的记录，旧文件不被覆盖。

## 假设

- 线上账号 `e2e-learner` 具备学习者端全部功能的访问权；管理员账号具备管理端全部页面权限。
- 线上数据里已有至少一条知识条目、一条学习线、一条速递，足以让只读用例有内容可点；没有时场景走空态分支。
- ego-browser 的 `loc=role:...` 选择器能覆盖现有界面；覆盖不到时允许补 `data-testid`。

## 待确认问题

无。环境与范围已由用户 2026-11-02 裁决。
