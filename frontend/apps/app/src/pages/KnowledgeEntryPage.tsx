import {
  EntryToc,
  KnowledgeNeighborhood,
  MarkdownBody,
  PracticeEntry,
  SelfAssessmentButtons,
  createKnowledgeApi,
  createLearningApi,
  createServerProgressPort,
} from '@paideia/knowledge'
import type { EntryRef, SelfAssessmentLevel } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  PageHeader,
  Spinner,
  extractQuizzes,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAssistantContext } from '@paideia/assistant'
import { ArrowLeft, ArrowRight, ChevronLeft, ListTree } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { API_BASE_URL } from '../env'

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
  const { setEntry, ask } = useAssistantContext()

  /**
   * 源码解读的双向入口（WORK-014）：内容里的「问 AI」把一句话交给助手输入框，
   * 学习路径点击某站直接跳过去。两个都保持**稳定引用**——它们会进 `MarkdownBody`
   * 的 memo 依赖，每次渲染换新函数会让整篇正文重解析。
   */
  const openEntry = useCallback((id: number) => navigate(`/knowledge/entries/${id}`), [navigate])

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
    }),
    [entryId],
  )

  const entry = useQuery({
    queryKey: ['knowledge-entry', entryId],
    queryFn: () => knowledge.entry(entryId),
    enabled: valid,
  })

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
  const practice = useMemo(
    () => extractQuizzes(entry.data?.body ?? ''),
    [entry.data?.body],
  )

  // 把"当前在读哪一条"交给悬浮壳：它是唯一知道这件事的地方。
  // 离开页面时清空，壳随即回到"打开一条内容后可用"的状态。
  useEffect(() => {
    if (entry.data === undefined) {
      return
    }
    // 课程条目用导师行为（WORK-020）：判断依据是服务端给的学习线类别，不是标题或地址。
    // 学情还没取到时先按通用助手——它没有学情段落，也不会假装看过记录。
    setEntry({
      id: entry.data.id,
      title: entry.data.title,
      harness: serverProgress.data?.lineKind === 'course' ? 'tutor' : 'assistant',
    })
    return () => setEntry(null)
  }, [entry.data, serverProgress.data?.lineKind, setEntry])

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
      <Link
        to={fromTrack?.fromTrackSlug ? `/learn/${fromTrack.fromTrackSlug}` : '/knowledge'}
        className="text-muted-foreground hover:text-foreground mb-6 inline-flex items-center gap-1 text-sm transition-colors"
        data-testid={fromTrack?.fromTrackSlug ? 'back-to-track' : 'back-to-categories'}
      >
        <ChevronLeft className="size-4" />
        {fromTrack?.fromTrackSlug
          ? `返回「${fromTrack.fromTrackName ?? '学习轨道'}」关卡地图`
          : '返回知识库'}
      </Link>

      {!valid ? (
        <Alert variant="destructive" data-testid="entry-state">
          <AlertTitle>地址里的条目编号不合法</AlertTitle>
        </Alert>
      ) : null}

      {valid && entry.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="entry-state">
          <Spinner />
          加载条目…
        </p>
      ) : null}

      {valid && entry.isError ? (
        <Alert variant="destructive" data-testid="entry-state">
          <AlertTitle>打不开这条内容</AlertTitle>
          <AlertDescription>{(entry.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {entry.isSuccess && serverProgress.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="entry-progress-loading">
          <Spinner />
          读取学习记录…
        </p>
      ) : null}

      {entry.isSuccess && !serverProgress.isPending ? (
        <div className="grid gap-12 lg:grid-cols-[minmax(0,1fr)_15rem]">
          <article className="min-w-0 lg:max-w-[46rem]">
            {/*
              "我在哪个分类里"是真信息，但它该是可点的面包屑而不是标题上方的小标签：
              标签只能看，面包屑能回去。
            */}
            {entry.data.categoryId === null || entry.data.categoryName === null ? null : (
              <Link
                to={`/knowledge/categories/${entry.data.categoryId}`}
                className="text-muted-foreground hover:text-foreground mb-3 inline-block text-sm"
              >
                {entry.data.categoryName}
              </Link>
            )}
            <PageHeader title={entry.data.title} />

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
                <EntryToc markdown={entry.data.body} testId="entry-toc-mobile" />
              </div>
            </details>

            <MarkdownBody
              markdown={practice.body}
              mediaBaseUrl={API_BASE_URL}
              entryId={entry.data.id}
              entryTitle={entry.data.title}
              onAskAssistant={ask}
              onOpenEntry={openEntry}
              sandbox={sandbox}
              progress={progressPort}
              recordAttempt={recordAttempt}
            />

            {/* 练习册（WORK-048）：题目从正文里摘出来，读完想测再测 */}
            <PracticeEntry
              quizzes={practice.quizzes}
              entryTitle={entry.data.title}
              onAttempt={recordAttempt}
              onScore={(quizId, correct, total) =>
                progressPort?.update({ quizzes: { [quizId]: { score: correct, total } } })
              }
            />

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
              <EntryToc markdown={entry.data.body} />
            </div>
          </aside>
        </div>
      ) : null}
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
