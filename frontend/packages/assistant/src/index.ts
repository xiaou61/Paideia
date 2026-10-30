/**
 * AI 助手共享包。
 *
 * <p>只服务学习者端：管理端不做精简、也不做对话（那是学习者自己的阅读与思考辅助，
 * 不是内容维护动作）。
 *
 * <p>本包**不认识令牌**：鉴权与 401 刷新重放由传入的 `ApiClient` 负责。
 */

export { AssistantShell } from './assistant-shell'
export { Attach } from './attach'
export {
  ATTACHMENT_EXCERPT_LIMIT,
  ATTACHMENT_LIMIT,
  attachmentUri,
  parseAttachmentUri,
  putAttachment,
  removeAttachment,
  serializeAttachments,
  type Attachment,
  type AttachmentType,
} from './attachments'
export { createAssistantApi } from './client'
export type { AssistantApi } from './client'
export { ConversationPanel } from './conversation-panel'
export {
  AssistantProvider,
  useAssistantContext,
  PAGE_CONTEXT_LIMITS,
  truncateContext,
  type AssistantHarness,
  type PageContext,
  type PendingPrompt,
  type SqlWorkbenchPageContext,
} from './context'
export { extractSqlBlock, serializePageContext } from './page-context'
export type { AssistantContextValue } from './context'
export { CreditsPanel } from './credits-panel'
export { MemoryPanel } from './memory-panel'
export { parseSseBlock, readEventStream } from './sse'
export type { AssistantEvent, AssistantEventType } from './sse'
export { SummaryCardView } from './summary-card'
export type {
  ChatMessage,
  ConversationView,
  CreditRow,
  CreditsView,
  CredentialView,
  InterviewDimension,
  InterviewEvaluation,
  InterviewEvaluationView,
  MemoryView,
  SummaryCard,
  SummarySection,
  SummaryView,
  SummaryVisibility,
  TokenUsage,
} from './types'
