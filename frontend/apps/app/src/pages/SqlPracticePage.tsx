import { createKnowledgeApi } from '@paideia/knowledge'
import { useAssistantContext } from '@paideia/assistant'
// 只引 schema 子入口：它是纯函数、不含 CodeMirror，静态引入不会把编辑器带进主 chunk。
// 编辑器/结果表/执行计划/侧栏都走下面的 lazy()。
import { parseSchema, toCompletionSchema } from '@paideia/sql-workbench/schema'
import { useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Button,
  Input,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Play, Search } from 'lucide-react'
import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)/**
 * 工作台组件必须**懒加载**（WORK-035 T-01 / AC-001）：CodeMirror 精简版 gzip 后约 137 kB，
 * 是本站唯一的重前端依赖。静态 import 会让每个进入首页的人为它付费。
 *
 * <p>懒加载由 `scripts/check-lazy-editor.mjs` 在构建时强制（已反向验证：改成静态 import 会让构建失败）。
 */
const SqlEditor = lazy(async () => {
  const module = await import('@paideia/sql-workbench')
  return { default: module.SqlEditor }
})

const ResultTables = lazy(async () => {
  const module = await import('@paideia/sql-workbench')
  return { default: module.ResultTables }
})

const ExplainTree = lazy(async () => {
  const module = await import('@paideia/sql-workbench')
  return { default: module.ExplainTree }
})

const SchemaSidebar = lazy(async () => {
  const module = await import('@paideia/sql-workbench')
  return { default: module.SchemaSidebar }
})

const ErDiagram = lazy(async () => {
  const module = await import('@paideia/sql-workbench')
  return { default: module.ErDiagram }
})

const ResultChart = lazy(async () => {
  const module = await import('@paideia/sql-workbench')
  return { default: module.ResultChart }
})

/**
 * SQL 工作台（WORK-032 的自由练习，WORK-035 起升级）。
 *
 * <p><b>与关卡的区别是"不计分"</b>：这里跑什么、跑对没跑对，都不写 `learning_progress`。
 * 关卡里的 SQL 题要判题、要计入这一关的通过与否；这里只是"随时打开、随便写"。
 *
 * <p><b>执行计划是一次独立的运行</b>：点「看执行计划」会真的起一个容器跑 `EXPLAIN FORMAT=TREE`，
 * 所以它同样占配额。这样做的理由是语义干净——计划与数据不会拼在同一个输出里让前端去猜。
 *
 * <p>数据集仍只有服务端下发的那些（隐藏集不下发，见 WORK-035 的修复）。
 */
