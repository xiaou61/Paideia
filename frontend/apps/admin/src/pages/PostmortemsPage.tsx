import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
  ListPagination,
  Markdown,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  toast,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'

import { api } from '../api'
import { JSON_HEADERS, errorMessage, formatLocalDateTime } from '@paideia/core'

/**
 * 运维复盘（WORK-086）。
 *
 * <p>这一页是「AI 的判断」到「团队的经验」之间那道闸门：诊断完成后系统会起草一份
 * {@code DRAFT}，人改完点「确认」才变 {@code APPROVED}——只有 APPROVED 才会被后续诊断当作
 * 历史经验召回（{@code /api/v1/ops/admin/postmortems} 那七个端点）。所以这里所有按钮的
 * 文案都刻意用「确认」而不是「保存」：保存是写文件，确认是"我担保这条经验成立"。
 *
 * <p>贴图走知识库的附件通道（`POST /api/v1/knowledge/admin/files`），正文里只留
 * `![说明](url)`——复盘不新建图片存储，也不进 Git（凭据与截图留在平台内）。
 */
interface Postmortem {
  id: number
  incidentId: number | null
  diagnosisId: number | null
  alertName: string | null
  title: string | null
  severity: string | null
  status: 'DRAFT' | 'APPROVED' | 'ARCHIVED'
  rootCause: string | null
  impact: string | null
  timelineMd: string | null
  actionsMd: string | null
  bodyMd: string | null
  evidenceRefs: string | null
  origin: string | null
  createdBy: number | null
  approvedBy: number | null
  approvedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

interface Page {
  items: Postmortem[]
  total: number
  pageSize: number
  offset: number
}

interface UploadedFile {
  id: number
  url: string
  contentType: string | null
  size: number
  originalName: string | null
}

const STATUS: Record<Postmortem['status'], { label: string; variant: 'warning' | 'success' | 'secondary' }> = {
  DRAFT: { label: '草稿', variant: 'warning' },
  APPROVED: { label: '已确认', variant: 'success' },
  ARCHIVED: { label: '已归档', variant: 'secondary' },
}

const SEVERITY = ['critical', 'warning', 'info']

interface FormState {
  title: string
  severity: string
  alertName: string
  rootCause: string
  impact: string
  timelineMd: string
  actionsMd: string
  evidenceRefs: string
  bodyMd: string
}

const EMPTY_FORM: FormState = {
  title: '',
  severity: '',
  alertName: '',
  rootCause: '',
  impact: '',
  timelineMd: '',
  actionsMd: '',
  evidenceRefs: '',
  bodyMd: '',
}

function toForm(item: Postmortem): FormState {
  return {
    title: item.title ?? '',
    severity: item.severity ?? '',
    alertName: item.alertName ?? '',
    rootCause: item.rootCause ?? '',
    impact: item.impact ?? '',
    timelineMd: item.timelineMd ?? '',
    actionsMd: item.actionsMd ?? '',
    evidenceRefs: item.evidenceRefs ?? '',
    bodyMd: item.bodyMd ?? '',
  }
}

export function PostmortemsPage() {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  // 正文是否被人手改过：后端只在 bodyMd 为空时按结构化字段重渲染，若总是回传非空正文，
  // 改了根因/影响面之后正文（预览、下载、召回用的都是它）会一直是旧的。
  const [bodyTouched, setBodyTouched] = useState(false)
  const areas = useRef(new Map<string, HTMLTextAreaElement | null>())
  const filePicker = useRef<HTMLInputElement | null>(null)

  const params = new URLSearchParams({ page: String(page), size: '20' })
  if (status !== '') {
    params.set('status', status)
  }

  const list = useQuery({
    queryKey: ['ops', 'postmortems', status, page],
    queryFn: () => api.requestEnvelope<Page>(`/api/v1/ops/admin/postmortems?${params.toString()}`),
  })

  const detail = useQuery({
    queryKey: ['ops', 'postmortem', selectedId],
    enabled: selectedId !== null,
    queryFn: () => api.requestEnvelope<Postmortem>(`/api/v1/ops/admin/postmortems/${selectedId}`),
  })

  // 只在切换到另一条复盘时用缓存里的详情重置表单。
  // 不能依赖 detail.data 的引用变化：React Query 默认 refetchOnWindowFocus，切回标签页
  // 或保存后的 invalidate 都会重新拉详情，那样会静默覆盖用户正在编辑的内容。
  useEffect(() => {
    if (selectedId === null) return
    const cached = queryClient.getQueryData<Postmortem>(['ops', 'postmortem', selectedId])
    if (cached !== undefined) {
      setForm(toForm(cached))
      setBodyTouched(false)
    }
  }, [selectedId, queryClient])

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['ops', 'postmortems'] })
    void queryClient.invalidateQueries({ queryKey: ['ops', 'postmortem'] })
  }

  const payload = () => ({
    ...form,
    // 空串表示"清空这一项"，后端 null 才表示"不改"；编辑页总是送全量，语义更简单
    title: form.title.trim(),
    severity: form.severity.trim(),
    alertName: form.alertName.trim(),
    // 没手改过正文就送空串，让后端按结构化字段重新渲染，避免正文与字段长期不一致
    bodyMd: bodyTouched ? form.bodyMd : '',
  })

  const save = useMutation({
    mutationFn: (id: number) =>
      api.requestEnvelope<Postmortem>(`/api/v1/ops/admin/postmortems/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(payload()),
      }),
    onSuccess: (data) => {
      toast.success(data.status === 'DRAFT' ? '已保存；确认过的复盘被改动后会退回草稿' : '已保存')
      invalidate()
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  const approve = useMutation({
    mutationFn: async (id: number) => {
      // 先落库再确认：approve 只读库里现存内容，不先 PUT 会把编辑器里没保存的改动静默丢掉
      await api.requestEnvelope<Postmortem>(`/api/v1/ops/admin/postmortems/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(payload()),
      })
      return api.requestEnvelope<Postmortem>(`/api/v1/ops/admin/postmortems/${id}/approve`, { method: 'POST' })
    },
    onSuccess: () => {
      toast.success('已确认，后续诊断会把它当作历史经验召回')
      invalidate()
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  // 正文与结构化字段打架时的显式出路：丢掉手写正文，让后端按字段重渲染一遍。
  const regenerate = useMutation({
    mutationFn: (id: number) =>
      api.requestEnvelope<Postmortem>(`/api/v1/ops/admin/postmortems/${id}`, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify({ ...payload(), bodyMd: '' }),
      }),
    onSuccess: (data) => {
      setBodyTouched(false)
      setForm(toForm(data))
      toast.success('正文已按结构化字段重新生成')
      invalidate()
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  const archive = useMutation({
    mutationFn: (id: number) =>
      api.requestEnvelope<Postmortem>(`/api/v1/ops/admin/postmortems/${id}/archive`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('已归档；它不再参与知识召回，记录仍在')
      invalidate()
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  const upload = useMutation({
    mutationFn: (file: File) => {
      const body = new FormData()
      body.append('file', file)
      // 不要手动设 Content-Type：multipart 的 boundary 必须由浏览器生成
      return api.requestEnvelope<UploadedFile>('/api/v1/knowledge/admin/files', { method: 'POST', body })
    },
    onSuccess: (file, variables) => {
      insertAtCursor(`![${variables.name}](${file.url})`)
      toast.success('图片已插入正文；请确认截图里没有口令与连接串')
    },
    onError: (error) => toast.error(`图片上传失败：${errorMessage(error)}`),
  })

  // 下载必须走 api（带 Authorization）：直接用 <a href> 会 401，因为令牌不在 Cookie 里。
  const download = useMutation({
    mutationFn: async (item: Postmortem) => {
      const response = await api.stream(`/api/v1/ops/admin/postmortems/${item.id}/download`)
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `postmortem-${item.id}.md`
      // 必须挂进文档才触发下载（Firefox 等要求元素在文档里），且不能立刻 revoke：
      // 部分浏览器此时还没读完 blob，会得到空文件。
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    },
    onError: (error) => toast.error(`下载失败：${errorMessage(error)}`),
  })

  const insertAtCursor = (text: string) => {
    // 程序化插入（截图）也算"手改过正文"，否则保存时会被当成未改动而丢掉
    setBodyTouched(true)
    const area = areas.current.get('bodyMd')
    if (area === null || area === undefined) {
      setForm((prev) => ({ ...prev, bodyMd: `${prev.bodyMd}${text}` }))
      return
    }
    const start = area.selectionStart
    const end = area.selectionEnd
    setForm((prev) => ({
      ...prev,
      bodyMd: `${prev.bodyMd.slice(0, start)}${text}${prev.bodyMd.slice(end)}`,
    }))
    requestAnimationFrame(() => {
      area.focus()
      area.setSelectionRange(start + text.length, start + text.length)
    })
  }

  /** 粘贴图片直接上传：截图是复盘里最常用的证据，让人先存盘再挑文件太啰嗦。 */
  const onPasteImage = (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const image = Array.from(event.clipboardData.files).find((file) => file.type.startsWith('image/'))
    if (image === undefined) {
      return
    }
    event.preventDefault()
    upload.mutate(image)
  }

  const onPickFile = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (file !== undefined) {
      if (!file.type.startsWith('image/')) {
        toast.error('只能插入图片')
      } else {
        upload.mutate(file)
      }
    }
    event.target.value = ''
  }

  const items = list.data?.items ?? []
  const current = detail.data
  const totalPages = Math.max(1, Math.ceil((list.data?.total ?? 0) / (list.data?.pageSize ?? 20)))
  const editable = current !== undefined && current.status !== 'ARCHIVED'
  // 详情有三种"没有内容"的原因：没选、正在读、读失败。必须分开表述，
  // 否则加载中像是没点中、失败像是没选。
  let detailTitle = '选择一条复盘'
  let detailDescription = '左侧列表选一条查看与编辑。'
  let detailPlaceholder = '尚未选择。'
  if (selectedId !== null) {
    if (current !== undefined) {
      detailTitle = current.title ?? `复盘 #${current.id}`
      const statusText =
        current.status === 'APPROVED' ? '已确认' : current.status === 'DRAFT' ? '草稿' : '已归档'
      detailDescription = `${statusText} · 最近更新 ${formatLocalDateTime(current.updatedAt)}`
    } else if (detail.isError) {
      detailTitle = '读取失败'
      detailDescription = errorMessage(detail.error, '详情读取失败，稍后重试或换一条')
      detailPlaceholder = '详情读取失败。'
    } else {
      detailTitle = '正在读取…'
      detailDescription = '正在读取这条复盘…'
      detailPlaceholder = '正在读取…'
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="运维复盘"
        description="诊断完成后自动起草，人工确认后成为后续诊断的历史经验；未确认的草稿不参与召回。"
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
        <Card className="self-start">
          <CardHeader>
            <CardTitle>复盘列表</CardTitle>
            <CardDescription>按状态筛选；「自动起草」表示由告警诊断完成后自动生成。</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <Select
              value={status === '' ? 'all' : status}
              onValueChange={(value) => {
                setStatus(value === null || value === 'all' ? '' : value)
                setPage(1)
              }}
            >
              <SelectTrigger data-testid="postmortem-status-filter">
                <SelectValue placeholder="全部状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部状态</SelectItem>
                <SelectItem value="DRAFT">草稿</SelectItem>
                <SelectItem value="APPROVED">已确认</SelectItem>
                <SelectItem value="ARCHIVED">已归档</SelectItem>
              </SelectContent>
            </Select>

            {list.isPending ? (
              <Spinner />
            ) : list.isError ? (
              <p className="text-destructive text-sm">{errorMessage(list.error)}</p>
            ) : items.length === 0 ? (
              <Empty>
                <EmptyHeader>
                  <EmptyTitle>还没有复盘</EmptyTitle>
                  <EmptyDescription>到运维面板打开一次诊断，点「生成复盘」即可起草。</EmptyDescription>
                </EmptyHeader>
              </Empty>
            ) : (
              <ul className="flex flex-col gap-1" data-testid="postmortem-list">
                {items.map((item) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedId(item.id)}
                      className={`border-border w-full rounded-md border px-3 py-2 text-left text-sm ${
                        selectedId === item.id ? 'border-primary bg-accent' : 'hover:bg-accent/60'
                      }`}
                      data-testid={`postmortem-item-${item.id}`}
                    >
                      <span className="flex items-center gap-2">
                        <Badge variant={STATUS[item.status].variant}>{STATUS[item.status].label}</Badge>
                        {item.origin === 'auto' ? <Badge variant="outline">自动起草</Badge> : null}
                        <span className="truncate font-medium">{item.title ?? `复盘 #${item.id}`}</span>
                      </span>
                      <span className="text-muted-foreground mt-1 block truncate text-xs">
                        {item.alertName ?? '—'} · {formatLocalDateTime(item.updatedAt)}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <ListPagination
              total={list.data?.total ?? 0}
              page={page}
              totalPages={totalPages}
              onPage={setPage}
              testIdPrefix="postmortem"
            />
          </CardContent>
        </Card>

        <Card className="self-start">
          <CardHeader>
            <CardTitle>{detailTitle}</CardTitle>
            <CardDescription>{detailDescription}</CardDescription>
          </CardHeader>
          <CardContent>
            {current === undefined ? (
              <p className="text-muted-foreground text-sm" data-testid="postmortem-detail-placeholder">
                {detailPlaceholder}
              </p>
            ) : (
              <div className="flex flex-col gap-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    size="sm"
                    disabled={!editable || save.isPending}
                    onClick={() => save.mutate(current.id)}
                    data-testid="postmortem-save"
                  >
                    保存
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={current.status === 'APPROVED' || approve.isPending}
                    onClick={() => approve.mutate(current.id)}
                    data-testid="postmortem-approve"
                  >
                    确认（进入知识库）
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={current.status !== 'APPROVED' || archive.isPending}
                    onClick={() => archive.mutate(current.id)}
                    data-testid="postmortem-archive"
                  >
                    归档
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!editable || regenerate.isPending}
                    onClick={() => regenerate.mutate(current.id)}
                    data-testid="postmortem-regenerate"
                    title="按上面的结构化字段重写正文，丢掉手写正文"
                  >
                    按字段重新生成正文
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={download.isPending}
                    onClick={() => download.mutate(current)}
                    data-testid="postmortem-download"
                  >
                    下载 .md
                  </Button>
                  {current.diagnosisId !== null ? (
                    <span className="text-muted-foreground text-xs">来源诊断 #{current.diagnosisId}</span>
                  ) : null}
                </div>

                <Tabs defaultValue="edit">
                  <TabsList>
                    <TabsTrigger value="edit">编辑</TabsTrigger>
                    <TabsTrigger value="preview">预览</TabsTrigger>
                    <TabsTrigger value="body">正文 Markdown</TabsTrigger>
                  </TabsList>

                  <TabsContent value="edit" className="flex flex-col gap-3">
                    <label className="flex flex-col gap-1 text-sm">
                      <span className="font-medium">标题</span>
                      <input
                        className="border-border bg-background rounded-md border px-2 py-1 text-sm"
                        value={form.title}
                        onChange={(event) => setForm({ ...form, title: event.target.value })}
                        data-testid="postmortem-title"
                      />
                    </label>
                    <div className="grid gap-3 sm:grid-cols-2">
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="font-medium">严重度</span>
                        <Select
                          value={form.severity === '' ? 'none' : form.severity}
                          onValueChange={(value) =>
                            setForm({ ...form, severity: value === null || value === 'none' ? '' : value })
                          }
                        >
                          <SelectTrigger data-testid="postmortem-severity">
                            <SelectValue placeholder="未设置" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="none">未设置</SelectItem>
                            {SEVERITY.map((item) => (
                              <SelectItem key={item} value={item}>
                                {item}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </label>
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="font-medium">来源告警</span>
                        <input
                          className="border-border bg-background rounded-md border px-2 py-1 text-sm"
                          value={form.alertName}
                          onChange={(event) => setForm({ ...form, alertName: event.target.value })}
                          data-testid="postmortem-alert"
                        />
                      </label>
                    </div>
                    {(
                      [
                        ['rootCause', '根因'],
                        ['impact', '影响面'],
                        ['timelineMd', '时间线（Markdown）'],
                        ['actionsMd', '处置与后续（Markdown）'],
                        ['evidenceRefs', '证据引用（日志查询、traceId、截图链接）'],
                      ] as const
                    ).map(([field, label]) => (
                      <label key={field} className="flex flex-col gap-1 text-sm">
                        <span className="font-medium">{label}</span>
                        <textarea
                          className="border-border bg-background min-h-24 w-full rounded-md border p-2 text-sm"
                          value={form[field]}
                          onChange={(event) => setForm({ ...form, [field]: event.target.value })}
                          data-testid={`postmortem-${field}`}
                        />
                      </label>
                    ))}
                  </TabsContent>

                  <TabsContent value="preview" className="flex flex-col gap-2">
                    {form.bodyMd.trim() === '' ? (
                      <p className="text-muted-foreground text-sm">正文为空。</p>
                    ) : (
                      <Markdown markdown={form.bodyMd} className="text-sm" />
                    )}
                  </TabsContent>

                  <TabsContent value="body" className="flex flex-col gap-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <Button
                        size="xs"
                        variant="outline"
                        disabled={upload.isPending || !editable}
                        onClick={() => filePicker.current?.click()}
                        data-testid="postmortem-insert-image"
                      >
                        插入截图
                      </Button>
                      <span className="text-muted-foreground text-xs">
                        支持粘贴/拖入图片；截图请遮盖口令与连接串（附件地址仅凭 UUID 可读）
                      </span>
                      <input
                        ref={filePicker}
                        type="file"
                        accept="image/*"
                        className="hidden"
                        onChange={onPickFile}
                        data-testid="postmortem-file-input"
                      />
                    </div>
                    <textarea
                      ref={(element) => {
                        areas.current.set('bodyMd', element)
                      }}
                      className="border-border bg-background min-h-80 w-full rounded-md border p-2 font-mono text-xs"
                      value={form.bodyMd}
                      onChange={(event) => {
                        setBodyTouched(true)
                        setForm({ ...form, bodyMd: event.target.value })
                      }}
                      onPaste={onPasteImage}
                      data-testid="postmortem-body"
                    />
                  </TabsContent>
                </Tabs>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
