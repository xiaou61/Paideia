import type { ApiClient } from '@paideia/core'

import { readEventStream } from './sse'
import type {
  ChatEvent,
  ConversationView,
  CredentialView,
  CreditsView,
  HistoryMessage,
  InterviewEvaluationView,
  MemoryCandidate,
  MemoryClearResult,
  MemorySettings,
  MemoryView,
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
  /** 本人的记忆控制面设置（本人范围：请求体里没有 `userId`，传不了别人的）。 */
  settings(): Promise<MemorySettings>
  /** 写设置。**三个布尔一次给全**：后端不接受部分更新，缺字段会被当成"关"。 */
  saveSettings(settings: MemorySettings): Promise<MemorySettings>
  /**
   * 本人的记忆（严格私有，没有"看别人的"这种调用）。
   *
   * <p>`limit`/`offset` 由调用方显式给：翻页要靠 offset，而后端的默认值将来可能变，
   * 前端跟着它走等于把"一页多少条"这个决定放在看不见的地方。
   */
  memories(limit?: number, offset?: number): Promise<MemoryView[]>
  /**
   * 待确认的候选记忆（WORK-050）。
   *
   * <p>它不是"记忆"——它是助手拿不准、排队等用户点一下的东西。被敏感信息拦下的那些
   * `content` 为 null，前端只能显示一句"有一条没记"。
   */
  pendingCandidates(limit?: number, offset?: number): Promise<MemoryCandidate[]>
  /** 接受一条候选：它成为一条正式记忆。 */
  acceptCandidate(id: number): Promise<void>
  /** 拒绝一条候选：它永远不会成为记忆。 */
  rejectCandidate(id: number): Promise<void>
  /** 逐条删除。返回是否真的删掉了一行（不是本人的会返回 false）。 */
  deleteMemory(source: MemoryView['source'], id: number): Promise<boolean>
  /**
   * 清空两路记忆**并把总开关置为 false**，返回更新后的设置。
   *
   * <p>清空与关闭是**一个**端点、一次请求：分成两个调用会出现"清空了但没关上"的半状态。
   */
  clearMemories(): Promise<MemoryClearResult>
  /**
   * 记住一句话（写一条全文记忆）。**这是记忆唯一的写入口**，只有用户点击才会走到这里——
   * 助手自己无法调用它（端口里没有写工具）。
   */
  remember(text: string): Promise<number>
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
  /**
   * 对一条助手回答提交反馈（WORK-036）。
   *
   * <p>**重复提交是覆盖**：同一用户对同一轮再点一次会改写那一条，不会堆出两条。
   * `traceId` 为 null 时（采集关闭）仍可提交，只是挂不到轮次上。
   */
  submitFeedback(input: {
    conversationId: number | null
    traceId: string | null
    rating: 'up' | 'down'
    reason?: 'off_topic' | 'too_verbose' | 'incorrect' | 'other'
  }): Promise<void>
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
  const JSON_HEADERS = { 'Content-Type': 'application/json' }

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

    settings: () => client.requestEnvelope<MemorySettings>(`${base}/settings`),

    saveSettings: (settings) =>
      client.requestEnvelope<MemorySettings>(`${base}/settings`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(settings),
      }),

    pendingCandidates: (limit = 20, offset = 0) =>
      client.requestEnvelope<MemoryCandidate[]>(
        `${base}/memories/candidates?limit=${limit}&offset=${offset}`),
    acceptCandidate: (id: number) =>
      client.requestEnvelope<void>(`${base}/memories/candidates/${id}/accept`, { method: 'POST' }),
    rejectCandidate: (id: number) =>
      client.requestEnvelope<void>(`${base}/memories/candidates/${id}/reject`, { method: 'POST' }),
    memories: (limit = 200, offset = 0) =>
      client.requestEnvelope<MemoryView[]>(`${base}/memories?limit=${limit}&offset=${offset}`),

    deleteMemory: (source, id) =>
      client.requestEnvelope<boolean>(`${base}/memories/${source}/${id}`, { method: 'DELETE' }),

    clearMemories: () =>
      client.requestEnvelope<MemoryClearResult>(`${base}/memories`, { method: 'DELETE' }),

    remember: (text) =>
      client.requestEnvelope<number>(`${base}/memories`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ type: 'text', text }),
      }),

    submitFeedback: async (input) => {
      await client.requestEnvelope(`${base}/feedback`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({
          conversationId: input.conversationId,
          traceId: input.traceId,
          rating: input.rating,
          reason: input.reason ?? null,
        }),
      })
    },

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
  }
}
