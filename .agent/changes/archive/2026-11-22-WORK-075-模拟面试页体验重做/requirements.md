---
artifact: requirements
work_id: WORK-075
work: 模拟面试页体验重做
status: approved
created: 2026-11-06
updated: 2026-11-06
mode: managed
mode_reason: 只改学习者端一个页面（InterviewPage.tsx）与其视觉层，不碰后端、数据库、公共接口与安全面；但改动面较大且需跨会话追踪，故 managed 而非 lite
workflow: compact
depends_on: []
related_to: [WORK-010, WORK-066]
approved_by: xiaou61
approved_at: 2026-11-06 16:20:03 +0800
approver_role: owner
---

# 需求

## 背景

模拟面试页（`/interview`，`frontend/apps/app/src/pages/InterviewPage.tsx`）建于 WORK-010，当时的定位是"能把一场对话跑通就行"，视觉几乎为零：

- 面试官的话是一段裸 `<p>`，候选人的话是一个 `bg-muted` 小方块，没有头像、没有气泡、没有分栏。
- 模型每次的**思考过程**用 `<details>思考过程</details>` 折叠展示在消息上方，既占位置又暴露推理噪音。
- 等待回答时只有一个 `Spinner` 孤零零挂在卡片里，没有"面试官正在思考"的现场感。
- 评分卡是纯文字堆叠（`overall` + 每维度一段字），没有可视化；`strengths`（优点）字段根本没渲染。
- 顶部是一条 `max-w-3xl` 的窄栏，空白多、页脚浮标还会盖住内容。

用户 2026-11-06 的原话：「这个模拟面试界面进行优化……你实在太简约太丑了……思考过程就不要展示出来了吧，这个就是转圈圈的这种是不是更好呢？」并要求参考互联网上同类产品的布局。

## 已确认事实

1. 页面只有 `InterviewPage.tsx` 一个文件，`AssistantApi` 已提供 `createConversation(entryId, 'interview')` / `sendMessage`（SSE，事件类型含 `content` / `thinking` / `error`）/ `reviewInterview` / `interviewEvaluations`。
2. `InterviewEvaluation` 结构：`overall`、`dimensions[{name,score,comment}]`、`strengths[]`、`improvements[]`。**当前 `strengths` 未展示**。
3. e2e `frontend/apps/app/e2e/identity.spec.ts:65-99` 依赖这些 testid 与行为，不能破坏：
   - `interview-start`、`interview-input`、`interview-send`
   - `interview-line-interviewer` 的 `textContent` 必须**只含面试官正文**（失败时长度必须为 0，不能把头像/名字/思考提示算进去）
   - 失败时 `没成功` 提示可见
4. `packages/ui` 现成可用：`AppShell(wide)`、`PageHeader(actions)`、`Card*`、`Button`、`Badge`、`Alert`、`Spinner`、`Separator`；`lucide-react` 已被本页引用。
5. 约束：颜色只能来自 `packages/ui/src/styles/globals.css` 令牌（`tokens.test.ts` 禁止调色板类/十六进制）；文字对比度由 `contrast.test.ts` 保证；`@source` 已登记 `apps/app`，本页新增的 token 类会正常生成。

## 参考（互联网同类产品布局）

- **面多多 / 面试鸭 AI 面试（ai.mianshiya.com）**：沉浸式"面试间"——候选人/面试官头像分区、对话流为主轴、结束给"个性化面试报告"（多维度量化 + 分项点评）。
- **Google Interview Warmup**：一问一答的极简对话流，焦点始终在当前问题上，历史消息弱化。
- **通用 AI Chat UI 范式（Dribbble / Muzli 2026 chat UI）**：左右分栏的**气泡对话流**、发送区固定在底部、AI 回复中显示打字/思考指示器、右侧信息栏放历史与提示。

采纳的要点：① 双栏布局（对话主轴 + 右侧历史/提示）；② 带头像与气泡的对话流；③ 思考过程改为"正在思考…"加载指示器；④ 评分改成维度进度条 + 优缺点分区；⑤ 发送区固定在对话卡片底部。

## 目标

