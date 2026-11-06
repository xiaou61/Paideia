import { createIdentityApi, configFor, type LearnerProfile } from '@paideia/identity'
import { type AssistantApi, type InterviewEvaluationView } from '@paideia/assistant'
import {
  createResumeApi,
  type ResumeData,
  type ResumeDocumentSummary,
} from '@paideia/resume'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  PageHeader,
  Separator,
  Spinner,
} from '@paideia/ui'
import { useBlocker } from 'react-router'
import {
  ArrowRight,
  Bot,
  Check,
  ChevronDown,
  Lightbulb,
  MessageSquareText,
  Send,
  Sparkles,
  Target,
  TrendingUp,
  Upload,
  User,
} from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import { api } from '../api'
import { createAssistantApi } from '@paideia/assistant'
import { LearnerHeader } from '../components/LearnerHeader'
import { errorMessage } from '@paideia/core'

const identity = createIdentityApi(api)
const assistant: AssistantApi = createAssistantApi(api)
const resumesApi = createResumeApi(api)

/** 半场对话里的一条。面试页自己维护，不复用助手的会话面板（那里面向"随手问一句"）。 */
interface Line {
  role: 'interviewer' | 'candidate'
  content: string
}

/** 开场用哪份简历：不使用 / 当场上传的 / 某份已有简历的 id（WORK-077）。 */
type ResumeChoice = 'none' | 'upload' | number

/**
 * 模拟面试（WORK-010 T-09 / T-11；WORK-075 视觉重做）。
 *
 * <p><b>行为由会话决定，不由页面决定</b>：会话建的时候带 `harness=interview`，之后即使从通用助手
 * 继续这段对话，说话的还是面试官。页面只是"发起这段会话的地方"。
 *
 * <p><b>笔记默认不参与</b>：本页**不把任何笔记内容送给上游**。用户在开关上勾选后，才把
 * （他自己写的）笔记正文拼进第一条消息。这是"私有数据离开本机要用户自己点"的保守默认
 * （design §5.1）。一期先只做提示语与勾选状态，不真的读取笔记接口——那需要笔记 API 的
 * 批量读，属后续；眼下把"默认不送"这条做成界面上的**明示**。
 *
 * <p><b>评分是独立的一次调用</b>：点「生成点评」才发，返回结构化评分并落库。
 *
 * <p><b>思考过程不展示（WORK-075）</b>：模型每次的 `thinking` 事件一律丢弃，不再渲染成
 * 折叠框。等待回答时改在面试官气泡位置显示"正在思考…"的转圈指示器——那是"现场感"，
 * 把推理噪音糊在页面上只是干扰。
 */
