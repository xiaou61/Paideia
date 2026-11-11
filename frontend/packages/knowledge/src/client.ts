import type { ApiClient } from '@paideia/core'
import { JSON_HEADERS } from '@paideia/core'

import type {
  AssessedItem,
  CategoryCount,
  CategoryNode,
  EntryDetail,
  EntrySummary,
  PageResult,
  SelfAssessmentLevel,
} from './types'

export interface ListEntriesOptions {
  /** 传父分类时后端会**包含其后代分类**的条目。 */
  categoryId?: number | null
  /** 检索串；长度不足 ngram 分词长度时后端自动退回 LIKE。 */
  q?: string
  page?: number
  size?: number
}

/**
 * 取**全部**匹配条目（内部循环翻页，2026-11-10）。
 *
 * <p>为什么需要：目录阅读页要一次列出整库并按分类树渲染，而列表接口单页有上限。
 * 原来那边写死 `size: 200`——线上已发布 571 条，于是目录里**静默少了三分之二**，
 * 既不报错也没有任何提示。凡是"必须完整"的场合都该用它，而不是随手给个大数。
 *
 * <p>终止条件用 `total` 而不是"某页为空"：后端总数与分页同源，前者能在最后一页
 * 刚好取满时少发一次请求；两者都留着，任何一边异常都不会死循环。
 */
export async function fetchAllEntries(
  api: KnowledgeApi,
  options: Omit<ListEntriesOptions, 'page' | 'size'> = {},
  pageSize = 200,
): Promise<EntrySummary[]> {
  const first = await api.entries({ ...options, page: 1, size: pageSize })
  const items = [...first.items]
  let page = 2
  while (items.length < first.total) {
    const next = await api.entries({ ...options, page, size: pageSize })
    if (next.items.length === 0) {
      break
    }
    items.push(...next.items)
    page += 1
  }
  return items
}

export interface KnowledgeApi {
  categories(): Promise<CategoryNode[]>
  /**
   * 若干分类下已发布条目数（2026-11-09）。
   *
   * <p>给首页「面经」四个方向显示条数：一次请求拿完，不在前端按分类各打一次列表接口。
   * 后端按入参顺序返回、查不到的分类补 0。
   */
  categoryCounts(ids: number[]): Promise<CategoryCount[]>
  entries(options?: ListEntriesOptions): Promise<PageResult<EntrySummary>>
  entry(id: number): Promise<EntryDetail>
  /** 标记「懂了／还不懂」。同一用户重复标记是更新，不会产生第二条。 */
  markSelfAssessment(entryId: number, level: SelfAssessmentLevel): Promise<null>
  /** 取消标记。幂等：本来就没有也返回成功。 */
  clearSelfAssessment(entryId: number): Promise<null>
  /** 我标记过的条目。**只可能是自己的**。 */
  myAssessments(level?: SelfAssessmentLevel): Promise<AssessedItem[]>
  /** 代码执行能力与今日用量（WORK-015）。不可用时 `ready=false`，界面据此不显示「运行」。 */
  sandboxStatus(): Promise<SandboxStatus>
  /** 执行一次练习代码。**服务端只回实际输出，判定在客户端**。 */
  runCode(input: RunCodeInput): Promise<RunCodeResult>
  /**
   * Linux 判题练习（WORK-056）。**结论由服务端给**——判定脚本不下发到客户端，
   * 因为它要判的是最终状态（权限位、文件内容），发下来等于把答案一起发下来。
   */
  runShell(input: ShellRunInput): Promise<ShellRunResult>
  /**
   * 数据集的结构快照（WORK-035）。只读、不含数据行、不占配额。
   *
   * <p>返回的是**原始 JSON 字符串**——服务端不解析它，前端拿到再 `JSON.parse`。
   * 这样两端不必维护同一份结构定义的序列化逻辑。
   */
  datasetSchema(key: string): Promise<string>
  /** 在数据集上现场出一道题（WORK-035 T-12）。期望结果集来自**真跑参考解**。 */
  generateSqlQuestion(key: string, goal?: string): Promise<GeneratedSqlQuestion>
  /** 保存一段 SQL 为数据集（WORK-053 / WORK-060）。可见性默认私有。 */
  uploadSqlDataset(name: string, sql: string, visibility?: DatasetVisibility): Promise<{ key: string }>

  /** AI 造数据集并保存（WORK-035 T-13）；可见性由用户在保存前选择。 */
  generateSqlDataset(topic?: string, visibility?: DatasetVisibility): Promise<{ key: string }>
  /** 只生成不保存（WORK-065）：向导把 SQL 填进「写 SQL」，由用户改完再保存。 */
  draftSqlDataset(topic: string): Promise<{ name: string; sql: string }>

  /** 数据集目录（WORK-060）：内置公开集 + 我的私人集 + 已发布社区集。 */
  datasetCatalog(): Promise<DatasetCatalogItem[]>

  /** 作者切换自己的数据集公开/私有（WORK-060）。 */
  setDatasetVisibility(key: string, visibility: DatasetVisibility): Promise<null>

