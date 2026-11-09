import {
  STAGE_LABELS,
  TIMELINE_LABELS,
  createIdentityApi,
  type JobStage,
  type JobTimeline,
  type LearnerProfileInput,
} from '@paideia/identity'
import { createAssistantApi, CreditsPanel } from '@paideia/assistant'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Button,
  Input,
  Label,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { BriefcaseBusiness, Check, MapPin } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'

import { api } from '../api'
import { AccountSecuritySection } from '../components/AccountSecuritySection'
import { LearnerHeader } from '../components/LearnerHeader'
import { errorMessage } from '@paideia/core'

const identity = createIdentityApi(api)

/**
 * 我的目标与阶段（原「身份与目标」，2026-11-09 去掉身份选择）。
 *
 * <p><b>为什么去掉身份</b>：后端只让 `job_seeker` 可选，界面上那个选择器实际只能点一项，
 * 却又宣称"选了会改变推荐"——用户直接要求"注册就注册，不要选这个身份"。现在这里只留
 * 四个真正被消费的字段：
 *
 * <ul>
 *   <li>目标岗位 → 从零开始按岗位排线、模拟面试按岗位出题；</li>
 *   <li>目标城市 → 新建简历时预填求职意向；</li>
 *   <li>当前阶段 → 首页最上面那条建议；</li>
 *   <li>招聘季 → 面试按秋招（广度）/ 春招（深度）换侧重。</li>
 * </ul>
 *
 * <p><b>接口仍带 `identity`</b>：后端契约没改（列还在、默认值不变），保存时原样回传，
 * 页面上不出现任何与身份有关的文案或控件。
 */