export function InterviewPage() {
  const { data: profile } = useQuery({ queryKey: ['profile'], queryFn: () => identity.profile(), retry: false })
  const config = configFor(profile?.identity)

  /**
   * 历次评估（`GET /ai/interviews/evaluations`）。
   *
   * <p>这条接口从 WORK-010 就在，但一直没有页面用它——一场面试结束、评分落库，
   * 用户下次进来看到的还是"开始面试"，等于把最该看的东西丢了。
   * 刷新时机跟着 `reviewing` 走：刚生成的点评要立刻出现在列表里。
   */
  const history = useQuery({
    queryKey: ['interview-evaluations'],
    queryFn: () => assistant.interviewEvaluations(),
    retry: false,
  })

  const [conversationId, setConversationId] = useState<number | null>(null)
  const [lines, setLines] = useState<Line[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [evaluation, setEvaluation] = useState<InterviewEvaluationView | null>(null)
  const [reviewing, setReviewing] = useState(false)
  /** 一次面试只要建过会话，就可能已经落库了消息——这里只用来控制按钮的可用性。 */
  const started = conversationId !== null

  /** 我的简历列表（WORK-077）：开场可选一份来出题，也可以当场上传。 */
  const resumeList = useQuery({
    queryKey: ['resumes'],
    queryFn: () => resumesApi.list(),
    retry: false,
  })
  const [resumeChoice, setResumeChoice] = useState<ResumeChoice>('none')
  const [uploadedName, setUploadedName] = useState<string | undefined>(undefined)
  const [uploadedText, setUploadedText] = useState<string | undefined>(undefined)
  const [uploading, setUploading] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [resumeError, setResumeError] = useState<string | null>(null)
  const resumeInitialized = useRef(false)

  /** 首次拿到列表时预选「默认简历」（没有默认就选第一份）；用户手动改过就不再覆盖。 */
  useEffect(() => {
    if (resumeInitialized.current) {
      return
    }
    const list = resumeList.data
    if (list !== undefined) {
      resumeInitialized.current = true
      const preferred = list.find((item) => item.isDefault) ?? list[0]
      if (preferred !== undefined) {
        setResumeChoice(preferred.id)
      }
    }
  }, [resumeList.data])

  /** 开面二次确认：明确告知会消耗积分（每轮对话与生成点评都计费）。 */
  const [confirmStartOpen, setConfirmStartOpen] = useState(false)
  /** 「结束面试」二次确认：结束后这场不能继续、也不能重进。 */
  const [endConfirmOpen, setEndConfirmOpen] = useState(false)

  /** 结束本场：退回到未开始状态（对话不再恢复）。 */
  const endInterview = () => {
    abortRef.current?.abort()
    setEndConfirmOpen(false)
    setConversationId(null)
    setLines([])
    setEvaluation(null)
    setInput('')
    setError(null)
  }

  /**
   * 未出报告就离开 = 这场白聊：站内跳转要拦一下确认，关页/刷新也要提示。
   * 出了报告（evaluation 非空）就不再拦——报告已落库，历史里能看。
   */
  const blockLeave = started && evaluation === null
  const blocker = useBlocker(blockLeave)
  useEffect(() => {
    if (!blockLeave) {
      return
    }
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => { window.removeEventListener('beforeunload', handler) }
  }, [blockLeave])

  /** 当前这轮在飞的请求；离开页面时中断它（与助手那条同一纪律：不能让上游继续烧钱）。 */
  const abortRef = useRef<AbortController | null>(null)
  useEffect(() => () => {
    abortRef.current?.abort()
  }, [])

  /** 消息区滚到底：新消息进来、或正在等待时，最新那句要看得见。 */
  const bodyRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    const element = bodyRef.current
    if (element !== null) {
      element.scrollTop = element.scrollHeight
    }
  }, [lines, busy])

  const send = useCallback(async (text: string, openingTurn = false, pageContext?: string) => {
    if (text.trim() === '' || busy) {
      return
    }
    setError(null)
    setBusy(true)
    // 开场那一步发给上游的是一段"开始面试 + 岗位"的指令，界面上不该原样显示出来，
    // 用一个"开始面试"的气泡代替，读起来更像真在跟面试官说话。
    setLines((current) => [...current, { role: 'candidate', content: openingTurn ? '开始面试' : text }])
    setInput('')

    let id = conversationId
    try {
      if (id === null) {
        // 会话建的时候就钉死 harness：这决定了"谁在说话"，不该由页面在每轮里重复指定
        const created = await assistant.createConversation(null, 'interview')
        id = created.id
        setConversationId(id)
      }

      const controller = new AbortController()
      abortRef.current = controller
      const message = openingTurn ? openingMessage(profile, config.interviewIntro, text) : text

      setLines((current) => [...current, { role: 'interviewer', content: '' }])

      await assistant.sendMessage(
        id,
        message,
        (event) => {
          if (event.type === 'content' && event.delta !== undefined) {
            setLines((current) => updateLast(current, 'interviewer', (line) => ({
              ...line,
              content: line.content + (event.delta ?? ''),
            })))
          } else if (event.type === 'error') {
            setError(event.message ?? '生成失败，请稍后再试')
          }
          // thinking 事件刻意忽略：推理过程不展示（WORK-075）。
        },
        controller.signal,
        pageContext,
      )
    } catch (cause) {
      if (!isAbort(cause)) {
        setError(errorMessage(cause))
      }
    } finally {
      abortRef.current = null
      setBusy(false)
    }
  }, [busy, conversationId, profile, config.interviewIntro])

  /**
   * 开始面试：先按所选简历拼出**只进提示词、不落库**的上下文，再发第一条消息（WORK-077）。
   * 不选简历就不带上下文，回到"只按岗位问"的老行为。
   */
  const start = async () => {
    if (busy || preparing) {
      return
    }
    setPreparing(true)
    setResumeError(null)
    let pageContext: string | undefined
    try {
      if (resumeChoice === 'upload') {
        pageContext = uploadedText
      } else if (typeof resumeChoice === 'number') {
        const document = await resumesApi.get(resumeChoice)
        pageContext = formatResume(document.data, document.title)
      }
    } catch (cause) {
      // 简历取不到不该拦住面试：给出可读提示，按"无简历"继续。
      setResumeError(errorMessage(cause))
    } finally {
      setPreparing(false)
    }
    void send(`开始面试。目标岗位：${profile?.targetRole ?? '未填写'}。`, true, pageContext)
  }

  /** 本页上传 PDF/Word：解析成功用抽取出的原文作本次面试上下文（不强行入库）。 */
  const uploadResume = async (file: File) => {
    setUploading(true)
    setResumeError(null)
    try {
      const draft = await resumesApi.parse(file)
      setUploadedText(draft.plainText)
      setUploadedName(file.name)
      setResumeChoice('upload')
    } catch (cause) {
      setResumeError(errorMessage(cause))
    } finally {
      setUploading(false)
    }
  }

  const review = async () => {
    if (conversationId === null) {
      return
    }
    setReviewing(true)
    setError(null)
    try {
      setEvaluation(await assistant.reviewInterview(conversationId))
      // 新评分已经落库，列表要跟着变，否则要刷新整页才看得到
      void history.refetch()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setReviewing(false)
    }
  }

  const role = profile?.targetRole ?? undefined

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <div className="mx-auto w-full max-w-5xl space-y-6">
        <PageHeader
          title="模拟面试"
          description="一次一个问题，答不上来就说不会。随时可以点「生成点评」拿一份结构化评分。"
          actions={
            role === undefined || role === '' ? undefined : (
              <Badge variant="secondary" className="gap-1">
                <Target className="size-3.5" />
                {role}
              </Badge>
            )
          }
        />

        {lines.length === 0 ? (
          <>
            <StartPanel
              intro={config.interviewIntro}
              role={role}
              busy={busy}
              preparing={preparing}
              onStart={() => { setConfirmStartOpen(true) }}
              resumes={resumeList.data}
              choice={resumeChoice}
              onChoose={setResumeChoice}
              onUpload={(file) => { void uploadResume(file) }}
              uploading={uploading}
              uploadedName={uploadedName}
              resumeError={resumeError}
            />
            <Alert>
              <AlertTitle>关于隐私</AlertTitle>
              <AlertDescription>
                这场对话会发给你配置的 AI 服务（第三方）。
                <strong>你的笔记默认不参与</strong>——只有你自己选择带上时才会发出去。
              </AlertDescription>
            </Alert>
          </>
        ) : (
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_18rem]">
            {/* 对话主轴：头（面试官身份 + 生成点评）+ 消息区 + 输入区 */}
            <div className="flex min-h-[34rem] flex-col overflow-hidden rounded-lg border border-border bg-card shadow-card">
              <div className="flex items-center gap-3 border-b border-border px-4 py-3 sm:px-5">
                <div className="relative">
                  <Avatar kind="bot" />
                  <span className="border-card bg-success absolute -right-0.5 -bottom-0.5 size-2.5 rounded-full border-2" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">AI 面试官</p>
                  <p className="text-muted-foreground flex items-center gap-1.5 text-xs">
                    {busy ? (
                      <>
                        <Spinner className="size-3" />
                        正在思考…
                      </>
                    ) : (
                      '在线 · 随时作答'
                    )}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  data-testid="interview-end"
                  disabled={busy}
                  onClick={() => { setEndConfirmOpen(true) }}
                  className="gap-1.5"
                >
                  结束面试
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  data-testid="interview-review"
                  disabled={reviewing || busy}
                  onClick={() => { void review() }}
                  className="gap-1.5"
                >
                  <Sparkles className="size-3.5" />
                  {reviewing ? '点评中…' : '生成点评'}
                </Button>
              </div>

              <div
                ref={bodyRef}
                data-testid="interview-transcript"
                className="flex-1 space-y-5 overflow-y-auto px-4 py-6 sm:px-5"
                style={{ maxHeight: 'min(60vh, 40rem)' }}
              >
                {lines.map((line, index) => {
                  const last = index === lines.length - 1
                  return (
                    <Bubble key={index} line={line} thinking={last && line.content === '' && busy} />
                  )
                })}
              </div>

              <div className="border-t border-border bg-card p-3">
                <div className="flex items-end gap-2">
                  <textarea
                    data-testid="interview-input"
                    rows={1}
                    placeholder="输入你的回答…（Enter 发送，Shift+Enter 换行）"
                    value={input}
                    disabled={busy}
                    onChange={(event) => { setInput(event.target.value) }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' && !event.shiftKey) {
                        event.preventDefault()
                        void send(input)
                      }
                    }}
                    className="border-border bg-background text-foreground placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/30 max-h-40 min-h-11 flex-1 resize-none rounded-xl border px-3.5 py-2.5 text-sm leading-6 outline-none focus-visible:ring-2 disabled:opacity-60"
                  />
                  <Button
                    data-testid="interview-send"
                    disabled={busy || input.trim() === ''}
                    onClick={() => { void send(input) }}
                    className="h-11 shrink-0 gap-1.5"
                  >
                    <Send className="size-4" />
                    发送
                  </Button>
                </div>
                <p className="text-muted-foreground mt-1.5 px-1 text-xs">
                  Enter 发送 · Shift+Enter 换行 · 答不上来就说不会
                </p>
              </div>
            </div>

            <aside className="space-y-4">
              <TipCard />
              {history.data === undefined || history.data.length === 0 ? null : (
                <HistoryCard items={history.data} />
              )}
            </aside>
          </div>
        )}

        {evaluation === null ? null : <EvaluationCard view={evaluation} />}

        {error === null ? null : (
          <Alert variant="destructive">
            <AlertTitle>没成功</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
      </div>

      {/* 开面二次确认：把"会扣积分 / 结束能出报告"讲清楚再开始 */}
      <Dialog open={confirmStartOpen} onOpenChange={setConfirmStartOpen}>
        <DialogContent data-testid="interview-start-confirm-dialog">
          <DialogHeader>
            <DialogTitle>开始这场模拟面试？</DialogTitle>
            <DialogDescription>开始前先确认几件事：</DialogDescription>
          </DialogHeader>
          <ul className="text-muted-foreground space-y-2 text-sm">
            <li>· <span className="text-foreground">会消耗积分</span>：每轮提问/回答按模型用量计费，点「生成点评」出报告也会计费。</li>
            <li>· <span className="text-foreground">结束有报告</span>：随时点「生成点评」，拿到总分 + 各维度评分 + 优点/改进。</li>
            <li>· <span className="text-foreground">开始后请别中途离开</span>：这场对话不会在页面留存，退出后无法继续、也不能重新进入。</li>
          </ul>
          <DialogFooter>
            <Button variant="outline" onClick={() => { setConfirmStartOpen(false) }}>
              再想想
            </Button>
            <Button
              data-testid="interview-start-confirm"
              disabled={busy || preparing}
              onClick={() => {
                setConfirmStartOpen(false)
                void start()
              }}
            >
              {preparing ? '准备中…' : '开始面试'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 未出报告就离开的二次确认（站内跳转被 useBlocker 拦下时弹） */}
      <Dialog
        open={blocker.state === 'blocked'}
        onOpenChange={(open) => { if (!open) { blocker.reset?.() } }}
      >
        <DialogContent data-testid="interview-leave-confirm-dialog">
          <DialogHeader>
            <DialogTitle>要离开这场面试吗？</DialogTitle>
            <DialogDescription>
              离开后这场对话<strong className="text-foreground">无法继续，也不能重新进入</strong>。
              想留下结果的话，先点右上角「生成点评」拿一份报告，报告会存进「历次面试」。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => { blocker.reset?.() }}>
              继续面试
            </Button>
            <Button variant="destructive" onClick={() => { blocker.proceed?.() }}>
              离开
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 「结束面试」二次确认 */}
      <Dialog open={endConfirmOpen} onOpenChange={setEndConfirmOpen}>
        <DialogContent data-testid="interview-end-confirm-dialog">
          <DialogHeader>
            <DialogTitle>结束这场面试？</DialogTitle>
            <DialogDescription>
              结束后这场对话<strong className="text-foreground">无法继续，也不能重新进入</strong>。
              想留下结果的话，先点「生成点评」拿一份报告，报告会存进「历次面试」。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => { setEndConfirmOpen(false) }}>
              继续面试
            </Button>
            <Button variant="destructive" onClick={endInterview}>
              结束并退出
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AppShell>
  )
}

