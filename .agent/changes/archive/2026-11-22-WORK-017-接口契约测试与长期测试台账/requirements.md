---
artifact: requirements
work_id: WORK-017
work: 接口契约测试与长期测试台账
status: approved
mode: strict
mode_reason: 覆盖全部 60 条 /api/v1 公共接口；新增打生产部署的冒烟与部署闸门（release.ps1、CI）；公共契约与部署行为变更，且台账会进入公开仓库
workflow: full
related_to: [WORK-001, WORK-016]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 14:16:10 +0800
approver_role: CTO
---

# 接口契约测试与长期测试台账需求

## 本项状态与本轮变化

- **2026-10-18 建项起草（draft）**。用户原话：「我想弄个东西 就是叫做测试 就是统一进行每一个接口详细测试 然后再加上自动化测试 就去线上测 我要怎么保证就是比如说后续修改了某个接口 肯定要再测一遍吧 这个问题 然后还有就是标准一点的」「我想把这个当做是一个长期任务的」「要记录到 .agent 里面某个文件夹里面测试的记录 结果之类的」。
- 标准草案已写在 [`.agent/notes/api-testing-standard.md`](../notes/api-testing-standard.md)；台账结构已建在 [`.agent/testing/`](../testing/README.md)。
- **待批准前不实施**：不新增测试类、不改 `release.ps1`、不建 CI。

## 已确认事实

### 仓库事实（2026-10-18 实测）

- `/api/v1` 下共 **60 条路由**（不含 `/api/v1/fixture/**` 测试夹具）：17 条 `/admin/`、8 条公开或凭据交换、35 条要求登录。
- 现有测试资产：后端 143 条（143 运行 / 0 失败 / 4 跳过），前端单测 240 条，学习者端 e2e 60 passed / 2 skipped，管理端 e2e 19 passed。
- **没有 OpenAPI/Swagger**：`backend` 的 pom 中没有 springdoc 或任何 openapi 依赖。
- **没有 CI**：`.github/workflows` 不存在。
- `scripts/release.ps1` 的 `-WithTests` 是**可选开关**，默认不跑测试；部署后只做 `/actuator/health` 探活。
- 缺 `PAIDEIA_TEST_DB_PASSWORD` 时，`paideia-app` 的集成测试整块跳过，`BUILD SUCCESS` 仍然成立——"假绿"已有明确先例。
- 线上登录按失败次数限流；成功登录不累计。前端 e2e 已支持 `E2E_BASE_URL` 打已部署实例。
- 管理端 e2e 打线上时会真的写生产库（自建自删），是既有事实。
- 安全层的 401/403 由 Spring 默认入口点返回，**没有** `ApiResponse` 包络；业务层错误才有 `{code,message,traceId}`。
- 所有既有路由在测试代码里至少被引用一次；唯一例外是工作区新加的 `GET/PUT /api/v1/account/profile`（两条都没有测试引用）。

## 用户决定

- **2026-10-18 方向订正（用户原话）**：「就是弄一个标准，以后我测的时候方便，不用什么 cicd 这种」；
  以及对"不能访问"的直接质疑：「应该要做到可以访问全接口的」。
  - 因此：**撤掉 CI 与部署闸**（`.github/workflows` 已删、`release.ps1` 已还原），
    把重心从"上线闸门"改为"**一条命令跑完全部接口的手动自查标准**"。
  - 并逐条解决"跑不通"的限制，做到**真的能访问全部接口**：自建账号、直接签 admin 令牌、
    AI 三端口打桩、注册后自发积分、附件传真 PNG 字节、沙箱 503 也算可达。
  - 新增覆盖断言：自查最后拿运行时路由全集与实际打到过的路由做差集，**非空即失败**。
    这条断言第一次运行就抓出 5 条没走到的端点。
- 把接口测试当成**长期任务**，不是一次性补齐。
- 测试记录与结果要落在 `.agent` 下的固定位置。
- 目标口径是"改了接口就一定重测"，且做法要"标准一点"。
- **2026-10-18 对四条待确认问题的裁决**（用户回"可以的继续吧"，即采纳推荐项）：
  1. **CI 现在就接**（GitHub Actions，MySQL service container + 前端检查）。
  2. **线上冒烟沿用现有 `e2e-learner` 与 `admin`**，不新建只读账号。
  3. **台账进 Git**（脱敏后），历史可回溯。
  4. **标准暂留 `.agent/notes/`**，不提升到 `rules/always.md`；等跑稳后再谈是否升级为强制约束。

## 目标

让"改了接口要重测"从**记忆**变成**构建失败**，并把每次运行的结果沉淀成可回溯的台账。

具体是四层闸，每层只回答一个问题：

