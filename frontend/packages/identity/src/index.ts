/**
 * 身份包：DTO、API 客户端与按身份的推荐配置（WORK-010）。
 *
 * <p><b>它不决定任何权限</b>：`role` 决定能做什么，身份只影响推荐顺序与面试开场。
 * 这个包的任何导出都不该出现在权限判断里。
 */

export { IDENTITY_LABELS, createIdentityApi, type IdentityApi } from './client'
export { configFor, stageCue, type IdentityConfig, type NavKey, type StageCue } from './config'
export { STAGE_LABELS, TIMELINE_LABELS } from './types'
export type { Identity, JobStage, JobTimeline, LearnerProfile, LearnerProfileInput } from './types'