  traceCode(input: RunCodeInput): Promise<TraceCodeResult>
  /** 记一次浏览（WORK-021）。旁路数据：失败不影响阅读。 */
  recordView(entryId: number): Promise<null>
  /**
   * 我「看过」的条目（2026-11-11）。列表页用来把已读行标出来、并算每类「已读 x / y」。
   *
   * <p>与 `recordView` 是一对：那边写、这边读，数据都在服务端，所以换设备/清缓存都不丢。
   */
  viewedEntries(): Promise<ViewedEntry[]>
}

/** 「我看过的一条」。只有 id、次数、最近时间——标题列表自己已经有。 */
export interface ViewedEntry {
  entryId: number
  viewCount: number
  lastViewedAt: string
}

/** 数据集可见性（WORK-060）。 */
export type DatasetVisibility = 'private' | 'public'

/** 目录的一行。**不含 schema、SQL 与数据行**。 */
export interface DatasetCatalogItem {
  key: string
  name: string
  visibility: DatasetVisibility
  source: 'builtin' | 'private' | 'community'
  ownerUserId: number | null
  ownerName: string
  tableCount: number
  rowCount: number
  mine: boolean
}

/** AI 生成的一道题。**期望结果集随题下发**，判题在客户端。 */
export interface GeneratedSqlQuestion {
  stem: string
  criteria: string
  explain: string
  columns: string[]
  rows: string[][]
}

/** 沙箱能力。`reason` 在不可用时说明原因（未开启 / Docker 不在 / 镜像缺失）。 */
export interface SandboxStatus {
  enabled: boolean
  ready: boolean
  runtime: string | null
  languages: string[]
  dailyLimit: number
  usedToday: number
  /** SQL 单独一档配额（WORK-032）：额度不与 Java/Python 共用。 */
  sqlDailyLimit?: number
  sqlUsedToday?: number
  /**
   * 可选数据集（SQL，WORK-035 起是对象而不是 key 字符串）。
   *
   * <p>`source` 让选择器能标出"内置 / 我的 / 社区"；隐藏集不在这个列表里。
   */
  datasets?: Array<{ key: string; name: string; source: string }>
  reason: string | null
}

export interface RunCodeInput {
  language: string
  code: string
  stdin?: string
  /** SQL 专用：数据集 key，服务端按白名单取内容（WORK-032）。 */
  dataset?: string
  /**
   * SQL 专用（WORK-035）：为真时跑 `EXPLAIN FORMAT=TREE` 而不是执行语句。
   *
   * <p>**写操作在计划模式下不会真的落数据**——它不是"跑一遍看看"，是"看看会怎么跑"。
   */
  explain?: boolean
  /**
   * 项目模式（WORK-038）：多文件一起提交，服务端编译后跑固定的测试入口。
   * 非空时 `code` 只进审计，不参与执行。
   */
  files?: { path: string; content: string }[]
  entryId?: number
  caseId?: string
}

/** 判题练习的提交（WORK-056）。`judge` 是题目自带的判定脚本，只在容器里跑。 */
export interface ShellRunInput {
  code: string
  judge: string
  /** 题目声明的初始文件。路径由服务端再校验一次。 */
  seed?: { path: string; content: string }[]
  entryId?: number
  caseId?: string
}

/**
 * 判题结果（WORK-056）。
 *
 * <p>形状与 `@paideia/ui` 的 `ShellOutcome` 一致；这里独立声明是为了让本包不依赖 UI 包的类型
 * 也能被后端契约测试引用。多出来的两个字段是**用量**，用来在按钮旁显示"今天还剩几次"。
 */
export interface ShellRunResult {
  verdict: 'passed' | 'failed' | 'undetermined'
  runExit: number
  judgeExit: number
  stdout: string
  stderr: string
  judgeStdout: string
  judgeStderr: string
  diff: string
  durationMs: number
  truncated: boolean
  reason: string | null
  usedToday: number
  dailyLimit: number
}

/**
 * 一次运行的结果。
 *
 * <p>`timedOut` / `outOfMemory` 是**结果**不是错误：程序崩了也是有价值的反馈。
 * `truncated` 表示输出被截断（服务端上限 64KB）。
 */
export interface RunCodeResult {
  stdout: string
  stderr: string
  exitCode: number
  durationMs: number
  timedOut: boolean
  outOfMemory: boolean
  truncated: boolean
  /** 结构化结果集（WORK-032）。只有 SQL 会返回；值一律是字符串。 */
  tables?: { statementIndex: number; columns: string[]; rows: string[][] }[]
}

/**
 * 逐步录制的结果（WORK-023）。
 *
 * <p>`reason` 说明为什么停：`done` / `step_limit` / `time_limit` / `error`。
 * `truncated` 为真时"只录到这里"，不是失败——那正是设计的形态。
 */
export interface TraceCodeResult {
  steps: { line: number; locals: Record<string, string>; event: string }[]
  output: string
  reason: string
  truncated: boolean
  durationMs: number
}

/**
 * 知识库端点客户端。
 *
 * <p>只包一层路径与查询串拼装，鉴权、401 刷新重放都由传入的 `ApiClient`（装配自
 * `packages/auth`）负责——本包不认识令牌。
 */
