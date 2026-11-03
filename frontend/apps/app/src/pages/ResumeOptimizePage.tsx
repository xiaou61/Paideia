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
  type KeywordBucket,
  type ResumeAnalysis,
  type ResumeProposal,
  type ResumeVersionDetail,
} from '@paideia/resume'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Printer, Sparkles } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const resumes = createResumeApi(api)

const BUCKET_ORDER: KeywordBucket[] = ['existing', 'evidenced', 'gap']

/**
 * 按岗位优化（WORK-018 REQ-012..020）。
 *
 * <p>四步，两步免费两步计费，界面必须把这条说清楚：
 * <ol>
 *   <li>粘贴 JD 并建版本 —— **免费**</li>
 *   <li>分析 —— **一次积分**</li>
 *   <li>生成改写提案 —— **一次积分**</li>
 *   <li>逐条采纳 → 定稿 —— **免费**（只跑确定性闸门）</li>
 * </ol>
 *
 * <p><b>提案默认全部不采纳</b>。这不是实现细节，是产品主张：一键全采纳会把"AI 优化"退化成
 * "AI 决定"。用户必须逐条看过"原文 vs 改后"再决定。
 */
export function ResumeOptimizePage() {
  const { id } = useParams<{ id: string }>()
  const documentId = Number(id)
  const navigate = useNavigate()

  const [jdText, setJdText] = useState('')
  const [company, setCompany] = useState('')
  const [role, setRole] = useState('')
  const [versionId, setVersionId] = useState<number | null>(null)
  const [analysis, setAnalysis] = useState<ResumeAnalysis | null>(null)
  const [proposals, setProposals] = useState<ResumeProposal[]>([])
  const [error, setError] = useState<string | null>(null)
  const [finalized, setFinalized] = useState<ResumeVersionDetail | null>(null)
  const [filter, setFilter] = useState<'all' | 'accepted' | 'pending'>('all')

  const { data: document } = useQuery({
    queryKey: ['resume', documentId],
    queryFn: () => resumes.get(documentId),
    enabled: Number.isFinite(documentId),
  })

  const { data: versions } = useQuery({
    queryKey: ['resume-versions', documentId],
    queryFn: () => resumes.listVersions(documentId),
    enabled: Number.isFinite(documentId),
  })

  const createVersion = useMutation({
    mutationFn: () => resumes.createVersion(documentId, {
      company: company.trim() === '' ? null : company.trim(),
      role: role.trim() === '' ? null : role.trim(),
      jdText,
    }),
    onSuccess: (created) => {
      setVersionId(created)
      setError(null)
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const analyze = useMutation({
    mutationFn: (target: number) => resumes.analyze(target),
    onSuccess: (result) => setAnalysis(result),
    onError: (failure: Error) => setError(failure.message),
  })

  const propose = useMutation({
    mutationFn: (target: number) => resumes.propose(target),
    onSuccess: (result) => setProposals(result),
    onError: (failure: Error) => setError(failure.message),
  })

  const toggle = useMutation({
    mutationFn: (input: { id: number; accepted: boolean }) =>
      resumes.updateProposal(input.id, { accepted: input.accepted }),
    onSuccess: (saved) => setProposals((current) => current.map((item) => (item.id === saved.id ? saved : item))),
    onError: (failure: Error) => setError(failure.message),
  })

  const finalize = useMutation({
    mutationFn: (target: number) => resumes.finalize(target),
    onSuccess: (result) => setFinalized(result),
    onError: (failure: Error) => setError(failure.message),
  })

  const acceptedCount = proposals.filter((item) => item.accepted).length
  const visible = useMemo(
    () => proposals.filter((item) =>
      filter === 'all' ? true : filter === 'accepted' ? item.accepted : !item.accepted),
    [proposals, filter],
  )

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="按岗位优化"
        description={
          document === undefined
            ? '主简历不会被修改；这里生成的是单独的一个岗位版本。'
            : `基于「${document.title}」生成。主简历不会被修改。`
        }
        actions={
          finalized !== null ? (
            <Button onClick={() => navigate(`/resume/versions/${finalized.id}/print`)}>
              <Printer className="size-4" />
              打印 / 存为 PDF
            </Button>
          ) : null
        }
      />

      {error !== null && (
        <Alert variant="destructive" className="mb-4">
          <AlertTitle>出错了</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {versionId === null ? (
        <Card className="max-w-3xl">
          <CardContent className="space-y-3 py-5">
            <h2 className="font-medium">粘贴岗位描述</h2>
            <div className="grid gap-2 sm:grid-cols-2">
              <label className="block text-sm">
                <span className="text-[var(--color-muted-foreground)]">公司（可选）</span>
                <Input className="mt-1" value={company} onChange={(event) => setCompany(event.target.value)} />
              </label>
              <label className="block text-sm">
                <span className="text-[var(--color-muted-foreground)]">岗位（可选）</span>
                <Input className="mt-1" value={role} onChange={(event) => setRole(event.target.value)} />
              </label>
            </div>
            <label className="block text-sm">
              <span className="text-[var(--color-muted-foreground)]">JD 文本</span>
              <textarea
                className="mt-1 min-h-56 w-full rounded-md border border-[var(--color-border)] bg-transparent p-3 text-sm"
                value={jdText}
                onChange={(event) => setJdText(event.target.value)}
                placeholder="把招聘页面上的岗位描述整段贴进来（至少 30 字）"
                data-testid="resume-jd-input"
              />
            </label>
            <p className="text-xs text-[var(--color-muted-foreground)]">
              第一步只保存 JD，不消耗积分。分析与生成提案各消耗一次。
            </p>
            <Button
              onClick={() => createVersion.mutate()}
              disabled={createVersion.isPending || jdText.trim().length < 30}
            >
              {createVersion.isPending ? <Spinner /> : null}
              下一步
            </Button>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          <Card>
            <CardContent className="flex flex-wrap items-center gap-2 py-4">
              <Button
                onClick={() => analyze.mutate(versionId)}
                disabled={analyze.isPending}
              >
                {analyze.isPending ? <Spinner /> : <Sparkles className="size-4" />}
                {analysis === null ? '分析这个岗位（消耗一次）' : '重新分析（再消耗一次）'}
              </Button>
              <Button
                variant="outline"
                onClick={() => propose.mutate(versionId)}
                disabled={propose.isPending || analysis === null}
              >
                {propose.isPending ? <Spinner /> : null}
                生成改写提案（消耗一次）
              </Button>
              <Button
                variant="outline"
                onClick={() => finalize.mutate(versionId)}
                disabled={finalize.isPending || acceptedCount === 0}
              >
                定稿（免费）
              </Button>
              <Badge variant="secondary">已采纳 {acceptedCount} / {proposals.length}</Badge>
              {proposals.length > 0 && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setFilter(filter === 'all' ? 'accepted' : filter === 'accepted' ? 'pending' : 'all')}
                >
                  筛选：{filter === 'all' ? '全部' : filter === 'accepted' ? '仅已采纳' : '仅未采纳'}
                </Button>
              )}
            </CardContent>
          </Card>

          {analysis !== null && <AnalysisCard analysis={analysis} />}

          {finalized !== null && (
            <Alert>
              <AlertTitle>已定稿</AlertTitle>
              <AlertDescription>
                这一版可以打印或存成 PDF 了；主简历没有被修改。
              </AlertDescription>
            </Alert>
          )}

          {visible.map((item) => (
            <ProposalCard
              key={item.id}
              proposal={item}
              onToggle={() => toggle.mutate({ id: item.id, accepted: !item.accepted })}
              disabled={toggle.isPending}
            />
          ))}

          {proposals.length === 0 && analysis !== null && (
            <Empty>
              <EmptyHeader>
                <EmptyTitle>还没有提案</EmptyTitle>
                <EmptyDescription>点「生成改写提案」让模型逐条给出改动建议。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          )}

          {versions !== undefined && versions.length > 0 && (
            <Card>
              <CardContent className="space-y-2 py-4">
                <h2 className="text-sm font-medium">这台主简历的版本</h2>
                <Separator />
                <ul className="space-y-1 text-sm">
                  {versions.map((version) => (
                    <li key={version.id} className="flex items-center justify-between gap-2">
                      <span>
                        {version.company ?? '未填公司'} · {version.role ?? '未填岗位'} ·{' '}
                        {version.status === 'finalized' ? '已定稿' : '草稿'}
                      </span>
                      {version.status === 'finalized' && (
                        <Button size="sm" variant="ghost" onClick={() => navigate(`/resume/versions/${version.id}/print`)}>
                          打印
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </AppShell>
  )
}

function AnalysisCard({ analysis }: { analysis: ResumeAnalysis }) {
  return (
    <Card>
      <CardContent className="space-y-3 py-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-medium">这个岗位看重什么</h2>
          <span className="text-sm text-[var(--color-muted-foreground)]">
            关键词覆盖 {analysis.covered} / {analysis.total}
          </span>
        </div>
        {analysis.summary !== '' && <p className="text-sm">{analysis.summary}</p>}
        <p className="text-xs text-[var(--color-muted-foreground)]">
          这是关键词覆盖面，不是录用概率。分桶由代码按你简历里的原文查找得出。
        </p>
        <div className="grid gap-3 md:grid-cols-3">
          {BUCKET_ORDER.map((bucket) => (
            <div key={bucket}>
              <h3 className="mb-1 text-xs font-medium text-[var(--color-muted-foreground)]">
                {BUCKET_LABELS[bucket]}
              </h3>
              <ul className="space-y-1 text-sm">
                {analysis.classified
                  .filter((item) => item.bucket === bucket)
                  .map((item, index) => (
                    <li key={index} title={item.quote}>
                      {item.term}
                    </li>
                  ))}
              </ul>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function ProposalCard({
  proposal,
  onToggle,
  disabled,
}: {
  proposal: ResumeProposal
  onToggle: () => void
  disabled: boolean
}) {
  return (
    <Card className={proposal.accepted ? 'border-[var(--color-primary)]' : undefined}>
      <CardContent className="space-y-3 py-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline">{changeTypeLabel(proposal.changeType)}</Badge>
            <span className="font-mono text-xs text-[var(--color-muted-foreground)]">
              {proposal.entryRef}
            </span>
            {proposal.gateStatus === 'failed' && <Badge variant="destructive">未通过事实校验</Badge>}
          </div>
          <Button
            size="sm"
            variant={proposal.accepted ? 'secondary' : 'default'}
            onClick={onToggle}
            disabled={disabled || proposal.gateStatus === 'failed'}
            data-testid={`proposal-toggle-${proposal.id}`}
          >
            {proposal.accepted ? '已采纳' : '采用这一条'}
          </Button>
        </div>

        <div className="grid gap-3 md:grid-cols-2">
          <div className="rounded-md border border-[var(--color-border)] p-3">
            <h3 className="mb-1 text-xs font-medium text-[var(--color-muted-foreground)]">原文</h3>
            <p className="text-sm">
              <RemovedText text={proposal.originalText} after={proposal.proposedText} />
            </p>
          </div>
          <div className="rounded-md border border-[var(--color-border)] p-3">
            <h3 className="mb-1 text-xs font-medium text-[var(--color-muted-foreground)]">改后</h3>
            <p className="text-sm">
              <ChangedText text={proposal.proposedText} before={proposal.originalText} />
            </p>
          </div>
        </div>

        {proposal.rationale !== '' && (
          <p className="text-sm text-[var(--color-muted-foreground)]">依据：{proposal.rationale}</p>
        )}
        {proposal.coversKeywords.length > 0 && (
          <p className="text-xs text-[var(--color-muted-foreground)]">
            覆盖关键词：{proposal.coversKeywords.join('、')}
          </p>
        )}
        {proposal.gateViolations.length > 0 && (
          <ul className="list-inside list-disc text-sm text-[var(--color-destructive)]">
            {proposal.gateViolations.map((violation, index) => (
              <li key={index}>{violation}</li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

const CHANGE_TYPE_LABELS: Record<ResumeProposal['changeType'], string> = {
  rewrite: '改写',
  reorder: '调整顺序',
  add: '新增',
  remove: '建议删除',
}

function changeTypeLabel(type: ResumeProposal['changeType']): string {
  return CHANGE_TYPE_LABELS[type]
}
