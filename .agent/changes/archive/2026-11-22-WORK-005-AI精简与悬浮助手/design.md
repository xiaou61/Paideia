---
artifact: design
work_id: WORK-005
work: AI 精简与悬浮助手
status: approved
mode: strict
mode_reason: 首次调用外部模型供应商；用户自带密钥加密落库（数据敏感）；代理端点会按用户给的地址发起服务端出站请求（SSRF 面）
workflow: full
depends_on: [WORK-004]
related_to: [WORK-007]
created: 2026-10-12
updated: 2026-10-12
approved_by: xiaou61
approved_at: 2026-10-12 09:50:30 +0800
approver_role: CTO
---

# AI 精简与悬浮助手设计

需求与提案均已签署（`xiaou61`／CTO）。本设计把提案落成可实施的结构，并把提案「待决定事项」的六条**由 Agent 收口**（用户 2026-10-12 指示「直接全做了」），逐条给理由与天花板；用户随时可推翻。

## 决策：六条选型的收口

| # | 选择 | 理由 | 天花板 / 触发重评 |
| --- | --- | --- | --- |
| 1 | **应用层 AES-GCM**（JDK 自带 `AES/GCM/NoPadding`，256 位密钥、每条随机 12 字节 IV、128 位 tag） | 标准库即可，不引依赖；每条独立 IV，同一 Key 两次落库密文不同 | 只有对称加密、无密钥分层。需要密钥轮换不停机时改两级 datakey |
| 2 | **主密钥走环境变量**（`paideia.ai.master-key`，base64 的 32 字节；缺失即启动失败） | 与既有 `DEEPSEEK_API_KEY`／SMTP 口令的注入方式一致 | **轮换主密钥 = 全体用户重新填一次 Key**（解密失败降级为"请重填"，不迁移旧密文） |
| 3 | **应用内固定窗口限流**（每用户每分钟 N 次、每日 M 次） | 单实例自托管（需求「假设」），几十行足够 | 多实例后各实例独立计数 → 需要集中式限流 |
| 4 | **SSRF：校验全部解析结果 + 禁止重定向**，并**把 DNS rebinding 明写为残余风险** | 见下节"为什么不按解析后 IP 直连" | 出现多租户或对外网开放时，上网络层出口策略（白名单/代理） |
| 5 | **凭据设置放在悬浮壳内的弹窗** | 就近、不新增路由与页面 | 设置项变多（多模型、参数模板）时拆成独立设置页 |
| 6 | **token 用量只随本次响应返回、不落库** | 最小面；BYOK 下平台不需要记账 | 要做用量统计/趋势时再落库 |

## 模块与包结构

新增 Maven 模块 `paideia-ai`（在 `backend/pom.xml` 的 `<modules>` 与 `paideia-app/pom.xml` 的依赖各注册一处，共两处；三处注册是知识库那时的口径，含 `paideia-app` 的 dependencies）。

```
io.github.xiaou61.ai                     ← 包根＝对外契约（跨模块只能引这里）
  EntrySummarizer                        端口接口（业务侧唯一入口）
  SummaryCard / SummarySection           结构化卡片的值对象
  SummarizedContent                      端口返回：卡片 + 用量 + 是否命中缓存 + 归属
  SummaryVisibility                      public | private
io.github.xiaou61.ai.internal
  credential/  ModelCredential(Mapper), CredentialService, CredentialCipher, AiProperties
  summary/     EntrySummary(Mapper), SummaryService, SummaryFingerprint
  provider/    AgentScopeEntrySummarizer（EntrySummarizer 的实现）, SummaryPrompt,
               SummarySchemaValidator, UpstreamGuard
  web/         AiCredentialController, AiSummaryController, AiRequester
```

