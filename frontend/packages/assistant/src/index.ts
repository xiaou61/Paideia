/**
 * AI 能力共享包（WORK-066 起不再有助手面板）。
 *
 * <p>现在只提供两类**有明确位置**的 UI 与它们的接口：条目摘要卡、积分面板，
 * 外加数据集向导用的选项卡（GuideCard）。通用对话面板与记忆面板已下线。
 *
 * <p>本包**不认识令牌**：鉴权与 401 刷新重放由传入的 `ApiClient` 负责。
 */

export { createAssistantApi } from './client'
export type { AssistantApi } from './client'
export { GuideCard, composeGuideMessage, type AssistantGuide } from './guide-card'
export { CreditsPanel } from './credits-panel'
export { SummaryCardView } from './summary-card'
export type {
  CreditRow,
  CreditsView,
  InterviewDimension,
  InterviewEvaluation,
  InterviewEvaluationView,
  SummaryCard,
  SummarySection,
  SummaryView,
  SummaryVisibility,
} from './types'