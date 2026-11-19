import { MarkdownField, type EssayReviewItem } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
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
  Label,
  ListPagination,
  LoadingRow,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@paideia/ui'
import { errorMessage, formatLocalDateTime } from '@paideia/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ExternalLink, ShieldCheck, X } from 'lucide-react'
import { useState } from 'react'

import { API_BASE_URL } from '../env'
import { admin } from '../knowledge-shared'

const PAGE_SIZE = 20

const STATUS_ITEMS = [
  { value: 'pending_review', label: '待复核' },
  { value: 'approved', label: '已采纳' },
  { value: 'rejected', label: '已驳回' },
  { value: 'all', label: '全部' },
]

function statusBadge(status: string) {
  if (status === 'approved') {
    return <Badge variant="success">已采纳</Badge>
  }
  if (status === 'rejected') {
    return <Badge variant="outline">已驳回</Badge>
  }
  return <Badge variant="warning">待复核</Badge>
}

function percent(value: number): string {
  return `${Math.round(value * 100)}%`
}

/**
 * 面经复核（WORK-084 TASK-007）。
 *
 * <p>内部自检没有把握的题会被拦在这里（{@code pending_review}），**不会**自动出现在学习者的知识库。
 * 管理员逐条看：自检给了什么问题、草稿答案是什么；可选改题目/答案，决定直接发布还是存草稿，
 * 或直接驳回并记原因。采纳后条目落到知识库（同指纹+标题会复用已有条目）。
 */
