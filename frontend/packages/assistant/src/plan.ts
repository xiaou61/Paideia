/**
 * 助手回复里的「计划候选」（WORK-053 REQ-006）。
 *
 * <p>模型被要求在给"今天做什么"时输出一个 ```plan 围栏，内容是 JSON 数组：
 * `[{"date":"2026-11-02","goal":"…","reason":"…"}]`。这里只做**解析与校验**：
 * 日期格式、必填字段、长度上限。**任何一条不合格就整块不采用**——宁可让用户看到原始文本，
 * 也不要展示一张点不动的卡片。
 */
export interface PlanSuggestion {
  /** `yyyy-MM-dd`（模型按提示词里的"今天"写） */
  date: string
  goal: string
  reason: string
}

const BLOCK = /```plan\s*\n([\s\S]*?)```/g
const DATE = /^\d{4}-\d{2}-\d{2}$/
const MAX_GOAL = 200
const MAX_REASON = 300
const MAX_ITEMS = 3

/** 取出候选，并返回去掉**合法**计划块之后的文本（给 markdown 渲染）。 */
export function extractPlanSuggestions(text: string): { suggestions: PlanSuggestion[]; text: string } {
  const suggestions: PlanSuggestion[] = []
  const cleaned = text.replace(BLOCK, (match, body: string) => {
    const parsed = parse(body)
    if (parsed === null) {
      return match
    }
    suggestions.push(...parsed)
    return ''
  })
  return { suggestions: suggestions.slice(0, MAX_ITEMS), text: cleaned.trim() }
}

/** 只去掉合法的计划块；解析失败的原样留着（不吞模型输出）。 */
export function stripPlanBlocks(text: string): string {
  return extractPlanSuggestions(text).text
}

function parse(body: string): PlanSuggestion[] | null {
  let raw: unknown
  try {
    raw = JSON.parse(body)
  } catch {
    return null
  }
  if (!Array.isArray(raw) || raw.length === 0) {
    return null
  }
  const items: PlanSuggestion[] = []
  for (const entry of raw) {
    if (entry === null || typeof entry !== 'object') {
      return null
    }
    const item = entry as Record<string, unknown>
    const date = String(item.date ?? '')
    const goal = String(item.goal ?? '').trim()
    const reason = String(item.reason ?? '').trim()
    if (!DATE.test(date) || goal === '' || goal.length > MAX_GOAL || reason.length > MAX_REASON) {
      return null
    }
    items.push({ date, goal, reason })
  }
  return items
}
