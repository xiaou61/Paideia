import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Spinner,
} from '@paideia/ui'
import { useEffect, useState } from 'react'

import type { AssistantApi } from './client'
import type { MemoryCandidate, MemorySettings, MemoryView } from './types'

/**
 * 记忆面板：看它记住了什么、控制它记不记、以及一次清干净（WORK-013 扩写；原为"查看 + 逐条删除"）。
 *
 * <p>三块的**顺序按用户会问的问题排**（已签署的设计第 8 节）："它还在记我吗" → "它记住了什么" →
 * "怎么全删掉"。开关放最上面、危险动作放最下面，是同一件事的两面：越靠上越安全、越常碰。
 *
 * <p>放在助手壳里而不是另开一个设置页：记忆是**这一个助手**的东西，与凭据对话框同一类。
 * 顺着"它记住我什么"这个疑问点开壳就能看到，比让人去别处找要直接。
 *
 * <p>**没有编辑**：用户只选了查看与删除（第 13 条）。改记忆的正确做法是让助手重新记一条，
 * 而不是手改一行——手改会让"这条是谁写的"变得含糊。
 */
export function MemoryPanel({ api }: { api: AssistantApi }) {
  const [settings, setSettings] = useState<MemorySettings | null>(null)
  const [items, setItems] = useState<MemoryView[] | null>(null)
  /** 第一页正好拉满就当作还有下一页；少于 PAGE 条即到底（后端没有 total，只能这样探）。 */
  const [hasMore, setHasMore] = useState(false)
  const [filter, setFilter] = useState<Filter>('all')
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** 删除与「加载更多」共用一个忙碌位：两者都会替换整个列表，并行会互相覆盖。 */
  const [busy, setBusy] = useState(false)
  /** 开关的 PUT 在飞：期间禁掉开关，免得连点让乐观值和最后落库的值错位。 */
  const [saving, setSaving] = useState(false)
  const [clearing, setClearing] = useState(false)
  /** 清空之后的回执（清掉几条）。**不替换空态那句原文**：既有 e2e 断言的是它。 */
  const [clearedNote, setClearedNote] = useState<string | null>(null)
  /** 待确认的候选（WORK-050）。null = 还没拉回来。 */
  const [pending, setPending] = useState<MemoryCandidate[] | null>(null)
  /** 候选上的忙碌位：接受/拒绝都会改两张表，连点会让乐观状态与库错位。 */
  const [deciding, setDeciding] = useState<number | null>(null)

  // 设置与第一页**分开**拉：任一失败不该把另一半也拖成空白——列表看不到是最糟的结果，
  // 因为用户就是来确认"你到底记住了我什么"的
  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const page = await api.memories(PAGE, 0)
        if (!alive) {
          return
        }
        setItems(page)
        setHasMore(page.length === PAGE)
      } catch (cause) {
        if (alive) {
          setError(message(cause))
        }
      }
    })()
    return () => {
      alive = false
    }
  }, [api])

  const loadPending = async () => {
    try {
      setPending(await api.pendingCandidates(20, 0))
    } catch (cause) {
      // 候选拉不到不该把"我记住了什么"也一起变空白：静默降级成空列表
      setPending([])
      setError(message(cause))
    }
  }

  useEffect(() => {
    void loadPending()
  }, [api])

  useEffect(() => {
    let alive = true
    api
      .settings()
      .then((value) => {
        if (alive) {
          setSettings(value)
        }
      })
      .catch((cause) => {
        if (alive) {
          setError(message(cause))
        }
      })
    return () => {
      alive = false
    }
  }, [api])

  /** 重新拉第一页。删除之后走它而不是本地删一条：服务端才知道"是不是真的删掉了"。 */
  const reload = async () => {
    setError(null)
    try {
      const page = await api.memories(PAGE, 0)
      setItems(page)
      setHasMore(page.length === PAGE)
    } catch (cause) {
      setError(message(cause))
    }
  }

  /** 接受一条候选：它会成为一条正式记忆，所以两张表都要重拉。 */
  const accept = async (id: number) => {
    setDeciding(id)
    setError(null)
    try {
      await api.acceptCandidate(id)
      await Promise.all([reload(), loadPending()])
    } catch (cause) {
      setError(message(cause))
    } finally {
      setDeciding(null)
    }
  }

  const reject = async (id: number) => {
    setDeciding(id)
    setError(null)
    try {
      await api.rejectCandidate(id)
      await loadPending()
    } catch (cause) {
      setError(message(cause))
    } finally {
      setDeciding(null)
    }
  }

  const loadMore = async () => {
    if (items === null) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      const page = await api.memories(PAGE, items.length)
      setItems([...items, ...page])
      setHasMore(page.length === PAGE)
    } catch (cause) {
      setError(message(cause))
    } finally {
      setBusy(false)
    }
  }

  const updateSettings = async (patch: Partial<MemorySettings>) => {
    if (settings === null) {
      return
    }
    const previous = settings
    const optimistic = { ...previous, ...patch }
    // 乐观：开关要立刻跟手。等一个来回再动会有"点了没反应"的错觉，用户会再点一次
    setSettings(optimistic)
    setSaving(true)
    setError(null)
    try {
      setSettings(await api.saveSettings(optimistic))
    } catch (cause) {
      // 回滚到点击前：不能让界面停在"看起来关了其实没关"——那正好会让人以为不再记录了
      setSettings(previous)
      setError(message(cause))
    } finally {
      setSaving(false)
    }
  }

  const clearAll = async () => {
    setClearing(true)
    setError(null)
    try {
      // 用响应里的设置覆盖三个开关：关掉总开关是后端那一次调用做的，本地推断会与它错位。
      // `cleared` 也要显示出来——用户点了这个按钮之后想确认的第一件事就是"到底删掉了什么"
      const result = await api.clearMemories()
      setSettings(result.settings)
      setClearedNote(result.cleared === 0 ? '没有可清空的记忆，个性化记忆已关闭。'
        : `已清空 ${result.cleared} 条记忆，并关闭了个性化记忆。`)
      setItems([])
      setHasMore(false)
      setConfirming(false)
    } catch (cause) {
      // 失败时**不关对话框**：留在原地用户才能重试，否则他会以为已经清掉了
      setError(message(cause))
    } finally {
      setClearing(false)
    }
  }

  const remove = async (item: MemoryView) => {
    setBusy(true)
    setError(null)
    try {
      await api.deleteMemory(item.source, item.id)
      await reload()
    } catch (cause) {
      setError(message(cause))
    } finally {
      setBusy(false)
    }
  }

  const visible = items === null ? null : items.filter((item) => filter === 'all' || item.origin === filter)

  return (
    <div className="space-y-3" data-testid="memory-panel">
      {/* ── 一、开关区：它还在记我吗 ─────────────────────────────────────────── */}
      {settings === null ? (
        <Spinner />
      ) : (
        <section className="space-y-2" data-testid="memory-settings">
          <ToggleRow
            label="记住关于我的事"
            checked={settings.memoryEnabled}
            disabled={saving}
            testId="memory-enabled"
            onChange={(next) => void updateSettings({ memoryEnabled: next })}
          />
          {/*
            三件事必须写全（REQ-012）：花钱、关掉不删、要删另点。少写哪一条都会让人
            点了"关"之后以为记忆已经没了。
          */}
          <p className="text-muted-foreground text-xs" data-testid="memory-cost-note">
            自动整理记忆会消耗你的积分（每 6 轮对话整理一次）。关掉不会删除已有的记忆——要删要另外点下面的
            「清空全部记忆并关闭」。
          </p>
          {/* 两个轴与总开关是三层不同的东西，用缩进表示从属，而不是并排成三个平级开关 */}
          <div className="border-border space-y-2 border-l pl-2">
            <ToggleRow
              label="使用已保存的记忆"
              hint="关掉后不再把已有记忆带上，但仍会自动整理新记忆。"
              checked={settings.referenceSavedMemories}
              disabled={saving}
              testId="memory-reference-saved"
              onChange={(next) => void updateSettings({ referenceSavedMemories: next })}
            />
            <ToggleRow
              label="参考当前对话的历史消息"
              hint="关掉后助手只答当前这一句，不看前文。"
              checked={settings.referenceChatHistory}
              disabled={saving}
              testId="memory-reference-history"
              onChange={(next) => void updateSettings({ referenceChatHistory: next })}
            />
          </div>
        </section>
      )}

      {error === null ? null : (
        <Alert variant="destructive" data-testid="memory-error">
          <AlertTitle>没能完成</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {/* ── 一·五、待确认：助手拿不准的，一次点击决定（WORK-050） ─────────────── */}
      {pending !== null && pending.length > 0 ? (
        <section className="space-y-2" data-testid="memory-candidates">
          <p className="text-sm font-medium">待确认（{pending.length}）</p>
          <p className="text-muted-foreground text-xs">
            助手拿不准这些要不要长期记住，确认之后才算数。
          </p>
          <ul className="space-y-2">
            {pending.map((candidate) => (
              <li
                key={candidate.id}
                className="border-border space-y-1 rounded-md border p-2"
                data-testid={`memory-candidate-${candidate.id}`}
              >
                <p className="text-sm">
                  {candidate.content ?? (
                    <span className="text-muted-foreground">
                      有一条内容疑似含敏感信息（{candidate.decisionReason ?? 'sensitive'}），没有记录。
                    </span>
                  )}
                </p>
                {candidate.reason === null ? null : (
                  <p className="text-muted-foreground text-xs">理由：{candidate.reason}</p>
                )}
                <div className="flex gap-2">
                  {candidate.content === null ? (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={deciding === candidate.id}
                      data-testid={`memory-candidate-dismiss-${candidate.id}`}
                      onClick={() => void reject(candidate.id)}
                    >
                      知道了
                    </Button>
                  ) : (
                    <>
                      <Button
                        size="sm"
                        disabled={deciding === candidate.id}
                        data-testid={`memory-candidate-accept-${candidate.id}`}
                        onClick={() => void accept(candidate.id)}
                      >
                        记住
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={deciding === candidate.id}
                        data-testid={`memory-candidate-reject-${candidate.id}`}
                        onClick={() => void reject(candidate.id)}
                      >
                        不要
                      </Button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {/* ── 二、记忆列表：它记住了什么 ───────────────────────────────────────── */}
      <section className="space-y-2">
        <p className="text-muted-foreground text-xs">助手只为你本人记住以下内容，可逐条删除。</p>
        <div className="flex items-center gap-1" data-testid="memory-filters" role="group" aria-label="按来源筛选">
          {FILTERS.map((option) => (
            <Button
              key={option.value}
              variant={filter === option.value ? 'secondary' : 'ghost'}
              size="sm"
              data-testid={`memory-filter-${option.value}`}
              onClick={() => setFilter(option.value)}
            >
              {option.label}
            </Button>
          ))}
        </div>

        {visible === null ? (
          <p className="text-muted-foreground text-xs">正在读取…</p>
        ) : visible.length === 0 ? (
          <>
            {clearedNote === null ? null : (
              <p className="text-muted-foreground text-sm" data-testid="memory-cleared-note">
                {clearedNote}
              </p>
            )}
            <p className="text-muted-foreground text-sm" data-testid="memory-empty">
              {items !== null && items.length > 0 ? '这一类还没有记忆。' : '还没有任何记忆。'}
            </p>
          </>
        ) : (
          <ul className="space-y-1">
            {visible.map((item) => (
              <li
                key={`${item.source}-${item.id}`}
                data-testid="memory-item"
                className="border-border flex items-start justify-between gap-2 rounded border px-2 py-1"
              >
                <div className="min-w-0 space-y-1">
                  <p className="text-sm break-words">{item.text}</p>
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge
                      variant="outline"
                      data-testid={`memory-origin-${item.source}-${item.id}`}
                      data-origin={item.origin}
                    >
                      {ORIGIN_LABEL[item.origin]}
                    </Badge>
                    {item.conversationId === null ? null : (
                      <span className="text-muted-foreground text-[11px]">
                        出自对话 {item.conversationId}
                      </span>
                    )}
                  </div>
                  {/* 理由折叠：它常常是一整句话，全展开会把列表撑得没法扫 */}
                  {item.reason === null ? null : (
                    <details data-testid={`memory-reason-${item.source}-${item.id}`}>
                      <summary className="text-muted-foreground cursor-pointer text-[11px]">
                        它为什么记这条
                      </summary>
                      <p className="text-muted-foreground text-[11px] whitespace-pre-wrap">{item.reason}</p>
                    </details>
                  )}
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  aria-label="删除这条记忆"
                  data-testid={`memory-delete-${item.source}-${item.id}`}
                  onClick={() => void remove(item)}
                >
                  删除
                </Button>
              </li>
            ))}
          </ul>
        )}

        {hasMore ? (
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            data-testid="memory-load-more"
            onClick={() => void loadMore()}
          >
            {busy ? <Spinner /> : null}
            加载更多
          </Button>
        ) : null}
      </section>

      {/* ── 三、危险区：怎么全删掉 ───────────────────────────────────────────── */}
      <section className="border-border space-y-1 border-t pt-2" data-testid="memory-danger">
        {/* 「只关不删」与「清空并关闭」必须写成两句话：混在一起用户会以为关掉就等于删了 */}
        <p className="text-muted-foreground text-xs">
          只是不想让它再记新的？把上面的「记住关于我的事」关掉就够了，那是<span className="font-medium">只关不删</span>
          ，已有记忆会留着。
        </p>
        <p className="text-muted-foreground text-xs">
          下面这个是<span className="font-medium">清空并关闭</span>：连已有的记忆一起删掉，同时把总开关也关掉，不能撤销。
        </p>
        <Button
          variant="destructive"
          size="sm"
          data-testid="memory-clear"
          onClick={() => setConfirming(true)}
        >
          清空全部记忆并关闭
        </Button>
      </section>

      {confirming ? (
        <Dialog open onOpenChange={() => setConfirming(false)}>
          <DialogContent className="sm:max-w-md" data-testid="memory-clear-dialog">
            <DialogHeader>
              <DialogTitle>清空全部记忆并关闭？</DialogTitle>
              <DialogDescription>
                这会删掉你所有的记忆，并关闭「记住关于我的事」。删掉的记忆无法恢复，也没有"只删一部分"这一步。
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button
                variant="ghost"
                disabled={clearing}
                data-testid="memory-clear-cancel"
                onClick={() => setConfirming(false)}
              >
                取消
              </Button>
              <Button
                variant="destructive"
                disabled={clearing}
                data-testid="memory-clear-confirm"
                onClick={() => void clearAll()}
              >
                {clearing ? <Spinner /> : null}
                清空并关闭
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}
    </div>
  )
}

/** 一页 200 条，与后端上限一致；「加载更多」按这个步长走 offset。 */
const PAGE = 200

type Filter = 'all' | 'auto' | 'manual'

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'auto', label: '自动' },
  { value: 'manual', label: '手动' },
]

/**
 * 来源标签的文案。
 *
 * <p>`manual` 不写"手动"而写「你让它记的」：用户点的是「记住这条」，"手动"是我们的词，不是他的。
 */
const ORIGIN_LABEL: Record<MemoryView['origin'], string> = {
  auto: '自动整理',
  manual: '你让它记的',
}

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/** 带说明的一行开关。三处同构，抽出来只为少写两遍 `<label>` 的样板。 */
function ToggleRow({
  label,
  hint,
  checked,
  disabled,
  testId,
  onChange,
}: {
  label: string
  hint?: string
  checked: boolean
  disabled: boolean
  testId: string
  onChange: (next: boolean) => void
}) {
  return (
    <label className="flex items-start gap-2">
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        data-testid={testId}
        onChange={(event) => onChange(event.target.checked)}
        className="mt-0.5"
      />
      <span className="min-w-0">
        <span className="text-sm">{label}</span>
        {hint === undefined ? null : (
          <span className="text-muted-foreground block text-xs">{hint}</span>
        )}
      </span>
    </label>
  )
}