- **AgentScope 只出现在 `internal.provider`**：端口签名不含它的任何类型（D-09 的既有要求），因此换实现或升级框架都不动业务与接口。
- 依赖坐标：`io.agentscope:agentscope-core:2.0.0` + `io.agentscope:agentscope-extensions-model-openai:2.0.0`。**取 2.0.0 而不是最新的 2.0.4**：本地仓库里 OpenAI 扩展只有 2.0.0，核心却有两个版本，取同一版本号可保证离线构建与二进制一致（不引 `agentscope-spring-boot-starter`：本项只发一次调用，不需要它的 Spring 装配，也少一处自动配置风险）。
- **不猜 API**：本设计只固定"用哪种能力"（OpenAI 兼容模型 + DeepSeek formatter + 结构化输出），**确切类名与构造方式由 TASK-001 的尖刺在依赖里核实后回填本文**。理由是 AgentScope 2.0.0 GA 仅三个月、文档与实现可能不同步，照文档臆写会得到一堆编译错误。

## 数据模型

迁移一个文件，**编号取实施当时的可用值**（当前是 `V4`；编号不写死在文档里——既有教训是并发会话会占用编号）。

### `ai_model_credentials`（每用户一行）

```sql
id, user_id BIGINT UNSIGNED NOT NULL,
base_url VARCHAR(512) NOT NULL,
model VARCHAR(128) NOT NULL,
api_key_cipher VARBINARY(512) NOT NULL,   -- iv(12) || ciphertext || tag(16)
api_key_last4 CHAR(4) NOT NULL,           -- 只为界面回显"已配置 ····ABCD"
created_at / updated_at  (DATETIME(3)，默认 CURRENT_TIMESTAMP(3))
UNIQUE KEY uk_ai_credential_user (user_id)
```

- **不存明文 Key**，也不存"Key 的哈希"（哈希无法解密、对调用无用）。
- 不用 MySQL 的列级加密函数：那会把密钥送进 SQL 语句与日志。

### `ai_entry_summaries`（每条目每人一行）

```sql
id, entry_id BIGINT UNSIGNED NOT NULL,
content_hash CHAR(64) NOT NULL,           -- 正文 SHA-256（十六进制）
model VARCHAR(128) NOT NULL,
prompt_version VARCHAR(16) NOT NULL,
owner_user_id BIGINT UNSIGNED NOT NULL,
visibility VARCHAR(16) NOT NULL,          -- public | private
card_json JSON NOT NULL,                  -- 已通过字段校验的卡片
created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
UNIQUE KEY uk_ai_summary_identity (entry_id, content_hash, model, prompt_version, owner_user_id)
KEY idx_ai_summary_read (entry_id, content_hash, model, prompt_version, visibility)
```

- **键里带 `content_hash`**：正文一改哈希即变，旧结果自然不再命中；不依赖"任何 UPDATE 都会刷时间戳"这个隐式前提。
- **读过滤不含模型名**（模型名只在写入的唯一键里）。这是实施时发现的设计矛盾：把模型名也拿来过滤，等于要求"读的人必须先有一个模型名"，而没配凭据的用户恰恰没有——那样他们永远读不到任何公开结果，与需求"未配凭据者可读公开结果"直接冲突。模型名留在唯一键里已足够：它保证同一个人换模型再生成时不会被旧行顶掉。
- **不建跨模块外键**：`entry_id`／`owner_user_id` 指向别的模块的表，加 FK 会把模块间的数据耦合写进数据库。代价是删条目/删用户会留下不可达的行（与既有的孤儿附件同类），列为已知缺口。
- **只存已校验通过的卡片**；校验失败与空结果**一律不落库**。

## 端口与契约

### 知识侧新增（本项的第一个真实跨模块调用）

`KnowledgeApi`（当前是空接口）新增一个方法，供 AI 模块取正文：

```java
// io.github.xiaou61.knowledge.KnowledgeApi
Optional<EntryContent> findPublishedContent(long entryId);
// EntryContent = title + body（+ updatedAt）；**只返回已发布条目**，草稿与不存在都返回 empty
```

