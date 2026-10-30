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
  Input,
  PageHeader,
  Separator,
  Spinner,
} from '@paideia/ui'
import {
  acceptServerRevision,
  beginSave,
  createResumeApi,
  forceOverwrite,
  initialState,
  markDirty,
  saveFailed,
  saveSucceeded,
  type AutosaveState,
  type Checkup,
  type ResumeData,
} from '@paideia/resume'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Eye, History, Plus, Sparkles, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const resumes = createResumeApi(api)

/** 分区。左侧导航用它；每一项都对应 {@link ResumeData} 的一个字段。 */
const SECTIONS = [
  { key: 'basics', label: '基本信息' },
  { key: 'intent', label: '求职意向' },
  { key: 'education', label: '教育经历' },
  { key: 'work', label: '工作与实习' },
  { key: 'projects', label: '项目经历' },
  { key: 'skills', label: '技能' },
  { key: 'awards', label: '证书与奖项' },
  { key: 'selfEvaluation', label: '自我评价' },
] as const

type SectionKey = (typeof SECTIONS)[number]['key']

/**
 * 简历编辑器（WORK-018 REQ-005..011）。
 *
 * <p><b>自动保存是防抖的，且失败不回填</b>。这条规则由 `@paideia/resume` 的状态机钉住并单测
 * （`autosave.test.ts`）——本项目已经因为"同步 effect 把正在打的字冲掉"踩过一次坑，
 * 而那一次的教训是：不要用"服务端返回的数据"去覆盖用户正在编辑的输入框。
 *
 * <p><b>冲突给两个选项，不静默覆盖</b>：两个标签页同时编辑时，revision 对不上会返回 409，
 * 界面必须让用户选"用我的覆盖"还是"重新加载"。
 */