export function EssayReviewPage() {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState('pending_review')
  const [page, setPage] = useState(1)
  const [active, setActive] = useState<EssayReviewItem | null>(null)

  const reviews = useQuery({
    queryKey: ['admin-essay-reviews', status, page],
    queryFn: () => admin.essayReviews.list(status === 'all' ? undefined : status, page, PAGE_SIZE),
  })

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin-essay-reviews'] })

  const total = reviews.data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <>
      <PageHeader
        title="面经复核"
        description="自动采集的面经先过一道事实自检：有把握的直接入库，存疑的留在这里等人工。采纳即生成知识条目，驳回只留记录。"
      />

      <Card>
        <CardContent className="space-y-4 pt-6">
          <div className="flex flex-wrap items-center gap-3">
            <Select
              value={status}
              onValueChange={(value) => {
                setStatus(value ?? 'all')
                setPage(1)
              }}
            >
              <SelectTrigger className="w-40" data-testid="essay-review-status">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {STATUS_ITEMS.map((item) => (
                  <SelectItem key={item.value} value={item.value}>
                    {item.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <span className="text-muted-foreground text-sm">共 {total} 条</span>
          </div>

          {reviews.isError ? (
            <Alert variant="destructive">
              <AlertTitle>复核列表加载失败</AlertTitle>
              <AlertDescription>{errorMessage(reviews.error)}</AlertDescription>
            </Alert>
          ) : null}

          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>题目</TableHead>
                <TableHead className="w-24">置信</TableHead>
                <TableHead className="w-28">状态</TableHead>
                <TableHead className="w-44">采集时间</TableHead>
                <TableHead className="w-28">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {reviews.isPending ? (
                <TableRow>
                  <TableCell colSpan={5} className="py-6">
                    <LoadingRow label="正在读取" />
                  </TableCell>
                </TableRow>
              ) : null}
              {!reviews.isPending && (reviews.data?.items.length ?? 0) === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-muted-foreground py-8 text-center text-sm">
                    没有待复核的记录
                  </TableCell>
                </TableRow>
              ) : null}
              {reviews.data?.items.map((item) => (
                <TableRow key={item.id} data-testid={`essay-review-row-${item.id}`}>
                  <TableCell className="max-w-0">
                    <div className="truncate font-medium" title={item.questionTitle}>
                      {item.questionTitle}
                    </div>
                    {item.issues.length > 0 ? (
                      <div className="text-muted-foreground truncate text-xs" title={item.issues.join('；')}>
                        自检：{item.issues.join('；')}
                      </div>
                    ) : null}
                  </TableCell>
                  <TableCell className="tabular-nums">{percent(item.confidence)}</TableCell>
                  <TableCell>{statusBadge(item.status)}</TableCell>
                  <TableCell className="text-muted-foreground text-sm">
                    {formatLocalDateTime(item.createdAt)}
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setActive(item)}
                      data-testid={`essay-review-open-${item.id}`}
                    >
                      查看
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>

          {totalPages > 1 ? (
            <ListPagination
              total={total}
              page={page}
              totalPages={totalPages}
              onPage={setPage}
              testIdPrefix="essay-review"
            />
          ) : null}
        </CardContent>
      </Card>

      {active !== null ? (
        <ReviewDialog
          item={active}
          onClose={() => setActive(null)}
          onDone={async () => {
            setActive(null)
            await refresh()
          }}
        />
      ) : null}
    </>
  )
}

function ReviewDialog({
  item,
  onClose,
  onDone,
}: {
  item: EssayReviewItem
  onClose: () => void
  onDone: () => Promise<void>
}) {
  const [title, setTitle] = useState(item.questionTitle)
  const [answer, setAnswer] = useState(item.finalAnswer ?? item.draftAnswer)
  const [publish, setPublish] = useState(true)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)

  const approve = useMutation({
    mutationFn: () =>
      admin.essayReviews.approve(item.id, { title: title.trim(), answer: answer.trim(), publish }),
    onSuccess: onDone,
    onError: (cause) => setError(errorMessage(cause)),
  })

  const reject = useMutation({
    mutationFn: () => admin.essayReviews.reject(item.id, note.trim() === '' ? undefined : note.trim()),
    onSuccess: onDone,
    onError: (cause) => setError(errorMessage(cause)),
  })

  const readOnly = item.status !== 'pending_review' && item.status !== 'revised'
  const busy = approve.isPending || reject.isPending

  return (
    <Dialog open onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogContent className="sm:max-w-3xl" data-testid="essay-review-dialog">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ShieldCheck className="size-4" />
            复核面经
          </DialogTitle>
          <DialogDescription>
            置信 {percent(item.confidence)} · {item.sourceUrl === null ? '无来源链接' : '有来源链接'}
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[70vh] space-y-4 overflow-y-auto pr-1">
          {item.issues.length > 0 ? (
            <Alert variant="destructive">
              <AlertTitle>自检发现</AlertTitle>
              <AlertDescription>
                <ul className="list-disc space-y-1 pl-4">
                  {item.issues.map((issue) => (
                    <li key={issue}>{issue}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          ) : null}

          <div className="space-y-2">
            <Label htmlFor="essay-review-title">题目</Label>
            <Input
              id="essay-review-title"
              value={title}
              disabled={readOnly}
              onChange={(event) => setTitle(event.target.value)}
              data-testid="essay-review-title"
            />
          </div>

          <div className="space-y-2">
            <Label>答案</Label>
            {readOnly ? (
              <div className="border-border bg-muted/40 max-h-72 overflow-y-auto whitespace-pre-wrap rounded-lg border p-3 text-sm">
                {answer}
              </div>
            ) : (
              <MarkdownField
                value={answer}
                onChange={setAnswer}
                testIdPrefix="essay-review-answer"
                rows={10}
                mediaBaseUrl={API_BASE_URL}
              />
            )}
          </div>

          {item.sourceUrl !== null ? (
            <a
              href={item.sourceUrl}
              target="_blank"
              rel="noreferrer"
              className="text-primary inline-flex items-center gap-1 text-sm hover:underline"
            >
              查看原帖
              <ExternalLink className="size-3" />
            </a>
          ) : null}

          {readOnly ? null : (
            <>
              <div className="flex items-center justify-between gap-3">
                <div>
                  <Label htmlFor="essay-review-publish">采纳后直接发布</Label>
                  <p className="text-muted-foreground text-xs">
                    关闭则存为草稿，需在「条目管理」再发布一次。
                  </p>
                </div>
                <Switch
                  id="essay-review-publish"
                  checked={publish}
                  onCheckedChange={setPublish}
                  data-testid="essay-review-publish"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="essay-review-note">驳回原因（可选）</Label>
                <Input
                  id="essay-review-note"
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  placeholder="例如：技术描述有误，已与官方文档核对"
                  data-testid="essay-review-note"
                />
              </div>
              {error !== null ? (
                <Alert variant="destructive">
                  <AlertTitle>操作失败</AlertTitle>
                  <AlertDescription>{error}</AlertDescription>
                </Alert>
              ) : null}
            </>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            关闭
          </Button>
          {readOnly ? null : (
            <>
              <Button
                variant="outline"
                className="text-destructive hover:text-destructive"
                disabled={busy}
                onClick={() => reject.mutate()}
                data-testid="essay-review-reject"
              >
                <X className="size-4" />
                驳回
              </Button>
              <Button
                disabled={busy || title.trim() === '' || answer.trim() === ''}
                onClick={() => approve.mutate()}
                data-testid="essay-review-approve"
              >
                <Check className="size-4" />
                {approve.isPending ? '采纳中…' : '采纳入库'}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
