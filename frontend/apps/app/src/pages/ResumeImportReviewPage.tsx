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
  EmptyTitle,
  Input,
  PageHeader,
  Separator,
  Spinner,
} from '@paideia/ui'
import {
  BUCKET_LABELS,
  ChangedText,
  createResumeApi,
  RemovedText,
  type ClassifiedKeyword,
  type ResumeAnalysis,
  type ResumeDocument,
  type ResumeDraft,
  type ResumeProposal,
} from '@paideia/resume'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileText, Plus, Sparkles, Trash2, Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const resumes = createResumeApi(api)

/**
 * 上传后的核对页（WORK-018 REQ-003）。
 *
 * <p>识别结果**先在这里给用户看、改、确认**，点保存才落库。这是需求里最硬的一条：
 * 未经确认不得写入任何简历数据。所以这一页从 `sessionStorage` 读草稿，保存成功后清掉它。
 */
const DRAFT_KEY = 'paideia:resume-draft'

export function ResumeImportReviewPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [draft] = useState<ResumeDraft | null>(() => {
    const raw = sessionStorage.getItem(DRAFT_KEY)
    if (raw === null) {
      return null
    }
    try {
      return JSON.parse(raw) as ResumeDraft
    } catch {
      return null
    }
  })
  const [title, setTitle] = useState('我的简历')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () => {
      if (draft === null) {
        throw new Error('草稿已丢失，请回到简历页重新上传')
      }
      return resumes.create({ title: title.trim(), data: draft.data, plainText: draft.plainText })
    },
    onSuccess: (document: ResumeDocument) => {
      sessionStorage.removeItem(DRAFT_KEY)
      void queryClient.invalidateQueries({ queryKey: ['resumes'] })
      navigate(`/resume/${document.id}`)
    },
    onError: (failure: Error) => setError(failure.message),
  })

  if (draft === null) {
    return (
      <AppShell title="Paideia" actions={<LearnerHeader />} wide>
        <PageHeader title="导入简历" />
        <Empty>
          <EmptyHeader>
            <EmptyTitle>没有待确认的简历</EmptyTitle>
            <EmptyDescription>草稿在刷新后会丢失。请回到「我的简历」重新上传。</EmptyDescription>
          </EmptyHeader>
          {/* 动作放进 EmptyContent：原来它挂在 Empty 外面，于是文案居中、按钮偏左 */}
          <EmptyContent>
            <Button onClick={() => navigate('/resume')}>回到我的简历</Button>
          </EmptyContent>
        </Empty>
      </AppShell>
    )
  }

  const counts = {
    work: draft.data.work.length,
    education: draft.data.education.length,
    projects: draft.data.projects.length,
    skills: draft.data.skills.length,
  }

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="核对识别结果"
        description="确认之后才会保存。没有识别对的地方先不用改——保存后可以随时在编辑器里改。"
        actions={
          <Button onClick={() => create.mutate()} disabled={create.isPending || title.trim() === ''}>
            {create.isPending ? <Spinner /> : null}
            保存为主简历
          </Button>
        }
      />

      {(draft.dropped > 0 || draft.truncatedChars > 0 || draft.truncatedFields > 0) && (
        <Alert className="mb-4">
          <AlertTitle>有内容没有完整读入</AlertTitle>
          <AlertDescription>
            <ul className="list-inside list-disc text-sm">
              {draft.dropped > 0 && (
                <li>{draft.dropped} 条内容因为找不到原文出处被丢弃（避免把不存在的信息写进简历）。</li>
              )}
              {draft.truncatedChars > 0 && (
                <li>正文超出上限，最后 {draft.truncatedChars} 个字符没有被读取。</li>
              )}
              {draft.truncatedFields > 0 && <li>{draft.truncatedFields} 个字段超长，已被截断。</li>}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      {error !== null && (
        <Alert variant="destructive" className="mb-4">
          <AlertTitle>保存失败</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Card className="mb-4">
        <CardContent className="space-y-2 py-5">
          <label className="block text-sm">
            <span className="text-[var(--color-muted-foreground)]">给这份简历起个名字</span>
            <Input className="mt-1" value={title} onChange={(event) => setTitle(event.target.value)} />
          </label>
          <Separator className="my-3" />
          <dl className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
            <Stat label="工作/实习" value={counts.work} />
            <Stat label="教育" value={counts.education} />
            <Stat label="项目" value={counts.projects} />
            <Stat label="技能分组" value={counts.skills} />
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 py-5">
          <h2 className="font-medium">识别出的内容（只读预览）</h2>
          <ReadOnly data={draft.data} />
        </CardContent>
      </Card>
    </AppShell>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-xs text-[var(--color-muted-foreground)]">{label}</dt>
      <dd className="text-lg font-medium">{value}</dd>
    </div>
  )
}

function ReadOnly({ data }: { data: ResumeDraft['data'] }) {
  return (
    <div className="space-y-4 text-sm">
      <div>
        <h3 className="font-medium">{data.basics.name || '（未识别到姓名）'}</h3>
        <p className="text-[var(--color-muted-foreground)]">
          {[data.basics.phone, data.basics.email, data.basics.city].filter(Boolean).join(' · ') || '（无联系方式）'}
        </p>
        {data.intent.role !== '' && <p className="mt-1">求职意向：{data.intent.role}</p>}
      </div>
      {data.work.length > 0 && (
        <div>
          <h3 className="font-medium">工作与实习</h3>
          <ul className="mt-1 space-y-2">
            {data.work.map((item, index) => (
              <li key={index}>
                <p>
                  {item.company} · {item.title} · {item.start} - {item.end}
                </p>
                <ul className="ml-4 list-disc text-[var(--color-muted-foreground)]">
                  {item.bullets.map((bullet, bulletIndex) => (
                    <li key={bulletIndex}>{bullet}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </div>
      )}
      {data.education.length > 0 && (
        <div>
          <h3 className="font-medium">教育</h3>
          {data.education.map((item, index) => (
            <p key={index}>
              {item.school} · {item.major} · {item.start} - {item.end}
            </p>
          ))}
        </div>
      )}
      {data.skills.length > 0 && (
        <div>
          <h3 className="font-medium">技能</h3>
          {data.skills.map((group, index) => (
            <p key={index}>
              {group.group}：{group.items.join('、')}
            </p>
          ))}
        </div>
      )}
    </div>
  )
}