export function ResumeEditorPage() {
  const { id } = useParams<{ id: string }>()
  const documentId = Number(id)
  const navigate = useNavigate()

  const [section, setSection] = useState<SectionKey>('basics')
  const [data, setData] = useState<ResumeData | null>(null)
  const [title, setTitle] = useState('')
  const [autosave, setAutosave] = useState<AutosaveState>(initialState(1))
  const [error, setError] = useState<string | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [checkup, setCheckup] = useState<Checkup | null>(null)

  const { data: document, isPending } = useQuery({
    queryKey: ['resume', documentId],
    queryFn: () => resumes.get(documentId),
    enabled: Number.isFinite(documentId),
  })

  // 首次加载把服务端内容灌进编辑态。**只在 document 首次到达时做一次**：
  // 每次 refetch 都灌一次就是"把正在打的字冲掉"的经典写法。
  const seeded = useRef(false)
  useEffect(() => {
    if (document !== undefined && !seeded.current) {
      seeded.current = true
      setData(document.data)
      setTitle(document.title)
      setAutosave(initialState(document.revision))
    }
  }, [document])

  const refreshCheckup = useCallback(async () => {
    try {
      setCheckup(await resumes.checkup(documentId))
    } catch {
      // 体检失败不该打断编辑：它只是提示，不是阻断
    }
  }, [documentId])

  useEffect(() => {
    if (document !== undefined) {
      void refreshCheckup()
    }
  }, [document, refreshCheckup])

  const save = useMutation({
    mutationFn: (input: { data: ResumeData; title: string; revision: number }) =>
      resumes.save(documentId, input),
    onSuccess: (saved) => {
      setAutosave((state) => saveSucceeded(state, saved.revision, Date.now()))
      void refreshCheckup()
    },
    onError: (failure: Error & { code?: number }) => {
      // 409 = 冲突（业务码 40900）。**不覆盖输入框内容**，只改状态。
      const conflict = failure.code === 40900 || failure.message.includes('被改过')
      setAutosave((state) => saveFailed(state, failure.message, conflict))
    },
  })

  // 防抖：800ms 内连续输入只发一次 PUT。依赖里**不含** data，否则每次输入都会重置定时器
  // 反而变成永远不保存；这里用 ref 读取最新值。
  const latest = useRef<{ data: ResumeData | null; title: string; revision: number }>({
    data: null,
    title: '',
    revision: 1,
  })
  latest.current = { data, title, revision: autosave.revision }

  useEffect(() => {
    if (autosave.status !== 'dirty') {
      return
    }
    const timer = window.setTimeout(() => {
      const snapshot = latest.current
      if (snapshot.data === null) {
        return
      }
      setAutosave((state) => beginSave(state))
      save.mutate({ data: snapshot.data, title: snapshot.title, revision: snapshot.revision })
    }, 800)
    return () => window.clearTimeout(timer)
  }, [autosave.status, save])

  const touch = (next: ResumeData) => {
    setData(next)
    setAutosave((state) => markDirty(state))
  }

  const patch = (updater: (current: ResumeData) => ResumeData) => {
    if (data === null) {
      return
    }
    touch(updater(data))
  }

  if (isPending || data === null) {
    return (
      <AppShell title="Paideia" actions={<LearnerHeader />} wide>
        <div className="flex justify-center py-20">
          <Spinner />
        </div>
      </AppShell>
    )
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title={title}
        description="改动会自动保存。投岗位时用「按岗位优化」生成另一份，主简历不会被改。"
        actions={
          <div className="flex items-center gap-2">
            <SaveStatus state={autosave} />
            <Button variant="outline" onClick={() => setHistoryOpen(true)}>
              <History className="size-4" />
              历史
            </Button>
            <Button variant="outline" onClick={() => navigate(`/resume/${documentId}/preview`)}>
              <Eye className="size-4" />
              预览
            </Button>
            <Button onClick={() => navigate(`/resume/${documentId}/optimize`)}>
              <Sparkles className="size-4" />
              按岗位优化
            </Button>
          </div>
        }
      />

      {error !== null && (
        <Alert variant="destructive" className="mb-4">
          <AlertTitle>出错了</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {autosave.status === 'conflict' && (
        <Alert variant="destructive" className="mb-4" data-testid="resume-conflict">
          <AlertTitle>这份简历在别处被改过了</AlertTitle>
          <AlertDescription className="space-y-2">
            <p>你的改动还在编辑框里，没有被覆盖。选一个：</p>
            <div className="flex gap-2">
              <Button
                size="sm"
                onClick={() => {
                  setAutosave((state) => forceOverwrite(state, state.revision))
                  void resumes.get(documentId).then((fresh) => {
                    setAutosave((state) => forceOverwrite(state, fresh.revision))
                  })
                }}
              >
                用我的版本覆盖
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={async () => {
                  const fresh = await resumes.get(documentId)
                  setData(fresh.data)
                  setTitle(fresh.title)
                  setAutosave((state) => acceptServerRevision(state, fresh.revision))
                }}
              >
                重新加载（放弃我的改动）
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}

      <div className="grid gap-6 lg:grid-cols-[200px_1fr_280px]">
        <nav className="flex flex-row flex-wrap gap-1 lg:flex-col" aria-label="简历分区">
          {SECTIONS.map((item) => (
            <Button
              key={item.key}
              variant={section === item.key ? 'secondary' : 'ghost'}
              className="justify-start"
              size="sm"
              onClick={() => setSection(item.key)}
              data-testid={`resume-section-${item.key}`}
            >
              {item.label}
            </Button>
          ))}
        </nav>

        <div className="min-w-0">
          <SectionEditor
            section={section}
            data={data}
            onPatch={patch}
            onTitleChange={(value) => {
              setTitle(value)
              setAutosave((state) => markDirty(state))
            }}
            title={title}
          />
        </div>

        <aside>
          <CheckupPanel checkup={checkup} />
        </aside>
      </div>

      <HistoryDialog
        open={historyOpen}
        onOpenChange={setHistoryOpen}
        documentId={documentId}
        revision={autosave.revision}
        onRestored={(restored) => {
          setData(restored.data)
          setTitle(restored.title)
          setAutosave((state) => acceptServerRevision(state, restored.revision))
          setHistoryOpen(false)
          void refreshCheckup()
        }}
      />
    </AppShell>
  )
}

function SaveStatus({ state }: { state: AutosaveState }) {
  const text =
    state.status === 'saving'
      ? '保存中…'
      : state.status === 'saved'
        ? `已保存 ${new Date(state.savedAt ?? Date.now()).toLocaleTimeString('zh-CN')}`
        : state.status === 'error' || state.status === 'conflict'
          ? '未保存'
          : state.status === 'dirty'
            ? '未保存'
            : '已保存'
  return (
    <Badge variant={state.status === 'saved' || state.status === 'idle' ? 'secondary' : 'destructive'}
      data-testid="resume-save-status">
      {text}
    </Badge>
  )
}

function CheckupPanel({ checkup }: { checkup: Checkup | null }) {
  if (checkup === null) {
    return null
  }
  const failing = checkup.items.filter((item) => !item.ok)
  return (
    <Card>
      <CardContent className="space-y-3 py-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-medium">体检</h2>
          <Badge variant={failing.length === 0 ? 'secondary' : 'outline'}>
            {failing.length === 0 ? '都通过了' : `${failing.length} 项待改`}
          </Badge>
        </div>
        <Separator />
        <ul className="space-y-2 text-sm" data-testid="resume-checkup">
          {checkup.items.map((item) => (
            <li key={item.code} className="flex gap-2">
              <span className={item.ok ? 'text-[var(--color-muted-foreground)]' : ''}>
                {item.ok ? '✓' : '•'}
              </span>
              <span>
                <span className={item.ok ? 'text-[var(--color-muted-foreground)]' : 'font-medium'}>
                  {item.title}
                </span>
                {!item.ok && (
                  <span className="block text-xs text-[var(--color-muted-foreground)]">{item.detail}</span>
                )}
              </span>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

function HistoryDialog({
  open,
  onOpenChange,
  documentId,
  revision,
  onRestored,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  documentId: number
  revision: number
  onRestored: (document: { data: ResumeData; title: string; revision: number }) => void
}) {
  const [error, setError] = useState<string | null>(null)
  const { data: history } = useQuery({
    queryKey: ['resume-history', documentId, open],
    queryFn: () => resumes.history(documentId),
    enabled: open,
  })
  const restore = useMutation({
    mutationFn: (historyId: number) => resumes.restore(documentId, historyId, revision),
    onSuccess: (document) => onRestored(document),
    onError: (failure: Error) => setError(failure.message),
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>编辑历史</DialogTitle>
          <DialogDescription>
            每次保存形成一个快照（一分钟内的连续保存会合并）。回滚本身也会记成一次新的编辑，
            所以回滚之后还能回来。
          </DialogDescription>
        </DialogHeader>
        {error !== null && <p className="text-sm text-[var(--color-destructive)]">{error}</p>}
        <ul className="max-h-72 space-y-2 overflow-auto">
          {history?.map((entry, index) => (
            <li key={entry.id} className="flex items-center justify-between gap-2 text-sm">
              <span>
                {index === 0 ? '（最近） ' : ''}
                {new Date(entry.createdAt).toLocaleString('zh-CN')}
              </span>
              <Button size="sm" variant="outline" onClick={() => restore.mutate(entry.id)}>
                回滚到这一版
              </Button>
            </li>
          ))}
          {history?.length === 0 && (
            <li className="text-sm text-[var(--color-muted-foreground)]">还没有历史记录。</li>
          )}
        </ul>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            关闭
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ── 分区表单 ──────────────────────────────────────────────────────

function SectionEditor({
  section,
  data,
  onPatch,
  title,
  onTitleChange,
}: {
  section: SectionKey
  data: ResumeData
  onPatch: (updater: (current: ResumeData) => ResumeData) => void
  title: string
  onTitleChange: (value: string) => void
}) {
  switch (section) {
    case 'basics':
      return (
        <Card>
          <CardContent className="space-y-3 py-5">
            <h2 className="font-medium">基本信息</h2>
            <Field label="姓名" value={data.basics.name}
              onChange={(value) => onPatch((current) => ({ ...current, basics: { ...current.basics, name: value } }))} />
            <Field label="电话" value={data.basics.phone}
              onChange={(value) => onPatch((current) => ({ ...current, basics: { ...current.basics, phone: value } }))} />
            <Field label="邮箱" value={data.basics.email}
              onChange={(value) => onPatch((current) => ({ ...current, basics: { ...current.basics, email: value } }))} />
            <Field label="城市" value={data.basics.city}
              onChange={(value) => onPatch((current) => ({ ...current, basics: { ...current.basics, city: value } }))} />
            <ListField label="链接（每行一个）" values={data.basics.links}
              onChange={(values) => onPatch((current) => ({ ...current, basics: { ...current.basics, links: values } }))} />
            <Separator />
            <Field label="这份简历的标题" value={title} onChange={onTitleChange} />
          </CardContent>
        </Card>
      )
    case 'intent':
      return (
        <Card>
          <CardContent className="space-y-3 py-5">
            <h2 className="font-medium">求职意向</h2>
            <Field label="目标岗位" value={data.intent.role}
              onChange={(value) => onPatch((current) => ({ ...current, intent: { ...current.intent, role: value } }))} />
            <Field label="目标城市" value={data.intent.city}
              onChange={(value) => onPatch((current) => ({ ...current, intent: { ...current.intent, city: value } }))} />
            <Field label="到岗时间" value={data.intent.availability}
              onChange={(value) => onPatch((current) => ({ ...current, intent: { ...current.intent, availability: value } }))} />
          </CardContent>
        </Card>
      )
    case 'selfEvaluation':
      return (
        <Card>
          <CardContent className="space-y-3 py-5">
            <h2 className="font-medium">自我评价</h2>
            <textarea
              className="min-h-40 w-full rounded-md border border-[var(--color-border)] bg-transparent p-3 text-sm"
              value={data.selfEvaluation}
              onChange={(event) =>
                onPatch((current) => ({ ...current, selfEvaluation: event.target.value }))
              }
            />
          </CardContent>
        </Card>
      )
    case 'education':
      return (
        <EntryList
          title="教育经历"
          entries={data.education}
          onAdd={() =>
            onPatch((current) => ({
              ...current,
              education: [...current.education, { school: '', degree: '', major: '', start: '', end: '', highlights: [] }],
            }))
          }
          onRemove={(index) =>
            onPatch((current) => ({ ...current, education: current.education.filter((_, i) => i !== index) }))
          }
          onMove={(from, to) =>
            onPatch((current) => ({ ...current, education: move(current.education, from, to) }))
          }
          render={(entry, index) => (
            <>
              <Field label="学校" value={entry.school}
                onChange={(value) => onPatch((current) => replace(current, 'education', index, { ...entry, school: value }))} />
              <div className="grid grid-cols-2 gap-2">
                <Field label="学历" value={entry.degree}
                  onChange={(value) => onPatch((current) => replace(current, 'education', index, { ...entry, degree: value }))} />
                <Field label="专业" value={entry.major}
                  onChange={(value) => onPatch((current) => replace(current, 'education', index, { ...entry, major: value }))} />
                <Field label="开始" value={entry.start}
                  onChange={(value) => onPatch((current) => replace(current, 'education', index, { ...entry, start: value }))} />
                <Field label="结束" value={entry.end}
                  onChange={(value) => onPatch((current) => replace(current, 'education', index, { ...entry, end: value }))} />
              </div>
              <ListField label="补充（每行一条）" values={entry.highlights}
                onChange={(values) => onPatch((current) => replace(current, 'education', index, { ...entry, highlights: values }))} />
            </>
          )}
        />
      )
    case 'work':
      return (
        <EntryList
          title="工作与实习"
          entries={data.work}
          onAdd={() =>
            onPatch((current) => ({
              ...current,
              work: [...current.work, { company: '', title: '', start: '', end: '', summary: '', bullets: [] }],
            }))
          }
          onRemove={(index) => onPatch((current) => ({ ...current, work: current.work.filter((_, i) => i !== index) }))}
          onMove={(from, to) => onPatch((current) => ({ ...current, work: move(current.work, from, to) }))}
          render={(entry, index) => (
            <>
              <div className="grid grid-cols-2 gap-2">
                <Field label="公司" value={entry.company}
                  onChange={(value) => onPatch((current) => replace(current, 'work', index, { ...entry, company: value }))} />
                <Field label="职位" value={entry.title}
                  onChange={(value) => onPatch((current) => replace(current, 'work', index, { ...entry, title: value }))} />
                <Field label="开始" value={entry.start}
                  onChange={(value) => onPatch((current) => replace(current, 'work', index, { ...entry, start: value }))} />
                <Field label="结束" value={entry.end}
                  onChange={(value) => onPatch((current) => replace(current, 'work', index, { ...entry, end: value }))} />
              </div>
              <Field label="一句话概要" value={entry.summary}
                onChange={(value) => onPatch((current) => replace(current, 'work', index, { ...entry, summary: value }))} />
              <ListField label="要点（每行一条）" values={entry.bullets}
                onChange={(values) => onPatch((current) => replace(current, 'work', index, { ...entry, bullets: values }))} />
            </>
          )}
        />
      )
    case 'projects':
      return (
        <EntryList
          title="项目经历"
          entries={data.projects}
          onAdd={() =>
            onPatch((current) => ({
              ...current,
              projects: [...current.projects, { name: '', role: '', period: '', bullets: [] }],
            }))
          }
          onRemove={(index) =>
            onPatch((current) => ({ ...current, projects: current.projects.filter((_, i) => i !== index) }))
          }
          onMove={(from, to) => onPatch((current) => ({ ...current, projects: move(current.projects, from, to) }))}
          render={(entry, index) => (
            <>
              <Field label="项目名" value={entry.name}
                onChange={(value) => onPatch((current) => replace(current, 'projects', index, { ...entry, name: value }))} />
              <div className="grid grid-cols-2 gap-2">
                <Field label="角色" value={entry.role}
                  onChange={(value) => onPatch((current) => replace(current, 'projects', index, { ...entry, role: value }))} />
                <Field label="时间" value={entry.period}
                  onChange={(value) => onPatch((current) => replace(current, 'projects', index, { ...entry, period: value }))} />
              </div>
              <ListField label="要点（每行一条）" values={entry.bullets}
                onChange={(values) => onPatch((current) => replace(current, 'projects', index, { ...entry, bullets: values }))} />
            </>
          )}
        />
      )
    case 'skills':
      return (
        <EntryList
          title="技能"
          entries={data.skills}
          onAdd={() => onPatch((current) => ({ ...current, skills: [...current.skills, { group: '', items: [] }] }))}
          onRemove={(index) =>
            onPatch((current) => ({ ...current, skills: current.skills.filter((_, i) => i !== index) }))
          }
          onMove={(from, to) => onPatch((current) => ({ ...current, skills: move(current.skills, from, to) }))}
          render={(entry, index) => (
            <>
              <Field label="分组（如 语言 / 中间件）" value={entry.group}
                onChange={(value) => onPatch((current) => replace(current, 'skills', index, { ...entry, group: value }))} />
              <ListField label="技能（每行一条）" values={entry.items}
                onChange={(values) => onPatch((current) => replace(current, 'skills', index, { ...entry, items: values }))} />
            </>
          )}
        />
      )
    case 'awards':
      return (
        <EntryList
          title="证书与奖项"
          entries={data.awards}
          onAdd={() => onPatch((current) => ({ ...current, awards: [...current.awards, { name: '', issuer: '', date: '' }] }))}
          onRemove={(index) =>
            onPatch((current) => ({ ...current, awards: current.awards.filter((_, i) => i !== index) }))
          }
          onMove={(from, to) => onPatch((current) => ({ ...current, awards: move(current.awards, from, to) }))}
          render={(entry, index) => (
            <div className="grid gap-2">
              <Field label="名称" value={entry.name}
                onChange={(value) => onPatch((current) => replace(current, 'awards', index, { ...entry, name: value }))} />
              <div className="grid grid-cols-2 gap-2">
                <Field label="颁发方" value={entry.issuer}
                  onChange={(value) => onPatch((current) => replace(current, 'awards', index, { ...entry, issuer: value }))} />
                <Field label="时间" value={entry.date}
                  onChange={(value) => onPatch((current) => replace(current, 'awards', index, { ...entry, date: value }))} />
              </div>
            </div>
          )}
        />
      )
  }
}

/** 把数组里某一项换成新值。用泛型而不是每处写一遍，是因为七种条目都走这一个动作。 */
function replace<T>(data: ResumeData, key: 'education' | 'work' | 'projects' | 'skills' | 'awards',
                    index: number, value: T): ResumeData {
  const list = [...(data[key] as unknown as T[])]
  list[index] = value
  return { ...data, [key]: list } as ResumeData
}

/** 上下移。顺序是数据结构的一部分（打印与提案都以它为准）。 */
function move<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length) {
    return list
  }
  const copy = [...list]
  const [item] = copy.splice(from, 1)
  if (item === undefined) {
    return list
  }
  copy.splice(to, 0, item)
  return copy
}

function EntryList<T>({
  title,
  entries,
  onAdd,
  onRemove,
  onMove,
  render,
}: {
  title: string
  entries: T[]
  onAdd: () => void
  onRemove: (index: number) => void
  onMove: (from: number, to: number) => void
  render: (entry: T, index: number) => React.ReactNode
}) {
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="font-medium">{title}</h2>
        <Button size="sm" variant="outline" onClick={onAdd}>
          <Plus className="size-4" />
          新增一条
        </Button>
      </div>
      {entries.length === 0 && (
        <p className="text-sm text-[var(--color-muted-foreground)]">这一块还是空的。</p>
      )}
      {entries.map((entry, index) => (
        <Card key={index}>
          <CardContent className="space-y-3 py-4">
            <div className="flex items-center justify-between">
              <span className="text-xs text-[var(--color-muted-foreground)]">第 {index + 1} 条</span>
              <div className="flex gap-1">
                <Button size="sm" variant="ghost" onClick={() => onMove(index, index - 1)} disabled={index === 0}>
                  <ArrowUp className="size-4" />
                </Button>
                <Button size="sm" variant="ghost" onClick={() => onMove(index, index + 1)}
                  disabled={index === entries.length - 1}>
                  <ArrowDown className="size-4" />
                </Button>
                <Button size="sm" variant="ghost" onClick={() => onRemove(index)}>
                  <Trash2 className="size-4" />
                </Button>
              </div>
            </div>
            {render(entry, index)}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="block text-sm">
      <span className="text-[var(--color-muted-foreground)]">{label}</span>
      <Input className="mt-1" value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  )
}

/** 多行列表：每行一条。用它而不是"动态加行"是因为用户从别处复制粘贴时最顺手。 */
function ListField({ label, values, onChange }: { label: string; values: string[]; onChange: (values: string[]) => void }) {
  return (
    <label className="block text-sm">
      <span className="text-[var(--color-muted-foreground)]">{label}</span>
      <textarea
        className="mt-1 min-h-24 w-full rounded-md border border-[var(--color-border)] bg-transparent p-2 text-sm"
        value={values.join('\n')}
        onChange={(event) => onChange(event.target.value.split('\n').filter((line) => line.trim() !== ''))}
      />
    </label>
  )
}
