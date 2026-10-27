import {
  IDENTITY_LABELS,
  STAGE_LABELS,
  TIMELINE_LABELS,
  createIdentityApi,
  type Identity,
  type JobStage,
  type JobTimeline,
  type LearnerProfileInput,
} from '@paideia/identity'
import { useQuery } from '@tanstack/react-query'
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
  Label,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import {
  BookOpen,
  BriefcaseBusiness,
  Check,
  ChevronRight,
  GraduationCap,
  Landmark,
  MapPin,
} from 'lucide-react'
import { useEffect, useState, type ComponentType } from 'react'

import { api } from '../api'
import { AccountSecuritySection } from '../components/AccountSecuritySection'
import { LearnerHeader } from '../components/LearnerHeader'

const identity = createIdentityApi(api)

/** 身份卡片上的图标与一句话说明。只在这里用，所以不放进 `@paideia/identity`。 */
const IDENTITY_META: Record<
  Identity,
  { description: string; icon: ComponentType<{ className?: string }> }
> = {
  job_seeker: {
    description: '面试与岗位相关的知识优先，助手多一个模拟面试入口。',
    icon: BriefcaseBusiness,
  },
  postgrad: { description: '基础理论与专业课相关的内容优先。', icon: GraduationCap },
  civil_service: { description: '公共基础与行测申论相关的内容优先。', icon: Landmark },
  daily_learning: { description: '按兴趣和分类自由探索。', icon: BookOpen },
}

const ALL_IDENTITIES = Object.keys(IDENTITY_LABELS) as Identity[]

/**
 * 我的身份与画像（WORK-010 T-03）。
 *
 * <p><b>这里改什么都不影响权限</b>：身份只决定"首页推荐什么、导航谁排前面、助手以什么形态出现"。
 * 页面顶部把这句话写出来，是因为它是最容易被误读的一处——"选了考研"不等于"失去了求职功能"。
 *
 * <p>四个画像字段全部可空：清空输入框等于"没有这个值"，不是存一个空字符串。
 *
 * <p>身份用弹窗挑（2026-10-19）：四个身份各有一张卡片，选中态是主色描边 + 勾。
 * 一期只有求职者可选，其余以「准备中」出现在同一张网格里——这比一个只有一个选项的下拉更能
 * 说明"这个平台按身份给不同推荐"这件事。
 */
