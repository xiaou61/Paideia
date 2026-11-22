---
artifact: tasks
work_id: WORK-005
work: AI 精简与悬浮助手
status: approved
mode: strict
workflow: full
depends_on: [WORK-004]
created: 2026-10-12
updated: 2026-10-12
approved_by: xiaou61
approved_at: 2026-10-12 09:51:05 +0800
approver_role: CTO
---

# AI 精简与悬浮助手任务

需求、提案、设计均已签署。八片按"能独立验证"切分，顺序即依赖顺序。

## TASK-001 | done | AI 模块骨架、依赖与 AgentScope 最小调用尖刺

- 对应：设计「模块与包结构」；REQ-001
- 新建：`backend/paideia-ai/`（`pom.xml`、`package-info.java`）、`io.github.xiaou61.ai` 包根与 `internal/{credential,summary,provider,web}` 子包
- 修改：`backend/pom.xml`（注册模块）、`backend/paideia-app/pom.xml`（依赖 `paideia-ai`）
- 步骤：
  1. 依赖 `io.agentscope:agentscope-core:2.0.0` 与 `io.agentscope:agentscope-extensions-model-openai:2.0.0`（**取同一版本号**，本地仓库里 OpenAI 扩展只有 2.0.0）。
  2. **尖刺**：写一个最小的一次性调用（临时测试或 `main` 方法均可），在依赖里核实 OpenAI 兼容模型类与 DeepSeek formatter 的**确切类名、构造方式、结构化输出入口**，把结果**回填 design.md**（设计里明确写了"不猜 API"）。
  3. 建 `EntrySummarizer` 端口与值对象（`SummaryCard`／`SummarySection`／`SummarizedContent`／`SummaryVisibility`）；端口签名不含 AgentScope 类型。
  4. `KnowledgeApi` 加 `findPublishedContent(long entryId)` 与 `EntryContent`，并建它的实现类（WORK-004 记的"等第一个真实方法"时点）。
- 验证：`mvn -B compile` 通过；`ModularityTest` 仍通过（模块边界）；尖刺结论已写进 design.md。

## TASK-002 | done | 凭据：加密落库与读写接口

- 对应：REQ-003；AC-002
- 新建：迁移（`ai_model_credentials` 与 `ai_entry_summaries` 两张表，编号取当时可用值）、`ModelCredential`／`ModelCredentialMapper`、`CredentialCipher`、`AiProperties`、`CredentialService`、`AiCredentialController`
- 步骤：
  1. `AiProperties.masterKey` 绑定 `paideia.ai.master-key`；`CredentialCipher` 构造期校验（缺失/长度/非法 base64 → 启动失败且说明是哪一种）。
  2. AES-GCM 加解密，布局 `iv || ciphertext || tag`；解密失败抛可识别异常。
  3. `GET/PUT/DELETE /api/v1/ai/credentials`：只回 `{configured, baseUrl, model, apiKeyLast4}`，**永不回 Key**；PUT 前做 SSRF 校验（TASK-003）。
  4. `SecurityConfiguration` 显式加 `/api/v1/ai/**` 的"已认证即可"规则并注释说明为何无角色限制。
  5. 日志纪律：`toString`／异常信息／日志里不得出现 Key 或密文。
- 验证：单测（加解密往返、篡改检测、主密钥缺失即失败）+ 集成测试（CRUD、只回 last4、日志无明文、匿名 401）。

## TASK-003 | done | SSRF 校验

- 对应：设计的 SSRF 节；AC-007
- 新建：`UpstreamGuard`（+ 单测）
- 步骤：按设计 4 条实现（https 必须、userinfo 拒绝、**全部**解析结果必须是公网、禁止重定向），并保证校验失败时请求不发出。
- 验证：单测覆盖回环／私网／链路本地与云元数据／ULA／IPv4 映射／非 https／含 userinfo；集成测试证明拒绝时端口未被调用。

## TASK-004 | done | 生成、结构化输出与缓存可见性

