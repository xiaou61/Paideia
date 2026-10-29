import type { Identity } from './types'

/**
 * 按身份的推荐配置包（WORK-010 T-12）。
 *
 * <p><b>为什么是代码里的映射而不是一张表</b>（design §6）：线上一共两个账号，规则映射都无从调参，
 * 建表只会多一处要维护的配置载体。等真有第二个真身份、且这些值需要按数据调时再迁移。
 *
 * <p>它决定三件**纯展示**的事：首页推荐哪几个分类、导航里哪些入口排前面、助手开场白说什么。
 * **它不决定任何权限**——身份与 role 正交，这里改什么都影响不了能不能做什么。
 */
export interface IdentityConfig {
  /** 首页优先推荐的知识库分类 slug（按顺序；不存在的忽略）。 */
  preferredCategories: string[]
  /** 导航顺序：把哪几个入口提前。 */
  preferredNav: readonly ('learn' | 'graph' | 'sql-practice' | 'knowledge' | 'notes' | 'my-assessments' | 'interview' | 'digest' | 'progress')[]
  /** 助手在"没有条目上下文"时的开场白。 */
  assistantGreeting: string
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
    // 求职者优先看到**面试与底层原理**这两块：面试问的正是这些，而日常学习更偏"按分类随手读"。
    // 这个差别必须真实存在——否则"切换身份"在首页上看不出任何变化（AC-002 就无从验收）。
    preferredCategories: ['java-concurrency', 'architecture', 'backend-basics'],
    // 'digest' 排第 2：求职者每天看速递比泛读知识库更贴合场景（WORK-027）
    preferredNav: ['interview', 'digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    assistantGreeting: '问点什么吧。找工作期间，我可以帮你看漏了哪些知识点、也可以陪你模拟面试。',
    interviewIntro: '我会一次问一个问题，答不上来就说"不会"，我会换个角度。随时可以说"结束"。',
  },
  postgrad: {
    preferredCategories: ['backend-basics', 'architecture'],
    preferredNav: ['digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    assistantGreeting: '问点什么吧，比如让它解释当前内容里的一个概念。',
    interviewIntro: '我会一次问一个问题。随时可以说"结束"。',
  },
  civil_service: {
    preferredCategories: ['backend-basics', 'architecture'],
    preferredNav: ['digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    assistantGreeting: '问点什么吧，比如让它解释当前内容里的一个概念。',
    interviewIntro: '我会一次问一个问题。随时可以说"结束"。',
  },
  daily_learning: {
    preferredCategories: ['backend-basics', 'java-concurrency', 'architecture'],
    preferredNav: ['digest', 'learn', 'knowledge', 'notes', 'my-assessments'],
    assistantGreeting: '问点什么吧，比如让它解释当前内容里的一个概念。',
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