export function SqlPracticePage() {
  const [code, setCode] = useState('SELECT * FROM customers;')
  const [tables, setTables] = useState<{ statementIndex: number; columns: string[]; rows: string[][] }[] | null>(null)
  const [explainOutput, setExplainOutput] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [dataset, setDataset] = useState<string | null>(null)

  const { setPageContext, pendingSql, clearPendingSql, ask } = useAssistantContext()
  const [searchParams] = useSearchParams()
  // 从关卡跳过来（WORK-035 T-14）：带 entryId + blockKey 就在工作台里打开那道题
  const loadEntryId = Number(searchParams.get('entryId') ?? '')
  const loadBlockKey = searchParams.get('blockKey') ?? ''

  const status = useQuery({
    queryKey: ['sandbox-status'],
    queryFn: () => knowledge.sandboxStatus(),
  })

  const datasets = status.data?.datasets ?? []
  const selected = dataset ?? datasets[0]?.key ?? null
  const sqlReady = status.data?.ready === true && (status.data?.languages ?? []).includes('sql')
  const remaining =
    status.data?.sqlDailyLimit === undefined || status.data?.sqlUsedToday === undefined
      ? null
      : status.data.sqlDailyLimit - status.data.sqlUsedToday

  // 从关卡跳过来时载入那道题（T-14）：只在带参数时请求，普通访问不产生额外调用
  const challengeQuery = useQuery({
    queryKey: ['sql-challenge', loadEntryId, loadBlockKey],
    queryFn: async () => {
      const entry = await knowledge.entry(loadEntryId)
      const { findSqlChallenge } = await import('@paideia/sql-workbench')
      return findSqlChallenge(entry.body, loadBlockKey)
    },
    enabled: Number.isFinite(loadEntryId) && loadEntryId > 0 && loadBlockKey !== '',
    staleTime: Infinity,
  })
  const challenge = challengeQuery.data ?? null

  // 结构快照只读、不占配额，所以选中数据集就拉一次；切数据集会重新拉。
  const schemaQuery = useQuery({
    queryKey: ['dataset-schema', selected],
    queryFn: () => knowledge.datasetSchema(selected!),
    enabled: selected !== null,
    // 快照是构建期生成的静态内容，不会在会话中变——没必要反复拉
    staleTime: Infinity,
  })
  /**
   * **必须 memo**：这两个函数每次都返回新对象，而 `SqlEditor` 的 effect 依赖 `schema`。
   * 不 memo 的后果是——打一个字 → 页面重渲染 → schema 新引用 → 编辑器被销毁重建 → **每输一个字母丢一次焦点**。
   * （2026-10-28 用户实测报上来，编辑器里打不了第二个字符。）
   */
  const schema = useMemo(() => parseSchema(schemaQuery.data), [schemaQuery.data])
  const completionSchema = useMemo(() => toCompletionSchema(schema), [schema])
  const [showEr, setShowEr] = useState(false)
  const [showChart, setShowChart] = useState(false)
  /** AI 现场出的题（WORK-035 T-12）。期望结果集来自真跑参考解，判题在客户端。 */
  const [aiQuestion, setAiQuestion] = useState<{
    stem: string
    criteria: string
    explain: string
    columns: string[]
    rows: string[][]
  } | null>(null)

  const run = useMutation({
    mutationFn: async (explain: boolean) => {
      if (selected === null) {
        throw new Error('没有可用的数据集')
      }
      return knowledge.runCode({ language: 'sql', code, dataset: selected, explain })
    },
    onSuccess: (result, explain) => {
      setError(
        result.exitCode === 0 && !result.timedOut && !result.outOfMemory ? null : describeFailure(result),
      )
      if (explain) {
        // 执行计划的树在**一个字段值**里（实测：1 列 × 1 行，多行用 \n）。
        // 所以取第一个结果集的第一行第一列，而不是把 tables 交给结果表组件。
        const first = result.tables?.[0]
        setExplainOutput(first?.rows[0]?.[0] ?? null)
        setTables(null)
      } else {
        setTables(result.tables ?? [])
        setExplainOutput(null)
      }
      status.refetch()
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : String(cause)),
  })

  /**
   * 现场出一道题（WORK-035 T-12）。
   *
   * <p>后端会在沙箱里**真跑一次参考解**取期望结果集——不是模型自己说的。
   * 它同时花一次 AI 调用与一次沙箱运行，所以按钮在运行时显示"出题中…"。
   */
  const askAi = useMutation({
    mutationFn: () => {
      if (selected === null) {
        throw new Error('没有可用的数据集')
      }
      return knowledge.generateSqlQuestion(selected)
    },
    onSuccess: (question) => {
      setAiQuestion(question)
      setError(null)
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : String(cause)),
  })

  /**
   * 让 AI 造一个数据集（WORK-035 T-13）。
   *
   * <p>生成的 SQL 走**与手动上传完全相同的检查**（白名单 + 干跑）——后端不给 AI 开更宽松的路。
   * 成功后刷新数据集清单，新数据集会出现在选择器里。
   */
  const [datasetTopic, setDatasetTopic] = useState('')
  const createDataset = useMutation({
    mutationFn: () => knowledge.generateSqlDataset(datasetTopic.trim() === '' ? undefined : datasetTopic.trim()),
    onSuccess: async (created) => {
      setError(null)
      setDatasetTopic('')
      await status.refetch()
      setDataset(created.key)
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : String(cause)),
  })

  /**
   * 题目载入后填进编辑器、切到它能用的数据集（WORK-035 T-14）。
   *
   * <p>只在**首次**载入时填：之后用户改了编辑器，不该因为后台重新拉了一次条目
   * 就把他写的东西冲掉。
   */
  const challengeApplied = useRef<string | null>(null)
  useEffect(() => {
    if (challenge === null || challengeApplied.current === challenge.id) {
      return
    }
    challengeApplied.current = challenge.id
    setCode(challenge.starter)
    // 优先用公开数据集：隐藏集不该出现在选择器里，用户也不该手动选它
    const pick = challenge.dataset.find((key) => key !== 'orders-hidden-a') ?? challenge.dataset[0]
    if (pick !== undefined) {
      setDataset(pick)
    }
  }, [challenge])

  /**
   * 上报页面场景给助手（WORK-035 T-15）。
   *
   * <p>**只放错误或行数摘要，不放数据行**：结果集可能上千行，塞进提示词既贵又可能
   * 把用户没想分享的数据送进模型。模型想看结构有 `sql_describe_dataset`。
   *
   * <p>离开页面时必须清掉——否则从 SQL 页跳到别处，助手还在说"你这段 SQL……"。
   */
  useEffect(() => {
    if (selected === null) {
      setPageContext(null)
      return
    }
    const summary =
      error !== null
        ? `报错：${error}`
        : tables !== null
          ? `返回 ${tables.length} 个结果集，共 ${tables.reduce((sum, table) => sum + table.rows.length, 0)} 行`
          : explainOutput !== null
            ? '刚才看了执行计划'
            : ''
    setPageContext({ kind: 'sql-workbench', datasetKey: selected, draftSql: code, lastResult: summary })
    return () => setPageContext(null)
  }, [selected, code, error, tables, explainOutput, setPageContext])

  /**
   * 消费助手填回的 SQL（WORK-035 T-15）。
   *
   * <p>**只填回编辑器，不执行**：助手没有执行 SQL 的能力（跑一次要 3 秒与一次配额，
   * 写操作还会真的落数据）。用户点"运行"才算数——这与 WORK-006 的"自主只建议不动作"一致。
   */
  useEffect(() => {
    if (pendingSql === null) {
      return
    }
    setCode(pendingSql.text)
    clearPendingSql(pendingSql.id)
  }, [pendingSql, clearPendingSql])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="写一段 SQL，立刻看到结果"
        description="不计分、不进学习进度。每次都在干净的数据库里跑，随便改随便试。"
      />

      {status.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm">
          <Spinner />
          检查运行环境…
        </p>
      ) : null}

      {status.isSuccess && !sqlReady ? (
        <Alert data-testid="sql-practice-unavailable">
          <AlertTitle>SQL 练习暂不可用</AlertTitle>
          <AlertDescription>
            {status.data.reason ?? '沙箱未开启或 SQL 镜像缺失'}。课程里的选择题仍然可以做。
          </AlertDescription>
        </Alert>
      ) : null}

      {challenge === null ? null : (
        <Alert data-testid="sql-challenge-banner">
          <AlertTitle>正在试做关卡题：{challenge.title}</AlertTitle>
          <AlertDescription>
            {challenge.goal === undefined ? null : <span>考的是：{challenge.goal}。 </span>}
            <strong>这一题在关卡里是要计分的</strong>；在这里跑不会计入，随便改随便试。
          </AlertDescription>
        </Alert>
      )}

      {sqlReady ? (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-3">
              <label className="text-sm" htmlFor="sql-dataset">
                数据集
              </label>
              <select
                id="sql-dataset"
                className="border-border bg-card rounded-md border px-2 py-1 text-sm"
                value={selected ?? ''}
                onChange={(event) => setDataset(event.target.value)}
              >
                {datasets.map((option) => (
                  <option key={option.key} value={option.key}>
                    {option.name}
                  </option>
                ))}
              </select>
              {/*
                配额不常驻显示（WORK-035 T-17）：500 次是熔断器不是预算，把大数字挂在页面上
                只会让人紧张。只在**快到上限**（≤20）时提示——那时它才是有用的信息。
              */}
              {remaining === null || remaining > 20 ? null : (
                <span className="text-warning text-xs" data-testid="sql-practice-quota">
                  今天还剩 {remaining} 次
                </span>
              )}
            </div>

            {/* AI 造数据集（WORK-035 T-13）：生成的 SQL 与手动上传过同样的检查 */}
            <details className="border-border rounded-lg border" data-testid="sql-create-dataset">
              <summary className="cursor-pointer px-3 py-2 text-sm">
                造一个自己的数据集
                <span className="text-muted-foreground ml-2 text-xs">
                  （AI 生成，也可以自己粘贴 SQL）
                </span>
              </summary>
              <div className="border-border space-y-2 border-t px-3 py-3">
                <Input
                  value={datasetTopic}
                  onChange={(event) => setDatasetTopic(event.target.value)}
                  placeholder="想要什么样的数据？例如：一个小型图书馆（书、读者、借阅记录）"
                  aria-label="数据集描述"
                  data-testid="sql-dataset-topic"
                />
                <div className="flex items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    disabled={createDataset.isPending}
                    data-testid="sql-generate-dataset"
                    onClick={() => createDataset.mutate()}
                  >
                    {createDataset.isPending ? '生成中…' : '让 AI 造'}
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    只允许建表与插数；生成后会先在沙箱里真跑一遍，跑不通不会保存
                  </span>
                </div>
              </div>
            </details>

            <Suspense
              fallback={
                <div
                  className="border-border bg-muted/30 flex min-h-56 w-full items-center justify-center rounded-lg border"
                  data-testid="sql-editor-loading"
                >
                  <Spinner />
                </div>
              }
            >
              <SqlEditor
                value={code}
                onChange={setCode}
                schema={completionSchema}
                placeholder="写一段 SQL，按运行看结果"
                ariaLabel="SQL 编辑器"
              />
            </Suspense>

            <div className="flex flex-wrap items-center gap-3">
              <Button
                type="button"
                data-testid="sql-practice-run"
                disabled={run.isPending || selected === null}
                onClick={() => run.mutate(false)}
              >
                <Play className="size-4" />
                {run.isPending && run.variables === false ? '正在运行…' : '运行'}
              </Button>
              <Button
                type="button"
                variant="outline"
                data-testid="sql-practice-explain"
                disabled={run.isPending || selected === null}
                onClick={() => run.mutate(true)}
              >
                <Search className="size-4" />
                {run.isPending && run.variables === true ? '分析中…' : '看执行计划'}
              </Button>
              <Button
                type="button"
                variant="outline"
                data-testid="sql-ai-question"
                disabled={askAi.isPending || selected === null}
                onClick={() => askAi.mutate()}
              >
                {askAi.isPending ? '出题中…' : '让 AI 出题'}
              </Button>
              <Button
                type="button"
                variant="ghost"
                data-testid="sql-ask-assistant"
                onClick={() => ask('帮我看看这段 SQL 哪里有问题')}
              >
                问助手
              </Button>
              <span className="text-muted-foreground text-xs">
                每次运行约 4–5 秒（要起一个干净的数据库）
              </span>
            </div>

            {error === null ? null : (
              <Alert variant="destructive" data-testid="sql-practice-error">
                <AlertTitle>运行失败</AlertTitle>
                <AlertDescription className="whitespace-pre-wrap font-mono text-xs">
                  {error}
                </AlertDescription>
              </Alert>
            )}

            {/*
              AI 现场出的题（WORK-035 T-12）。
              **判定口径必须显示**：题目唯一性没法自动保证，用户看到的判据必须是明确的
              口径，而不是"AI 说你对"。
            */}
            {aiQuestion === null ? null : (
              <div className="border-border rounded-lg border p-3" data-testid="sql-ai-question-card">
                <p className="text-sm font-medium">AI 出的题</p>
                <p className="mt-1 text-sm whitespace-pre-wrap">{aiQuestion.stem}</p>
                <p className="text-muted-foreground mt-2 text-xs">
                  <strong>判定口径：</strong>
                  {aiQuestion.criteria}
                </p>
                <p className="text-muted-foreground mt-1 text-xs">
                  期望结果：{aiQuestion.columns.length} 列 × {aiQuestion.rows.length} 行
                  （来自参考解在这个数据集上的实际运行）
                </p>
                {aiQuestion.explain === '' ? null : (
                  <p className="text-muted-foreground mt-1 text-xs">{aiQuestion.explain}</p>
                )}
              </div>
            )}

            <Suspense fallback={<Spinner />}>
              {explainOutput === null ? null : <ExplainTree output={explainOutput} />}
            </Suspense>

            <Suspense fallback={<Spinner />}>
              {tables === null ? null : <ResultTables tables={tables} />}
            </Suspense>

            {/* 图表：只对第一张结果集提供（多语句时用户通常只关心主查询的那张） */}
            {tables !== null && tables.length > 0 && tables[0] !== undefined ? (
              <div className="space-y-2">
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  data-testid="sql-toggle-chart"
                  onClick={() => setShowChart((current) => !current)}
                >
                  {showChart ? '收起图表' : '把结果画成图'}
                </Button>
                {showChart ? (
                  <Suspense fallback={<Spinner />}>
                    <ResultChart table={tables[0]} />
                  </Suspense>
                ) : null}
              </div>
            ) : null}
          </div>

          <aside className="space-y-3">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-medium">表结构</h2>
              <Button
                type="button"
                size="sm"
                variant="ghost"
                data-testid="sql-toggle-er"
                onClick={() => setShowEr((current) => !current)}
              >
                {showEr ? '看列表' : '看关系图'}
              </Button>
            </div>
            <Suspense fallback={<Spinner />}>
              {showEr ? <ErDiagram schema={schema} /> : <SchemaSidebar schema={schema} />}
            </Suspense>
          </aside>
        </div>
      ) : null}
    </AppShell>
  )
}

function describeFailure(result: {
  stderr: string
  timedOut: boolean
  outOfMemory: boolean
  truncated: boolean
}): string {
  if (result.timedOut) {
    return '查询超过时限，已被终止。'
  }
  if (result.outOfMemory) {
    return '内存超限，已被终止。'
  }
  if (result.truncated) {
    return '返回结果过多，请加条件缩小范围。'
  }
  const message = result.stderr.trim()
  return message === '' ? '执行失败（没有错误输出）' : message
}
