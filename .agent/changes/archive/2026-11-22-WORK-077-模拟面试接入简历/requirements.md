---
artifact: requirements
work_id: WORK-077
work: 模拟面试接入简历（选择 / 上传）
status: approved
created: 2026-11-06
updated: 2026-11-06
mode: managed
mode_reason: 只改学习者端 InterviewPage 与其交互，复用既有简历 API（/api/v1/ai/resumes）与 pageContext 通道，不碰后端/数据库/公共接口；但涉及跨功能集成与用户数据（简历文本）离开本机，需追踪，故 managed
workflow: compact
depends_on: []
related_to: [WORK-010, WORK-018, WORK-075]
approved_by: xiaou61
approved_at: 2026-11-06 16:59:15 +0800
approver_role: owner
---

# 需求

## 背景

WORK-075 刚重做了模拟面试页，但它仍**不读简历**：面试官只按「目标岗位 + 平台知识库」出题。求职者真正的面试是围绕简历里的项目/技术栈追问的，所以开场应先让用户**选一份已有简历，或当场上传一份**，面试官据此提问。

平台已有完整简历能力（WORK-018）：`@paideia/resume` 的 `ResumeApi.list()/get(id)/parse(file)` 与后端 `/api/v1/ai/resumes`，含「默认简历」标记与 PDF/Word 解析（返回 `ResumeDraft{data, plainText}`）。对话侧也有 `sendMessage(..., pageContext)`：一段**只进提示词、不落库**的上下文文本（后端截断到 4000 字）。

## 已确认事实

1. 面试当前不读简历：`InterviewPrompt.opening(targetRole, entryHint)` 只用岗位与知识库条目。
2. `ResumeApi`：`list(): ResumeDocumentSummary[]{id,title,isDefault,revision,updatedAt}`、`get(id): ResumeDocument{data: ResumeData}`、`parse(file): ResumeDraft{data, plainText}`。
3. `sendMessage(conversationId, message, onEvent, signal, pageContext?)`，`pageContext` 由后端 `ConversationService` 截断到 4000 字后进提示词，不落库。
4. 简历文本属于用户私有数据：**默认不带**，只有用户选了/传了才发；不选就按岗位问（与"笔记默认不参与"同一纪律）。

## 用户决定

- 2026-11-06：面试要能基于简历；范围选「**选已有简历 + 本页直接上传**」两种都做。
- 2026-11-06：简历可选（不选则按岗位问）。
- 2026-11-06（追加）：开场要**标注会扣积分并二次确认**；**退出/结束也要二次确认**（退出后本场无法继续、不能重进）；结束要能出报告。

## 实现选择

- **前端单页改动** `InterviewPage.tsx`：起始卡增加「简历（可选）」区——
  - 列出已有简历供点选（默认选中「默认简历」），并有「不使用简历（按岗位问）」。
  - 支持本页上传 PDF/Word：调 `resumeApi.parse(file)`，成功用其 `plainText` 作本次上下文（不强行入库）。
- **上下文投递**：开场面把简历文本作为 `sendMessage` 的 `pageContext` 传入（只进提示词、不落库，不显示在气泡里）。
- **隐私**：界面注明"简历只在你选择后用于本次面试"。
- 不改后端、不加依赖、颜色仍只用令牌。

## 目标

1. 开场能选已有简历（含默认）或不上传；也能当场上传 PDF/Word。
2. 面试官提问能体现简历内容。
3. 简历文本不落库、不显示为候选人发言。

## 非目标

- 不做"简历押题"独立报告、不改点评提示词结构。
- 不把上传的简历自动保存进简历库（本期只用一次；要留存走简历页）。
- 不改后端接口。

## 功能需求

- REQ-001 起始卡展示简历选择：默认简历预选；可选其它；可选"不使用"。
- REQ-002 起始卡支持上传 PDF/Word，解析中显示状态，失败有可读提示。
- REQ-003 开始面试时把所选简历文本作为 `pageContext` 传给首条消息；不选则不发。
- REQ-004 隐私说明：简历仅在你选择后用于本次面试。
- REQ-005 积分提示与二次确认：开场页标注"每轮提问/回答与生成点评都计费"；点开始弹确认框，确认后才开面。
- REQ-006 退出/结束二次确认：提供「结束面试」按钮与站内跳转拦截，提示"结束后无法继续、也不能重新进入"。

## 验收标准

- AC-001 有简历时开场默认选中默认简历；无简历时提示去上传或去简历页新建。
- AC-002 点选别的简历能切换；选"不使用简历"后开面不带简历。
- AC-003 上传 PDF 能解析成功并显示文件名；解析失败给出错误提示、不阻断开面。
- AC-004 开面后首条消息的 `pageContext` 含简历内容（可在网络请求体里看到），但界面气泡只显示"开始面试"，不显示简历全文。
- AC-005 `pnpm --filter @paideia/app typecheck` 与 `pnpm --filter @paideia/ui test` 通过；`identity.spec` 面试用例契约保持。
- AC-006 开场有积分提示；点「开始面试」先弹确认框，确认后才真正发起（`identity.spec` 已同步点确认）。
- AC-007 「结束面试」与站内跳转都弹二次确认，确认后回到未开始状态；关闭/刷新有 `beforeunload` 提示。

## 待确认问题

- 无（范围由用户选定）。
