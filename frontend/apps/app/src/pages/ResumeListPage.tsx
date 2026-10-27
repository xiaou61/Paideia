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
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Input,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import {
  createResumeApi,
  emptyResume,
  type ResumeDraft,
  type ResumeDocumentSummary,
} from '@paideia/resume'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileText, Plus, Star, Trash2, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const resumes = createResumeApi(api)

/**
 * 我的简历（WORK-018）。
 *
 * <p>这一页承担两件事：**第一次上传**（空状态）与**之后的管理列表**。
 *
 * <p>上传之后的解析结果**不落库**：它先经 `/parse` 拿到草稿，用户在核对页上确认后才
 * `POST /resumes` 创建。所以这一页在拿到草稿后唯一的动作是"带着草稿去核对页"——
 * 草稿放 `sessionStorage`，因为它是"这一次跳转的中间物"，刷新就该丢（刷新后重传即可，
 * 而把它放库里会让"未经确认的 PII"进入持久层）。
 */
const DRAFT_KEY = 'paideia:resume-draft'

export function ResumeListPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState<ResumeDocumentSummary | null>(null)
  const [renaming, setRenaming] = useState<ResumeDocumentSummary | null>(null)
  const [newTitle, setNewTitle] = useState('')

  const { data: list, isPending } = useQuery({
    queryKey: ['resumes'],
    queryFn: () => resumes.list(),
  })

  const parse = useMutation({
    mutationFn: (file: File) => resumes.parse(file),
    onSuccess: (draft: ResumeDraft) => {
      sessionStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
      navigate('/resume/new')
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const remove = useMutation({
    mutationFn: (id: number) => resumes.remove(id),
    onSuccess: () => {
      setPendingDelete(null)
      void queryClient.invalidateQueries({ queryKey: ['resumes'] })
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const setDefault = useMutation({
    mutationFn: (id: number) => resumes.setDefault(id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['resumes'] }),
    onError: (failure: Error) => setError(failure.message),
  })

  const rename = useMutation({
    mutationFn: (input: { id: number; title: string }) => resumes.rename(input.id, input.title),
    onSuccess: () => {
      setRenaming(null)
      void queryClient.invalidateQueries({ queryKey: ['resumes'] })
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const pickFile = () => fileRef.current?.click()

  const onFile = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file === undefined) {
      return
    }
    setError(null)
    parse.mutate(file)
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="我的简历"
        description="上传一次，之后在站内持续编辑；投不同岗位时另存版本，主简历不会被改。"
        actions={
          <Button onClick={pickFile} disabled={parse.isPending}>
            {parse.isPending ? <Spinner /> : <Upload className="size-4" />}
            上传简历
          </Button>
        }
      />
      <input
        ref={fileRef}
        type="file"
        accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        className="hidden"
        onChange={onFile}
        data-testid="resume-upload-input"
      />

      {error !== null && (
        <Alert variant="destructive" className="mb-4">
          <AlertTitle>没能处理这份简历</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {parse.isPending && (
        <Alert className="mb-4">
          <AlertTitle>正在读取简历</AlertTitle>
          <AlertDescription>
            抽取文字并整理成条目需要几秒。扫描件与图片型 PDF 读不出文字，会被明确拒绝。
          </AlertDescription>
        </Alert>
      )}

      {isPending ? (
        <div className="flex justify-center py-16">
          <Spinner />
        </div>
      ) : list === undefined || list.length === 0 ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileText />
            </EmptyMedia>
            <EmptyTitle>还没有简历</EmptyTitle>
            <EmptyDescription>
              支持 PDF 与文本型 Word（.docx）。扫描件需要先转成电子版。
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <Button onClick={pickFile}>上传第一份简历</Button>
          </EmptyContent>
        </Empty>
      ) : (
        <div className="grid gap-3" data-testid="resume-list">
          {list.map((item) => (
            <Card key={item.id}>
              <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      // py-1 把命中区从 20px 抬到 28px（WCAG 2.5.8 要 24px）；-my-1 抵掉视觉位移。
                      className="-my-1 truncate py-1 text-left font-medium hover:underline"
                      onClick={() => navigate(`/resume/${item.id}`)}
                      data-testid={`resume-open-${item.id}`}
                    >
                      {item.title}
                    </button>
                    {item.isDefault && <Badge variant="secondary">默认</Badge>}
                  </div>
                  <p className="mt-1 text-sm text-[var(--color-muted-foreground)]">
                    更新于 {new Date(item.updatedAt).toLocaleString('zh-CN')}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" onClick={() => navigate(`/resume/${item.id}`)}>
                    编辑
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => navigate(`/resume/${item.id}/optimize`)}>
                    按岗位优化
                  </Button>
                  {/* 训练营（WORK-029）：带上简历 id 直接开营，用户不用再去列表里找一遍 */}
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => navigate(`/camp?resume=${item.id}`)}
                    data-testid={`resume-to-camp-${item.id}`}
                  >
                    用它开训练营
                  </Button>
                  {!item.isDefault && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => setDefault.mutate(item.id)}
                      title="设为默认"
                    >
                      <Star className="size-4" />
                    </Button>
                  )}
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setRenaming(item)
                      setNewTitle(item.title)
                    }}
                  >
                    改名
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setPendingDelete(item)}>
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <Dialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除「{pendingDelete?.title}」？</DialogTitle>
            <DialogDescription>
              这份简历、它的编辑历史与所有岗位版本都会一起删除，无法恢复。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPendingDelete(null)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={remove.isPending}
              onClick={() => pendingDelete !== null && remove.mutate(pendingDelete.id)}
            >
              删除
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={renaming !== null} onOpenChange={(open) => !open && setRenaming(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>改简历标题</DialogTitle>
          </DialogHeader>
          <Input value={newTitle} onChange={(event) => setNewTitle(event.target.value)} />
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenaming(null)}>
              取消
            </Button>
            <Button
              disabled={rename.isPending || newTitle.trim() === ''}
              onClick={() =>
                renaming !== null && rename.mutate({ id: renaming.id, title: newTitle.trim() })
              }
            >
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AppShell>
  )
}

/**
 * 手工新建（不上传）。
 *
 * <p>上传不是唯一入口：用户可能只想把已有简历重新录一遍，或者手里根本没有电子文件。
 * 这条路径直接创建一份空简历进编辑器。
 */
export function ResumeCreatePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('我的简历')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () => resumes.create({ title: title.trim(), data: emptyResume(), plainText: '' }),
    onSuccess: (document) => {
      void queryClient.invalidateQueries({ queryKey: ['resumes'] })
      navigate(`/resume/${document.id}`)
    },
    onError: (failure: Error) => setError(failure.message),
  })

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader title="新建简历" description="不导入文件，从空白开始填。" />
      <Card className="max-w-xl">
        <CardContent className="space-y-4 py-6">
          <div>
            <label className="text-sm font-medium" htmlFor="resume-title">
              标题
            </label>
            <Input
              id="resume-title"
              className="mt-1"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
            <p className="mt-1 text-xs text-[var(--color-muted-foreground)]">
              用「后端方向」「算法方向」这类能区分用途的名字，之后一眼能找到。
            </p>
          </div>
          {error !== null && <p className="text-sm text-[var(--color-destructive)]">{error}</p>}
          <Button onClick={() => create.mutate()} disabled={create.isPending || title.trim() === ''}>
            {create.isPending ? <Spinner /> : <Plus className="size-4" />}
            创建并开始编辑
          </Button>
        </CardContent>
      </Card>
    </AppShell>
  )
}
