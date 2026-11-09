import type { JobStage } from './types'

/**
 * 导航键：与 `LearnerHeader` 的 `NAV[].key`、首页快捷卡的 `key` 一一对应。
 *
 * <p>它是联合字面量而不是 `string`：加了新入口却忘了补排序时，编译期就会红。
 */
export type NavKey =
  | 'learn'
  | 'graph'
  | 'sql-practice'
  | 'knowledge'
  | 'notes'
  | 'my-assessments'
  | 'interview'
  | 'digest'
  | 'progress'

/**
 * 导航与首页入口的推荐顺序（`interview` 与 `digest` 在前）。
 *
 * <p><b>2026-11-09：从"按身份分叉的配置表"降成一组常量。</b>原来这里是一张
 * `Record<Identity, IdentityConfig>`，四个身份四份 `preferredNav`，但后端只让
 * `job_seeker` 可选——于是"按身份排序"实际上永远取到同一份，界面上还多了一个
 * 只能选一项的选择器。用户要求"注册就注册，不要选身份"，于是：
 *
 * <ul>
 *   <li>身份选择器与画像页的「当前身份」整块删除；
 *   <li>排序不再读画像，变成这组常量（写死的就是原来求职者那份的顺序，界面上看不出变化）；
 *   <li>画像里真正被消费的四个字段（岗位/城市/阶段/求职季）保持原样，与身份无关。
 * </ul>
 *
 * <p>**为什么还要保留这个包**：画像的读写客户端（`createIdentityApi`）、阶段提示
 * （`stageCue`）与求职季标签都在这里，删包等于把三件事各搬一次家。
 */
export const NAV_PREFERENCE: readonly NavKey[] = [
  'interview',
  'digest',
  'learn',
  'knowledge',
  'notes',
  'my-assessments',
]

/** 面试页的开场说明（面试官自己会再问第一题）。 */
export const INTERVIEW_INTRO = '我会一次问一个问题，答不上来就说"不会"，我会换个角度。随时可以说"结束"。'

/**
 * 当前求职阶段最该先做的一件事（2026-11-09）。
 *
 * <p>画像里的 `stage` 原来只存不读——填了"面试"和没填，首页一模一样。三个阶段各给一条
 * **真实存在**的去处：投递期补简历、笔试期清复习队列、面试期练面试。
 *
 * <p>表里只有 `to` / `label` / `hint` 三个字段，且 `to` 全是字面量：路由改名时这里会编译不过，
 * 不会留下一个点了 404 的提示。
 */
export interface StageCue {
  /** 站内路径（字面量，路由改名即编译失败）。 */
  to: string
  /** 一句话的动作。 */
  label: string
  /** 为什么现在做这件事。 */
  hint: string
}

const STAGE_CUES: Record<JobStage, StageCue> = {
  applying: {
    to: '/resume',
    label: '投递期：先把简历体检一遍',
    hint: '体检会指出缺哪几块——没写目标岗位、要点没有数字、某个分区是空的。',
  },
  written_test: {
    to: '/review',
    label: '笔试期：把到期复习清掉',
    hint: '笔试考的是记得住的细节，复习队列按到期日排好了，答错顺延、全对进档。',
  },
  interview: {
    to: '/interview',
    label: '面试期：来一场模拟面试',
    hint: '一次一个问题，可以带一份简历进去，结束按维度给评分与改进建议。',
  },
}

/**
 * 阶段 → 提示。没填阶段返回 `null`（不占位置、不硬塞建议）。
 *
 * <p>库里读到不认识的值也返回 `null` 而不是抛错：脏值不该让首页打不开。
 */
export function stageCue(stage: JobStage | null | undefined): StageCue | null {
  if (stage === null || stage === undefined) {
    return null
  }
  return STAGE_CUES[stage] ?? null
}
