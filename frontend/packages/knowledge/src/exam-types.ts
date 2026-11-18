/** 试卷 / 刷题 / 错题本的前端 DTO（WORK-083）。与后端 PaperService / PracticeService / WrongBookService 对齐。 */

export interface PracticeFilter {
  categoryId?: number | null
  conceptIds?: number[] | null
  difficultyLabel?: string | null
  onlyWrong?: boolean
  onlyUndone?: boolean
  limit?: number
}

export interface PracticeQuestion {
  blockKey: string
  questionKey: string
  entryId: number
  stem: string
  options: string[]
  difficultyLabel: string | null
}

export interface AttemptResult {
  correct: boolean
  fingerprint: string
}

export interface PaperRuleInput {
  title: string
  conceptIds?: number[]
  categoryId?: number | null
  difficultyDistribution?: Record<string, number>
  count: number
  excludeFingerprints?: string[]
}

export interface PaperItemView {
  itemId: number
  seq: number
  stem: string
  options: string[]
  difficultyLabel: string | null
  conceptId: number | null
}

export interface PaperView {
  id: number
  title: string
  status: string
  shortfall: number
  items: PaperItemView[]
  correctCount: number | null
  totalCount: number | null
  submittedAt: string | null
}

export interface PaperSummary {
  id: number
  title: string
  status: string
  totalCount: number | null
  createdAt: string
}

export interface ConceptStat {
  conceptId: number
  correct: number
  total: number
  weak: boolean
}

export interface DifficultyStat {
  label: string
  correct: number
  total: number
}

export interface PaperResult {
  id: number
  title: string
  correct: number
  total: number
  concepts: ConceptStat[]
  difficulties: DifficultyStat[]
  weakConceptIds: number[]
}

export interface WrongQuestion {
  blockKey: string
  questionKey: string
  entryId: number
  stem: string
  options: string[]
  difficultyLabel: string | null
  conceptIds: number[]
  conceptNames: string[]
}