1. **L1 路由清单**：有没有漏掉一个接口？（运行时路由集合与清单做集合相等比较）
2. **L2a 通用拒绝矩阵**：每个接口的边界一致吗？（匿名 401、越权 403、畸形体 400、不存在 404、响应头）
3. **L2b 业务语义**：这个接口做对了吗？（现有集成测试 + 新接口的成功/失败各一条）
4. **L3 线上只读冒烟**：部署后的那个进程真的对吗？
5. **L4 闸门**：CI 与部署流程是否真的会跑这些检查？

## 非目标

- 不引 Pact、RestAssured、Karate、Postman/Newman。
- 不做按 git diff 的选择性测试。
- 不追求 100% 行覆盖。
- 不在本项做 OpenAPI 文档与客户端生成（列为二期可选）。
- 不改安全层 401/403 的响应包络（与测试无必然关系，单独评估）。

## 用户与工作流程

### 日常改一个接口

1. 写/改控制器。
2. 在控制器类或方法上标 `@ApiAccess(ApiAccess.Access.…)`；不标则 L1 红，标错则 L2a 红。
3. L2a 自动覆盖拒绝路径。
4. 补至少一条成功 + 一条业务失败的集成测试。
5. 跑 `mvn -B verify`；新套件不许跳过。
6. 部署后跑 L3 冒烟，结果落 `.agent/testing/runs/`。

### 每个切片收口

`release.ps1` 默认跑测试；部署后自动冒烟；任一失败则非零退出并打印红。

## 功能需求

### REQ-001 路由清单闸（L1）

- **2026-10-18 修订（用户要求更自动）**：取消人工清单。`paideia-platform` 提供 `@ApiAccess(Access)`，
  测试从 `RequestMappingHandlerMapping` 枚举运行时路由并解析注解；声明写在代码旁边，不另立第二真相源。
- 必须拦住四类：端点未标注解；`@RequestMapping` 不限方法；接口挂在 `/api/v1` 之外；同一端点两种声明来源。
- 排除 `/api/v1/fixture/**`（测试专用控制器）。
- 不建 YAML/Postman。

### REQ-002 通用拒绝矩阵（L2a）

对清单中每条路由，自动执行：

| 用例 | 期望 | 适用 |
| --- | --- | --- |
| 匿名调用 | 401 | 非 PUBLIC |
| learner 调 ADMIN | 403 | ADMIN |
| 畸形 JSON 体 | 400 + `code=40000` | JSON_BODY |
| 不存在的 id | 404 + `code=40400` | ID_ROUTE |
| 响应头 | `X-Trace-Id` 存在且匹配 `[A-Za-z0-9_-]{1,64}` | 全部 |

- **矩阵不调用成功路径**：避免写库、跑代码、花积分。
- 共用 `ApiTestSupport`：learner/admin 令牌由 `AuthPort` 直接签发，不依赖账号数据。

### REQ-003 业务语义（L2b）

- 现有集成测试保留。
- 新增接口的完成标准：至少一条成功 + 一条业务失败。
- 不为覆盖率设硬门槛。

### REQ-004 全接口正面自查（原「线上只读冒烟」，2026-10-18 改向）

- `ApiWalkthroughTest`：一条命令、自己注册账号、把每条接口真走一遍、注销收尾。
- 覆盖断言：运行时路由全集减去本轮实际打到过的路由，必须为空。
- 报告自动写入 `.agent/testing/runs/`（时间戳命名）。
- **不做**线上定向冒烟（那是用户明确排除的形态）。

- 用已装的 Playwright `request`，不引新工具。
- 登录一次拿 token；**不复用 UI 的 `storageState` 去调 `/auth/refresh`**（轮换会作废 UI 那条）。
- 只测：健康检查、匿名 401、越权 403、只读 GET、随机 id 404、未知路径 404、错误方法 405、错误 Content-Type 415。
- 默认不碰：注册、AI 对话、sandbox、所有写接口。
- 写路径需显式开关 `ALLOW_ONLINE_MUTATION=1`，且唯一前缀 + `try/finally` 自清理。
- 放在 `frontend/apps/app/e2e/api-smoke.spec.ts`，单独脚本，不进默认 `test:e2e`。

### REQ-005 不接 CI / 不改部署（2026-10-18 改向）

- **明确不做 CI**，也不改 `release.ps1`。用户明确说"不用什么 cicd"。
- 检查由人按需执行，给一条命令与一份报告即可。

### REQ-006 长期台账

