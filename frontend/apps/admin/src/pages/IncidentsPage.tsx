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
  Input,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { api } from '../api'

interface Incident {
  id: number
  fingerprint: string
  alertName: string
  severity: string | null
  status: 'OPEN' | 'RESOLVED'
  firstSeen: string
  lastSeen: string
  resolvedAt: string | null
  alertCount: number
  lastDiagnosedAt: string | null
  lastDiagnosisId: number | null
}

interface IncidentEvent {
  id: number
  kind: string
  message: string | null
  actorUserId: number | null
  createdAt: string
}

interface DiagnosisSummary {
  id: number
  triggerType: string
  status: string
  model: string | null
  rating: number | null
  errorMessage: string | null
  createdAt: string
  finishedAt: string | null
  inputTokens: number | null
  outputTokens: number | null
}

interface IncidentDetail {
  incident: Incident
  events: IncidentEvent[]
  diagnoses: DiagnosisSummary[]
}

const STATUS: Record<Incident['status'], { label: string; variant: 'warning' | 'success' }> = {
  OPEN: { label: '进行中', variant: 'warning' },
  RESOLVED: { label: '已恢复', variant: 'success' },
}

const KIND_LABEL: Record<string, string> = {
  FIRED: '告警触发',
  RESOLVED: '告警恢复',
  DIAGNOSIS_STARTED: '开始诊断',
  DIAGNOSIS_DONE: '诊断完成',
  DIAGNOSIS_FAILED: '诊断失败',
  NOTE: '备注',
}

