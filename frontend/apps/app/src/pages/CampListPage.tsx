import { createCampApi } from '@paideia/camp'
import { createResumeApi } from '@paideia/resume'
import { AppShell, Button, Card, CardContent, PageHeader } from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Target } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const camps = createCampApi(api)
const resumes = createResumeApi(api)

/**
 * 训练营列表与开营（WORK-029）。
 *
 * <p>开营是两个决定：**用哪份简历**与**3 天还是 7 天**。都在这页做完，不另开向导——
 * 只有两个选项的向导比一个按钮更麻烦。
 *
 * <p>`?resume=<id>` 是从简历详情页带过来的（「用它开训练营」）。带了这个参数就直接选中它，
 * 用户不用再找一遍。
 */
export function CampListPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [params] = useSearchParams()
  const presetResume = params.get('resume')

  const list = useQuery({ queryKey: ['camps'], queryFn: () => camps.list() })
  const documents = useQuery({ queryKey: ['resumes'], queryFn: () => resumes.list() })

  const [resumeId, setResumeId] = useState<number | null>(
    presetResume === null ? null : Number(presetResume),
  )
  const [days, setDays] = useState<3 | 7>(3)

  const create = useMutation({
    mutationFn: () => {
      if (resumeId === null) {
        throw new Error('先选一份简历')
      }
      return camps.create(resumeId, days)
    },
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ['camps'] })
      navigate(`/camp/${created.id}`)
    },
  })

  const selected = resumeId ?? documents.data?.[0]?.id ?? null

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="用简历开一个训练营"
        description="先做一套诊断题，按薄弱点排每天学什么；走完后再测一次，两次并排看变化。"
      />

      <Card className="mb-6">
        <CardContent className="space-y-4 py-4">
          <div className="space-y-2">
            <p className="text-sm font-medium">用哪份简历</p>
            {documents.isPending ? (
              <p className="text-muted-foreground text-sm">加载简历…</p>
            ) : documents.data === undefined || documents.data.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                还没有简历。先去
                <Link to="/resume" className="text-primary mx-1 underline-offset-2 hover:underline">
                  简历页
                </Link>
                导入一份。
              </p>
            ) : (
              <div className="flex flex-wrap gap-2">
                {documents.data.map((document) => (
                  <button
                    key={document.id}
                    type="button"
                    onClick={() => setResumeId(document.id)}
                    className={
                      selected === document.id
                        ? 'border-primary bg-primary/5 rounded-lg border px-3 py-2 text-sm font-medium'
                        : 'border-border hover:border-primary/40 rounded-lg border px-3 py-2 text-sm'
                    }
                    data-testid={`camp-resume-${document.id}`}
                  >
                    {document.title}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="space-y-2">
            <p className="text-sm font-medium">几天</p>
            <div className="flex gap-2">
              {([3, 7] as const).map((option) => (
                <button
                  key={option}
                  type="button"
                  onClick={() => setDays(option)}
                  className={
                    days === option
                      ? 'border-primary bg-primary/5 rounded-lg border px-3 py-2 text-sm font-medium'
                      : 'border-border hover:border-primary/40 rounded-lg border px-3 py-2 text-sm'
                  }
                  data-testid={`camp-days-${option}`}
                >
                  {option} 天 · 每天 {option === 3 ? 90 : 60} 分钟
                </button>
              ))}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <Button
              disabled={selected === null || create.isPending}
              onClick={() => {
                setResumeId(selected)
                create.mutate()
              }}
              data-testid="camp-create"
            >
              {create.isPending ? '正在出卷…' : `开营并出 ${days === 3 ? 12 : 18} 道题`}
            </Button>
            {/* 计费口径写在按钮旁，不让用户在扣分之后才知道 */}
            <span className="text-muted-foreground text-xs">开营会调用一次模型，按积分计费。</span>
          </div>

          {create.isError ? (
            <p className="text-destructive text-sm">{(create.error as Error).message}</p>
          ) : null}
        </CardContent>
      </Card>

      <h2 className="mb-3 text-base font-medium">我的训练营</h2>
      {list.isPending ? <p className="text-muted-foreground text-sm">加载中…</p> : null}
      {list.isSuccess && list.data.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="camp-empty">
          还没有训练营。选一份简历开一个。
        </p>
      ) : null}

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid="camp-list">
        {(list.data ?? []).map((camp) => (
          <Link
            key={camp.id}
            to={`/camp/${camp.id}`}
            data-testid={`camp-card-${camp.id}`}
            className="border-border bg-card hover:border-primary/40 flex flex-col rounded-xl border p-5 transition-colors"
          >
            <div className="flex items-start gap-3">
              <span className="bg-primary/10 text-primary flex size-10 shrink-0 items-center justify-center rounded-lg">
                <Target className="size-5" />
              </span>
              <div className="min-w-0 flex-1">
                <h3 className="truncate text-base font-medium">{camp.resumeTitle}</h3>
                <p className="text-muted-foreground mt-0.5 text-xs">
                  {camp.days} 天 · {camp.itemCount} 道题
                </p>
              </div>
            </div>

            <p className="text-muted-foreground mt-3 text-sm">
              {camp.diagnosticSubmitted ? '诊断已完成' : '还没做诊断'}
              {camp.hasPlan ? ' · 有学习计划' : ''}
              {camp.retestSubmitted ? ' · 复测已完成' : ''}
            </p>

            <div className="mt-4 flex items-center justify-end">
              <ArrowRight className="text-muted-foreground size-4" />
            </div>
          </Link>
        ))}
      </div>
    </AppShell>
  )
}
