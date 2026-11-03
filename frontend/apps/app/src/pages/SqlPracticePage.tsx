import { createKnowledgeApi } from '@paideia/knowledge'
import { useAssistantContext } from '@paideia/assistant'
// 只引 schema 子入口：它是纯函数、不含 CodeMirror，静态引入不会把编辑器带进主 chunk。
// 编辑器/结果表/执行计划/侧栏都走下面的 lazy()。
import {
  buildDatasetSql,
  parseSchema,
  parseCreateTableNames,
  toCompletionSchema,
  type DesignTable,
} from '@paideia/sql-workbench/schema'
import { useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  Input,
  LoadingRow,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Play, Search } from 'lucide-react'
import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'

import { api } from '../api'
import { DatasetCatalogDialog } from '../components/DatasetCatalogDialog'
import { DatasetDesigner } from '../components/DatasetDesigner'
import { LearnerHeader } from '../components/LearnerHeader'
import { errorMessage } from '@paideia/core'

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
  const [catalogOpen, setCatalogOpen] = useState(false)

  const {
    setPageContext,
    pendingSql,
    clearPendingSql,
    pendingDatasetSql,
    clearPendingDatasetSql,
    ask,
  } = useAssistantContext()
  const queryClient = useQueryClient()
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
    onError: (cause) => setError(errorMessage(cause)),
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
    onError: (cause) => setError(errorMessage(cause)),
  })

  /**
   * 让 AI 造一个数据集（WORK-035 T-13 / WORK-060）。
   *
   * <p>生成的 SQL 走**与手动上传完全相同的检查**（白名单 + 干跑）——后端不给 AI 开更宽松的路。
   * 成功后刷新数据集清单，新数据集会出现在选择器里。
   */
  // 助手产出的 SQL 要落到这里再保存（WORK-053）：不进编辑器，避免污染用户正在写的查询
  const [datasetSql, setDatasetSql] = useState('')
  const [datasetVisibility, setDatasetVisibility] = useState<'private' | 'public'>('private')
  const [datasetNameInput, setDatasetNameInput] = useState('')
  const [datasetMode, setDatasetMode] = useState<'new' | 'existing'>('new')
  const [datasetDialogOpen, setDatasetDialogOpen] = useState(false)
  const [addRows, setAddRows] = useState(5)

  /**
   * 新建数据集的两种编辑方式（2026-11-02 用户要求可视化建表）：点着建表（默认）或直接写 SQL。
   *
   * <p>**两份内容各存各的**：点着建表每次改动都会重生成 SQL，如果和手写共用一块文本，
   * 切回去就把用户手写的覆盖了。保存用当前页签那份（见 `effectiveSql`）。
   */
  const [designerTab, setDesignerTab] = useState<'visual' | 'sql'>('visual')
  const [designTables, setDesignTables] = useState<DesignTable[]>(() => [
    {
      name: '',
      columns: [
        { name: 'id', type: 'INT', primaryKey: true },
        { name: '', type: 'VARCHAR(50)', primaryKey: false },
      ],
    },
  ])
  const [designRows, setDesignRows] = useState(5)
  const design = useMemo(() => buildDatasetSql(designTables, designRows), [designTables, designRows])
  const effectiveSql = designerTab === 'visual' ? design.sql : datasetSql

  /** 数据集名：用户填了名字就用名字；没填给个通用名。 */
  const datasetName = () =>
    (datasetNameInput.trim() === '' ? '我的数据集' : datasetNameInput.trim()).slice(0, 60)

  /** 保存前的预览：只数 `CREATE TABLE` 字面量，真实结构以保存时的沙箱干跑为准。 */
  const previewTables = useMemo(() => parseCreateTableNames(effectiveSql), [effectiveSql])

  const saveDataset = useMutation({
    mutationFn: () => knowledge.uploadSqlDataset(datasetName(), effectiveSql, datasetVisibility),
    onSuccess: async (created) => {
      setError(null)
      setDatasetSql('')
      setDatasetNameInput('')
      // 可见性回到默认私有：上次选了「公开」不该让别人下一次无意中也发布（2026-11-03 审查）
      setDatasetVisibility('private')
      setDesignTables([
        {
          name: '',
          columns: [
            { name: 'id', type: 'INT', primaryKey: true },
            { name: '', type: 'VARCHAR(50)', primaryKey: false },
          ],
        },
      ])
      setDatasetDialogOpen(false)
      await status.refetch()
      void queryClient.invalidateQueries({ queryKey: ['sql-dataset-catalog'] })
      setDataset(created.key)
    },
    onError: (cause) => setError(errorMessage(cause)),
  })

  /**
   * 「让 AI 设计」（WORK-053；2026-11-02 改成直开助手）。
   *
   * <p>**不在页面上收集主题**：AI 自己会先问（要练什么、哪些表、多少行），用户回答即可。
   * 生成的 SQL 经回答里的「存为数据集」回到弹窗（见上面的 `pendingDatasetSql` effect），
   * 确认名字与可见性后才保存。
   */
  const askAiToDesign = () => {
    setDatasetDialogOpen(false)
    ask(
      '帮我造一个 SQL 练习数据集。先问我三个问题：用来练什么、要哪些表、大概多少行，然后用 planner_build_dataset 把 SQL 拼出来。',
    )
  }

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

  /**
   * 消费助手送来的「存为数据集」（WORK-060）。
   *
   * <p>**只回填面板，不保存**：唯一写库的动作仍是用户点「确认保存」。
   */
  useEffect(() => {
    if (pendingDatasetSql === null) {
      return
    }
    setDatasetSql(pendingDatasetSql.text)
    // 助手给的是 SQL 文本：切到「写 SQL」页签，别让它落在点着建表的视图后面看不见
    setDesignerTab('sql')
    setDatasetMode('new')
    setDatasetDialogOpen(true)
    clearPendingDatasetSql(pendingDatasetSql.id)
  }, [pendingDatasetSql, clearPendingDatasetSql])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="写一段 SQL，立刻看到结果"
        description="不计分、不进学习进度。每次都在干净的数据库里跑，随便改随便试。"
      />

      {status.isPending ? (
        <LoadingRow label="检查运行环境…" />
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
                data-testid="sql-dataset"
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
              <Button
                type="button"
                size="sm"
                variant="outline"
                data-testid="sql-open-dataset-catalog"
                onClick={() => setCatalogOpen(true)}
              >
                浏览数据集
              </Button>
              {/* 造数据集走弹窗（2026-11-02 用户反馈）：自己写在弹窗里写，AI 设计则直开助手提问 */}
              <Button
                type="button"
                size="sm"
                data-testid="sql-create-dataset"
                onClick={() => {
                  setDatasetMode('new')
                  setDatasetDialogOpen(true)
                }}
              >
                造一个自己的数据集
              </Button>
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

            {/*
              数据集工坊（WORK-035 T-13 / WORK-060；2026-11-02 由展开面板改弹窗）：
              「自己设计」= 在弹窗里直接写 SQL；「让 AI 设计」= 关弹窗、开助手，AI 先问问题，
              再把 SQL 经回答里的「存为数据集」带回来。
            */}
            <Dialog open={datasetDialogOpen} onOpenChange={setDatasetDialogOpen}>
              <DialogContent
                className="max-h-[85vh] overflow-y-auto sm:max-w-4xl"
                data-testid="sql-dataset-dialog"
              >
                <DialogHeader>
                  <DialogTitle>造一个自己的数据集</DialogTitle>
                  <DialogDescription>
                    点着建表就能造数据集，生成 SQL 后保存；也可以自己写 SQL，或让 AI 先问你几个问题再拼。
                  </DialogDescription>
                </DialogHeader>
                <div className="flex flex-wrap gap-2" role="group" aria-label="数据集模式">
                  <Button
                    type="button"
                    size="sm"
                    variant={datasetMode === 'new' ? 'default' : 'outline'}
                    onClick={() => setDatasetMode('new')}
                  >
                    新建数据集
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant={datasetMode === 'existing' ? 'default' : 'outline'}
                    onClick={() => setDatasetMode('existing')}
                  >
                    基于当前数据集改
                  </Button>
                </div>

                {datasetMode === 'new' ? (
                  <>
                    {/* 编辑方式（2026-11-02）：默认点着建表——不写 SQL 也能造数据集 */}
                    <div className="flex flex-wrap gap-2" role="group" aria-label="编辑方式">
                      <Button
                        type="button"
                        size="sm"
                        variant={designerTab === 'visual' ? 'secondary' : 'ghost'}
                        data-testid="sql-dataset-mode-visual"
                        onClick={() => setDesignerTab('visual')}
                      >
                        点着建表
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant={designerTab === 'sql' ? 'secondary' : 'ghost'}
                        data-testid="sql-dataset-mode-sql"
                        onClick={() => setDesignerTab('sql')}
                      >
                        写 SQL
                      </Button>
                    </div>

                    {designerTab === 'visual' ? (
                      <DatasetDesigner
                        tables={designTables}
                        onTablesChange={setDesignTables}
                        rows={designRows}
                        onRowsChange={setDesignRows}
                        result={design}
                      />
                    ) : (
                      <>
                        <textarea
                          value={datasetSql}
                          onChange={(event) => setDatasetSql(event.target.value)}
                          rows={8}
                          placeholder="自己写建表与插数 SQL，例如：CREATE TABLE books (id INT, title VARCHAR(60));"
                          aria-label="数据集 SQL"
                          data-testid="sql-dataset-sql"
                          className="border-border bg-background focus-visible:border-ring w-full rounded-lg border p-2 font-mono text-xs"
                        />
                        {previewTables.length === 0 ? null : (
                          <p className="text-muted-foreground text-xs" data-testid="sql-dataset-preview">
                            预计 {previewTables.length} 张表：{previewTables.join('、')}
                            （保存时以沙箱实际执行为准）
                          </p>
                        )}
                      </>
                    )}
                    <Input
                      value={datasetNameInput}
                      onChange={(event) => setDatasetNameInput(event.target.value)}
                      placeholder="数据集名字（不填就用「我的数据集」）"
                      aria-label="数据集名字"
                      data-testid="sql-dataset-name"
                    />
                    <fieldset className="flex flex-wrap items-center gap-3">
                      <legend className="sr-only">数据集可见性</legend>
                      <label className="flex items-center gap-1 text-sm">
                        <input
                          type="radio"
                          name="dataset-visibility"
                          checked={datasetVisibility === 'private'}
                          onChange={() => setDatasetVisibility('private')}
                          data-testid="sql-dataset-private"
                        />
                        私有（仅自己）
                      </label>
                      <label className="flex items-center gap-1 text-sm">
                        <input
                          type="radio"
                          name="dataset-visibility"
                          checked={datasetVisibility === 'public'}
                          onChange={() => setDatasetVisibility('public')}
                          data-testid="sql-dataset-public"
                        />
                        公开（所有人可见）
                      </label>
                    </fieldset>
                    <div className="flex flex-wrap items-center gap-2">
                      <Button
                        type="button"
                        size="sm"
                        disabled={saveDataset.isPending || effectiveSql.trim() === ''}
                        data-testid="sql-save-dataset"
                        onClick={() => saveDataset.mutate()}
                      >
                        {saveDataset.isPending ? '保存中…' : '确认保存为新数据集'}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        data-testid="sql-ask-dataset"
                        onClick={askAiToDesign}
                      >
                        让 AI 设计
                      </Button>
                    </div>
                    {/* 弹窗带遮罩，页面主体的错误提示被盖住：失败原因必须也出现在这里（2026-11-03 审查） */}
                    {saveDataset.isError ? (
                      <p className="text-destructive text-sm" data-testid="sql-dataset-save-error">
                        {errorMessage(saveDataset.error)}
                      </p>
                    ) : null}
                    <p className="text-muted-foreground text-xs">
                      只允许建表与插数；保存前会在沙箱里真跑一遍，跑不通不会存。
                    </p>
                    <p className="text-muted-foreground text-xs">
                      点「让 AI 设计」会打开助手：AI 先问你要练什么、哪些表、多少行，再把 SQL
                      带回来填进「写 SQL」里。
                    </p>
                  </>
                ) : (
                  <>
                    <p className="text-muted-foreground text-xs">
                      当前数据集：<strong>{selected ?? '未选择'}</strong>
                      {schema === null ? '' : `（${schema.tables.length} 张表）`}。
                      选一张表让 AI 在它基础上追加；<strong>原数据集不会被修改，保存的是新的一份</strong>。
                    </p>
                    <div className="flex flex-wrap items-center gap-2">
                      <label className="text-xs" htmlFor="sql-dataset-add-rows">
                        每张表加
                      </label>
                      <Input
                        id="sql-dataset-add-rows"
                        type="number"
                        min={1}
                        max={50}
                        value={addRows}
                        onChange={(event) =>
                          setAddRows(Math.max(1, Math.min(50, Number(event.target.value) || 1)))
                        }
                        className="w-20"
                        aria-label="每张表追加行数"
                      />
                      <span className="text-xs">行</span>
                    </div>
                    {schema === null || schema.tables.length === 0 ? (
                      <p className="text-muted-foreground text-xs">
                        这个数据集还没有结构信息，先选中它再试。
                      </p>
                    ) : (
                      <div className="flex flex-wrap gap-1.5">
                        {schema.tables.map((table) => (
                          <Button
                            key={table.name}
                            type="button"
                            size="sm"
                            variant="outline"
                            disabled={selected === null}
                            data-testid={`sql-dataset-add-${table.name}`}
                            onClick={() => {
                              setDatasetDialogOpen(false)
                              ask(
                                `当前数据集是 ${selected}。请先用 sql_read_dataset 读取它的完整 SQL，然后给表 ${table.name} 追加 ${addRows} 行数据，其他表保持不变。最后给出可以直接保存的完整 SQL。`,
                              )
                            }}
                          >
                            {table.name} +{addRows} 行
                          </Button>
                        ))}
                      </div>
                    )}
                    <p className="text-muted-foreground text-xs">
                      AI 会先读取完整 SQL 再追加；回答里点「存为数据集」就回到「新建数据集」里确认保存。
                    </p>
                  </>
                )}
              </DialogContent>
            </Dialog>

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

            {/* 补全是 CodeMirror 自带的能力，但**界面上不说就等于没有**（用户实测问"是不是没做"）。
                一行提示的成本，换回一个没人知道的功能。 */}
            <p className="text-muted-foreground mt-2 text-xs" data-testid="sql-editor-hint">
              输入时会自动补全表名、列名与 SQL 关键字；也可以按 Ctrl + 空格 手动触发。
            </p>

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
                disabled={selected === null}
                onClick={() =>
                  // 统一到对话（WORK-053 REQ-009）：预填意图，不自动发送
                  ask('按当前数据集和我的 SQL 水平出一道练习题：先别给答案，我写完再让你判。')
                }
              >
                让 AI 出题
              </Button>
              {/* 降级：助手不可用（余额/上游）时仍然能一次性生成，与「AI 造数据集」同一取舍 */}
              <Button
                type="button"
                variant="ghost"
                data-testid="sql-ai-question-direct"
                disabled={askAi.isPending || selected === null}
                onClick={() => askAi.mutate()}
              >
                {askAi.isPending ? '出题中…' : '直接生成'}
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

      <DatasetCatalogDialog
        open={catalogOpen}
        onOpenChange={setCatalogOpen}
        selectedKey={selected}
        onUse={(key) => {
          setDataset(key)
          // 目录可能比选择器的缓存更新（刚发的数据集）；拉一次让下拉框跟上
          void queryClient.invalidateQueries({ queryKey: ['sandbox-status'] })
        }}
      />
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
