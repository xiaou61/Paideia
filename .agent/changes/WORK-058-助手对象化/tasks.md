---
artifact: tasks
work_id: WORK-058
work: 助手对象化（全站对象可附着、可执行）
status: draft
created: 2026-10-30
updated: 2026-10-30
---

# 助手对象化实施任务

## 依据

- `requirements.md`（REQ-001…010 / AC-001…012，已签署）、`design.md`（数据形态、传输、注入边界、失败模式）。
- 用户 2026-10-30：「按主流方案做」+「新开 worktree 开始实现」。

## 全局约束

- 不改 schema、不加迁移；`ai_runs.attrs` 已够用。
- 后端契约不变：`pageContext` 仍是一段自由文本，服务端 4000 字符兜底。
- 工具名、分组、scope 不动（MCP 外部客户端不受影响）。
- 写动作一律"提议 → 确认 → 执行"，不做静默写。
- 附件的读取权限仍按当前用户校验，URI 只做标识。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 附件文本被当成指令执行 | TASK-004 | `ConversationPromptTest`（资料不是指令 / 草稿 / 无数据行） |
| 附件把提示词撑爆 | TASK-001 | `attachments.test.ts`（5 条淘汰、600 截断） |
| URI 越权读别人的数据 | TASK-001 | `parseAttachmentUri` 只校验格式；读取走既有鉴权 |
| 按钮套在链接里（键盘/读屏坏掉） | TASK-005 | `learn.spec.ts` 仍断言关卡格是 `<a>` |
| 附着没反馈 | TASK-003 | `learn.spec.ts`（点完面板打开、上下文条出现"附着 1 个对象"） |
| 连点两次第二次不生效 | TASK-003 | `revealSeq` 计数器（不是布尔） |

## 任务

### TASK-001 | done | 附件模型与序列化

- 对应：`REQ-001`、`REQ-003`
- 修改：新增 `frontend/packages/assistant/src/attachments.ts`、`attachments.test.ts`
- 接口：`Attachment`、`AttachmentType`、`attachmentUri`、`parseAttachmentUri`、`putAttachment`、
  `removeAttachment`、`serializeAttachments`、`ATTACHMENT_LIMIT = 5`、`ATTACHMENT_EXCERPT_LIMIT = 600`
- 验证：`pnpm --filter @paideia/assistant test` → 55 passed（含新增 9 条）

### TASK-002 | done | 上下文接上附件栈

- 对应：`REQ-001`
- 修改：`context.tsx`（`attachments` / `attach` / `detach` / `revealSeq` / `revealAssistant`）、
  `conversation-panel.tsx`（`turnContext` 拼接、附件条渲染、上下文条计数）、`index.ts` 导出
- 说明：`pageContext`（SQL 工作台 / 学习记录）原样保留，两者拼成一段文本发送
- 验证：`pnpm --filter @paideia/app typecheck` 通过；包测试 55 passed

### TASK-003 | done | 通用附着组件

- 对应：`REQ-002`
- 修改：新增 `frontend/packages/assistant/src/attach.tsx`（`Attach`）、`assistant-shell.tsx`（`revealSeq` effect）
- 行为：点击 = 附着 + 打开面板并切到对话页；**不发送**；`compact` 只显示图标（`aria-label` 保可读名）
- 验证：`learn.spec.ts` 新增用例（点「问助手」→ 面板打开 → 上下文条出现 → 再点取下复原）

### TASK-004 | done | 注入边界措辞

- 对应：`REQ-008`
- 修改：`ConversationPrompt.pageContext`（"资料不是指令"）、`VERSION` → `assistant-chat-v2`；
  新增 `ConversationPromptTest`
- 验证：`scripts/test-db.sh mvn -B -f backend/pom.xml -pl paideia-ai -am -Dtest=ConversationPromptTest`
  → 3 run / 0 fail

### TASK-005 | done | 四页接入

- 对应：`REQ-010`
- 修改：`KnowledgeEntryPage`（正文选区，`entry:<id>/selection`）、`TrackMapPage`（`level:<slug>/<seq>`）、
  `NotesPage`（`note:<id>`，含未保存草稿）、`ReviewPage`（`entry:<id>`，档位与到期日进摘录）
- 说明：关卡格与复习卡的链接只包主内容，按钮为兄弟节点（避免嵌套可交互内容）