export function IncidentsPage() {
  const [status, setStatus] = useState<'OPEN' | 'ALL'>('ALL')
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [note, setNote] = useState('')
  const queryClient = useQueryClient()

  const incidents = useQuery({
    queryKey: ['admin-ops-incidents', status],
    queryFn: () =>
      api.requestEnvelope<Incident[]>(
        `/api/v1/ops/admin/incidents?limit=50${status === 'OPEN' ? '&status=OPEN' : ''}`,
      ),
    refetchInterval: 15_000,
  })

  const detail = useQuery({
    queryKey: ['admin-ops-incident', selectedId],
    queryFn: () => api.requestEnvelope<IncidentDetail>(`/api/v1/ops/admin/incidents/${selectedId}`),
    enabled: selectedId !== null,
    refetchInterval: 10_000,
  })

  const addNote = useMutation({
    mutationFn: (message: string) =>
      api.requestEnvelope<IncidentDetail>(`/api/v1/ops/admin/incidents/${selectedId}/notes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message }),
      }),
    onSuccess: () => {
      setNote('')
      void queryClient.invalidateQueries({ queryKey: ['admin-ops-incident', selectedId] })
      void queryClient.invalidateQueries({ queryKey: ['admin-ops-incidents'] })
    },
  })

  return (
    <>
      <PageHeader
        title="事件"
        description="同一告警的重复通知归到一个事件；时间线只追加。"
        actions={
          <div className="flex gap-2">
            <Button
              variant={status === 'ALL' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setStatus('ALL')}
            >
              全部
            </Button>
            <Button
              variant={status === 'OPEN' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setStatus('OPEN')}
            >
              进行中
            </Button>
          </div>
        }
      />

      {incidents.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm">
          <Spinner /> 正在读取事件…
        </p>
      ) : incidents.isError ? (
        <p className="text-muted-foreground text-sm">
          事件读取失败：{errorMessage(incidents.error)}
        </p>
      ) : incidents.data.length === 0 ? (
        <Empty data-testid="incidents-empty">
          <EmptyHeader>
            <EmptyTitle>没有事件</EmptyTitle>
            <EmptyDescription>
              告警触发后会自动出现在这里；现在没有进行中或历史事件。
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
          <div className="space-y-2" data-testid="incidents-list">
            {incidents.data.map((incident) => (
              <button
                key={incident.id}
                type="button"
                onClick={() => setSelectedId(incident.id)}
                aria-pressed={selectedId === incident.id}
                className={`border-border hover:bg-muted/40 w-full rounded-lg border p-3 text-left ${
                  selectedId === incident.id ? 'ring-primary ring-2' : ''
                }`}
              >
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <Badge variant={STATUS[incident.status].variant}>
                    {STATUS[incident.status].label}
                  </Badge>
                  <span className="font-medium">{incident.alertName}</span>
                  {incident.severity ? (
                    <span className="text-muted-foreground text-xs">{incident.severity}</span>
                  ) : null}
                </div>
                <p className="text-muted-foreground mt-1 text-xs">
                  首次 {formatTime(incident.firstSeen)} · 最近 {formatTime(incident.lastSeen)} ·{' '}
                  {incident.alertCount} 次
                </p>
              </button>
            ))}
          </div>

          <Card data-testid="incident-detail">
            <CardHeader>
              <CardTitle className="text-sm font-medium">
                {selectedId === null ? '选择左侧事件' : `事件 #${selectedId}`}
              </CardTitle>
              <CardDescription>
                {selectedId === null
                  ? '选中一个事件后显示时间线与关联诊断。'
                  : '时间线只追加；备注会记在当前账号名下。'}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {selectedId === null ? null : detail.isPending ? (
                <p className="text-muted-foreground flex items-center gap-2 text-sm">
                  <Spinner /> 读取事件…
                </p>
              ) : detail.isError ? (
                <p className="text-muted-foreground text-sm">
                  事件读取失败：{errorMessage(detail.error)}
                </p>
              ) : detail.data ? (
                <>
                  <dl className="mb-4 grid grid-cols-2 gap-2 text-sm">
                    <div>
                      <dt className="text-muted-foreground text-xs">告警</dt>
                      <dd>{detail.data.incident.alertName}</dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground text-xs">严重度</dt>
                      <dd>{detail.data.incident.severity ?? '—'}</dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground text-xs">首次</dt>
                      <dd>{formatTime(detail.data.incident.firstSeen)}</dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground text-xs">最近</dt>
                      <dd>{formatTime(detail.data.incident.lastSeen)}</dd>
                    </div>
                  </dl>

                  <h3 className="mb-2 text-sm font-medium">时间线</h3>
                  <ol className="border-border mb-4 space-y-2 border-l pl-3" data-testid="incident-timeline">
                    {detail.data.events.map((event) => (
                      <li key={event.id} className="text-sm">
                        <span className="text-muted-foreground text-xs">
                          {formatTime(event.createdAt)}
                        </span>{' '}
                        <span className="font-medium">{KIND_LABEL[event.kind] ?? event.kind}</span>
                        {event.message ? <span> — {event.message}</span> : null}
                      </li>
                    ))}
                  </ol>

                  <h3 className="mb-2 text-sm font-medium">关联诊断</h3>
                  {detail.data.diagnoses.length === 0 ? (
                    <p className="text-muted-foreground mb-4 text-sm">还没有诊断。</p>
                  ) : (
                    <ul className="mb-4 space-y-1 text-sm">
                      {detail.data.diagnoses.map((diagnosis) => (
                        <li key={diagnosis.id}>
                          诊断 #{diagnosis.id} · {diagnosis.status}
                          {diagnosis.model ? ` · ${diagnosis.model}` : ''}
                          {diagnosis.rating === null
                            ? ''
                            : ` · ${diagnosis.rating === 1 ? '准' : diagnosis.rating === -1 ? '不准' : '部分准'}`}
                          {' · '}
                          {formatTime(diagnosis.createdAt)}
                        </li>
                      ))}
                    </ul>
                  )}

                  <div className="flex flex-col gap-2 sm:flex-row">
                    <Input
                      value={note}
                      onChange={(event) => setNote(event.target.value)}
                      maxLength={500}
                      placeholder="加一条备注（例如：已确认是磁盘）"
                      aria-label="事件备注"
                    />
                    <Button
                      onClick={() => addNote.mutate(note)}
                      disabled={addNote.isPending || note.trim() === ''}
                    >
                      {addNote.isPending ? '提交中…' : '添加备注'}
                    </Button>
                  </div>
                  {addNote.isError ? (
                    <p className="text-destructive mt-2 text-sm">
                      备注失败：{errorMessage(addNote.error)}
                    </p>
                  ) : null}
                </>
              ) : null}
            </CardContent>
          </Card>
        </div>
      )}
    </>
  )
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '未知错误'
}