- 每次 `contract` / `smoke` 运行在 `.agent/testing/runs/` 追加一个文件，命名为 `YYYY-MM-DDTHHmm-<layer>-<target>.md`。
- 文件含：commit、是否 dirty、层、目标（local/online）、通过/失败/跳过、耗时、失败明细、与上次的差异。
- 常规运行**不写** `.agent/history/updates.md`；只有台账结构变化、导致代码修改的失败、闸门首次接入才写。
- 不建"最新状态汇总"文件；文件名排序即时间线。

### REQ-007 台账脱敏

- 不写主机名、IP、端口、账号、令牌、连接串。
- 目标只写 `local` / `online`；账号只写 `learner` / `admin`。
- 失败信息带出敏感值时先删再提交。

### REQ-008 不静默跳过

- 与既有规则的关系要说清：`.agent/rules/always.md` 已签署"未配置口令时集成测试跳过、构建不因此失败"，本项**不推翻**它。
- 补偿手段：CI 里提供数据库并断言契约套件的 `skipped == 0`；本地跳过时台账必须记录 `skipped>0`。
- 因此"本地可能跳过"与"合并必不跳过"同时成立。

## 质量要求

- 零新依赖（Playwright、MockMvc、Spring 测试栈都已在用）。
- 契约套件与现有集成测试共用同一个 Spring 上下文，不新增上下文（`@SpringBootTest(properties=...)` 一律不用）。
- 断言错误信息要能定位到具体路由，例如 `GET /api/v1/notes/{id} 缺少清单条目`。

## 约束与依赖

- 集成测试共用一个测试库，本地串行；CI 必须用独立容器库，避免与本地并发写同一个 `paideia_test`。
- `PAIDEIA_TEST_DB_URL` 覆盖时必须保留 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。
- 线上冒烟会真的发请求；账号口令只从环境变量来。
- 依赖 WORK-001 的测试栈与 WORK-016 的移动端/线上 e2e 路径。

## 实现选择

- **运行时枚举而非扫源码**：从 `RequestMappingHandlerMapping` 取路由，类级前缀、路径变量规范形与方法条件都由框架解析好；扫注解正则会漏掉组合注解与 `path=` 写法。
- **清单只登记三样**：`method / pattern / access`。请求体与 `consumes` 从 handler 方法反射推导，避免第二个真相源。
- **路径变量替换用兜底正则**：只有 `{shareId}`（UUID 形状）与 `{source}`（只认 structured/text）需要语义值，其余一律填 `1`。
- **矩阵不碰成功路径**：畸形体在参数绑定阶段就被拒，到不了 service，因此不写库、不跑代码、不花积分。
- **令牌直接用 `AuthPort` 签发**：不依赖账号表，与既有集成测试一致。
- **401/403 只断言状态码**：安全层由 Spring 默认入口点产生，没有 `ApiResponse` 包络；断言业务码会误报。
- **冒烟不复用 UI 的 storageState**：refresh 是轮换的，冒烟拿它去换会让同轮的 UI 用例失效。
- **CI 里断言 `skipped == 0` 而不改既有跳过规则**：`.agent/rules/always.md` 已签署"无口令时跳过不失败"，本项不推翻它，改用 CI 补偿。
- **不自动写台账**：测试里写文件会在 CI 产生噪音与并发问题；台账由人执行时记录。

## 验收标准

| 编号 | 验收标准 | 验证方式 |
| --- | --- | --- |
| AC-001 | 每个 `/api/**` 端点都能从 `@ApiAccess` 解析到访问级别；不限方法的映射、`/api/v1` 之外的接口、双声明均使测试失败 | 自动化；四类反向验证 |
| AC-002 | 60 条路由全部经过拒绝矩阵；PUBLIC 路由正确跳过匿名 401 | 自动化 |
| AC-003 | learner 打 17 条 ADMIN 路由全部 403 | 自动化 |
| AC-004 | 畸形 JSON 体与不存在 id 分别得到 400/404 与对应业务码 | 自动化 |
| AC-005 | 每条响应带合法 `X-Trace-Id` | 自动化 |
| AC-006 | `ApiWalkthroughTest` 一条命令跑完全部端点；**覆盖断言**证明运行时路由无遗漏 | 运行记录 + 断言 |
| AC-007 | 不引入 CI、不改 `release.ps1` | 检查 `.github` 不存在、`scripts/release.ps1` 无 diff |
| AC-008 | 本地无 `PAIDEIA_TEST_DB_PASSWORD` 时按既有规则跳过（不失败）；结果里 `Skipped` 可见 | 反向验证（不设口令跑一次） |
| AC-009 | 每次 contract/smoke 运行产生一个台账文件，字段齐全 | 检查 `.agent/testing/runs/` |
| AC-010 | 台账不含主机名、账号、令牌、连接串 | 提交前扫描 |

## 待确认问题

无（四条已裁决，见「用户决定」）。