- 对应：REQ-001、REQ-002、REQ-004、REQ-005、REQ-007、REQ-008；AC-001、AC-003、AC-004、AC-006、AC-009、AC-010
- 新建：`EntrySummary`／`EntrySummaryMapper`、`SummaryFingerprint`、`SummaryService`、`SummaryPrompt`、`SummarySchemaValidator`、`AgentScopeEntrySummarizer`、`AiSummaryController`
- 步骤：
  1. 指纹 = 正文 SHA-256 + model + prompt 版本；正文改动即换指纹。
  2. 读路径按设计：可见结果命中则**不调模型**（`cached=true`、`usage=null`）；未命中取凭据 → 调端口 → 校验 → 落库。
  3. 结构化输出 + 字段级校验；**空内容与校验失败绝不落库**。
  4. 关闭思考模式、`max_tokens=1024`、超长正文按段落截断并标注。
  5. 并发写用"先查后插 + 唯一键兜底回读"。
  6. `GET/POST /api/v1/ai/summaries`；错误映射按设计表（不泄露上游原始报文）。
- 验证：单测（校验器、指纹）+ 集成测试（**计数桩**证明命中不再调用、可见性矩阵、正文改动失效、无凭据可读不可生成、错误映射）。

## TASK-005 | done | 限流与指标

- 对应：REQ 之外的质量要求；AC-005 的一部分（42900）
- 新建：进程内固定窗口限流器、指标计数（日志 + Actuator 可读）
- 步骤：每用户每分钟 6 次、每日 60 次（生成计入，读缓存不计入）；超限 42900。计数用 `ConcurrentHashMap<userId, Window>` 并写 `ponytail:` 注释标明单实例天花板。
- 验证：集成测试连续触发到 42900；日志里能看到四类指标名。

## TASK-006 | done | 前端 `packages/assistant`

- 对应：REQ-001、REQ-005、REQ-006；AC-001、AC-010
- 新建：`frontend/packages/assistant/`（`client.ts`、`types.ts`、`summary-card.tsx`、`assistant-shell.tsx`、`credential-dialog.tsx`、`context.tsx`、`index.ts`）
- 修改：`frontend/packages/ui/src/styles/globals.css`（**登记 `@source`**，否则类名静默不生成）
- 步骤：客户端接五个端点；卡片渲染结构化字段 + 来源与用量脚注；悬浮壳收起/展开两态、非条目页显示"打开一条内容后可用"、未配凭据时给配置入口；凭据弹窗三字段 + last4 回显；`AssistantProvider` 暴露 `setEntry`。
- 验证：`pnpm --filter @paideia/assistant typecheck` 与单测（卡片渲染、客户端错误映射）；`source-coverage` 检查通过。

## TASK-007 | done | 学习者端挂载与 e2e

- 对应：AC-001（端到端）
- 修改：`apps/app` 外壳挂载悬浮壳、条目页设置/清除上下文、`apps/app/e2e/`（新增一条 e2e：条目页能看到壳、未配凭据时显示配置入口、不是真实调用模型）
- 验证：`pnpm --filter @paideia/app test:e2e`（本地跑，不指向线上）。

## TASK-008 | done | 全量回归与测试报告

- 修改：`testing/plan.md`、`testing/report.md`
- 步骤：跑 `mvn -B verify`、`pnpm -r typecheck`、`pnpm -r test`、三端 e2e、四项边界检查、桌面产物核对；按验收矩阵逐条落证据；把 DNS rebinding 等残余风险写进报告而不是省略。
- 验证：报告 `status: passed` 且每条 AC 有命令 + 退出码 + 证据位置。

## 报告状态

`testing/report.md` 为 **`passed`**：10 条 AC 与两条反向验证都有命令 + 退出码 + 证据位置；
**所有测试都不真连上游**（生成路径用桩覆盖）。残余风险（DNS rebinding、单实例限流、
孤儿行、主密钥轮换即全体重填、AgentScope 年轻、未做真连冒烟）已写在报告里，没有省略。

## 完成条件

- 十条 REQ 都有实现，十条 AC 都有可复查证据；
- 测试一律不真连上游；
- 六条收口决策若被用户推翻，改回设计对应条目再继续。
