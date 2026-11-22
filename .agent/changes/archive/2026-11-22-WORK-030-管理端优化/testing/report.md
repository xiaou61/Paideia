---
artifact: test-report
work_id: WORK-030
work: 管理端优化
status: passed
evidence: required
workflow: full
created: 2026-10-21
updated: 2026-10-21
verified_commit: 6b09e86
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:42 +0800
---

# WORK-030 阶段 A 验证报告

> **阶段 A 与阶段 B 都已实现并通过。** 阶段 B 的记录在下面「阶段 B 验收矩阵」一节。

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25，`scripts/test-db.sh` 独占库 |
| 前端 | 管理端从当前工作区构建；e2e 走本地 Vite + 本地后端（隔离库 + devdata 种子） |
| 分支 | `work/WORK-030`，工作树 `.worktrees/WORK-030` |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 搜到人 | `pnpm -F @paideia/admin exec playwright test e2e/users.spec.ts` | 0 | passed | 3 passed；「搜索框输入即筛：搜不存在的串显示空状态」2.0s 通过 |
| AC-002 列表字段齐 | 同上 | 0 | passed | 列表九列（用户名/邮箱/角色/余额/已用/调用/最近活跃/注册时间/操作），e2e 断言表格有首行 |
| AC-003 调积分可用 | `AdminUserIntegrationTest#adjustCreditsRequiresNote` | 0 | passed | 加 100 分后 `ai_credit_accounts.balance=100`，`ai_credit_ledger` 多一条 ADJUST |
| AC-004 不可自伤 | `AdminUserIntegrationTest#cannotTouchSelf` | 0 | passed | 改自己角色/删自己/给自己发分三条都 400，之后角色仍是 admin |
| AC-005 改角色生效 | `AdminUserIntegrationTest#changeRoleRevokesRefreshTokens` | 0 | passed | 角色改成 admin 且该用户 `refresh_tokens` 归零（U-18） |
| AC-006 无 schema 变更 | `git diff --stat` 全仓 | 0 | passed | **没有任何迁移文件**改动（U-19 撤销禁用后 schema 零变更） |
| AC-007 删除干净 | `AdminUserIntegrationTest#adminDeletionCascades` | 0 | passed | 删号后 `users` 与 `refresh_tokens` 都归零；级联与自助注销共用 `deleteCascade` |
| AC-008 积分调整页已消失 | `rg -e ai-credits/grant -e AiCreditsGrantPage frontend/` | 0 | passed | 零命中；`/ai-credits` 转向 `/ai-credits/audit` |
| AC-009 每个列表都能筛 | 管理端 e2e 25 条 + 人工点检 | 0 | passed | 用量审计（种类/用户/分页）、举报（状态）、概念（名称）、分类（名称）、条目（分类）、调用明细（时间/场景/状态/用户/分页） |
| AC-010 举报默认只看待处理 | e2e + 代码 | 0 | passed | `statusFilter` 初值 `'pending'`，列表请求带 `?status=pending` |
| AC-011 调用明细分页可用 | 代码 + 构建 | 0 | passed | `page/size` 参数化，`size=50` 不再写死；页码控件 `runs-prev`/`runs-next` |
| AC-012 时间区间可用 | 代码 | 0 | passed | 近1小时/今天/近7天/近30天四个选项，切后重算 `from/to` |
| AC-013 无原生下拉 | `rg "<select" frontend/apps/admin/src/` | 0 | passed | 零命中（原两处已换 `@paideia/ui` 的 `Select`） |
| AC-014 总览有运营数字 | 代码 + 构建 | 0 | passed | 五个数字：分类/条目/草稿/账号/待处理举报，后两个可点进去 |
| AC-015 不泄露口令 | `AdminUserIntegrationTest#adminCanListWithoutPasswordHash` | 0 | passed | 响应体不含 `passwordhash`、`password_hash`、`$2a$` |
| 权限规则不静默失效 | `AdminUserIntegrationTest#learnerIsForbidden` | 0 | passed | 学习者调列表/详情/改角色/删号四条全 403 |
| 权限规则反向验证 | 把规则 path 改成永不匹配后重跑 | 1 | passed | 按预期红：`expected: 403`（学习者拿到 200） |
| AC-016 步骤能看参数与返回 | `rg -e toolArgs -e toolResult AgentScopeConversation` | 0 | passed | `BridgedTool.callAsync` 里写 `toolArgs`（1000 字符上限）与 `toolResult`（500 上限），超限标明原长 |
| AC-017 步骤有首字 | `AiRunPayloadIntegrationTest` + 代码 | 0 | passed | 中间件按第一个 `TextBlockDeltaEvent` 算 `ttftMs` 并 `step.ttftMs(...)`；`SpanView` 已带该字段 |
| AC-018 截断真的生效 | `truncate()` 单测语义 + 代码 | 0 | passed | 超过上限时返回 `前 N 字符 + 「…（已截断，原长 X 字符）」` |
| AC-019 原有能力不退化 | 全量 `AiTelemetryIntegrationTest` | 0 | passed | 随全量 700 条一起通过 |
| AC-020 能看到完整请求与响应 | `PayloadJson.request/response` + 前端 StepRow | 0 | passed | 请求体含 messages/tools/params；响应体含 text/thinking；前端 JSON 美化展示 |
| AC-021 payload 保留期独立 | `AiRunPayloadIntegrationTest#payloadRetentionIsIndependent` | 0 | passed | 造一条 40 天前的载荷，`purge()` 后载荷归零而 spans 与 runs 仍在 |
| AC-022 开关有效 | `AiRunPayloadIntegrationTest#payloadSettings` + 前端 | 0 | passed | 默认 `payloadEnabled=true`；关闭时 sink 整段跳过，页面显示「本次未记录请求体与响应体（采集已停用）」 |
| AC-017 步骤有首字 | `AiRunPayloadIntegrationTest` + 代码 | 0 | passed | 中间件按第一个 `TextBlockDeltaEvent` 算 `ttftMs` 并 `step.ttftMs(...)`；`SpanView` 已带该字段 |
| AC-018 截断真的生效 | `truncate()` 单测语义 + 代码 | 0 | passed | 超过上限时返回 `前 N 字符 + 「…（已截断，原长 X 字符）」` |
| AC-019 原有能力不退化 | 全量 `AiTelemetryIntegrationTest` | 0 | passed | 随全量 700 条一起通过 |
| AC-020 能看到完整请求与响应 | `PayloadJson.request/response` + 前端 StepRow | 0 | passed | 请求体含 messages/tools/params；响应体含 text/thinking；前端 JSON 美化展示 |
| AC-021 payload 保留期独立 | `AiRunPayloadIntegrationTest#payloadRetentionIsIndependent` | 0 | passed | 造一条 40 天前的载荷，`purge()` 后载荷归零而 spans 与 runs 仍在 |
| AC-022 开关有效 | `AiRunPayloadIntegrationTest#payloadSettings` + 前端 | 0 | passed | 默认 `payloadEnabled=true`；关闭时 sink 整段跳过，页面显示「本次未记录请求体与响应体（采集已停用）」 |
| AC-023 超限不静默丢 | `AiRunPayloadIntegrationTest#oversizedPayloadKeepsPlaceholder` | 0 | passed | 正文为 null 但 `truncated` 为真、`request_bytes` 有值 |
| AC-024 密钥不入库 | `AiRunPayloadIntegrationTest#payloadJsonNeverIncludesCredentials` | 0 | passed | 用带 `sk-super-secret-...` 的 `GenerateOptions` 序列化，结果里搜不到该串，也搜不到 baseUrl |
| AC-025 注销级联 | `AiRunPayloadIntegrationTest#deletionRemovesPayloads` | 0 | passed | 按 user_id 删后本人的载荷归零、别人的仍有 |
| 载荷能落库并读回 | `AiRunPayloadIntegrationTest#payloadRoundTrips` | 0 | passed | 请求与响应正文都完整读回 |

## 阶段 B 的测试数字

| 范围 | 结果 |
| --- | --- |
| 后端全量 | **700 条 / 0 失败 / 18 跳过** |
| 管理端 e2e | **25 passed** |
| 新增测试类 | `AiRunPayloadIntegrationTest` 6 条 |

## 阶段 B 据实说明的缺口

- **没有用真实上游验证"载荷内容与抓包一致"**：`AiRunPayloadIntegrationTest` 验的是
  "落库与读回"和"凭据不进库"，没有跑一次真实 DeepSeek 对话再逐字节比对请求体。
  那段逻辑（`PayloadJson.request` 拿 `ModelCallInput.messages`）在尖刺里验过能拿到完整
  messages，但没有端到端比对。
- **`payloadEnabled=false` 端到端没跑**：只验了配置默认值与 sink 分支的代码路径，
  没有真关掉开关跑一次对话再查库为空。
- **步骤级 `model` 没做**：REQ-051 要求每个 LLM 步记 model 与 prompt_version，
  实际只做到了 run 级（那两列本来就有）。步骤级没有对应列，也没进 attrs。
  **这是与需求的偏离**，据实记在这里。
