import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/** 一次对话的公共行为（WORK-020 起新增 tutor）。 */
export type AssistantHarness = 'assistant' | 'interview' | 'tutor' | 'planner'

/** 悬浮壳能看到的"当前内容"。 */
export interface AssistantContextValue {
  entryId: number | null
  entryTitle: string | null
  /**
   * 这一页的助手用哪种行为。**默认通用助手**；课程条目页会设成 `tutor`（WORK-020）。
   *
   * <p>为什么放在页面而不是壳里：只有页面知道"我正在看的是不是一门课"。
   * 壳只负责把它传给建会话的请求——行为会记在会话上，恢复时不丢。
   */
  harness: AssistantHarness
  /** 只影响显示范围（把别人的公开结果折叠掉），**不是权限**：私有结果由服务端的查询拦。 */
  onlyMine: boolean
  /**
   * 页面场景（WORK-035 T-15）。**只给真正有私有草稿的页面用**。
   *
   * <p>判据不是"页面多"，是"模型自己查不到、而用户此刻正看着"。SQL 工作台的草稿与报错
   * 就是典型：没有任何工具能读到它。
   *
   * <p>**为什么单独一个字段而不是塞进 entryTitle**：`entryId` 那套是"正在读哪条知识"，
   * 语义完全不同；混用会让条目页的清理逻辑（`setEntry(null)`）把 SQL 上下文一起清掉。
   */
  pageContext: PageContext | null
}

/**
 * 学习记录页的上下文（WORK-053）。
 *
 * <p>四块都是**已经算好的数字与名字**，不是原始数据：模型要的是"他卡在哪"，不是让他自己统计。
 * 每块都按 {@link PAGE_CONTEXT_LIMITS} 截断。
 */
export interface StudyLogPageContext {
  kind: 'study-log'
  /** 各线进度：`Java 从零开始 3/37，下一关 循环与方法`。 */
  lines: string
  /** 最近没拿满分的关卡：`第 12 关 数组与字符串（挑战 1/3）`。 */
  failing: string
  /** 到期复习条目名。 */
  dueReviews: string
  /** 掌握度最低的几个概念与概率。 */
  weakConcepts: string
}

/** 页面场景。目前两种；加新的时按 kind 扩展。 */
export interface SqlWorkbenchPageContext {
  kind: 'sql-workbench'
  /** 当前选中的数据集 key。 */
  datasetKey: string
  /** 编辑器里的草稿。**已截断**（见 {@link PAGE_CONTEXT_LIMITS}）。 */
  draftSql: string
  /** 最近一次运行的错误或结果摘要。**不含数据行**。 */
  lastResult: string
}

export type PageContext = SqlWorkbenchPageContext | StudyLogPageContext

/**
 * 页面上下文的长度上限。
 *
 * <p>**必须有上限**：草稿可能几千行，结果摘要可能是一整段 stderr。整段塞进提示词既贵
 * 又可能把用户没想分享的内容送进模型。截断而不是报错——上下文是"锦上添花"，
 * 超长不该让对话失败。
 */
export const PAGE_CONTEXT_LIMITS = {
  draftSql: 2000,
  lastResult: 800,
  /** 学习记录四块各 300 字符：够放 8 条线 + 5 个卡关，又不至于把提示词挤爆。 */
  studyLogBlock: 300,
} as const

/** 按上限截断，并在尾部标明"还有多少没显示"。 */
export function truncateContext(text: string, limit: number): string {
  if (text.length <= limit) {
    return text
  }
  return `${text.slice(0, limit)}\n…（后面还有 ${text.length - limit} 个字符没显示）`
}

/**
 * 一次"预填"请求（WORK-014）：源码解读的「问 AI」把一句话送进输入框。
 *
 * <p>**它不是一条待发送的消息**——`ask()` 只写这里，真正的发送仍然要用户点。
 * 带 `id` 是因为同一句话可能被连点两次：消费方按 id 清一次，避免把后面的新请求也清掉。
 */
export interface PendingPrompt {
  id: number
  text: string
}