- 只返回已发布内容：学习者侧本来就看不到草稿，让它能被 AI 总结没有意义，也少一个越权读取面。
- 这是 WORK-004 记的"等第一个真实方法出现时再建 `KnowledgeApiImplementation`"的那个时刻，本项建它。
- **AI 模块只依赖 `paideia-knowledge` 的包根**（Modulith 边界由构建期校验）。

### AI 侧端口

```java
public interface EntrySummarizer {
    SummarizedContent summarize(long entryId, SummaryVisibility visibility, long requesterId);
}
```

端口自己不清缓存、不管凭据：缓存与可见性在 `SummaryService`（同模块内部），凭据在 `CredentialService`。这样"换模型实现"只影响 `provider` 包。

## 接口设计

统一走既有 `{code, message, data}` 信封。**不新增 ErrorCode**（该枚举是"追加制"的公共契约，本项不需要前端按码分支，靠 `message` 区分即可）：

| 端点 | 行为 |
| --- | --- |
| `GET /api/v1/ai/credentials` | 返回 `{configured, baseUrl, model, apiKeyLast4}`；**永不返回 Key** |
| `PUT /api/v1/ai/credentials` | 校验（含 SSRF）→ 加密落库 → 回同样的脱敏结构 |
| `DELETE /api/v1/ai/credentials` | 删除该用户的凭据（同时让"能否生成"变回否） |
| `GET /api/v1/ai/summaries/{entryId}` | 只读：命中可见结果就返回；没有返回 `data: null` |
| `POST /api/v1/ai/summaries` | 生成或命中；body `{entryId, visibility}` |

- 全部要求登录；**不需要角色限制**（这是用户自己的额度与凭据，不是管理操作），但要在 `SecurityConfiguration` 里**显式**加 `/api/v1/ai/**` 的"已认证即可"规则并写注释说明为什么不加角色——避免将来被误当成漏配。
- 返回体里的卡片字段：`{headline, sections:[{heading, body}]}`，加 `cached`（是否命中）、`source`（`mine`／`others`）、`usage`（命中时为 `null`）。

错误映射（复用既有码 + 明确 message）：

| 情形 | code | message 示例 |
| --- | --- | --- |
| 未配置凭据就触发生成 | 40900 | 尚未配置模型凭据 |
| baseUrl 不被允许 | 40000 | baseUrl 不被允许：只接受 https 且解析到公网地址 |
| 上游拒绝这个 Key（401/403） | 40000 | 上游拒绝了这组凭据，请检查 Key 与 baseUrl |
| 超时 / 上游 5xx | 50000 | 上游模型服务暂时不可用 |
| 空响应或字段校验失败 | 50000 | 模型没有返回可用的精简结果 |
| 超出限流 | 42900 | 请求过于频繁，请稍后再试 |

**任何一条 message 都不得包含上游原始报文**（可能回显 Authorization 或内部地址）。

## 凭据的加密与主密钥

- `AiProperties.masterKey` 绑定 `paideia.ai.master-key`（环境变量 `PAIDEIA_AI_MASTER_KEY`）：base64 的 32 字节。
- `CredentialCipher` 在**构造期**校验主密钥：缺失、长度不对、非法 base64 → **启动失败并说清是哪一种**（不降级为明文，也不生成随机密钥——后者会让重启后所有密文作废且无人知道）。
- 加密：`AES/GCM/NoPadding`，12 字节随机 IV，tag 128 位；存储布局 `iv || ciphertext || tag`；解密时验证 tag，**校验失败抛可识别异常**（→ 提示"凭据无法解密，请重新填写"，不是 500）。
- 日志纪律：`ModelCredential` 的 `toString`／异常信息里不得出现 Key；`api_key_cipher` 也不进日志。测试里有专门用例。

## SSRF 校验（信任边界，不做成可选项）

`UpstreamGuard.assertAllowed(URI)`：

