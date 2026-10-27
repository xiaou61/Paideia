import {
  ComparisonTable,
  PlanDays,
  TopicTable,
  WeaknessCards,
  type CampAnswer,
  type CampForm,
  type CampItem,
  type CampPlan,
  type CampResult,
  type CampTask,
  createCampApi,
} from '@paideia/camp'
import {
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  PageHeader,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2 } from 'lucide-react'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const camps = createCampApi(api)

/**
 * 训练营详情（WORK-029）。
 *
 * <p>四段：**诊断**（一题一屏，答完才能交）→ **结果**（主题计数 + 薄弱点）→ **计划**（按天勾选）
 * → **复测**（并排对比）。
 *
 * <p>三条产品口径写在这里而不是组件里：
 * <ol>
 *   <li>**不打分**：页面上不出现总分、百分制、通过率、等级。弱项用"X / Y 题答对"说。</li>
 *   <li>**答完才能交**：全部题目作答之前提交按钮是禁用的——半份卷子的判定没有意义。</li>
 *   <li>**计费写在按钮旁**：开营与复测各是一次模型调用。</li>
 * </ol>
 */
export function CampDetailPage() {
  const params = useParams()
  // 路由参数可能是任意串（旧链接、手改地址）：NaN 会让 5 个 query 都以 NaN 为 key 发请求。
  // 与 KnowledgeEntryPage 同一写法：先判有效，无效直接禁用查询。
  const campId = Number(params.id)
  const valid = Number.isFinite(campId)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const camp = useQuery({
    queryKey: ['camp', campId],
    queryFn: () => camps.get(campId),
    enabled: valid,
  })
  const diagnosticItems = useQuery({
    queryKey: ['camp-items', campId, 'diagnostic'],
    queryFn: () => camps.items(campId, 'diagnostic'),
    enabled: valid,
  })
  const retestItems = useQuery({
    queryKey: ['camp-items', campId, 'retest'],
    queryFn: () => camps.items(campId, 'retest'),
    enabled: valid,
  })
  const diagnosticResult = useQuery({
    queryKey: ['camp-result', campId, 'diagnostic'],
    queryFn: () => camps.result(campId, 'diagnostic'),
    enabled: valid,
    // 没交卷时后端返回 400；那不是"出错"，只是还没有结果
    retry: false,
  })
  const retestResult = useQuery({
    queryKey: ['camp-result', campId, 'retest'],
    queryFn: () => camps.result(campId, 'retest'),
    retry: false,
    enabled: valid,
  })
  const plan = useQuery({
    queryKey: ['camp-plan', campId],
    queryFn: () => camps.plan(campId),
    retry: false,
    enabled: valid,
  })

  const submit = useMutation({
    mutationFn: ({ form, answers }: { form: CampForm; answers: CampAnswer[] }) =>
      camps.submit(campId, form, answers),
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['camp', campId] })
      void queryClient.invalidateQueries({ queryKey: ['camp-result', campId, variables.form] })
    },
  })

  const retest = useMutation({
    mutationFn: () => camps.retest(campId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['camp', campId] })
      void queryClient.invalidateQueries({ queryKey: ['camp-items', campId, 'retest'] })
      void queryClient.invalidateQueries({ queryKey: ['camp-result', campId, 'retest'] })
    },
  })

  const buildPlan = useMutation({
    mutationFn: () => camps.buildPlan(campId),
    onSuccess: (built) => {
      queryClient.setQueryData(['camp-plan', campId], built)
      void queryClient.invalidateQueries({ queryKey: ['camp', campId] })
    },
  })

  const toggle = useMutation({
    mutationFn: ({ task, done }: { task: CampTask; done: boolean }) =>
      camps.markTask(campId, task.id, done),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['camp-plan', campId] }),
  })

  const remove = useMutation({
    mutationFn: () => camps.remove(campId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['camps'] })
      navigate('/camp')
    },
  })

  if (camp.isPending) {
    return (
      <AppShell title="Paideia" actions={<LearnerHeader />} wide>
        <p className="text-muted-foreground text-sm">加载训练营…</p>
      </AppShell>
    )
  }
  if (camp.isError) {
    return (
      <AppShell title="Paideia" actions={<LearnerHeader />} wide>
        <PageHeader title="这个训练营看不到" />
        <p className="text-muted-foreground text-sm">{(camp.error as Error).message}</p>
        <Button className="mt-4" onClick={() => navigate('/camp')}>
          回到列表
        </Button>
      </AppShell>
    )
  }

  const detail = camp.data
  const retestGenerated = (retestItems.data ?? []).length > 0

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title={detail.resumeTitle}
        description={`${detail.days} 天训练营 · ${detail.itemCount} 道诊断题。诊断、计划、复测都在这一页。`}
      />

      <div className="mb-4 flex flex-wrap gap-2">
        <Badge variant={detail.diagnosticSubmitted ? 'success' : 'outline'}>
          {detail.diagnosticSubmitted ? '诊断已完成' : '待做诊断'}
        </Badge>
        <Badge variant={detail.hasPlan ? 'success' : 'outline'}>
          {detail.hasPlan ? '已有计划' : '待出计划'}
        </Badge>
        <Badge variant={detail.retestSubmitted ? 'success' : 'outline'}>
          {detail.retestSubmitted ? '复测已完成' : '待复测'}
        </Badge>
      </div>

      <Tabs defaultValue="diagnostic">
        <TabsList>
          <TabsTrigger value="diagnostic">诊断</TabsTrigger>
          <TabsTrigger value="result">结果</TabsTrigger>
          <TabsTrigger value="plan">计划</TabsTrigger>
          <TabsTrigger value="retest">复测</TabsTrigger>
        </TabsList>

        <TabsContent value="diagnostic" className="mt-4">
          {detail.diagnosticSubmitted ? (
            <AlreadySubmitted note="诊断卷已经交过了，结果在「结果」标签里。" />
          ) : (
            <QuizPanel
              items={diagnosticItems.data ?? []}
              loading={diagnosticItems.isPending}
              submitting={submit.isPending}
              error={submit.isError ? (submit.error as Error).message : null}
              onSubmit={(answers) => submit.mutate({ form: 'diagnostic', answers })}
            />
          )}
        </TabsContent>

        <TabsContent value="result" className="mt-4">
          {diagnosticResult.data !== undefined ? (
            <div className="space-y-4" data-testid="camp-result">
              <Card>
                <CardContent className="py-4">
                  <TopicTable topics={diagnosticResult.data.topics} />
                </CardContent>
              </Card>
              <WeaknessCards
                topics={diagnosticResult.data.topics}
                weak={diagnosticResult.data.weak}
                strong={diagnosticResult.data.strong}
              />
              <div className="flex flex-wrap items-center gap-3">
                <Button
                  onClick={() => buildPlan.mutate()}
                  disabled={buildPlan.isPending}
                  data-testid="camp-build-plan"
                >
                  {buildPlan.isPending ? '正在排期…' : '按薄弱点排一份计划'}
                </Button>
                <span className="text-muted-foreground text-xs">排期不调模型，不扣积分。</span>
              </div>
            </div>
          ) : (
            <p className="text-muted-foreground text-sm">
              {detail.diagnosticSubmitted ? '加载结果…' : '还没有结果，先做诊断卷。'}
            </p>
          )}
        </TabsContent>

        <TabsContent value="plan" className="mt-4">
          {!detail.diagnosticSubmitted ? (
            <p className="text-muted-foreground text-sm">做完诊断卷才能出计划。</p>
          ) : plan.data !== undefined ? (
            <div className="space-y-4">
              <PlanDays
                plan={plan.data}
                onToggle={(task, done) => toggle.mutate({ task, done })}
                pendingTaskId={toggle.isPending ? (toggle.variables?.task.id ?? null) : null}
                onOpenEntry={(entryId) => navigate(`/knowledge/entries/${entryId}`)}
              />
              <div className="flex flex-wrap items-center gap-3">
                <Button variant="outline" onClick={() => buildPlan.mutate()} disabled={buildPlan.isPending}>
                  {buildPlan.isPending ? '正在重排…' : '重新排一份'}
                </Button>
                <span className="text-muted-foreground text-xs">旧计划的勾选记录会保留。</span>
              </div>
            </div>
          ) : (
            <div className="space-y-3">
              <p className="text-muted-foreground text-sm">还没有计划。</p>
              <Button onClick={() => buildPlan.mutate()} disabled={buildPlan.isPending}>
                {buildPlan.isPending ? '正在排期…' : '排一份计划'}
              </Button>
            </div>
          )}
        </TabsContent>

        <TabsContent value="retest" className="mt-4">
          {!detail.diagnosticSubmitted ? (
            <p className="text-muted-foreground text-sm">先做完诊断卷，再复测。</p>
          ) : detail.retestSubmitted ? (
            <div className="space-y-4">
              {retestResult.data?.previous != null ? (
                <ComparisonTable
                  compared={retestResult.data.previous.topics}
                  onlyInDiagnostic={retestResult.data.previous.onlyInDiagnostic}
                  onlyInRetest={retestResult.data.previous.onlyInRetest}
                />
              ) : (
                <p className="text-muted-foreground text-sm">加载对比…</p>
              )}
            </div>
          ) : retestGenerated ? (
            <div className="space-y-4">
              <QuizPanel
                items={retestItems.data ?? []}
                loading={retestItems.isPending}
                submitting={submit.isPending}
                error={submit.isError ? (submit.error as Error).message : null}
                onSubmit={(answers) => submit.mutate({ form: 'retest', answers })}
              />
            </div>
          ) : (
            <Card>
              <CardContent className="space-y-3 py-5">
                <p className="text-sm">复测会按诊断卷的主题与题量重新出一份题，题干不与诊断重复。</p>
                <div className="flex flex-wrap items-center gap-3">
                  <Button onClick={() => retest.mutate()} disabled={retest.isPending} data-testid="camp-retest">
                    {retest.isPending ? '正在出卷…' : '出复测卷'}
                  </Button>
                  <span className="text-muted-foreground text-xs">出卷会调用一次模型，按积分计费。</span>
                </div>
                {retest.isError ? (
                  <p className="text-destructive text-sm">{(retest.error as Error).message}</p>
                ) : null}
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>

      <div className="mt-8">
        <Button
          variant="outline"
          onClick={() => {
            if (window.confirm('删除这个训练营？题目、作答与计划都会一起删掉。')) {
              remove.mutate()
            }
          }}
          disabled={remove.isPending}
        >
          删除这个训练营
        </Button>
      </div>
    </AppShell>
  )
}

