/**
 * 岗位 → 轨道 的定位表（WORK-047）。
 *
 * <p><b>为什么是代码常量而不是一张表</b>：这张映射是编辑判断，没有用户数据可调参。
 * 建表只会多一个要维护、要同步、还要写管理端界面的载体。真到了要按数据调的那天，
 * 迁移是一次性的；在那之前，改映射发个版就够了。与身份配置（WORK-010）同一条理由。
 *
 * <p><b>兜底是刻意的</b>：不在任何岗位显式列表里的轨道一律算「选读」。
 * 新增一条轨道却忘了标岗位时，它不会从所有岗位里消失，只是排在选读的最后。
 */

export type RoleKey = 'backend' | 'ai-algo' | 'data' | 'frontend'

/** 一条线在某个岗位里的定位。 */
export type TrackTier = 'core' | 'advanced' | 'optional'

export interface RoleMeta {
  key: RoleKey
  label: string
  /** 一句话说明这个岗位主要看什么。 */
  blurb: string
  /**
   * 这个岗位有没有内容支撑。`false` 时页面显示"内容建设中"，
   * 而不是给一张空地图——空地图会让人以为平台就这么点东西。
   */
  available: boolean
}

export const ROLES: readonly RoleMeta[] = [
  {
    key: 'backend',
    label: '后端开发',
    blurb: '写服务、扛流量、排查线上问题的那一类岗位。',
    available: true,
  },
  {
    key: 'ai-algo',
    label: '算法与 AI',
    blurb: '做模型、做智能体、也做数据结构的题。',
    available: true,
  },
  {
    key: 'data',
    label: '数据开发',
    blurb: '写 SQL、管数据仓库、对口径负责。',
    available: true,
  },
  {
    key: 'frontend',
    label: '前端开发',
    blurb: '做界面与交互。这个岗位的内容还在建设中。',
    available: false,
  },
]

/** 默认岗位：线上内容最厚的一档，与现有 22 条线的分布一致。 */
export const DEFAULT_ROLE: RoleKey = 'backend'

/**
 * 显式定位表。**没列出的 slug 一律按 optional 兜底**，不要为了"列全"把每条都抄一遍——
 * 那样每加一条线都要改四个岗位，漏一处就是一处静默的错。
 */
const TIERS: Record<RoleKey, { core: readonly string[]; advanced: readonly string[] }> = {
  backend: {
    core: ['java-from-zero', 'spring', 'redis', 'mysql-from-zero', 'network-basics'],
    advanced: ['concurrency', 'jvm', 'system-design', 'os'],
  },
  'ai-algo': {
    core: ['algorithms', 'python-from-zero', 'ai'],
    advanced: ['patterns', 'agents', 'system-design'],
  },
  data: {
    core: ['sql-practice', 'mysql-from-zero'],
    advanced: ['redis', 'system-design', 'algorithms'],
  },
  // 前端岗位目前**没有任何内容**（22 条线里 slug 含 front/react/css 的为 0）。
  // 留空不是占位，是如实反映现状；页面对 available=false 有专门的处理。
  frontend: { core: [], advanced: [] },
}

export function roleMeta(role: RoleKey): RoleMeta {
  // ROLES 是非空常量表，`[0]` 一定存在；`!` 只是绕过 noUncheckedIndexedAccess
  return ROLES.find((item) => item.key === role) ?? ROLES[0]!
}

/** 同段内的先后由数组顺序决定：越靠前越该先看。未列出的一律「选读」且排最后。 */
export function tierOf(role: RoleKey, slug: string): { tier: TrackTier; rank: number } {
  const table = TIERS[role] ?? TIERS[DEFAULT_ROLE]
  const coreIndex = table.core.indexOf(slug)
  if (coreIndex >= 0) {
    return { tier: 'core', rank: coreIndex }
  }
  const advancedIndex = table.advanced.indexOf(slug)
  if (advancedIndex >= 0) {
    return { tier: 'advanced', rank: advancedIndex }
  }
  return { tier: 'optional', rank: Number.MAX_SAFE_INTEGER }
}

/** 把任意字符串收敛成合法岗位。资料里存的是自由文本，不能直接当 RoleKey 用。 */
export function normalizeRole(value: string | null | undefined): RoleKey {
  return ROLES.some((role) => role.key === value) ? (value as RoleKey) : DEFAULT_ROLE
}
