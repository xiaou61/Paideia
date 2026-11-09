/**
 * 画像包：DTO、API 客户端、导航顺序与阶段提示。
 *
 * <p><b>2026-11-09 起没有"身份选择"</b>：后端只让求职者可选，选择器只是让用户点一个没有
 * 第二选项的东西，于是连同"按身份排序"一起移除。这里保留的是画像本身：
 * 目标岗位 / 城市 / 招聘季 / 当前阶段，四个字段都被真实消费（学习线排序、简历预填、
 * 首页建议、面试出题侧重）。
 *
 * <p><b>它不决定任何权限</b>：`role` 才决定能做什么。这个包的任何导出都不该出现在权限判断里。
 */

export { createIdentityApi, type IdentityApi } from './client'
export {
  INTERVIEW_INTRO,
  NAV_PREFERENCE,
  stageCue,
  type NavKey,
  type StageCue,
} from './config'
export { STAGE_LABELS, TIMELINE_LABELS } from './types'
export type { Identity, JobStage, JobTimeline, LearnerProfile, LearnerProfileInput } from './types'