function AlreadySubmitted({ note }: { note: string }) {
  return (
    <Card>
      <CardContent className="flex items-center gap-3 py-5">
        <CheckCircle2 className="text-success size-5 shrink-0" />
        <p className="text-sm">{note}</p>
      </CardContent>
    </Card>
  )
}

/**
 * 一题一屏的作答面板。
 *
 * <p>作答状态**留在这里**而不是提到页面上：诊断卷与复测卷是两份独立的卷子，
 * 共用一个 answers 会让"给复测选了 B，结果改到了诊断卷同一题的 id 上"这类错误悄悄发生。
 */
function QuizPanel({
  items,
  loading,
  submitting,
  error,
  onSubmit,
}: {
  items: CampItem[]
  loading: boolean
  submitting: boolean
  error: string | null
  onSubmit: (answers: CampAnswer[]) => void
}) {
  const [index, setIndex] = useState(0)
  const [answers, setAnswers] = useState<Record<number, number>>({})

  if (loading) {
    return <p className="text-muted-foreground text-sm">加载题目…</p>
  }
  if (items.length === 0) {
    return <p className="text-muted-foreground text-sm">这一卷还没有题目。</p>
  }

  const item = items[Math.min(index, items.length - 1)]
  if (item === undefined) {
    return <p className="text-muted-foreground text-sm">这一卷还没有题目。</p>
  }
  const answered = Object.keys(answers).length
  const complete = answered === items.length
  const chosen = answers[item.id]

  return (
    <Card data-testid="camp-quiz">
      <CardContent className="space-y-4 py-5">
        <div className="flex items-center justify-between gap-3">
          <span className="text-muted-foreground text-xs tabular-nums" data-testid="camp-progress">
            第 {index + 1} / {items.length} 题
          </span>
          <Badge variant="outline">{item.topic}</Badge>
        </div>

        <p className="text-base leading-relaxed font-medium">{item.stem}</p>

        <div className="space-y-2">
          {item.options.map((option, optionIndex) => (
            <button
              key={optionIndex}
              type="button"
              onClick={() => setAnswers({ ...answers, [item.id]: optionIndex })}
              className={
                chosen === optionIndex
                  ? 'border-primary bg-primary/5 block w-full rounded-lg border px-3 py-2 text-left text-sm'
                  : 'border-border hover:border-primary/40 block w-full rounded-lg border px-3 py-2 text-left text-sm'
              }
              data-testid={`camp-option-${optionIndex}`}
            >
              {option}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={index === 0}
            onClick={() => setIndex(Math.max(0, index - 1))}
          >
            上一题
          </Button>
          <div className="flex gap-2">
            {index < items.length - 1 ? (
              <Button size="sm" onClick={() => setIndex(index + 1)}>
                下一题
              </Button>
            ) : (
              <Button
                size="sm"
                disabled={!complete || submitting}
                onClick={() => onSubmit(toAnswers(answers))}
                data-testid="camp-submit"
              >
                {submitting ? '交卷中…' : '交卷'}
              </Button>
            )}
          </div>
        </div>

        {!complete ? (
          <p className="text-muted-foreground text-xs" data-testid="camp-incomplete">
            还有 {items.length - answered} 题没答，答完才能交卷。
          </p>
        ) : null}
        {error !== null ? <p className="text-destructive text-sm">{error}</p> : null}
      </CardContent>
    </Card>
  )
}

function toAnswers(answers: Record<number, number>): CampAnswer[] {
  return Object.entries(answers).map(([itemId, choice]) => ({
    itemId: Number(itemId),
    choice,
  }))
}