interface AssistantContextApi extends AssistantContextValue {
  setEntry: (entry: { id: number; title: string; harness?: AssistantHarness } | null) => void
  setOnlyMine: (onlyMine: boolean) => void
  /** 设置/清除页面场景。传 null 清除（离开页面时调）。 */
  setPageContext: (context: PageContext | null) => void
  pendingPrompt: PendingPrompt | null
  /** 打开助手并把这句话填进输入框；**不发送**。 */
  ask: (text: string) => void
  clearPendingPrompt: (id: number) => void
  /**
   * 助手建议的 SQL 填回编辑器（WORK-035 T-15）。与 `ask` 同形：写一个待消费对象，
   * 页面消费一次就清。**不是工具**——助手只是正常回答，按钮是前端的渲染能力。
   */
  pendingSql: PendingPrompt | null
  applySql: (text: string) => void
  clearPendingSql: (id: number) => void
}

const AssistantContext = createContext<AssistantContextApi | null>(null)

/**
 * 当前内容的提供者。
 *
 * <p>为什么要一个上下文而不是让壳自己去读路由：条目页是唯一知道"当前在读哪一条"的地方
 * （它已经在加载详情了）。壳只需要知道 id 与标题，页面把它交出来即可——壳不必了解路由结构，
 * 将来换路由方案也不用改壳。
 *
 * <p>`onlyMine` 存在这里而不是服务端：它只是**显示偏好**，收窄自己能看的东西，
 * 不构成任何权限——私有结果在服务端就查不出来。
 */
export function AssistantProvider({ children }: { children: ReactNode }) {
  const [entryId, setEntryId] = useState<number | null>(null)
  const [entryTitle, setEntryTitle] = useState<string | null>(null)
  const [harness, setHarness] = useState<AssistantHarness>('assistant')
  const [onlyMine, setOnlyMine] = useState(false)
  const [pageContext, setPageContext] = useState<PageContext | null>(null)
  const [pendingPrompt, setPendingPrompt] = useState<PendingPrompt | null>(null)
  const [pendingSql, setPendingSql] = useState<PendingPrompt | null>(null)

  // setEntry 必须**稳定**：调用方（条目页）把它放进 useEffect 的依赖里，
  // 每次渲染都换一个新函数会让那个 effect 反复跑。
  const setEntry = useCallback((entry: { id: number; title: string; harness?: AssistantHarness } | null) => {
    setEntryId(entry === null ? null : entry.id)
    setEntryTitle(entry === null ? null : entry.title)
    // 离开条目页时回到通用助手：否则"上一次看的是课程"会让之后每个页面的助手都用导师口径
    setHarness(entry === null ? 'assistant' : entry.harness ?? 'assistant')
  }, [])

  const ask = useCallback((text: string) => {
    const trimmed = text.trim()
    if (trimmed === '') {
      return
    }
    setPendingPrompt({ id: Date.now(), text: trimmed })
  }, [])

  const clearPendingPrompt = useCallback((id: number) => {
    setPendingPrompt((current) => (current?.id === id ? null : current))
  }, [])

  const applySql = useCallback((text: string) => {
    const trimmed = text.trim()
    if (trimmed === '') {
      return
    }
    setPendingSql({ id: Date.now(), text: trimmed })
  }, [])

  const clearPendingSql = useCallback((id: number) => {
    setPendingSql((current) => (current?.id === id ? null : current))
  }, [])

  const value = useMemo<AssistantContextApi>(
    () => ({
      entryId,
      entryTitle,
      harness,
      onlyMine,
      pageContext,
      setEntry,
      setOnlyMine,
      setPageContext,
      pendingPrompt,
      ask,
      clearPendingPrompt,
      pendingSql,
      applySql,
      clearPendingSql,
    }),
    [
      entryId,
      entryTitle,
      harness,
      onlyMine,
      pageContext,
      setEntry,
      pendingPrompt,
      ask,
      clearPendingPrompt,
      pendingSql,
      applySql,
      clearPendingSql,
    ],
  )

  return <AssistantContext.Provider value={value}>{children}</AssistantContext.Provider>
}

export function useAssistantContext(): AssistantContextApi {
  const value = useContext(AssistantContext)
  if (value === null) {
    throw new Error('useAssistantContext 必须在 AssistantProvider 内使用')
  }
  return value
}