1. scheme 必须是 `https`；URI 不得含 userinfo；host 必须存在。
2. `InetAddress.getAllByName(host)` —— **全部**解析结果都必须通过：
   - 拒 `isAnyLocalAddress` / `isLoopbackAddress` / `isLinkLocalAddress`（含 `169.254.169.254` 云元数据）/ `isSiteLocalAddress` / `isMulticastAddress`；
   - 额外拒 IPv6 ULA（`fc00::/7`）与 `::ffff:` 形式的 IPv4 映射地址（映射到内网的要按内网判）。
3. 出站 HTTP 客户端**禁止重定向**（`followRedirects(NEVER)`）。
4. 校验失败时**请求不得发出**（AC-007 用"桩替身未被调用"证明）。

**为什么不按解析后 IP 直连**（更彻底但未选）：HTTPS 的证书校验必须对**主机名**做，而直连 IP 会让 JDK 的 `HttpClient` 用 IP 当主机名去校验——两者在标准 API 上不能同时成立；绕过它就要自己接管 TLS 校验（关掉主机名校验或手写 SNI + 证书比对），那是**用一个更严重的错误换一个较小的风险**。因此：校验 + 禁重定向 + **把 DNS rebinding 写进报告为残余风险**，并记为"多实例或对外网开放时上网络层出口策略"的触发条件。

## 调用与结构化输出

- **一次调用，不启用 agent 运行时**：没有工具、没有记忆、没有多轮（属 WORK-006）。用的是 OpenAI 兼容模型 + DeepSeek formatter。
- **显式关闭 natives 结构化输出与工具的耦合**：`nativeStructuredOutputWithTools(false)`。本项无工具，这条设置没有副作用，但它把"将来加工具会撞上 DeepSeek 那条限制"变成一次显式声明。
- **结构化输出：要求模型只回一段 JSON，再由 `SummarySchemaValidator` 逐字段校验**（`headline` 非空、`sections` 非空、每节 `heading` 与 `body` 非空、长度上限内）。**空内容与校验失败都按失败处理，绝不入缓存。**
  **这是对本文的一处实施偏离**（原设计写的是"走框架的合成工具回退路径"）：AgentScope 对 DeepSeek 的结构化输出要靠自己构造工具 schema 并解析工具调用参数，面比收益大；而校验器本来就不可少（原生路径也会字段漂移），少一层协议是净收益。两个 `nativeStructuredOutput*` 开关仍按原设计显式写成 `false`——那是给"将来加工具"留的显式声明。
- **`max_tokens` 与思考模式**：默认**关闭思考**（`enableThinking(false)`），`max_tokens` 设 1024——卡片是短产物，且思考 token 会吃 `max_tokens` 并导致**静默空响应**（DeepSeek 实测）。若将来开思考，必须同时抬高 `max_tokens` 并**分开渲染思考与正文两类事件**（流式时才需要）。
- **超长正文**：超过上限（8000 字符）按段落截断，并在卡片上标注"已按前 N 字生成"。
- **提示词版本**：`SummaryPrompt.VERSION`（如 `v1`）进缓存键；改提示词等于换键，不复用旧结果。

## 缓存与可见性

读路径（`SummaryService`）：

1. 取正文 → 算 `content_hash`（正文原文的 SHA-256）。
2. 查"当前用户可见且指纹相同"的结果：`visibility='public' OR owner_user_id = :me`，按 `created_at DESC` 取最新一条。
3. 命中 → 返回，`cached=true`、`usage=null`、`source` 按 owner 判定；**不调模型**。
4. 未命中 → 取凭据（没有 → 40900）→ 调端口 → 校验 → 落库（`owner=me`、`visibility` 由请求给）→ 返回 `cached=false` + 真实用量。
5. **写入用"先查后插 + 唯一键兜底"**：并发下唯一键冲突则回读已有行，不抛给用户。

消费者侧的"只看我的"是**客户端偏好**（`localStorage`），不是服务端设置：它只影响前端把 `source=others` 的结果折叠起来，不构成权限。**权限只有一条**：私有结果只有 owner 能读；这条由服务端 SQL 的 `WHERE` 保证，前端偏好不参与。

