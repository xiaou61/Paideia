/** AI 精简的对外数据形状，与后端 DTO 字段名一致（不再加一层映射）。 */

/** 结构化卡片的一节。 */
export interface SummarySection {
  heading: string
  body: string
}

/** 结构化精简卡片：总标题 + 若干带小标题的小节。 */
export interface SummaryCard {
  headline: string
  sections: SummarySection[]
}

/**
 * 一次精简的结果。
 *
 * <p>`usage` 在**命中缓存时为 `null`**，而不是 0：那代表"本次没有产生调用"，
 * 与"花了 0 个 token"是两件事，混成一个值会让人以为又付了一次钱。
 */
export interface SummaryView {
  card: SummaryCard
  cached: boolean
  /** `mine` = 当前用户生成的；`others` = 他人公开的结果。 */
  source: 'mine' | 'others'
  visibility: 'public' | 'private'
  usage: TokenUsage | null
  truncated: boolean
  generatedAt: string
}

export interface TokenUsage {
  inputTokens: number
  outputTokens: number
  totalTokens: number
}

/**
 * AI 服务的状态。
 *
 * <p>WORK-009 起上游凭据由**平台**持有、用户不再配置 Key，所以这里既没有写入口，也**没有
 * `apiKeyLast4`**——那是平台的 Key，不是用户的，把它的片段放到用户界面上没有意义。
 */
export interface CredentialView {
  configured: boolean
  baseUrl: string | null
  model: string | null
}

/** 一笔积分流水（WORK-009 REQ-008）。 */
export interface CreditRow {
  createdAt: string | null
  delta: number
  kind: 'ADJUST' | 'RESERVE' | 'SETTLE' | 'REFUND'
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  refMessageId: number | null
  note: string | null
}

/** 本人的积分余额与消耗明细。**只读**：本项不做充值，积分只能由管理员发放。 */
export interface CreditsView {
  balance: number
  ledger: CreditRow[]
}

/** 生成时的可见性，由**生成者**选择（谁付费谁决定要不要分享）。 */
export type SummaryVisibility = 'public' | 'private'

/** 会话列表项。**不含消息内容**：列表只用来选，拉开某一条才取内容。 */
export interface ConversationView {
  id: number
  entryId: number | null
  updatedAt: string | null
}

/**
 * 一条记忆（设置页用，用户裁决 13）。
 *
 * <p>`source` 必须回给删除动作：结构化与全文两路的 id **各自独立**，只说 id 会删错一路。
 * 它与 {@link MemoryView.origin} 是**两件事**：`source` 是存哪一路，`origin` 是谁写的。
 */
export interface MemoryView {
  source: 'structured' | 'text'
  id: number
  text: string
  createdAt: string | null
  /** 谁写的：`manual` = 用户点「记住这条」；`auto` = 回答后台自动整理写入（WORK-013）。 */
  origin: 'manual' | 'auto'
  /** `auto` 时模型给的判定理由（为什么记/为什么改）；`manual` 时为 `null`。 */
  reason: string | null
  /** `auto` 时的出处会话，用来回答"它为什么在这里"；`manual` 时为 `null`。 */
  conversationId: number | null
}

/**
 * 记忆的控制面设置（`GET/PUT /api/v1/ai/settings`，WORK-013）。
 *
 * <p>**三个布尔一次给全**（PUT 不做部分更新的花活）：只传一个字段的话，客户端就得知道
 * 其余两个在服务端当前是什么值，那正是"本地状态与服务端不一致"的起点。
 */
export interface MemorySettings {
  /** 总开关：关掉后不召回、不自动提取，手动写入也会被后端拒绝。 */
  memoryEnabled: boolean
  /** 轴一：把已有记忆召回进提示词。**不影响自动提取**（提取是"写"不是"用"）。 */
  referenceSavedMemories: boolean
  /** 轴二：把当前对话的历史消息拼进提示词。关掉 = 只看本轮这一句。 */
  referenceChatHistory: boolean
}

