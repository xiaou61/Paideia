---
artifact: design
work_id: WORK-011
work: AI 助手面板重做与会话历史
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-005, WORK-006, WORK-009]
created: 2026-10-16
updated: 2026-10-16
approved_by: xiaou61
approved_at: 2026-10-16 11:05:10 +0800
approver_role: CTO
---

# 设计 · AI 助手面板重做与会话历史

## 1. 面板布局与状态

| 项 | 决定 |
| --- | --- |
| 形态 | **浮动对话框**：`fixed`，位置与尺寸都来自本地状态。**不再是通高侧栏**（2026-10-16 用户看过停靠版后改向） |
| 位置 | 默认右下角（距边 24px）；**拖标题栏可移到任意位置**；始终夹在可视区内——至少留 16px 边缘可见，窗口缩小时也会自动夹回来，避免被拖到屏幕外再也点不到 |
| 尺寸 | 默认 **384×560**；范围宽 320–720、高 360–900；拖**右下角握把**改，**键盘左右箭头**也能改宽 |
| 收起 | 收起后是一个**显眼的**悬浮药丸按钮（图标 + 「AI 助手」）。**不能用小圆点或细窄条**——用户明确反馈过那种"不太明显" |
| 持久化 | `localStorage` 键 `paideia:assistant-panel`，值 `{open, x, y, w, h}`；**刷新保持** |
| 不自动弹开 | 收起状态在导航间保持不变（外壳是常驻组件，状态本来就不会被导航重置；**明确不要**在路由变化时 `open=true`） |
| 键盘 | `Ctrl/Cmd + J` 开关（键位属实现选择） |
| 不挤压正文 | 浮动即覆盖，**没有"内容让位"这回事**。所以不设任何根 CSS 变量，`AppShell` 也不为它留 padding（停靠版的那一处改动已撤回） |

**为什么用 CSS 变量而不是把宽度传给 AppShell**：外壳在各端复用，改它的 props 会牵动所有调用点；一个根变量是**单点注入**，关掉面板时置 0 即恢复。

## 2. 会话：恢复、新开、切换

**现状（要修的病）**：`conversationId` 是组件状态，面板收起/换条目即归零。

**新规则**：

```
本地键 paideia:assistant-conversation 的三个状态要分清：
  键不存在        → 从没选过：恢复"最近的会话"（有当前条目时优先该条目绑定的那条）
  值为空串 ''     → 刚刚点了「新开对话」：面板保持空白，**不要**再去恢复旧的
  值为 '<id>'     → 恢复这一条
```

- **恢复**：面板展开时（且仅在展开时）读一次 → `GET /api/v1/ai/conversations` 拿列表 → 按上面的规则决定读哪条 → `GET /api/v1/ai/conversations/{id}/messages` 渲染历史。
- **新开**：清空消息与 `conversationId`，并把本地键写成空串。**不立刻建会话**——建会话推迟到第一次发送（避免留下只有开场白的空会话）。
- **切换**：列表里点一条 → 设 `conversationId` 并写本地键 → 拉那条的消息。
- **换条目**：有条目绑定的会话就切过去；没有则**保持当前会话不动**（而不是静默清空——那是现状最让人恼的地方）。只有点「新开」才清。
- 列表放**面板顶部的下拉**（REQ 待确认问题 1 的建议）：面板窄，独立一屏会牺牲对话区。每项显示"时间 + 所属条目标题（没有则不显示）"。

## 3. 后端：读会话消息端点

```
GET /api/v1/ai/conversations/{id}/messages
```

- 归属校验走既有 `ConversationMapper.findActiveByIdAndUser(id, userId, now)`——**找不到就抛既有的"会话不存在或已过期"**（与其他会话端点同一条错误，不区分"不存在/不是你的/已过期"）。
- 读消息复用既有 `ConversationMessageMapper.selectRecentNewestFirst(conversationId, limit)`，`limit` 取 `ConversationProperties.maxStoredMessages`（200）——**不加新 SQL**，也不无限拉。
- 返回**旧 → 新**（与进模型的顺序一致），每条含 `role` / `content` / `usage`（解析既有的 `token_usage` JSON，可能为 null）/ `createdAt`。
- **不改表**：`ai_conversations` / `ai_messages` 已够用。

## 4. 前端客户端

`packages/assistant/src/client.ts` 补两个方法（与既有风格一致，只包路径与请求体）：

- `conversations(): Promise<ConversationView[]>` → `GET /api/v1/ai/conversations`
- `messages(conversationId): Promise<ChatMessage[]>` → `GET .../{id}/messages`

`ChatMessage` 需要扩一个可选的 `createdAt`（历史消息要有时间），并新增一个 `ConversationSummary`（含 `entryTitle` 便于列表显示——**若后端列表不返回标题，则前端用 `entryId` 从知识库侧查**；一期为了简单，列表项只显示时间 + 条目 id 的存在与否，标题留到有需要时再加）。

## 5. 与既有行为的关系（不许回归）

- **流式中收起面板仍要中断上游**：收起不是卸载（收起后组件仍在树上），所以现有的"卸载即 abort"不再覆盖这条路径——**必须显式在收起时也 abort**，否则一次调用会继续计费到用户看不见的地方。
- 「记住这条」「存进笔记」「积分」三个既有面**不动**。
- 思考过程仍默认折叠（原生 `<details>`）。
- 既有 testid 保持：`assistant-launcher`（窄条按钮沿用）、`assistant-shell`、`assistant-steps-*`、各标签页 id。

## 6. 窄屏降级

- 视口 < 1024px：面板不占位（不设根变量），改为**覆盖式**从右侧滑入、宽度 `min(384px, 100vw - 2rem)`。
- 视口 < 640px：**底部抽屉**（高 70dvh），因为手机上右侧滑入会盖住整个正文。

## 7. e2e 影响

`apps/app/e2e/assistant.spec.ts` 现有 27 条依赖 `assistant-launcher` 与 `assistant-shell`。本项要**保持这两个 testid 的语义**（窄条按钮仍是 `assistant-launcher`；展开后的根仍是 `assistant-shell`），并新增四条：
1. 拖宽/收起后刷新仍保持；
2. 关掉面板再打开，上一次的消息还在；
3. 点「新开对话」后是空白，且旧会话能在列表里切回；
4. 用他人会话 id 读消息得 404。

## 8. 实现选择（记录理由）

| 选择 | 理由 |
| --- | --- |
| 宽度用根 CSS 变量而不是改 `AppShell` 的 props | 外壳被多端复用，改 props 牵动所有调用点；根变量是单点注入，关面板置 0 即恢复 |
| 「新开」不立刻建会话 | 否则会留下只有开场白、没有消息的空会话，且它在列表里和真会话分不开 |
| 换条目**不**清空当前会话 | 现状最恼人的地方就是"什么都没做，对话没了"；要清必须由用户显式点 |
| 读消息复用 `selectRecentNewestFirst` | 已有 SQL 就是"取最近 N 条"，加一个全量查询只会多一条没人维护的 SQL |
| 本地键用"三态"（不存在/空串/id） | 要区分"从没选过"和"刚点了新开"，两者都表现为 `conversationId == null` |

## 9. 已知缺口（登记）

1. **换浏览器/清缓存后**回到"最近一条会话"而不是"上次看的那条"——本地键丢了。
2. 会话列表**不能删除**（沿用 WORK-006：会话靠 7 天滑动过期自然清理）。
3. 面板宽度只在桌面端有意义；窄屏的覆盖式/抽屉宽度固定。
