---
artifact: test-report
work_id: WORK-013
work: AI 记忆的自动提取与控制面
status: passed
evidence: required
verified_commit: 1945ce7
verified_at: 2026-10-17
accepted_by: xiaou61
accepted_at: 2026-10-29 13:17:36 +0800
---

# AI 记忆的自动提取与控制面验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 机器 | 本机（Windows / Git Bash），JDK 25、Maven、Node 24 |
| 被验提交 | `70e1b7f`（实现）；其后只有 `.agent/` 工件与本报告 |
| 数据库 | 服务器 MySQL 8.0.46，**公网直连 `36.212.5.130:3306`**（2026-10-16 起，见下）；账号 `paideia_test`，只授权 `paideia_test.*` |
| 跑测试的前置 | `source backend/config/remote-db.local.env`（口令只在被忽略的文件里）。**不再需要 SSH 隧道** |
| 上游模型 | 集成测试**从不真打上游**：`MemoryExtractor` 是 `@MockitoBean` 桩；真上游只被 `AssistantLiveUpstreamIntegrationTest` 用到（未配置时按设计跳过） |

## 验证结果

**通过。** 记忆现在会**自己长**：同一会话攒够 6 轮，回答发给用户之后异步整理一次；写进去的每条都带来源（手动／自动）、理由与出自哪段对话；同一件事说第二次是**覆盖**而不是新增；三个开关各自成立（关掉不召回、不整理、手动写入也被明确拒绝）；「清空并关闭」与「只关不删」是两个动作。

三处机制值得单独记一句：

1. **单飞靠一条条件 UPDATE**（`... AND last_memory_extract_message_id <=> ?`），抢到即推进游标 ⇒ 不需要分布式锁，也天然不会重复整理。**反向验证过**：把 `<=>` 改成 `=`，12 条集成测试红 9 条——因为游标第一次是 NULL，而 `= NULL` 永远不成立。
2. **"空内容"按故障处理**：DeepSeek 会把 `max_tokens` 吃在思考上并**静默返回空**，所以解析器把空/非法 JSON 一律判失败、**整次放弃**（一个事务全有或全无），不当成"这几轮没什么可记的"。
3. **提取花的是用户的积分**：走同一套预扣/结算，流水单独记 `MEMORY_EXTRACT`；余额不足**一个上游请求都不发**（有专门用例），失败也只记日志、绝不影响那一轮回答。