/**
 * 待确认的候选记忆（WORK-050 半自动提案）。
 *
 * <p>`status='filtered'` 的行 `content` 恒为 `null`：它是因为"疑似含敏感信息"被拦下的，
 * 正文根本不该出现在浏览器里——面板只显示"有一条没记"和原因。
 */
export interface MemoryCandidate {
  id: number
  store: 'structured' | 'text'
  kind: string | null
  content: string | null
  reason: string | null
  conversationId: number | null
  status: 'pending' | 'accepted' | 'rejected' | 'filtered' | 'expired'
  decisionReason: string | null
  createdAt: string | null
  expiresAt: string | null
}

/** 「清空并关闭」的响应：清掉了几条 + 清空之后的设置（总开关已被后端关掉）。 */
export interface MemoryClearResult {
  cleared: number
  settings: MemorySettings
}

/** 界面上的一条对话消息。与后端 `role` 一致。 */
export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  /** 思考过程**只在当次显示**（服务端不落库），所以它挂在消息上、随会话消失而消失。 */
  thinking: string
  /** 助手消息的用量；上游没给或用户消息为 null。 */
  usage: TokenUsage | null
  /** 正文为空、服务端给了可见提示时为 true——界面据此区别"这是提示，不是回答"。 */
  emptyHint: boolean
  /** 这一轮的轮次 id（WORK-036）。反馈靠它挂到具体一轮上；采集关闭时为 null。 */
  traceId?: string | null
  /** 本次会话里已提交的评价（WORK-036）。**只在内存里**，刷新后不保留。 */
  feedback?: { rating: 'up' | 'down'; reason?: string | null }
  /** 落库时间。**当次流式产生的消息没有它**（那时还没落库）；从服务端读回来的历史消息有。 */
  createdAt?: string | null
}

/**
 * 服务端读回的一条历史消息（`GET /api/v1/ai/conversations/{id}/messages`）。
 *
 * <p>与 {@link ChatMessage} 分开是有理由的：那边带 `thinking` 与 `emptyHint` 这类**只在当次响应里
 * 成立**的界面状态（思考不落库，所以历史里没有它），这边只有真正写进库的东西。
 */
export interface HistoryMessage {
  role: 'user' | 'assistant'
  content: string
  usage: TokenUsage | null
  createdAt: string | null
}

/**
 * 回调给界面的对话事件——**用量已归一成 {@link TokenUsage}**。
 *
 * <p>与 `sse.ts` 的 {@link import('./sse').AssistantEvent} 分开：那个是"线上是什么形状"，
 * 这个是"界面想要什么形状"。归一只有一处（`client.ts`），界面不必知道线上用 `input` 还是 `inputTokens`。
 */
export interface ChatEvent {
  type: 'thinking' | 'content' | 'draft' | 'done' | 'error'
  delta?: string
  message?: string
  usage?: TokenUsage | null
  empty?: boolean
  /** `done` 的这一轮标识（WORK-036）；采集关闭时为 null。反馈靠它挂到具体一轮上。 */
  traceId?: string | null
  /** 助手提出的一条**待确认**笔记草稿。落库要等用户点击。 */
  draft?: { title: string; body: string }
}

/** 交给宿主存成笔记的输入。标题缺省时由宿主兜底。 */
export interface NoteDraftInput {
  title?: string
  body: string
}

/** 一个维度的评分（WORK-010）。 */
export interface InterviewDimension {
  name: string
  /** 1–5。后端会拒掉越界与非整数，所以这里不必再夹。 */
  score: number
  comment: string
}

/** 一场面试的评估。 */
export interface InterviewEvaluation {
  overall: number
  dimensions: InterviewDimension[]
  strengths: string[]
  improvements: string[]
}

/** 评估 + 它的落库标识与时间。 */
export interface InterviewEvaluationView {
  id: number
  evaluation: InterviewEvaluation
  createdAt: string
}