/** 头像：面试官是机器人图标，候选人是人形图标，颜色都走令牌。 */
function Avatar({ kind }: { kind: 'bot' | 'user' }) {
  const bot = kind === 'bot'
  return (
    <span
      aria-hidden
      className={
        'flex size-8 shrink-0 items-center justify-center rounded-full border ' +
        (bot ? 'border-border bg-card text-primary' : 'border-transparent bg-primary text-primary-foreground')
      }
    >
      {bot ? <Bot className="size-4" /> : <User className="size-4" />}
    </span>
  )
}

/** 一条消息气泡：面试官靠左、候选人靠右。
 *
 * <p>`data-testid="interview-line-*"` **只包正文**（不含头像/称呼/思考提示），
 * 因为 e2e 用它判断"失败时不该留一条空气泡"——把装饰也算进去会让那条断言失真。
 */
function Bubble({ line, thinking }: { line: Line; thinking: boolean }) {
  const isInterviewer = line.role === 'interviewer'
  const empty = line.content === ''

  if (isInterviewer) {
    return (
      <article className="flex items-start gap-3">
        <Avatar kind="bot" />
        <div className="min-w-0 max-w-[85%]">
          <p className="text-muted-foreground mb-1 text-xs">面试官</p>
          <div
            data-testid="interview-line-interviewer"
            className={
              'rounded-2xl rounded-tl-md bg-muted px-4 py-2.5 text-sm leading-relaxed whitespace-pre-wrap text-foreground ' +
              (empty && !thinking ? 'hidden' : '')
            }
          >
            {empty ? <Spinner className="text-muted-foreground size-4" /> : line.content}
          </div>
          {thinking ? <p className="text-muted-foreground mt-1.5 text-xs">面试官正在思考…</p> : null}
        </div>
      </article>
    )
  }

  return (
    <article className="flex items-start justify-end gap-3">
      <div className="max-w-[85%] min-w-0">
        <p className="text-muted-foreground mb-1 text-right text-xs">我</p>
        <div
          data-testid="interview-line-candidate"
          className="bg-primary text-primary-foreground rounded-2xl rounded-tr-md px-4 py-2.5 text-left text-sm leading-relaxed whitespace-pre-wrap"
        >
          {line.content}
        </div>
      </div>
      <Avatar kind="user" />
    </article>
  )
}

