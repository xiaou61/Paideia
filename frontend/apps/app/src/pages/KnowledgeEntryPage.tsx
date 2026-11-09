import {
  EntryToc,
  KnowledgeNeighborhood,
  MarkdownBody,
  PracticeEntry,
  SelfAssessmentButtons,
  createKnowledgeApi,
  createLearningApi,
  createServerProgressPort,
  findCategoryPath,
  stripDuplicateTitle,
} from '@paideia/knowledge'
import type { EntryRef, SelfAssessmentLevel } from '@paideia/knowledge'
import {
  Alert,
  AlertTitle,
  AppShell,
  Button,
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  ErrorAlert,
  LoadingRow,
  PageHeader,
  extractQuizzes,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createAssistantApi, SummaryCardView } from '@paideia/assistant'
import { ArrowLeft, ArrowRight, ArrowUp, ChevronLeft, ListTree } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { PageBreadcrumb } from '../components/PageBreadcrumb'
import { API_BASE_URL } from '../env'
import { errorMessage } from '@paideia/core'

const knowledge = createKnowledgeApi(api)
const learning = createLearningApi(api)

/**
 * 条目详情：**一栏阅读正文 + 右侧常驻目录**。
 *
 * <p>照文档站的正文页排：左栏是阅读区（宽度上限 46rem 左右，汉字一行 30 多字最好读），
 * 右侧是常驻目录，正文之后才是"上一篇／下一篇"与关系列表——三块阅读辅助都不打断阅读流。
 *
 * <p>上下篇做成**卡片**而不是两个文字链接：它们是长文里唯一的"继续读"出口，
 * 而文字链接在一屏里几乎看不见。
 */