export function ProfilePage() {
  const { data, isLoading, error } = useQuery({ queryKey: ['profile'], queryFn: () => identity.profile() })
  const navigate = useNavigate()
  const location = useLocation()
  const assistantApi = useMemo(() => createAssistantApi(api), [])

  const [form, setForm] = useState<LearnerProfileInput | null>(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  // 服务端值到位后填进表单。**只填一次**：否则用户正在改的时候后台刷新会把他的输入盖掉
  // （与笔记页那个"同步 effect"是同一类缺陷，这里从开始就避开）。
  useEffect(() => {
    if (data !== undefined && form === null) {
      setForm({
        identity: data.identity,
        targetRole: data.targetRole,
        targetCity: data.targetCity,
        timeline: data.timeline,
        stage: data.stage,
      })
    }
  }, [data, form])

  // 失败时 form 永远是 null，所以 error 必须先判——否则这里会永久转圈、下面的错误提示成了死代码
  if (error !== null) {
    return (
      <AppShell title="Paideia" actions={<LearnerHeader />}>
        <Alert variant="destructive">
          <AlertTitle>读不到你的资料</AlertTitle>
          <AlertDescription>{errorMessage(error)}</AlertDescription>
        </Alert>
      </AppShell>
    )
  }

  // 加载中：form 还没灌进去。放在 error 之后，失败时才不会卡在这里
  if (isLoading || form === null) {
    return (
      <AppShell title="Paideia" actions={<LearnerHeader />}>
        <Spinner />
      </AppShell>
    )
  }

  const patch = (next: Partial<LearnerProfileInput>) => {
    setSaved(false)
    setForm((current) => (current === null ? current : { ...current, ...next }))
  }

  const submit = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      await identity.saveProfile(form)
      setSaved(true)
      // 保存完回到来的那一页（2026-11-06 用户要求）：首页「三步就能开始」的第 1 步点进来，
      // 保存后停在这里就断了——用户不知道下一步在首页。`key === 'default'` 表示这是本次
      // 会话的第一条历史（直接开 /profile 或书签进来），没有"上一页"可退，退回首页。
      if (location.key === 'default') {
        void navigate('/', { replace: true })
      } else {
        void navigate(-1)
      }
    } catch (cause) {
      setSaveError(errorMessage(cause))
    } finally {
      setSaving(false)
    }
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <div className="mx-auto max-w-2xl">
        <PageHeader
          title="目标与阶段"
          description="这四项决定推荐与提醒：目标岗位排学习线、目标城市预填简历、当前阶段决定首页那条建议、招聘季决定面试侧重。随时可以改。"
        />

        <div className="border-border bg-card shadow-card overflow-hidden rounded-xl border">
          <section className="border-border space-y-4 border-b p-6">
            <div>
              <h2 className="text-sm font-medium">求职目标</h2>
              <p className="text-muted-foreground mt-1 text-xs">
                用来挑推荐内容，不对外公开，留空也没关系。
              </p>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="profile-role">目标岗位</Label>
                <div className="relative">
                  <BriefcaseBusiness
                    aria-hidden
                    className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2"
                  />
                  <Input
                    id="profile-role"
                    data-testid="profile-target-role"
                    placeholder="例如：后端开发"
                    value={form.targetRole ?? ''}
                    onChange={(event) => {
                      patch({ targetRole: event.target.value })
                    }}
                    className="bg-background h-11 rounded-xl pl-9"
                  />
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="profile-city">目标城市</Label>
                <div className="relative">
                  <MapPin
                    aria-hidden
                    className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2"
                  />
                  <Input
                    id="profile-city"
                    data-testid="profile-target-city"
                    placeholder="例如：武汉"
                    value={form.targetCity ?? ''}
                    onChange={(event) => {
                      patch({ targetCity: event.target.value })
                    }}
                    className="bg-background h-11 rounded-xl pl-9"
                  />
                </div>
              </div>
            </div>
          </section>

          <section className="border-border space-y-5 border-b p-6">
            <div>
              <h2 className="text-sm font-medium">进度</h2>
              <p className="text-muted-foreground mt-1 text-xs">
                「当前阶段」决定首页最上面那条建议：投递期先体检简历、笔试期先清到期复习、面试期先练一场模拟面试。没填就不显示建议。
              </p>
            </div>

            <div className="grid gap-5 sm:grid-cols-2">
              <ChoiceChips
                label="招聘季"
                testId="profile-timeline"
                value={form.timeline}
                options={[
                  { value: null, label: '未填写' },
                  ...(Object.entries(TIMELINE_LABELS) as [JobTimeline, string][]).map(
                    ([value, label]) => ({ value, label }),
                  ),
                ]}
                onChange={(value) => {
                  patch({ timeline: value })
                }}
              />
              <ChoiceChips
                label="当前阶段"
                testId="profile-stage"
                value={form.stage}
                options={[
                  { value: null, label: '未填写' },
                  ...(Object.entries(STAGE_LABELS) as [JobStage, string][]).map(([value, label]) => ({
                    value,
                    label,
                  })),
                ]}
                onChange={(value) => {
                  patch({ stage: value })
                }}
              />
            </div>
          </section>

          <div className="bg-muted/40 flex flex-wrap items-center gap-3 p-6">
            <Button
              data-testid="profile-save"
              disabled={saving}
              onClick={() => {
                void submit()
              }}
            >
              {saving ? '保存中…' : '保存'}
            </Button>
            {saved ? (
              <span
                className="text-muted-foreground flex items-center gap-1.5 text-sm"
                data-testid="profile-saved"
              >
                <Check className="size-4" />
                已保存
              </span>
            ) : null}
          </div>

          {saveError === null ? null : (
            <div className="border-border border-t p-6 pt-0">
              <Alert variant="destructive" className="mt-4">
                <AlertTitle>没保存成功</AlertTitle>
                <AlertDescription>{saveError}</AlertDescription>
              </Alert>
            </div>
          )}

          {/* 账号与安全（WORK-037）：登录方式与学习资料是两件事，所以单独一块、单独保存 */}
          <AccountSecuritySection />

          {/* AI 积分（原独立设置页撤销后并入）：只留余额一行，明细不再展示 */}
          <section className="border-border border-t p-6" data-testid="profile-credits">
            <h2 className="mb-2 text-sm font-medium">AI 积分</h2>
            <CreditsPanel api={assistantApi} />
          </section>
        </div>
      </div>

    </AppShell>
  )
}

/**
 * 二选一 / 三选一的胶囊组。
 *
 * <p>选项只有两三个时下拉反而多一次点击，而且首页那一屏看不到全部选项。`role="radio"`
 * 而不是按钮组的态度：这里是单选，键盘方向键与读屏都该按单选理解。
 */
function ChoiceChips<T extends string>({
  label,
  testId,
  value,
  options,
  onChange,
}: {
  label: string
  testId: string
  value: T | null
  options: { value: T | null; label: string }[]
  onChange: (value: T | null) => void
}) {
  return (
    <div className="space-y-2" data-testid={testId}>
      <Label>{label}</Label>
      <div role="radiogroup" aria-label={label} className="flex flex-wrap gap-2">
        {options.map((option) => {
          const active = option.value === value
          return (
            <button
              key={option.value ?? 'none'}
              type="button"
              role="radio"
              aria-checked={active}
              data-testid={`${testId}-${option.value ?? 'none'}`}
              onClick={() => {
                onChange(option.value)
              }}
              className={`h-9 rounded-full border px-4 text-sm transition-colors outline-none focus-visible:ring-3 ${
                active
                  ? 'border-primary/45 bg-primary/10 text-primary font-medium'
                  : 'border-border text-muted-foreground hover:border-foreground/25 hover:bg-muted/60 hover:text-foreground'
              }`}
            >
              {option.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}
