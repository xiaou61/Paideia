import type { Identity, JobStage } from './types'

/**
 * 导航键：与 `LearnerHeader` 的 `NAV[].key`、首页快捷卡的 `key` 一一对应。
 *
 * <p>它是联合字面量而不是 `string`：加了新入口却忘了给某个身份补排序时，编译期就会红，
 * 不用等到界面上发现某一行排错位置。
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
 * 按身份的推荐配置包（WORK-010 T-12）。
 *
 * <p><b>为什么是代码里的映射而不是一张表</b>（design §6）：线上一共两个账号，规则映射都无从调参，
 * 建表只会多一处要维护的配置载体。等真有第二个真身份、且这些值需要按数据调时再迁移。
 *
 * <p>它决定两件**纯展示**的事：导航里哪些入口排前面、面试开场说什么。
 * **它不决定任何权限**——身份与 role 正交，这里改什么都影响不了能不能做什么。
 *
 * <p><b>2026-11-09 删掉了两个字段</b>：`preferredCategories`（首页早改成读 `preferredNav`
 * 排快捷卡，它没人读了）与 `assistantGreeting`（助手面板下线后开场白没有消费者）。
 * 留着它们比删掉更危险：下一个改配置的人会以为改这几行界面就会变。
 */
export interface IdentityConfig {
  /** 导航顺序：把哪几个入口提前。 */
  preferredNav: readonly NavKey[]
  /** 面试页的开场说明（面试官自己会再问第一题）。 */
  interviewIntro: string
}

/**
 * 各身份的配置。
 *
 * <p>留位的三个身份**也给了配置**：这不是"做了界面"，而是让"按身份配置"这件事有真实的
 * 第二个取值可测（数据层与单测能覆盖），同时保证它们万一被启用时不会拿到空配置。
 */
const CONFIGS: Record<Identity, IdentityConfig> = {
  job_seeker: {
    // 'digest' 排第 2：求职者每天看速递比泛读知识库更贴合场景（WORK-027）
    preferredNav: ['interview', 'digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    interviewIntro: '我会一次问一个问题，答不上来就说"不会"，我会换个角度。随时可以说"结束"。',
  },
  postgrad: {
    preferredNav: ['digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    interviewIntro: '我会一次问一个问题。随时可以说"结束"。',
  },
  civil_service: {
    preferredNav: ['digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    interviewIntro: '我会一次问一个问题。随时可以说"结束"。',
  },
  daily_learning: {
    preferredNav: ['digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    interviewIntro: '我会一次问一个问题。随时可以说"结束"。',
  },
}

/** 取某个身份的配置。不认识的身份退回 `job_seeker`（与后端同一个兜底方向）。 */
export function configFor(identity: Identity | null | undefined): IdentityConfig {
  if (identity === null || identity === undefined) {
    return CONFIGS.job_seeker
  }
  return CONFIGS[identity] ?? CONFIGS.job_seeker
}

/**
 * 当前求职阶段最该先做的一件事（2026-11-09）。
 *
 * <p><b>为什么单独做一张表</b>：画像里的 `stage` 原来**只存不读**——填了"面试"和没填，
 * 首页一模一样，那这个字段就是在骗人。三个阶段各给一条**真实存在**的去处：
 * 投递期补简历、笔试期清复习队列、面试期练面试。它们都是站内已经做好的页面，
 * 不是占位链接。
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