/** 起始态：左边讲清楚要做什么（含简历选择），右边讲这场面试怎么进行。 */
function StartPanel({
  intro,
  role,
  busy,
  preparing,
  onStart,
  resumes,
  choice,
  onChoose,
  onUpload,
  uploading,
  uploadedName,
  resumeError,
}: {
  intro: string
  role: string | undefined
  busy: boolean
  preparing: boolean
  onStart: () => void
  resumes: ResumeDocumentSummary[] | undefined
  choice: ResumeChoice
  onChoose: (choice: ResumeChoice) => void
  onUpload: (file: File) => void
  uploading: boolean
  uploadedName: string | undefined
  resumeError: string | null
}) {
  const starting = busy || preparing
  return (
    <Card className="overflow-hidden">
      <div className="grid md:grid-cols-[1.35fr_1fr]">
        <div className="p-6 sm:p-8">
          <span className="from-primary to-primary/75 text-primary-foreground inline-flex size-12 items-center justify-center rounded-2xl bg-gradient-to-br">
            <MessageSquareText className="size-6" />
          </span>
          <h2 className="mt-4 text-xl font-semibold">准备好开始了吗？</h2>
          <p className="text-muted-foreground mt-2 text-sm leading-6">{intro}</p>
          <div className="mt-5 flex flex-wrap items-center gap-2">
            <Badge variant="secondary" className="gap-1">
              <Target className="size-3.5" />
              {role === undefined || role === '' ? '未填写目标岗位' : role}
            </Badge>
          </div>

          {/* 简历（可选，WORK-077）：选已有 / 当场上传 / 不使用 */}
          <div className="border-border mt-5 space-y-2 border-t pt-4">
            <div className="flex items-center justify-between gap-2">
              <p className="text-sm font-medium">
                用哪份简历来面？<span className="text-muted-foreground font-normal">（可选）</span>
              </p>
              <label
                className={
                  'border-border hover:bg-muted text-foreground inline-flex cursor-pointer items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-xs font-medium ' +
                  (uploading ? 'pointer-events-none opacity-60' : '')
                }
              >
                <input
                  type="file"
                  accept=".pdf,.doc,.docx"
                  className="hidden"
                  disabled={uploading}
                  data-testid="interview-resume-upload"
                  onChange={(event) => {
                    const file = event.target.files?.[0]
                    if (file !== undefined) {
                      onUpload(file)
                    }
                    event.target.value = ''
                  }}
                />
                {uploading ? <Spinner className="size-3.5" /> : <Upload className="size-3.5" />}
                {uploading ? '解析中…' : '上传简历'}
              </label>
            </div>

            <div className="space-y-1.5">
              <ResumeOption
                label="不使用简历（按岗位问）"
                selected={choice === 'none'}
                onClick={() => { onChoose('none') }}
              />
              {(resumes ?? []).map((resume) => (
                <ResumeOption
                  key={resume.id}
                  label={resume.title}
                  badge={resume.isDefault ? '默认' : undefined}
                  selected={choice === resume.id}
                  onClick={() => { onChoose(resume.id) }}
                />
              ))}
              {uploadedName === undefined ? null : (
                <ResumeOption
                  label={uploadedName}
                  badge="本次上传"
                  selected={choice === 'upload'}
                  onClick={() => { onChoose('upload') }}
                />
              )}
            </div>

            {resumes !== undefined && resumes.length === 0 && uploadedName === undefined ? (
              <p className="text-muted-foreground text-xs">还没有简历，可上传一份，或去「简历」页新建。</p>
            ) : null}
            {resumeError === null ? null : <p className="text-destructive text-xs">{resumeError}</p>}
            <p className="text-muted-foreground text-xs">简历只在你选择后用于本次面试，不会保存。</p>
          </div>

          <Button
            data-testid="interview-start"
            size="lg"
            className="mt-6 gap-2"
            disabled={starting}
            onClick={onStart}
          >
            {starting ? <Spinner /> : <Sparkles className="size-4" />}
            {preparing ? '准备中…' : busy ? '开场中…' : '开始面试'}
            {starting ? null : <ArrowRight className="size-4" />}
          </Button>
          <p className="text-muted-foreground mt-2 text-xs">
            本场会消耗积分：每轮提问/回答与「生成点评」都按模型用量计费。
          </p>
        </div>
        <div className="border-border bg-muted/40 border-t p-6 sm:p-8 md:border-t-0 md:border-l">
          <p className="text-muted-foreground text-xs font-medium tracking-wide uppercase">这场面试怎么进行</p>
          <ol className="mt-4 space-y-4">
            <Step n={1} title="面试官一次问一题" desc="围绕你的目标岗位与简历，从基础到深入逐个追问。" />
            <Step n={2} title="像真面试一样作答" desc="答不上来就说不会，面试官会换个角度继续问。" />
            <Step n={3} title="结束拿结构化点评" desc="随时点「生成点评」，按维度给出分数与改进建议。" />
          </ol>
        </div>
      </div>
    </Card>
  )
}