**顺带做了用户要求的一件事**：数据库从 SSH 隧道改成公网直连。**只新建了一个 `paideia_test@'%'` 且只授权 `paideia_test.*`；生产库 `paideia` 没有任何可远程登录的账号**（原先只有 `@127.0.0.1`/`@localhost`，一个都没改）——先查清这一点才动的 `bind-address`。风险与回滚写在 `.agent/notes/dev-workflow.md`。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 自动提取真的发生 | `source backend/config/remote-db.local.env && cd backend && mvn -B -pl paideia-app -am test -Dtest=MemoryAutoExtractionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` | 0 | passed | `extractionWritesMemoryWithProvenance`：攒 6 轮 + 触发 → 写入一条，`origin=auto`、`reason` 与 `conversationId` 都在 |
| AC-002 幂等（不膨胀） | 同上 | 0 | passed | `updatingDoesNotDuplicate`：两次同一件事，第二次 `UPDATE` → `ai_memories_text` 仍是 **1 行**，内容被覆盖成最新说法 |
| AC-003 纠错可追溯 | 同上 | 0 | passed | `deleteActionRemovesTheRow`：判定"已不成立"→ 行真的被删；`MemoryCandidateParserTest` 覆盖 `DELETE` 解析（缺 `targetId` 判失败）；理由落库、面板可展开看到 |
| AC-004 失败与欠费都不影响本轮 | 同上 | 0 | passed | `extractorFailureIsSwallowed`（模型抛错 → 不写记忆、游标推进不再重跑）+ `insufficientCreditsSkipsExtraction`（余额 0 → `verify(extractor, times(0))`，一个请求都不发） |
| AC-005 总开关真的关掉 | 同上 | 0 | passed | `disabledMemoryStopsExtractionAndManualWrite`：关闸后不整理（0 次调用）；`POST /memories` → **409 + 业务码 40910**；库 0 行 |
| AC-006 两个轴独立 | 同上 | 0 | passed | `historyAxisIsIndependent`：关掉「参考当前对话的历史消息」后，桩收到的 `history()` 为空 |
| AC-007 清空并关闭 ≠ 只关不删 | 同上 | 0 | passed | `clearingIsSeparateFromDisabling`：只关不删 → 2 条仍在；`DELETE /api/v1/ai/memories` → `cleared:2` + `memoryEnabled:false` + 列表空 |
| AC-008 来源可见 + 分页 | 同上 | 0 | passed | `listSupportsPagingAndProvenance`：`limit/offset` 取到不同页；响应含 `origin`/`reason`，`manual` 与 `auto` 分得开 |
| AC-009 不占用户时间 | 同上 | 0 | passed | `extractionDoesNotBlockTheAnswer`：桩**卡在模型调用里**，SSE 回答仍完整返回（含 `done`），整理在另一个线程里等 |
| AC-010 账对得上 | 同上 | 0 | passed | `extractionIsRecordedInLedger`：流水有 `MEMORY_EXTRACT` 一行且 `input_tokens` 为 1000 |
| T-06 并发只跑一次 | 同上 | 0 | passed | `concurrentTriggersRunOnce`：连续两次 `scheduleIfDue` → `extractor` 只被调用 1 次 |
| 后端全量不回归 | `source ... && cd backend && mvn -B test` | 0 | passed | BUILD SUCCESS；`paideia-app` **130 运行 / 0 失败 / 4 跳过**（跳过的是 `AssistantLiveUpstreamIntegrationTest`，它要真上游）；`paideia-ai` 43 条（含新增 `MemoryCandidateParserTest` 11 条） |
| 注销级联含新表 | 同上（`AccountDeletionCascadeIntegrationTest`） | 0 | passed | 用例逐表断言行数为 0；清单与夹具都加了 `ai_user_settings`（不加这条用例就红） |
| e2e：学习者端 | `source backend/config/remote-db.local.env && cd frontend && pnpm --filter @paideia/app test:e2e` | 0 | passed | **33 passed**（2.1 分钟），全程无隧道 |
| e2e：管理端 | `... pnpm --filter @paideia/admin test:e2e` | 0 | passed | **8 passed**（32 秒） |
| 前端类型与单测 | `cd frontend && pnpm typecheck` / `pnpm test` | 0 | passed | typecheck 全绿；单测 ui 93、assistant 23、knowledge 20、core 22、auth 13、platform-desktop 4 |
| 数据库公网直连可用 | 本机 socket 连 `36.212.5.130:3306`；再跑上面那组集成测试（**隧道已停**） | 0 | passed | 拿到 `8.0.46-0ubuntu0` 握手；集成测试 12/12 通过；线上服务健康检查仍 UP |

## 反向验证（"这条要是坏了，哪个断言会红"）

| 被验行为 | 反着改一处 | 结果 |
| --- | --- | --- |
| 单飞的 `<=>`（游标首次为 NULL） | `last_memory_extract_message_id <=> #{expectedCursor}` 改成 `=` | **12 条集成测试红 9 条**，报"等记忆写入超时"——`= NULL` 永远不成立，第一次整理永远抢不到。改回后 12/12 绿 |
| 解析器的"失败即放弃" | 单测里直接喂空串 / 非 JSON / 缺 `targetId` / id 非正整数 | 每一条都抛业务异常（`MemoryCandidateParserTest` 11 条覆盖）；若改成"尽力而为"，写半条记忆的路径就没了守卫 |
| 开关的短路真的生效 | 关掉总开关后再触发 | `verify(extractor, times(0))` 与 409/40910 两条断言；短路失效必红 |
| 清的确实是库不是内存 | `DELETE` 之后按 SQL 数行 | `SELECT COUNT(*) FROM ai_memories_text` 为 0（不是只看接口返回） |

