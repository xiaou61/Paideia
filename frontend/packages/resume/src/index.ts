/**
 * 简历包：DTO、API 客户端、自动保存状态机与对比渲染（WORK-018）。
 *
 * <p><b>它不决定任何权限</b>：简历的归属完全由后端 SQL 校验，前端拿到的永远只是"我的"。
 * 这个包的任何导出都不该出现在权限判断里。
 */

export {
  acceptServerRevision,
  beginSave,
  forceOverwrite,
  hasUnsavedWork,
  initialState,
  markDirty,
  saveFailed,
  saveSucceeded,
  type AutosaveState,
  type AutosaveStatus,
} from './autosave'
export { createResumeApi, type ResumeApi } from './client'
export { ChangedText, diffParts, RemovedText, removedParts, type DiffPart } from './diff'
export {
  readResumeTemplate,
  RESUME_TEMPLATES,
  ResumeSheet,
  saveResumeTemplate,
  type ResumeTemplateId,
  type ResumeTemplateInfo,
} from './templates'
export {
  BUCKET_LABELS,
  emptyResume,
  type ChangeType,
  type Checkup,
  type CheckupItem,
  type ClassifiedKeyword,
  type GateStatus,
  type KeywordBucket,
  type ResumeAnalysis,
  type ResumeAward,
  type ResumeBasics,
  type ResumeData,
  type ResumeDocument,
  type ResumeDocumentSummary,
  type ResumeDraft,
  type ResumeEducation,
  type ResumeHistoryEntry,
  type ResumeIntent,
  type ResumeProject,
  type ResumeProposal,
  type ResumeSkillGroup,
  type ResumeVersionDetail,
  type ResumeVersionSummary,
  type ResumeWork,
} from './types'