export function ProfilePage() {
  const { data, isLoading, error } = useQuery({ queryKey: ['profile'], queryFn: () => identity.profile() })

  const [form, setForm] = useState<LearnerProfileInput | null>(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [pickerOpen, setPickerOpen] = useState(false)

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
          <AlertTitle>读不到你的身份</AlertTitle>
          <AlertDescription>{error instanceof Error ? error.message : String(error)}</AlertDescription>
        </Alert>
      </AppShell>
    )
  }

  const selectable = data?.selectableIdentities ?? ['job_seeker']
  // 加载中：form 还没灌进去。放在 error 之后，失败时才不会卡在这里
  if (isLoading || form === null) {
    return (
      <AppShell title="Paideia" actions={<LearnerHeader />}>
        <Spinner />
      </AppShell>
    )
  }
  const meta = IDENTITY_META[form.identity]
  const IdentityIcon = meta.icon

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
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setSaving(false)
    }
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <div className="mx-auto max-w-2xl">
        <PageHeader
          title="身份与目标"
          description="身份只影响推荐顺序与助手的形态（比如求职者会多一个面试入口），不影响你能做什么——每个身份都能读全部内容、记笔记、用助手。"
        />

        <div className="border-border bg-card shadow-card overflow-hidden rounded-xl border">
          <section className="border-border border-b p-6">
            <div className="mb-3 flex items-end justify-between gap-3">
              <Label htmlFor="profile-identity">当前身份</Label>
              <span className="text-muted-foreground text-xs">随时可以换</span>
            </div>

            <button
              type="button"
              id="profile-identity"
              data-testid="profile-identity"
              onClick={() => {
                setPickerOpen(true)
              }}
              className="group border-border bg-background hover:border-primary/40 hover:bg-primary/[0.03] focus-visible:border-ring focus-visible:ring-ring/50 flex w-full items-center gap-4 rounded-xl border p-4 text-left transition-all outline-none focus-visible:ring-3"
            >
              <span
                aria-hidden
                className="tile-surface border-border text-primary flex size-12 shrink-0 items-center justify-center rounded-xl border"
              >
                <IdentityIcon className="size-5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-base font-medium">{IDENTITY_LABELS[form.identity]}</span>
                <span className="text-muted-foreground mt-0.5 block truncate text-sm">
                  {meta.description}
                </span>
              </span>
              <span className="text-muted-foreground group-hover:text-foreground flex shrink-0 items-center gap-1 text-sm transition-colors">
                更换
                <ChevronRight className="size-4" />
              </span>
            </button>

            <p className="text-muted-foreground mt-2.5 text-xs">
              一期只有「{IDENTITY_LABELS.job_seeker}」可选用；其它身份还在准备中。
            </p>
          </section>

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
                让助手知道你现在处在哪一步，回答会更贴近处境。
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

          {/* 账号与安全（WORK-037）：登录方式与身份是两件事，所以单独一块、单独保存 */}
          <AccountSecuritySection />
        </div>
      </div>

      <Dialog open={pickerOpen} onOpenChange={setPickerOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader className="gap-1.5 pr-8">
            <DialogTitle className="text-lg">选择你的身份</DialogTitle>
            <DialogDescription className="leading-6">
              身份决定首页先推荐什么、导航怎么排、助手以什么形态出现。它不改变你能做什么。
            </DialogDescription>
          </DialogHeader>

          <div className="mt-1 grid gap-3 sm:grid-cols-2">
            {ALL_IDENTITIES.map((key) => {
              const item = IDENTITY_META[key]
              const Icon = item.icon
              const enabled = selectable.includes(key)
              const active = form.identity === key
              return (
                <button
                  key={key}
                  type="button"
                  disabled={!enabled}
                  aria-pressed={active}
                  data-testid={`identity-option-${key}`}
                  onClick={() => {
                    patch({ identity: key })
                    setPickerOpen(false)
                  }}
                  className={`relative flex flex-col items-start rounded-xl border p-4 text-left transition-all outline-none focus-visible:ring-3 ${
                    active
                      ? 'border-primary bg-primary/5 ring-primary ring-1'
                      : enabled
                        ? 'border-border bg-background hover:border-primary/40 hover:bg-primary/[0.03]'
                        : 'border-border bg-muted/30 cursor-not-allowed opacity-70'
                  }`}
                >
                  {enabled ? null : (
                    <span className="bg-muted text-muted-foreground absolute top-3 right-3 rounded-full px-2 py-0.5 text-[0.6875rem]">
                      准备中
                    </span>
                  )}
                  <span
                    aria-hidden
                    className={`flex size-10 items-center justify-center rounded-lg border ${
                      active ? 'border-primary/30 bg-primary text-primary-foreground' : 'tile-surface border-border text-muted-foreground'
                    }`}
                  >
                    <Icon className="size-5" />
                  </span>
                  <span className="mt-3 flex items-center gap-1.5 text-sm font-medium">
                    {IDENTITY_LABELS[key]}
                    {active ? (
                      <span className="bg-primary text-primary-foreground flex size-4 items-center justify-center rounded-full">
                        <Check className="size-2.5" />
                      </span>
                    ) : null}
                  </span>
                  <span className="text-muted-foreground mt-1 block text-xs leading-5">
                    {item.description}
                  </span>
                </button>
              )
            })}
          </div>

          <p className="text-muted-foreground text-xs">
            一期只有「{IDENTITY_LABELS.job_seeker}」可选用；其它身份会随着功能上线陆续开放。
          </p>
        </DialogContent>
      </Dialog>
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
