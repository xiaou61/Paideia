import type { CategoryNode } from '@paideia/knowledge'

/**
 * 面经（WORK-025）的四个方向，以及「从分类树里把它们找出来」这件事。
 *
 * <p><b>为什么单独一份</b>：这套 slug → 中文短名与顺序原来只写在首页里（`HomePage` 的
 * `ESSAY_LABELS` 与那段 `order`）。2026-11-10 加 `/essay` 面经页之后，两个页面都要同一份，
 * 抄第二遍就会在下次改方向名时只改一处。
 *
 * <p><b>为什么按 slug 找、不写死 id</b>：生产库里 essay-java 是 20（后被 devdata 挪到 900），
 * 全新测试库上是 50——写死任何一个都会在另一侧指向空分类。找的是"分类树上挂着这个 slug 的节点"。
 *
 * <p>顺序固定为 后端 → 前端 → 测试 → 数据（按岗位热度），与抓取源清单
 * （`application.yml` 的 `paideia.ai.essay.sources`）一致。
 */
export interface EssayDirectionSpec {
  slug: string
  /** 界面上的短名：分类全名（「八股文 · 前端面经」）太长，卡片与 chip 里放不下。 */
  label: string
}

export const ESSAY_DIRECTIONS: readonly EssayDirectionSpec[] = [
  { slug: 'essay-java', label: 'Java 后端' },
  { slug: 'essay-frontend', label: '前端' },
  { slug: 'essay-testing', label: '测试' },
  { slug: 'essay-data', label: '数据' },
]

/** slug → 短名。首页横幅的四个 chip 用它。 */
export const ESSAY_LABELS: Record<string, string> = Object.fromEntries(
  ESSAY_DIRECTIONS.map((direction) => [direction.slug, direction.label]),
)

/** 已经找到分类的方向：`category` 是它在分类树上的节点（`id` 用来拼分类页地址）。 */
export interface EssayDirection extends EssayDirectionSpec {
  category: CategoryNode
}

/**
 * 从分类树里取出四个方向。**找不到的方向直接不出现**，顺序与 `ESSAY_DIRECTIONS` 一致。
 *
 * <p>缺一个不是错误：抓取任务默认关闭，分类也可能是后来才建的。少一个方向时页面照样能用，
 * 总比整页报错或者指向一个空分类强。
 */
export function essayDirections(nodes: CategoryNode[]): EssayDirection[] {
  const found = new Map<string, CategoryNode>()
  const walk = (list: CategoryNode[]) => {
    for (const node of list) {
      found.set(node.slug, node)
      walk(node.children)
    }
  }
  walk(nodes)

  return ESSAY_DIRECTIONS.flatMap((spec) => {
    const category = found.get(spec.slug)
    return category === undefined ? [] : [{ ...spec, category }]
  })
}
