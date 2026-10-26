/**
 * 学习线与学习记录的前端契约（WORK-020）。
 *
 * <p>与后端 DTO 同构，不做中间映射层：字段名一致，改一处就两处都看得见。
 *
 * <p>**线的集合来自服务端**：这里没有任何"Python / Java"常量。加一条线不改前端，
 * 靠的就是这一点——`/learn` 只遍历 `lines`。
 */

export type LineKind = 'course' | 'reading'

export interface LearningStation {
  entryId: number
  title: string
  index: number
}

export interface LearningLine {
  categoryId: number
  slug: string
  name: string
  kind: LineKind
  sortOrder: number
  stationCount: number
  passedCount: number
  nextEntry: LearningStation | null
  dueReviewCount: number
}

export interface BlockScore {
  score: number
  total: number
}

export type LearningStatus = 'in_progress' | 'passed'

export interface EntryProgressView {
  entryId: number
  title: string
  /** 所属分类的学习线类别（course / reading / null）：页面据此选助手行为。 */
  lineKind: LineKind | null
  status: LearningStatus
  tourStep: number
  passedAt: string | null
  reviewStage: number
  reviewDueAt: string | null
  challenges: Record<string, BlockScore>
  quizzes: Record<string, BlockScore>
}

export interface ProgressReport {
  tourStep?: number
  challenges?: Record<string, BlockScore>
  quizzes?: Record<string, BlockScore>
}

/** 掌握度档位（WORK-021）。与后端 `ConceptMastery` 的常量一一对应。 */
export type MasteryTier = 'unknown' | 'learning' | 'familiar' | 'mastered'

export interface ConceptCarrier {
  entryId: number
  title: string
  /** 该条目上的得分率；null 表示没做过小测或练习。 */
  scoreRatio: number | null
}

export interface ConceptEvidence {
  viewed: number
  assessed: 'understood' | 'unsure' | null
  scoredEntries: number
}

export interface ConceptNode {
  id: number
  name: string
  summary: string | null
  categoryId: number | null
  categoryName: string | null
  score: number
  tier: MasteryTier
  reviewDue: boolean
  evidence: ConceptEvidence
  entries: ConceptCarrier[]
}

export interface ConceptEdge {
  from: number
  to: number
  type: 'prerequisite' | 'related'
}

export interface ConceptGraphStats {
  concepts: number
  touched: number
  mastered: number
  dueForReview: number
  truncated: boolean
}

export interface ConceptGraph {
  concepts: ConceptNode[]
  edges: ConceptEdge[]
  stats: ConceptGraphStats
}

/**
 * 学习轨道（WORK-024）。
 *
 * <p>与 `LearningLine` 的区别：轨道是**独立实体**，关卡有自己的顺序、章节分组与前置依赖；
 * 线只是知识库分类的一个标记。用户要求"从零开始不要是知识库"，所以轨道另起一套类型。
 */
export interface TrackSummary {
  id: number
  slug: string
  name: string
  summary: string | null
  kind: string
  stageCount: number
  passedCount: number
  nextEntryId: number | null
  nextTitle: string | null
}

/** 地图上的一格。`done` 表示这一关已经通过；没有锁定态，任何一关都能直接点进去。 */
export interface TrackStage {
  entryId: number
  seq: number
  title: string
  status: 'done' | 'current'
  /** 前置关卡的 entryId；前端据此画连线，它表达顺序不表达门槛。 */
  requiresEntryId: number | null
  /** 难度：beginner / intermediate / advanced。 */
  level: string
}

export interface TrackChapter {
  chapter: string
  stages: TrackStage[]
}

export interface TrackMap {
  id: number
  slug: string
  name: string
  summary: string | null
  chapters: TrackChapter[]
  stageCount: number
  passedCount: number
}

/** 到期复习（WORK-034 S1）。 */
export interface ReviewDueItem {
  entryId: number
  title: string
  conceptId: number | null
  conceptName: string | null
  reviewDueAt: string
  stage: number
}

export interface ReviewDueList {
  items: ReviewDueItem[]
  total: number
}

export interface ReviewSessionItem {
  itemId: number
  conceptId: number
  stem: string
  options: string[]
}

export interface ReviewFallback {
  entryId: number
  title: string
}

export interface ReviewSession {
  sessionId: number
  items: ReviewSessionItem[]
  expiresAt: string | null
  fallback: ReviewFallback[]
}

export interface ReviewResult {
  itemId: number
  conceptId: number
  correct: boolean
  explanation: string
}

export interface ReviewSchedule {
  entryId: number
  stage: number
  reviewDueAt: string
}

export interface ReviewSubmitResult {
  results: ReviewResult[]
  schedule: ReviewSchedule[]
}