export function KnowledgeEntryPage() {
  const params = useParams()
  const entryId = Number(params['id'])
  const valid = Number.isFinite(entryId)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const fromTrack = location.state as
    | { fromTrackSlug?: string; fromTrackName?: string }
    | null

  /**
   * 源码解读的双向入口（WORK-014）：学习路径点击某站直接跳过去。
   * 保持**稳定引用**——它会进 `MarkdownBody` 的 memo 依赖，每次渲染换新函数会让整篇正文重解析。
   */
  const openEntry = useCallback((id: number) => navigate(`/knowledge/entries/${id}`), [navigate])

  /**
   * AI 摘要（WORK-066）：原来在助手面板的「精简」页签；面板下线后放在正文末尾。
   * 只读已有的那份，没有就点按钮生成——不再需要开对话。
   */
  const assistantApi = useMemo(() => createAssistantApi(api), [])
  const summaryQuery = useQuery({
    queryKey: ['entry-summary', entryId],
    queryFn: () => assistantApi.summary(entryId),
    enabled: valid,
  })
  const generateSummary = useMutation({
    mutationFn: () => assistantApi.generate(entryId, 'private'),
    onSuccess: (view) => queryClient.setQueryData(['entry-summary', entryId], view),
  })

  /** AI 出一道题（WORK-067）：用这一条的正文出，题与解析只回这一次，不落库。 */
  const [quizOpen, setQuizOpen] = useState(false)
  const [quizChoice, setQuizChoice] = useState<number | null>(null)
  const aiQuiz = useMutation({
    mutationFn: () => assistantApi.quiz({ title: entry.data?.title ?? '', body }),
  })

  /**
   * 代码执行（WORK-015）：把能力探测与运行封成端口交给正文块。
   *
   * <p>**只在条目页注入**——管理端预览、笔记预览都没有它，所以那些地方的「运行」按钮不会出现。
   * `ready` 每次都问一次（很轻），因为用户可能在别的标签页跑完了当天的额度。
   */
  /**
   * 逐题作答上报（WORK-033）。失败不弹错：小测的本地判分已经给了用户反馈，
   * 上报失败只影响统计，不该打断阅读。
   */
  const recordAttempt = useCallback(
    (input: { blockKey: string; questionKey: string; choice: number }) => {
      void learning.attempt({ entryId, ...input }).catch(() => undefined)
    },
    [entryId],
  )

  const sandbox = useMemo(
    () => ({
      ready: () => knowledge.sandboxStatus().then((status) => status.ready),
      run: (input: {
        language: string
        code: string
        stdin?: string
        /** SQL 专用：数据集 key，内容在服务端（WORK-032）。 */
        dataset?: string
        caseId?: string
      }) => knowledge.runCode({ ...input, entryId }),
      // 逐步录制（WORK-023）：只在条目页注入，管理端预览与笔记预览都没有它，
      // 所以那些地方的「逐步执行」按钮不会出现——与 `run` 同一条纪律。
      trace: (input: { language: string; code: string; stdin?: string; caseId?: string }) =>
        knowledge.traceCode({ ...input, entryId }),
      // Linux 判题（WORK-056）：判定脚本不下发，结论由服务端给
      runShell: (input: {
        code: string
        judge: string
        seed?: { path: string; content: string }[]
        caseId?: string
      }) => knowledge.runShell({ ...input, entryId }),
    }),
    [entryId],
  )

  const entry = useQuery({
    queryKey: ['knowledge-entry', entryId],
    queryFn: () => knowledge.entry(entryId),
    enabled: valid,
  })

  /**
   * 分类树：只为面包屑取一次（2026-11-09）。
   *
   * <p>条目的接口只给 `categoryId + categoryName`，给不出"计算机基础 › 操作系统"这种层级。
   * 分类树在服务端有 Redis 缓存、前端又被 react-query 缓存 5 分钟，所以这一跳很便宜；
   * 取不到就只渲染「知识库 › 分类名」，不因为面包屑挡住正文。
   */
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
    staleTime: 5 * 60_000,
    retry: false,
  })
  const categoryPath = useMemo(() => {
    const categoryId = entry.data?.categoryId
    if (categoryId === null || categoryId === undefined) {
      return []
    }
    return findCategoryPath(categories.data ?? [], categoryId)
  }, [categories.data, entry.data?.categoryId])

  /**
   * 阅读进度与「回到顶部」（2026-11-09）。
   *
   * <p>长条目（有的上万字）最常被问的两件事就是"我读到哪了"和"怎么回去"。
   * 进度按**整篇文档**算而不是按正文元素算：目录、关系列表与页脚也在阅读区里，
   * 按正文元素算会在目录还没滚完时就走满，读起来像个坏掉的进度条。
   *
   * <p>滚动回调里只记一个 rAF 句柄：长文滚动一次会触发几十次事件，逐次 setState 会让
   * 阅读变成掉帧（实测在低配笔记本上可见）。
   */
  const [readingProgress, setReadingProgress] = useState(0)
  useEffect(() => {
    let frame = 0
    const update = () => {
      frame = 0
      const scrollable = document.documentElement.scrollHeight - window.innerHeight
      const next = scrollable <= 0 ? 0 : (window.scrollY / scrollable) * 100
      setReadingProgress(Math.min(100, Math.max(0, next)))
    }
    const onScroll = () => {
      if (frame === 0) {
        frame = window.requestAnimationFrame(update)
      }
    }
    update()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    return () => {
      if (frame !== 0) {
        window.cancelAnimationFrame(frame)
      }
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
    }
  }, [])

  /**
   * 学习记录（WORK-020）：**先取再渲染正文**。
   *
   * <p>顺序很重要：渲染块时会用进度端口读出"上次做到哪"，服务端的值必须在那之前到手，
   * 否则块先按空进度画一遍、再跳变。所以这里用 `isPending` 挡住正文。
   *
   * <p>取不到（非课程条目、接口失败）时传 null，端口照常工作——只是本次得分只留在本地。
   */
  const serverProgress = useQuery({
    queryKey: ['learning-entry', entryId],
    queryFn: () => learning.entry(entryId),
    enabled: valid,
    retry: false,
  })

  /**
   * 已答过的题：练习册打开时要显示上次做到哪，所以和进度一样**先取再渲染卡片**。
   * 取不到（老接口、网络抖动）就当没答过，卡片照常可用。
   */
  const serverAnswers = useQuery({
    queryKey: ['learning-answers', entryId],
    queryFn: () => learning.answered(entryId),
    enabled: valid,
    retry: false,
  })

  /**
   * 「这是第几关」（WORK-068）：只有从关卡地图点进来才知道自己在路上的位置。
   * 地图数据在轨道页已经取过，正常路径是缓存命中；直接开链接时多一次查询，可以接受。
   */
  const trackMap = useQuery({
    queryKey: ['learning-track', fromTrack?.fromTrackSlug ?? ''],
    queryFn: () => learning.trackMap(fromTrack?.fromTrackSlug ?? ''),
    enabled: Boolean(fromTrack?.fromTrackSlug),
  })
  const place = useMemo(() => {
    if (trackMap.data === undefined) {
      return null
    }
    for (const chapter of trackMap.data.chapters) {
      const stage = chapter.stages.find((item) => item.entryId === entryId)
      if (stage !== undefined) {
        return { seq: stage.seq, total: trackMap.data.stageCount, done: stage.status === 'done' }
      }
    }
    return null
  }, [trackMap.data, entryId])

  const progressPort = useMemo(() => {
    if (!valid || serverProgress.isPending || entry.data === undefined) {
      return undefined
    }
    return createServerProgressPort({
      entryId,
      title: entry.data.title,
      initial: serverProgress.data ?? null,
      report: (body) => learning.report(entryId, body),
      clear: () => learning.reset(entryId),
    })
  }, [valid, serverProgress.isPending, serverProgress.data, entry.data, entryId])

  /**
   * 练习册（WORK-048）：把正文里的 `quiz` 围栏摘出来，交给末尾的入口卡。
   *
   * <p>在**数据层**摘而不是做一个"只收集不渲染"的渲染器：后者要在渲染期写共享状态，
   * React 严格模式会把渲染跑两遍，同一道题就被收两次。
   */
  /**
   * 顶部已经渲染了条目标题，正文开头那条同名一级标题就不再显示——课程页原来会并排出现
   * 两条同文大标题。**必须和目录用同一份**：`EntryToc` 与正文按同一次 `extractHeadings`
   * 的顺序编号，只给正文剥掉会让目录多一条、点下去错位一节。
   */
  const body = useMemo(
    () => stripDuplicateTitle(entry.data?.body ?? '', entry.data?.title),
    [entry.data?.body, entry.data?.title],
  )

  const practice = useMemo(() => extractQuizzes(body), [body])

  // 把"当前在读哪一条"交给悬浮壳：它是唯一知道这件事的地方。
  // 离开页面时清空，壳随即回到"打开一条内容后可用"的状态。
  /**
   * 记一次浏览（WORK-021）。
   *
   * <p>用 ref 挡住重复上报：严格模式下 effect 会跑两次，切标签页回来也可能重渲染，
   * 那些都不该多算一次。刷新页面才算新的一次。
   *
   * <p>失败静默：旁路数据不值得让阅读页报错。
   */
  const viewReported = useRef<number | null>(null)
  useEffect(() => {
    if (!valid || entry.data === undefined || viewReported.current === entryId) {
      return
    }
    viewReported.current = entryId
    void knowledge.recordView(entryId).catch(() => undefined)
  }, [valid, entryId, entry.data])

  // 标记与取消共用一次 mutation：对用户来说是"改我的标记"，不是两个动作
  const assess = useMutation({
    mutationFn: (next: SelfAssessmentLevel | null) =>
      next === null ? knowledge.clearSelfAssessment(entryId) : knowledge.markSelfAssessment(entryId, next),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['knowledge-entry', entryId] })
      await queryClient.invalidateQueries({ queryKey: ['my-assessments'] })
    },
  })

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      {/*
        进度条贴在页头下沿（页头 h-14 且 sticky）：它必须永远可见，所以用 fixed 而不是
        sticky 放在正文里——正文一滚它就跟着走了。低于 4% 不显示，避免刚进页面就有一条线。
      */}
      {readingProgress > 4 ? (
        <div
          role="progressbar"
          aria-label="阅读进度"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(readingProgress)}
          data-testid="entry-reading-progress"
          className="pointer-events-none fixed inset-x-0 top-14 z-20 h-0.5"
        >
          <div className="bg-primary h-full" style={{ width: `${readingProgress}%` }} />
        </div>
      ) : null}

      {readingProgress > 8 ? (
        <button
          type="button"
          onClick={() => {
            window.scrollTo({ top: 0, behavior: 'smooth' })
          }}
          data-testid="entry-back-to-top"
          aria-label="回到顶部"
          title="回到顶部"
          className="border-border bg-background/95 hover:bg-accent focus-visible:ring-ring/50 fixed right-4 bottom-6 z-30 grid size-11 place-items-center rounded-full border shadow-lg transition-colors outline-none focus-visible:ring-3 sm:right-6"
        >
          <ArrowUp className="size-4" aria-hidden />
        </button>
      ) : null}
      {fromTrack?.fromTrackSlug ? (
        // 从学习线进来时，"回到关卡地图"比面包屑更有用——那才是他刚才在走的路
        <Link
          to={`/learn/${fromTrack.fromTrackSlug}`}
          className="text-muted-foreground hover:text-foreground mb-6 inline-flex items-center gap-1 text-sm transition-colors"
          data-testid="back-to-track"
        >
          <ChevronLeft className="size-4" />
          {`返回「${fromTrack.fromTrackName ?? '学习轨道'}」关卡地图`}
        </Link>
      ) : (
        /*
          面包屑（2026-11-09）：原来只有一行「‹ 返回知识库」，进了二级分类不知道自己在哪。
          这里最后一级是**所属分类**且可点（lastLink）：条目页的"你在这里"是下面的 H1，
          分类应该能点进去看同一分类下的其它内容。分类树没到手时退化成「知识库」一级。
        */
        <PageBreadcrumb
          lastLink
          items={[
            { label: '知识库', to: '/knowledge', testId: 'back-to-categories' },
            ...categoryPath.map((node) => ({ label: node.name, to: `/knowledge/categories/${node.id}` })),
          ]}
        />
      )}

      {!valid ? (
        <Alert variant="destructive" data-testid="entry-state">
          <AlertTitle>地址里的条目编号不合法</AlertTitle>
        </Alert>
      ) : null}

      {valid && entry.isPending ? (
        <LoadingRow label="加载条目…" testId="entry-state" />
      ) : null}

      {valid && entry.isError ? (
        <ErrorAlert title="打不开这条内容" error={entry.error} testId="entry-state" />
      ) : null}

      {entry.isSuccess && serverProgress.isPending ? (
        <LoadingRow label="读取学习记录…" testId="entry-progress-loading" />
      ) : null}

      {entry.isSuccess && !serverProgress.isPending ? (
        <div className="grid gap-12 lg:grid-cols-[minmax(0,1fr)_15rem]">
          <article
            className={`min-w-0 ${
              // 含「左图右码」交互块的文章放宽正文列，让内容铺满、不挤在 46rem 里
              /```(code-flow|source-walk)/.test(practice.body) ? 'lg:max-w-none' : 'lg:max-w-[46rem]'
            }`}
          >
            {/* 所属分类已在页头面包屑里（可点），这里不再重复一行标签 */}
            <PageHeader title={entry.data.title} />

            {/*
              「第几关 + 通关状态」（WORK-068）：从地图点进来时最想知道的是"走了一半没有"。
              直接开链接的条目不显示"第 N/M 关"——那时它不属于任何一条路。
            */}
            {fromTrack?.fromTrackSlug || serverProgress.data?.status === 'passed' ? (
              <div
                className="text-muted-foreground mb-6 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm"
                data-testid="entry-place"
              >
                {fromTrack?.fromTrackSlug ? (
                  <span>
                    「{fromTrack.fromTrackName ?? '学习轨道'}」
                    {place === null ? null : (
                      <>
                        {' '}
                        第 <span className="text-foreground font-medium tabular-nums">{place.seq}</span>/
                        <span className="tabular-nums">{place.total}</span> 关
                      </>
                    )}
                  </span>
                ) : null}
                {place?.done === true || serverProgress.data?.status === 'passed' ? (
                  <span className="text-success text-xs" data-testid="entry-passed">
                    已通关
                  </span>
                ) : (
                  <span className="text-xs" data-testid="entry-passed">
                    未通关
                  </span>
                )}
              </div>
            ) : null}

            {/*
              来源链接（WORK-025）：抓来的面经帖标出原帖，让读者能回原处核对。
              平台自产内容与存量条目的 sourceUrl 是 null，这里整个不渲染。
              `rel="noreferrer noopener"`：外站链接不带 referrer，也不给它操作 window.opener 的机会。
            */}
            {entry.data.sourceUrl ? (
              <p className="text-muted-foreground mb-6 text-sm">
                题目来源：
                <a
                  className="text-primary underline-offset-4 hover:underline"
                  data-testid="entry-source-link"
                  href={entry.data.sourceUrl}
                  target="_blank"
                  rel="noreferrer noopener"
                >
                  原帖
                </a>
              </p>
            ) : null}

            {/*
              窄屏的目录（WORK-016）：原来 `<lg` 直接不渲染，等于长文在手机上完全没有"我在哪"。
              改成折叠面板放进正文列里 —— 默认收起不占地方，要跳章节时展开即可。
              `EntryToc` 自带"少于两个标题就不渲染"，所以这里不需要再判一次。
            */}
            <details
              className="border-border bg-card mb-6 rounded-xl border px-4 py-3 lg:hidden"
              data-testid="entry-toc-collapsed"
            >
              <summary className="text-muted-foreground flex min-h-11 cursor-pointer list-none items-center gap-2 text-sm font-medium">
                <ListTree className="size-4" />
                本页目录
              </summary>
              <div className="mt-2">
                {/* 窄屏这份用独立 testid：宽屏右栏那份也叫 entry-toc，同页两份会让数量断言数到 2 */}
                <EntryToc markdown={body} testId="entry-toc-mobile" />
              </div>
            </details>

            <MarkdownBody
              markdown={practice.body}
              mediaBaseUrl={API_BASE_URL}
              entryId={entry.data.id}
              entryTitle={entry.data.title}
              onOpenEntry={openEntry}
              sandbox={sandbox}
              progress={progressPort}
              recordAttempt={recordAttempt}
            />

            {/* 练习册（WORK-048）：题目从正文里摘出来，读完想测再测 */}
            {serverAnswers.isPending ? null : (
              <PracticeEntry
                quizzes={practice.quizzes}
                entryTitle={entry.data.title}
                previous={serverAnswers.data}
                onAttempt={recordAttempt}
                onScore={(quizId, correct, total) =>
                  progressPort?.update({ quizzes: { [quizId]: { score: correct, total } } })
                }
              />
            )}

            {/* AI 摘要（WORK-066）：面板下线后这块回到条目页 */}
            <section className="border-border bg-card mt-8 rounded-xl border p-4" data-testid="entry-summary">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-sm font-medium">AI 摘要</h2>
                <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={generateSummary.isPending}
                  data-testid="entry-summary-generate"
                  onClick={() => generateSummary.mutate()}
                >
                  {generateSummary.isPending ? '生成中…' : summaryQuery.data == null ? '生成摘要' : '重新生成'}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  data-testid="entry-quiz-generate"
                  onClick={() => {
                    setQuizChoice(null)
                    setQuizOpen(true)
                    aiQuiz.mutate()
                  }}
                >
                  AI 出一道题
                </Button>
                </div>
              </div>
              {summaryQuery.isPending ? (
                <p className="text-muted-foreground text-xs">读取中…</p>
              ) : summaryQuery.data == null ? (
                <p className="text-muted-foreground text-xs">
                  还没有摘要。点「生成摘要」用 AI 把这条内容压缩成要点。
                </p>
              ) : (
                <SummaryCardView view={summaryQuery.data} />
              )}
              {generateSummary.isError ? (
                <p className="text-destructive mt-2 text-xs" data-testid="entry-summary-error">
                  {errorMessage(generateSummary.error)}
                </p>
              ) : null}
            </section>

            <div className="mt-10">
              <SelfAssessmentButtons
                current={entry.data.selfAssessment}
                disabled={assess.isPending}
                onChange={(next) => assess.mutate(next)}
              />
            </div>

            <nav className="border-border mt-12 grid gap-4 border-t pt-8 sm:grid-cols-2">
              <NeighborCard side="previous" entry={entry.data.previous} testId="entry-previous" />
              <NeighborCard side="next" entry={entry.data.next} testId="entry-next" />
            </nav>

            <KnowledgeNeighborhood entry={entry.data} />

            <section className="mt-10 grid gap-6 sm:grid-cols-3">
              <RelationList title="需要先看" items={entry.data.prerequisites} testId="entry-prerequisites" />
              <RelationList title="相关内容" items={entry.data.related} testId="entry-related" />
              <RelationList title="看完这篇再看" items={entry.data.dependents} testId="entry-dependents" />
            </section>
          </article>

          {/*
            目录在右侧常驻：长文里"边读边看我在哪"比在正文上方摆一个框有用；
            小屏没有侧栏空间，此时不显示（窄屏目录价值本来也低）。
          */}
          <aside className="hidden lg:block">
            <div className="lg:sticky lg:top-20">
              <EntryToc markdown={body} />
            </div>
          </aside>
        </div>
      ) : null}
      <Dialog
        open={quizOpen}
        onOpenChange={(open) => {
          setQuizOpen(open)
          if (!open) {
            setQuizChoice(null)
          }
        }}
      >
        <DialogContent data-testid="entry-quiz-dialog">
          <DialogHeader>
            <DialogTitle>AI 出的题</DialogTitle>
          </DialogHeader>
          {aiQuiz.isPending ? (
            <p className="text-muted-foreground text-sm">AI 正在读这一条并出题…</p>
          ) : aiQuiz.isError ? (
            <p className="text-destructive text-sm" data-testid="entry-quiz-error">
              {errorMessage(aiQuiz.error)}
            </p>
          ) : aiQuiz.data === undefined ? null : (
            <div className="space-y-3">
              <p className="text-sm font-medium" data-testid="entry-quiz-question">
                {aiQuiz.data.question}
              </p>
              <div className="grid gap-1.5">
                {aiQuiz.data.options.map((option, index) => {
                  const chosen = quizChoice === index
                  const isAnswer = quizChoice !== null && index === aiQuiz.data!.answer
                  return (
                    <button
                      key={option}
                      type="button"
                      disabled={quizChoice !== null}
                      data-testid={`entry-quiz-option-${index}`}
                      onClick={() => setQuizChoice(index)}
                      className={`min-h-11 rounded-md border px-3 py-2 text-left text-sm transition-colors ${
                        isAnswer
                          ? 'border-success bg-success/10 text-success'
                          : chosen
                            ? 'border-destructive bg-destructive/10 text-destructive'
                            : 'border-border bg-background hover:bg-muted'
                      }`}
                    >
                      {isAnswer ? '✓ ' : chosen ? '✗ ' : ''}
                      {option}
                    </button>
                  )
                })}
              </div>
              {quizChoice === null ? null : (
                <p className="text-muted-foreground text-sm leading-6" data-testid="entry-quiz-explain">
                  {quizChoice === aiQuiz.data.answer ? '答对了。' : '不对。'} {aiQuiz.data.explain}
                </p>
              )}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </AppShell>
  )
}

/** 上一篇／下一篇。两端结构相同、方向相反，合成一个组件。 */
function NeighborCard({
  side,
  entry,
  testId,
}: {
  side: 'previous' | 'next'
  entry: EntryRef | null
  testId: string
}) {
  const next = side === 'next'
  return (
    <div data-testid={testId} className={next ? 'sm:text-right' : undefined}>
      <div className="text-muted-foreground mb-2 flex items-center gap-1 text-xs">
        {next ? null : <ArrowLeft className="size-3.5" />}
        {next ? '下一篇' : '上一篇'}
        {next ? <ArrowRight className="size-3.5" /> : null}
      </div>
      {entry === null ? (
        <span className="text-muted-foreground text-sm">没有了</span>
      ) : (
        <Link
          to={`/knowledge/entries/${entry.id}`}
          className={`border-border hover:border-primary/40 hover:bg-accent/40 block rounded-lg border p-3.5 transition-all ${
            next ? 'sm:ml-auto' : ''
          }`}
        >
          <span className="text-sm font-medium">{entry.title}</span>
        </Link>
      )}
    </div>
  )
}

/** 一组关系链接。三组结构相同，只有标题与来源不同。 */
function RelationList({
  title,
  items,
  testId,
}: {
  title: string
  items: EntryRef[]
  testId: string
}) {
  return (
    <div data-testid={testId}>
      <h2 className="mb-2 text-sm font-medium">{title}</h2>
      {items.length === 0 ? (
        <p className="text-muted-foreground text-sm">暂时没有</p>
      ) : (
        <ul className="space-y-1">
          {items.map((item) => (
            <li key={item.id}>
              <Link
                to={`/knowledge/entries/${item.id}`}
                className="text-sm underline-offset-4 hover:underline"
              >
                {item.title}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
