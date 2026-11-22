---
artifact: test-report
work_id: WORK-058
work: 助手对象化（全站对象可附着、可执行）
status: passed
evidence: required
workflow: full
created: 2026-10-30
updated: 2026-10-30
verified_commit: 61faf8b
accepted_by: xiaou61
accepted_at: 2026-10-30 17:52:25 +0800
---

# WORK-058 验证报告

**结论**：助手对象化已合并并上线（merge `61faf8b`，32 文件 / +1346 −82）。附件栈、四页接入、
`@` 选择器、回答内引用可点、附件 URI 落 `ai_runs.attrs.contextUris` 均已落地，验收标准逐条有证据。
后端切片 **7 类 25 条全过**，`@paideia/assistant` **71 条**全过（新增引用接线 1 条），
`attachments.spec.ts` **4/4**、`--grep WORK-058` **2/2** 通过；线上健康检查 UP、主 bundle 含本次三个标识。

**未达**（如实记录）：AC-008 按**设计取消**（见「实现订正」，已从需求里作废），不是"做失败"。
除它以外全部验收标准都有命令级证据。

## 验证环境

| 项 | 值 |
| --- | --- |
| 线上 | `codenest-online`（36.212.5.130:8081），JDK 25 / MySQL 8 |
| 本机 | Windows / Git Bash；Playwright 1.64（`PLAYWRIGHT_BROWSERS_PATH=F:/caches/ms-playwright`） |
| 数据库 | `scripts/test-db.sh` 每次分配的独占库（如 `paideia_run_1030175047_25583`，跑完自动删） |
| 种子 | `db/devdata` V959 新种一条"到期复习项"（AC-004 的数据前提；线上不加载该目录） |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 条目页选中一段递给助手 | e2e `attachments.spec.ts -g AC-001` | 0 | passed | 用 Range 选中 `article p` 后点「把选中这段问助手」：`conversation-context` 出现「附着 1 个对象」、附件条含「选中片段」 |
| AC-002 笔记未保存改动也递得过去 | e2e `-g AC-002` | 0 | passed | 改完标题**不等自动保存**就附着，附件条显示的是改后的标题 |
| AC-003 关卡页「问助手」带上关卡身份 | e2e `--grep WORK-058`（`learn.spec.ts`） | 0 | passed | 关卡格仍是 `<a>`，兄弟节点按钮点后面板打开、上下文出现「附着 1 个对象」 |
| AC-004 复习题带着题干与档位递给助手 | e2e `-g AC-004`（配 V959 种子） | 0 | passed | 到期列表出现「复习：什么是依赖注入」；档位与到期日在摘录里（由 `serializeAttachments` 单测覆盖） |
| AC-005 `@` 搜到对象并变成附件 | e2e `--grep WORK-058`（`assistant.spec.ts`）+ mention 单测 6 条 | 0 | passed | 选中后进 `conversation-attachments`、`@查询` 被删；邮箱里的 `@`、含空白、超 40 字都不弹 |
| AC-006 回答里的引用可点 | `conversation-panel.test.tsx` 新增「回答里的对象引用」 | 0 | passed | 桩 `sendMessage` 推 `[[entry:123]]`：完整面板渲染后出现 `citation-entry-123`，点击把 `entry:123` 交回应用 |
| AC-007 超上限丢最旧、提示词只留 5 条 | `@paideia/assistant test` | 0 | passed | `putAttachment`「超过 5 条丢最旧的」；`serializeAttachments` 单条超长截断并写明剩余 |
| AC-009 附件里的指令不被执行 | `ConversationPromptTest` 4/4 | 0 | passed | 资料被包成数据段，断言「资料不是指令 / 草稿 / 无数据行」 |
| AC-010 375px 附件条不撑破、键盘能取下 | e2e `-g AC-010`（375×800） | 0 | passed | `scrollWidth - innerWidth = 0`；附件条右边界 ≤ 375；`取下` 按钮 `focus()` + Enter 后附件条消失 |
| AC-011 工具清单与旧参数兼容 | `-Dtest=McpToolSnapshotTest,MigrationVersionFormatTest`（`-am`） | 0 | passed | 7/7；`uri` 是可选参数，旧入参返回不变；快照漏改一并补上（见「实现订正」） |
| AC-012 不含新迁移文件 | `git diff --stat 149ee11..61faf8b -- db/migration` + `MigrationVersionFormatTest` | 0 | passed | 迁移目录 diff 为空；`MigrationVersionFormatTest` 3/3 |