/** 简历选项：一个可选中的行，选中显示对勾。 */
function ResumeOption({
  label,
  badge,
  selected,
  onClick,
}: {
  label: string
  badge?: string
  selected: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'flex w-full items-center gap-2 rounded-lg border px-3 py-2 text-left text-sm transition-colors ' +
        (selected ? 'border-primary bg-accent text-accent-foreground' : 'border-border hover:bg-muted')
      }
    >
      <span
        className={
          'flex size-4 shrink-0 items-center justify-center rounded-full border ' +
          (selected ? 'border-primary bg-primary text-primary-foreground' : 'border-border')
        }
      >
        {selected ? <Check className="size-3" /> : null}
      </span>
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {badge === undefined ? null : (
        <Badge variant="outline" className="shrink-0">
          {badge}
        </Badge>
      )}
    </button>
  )
}

function Step({ n, title, desc }: { n: number; title: string; desc: string }) {
  return (
    <li className="flex gap-3">
      <span className="bg-primary text-primary-foreground flex size-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold">
        {n}
      </span>
      <div className="min-w-0">
        <p className="text-sm font-medium">{title}</p>
        <p className="text-muted-foreground mt-0.5 text-xs leading-5">{desc}</p>
      </div>
    </li>
  )
}

/** 右侧栏的小贴士：把隐私与操作方式在"面试间"里也留一份。 */
function TipCard() {
  return (
    <Card>
      <CardContent className="space-y-3 pt-6">
        <h2 className="flex items-center gap-1.5 text-sm font-medium">
          <Lightbulb className="text-primary size-4" />
          小贴士
        </h2>
        <ul className="text-muted-foreground space-y-1.5 text-xs leading-5">
          <li>
            · 隐私：这场对话会发给 AI 服务，<strong className="text-foreground font-medium">你的笔记默认不参与</strong>。
          </li>
          <li>· Enter 发送，Shift+Enter 换行。</li>
          <li>· 随时点右上「生成点评」拿一份评分。</li>
        </ul>
      </CardContent>
    </Card>
  )
}