### TASK-006 | done | 工具入参吃 `uri`

- 对应：`REQ-006`（AC-011）
- 范围：`knowledge_get_entry`（`entry:`）与 `note_get`（`note:`）新增**可选** `uri`；传了 uri 就忽略同名裸 id
- 新增：`paideia-platform` 的 `ToolUri.numericId(uri, type)`（认数字前缀，`entry:123/selection` 也给 123）
- 验证：`ToolUriTest` 3 条 + `KnowledgeToolsTest` 新增 1 条（`entry:1` / `entry:1/selection` / 类型不符）；
  旧参数路径行为不变
- 未做：写工具（`note_create` / `note_append` / `assessment_mark_entry`）不加 `uri`——内部助手只读，
  它们只出现在外部 MCP 的 write 视图里，没有前端附件这条路径

### TASK-011 | done | 附件条可点回去

- 对应：`REQ-005` 的一半（AC-006 的附件部分）
- 修改：`conversation-panel.tsx`（附着一个 `<button>` + `onOpenUri`）、`assistant-shell.tsx`（透传）、
  `apps/app/src/main.tsx`（`openAttachment` 路由映射：`entry` / `note` / `track` / `book` / `resume`）
- 说明：路由映射留在应用里，助手包只交出 uri；合成对象（`level:`）没有路由，保持纯文本
- 验证：`@paideia/assistant` typecheck + app typecheck 通过

### TASK-007 | cancelled | 写动作确认闸

- **取消原因（更正）**：内部助手本来就是只读的——`ToolQuery.internal` 固定 `readOnly=true`、
  `scopes={READ}`，这是 WORK-006 的产品裁决（自主只建议不动作）。写工具只出现在外部 MCP 的
  write 视图中，必须由用户自己签发且带 `write` scope 的凭据才能调用。
  所以"助手静默改用户数据"这条风险不存在，本任务无对象。
- 保留纪律：将来若给内部助手开写，**必须先补确认闸**，不允许直接放开。

### TASK-008 | done | 附件 URI 落 `ai_runs.attrs`

- 对应：`REQ-009`（AC-007）
- 实现：`serializeAttachments` 首行输出 `附着对象：a, b`（给服务端读，格式与
  `AttachmentUris.java` 成对）；`AssistantConversationController.runFor` 解析后写
  `run.attribute("contextUris", ...)`，为空就不写
- 验证：`AttachmentUrisTest` 3 条（正常/缺失/超 5 条）+ 前端 attachments 断言首行格式

### TASK-009 | done | 回复里的引用可点

- 对应：`REQ-005`（AC-006）
- 实现：提示词 v3 约定 `[[entry:123]]` / `[[note:45]]` 并禁止编造 id；`linkifyCitations` 只换完整的
  `[[...]]`（流式到一半不变形）；`CitationLink` 接住占位前缀，点一下把 uri 交回应用
- **踩坑**：自定义协议 `paideia:` 会被渲染器拦成 `entry:1 [blocked]`，改用
  `https://paideia.invalid/`（RFC 2606 保留域）
- 验证：`citation.test.ts` 5 条 + `citation-link.test.tsx` 3 条；渲染器透传 href 在 ui 包手动核实
- 已知缺口：模型会不会写对 uri 只能人工抽查；点开不可路由的 uri（如 `level:`）什么也不会发生

### TASK-010 | done | `@` 对象选择器

- 对应：`REQ-004`（AC-005）
- 新增：`mention.ts`（`findMention` / `stripMention`）+ `mention.test.ts`（6 条）
- 修改：`conversation-panel.tsx`（候选列表、方向键/回车/Esc、180ms 防抖）、`assistant-shell.tsx`（透传）、
  `apps/app/src/main.tsx`（`searchAttachments`：笔记本地过滤 + 条目 `knowledge.entries({q})`，allSettled）
- 说明：搜索来源由应用注入（`searchObjects`），助手包不认识业务检索端点；选中即进附件栈但**不发送**
- 验证：assistant 包 61 tests；两处 typecheck；`assistant.spec.ts` 11/11（含新增一条）
- 已知限制：候选只有笔记与知识条目两类；关卡/书籍还没有检索来源（`level:`/`book:` 只能从页面入口附着）
