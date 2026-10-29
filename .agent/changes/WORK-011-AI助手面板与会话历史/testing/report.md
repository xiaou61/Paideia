---
artifact: test-report
work_id: WORK-011
work: AI 助手面板重做与会话历史
status: passed
evidence: required
verified_commit: 1945ce7
verified_at: 2026-10-18
accepted_by: xiaou61
accepted_at: 2026-10-29 13:17:23 +0800
---

# AI 助手面板重做与会话历史验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 机器 | 本机（Windows / Git Bash），JDK 25、Node 24 |
| 数据库 | **独立验证库** `paideia_verify_work011`（本机 MySQL 8.0.26，从零跑 Flyway）。用独立库的原因见「失败与未验证项」3：`paideia_test` 里 `V953` 的校验和与仓库文件不一致，谁在这个库上跑都会起不来上下文 |
| 前端 | 学习者端 e2e 用本地 vite（5173）+ **从当前工作区重打的**后端 jar；管理端 5174 |
| 上游模型 | 集成测试**从不真打上游**（`AssistantConversation` 是桩）；需要真上游的 4 条按设计跳过 |

## 验证结果

**通过。** 面板是**浮动可拖动的对话框**（拖标题栏移动、拖右下角改尺寸、位置与尺寸都存本地、始终夹回可视区），入口在收起后是一个显眼的按钮；会话能恢复上次那一段、能显式新开、能从历史列表切换；读历史的消息端点归属一律 SQL 校验（他人／不存在／已过期同为 404）；窄视口转覆盖式、正文列宽度不变；`Ctrl/Cmd+J` 开关面板，**收起即卸载并中断在飞的上游流**。

**与已批准计划的偏差（都已在 `tasks.md` 的「任务状态」里就地写明）**：

1. **T-06 的面板形态**：计划写的是「右侧停靠」，实际是**浮动可拖动对话框**——2026-10-16 用户看过停靠版后明确否决（原话「随心所欲地拖动…而不是固定到某个界面」）。因此 **T-07「内容区让位」也一并撤回**（浮动即覆盖，不设 `--assistant-panel-w`），相应的 REQ-001／REQ-004 正文已订正。
2. **计划外补的一条**：`prepare` 去掉 `@Transactional`。原实现里积分预留抛 402 会把已写入的用户消息一起回滚，界面乐观画出的气泡刷新即丢；这也是 AC-004 从 `test.fixme` 恢复成真跑的前提。

## 验收矩阵

> 口径：每个 `AC-*` 都在下面出现，命令/退出码/结果/证据四列不空。**未能通过或只做到单测级别**的，在「失败与未验证项」里逐条写明，不在表里充数。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 可拖动/可调尺寸且持久 | `cd frontend && pnpm --filter @paideia/app test:e2e`（`assistant.spec.ts`） | 0 | passed | 用 `mouse.down/move/up` 真拖标题栏（`assistant-drag`）与右下角握把（`assistant-resize`），断言 `x` 左移 >100px、`width` 变大；`reload` 后位置与尺寸差 <2px |
| AC-002 收起状态持久、不自动弹开 | 同上 | 0 | passed | 点 `assistant-collapse` → `assistant-launcher` 可见且 `assistant-shell` 计数为 0；`reload` 后仍是收起态 |
| AC-003 键盘开关 | 同上 | 0 | passed | `Control+j` 两次分别出现/消失 `assistant-shell` |
| AC-004 恢复上次会话 | 同上 | 0 | passed | 显式新开 → 发一句（被积分闸拦下，但消息已落库）→ 收起（面板卸载）→ 重新打开，`conversation-message-user` 仍含那句话。**原来是 `test.fixme`**，根因（`prepare` 事务回滚）已修，现为真跑 |
| AC-005 显式新开 | 同上 | 0 | passed | 点 `conversation-new` 后用户消息计数为 0；旧会话仍出现在 `conversation-sessions` 列表里 |
| AC-006 列表切换 | 同上 + `conversation-panel.test.tsx` | 0 | passed | e2e 打开 `conversation-history` 看到列表项；单测覆盖切换后消息随之更新 |
| AC-007 越权回归 | `cd backend && mvn -B -pl paideia-app -am test -Dtest=AssistantConversationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` | 0 | passed | 12 条 0 失败；「他人的会话对自己不可见，且与"不存在"返回同一种结果」断言 404；「未登录：建会话、列会话、发消息三条路径一律 401」 |
| AC-008 窄屏降级 | `pnpm --filter @paideia/app test:e2e` | 0 | passed | 窄视口下正文列宽度不因面板改变（覆盖而非占位），且面板留在可视区内 |
| AC-009 既有面不回归 + 收起中断 | e2e（上）+ `pnpm --filter @paideia/assistant test`（`conversation-panel.test.tsx`） | 0 | passed | 学习者端 e2e **60 passed / 2 skipped / 0 failed**（2.4 分钟；含既有助手用例与 WORK-016 的移动 project）；单测「卸载即中断在飞的上游调用」**反向验证过**：摘掉那个 `useEffect` cleanup 即红 |
| 后端全量不回归 | `source backend/config/remote-db.local.env && cd backend && mvn -B test` | 0 | passed | BUILD SUCCESS：**143 运行 / 0 失败 / 4 跳过**（跳过的是 `AssistantLiveUpstreamIntegrationTest`，它要真上游） |
| 前端类型与单测 | `cd frontend && pnpm typecheck` / `pnpm --filter @paideia/assistant test` | 0 | passed | typecheck 全绿；assistant 包 **25 passed**（5 文件：conversation-panel / memory-panel / session-store / sse / summary-card） |
| 管理端不回归 | `pnpm --filter @paideia/admin test:e2e` | 0 | passed | **19 passed / 0 failed**（46.2 秒；含 WORK-016 的窄屏用例） |