/** 历次面试：默认收起，展开看分维度与改进点。 */
function HistoryCard({ items }: { items: InterviewEvaluationView[] }) {
  const latest = items[0]
  return (
    <Card data-testid="interview-history">
      <CardContent className="pt-6">
        <div className="mb-3 flex items-baseline justify-between gap-2">
          <h2 className="text-sm font-medium">历次面试</h2>
          <span className="text-muted-foreground text-xs">
            共 {items.length} 次{latest === undefined ? '' : ` · 最近 ${latest.evaluation.overall}/5`}
          </span>
        </div>
        <ul className="divide-border divide-y">
          {items.map((item) => (
            <li key={item.id} data-testid="interview-history-item">
              <details className="group py-2.5">
                <summary className="flex cursor-pointer items-center justify-between gap-2 text-sm">
                  <span className="text-muted-foreground text-xs">
                    {new Date(item.createdAt).toLocaleString('zh-CN', {
                      dateStyle: 'short',
                      timeStyle: 'short',
                    })}
                  </span>
                  <span className="flex items-center gap-1.5">
                    <span className="font-medium">{item.evaluation.overall} / 5</span>
                    <ChevronDown className="text-muted-foreground size-4 transition-transform group-open:rotate-180" />
                  </span>
                </summary>
                <div className="mt-3 space-y-2.5">
                  {item.evaluation.dimensions.map((dimension) => (
                    <div key={dimension.name}>
                      <div className="flex items-baseline justify-between gap-2 text-xs">
                        <span className="text-muted-foreground">{dimension.name}</span>
                        <span className="text-muted-foreground">{dimension.score}/5</span>
                      </div>
                      <ScoreBar score={dimension.score} className="mt-1 h-1" />
                    </div>
                  ))}
                  {item.evaluation.improvements.length === 0 ? null : (
                    <ul className="text-muted-foreground mt-1 list-disc space-y-1 pl-4 text-xs leading-5">
                      {item.evaluation.improvements.map((line) => (
                        <li key={line}>{line}</li>
                      ))}
                    </ul>
                  )}
                </div>
              </details>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

/** 维度进度条：宽度按 score/5 算，颜色只取令牌。 */
function ScoreBar({ score, className = 'h-1.5' }: { score: number; className?: string }) {
  const percent = Math.max(0, Math.min(100, (score / 5) * 100))
  return (
    <div className={'bg-muted w-full overflow-hidden rounded-full ' + className}>
      <div className="bg-primary h-full rounded-full transition-all" style={{ width: `${percent}%` }} />
    </div>
  )
}

/** 评估卡片：总分 + 各维度进度条 + 优点 / 改进两栏。 */
function EvaluationCard({ view }: { view: InterviewEvaluationView }) {
  const { evaluation } = view
  return (
    <Card data-testid="interview-evaluation" className="mt-0">
      <CardContent className="space-y-5 pt-6">
        <div className="flex flex-wrap items-end gap-x-4 gap-y-2">
          <div>
            <h2 className="text-lg font-semibold">面试点评</h2>
            <p className="text-muted-foreground mt-0.5 text-xs">
              {new Date(view.createdAt).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })}
            </p>
          </div>
          <div className="ml-auto flex items-end gap-1">
            <span className="text-4xl leading-none font-semibold" data-testid="interview-overall">
              {evaluation.overall}
            </span>
            <span className="text-muted-foreground pb-1 text-sm">/ 5</span>
          </div>
        </div>

        <Separator />

        <div className="grid gap-6 sm:grid-cols-2">
          <div className="space-y-3.5">
            <h3 className="text-sm font-medium">各维度评分</h3>
            {evaluation.dimensions.map((dimension) => (
              <div key={dimension.name} data-testid="interview-dimension">
                <div className="flex items-baseline justify-between gap-2 text-sm">
                  <span className="font-medium">{dimension.name}</span>
                  <span className="text-muted-foreground text-xs">{dimension.score} / 5</span>
                </div>
                <ScoreBar score={dimension.score} className="mt-1.5 h-1.5" />
                <p className="text-muted-foreground mt-1 text-xs leading-5">{dimension.comment}</p>
              </div>
            ))}
          </div>

          <div className="space-y-5">
            {evaluation.strengths.length === 0 ? null : (
              <div>
                <h3 className="flex items-center gap-1.5 text-sm font-medium">
                  <TrendingUp className="text-success size-4" />
                  做得好的地方
                </h3>
                <ul className="mt-2 space-y-1.5">
                  {evaluation.strengths.map((item) => (
                    <li key={item} className="text-muted-foreground text-xs leading-5">
                      · {item}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {evaluation.improvements.length === 0 ? null : (
              <div>
                <h3 className="flex items-center gap-1.5 text-sm font-medium">
                  <Lightbulb className="text-warning size-4" />
                  可以改进的地方
                </h3>
                <ul className="mt-2 space-y-1.5">
                  {evaluation.improvements.map((item) => (
                    <li key={item} data-testid="interview-improvement" className="text-muted-foreground text-xs leading-5">
                      · {item}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

/** 把一份结构化简历压成给面试官看的纯文本（截断到 pageContext 上限以内）。 */
function formatResume(data: ResumeData, title: string): string {
  const lines: string[] = [`简历：${title}`]
  const basics = data.basics
  if (basics.name !== '') {
    lines.push(`姓名：${basics.name}`)
  }
  if (data.intent.role !== '') {
    lines.push(`求职意向：${data.intent.role}`)
  }
  if (data.education.length > 0) {
    lines.push(
      '教育经历：' +
        data.education
          .map((item) => `${item.school} ${item.degree} ${item.major} ${item.start}-${item.end}`.trim())
          .join('；'),
    )
  }
  if (data.work.length > 0) {
    lines.push('工作经历：')
    data.work.forEach((item) => {
      lines.push(`- ${item.company} ${item.title} ${item.start}-${item.end}${item.summary === '' ? '' : `：${item.summary}`}`)
      item.bullets.forEach((bullet) => lines.push(`  · ${bullet}`))
    })
  }
  if (data.projects.length > 0) {
    lines.push('项目经历：')
    data.projects.forEach((item) => {
      lines.push(`- ${item.name} ${item.role} ${item.period}`)
      item.bullets.forEach((bullet) => lines.push(`  · ${bullet}`))
    })
  }
  if (data.skills.length > 0) {
    lines.push('技能：' + data.skills.map((group) => `${group.group}：${group.items.join('、')}`).join('；'))
  }
  if (data.selfEvaluation !== '') {
    lines.push(`自我评价：${data.selfEvaluation}`)
  }
  return lines.join('\n').slice(0, 3800)
}

/** 把"开始面试"变成一个模型看得懂的请求：目标岗位 + 本页约定的开场说明。 */
function openingMessage(profile: LearnerProfile | undefined, intro: string, fallback: string): string {
  const role = profile?.targetRole ?? ''
  const head = role === '' ? fallback : `开始面试。目标岗位：${role}。`
  return `${head}\n（本场约定：${intro}）`
}

/** 替换最后一条指定角色的消息。用函数式更新避免闭包里拿到旧数组。 */
function updateLast(lines: Line[], role: Line['role'], patch: (line: Line) => Line): Line[] {
  const index = lines.map((line) => line.role).lastIndexOf(role)
  if (index < 0) {
    return lines
  }
  return lines.map((line, position) => (position === index ? patch(line) : line))
}

/** 用户主动离开或被中断都不该显示成错误。 */
function isAbort(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError'
}