1. 把页面从"裸文本堆叠"改成**有结构的面试间**：对话主轴 + 右侧信息栏。
2. **不再展示思考过程**：`thinking` 事件不再渲染；等待时在面试官气泡位置显示"正在思考…"的加载指示器（转圈）。
3. 评分卡可视化：总分 + 每个维度进度条 + 优点/改进两栏。
4. 不改变任何后端接口、会话语义、积分行为；不破坏既有 testid 与 e2e 断言。

## 非目标

- 不做语音面试、不做简历押题、不做多轮计时/追问策略（那是新功能，不在本次 UI 重做内）。
- 不改 `AssistantApi` / SSE 协议 / 后端 `interview` harness。
- 不新增第三方 UI 依赖（沿用 `@paideia/ui` + `lucide-react`）。

## 功能需求

- REQ-001 对话区：面试官/候选人分左右气泡，各自头像与称呼；内容保留换行。
- REQ-002 思考过程：不渲染 `thinking`；`busy` 且当前面试官消息尚无正文时，显示带转圈动画的"面试官正在思考…"。
- REQ-003 发送区：文本框固定在对话卡片底部，Enter 发送、Shift+Enter 换行；「发送」与「生成点评」按钮可用性同现状。
- REQ-004 右侧栏：放历次面试（可展开看分维度与改进点）与简短使用提示；无历史时不占位。
- REQ-005 评分卡：总分醒目展示；每个维度一条进度条（score/5）；`strengths` 与 `improvements` 分两栏列出。
- REQ-006 起始态：未开始时是一张"开始面试"引导卡（目标岗位、隐私说明、怎么进行三步）。

## 质量要求

- 只用令牌颜色类；不得出现硬编码颜色（`tokens.test.ts` 必须仍绿）。
- 移动端单列、桌面双栏；不产生横向滚动。
- 既有 testid 全部保留且语义不变（尤其 `interview-line-interviewer` 的 textContent 口径）。
- `pnpm --filter @paideia/app typecheck` 与 `pnpm --filter @paideia/ui test` 通过；`identity.spec.ts` 面试用例本地通过。

## 验收标准

- AC-001 打开 `/interview` 未开始时看到引导卡 + 隐私说明（含"你的笔记默认不参与"）。
- AC-002 开始面试后：出现候选人气泡与面试官气泡（左右区分、有头像/称呼）；等待期间显示"正在思考…"转圈指示器。
- AC-003 全程**不出现任何"思考过程"字样或折叠框**（`getByText('思考过程')` 取不到）。
- AC-004 生成点评后：总分 + 维度进度条 + 优点 + 改进点齐全可见。
- AC-005 `identity.spec.ts` 的面试用例通过：失败时 `interview-line-interviewer` 的 textContent 为空、`没成功` 可见；成功时 textContent 非空。
- AC-006 `tokens.test.ts`、`contrast.test.ts`、`pnpm --filter @paideia/app typecheck` 全绿。

## 用户决定

- 2026-11-06：开一个独立 WORK 做这次 UI 重做（不并入旧项）。
- 2026-11-06：**思考过程不展示**，改为加载指示器（用户原话"转圈圈"）。
- 2026-11-06：布局参考互联网同类产品，不要"太简约"。

## 实现选择

- **单文件重写** `InterviewPage.tsx`，不动 `AssistantApi` / SSE / 后端：页面层把 `thinking` 事件丢弃，等 `busy` 时用 `Spinner` 表达"正在思考"。
- **布局**：`AppShell wide` + 内层 `max-w-5xl`；对话页桌面 `lg:grid-cols-[minmax(0,1fr)_18rem]`（左对话主轴、右历史/提示），移动端单列。
- **视觉**：仅用 `@paideia/ui` 现成组件与 `globals.css` 令牌类（`bg-muted`/`bg-primary`/`text-muted-foreground` 等），不引新依赖、不写颜色字面量。
- **评分**：维度用进度条（宽度 = score/5），总分单独放大；`strengths` / `improvements` 两栏。
- **契约**：保留全部既有 `data-testid`，且 `interview-line-interviewer` 只包正文（思考提示与其分离），保证 `identity.spec.ts` 断言不破。

## 待确认问题

- 无（布局方向与"不展示思考过程"已由用户明确；字号/间距按现有设计系统令牌执行）。
