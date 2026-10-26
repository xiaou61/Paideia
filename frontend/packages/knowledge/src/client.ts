import type { ApiClient } from '@paideia/core'

import type {
  AssessedItem,
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

export interface KnowledgeApi {
  categories(): Promise<CategoryNode[]>
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
   * 数据集的结构快照（WORK-035）。只读、不含数据行、不占配额。
   *
   * <p>返回的是**原始 JSON 字符串**——服务端不解析它，前端拿到再 `JSON.parse`。
   * 这样两端不必维护同一份结构定义的序列化逻辑。
   */
  datasetSchema(key: string): Promise<string>
  /** 在数据集上现场出一道题（WORK-035 T-12）。期望结果集来自**真跑参考解**。 */
  generateSqlQuestion(key: string, goal?: string): Promise<GeneratedSqlQuestion>
  /** 让 AI 造一个数据集并保存为私人数据集（WORK-035 T-13）。 */
  generateSqlDataset(topic?: string): Promise<{ key: string }>
  traceCode(input: RunCodeInput): Promise<TraceCodeResult>
  /** 记一次浏览（WORK-021）。旁路数据：失败不影响阅读。 */
  recordView(entryId: number): Promise<null>
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
        headers: { 'Content-Type': 'application/json' },
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
        headers: { 'Content-Type': 'application/json' },
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
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(goal === undefined ? {} : { goal }),
        },
      ),

    /** AI 造数据集（WORK-035 T-13）：生成的 SQL 走与手动上传相同的检查。 */
    generateSqlDataset: (topic) =>
      client.requestEnvelope<{ key: string }>('/api/v1/sql-datasets/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(topic === undefined ? {} : { topic }),
      }),

    /** 逐步录制（WORK-023）。不支持的语言由后端给可读原因，前端据此降级。 */
    traceCode: (input) =>
      client.requestEnvelope<TraceCodeResult>('/api/v1/sandbox/trace', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),

    recordView: (entryId) =>
      client.requestEnvelope<null>(`/api/v1/knowledge/entries/${entryId}/view`, { method: 'POST' }),
  }
}