export function createKnowledgeApi(client: ApiClient): KnowledgeApi {
  return {
    categories: () => client.requestEnvelope<CategoryNode[]>('/api/v1/knowledge/categories'),

    categoryCounts: (ids) => {
      // 空数组直接返回空：后端对空 ids 会返回全 0，但没必要为了一个空结果打一次请求
      if (ids.length === 0) {
        return Promise.resolve([])
      }
      const params = new URLSearchParams()
      for (const id of ids) {
        params.append('ids', String(id))
      }
      return client.requestEnvelope<CategoryCount[]>(
        `/api/v1/knowledge/categories/counts?${params.toString()}`,
      )
    },

    entries: (options = {}) => {
      const params = new URLSearchParams()
      if (options.categoryId !== undefined && options.categoryId !== null) {
        params.set('categoryId', String(options.categoryId))
      }
      const keyword = options.q?.trim() ?? ''
      if (keyword !== '') {
        params.set('q', keyword)
      }
      if (options.page !== undefined) {
        params.set('page', String(options.page))
      }
      if (options.size !== undefined) {
        params.set('size', String(options.size))
      }
      const query = params.toString()
      return client.requestEnvelope<PageResult<EntrySummary>>(
        `/api/v1/knowledge/entries${query === '' ? '' : `?${query}`}`,
      )
    },

    entry: (id) => client.requestEnvelope<EntryDetail>(`/api/v1/knowledge/entries/${id}`),

    markSelfAssessment: (entryId, level) =>
      client.requestEnvelope<null>(`/api/v1/knowledge/entries/${entryId}/self-assessment`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify({ level }),
      }),

    clearSelfAssessment: (entryId) =>
      client.requestEnvelope<null>(`/api/v1/knowledge/entries/${entryId}/self-assessment`, {
        method: 'DELETE',
      }),

    myAssessments: (level) =>
      client.requestEnvelope<AssessedItem[]>(
        `/api/v1/knowledge/self-assessments${level === undefined ? '' : `?level=${level}`}`,
      ),

    sandboxStatus: () => client.requestEnvelope<SandboxStatus>('/api/v1/sandbox/status'),

    runCode: (input) =>
      client.requestEnvelope<RunCodeResult>('/api/v1/sandbox/run', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    /** Linux 判题（WORK-056）：与 runCode 同一套配额与并发，判定由服务端给。 */
    runShell: (input) =>
      client.requestEnvelope<ShellRunResult>('/api/v1/sandbox/shell/run', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    /** 数据集结构（WORK-035 T-03）：只读，不占配额。 */
    datasetSchema: (key) =>
      client.requestEnvelope<string>(`/api/v1/sandbox/datasets/${encodeURIComponent(key)}/schema`),

    /** AI 现场出题（WORK-035 T-12）。它会在沙箱里真跑一次参考解。 */
    generateSqlQuestion: (key, goal) =>
      client.requestEnvelope<GeneratedSqlQuestion>(
        `/api/v1/sql-datasets/${encodeURIComponent(key)}/question`,
        {
          method: 'POST',
          headers: JSON_HEADERS,
          body: JSON.stringify(goal === undefined ? {} : { goal }),
        },
      ),

    /** 保存一段 SQL 为自己的数据集（WORK-053 / WORK-060）：与手动上传同一条后端校验路径。 */
    uploadSqlDataset: (name, sql, visibility = 'private') =>
      client.requestEnvelope<{ key: string }>('/api/v1/sql-datasets', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ name, sql, visibility }),
      }),

    /** AI 造数据集（WORK-035 T-13）：生成的 SQL 走与手动上传相同的检查。 */
    generateSqlDataset: (topic, visibility = 'private') =>
      client.requestEnvelope<{ key: string }>('/api/v1/sql-datasets/generate', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ ...(topic === undefined ? {} : { topic }), visibility }),
      }),

    /** 只生成不保存（WORK-065）。 */
    draftSqlDataset: (topic) =>
      client.requestEnvelope<{ name: string; sql: string }>('/api/v1/sql-datasets/draft', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ topic }),
      }),

    /** 数据集目录（WORK-060）。 */
    datasetCatalog: () =>
      client.requestEnvelope<DatasetCatalogItem[]>('/api/v1/sql-datasets/catalog'),

    /** 作者切换自己的数据集公开/私有（WORK-060）。 */
    setDatasetVisibility: (key, visibility) =>
      client.requestEnvelope<null>(`/api/v1/sql-datasets/${encodeURIComponent(key)}/visibility`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({ visibility }),
      }),

    /** 逐步录制（WORK-023）。不支持的语言由后端给可读原因，前端据此降级。 */
    traceCode: (input) =>
      client.requestEnvelope<TraceCodeResult>('/api/v1/sandbox/trace', {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),

    recordView: (entryId) =>
      client.requestEnvelope<null>(`/api/v1/knowledge/entries/${entryId}/view`, { method: 'POST' }),

    viewedEntries: () => client.requestEnvelope<ViewedEntry[]>('/api/v1/knowledge/entry-views'),
  }
}