## 失败与未验证项

1. **前端单测有一次"与 e2e 并发跑"时失败**：`pnpm test` 与 e2e 同时跑时，`packages/ui` 的 `markdown.test.tsx` transform 失败、73 条被跳过（其余 20 条通过）。**单独复跑 93/93 通过**（6 文件）。判断为并发资源问题，不是代码问题；已如实记下，未改测试。
2. **提炼质量没有断言**：模型"该记的没记、记了一堆废话"这类问题，本报告给不出证据——它由提示词与真实使用决定（`memory-extract-v1`）。桩只能证明"我们这边"的行为正确。**这一条需要真实使用几轮之后看**。
3. **`kind` 白名单的边界只验了"越界落全文路"**：`weak_topic`/`goal`/`preference` 谁是"事实"谁是"偏好"由模型判断，没有用例（也不该有——那是提示词的活）。
4. **面板的"来源筛选"只过了前端单测**（"已加载页内筛选"），没有 e2e；`assistant.spec.ts` 里与记忆面板相关的断言（"可逐条删除"、空态文案）都保留并通过。
5. **删除的"动作历史"没做**：`DELETE` 的理由只进日志，库里不留痕（那一行已经没了）。用户看到的是"这条不见了"，看不到"什么时候因为什么被删"。设计里就是这么定的（非目标），记在这里以免被当成缺陷。

## 剩余风险

- **公网 3306 是长期风险面**：扫描器会持续戳它。当前能连的只有那个只碰得到 `paideia_test` 的账号，但"测试库被写坏"是可能的。回滚/收窄办法写在 `.agent/notes/dev-workflow.md`。
- **失败不重跑**：抢到游标即推进 ⇒ 一次提取失败，那一段就不再整理（设计里登记的取舍：重跑要引入重试状态机与"失败要不要计费"一串问题）。若实测"记忆老是漏"，第一个该改的是这里。
- **判重只看最近 40 条**：更早的记忆不参与判重，可能出现跨时间重复。`ponytail:` 天花板已标在代码里。
- **提示词成本**：默认 N=6（摊到每轮 +约 13%）。若用户嫌"整理太频繁花钱"，把 `paideia.ai.memory.rounds-per-extraction` 调大即可；调成 0/负数 = 关掉自动整理（留的后门，不需要改代码）。
- **与 WORK-010 的边界**：本项只做"对话里的自动提取"；"自评 → 结构化记忆"那座桥仍属 WORK-010，谁后实施谁负责对齐覆盖式更新的语义。

## 2026-10-17 重新核对（结算）

`MemoryAutoExtractionIntegrationTest` 12/0、`MemoryCandidateParserTest` 11/0、`ConversationWindowTest` 5/0、`ConversationUsageAccumulationTest` 4/0；`@paideia/assistant` 24/0（含记忆面板 7 条）。锚点之后只有测试文件变化（`assistant.spec.ts`、`knowledge.spec.ts`、新增 `conversation-panel.test.tsx`），记忆提取与控制面的实现未被改动。

## 2026-10-17 积分闸回滚修复后的复核

本轮改动落在 `ConversationService.prepare`：去掉 `@Transactional`，让用户消息先提交、再做积分预留，并把所有可能抛异常的读操作移到 `reserve` 之前（保证「预留之后不再有会抛的动作」）。另外 `AiCreditIntegrationTest` 原本断言「被拒的那一轮不留任何消息」，已改为「留用户消息、不留助手消息」——AC-003 只管上游没有收到请求，不要求丢用户输入。