## 限流与可观测

- 固定窗口：每用户**每分钟 6 次、每日 60 次**生成；读缓存不计入。超限 → 42900。
- 计数放进程内的 `ConcurrentHashMap<userId, Window>`；**天花板**：单实例、重启清零（写 `ponytail:` 注释）。多实例或需要跨重启保留时换集中式。
- 指标以日志 + Actuator 可读形式暴露（不引 Prometheus）：`ai.summary.generated`、`ai.summary.cached`、`ai.summary.failed{reason}`、`ai.credential.saved`。

## 前端设计

新增 `packages/assistant`（含 `.tsx`，**必须登记 `globals.css` 的 `@source`**，有自动化检查兜底）：

- `client.ts`：`createAssistantApi(client)` —— 凭据读写、摘要读/生成。
- `summary-card.tsx`：结构化卡片（小标题 + 正文），带来源与用量脚注（"你自己生成的"／"来自其他学习者"；命中缓存时"本次未产生调用"）。
- `assistant-shell.tsx`：右下角常驻悬浮壳。收起是一个胶囊按钮；展开是面板（当前条目标题 → 精简结果／未生成时的按钮／未配凭据时的配置入口）。**没有对话输入框**（需求裁决 3）。
- `credential-dialog.tsx`：baseUrl / model / apiKey 三个字段 + "只回显 last4"的提示；保存后只回 `last4`。
- `context.tsx`：`AssistantProvider` + `useEntryContext()`。条目页在挂载时 `setEntry({id, title})`，离开时清空；非条目页壳显示"打开一条内容后可用"。
- 外壳挂载点：`apps/app` 的 `AppShell` 层（全站常驻）；Web 与桌面共用同一产物。

## 复用与新增件

- **复用**：`@paideia/ui` 的 `Dialog`/`Button`/`Input`/`Label`/`Badge`/`Spinner`/`Alert`、`@paideia/core` 的 `ApiClient` 与 `session`、`@paideia/knowledge` 的条目渲染（不复制 MarkdownBody，壳只渲染卡片）。
- **新增**：`paideia-ai` 模块、两张表、一个前端包、`KnowledgeApi` 的第一个方法与其实现。

## 测试策略

- **纯函数单测（内环，秒级）**：`UpstreamGuard`（回环／私网／元数据／ULA／IPv4 映射／非 https／重定向拒绝）、`CredentialCipher`（往返、篡改检测、主密钥缺失即失败）、`SummarySchemaValidator`（缺字段、空内容、超长）、`SummaryFingerprint`（正文改动即换哈希）。
- **集成测试（真实 MySQL + MockMvc）**：凭据 CRUD 与"接口只回 last4、日志无明文"、生成（**端口用桩计数**，证明命中缓存不再调用）、可见性矩阵（自己／他人公开／他人私有）、正文改动后失效、无凭据"可读不可生成"、限流 42900、各错误映射、并发唯一键兜底。
- **测试一律不真连上游**：端口在测试里被桩替换；e2e 也不调真实模型（没有网络、也没有用户 Key）。真实调用只在人工冒烟时发生。
- **回归**：`mvn -B verify`、`pnpm -r typecheck`／`pnpm -r test`、三端 e2e、四项边界检查（`workspace-boundaries`／`tokens`／`source-coverage`／`contrast`）、桌面产物仍不含管理端。

## 已知残余风险

- **DNS rebinding 未根治**（理由见 SSRF 节）：校验与连接之间仍有解析窗口。
- **孤儿行**：删条目/删用户后 `ai_entry_summaries` 留下不可达的行，无定时清理（与孤儿附件同类）。
- **主密钥轮换即全体重填**；主密钥泄露等于全部凭据泄露。
- **AgentScope 2.0.0 很年轻**（GA 三个月、891 未关 issue）：只用最小面 + 薄端口隔离，升级风险可控但不能免除。
- **限流是单实例内存态**：重启清零，多实例失效。