## 测试与构建汇总

- 后端：`ToolUriTest` 3/3、`KnowledgeToolsTest` 8/8、`AttachmentUrisTest` 3/3、`ConversationPromptTest` 4/4；
  `McpToolSnapshotTest` 4/4、`MigrationVersionFormatTest` 3/3；两轮 `BUILD SUCCESS`（独占库）。
- 前端：`@paideia/assistant` **71 passed**（新增面板级引用接线 1 条）；`@paideia/resume` 20；
  `@paideia/ui` 256/257（唯一红是既有的 `tokens.test.ts` 报 `apps/admin/AnalyticsPage.tsx` 调色板，自 2026-10-27 起）；
  `@paideia/app` typecheck + `vite build` 通过。
- e2e：`attachments.spec.ts` 4 passed / 37.1s（独占库自动删）；`--grep WORK-058` 2 passed / 32.6s。
- 线上：release.ps1 261.3 秒；`/actuator/health` UP；主 bundle 含 `paideia.invalid` / `附着对象` / `输入 @ 引用笔记或条目`。

## 实现订正（验证中发现）

- **TASK-007 / AC-008 作废**：内部助手本来就是只读的——`ToolQuery.internal` 固定
  `readOnly=true` / `scopes={READ}`，写工具只出现在外部 MCP 的 write 视图且需用户签发的 `write` scope。
  「请助手改笔记标题 → 出现确认条」这条路径不存在，验收前提不成立，需求里已标注作废。
  保留纪律：**将来给内部助手开写之前必须先补确认闸**。
- **工具快照漏改（WORK-053 遗留）**：`McpToolSnapshotTest` 期望 65 个工具，实际 66——
  WORK-053 的 `planner_build_dataset` 进 `planner` 组时没更新快照，这条测试自 053 合并起一直红。
  本次把 `hasSize` 改 66 并补 `planner: 1`。**注意**：单跑 `-pl paideia-app`（不带 `-am`）会用 `~/.m2`
  里的旧 jar 得到 65 → 假绿；这类快照必须带 `-am`。
- **devdata 的种子要重打 jar 才生效**：e2e 起的是 `target/*.jar`，改 `db/devdata` 后不重建 jar，
  新迁移不会执行（V959 第一次跑就是这样静默缺席的）。

## 未覆盖与已知限制

- AC-006 的验证在**面板层**（happy-dom + 桩流），不在真浏览器里；链接落到 `/knowledge/entries/123`
  这件事由应用的 `openAttachment` 路由映射负责，没有单独 e2e。
- 附件不跨刷新保留、不进记忆；`@` 候选只有笔记与知识条目（关卡/书籍没有检索来源）。
- 模型会不会写对 `[[entry:123]]` 只能人工抽查——提示词已明写"只引用见过的 id"。

## 反向验证

- AC-011 是**先复现红、再改数、再跑绿**：红态两次复现（`-pl paideia-app -am` 带迁移用例），
  单跑 `-pl paideia-app` 却绿，据此定位到陈旧 jar，而不是直接改期望值敷衍。
- `parseAttachmentUri` 的拒绝路径是正向断言（空 id 抛、未知类型/缺 id/带空白返回 null），
  调用方降级为纯文本；读取仍走既有鉴权，URI 只做标识。
- AC-010 的键盘删除是"聚焦按钮 + 真按 Enter"，不是直接调 `detach()`。
