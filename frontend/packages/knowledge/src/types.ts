/** 知识库的对外数据形状，与后端 DTO 一一对应（字段名一致，避免中间映射层）。 */

export interface CategoryNode {
  id: number
  name: string
  slug: string
  /** 非空表示这是一条学习线（`course` / `reading`）；为 null 时后端可能整个字段都不出现。 */
  lineKind?: string | null
  children: CategoryNode[]
}

/** 与后端 `PageResult` 同构。 */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  items: T[]
}

/** 列表项：**不含正文**（后端列表查询刻意不取 body），但带一句截断的摘要。 */
export interface EntrySummary {
  id: number
  title: string
  categoryId: number | null
  categoryName: string | null
  /** 正文前 200 字（未清洗）；可能为 null（老数据或正文为空）。 */
  excerpt: string | null
  /**
   * 作者声明的站序（WORK-046）。列表本身按发布时间倒序返回，
   * 书籍章节页要"按书里的顺序"读，靠这个字段在客户端重排。手工建的条目可能为 null。
   */
  sortOrder?: number | null
  updatedAt: string
}

/** 关系与相邻条目用的最小引用。 */
export interface EntryRef {
  id: number
  title: string
}

/** 学习者自评的两个状态。刻意只有两个：不做等级、不做权重、不做基于作答的推断。 */
export type SelfAssessmentLevel = 'understood' | 'unsure'

/** 「我标记过的条目」列表行。 */
export interface AssessedItem {
  entryId: number
  title: string
  level: SelfAssessmentLevel
  updatedAt: string
}

export interface EntryDetail {
  id: number
  title: string
  body: string
  categoryId: number | null
  categoryName: string | null
  /** 原始来源链接（WORK-025）。自动采集的「面经真题」有值，平台自产内容与存量条目为 null。 */
  sourceUrl?: string | null
  publishedAt: string | null
  previous: EntryRef | null
  next: EntryRef | null
  /** 本条目依赖的前置。 */
  prerequisites: EntryRef[]
  /** 与本条目相关的内容（无向关系，两端的边都算）。 */
  related: EntryRef[]
  /** 反向链接：把本条目当作前置的条目。 */
  dependents: EntryRef[]
  /** **当前请求者自己**的标记；没有标记时为 null。别人的标记永不返回。 */
  selfAssessment: SelfAssessmentLevel | null
}