本项相关证据随后重跑：`MemoryAutoExtractionIntegrationTest` 12/0、`MemoryCandidateParserTest` 11/0、`@paideia/assistant` 24/0。

锚点改绑到 `e941bcb`。

## 2026-10-17 测试基建改动后的复核

本轮改动是测试基建：测试库改本机、四个前端包换 happy-dom、会话集成测试改用同步执行器。
业务代码未变。重跑结果：后端全量 **257 条 / 0 失败 / 0 error / 4 跳过**（墙钟 1 分 31 秒），
前端单测 **176 条全过**，`typecheck` 干净。

锚点改绑到 `d184e36`（其后只有 `.agent/` 工件变化）。

## 复验记录（2026-10-18，锚点 9c99033）

**为什么会有这一段**：原报告的证据是在更早的提交上采的，之后仓库里又落了 WORK-014／015／016 三个工作项
（源码与配置都变过），校验器因此把本报告判为 `stale`。本轮在**当前 HEAD**（`9c99033`）上重跑了与本项
**受影响面**相关的那几组检查，逐条结果如下。**没有改任何功能代码**。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 记忆整理全量 | `mvn -B test`（`MemoryAutoExtractionIntegrationTest` / `LearnerMemoryIntegrationTest`） | 0 | passed | 后端全量 143 运行 / 0 失败 / 4 跳过；前者 12 条覆盖 AC-001…010 + 单飞 + 欠费跳过 |
| 记忆面板 | `pnpm --filter @paideia/assistant test` | 0 | passed | 25 passed（含 memory-panel 7 条） |
| 前端类型 | `pnpm typecheck` | 0 | passed | 退出码 0 |

**这段区间里本项的受影响面**：**本项自己的代码在锚点之后被另一个会话修过两处**（结构化 UPDATE 缺 targetId 会 NPE、两路分页会跳行），均未提交时就已修好；本轮验证把它们算了进来。

**结论**：本项的 12 条集成用例在当前 HEAD 上全绿。**报告里原先那四条未验证项仍然成立**（提炼质量无断言、DELETE 不留动作历史、面板筛选无 e2e、失败不重跑），没有被这次复验消掉。

### 追加复验（1945ce7，2026-10-18）

> 上面那次复验的锚点是 `9c99033`；之后合并了 `d36a304`（第四次审查的 17 条 high 修复，**其中改了
> `assistant-shell.tsx` / `conversation-panel.tsx` 的会话切换与恢复逻辑、以及记忆的 UPDATE 落库**）。
> 那些文件正是 WORK-011 与 WORK-013 的验收对象，所以**在原锚点上采到的证据对新代码不再成立**，
> 本轮在 `1945ce7` 上整组重跑（独立验证库 `paideia_verify_work011` 已重建，从零跑 Flyway）：

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 后端全量 | `source backend/config/remote-db.local.env && cd backend && mvn -B test` | 0 | passed | **143 运行 / 0 失败 / 4 跳过**（跳过的是要真上游的 `AssistantLiveUpstreamIntegrationTest`） |
| 学习者端 e2e | `pnpm --filter @paideia/app test:e2e` | 0 | passed | **60 passed / 2 skipped / 0 failed**（1.7 分钟） |
| 管理端 e2e | `pnpm --filter @paideia/admin test:e2e` | 0 | passed | **19 passed / 0 failed**（35.4 秒） |
| 前端单测 + 类型 | `pnpm test` / `pnpm typecheck` | 0 | passed | 单测 **240** 条全过（ui 148 / knowledge 28 / assistant 25 / core 22 / auth 13 / desktop 4）；typecheck 退出码 0 |

**结论**：会话切换/恢复逻辑在改动之后仍然通过 AC-004/AC-005/AC-006 的端到端断言（e2e 里那几条正是
点历史、点新开、收起再打开）；记忆的 UPDATE 落库在 `MemoryAutoExtractionIntegrationTest` 的
"同一件事说两次不新增"里仍然成立。
