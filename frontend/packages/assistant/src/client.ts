import type { ApiClient } from '@paideia/core'
import { JSON_HEADERS } from '@paideia/core'

import { readEventStream } from './sse'
import type {
  AiPoints,
  AiQuiz,
  AiSteps,
  AiText,
  ChatEvent,
  ConversationView,
  CredentialView,
  CreditsView,
  HistoryMessage,
  InterviewEvaluationView,
  ReviewExplainInput,
  SummaryView,
  SummaryVisibility,
} from './types'

export interface AssistantApi {
  /** AI 服务状态（平台配没配）。**只读**：WORK-009 起用户不再配置 Key。 */
  credential(): Promise<CredentialView>
  /** 本人的积分余额与消耗明细。**只读**：本项不做充值。 */
  credits(): Promise<CreditsView>
  /** 只读：有现成结果就返回，没有返回 null（**不触发生成**，也不花钱）。 */
  summary(entryId: number): Promise<SummaryView | null>
  /** 生成或命中。这是唯一会花用户额度的动作。 */
  generate(entryId: number, visibility: SummaryVisibility): Promise<SummaryView>
  /** 建会话。`entryId` 可空——助手在任意页都能开，条目只是可选上下文。 */
  /**
   * 建一段会话。
   *
   * <p>`harness` 是**公共行为**（WORK-010）：面试页传 `interview`，点评用 `review`。
   * 不传 = 通用助手。**行为会记在会话上**——从通用入口继续一段面试对话仍是面试行为。
   */
  createConversation(entryId: number | null, harness?: 'assistant' | 'interview' | 'tutor' | 'planner'): Promise<ConversationView>
  /** 本人的会话列表（**不含消息内容**，只用来选一条）。 */
  conversations(): Promise<ConversationView[]>
  /** 面试点评（一次性结果，非流式）。 */
  reviewInterview(conversationId: number): Promise<InterviewEvaluationView>
  /** 我历次面试的评估。 */
  interviewEvaluations(): Promise<InterviewEvaluationView[]>
  /** 读回某会话的消息（**旧 → 新**），供界面恢复历史。 */
  messages(conversationId: number): Promise<HistoryMessage[]>
  /** 五个一次性 AI 入口（WORK-067）：只回一小段、不落库，每次点击都花积分。 */
  explainReviewItem(input: ReviewExplainInput): Promise<AiText>
  nextStep(input: { lines: string; failing?: string; dueReviews?: string; weakConcepts?: string }): Promise<AiSteps>
  explainConcept(input: { name: string; summary?: string }): Promise<AiText>
  notePoints(input: { title?: string; body: string }): Promise<AiPoints>
  quiz(input: { title?: string; body: string }): Promise<AiQuiz>
  /**
   * 发一轮消息，把 SSE 事件逐个回调出去。本方法返回时表示这一轮结束。
   *
   * <p>`signal` 用于"用户关掉面板/切换会话"时中断：不断开的话，上游调用会继续计费到
   * 用户看不见的地方。
   */
  sendMessage(
    conversationId: number,
    message: string,
    onEvent: (event: ChatEvent) => void,
    signal?: AbortSignal,
    /** 页面场景（WORK-035）：已截断的一段文本，只进提示词不落库。 */
    pageContext?: string,
  ): Promise<void>
}

/**
 * AI 助手端点客户端。
 *
 * <p>与知识库那份同样只包路径与请求体，鉴权与 401 刷新重放交给传入的 `ApiClient`——
 * 本包不认识令牌，也不需要知道它存在。
 *
 * <p>对话那条走 `client.stream`（SSE）：**不是** `requestEnvelope`，后者会把响应体读完再解析 JSON，
 * 那样流式就退化成"等完整结果"了。
 */
export function createAssistantApi(client: ApiClient): AssistantApi {
  const base = '/api/v1/ai'

  return {
    credential: () => client.requestEnvelope<CredentialView>(`${base}/credentials`),

    credits: () => client.requestEnvelope<CreditsView>(`${base}/credits`),

    summary: (entryId) => client.requestEnvelope<SummaryView | null>(`${base}/summaries/${entryId}`),

    generate: (entryId, visibility) =>
      client.requestEnvelope<SummaryView>(`${base}/summaries`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ entryId, visibility }),
      }),

    createConversation: (entryId, harness) =>
      client.requestEnvelope<ConversationView>(`${base}/conversations`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ entryId, harness }),
      }),

    conversations: () => client.requestEnvelope<ConversationView[]>(`${base}/conversations`),

    /**
     * 给一段面试生成点评（WORK-010）。
     *
     * <p>**不是 SSE**：点评是一次性结果，要同步拿到评分再展示与落库。
     */
    reviewInterview: (conversationId) =>
      client.requestEnvelope<InterviewEvaluationView>(`${base}/interviews/${conversationId}/review`, {
        method: 'POST',
      }),

    /** 我历次面试的评估（新 → 旧）。 */
    interviewEvaluations: () => client.requestEnvelope<InterviewEvaluationView[]>(`${base}/interviews/evaluations`),

    messages: (conversationId) =>
      client.requestEnvelope<HistoryMessage[]>(`${base}/conversations/${conversationId}/messages`),

    // pageContext 来自 WORK-035（工作台把当前 SQL/数据集带进对话）；两边各加一个参数与方法，都要留
    sendMessage: async (conversationId, message, onEvent, signal, pageContext) => {
      const response = await client.stream(`${base}/conversations/${conversationId}/messages`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(pageContext === undefined ? { message } : { message, pageContext }),
        ...(signal === undefined ? {} : { signal }),
      })
      // 用量在线上是 `input`/`output`/`total`，界面用的是 `TokenUsage`。**只在这一处归一**：
      // 分散到各个渲染点去换字段名，迟早有一条路径漏掉。
      await readEventStream(response, (event) => {
        const usage =
          event.type === 'done' && event.usage !== undefined && event.usage !== null
            ? {
                inputTokens: event.usage.input,
                outputTokens: event.usage.output,
                totalTokens: event.usage.total,
              }
            : null
        onEvent({
          type: event.type,
          delta: event.delta,
          message: event.message,
          empty: event.empty,
          // 草稿必须透传：界面靠 event.draft 收集"待确认草稿"，
          // 在这里漏掉会让"保存为笔记"整条链路静默失效且不报错
          draft: event.draft,
          // 轮次 id 同理必须透传：漏掉它，点赞/点踩就挂不到任何一轮上，
          // 而且不会报错——只是所有反馈都变成"未关联"
          traceId: event.traceId,
          usage,
        })
      })    },
    explainReviewItem: (input) =>
      client.requestEnvelope<AiText>('/api/v1/ai/explain/review-item', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    nextStep: (input) =>
      client.requestEnvelope<AiSteps>('/api/v1/ai/next-step', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    explainConcept: (input) =>
      client.requestEnvelope<AiText>('/api/v1/ai/concepts/explain', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    notePoints: (input) =>
      client.requestEnvelope<AiPoints>('/api/v1/ai/notes/points', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    quiz: (input) =>
      client.requestEnvelope<AiQuiz>('/api/v1/ai/quiz', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

  }
}
