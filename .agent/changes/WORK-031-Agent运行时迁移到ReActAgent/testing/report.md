---
artifact: test-report
work_id: WORK-031
work: Agent运行时迁移到ReActAgent
status: passed
evidence: required
workflow: full
created: 2026-10-21
updated: 2026-10-21
verified_commit: 5c1d5f8
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:48 +0800
---

# WORK-031 验证报告（三步全部完成）

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25，`scripts/test-db.sh` 独占库（`paideia_run_1021150730_*`） |
| 分支 | `work/WORK-031`，工作树 `.worktrees/WORK-031` |
| 版本 | `agentscope-core:2.0.4`、`agentscope-extensions-model-openai:2.0.4`、`mcp:0.17.2` |
| 上游 | **真实 DeepSeek**（`AgentLiveConversationTest` 两条：纯文本 + 真工具循环，实跑通过） |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 升版本不炸 | `mvn -B -pl paideia-ai -am compile` | 0 | passed | 2.0.4 的 API 与我们现有用法兼容，零 Java 改动即编译通过 |
| AC-001 MCP 版本对齐 | `mvn -B -pl paideia-ai dependency:tree` | 0 | passed | `agentscope-core:2.0.4` 传递 `mcp:0.17.2`，与 `backend/pom.xml` 的 pin 一致 |
| AC-001 MCP 客户端可加载 | 全量里的 `McpStartupSmokeTest` | 0 | passed | 随全量一起通过 |
| AC-001 对话与工具集成测试 | `scripts/test-db.sh mvn -B -T 1C test` | 0 | passed | 12 个模块全部 SUCCESS，累计 545 条测试 |
| AC-002 缓存量可承载 | `mvn -pl paideia-ai test -Dtest=TokenUsageTest` | 0 | passed | `Tests run: 2, Failures: 0, Skipped: 0`；三参构造 cacheReadTokens=0，四参保留给定值 |
| AC-002 缓存链路已改（代码级） | `rg -n "cacheReadTokens" backend/paideia-ai/src/main` | 0 | passed | `toUsage()` 读 `getCachedTokens()`；`step.tokens` 与 9 处 `run.tokens` 全部传真实值。**真实上游数值待部署后补**，见下方边界说明 |
| AC-003 契约只增不改 | `git diff backend/paideia-app/src/test/resources/api-shape.json` | 0 | passed | 仅新增 `data.usage.cacheReadTokens: number` 一处，无删除、无类型变更 |
| AC-004 内环不退化 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test` | 0 | passed | `Tests run: 150, Failures: 0, Errors: 0, Skipped: 3` |
| AC-004 全量不退化 | `scripts/test-db.sh mvn -B -T 1C test` | 0 | passed | 545 条 / 0 失败 / 4 跳过（基线 543+4，增量即本次新增 2 条） |
| AC-004 Skipped 逐条核对 | `rg "Tests run:.*Skipped" full.log` | 0 | passed | `paideia-ai` 3 条 = `LayaContentClassifierLiveTest`（需真实上游）；`paideia-knowledge` 1 条 = `FileStorageContractTest`（需 S3）——均为既有设计，不是"跳过即通过" |
| AC-005 反向验证 | 三参构造改为 `this(in,out,total,total)` 后重跑 | 1 | passed | 按预期红：`expected: 0 but was: 120`；复原后重新转绿 |
| AC-006 可回滚 | `git log --oneline` | 0 | passed | 三笔独立提交：`a1af6ce`（纯版本）、`c001d4d`（缓存修复）、`5c1d5f8`（运行时迁移），可各自 revert |
| AC-007 工具循环仍工作 | `AgentLiveConversationTest#liveToolLoopExecutesTool`（真连 DeepSeek） | 0 | passed | 答案里含工具返回的「Redis」，证明工具真被执行且结果回喂 |
| AC-007 多轮工具调用 | `AgentTurnBehaviorTest#multipleToolRoundsStillAnswer` | 0 | passed | 两轮工具调用后仍正常出答案 |
| AC-008 旁白不重复 | `AgentTurnBehaviorTest#toolRoundNarrationIsNotEmitted` | 0 | passed | 正文严格等于「最终答案」，不含工具轮旁白 |
| AC-009 无 400 | `AgentLiveConversationTest`（真实上游多轮） | 0 | passed | 真实 DeepSeek 上多轮工具调用未触发 `insufficient tool messages` |
| AC-010 思考不混正文 | `AgentTurnBehaviorTest#thinkingStaysOutOfContent` | 0 | passed | 思考走 `onThinking`，正文不含思考文本 |
| AC-011 草稿中途到达 | `AgentTurnBehaviorTest#draftIsDeliveredSeparately` | 0 | passed | 草稿经 `onDraft` 送达，未混进正文 |
| AC-012 逐轮计费 | `ConversationUsageAccumulationTest`（4 条） | 0 | passed | 两轮用量 40/15/55 是各轮之和；绕满轮次也上报 |
| AC-013 `find_tools` 语义 | `AgentTurnBehaviorTest#discoveredToolBecomesVisibleNextRound` | 0 | passed | 发现前看不见、发现后下一轮可见并成功调用 |
| AC-013 反向验证 | 把 schema 收窄改成原样透传后重跑 | 1 | passed | 按预期红：`["secret_tool","find_tools"]` 在发现前就可见 |
| AC-014 采集点定型 | `RoundMiddleware.onModelCall` + `BridgedTool.callAsync` | 0 | passed | 每轮 llm 步 + 每工具一步；`ModelCallInput` 给 messages/tools/options/model |
| AC-015 边界未破 | 全量里的模块边界校验 | 0 | passed | SDK 类型仍收在 `ai` 模块内 |
| AC-016 可回滚 | `git log --oneline` | 0 | passed | 三笔独立提交 |

## 缓存 token 的边界（据实说明）

**`ai_runs.cache_read_tokens > 0` 这一条在本批只做到"代码链路打通 + 单测覆盖"，没有用真实上游调用验证。**

原因：本步没有部署、也没有在隔离库里发起真实 DeepSeek 调用。代码层面已确认三处都改了：
`toUsage()` 读 `getCachedTokens()`、`step.tokens(...)` 与 9 处 `run.tokens(...)` 全部传 `cacheReadTokens()`。
真实链路上的数值需要**部署后跑一次带缓存的真实对话**才能记录，届时补进本报告。

尖刺里已经见过上游真的报 `cached=256`（`MODEL_CALL_END#2 in=446 out=12 cached=256`），所以"上游会给非零值"这个前提是实测成立的。

## 未做

- 前端任何验证（本项无前端改动；API 唯一变化是 `usage` 多一个字段，且是新增）
- 部署与线上验证（未授权）
- **首字延迟的对照测量**：迁移前后各 3 次取中位数这件事没有做——本地跑的是真实上游但未做时间序列对比，
  只能定性说"流式未退化"（`AgentLiveConversationTest` 全链路 13 秒内完成，含两次上游往返）。
  若需要精确对照，要在部署后用同一问题跑新旧两版，属于发版后的工作。
- **八类固定输入的完整对照套件**：只覆盖了其中五类（单轮无工具、单轮带工具、多轮工具、
  思考、草稿）。工具失败、上游 401、超时三类没有专门的对照用例——既有
  `UploadGuardTest`/错误分类测试覆盖了错误映射，但没有在 ReActAgent 路径上重跑。
