/**
 * 知识库共享包。
 *
 * <p>学习者端与管理端都用它：两个应用展示同一份内容、按同一条规则生成正文锚点，
 * 因此类型、客户端与 markdown 渲染只能有一份实现——另写一份就会在"管理端预览与实际
 * 展示不一致"上翻车，而预览的全部价值恰恰是"看到的就是学习者看到的"。
 *
 * <p>本包**不认识令牌**：鉴权、401 刷新重放都由传入的 `ApiClient` 负责。
 */

export { createKnowledgeAdminApi } from './admin-client'
export type {
  AdminEntryDetail,
  AdminEntrySummary,
  AdminRelation,
  CategoryInput,
  ConceptInput,
  ConceptSummary,
  EntryInput,
  KnowledgeAdminApi,
} from './admin-client'
export { createKnowledgeApi } from './client'
export type {
  KnowledgeApi,
  ListEntriesOptions,
  RunCodeInput,
  RunCodeResult,
  SandboxStatus,
} from './client'
export { stripDuplicateTitle } from './entry-body'
export { EntryToc } from './entry-toc'
export { PracticeEntry } from './practice-entry'
export { findCategoryBySlug } from './category-tree'
export { orderChapters } from './chapter-order'
export { MAX_HEADING_LEVEL, createSlugger, extractHeadings, slugify } from './heading-slug'
export type { Heading } from './heading-slug'
export { FigureWithLightbox } from './image-lightbox'
export { resolveMediaUrl } from './media'
export {
  circularLayout,
  layoutKey,
  loadPositions,
  resetPositions,
  savePositions,
  spreadApart,
  type Point,
} from './concept-graph-layout'
export { createEntryProgressPort, recentReading, type RecentReading } from './source-reading-progress'
export { createLearningApi, type LearningApi } from './learning-client'
export { createDigestApi, type DigestApi } from './digest-client'
export {
  DIGEST_GROUPS,
  type DigestByGroup,
  type DigestGroupKey,
  type DigestItem,
  type DigestWeekly,
} from './digest-types'
export { createServerProgressPort, mergeProgress, type ServerProgressPort } from './learning-progress'
export {
  DEFAULT_ROLE,
  normalizeRole,
  ROLES,
  roleMeta,
  tierOf,
  type RoleKey,
  type RoleMeta,
  type TrackTier,
} from './role-map'
export {
  explainParts,
  pickAdvice,
  ruleReason,
  scoreTracks,
  type RecommendTrack,
  type ScoreParts,
  type ScoredTrack,
} from './recommend'
export type {
  AnsweredChoice,
  BlockScore,
  ConceptCarrier,
  ConceptEdge,
  ConceptEvidence,
  ConceptGraph,
  ConceptGraphStats,
  ConceptNode,
  EntryProgressView,
  LearningLine,
  LearningStation,
  LearningStatus,
  LineKind,
  MasteryTier,
  ProgressReport,
  TrackChapter,
  TrackMap,
  TrackStage,
  TrackSummary,
} from './learning-types'
export { plainText } from './plain-text'
export { SelfAssessmentButtons } from './self-assessment'
export {
  rankHomeEntries,
  type HomeCandidate,
  type HomeSignals,
  type RankedEntry,
} from './home-ranking'
export { MarkdownBody } from './markdown-body'
export { MarkdownField } from './markdown-field'
export { KnowledgeNeighborhood } from './neighborhood'
export type {
  ReviewDueItem,
  ReviewDueList,
  ReviewFallback,
  ReviewResult,
  ReviewSchedule,
  ReviewSession,
  ReviewSessionItem,
  ReviewSubmitResult,
} from './learning-types'

export type {
  AssessedItem,
  CategoryNode,
  EntryDetail,
  EntryRef,
  EntrySummary,
  PageResult,
  SelfAssessmentLevel,
} from './types'
