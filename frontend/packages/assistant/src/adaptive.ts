import type { InterviewEvaluationView } from './types'

/**
 * 面试自适应（WORK-078）。
 *
 * <p><b>为什么不用完整 IRT</b>：IRT 需要"每道题对/错 + 题目难度/区分度"的逐题响应数据，我们只有
 * 一场一个的整场评分，拟合不出题目参数。这里取其**核心思想**——"按能力估计决定难度"——用
 * 历史分数的**指数加权平均**近似能力值（越近的场次权重越大），再据此给出本场重点与起始难度。
 * 简单、可解释、零依赖，也方便以后换成真正的能力估计（接口不变）。
 *
 * <p>维度名必须与后端 `ReviewPrompt.DIMENSIONS` 字面一致，否则跨场聚合会失效。
 */
export const INTERVIEW_DIMENSIONS = ['技术深度', '表达结构', '项目经验', '应变能力'] as const

export type InterviewDimensionName = (typeof INTERVIEW_DIMENSIONS)[number]
export type InterviewDifficulty = 'basic' | 'medium' | 'hard'

export interface DimensionAbility {
  name: string
  /** 1–5 的加权能力值。 */
  ability: number
  /** 参与聚合的场次数。 */
  samples: number
}

export interface AdaptiveBrief {
  /** 参与聚合的场次数。 */
  sessions: number
  /** 加权后的平均总分 1–5。 */
  overall: number
  /** 各维度能力（按能力从低到高）。 */
  dimensions: DimensionAbility[]
  /** 本场重点：能力最弱的 1–2 个维度。 */
  focus: string[]
  /** 起始难度。 */
  difficulty: InterviewDifficulty
}

/** 越往前的场次权重按此比例衰减：0.65^k（0.65 约等于"隔一场权重降到三分之二"）。 */
const DECAY = 0.65

/** 旧数据里的维度名按关键词归一到固定四维；认不出的原样保留（仍参与计算但不进重点判断）。 */
export function normalizeDimension(name: string): string {
  const trimmed = name.trim()
  if ((INTERVIEW_DIMENSIONS as readonly string[]).includes(trimmed)) {
    return trimmed
  }
  if (/技术|准确|深度|基础/.test(trimmed)) return '技术深度'
  if (/表达|清晰|结构|逻辑/.test(trimmed)) return '表达结构'
  if (/项目|经历|经验/.test(trimmed)) return '项目经验'
  if (/应变|沟通|临场|反应/.test(trimmed)) return '应变能力'
  return trimmed
}

function round1(value: number): number {
  return Math.round(value * 10) / 10
}

/**
 * 从历次评估算出能力画像。输入按**新 → 旧**（与后端 `GET /interviews/evaluations` 一致）。
 * 没有任何场次时返回 `null`——调用方据此"不注入自适应上下文"。
 */
export function computeAbility(views: InterviewEvaluationView[]): AdaptiveBrief | null {
  const usable = views.filter(
    (view) => view !== null && view !== undefined && Array.isArray(view.evaluation?.dimensions),
  )
  if (usable.length === 0) {
    return null
  }

  const buckets = new Map<string, { weighted: number; weight: number; samples: number }>()
  let overallWeighted = 0
  let overallWeight = 0

  usable.forEach((view, index) => {
    const weight = Math.pow(DECAY, index)
    overallWeighted += view.evaluation.overall * weight
    overallWeight += weight
    for (const dimension of view.evaluation.dimensions) {
      const key = normalizeDimension(dimension.name)
      const bucket = buckets.get(key) ?? { weighted: 0, weight: 0, samples: 0 }
      bucket.weighted += dimension.score * weight
      bucket.weight += weight
      bucket.samples += 1
      buckets.set(key, bucket)
    }
  })

  const dimensions = [...buckets.entries()]
    .map(([name, bucket]) => ({
      name,
      ability: round1(bucket.weighted / bucket.weight),
      samples: bucket.samples,
    }))
    .sort((left, right) => left.ability - right.ability)

  const overall = round1(overallWeighted / overallWeight)
  // 弱的定义：能力 < 3.6（离"良好"还差一档）；一个都不弱时退化为"最弱的那一个"。
  const weak = dimensions.filter((dimension) => dimension.ability < 3.6).slice(0, 2)
  const focus = (weak.length > 0 ? weak : dimensions.slice(0, 1)).map((dimension) => dimension.name)

  const difficulty: InterviewDifficulty = overall >= 3.8 ? 'hard' : overall >= 2.6 ? 'medium' : 'basic'

  return { sessions: usable.length, overall, dimensions, focus, difficulty }
}

const DIFFICULTY_LABEL: Record<InterviewDifficulty, string> = {
  basic: '基础（先补底子）',
  medium: '中等',
  hard: '偏难（可深挖）',
}

/** 给界面看的短标签："重点：项目经验、技术深度 · 难度：中等"。 */
export function difficultyLabel(difficulty: InterviewDifficulty): string {
  return DIFFICULTY_LABEL[difficulty]
}

/**
 * 拼成注入 `pageContext` 的文本（只进提示词、不落库）。简历文本由调用方追加在后面。
 *
 * @param advice 最近一场的改进建议（会作为追问角度一并给面试官）
 */
export function adaptiveContextText(brief: AdaptiveBrief, advice: string[] = []): string {
  const dimensionText = brief.dimensions.map((item) => `${item.name} ${item.ability.toFixed(1)}/5`).join('、')
  const adviceText = advice.slice(0, 3).map((line) => `- ${line}`).join('\n')
  const lines = [
    '【本场自适应设置（来自候选人过往的模拟面试）】',
    `共 ${brief.sessions} 场记录，加权平均总分 ${brief.overall.toFixed(1)}/5；各维度能力：${dimensionText}。`,
    `本场重点考察：${brief.focus.join('、')}（这几项较弱，多问、深问）。`,
    `起始难度：${DIFFICULTY_LABEL[brief.difficulty]}；之后随回答表现动态调整——答得好就加深，答不好退回基础补一补。`,
  ]
  if (adviceText !== '') {
    lines.push(`上几场给出的改进建议（可作为追问角度）：\n${adviceText}`)
  }
  return lines.join('\n')
}