## 反向验证（"这条要是坏了，哪个断言会红"）

| 被验行为 | 反着改一处 | 结果 |
| --- | --- | --- |
| 收起必须中断上游（否则继续计费） | 删掉 `useEffect(() => () => abortRef.current?.abort(), [])` | `conversation-panel.test.tsx` 里那条立刻红（当初就是这么验的） |
| 恢复上次会话依赖"用户消息先落库" | 给 `prepare` 加回 `@Transactional` | 积分闸那一轮的用户消息会被回滚，AC-004 的 e2e 红（这正是它当初被登记成 `fixme` 的原因） |
| 面板位置真的存本地 | 把位置写死为默认右下角 | AC-001 的 `reload` 后 `x` 差 <2px 那条红 |
| 越权读消息不能泄露"存在" | 把"非本人"分流成 403 或另一条消息 | AC-007 的"与不存在返回同一种结果"红 |

## 失败与未验证项

1. **本报告是补记的**（2026-10-18）：WORK-011 的代码在 10-16 就写完了，但当时只更新了 `.agent/history/updates.md`，没建 `testing/report.md`、`tasks.md` 也没有状态标记 —— 于是状态机一直认为它停在「开发实现 · 可以开始」，还连带把 WORK-012 的硬依赖卡成未完成。本轮补上任务状态与这份报告，**没有改任何功能代码**（除下述两条测试自身的写法修复）。
2. **两条测试的写法有错，本轮修正**（都不是产品缺陷，但都会让人误判成"功能坏了"）：
   - `KnowledgeReadIntegrationTest.likeWildcardsInQueryAreTreatedLiterally` 把 `%25` 直接写进 URL：MockMvc 不走容器 URI 解码，于是到了服务端是字面三个字符 `%25`，长度 ≥2 走了**全文检索**分支，测的根本不是 LIKE 转义。已改为 `.param("q", "%")`。
   - `AssistantToolsIntegrationTest.searchSaysSoWhenNothingFound` 用「绝不可能存在的词…」当"查不到"的关键词：**ngram 自然语言模式下任一 bigram 命中即算命中**，而 React 那篇正文含"可能"，于是它命中了。已改为不与任何语料共享 bigram 的生僻字串，并注明原因。
3. **`paideia_test` 库与种子文件已经漂移**：`V953__seed_source_reading.sql` 在 10-18 08:43 被改过（WORK-015 加了一个可运行练习），而那个库在 09:15 应用的仍是旧内容 → Flyway 校验和失配，**任何测试类在该库上都起不来上下文**。本轮改用独立库 `paideia_verify_work011` 验证。修复办法是重建 `paideia_test`（或 `flyway repair` 后重放 V953），**这属于共用测试环境的清理，我没有擅自做**。
4. **ngram 检索的"任一 bigram 命中"是产品行为，不是缺陷但值得记**：搜「不存在的长短语」可能因为共享一个双字词而命中无关条目。若将来用户抱怨"搜出来的东西不相关"，该动的是检索模式（改成 BOOLEAN 短语或加评分阈值），那是一个独立工作项。
5. **并发跑前端单测时 `packages/ui` 的 markdown 套件偶发 transform 失败**（73 条被跳过）；单独复跑 148/148 通过。判断为并发资源问题，已如实记下。

## 剩余风险

- **面板形态被用户改过一次**：停靠版已被否决，本报告验的是浮动版。若将来又要"把正文挤开而不是盖住"，要回到 REQ-004 重选形态。
- **会话有 7 天滑动 TTL**：恢复上次会话只在 TTL 内成立，过期即视为不存在（这是设计，不是缺陷）。
- **`paideia_test` 的漂移会继续复发**：只要有会话改了 `devdata` 里已应用过的种子文件，请记得同步重建测试库；否则下一个跑测试的人会看到一堆"上下文起不来"。

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
