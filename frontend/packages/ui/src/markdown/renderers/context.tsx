import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/**
 * 源码解读的**页级共享状态**（WORK-014）。
 *
 * <p>为什么必须是页级而不是块内：架构地图与走读要互相驱动——点地图节点，走读跳到那一步；
 * 走读切步，地图高亮对应节点。两个块各自持有一份状态就做不到，而把状态放进全局 store
 * 又会让它跨页面残留。上下文随 `Markdown` 挂载、随页面卸载消失，正好是这个生命期。
 *
 * <p>进度不是 state：它由调用方注入一个端口（`localStorage` 或空实现），因为
 * `@paideia/ui` 不认识条目 id，也不该知道存储键。管理端预览不注入端口，块照常可用但不留痕。
 */

export interface TourProgress {
  step: number
  total: number
  done: boolean
}

export interface BlockScore {
  score: number
  total: number
}

export interface EntryProgress {
  updatedAt: string
  title?: string
  tours: Record<string, TourProgress>
  challenges: Record<string, BlockScore>
  quizzes: Record<string, BlockScore>
}

export interface ProgressPatch {
  tours?: Record<string, TourProgress>
  challenges?: Record<string, BlockScore>
  quizzes?: Record<string, BlockScore>
}

export interface ProgressPort {
  /** 不传 id 时读当前条目；传 id 用于学习路径读其他站点的状态。 */
  read(entryId?: number): EntryProgress | null
  update(patch: ProgressPatch): void
  reset(): void
}

/** 一次运行的输出（WORK-015）。形状与服务端一致；判定在客户端做。 */
export interface RunOutput {
  stdout: string
  stderr: string
  exitCode: number
  durationMs: number
  timedOut: boolean
  outOfMemory: boolean
  truncated: boolean
  /**
   * 结构化结果集（WORK-032）。**只有 SQL 会返回**，其它语言是空数组。
   *
   * <p>值一律是字符串，类型归一在判题侧按题面声明做——`DECIMAL` 的 92.50 一旦转成
   * JSON 数字就再也还不出那个尾零。
   */
  tables?: import('./sql-judge').SqlTable[]
}

/** 代码执行能力。缺这一项时「运行」按钮不渲染（管理端预览、未登录等）。 */
export interface SandboxPort {
  ready(): Promise<boolean>
  run(input: {
    language: string
    code: string
    stdin?: string
    /** SQL 专用：数据集 key，服务端按白名单取内容（WORK-032）。 */
    dataset?: string
    /** 项目模式（WORK-038）：多文件一起提交。非空时 code 只进审计。 */
    files?: { path: string; content: string }[]
    caseId?: string
  }): Promise<RunOutput>
  /**
   * 逐步录制（WORK-023）。**可选**：后端没接这条路、或后端拒绝（比如 Java 暂不支持）时，
   * 前端退回"一次性运行"，不显示播放器。
   */
  trace?(input: { language: string; code: string; stdin?: string; caseId?: string }): Promise<TraceResult>
  /**
   * Linux 判题练习（WORK-056）。**可选**：端口没实现、或后端没配 shell 镜像时，
   * 题型降级为"这一题可以改命令，但当前环境没有启用运行"。
   *
   * <p>结论由**服务端**给（`verdict`），客户端不参与判定——判定脚本不下发。
   */
  runShell?(input: {
    code: string
    judge: string
    seed?: { path: string; content: string }[]
    caseId?: string
  }): Promise<ShellOutcome>
}

/**
 * 一次判题结果（WORK-056）。
 *
 * <p>`verdict` 是稳定值，不要在前端重算：`undetermined` 与 `failed` 的区别就是
 * "你不知道"和"你做错了"，混在一起会让学习者对着一个没跑起来的容器改半小时命令。
 */
export interface ShellOutcome {
  verdict: 'passed' | 'failed' | 'undetermined'
  runExit: number
  judgeExit: number
  stdout: string
  stderr: string
  judgeStdout: string
  judgeStderr: string
  /** 文件变更（`find` 快照 diff）。空字符串表示工作区没动过。 */
  diff: string
  durationMs: number
  truncated: boolean
  /** `undetermined` 时的人话原因（超时 / 输出被截断 / 容器没回来）。 */
  reason: string | null
  usedToday: number
  dailyLimit: number
}

/** 一个录制步。`locals` 是"变量名 → 浅层 repr"，值已在服务端截断。 */
export interface TraceStep {
  line: number
  locals: Record<string, string>
  event: string
}

/** 录制结果。`reason` 说明为什么结束：done / step_limit / time_limit / error。 */
export interface TraceResult {
  steps: TraceStep[]
  output: string
  reason: string
  truncated: boolean
  durationMs: number
}

/** 由页面注入的能力。缺哪一项，对应的交互就不渲染（而不是渲染一个坏掉的按钮）。 */
/** 一次作答的上报形状（WORK-033）。对错由服务端判定，这里只有"选了第几个"。 */
export interface AttemptInput {
  blockKey: string
  questionKey: string
  choice: number
  latencyMs?: number
}

export interface DocumentContextValue {
  entryId?: number
  entryTitle?: string
  onAskAssistant?: (prompt: string) => void
  onOpenEntry?: (entryId: number) => void
  progress?: ProgressPort
  /** 逐题作答上报（WORK-033）。不传时只在本地判分，不上传。 */
  recordAttempt?: (input: AttemptInput) => void
  /** 代码执行（WORK-015）。没有它时 `run` 题型只显示代码、不显示运行按钮。 */
  sandbox?: SandboxPort
}

interface SourceReadingValue extends DocumentContextValue {
  activeStep: Record<string, number>
  setActiveStep: (tourId: string, step: number) => void
  ask?: (prompt: string) => void
  /** 解释文本复用同一个 markdown 渲染器（由 `Markdown` 注入，避免包之间循环依赖）。 */
  renderText?: (markdown: string) => ReactNode
}

const EMPTY: SourceReadingValue = {
  activeStep: {},
  setActiveStep: () => {},
}

const SourceReadingContext = createContext<SourceReadingValue | null>(null)

export function SourceReadingProvider({
  value,
  renderText,
  children,
}: {
  value?: DocumentContextValue
  renderText?: (markdown: string) => ReactNode
  children: ReactNode
}) {
  const [activeStep, setActiveStepMap] = useState<Record<string, number>>({})

  const setActiveStep = useCallback((tourId: string, step: number) => {
    setActiveStepMap((previous) => (previous[tourId] === step ? previous : { ...previous, [tourId]: step }))
  }, [])

  const merged = useMemo<SourceReadingValue>(
    () => ({
      ...value,
      activeStep,
      setActiveStep,
      ask: value?.onAskAssistant,
      renderText,
    }),
    [value, activeStep, setActiveStep, renderText],
  )

  return <SourceReadingContext.Provider value={merged}>{children}</SourceReadingContext.Provider>
}

/**
 * 读取页级上下文。没有 Provider 时返回一个空对象而不是抛错：块在 ui-kit 展览页、
 * 单测里可能被单独渲染，为"没包 Provider"而白屏不值得。
 */
export function useSourceReading(): SourceReadingValue {
  return useContext(SourceReadingContext) ?? EMPTY
}
