import { Spinner } from '@paideia/ui'
import { useEffect, useState } from 'react'

import type { AssistantApi } from './client'
import type { CreditsView } from './types'
import { errorMessage } from '@paideia/core'

/** 流水的种类 → 人话。认不出的就照原样显示，不做兜底猜测。 */
const KIND_LABEL: Record<string, string> = {
  ADJUST: '管理员调整',
  RESERVE: '预扣',
  SETTLE: '结算',
  REFUND: '退还',
  // 下面三种是"用途单独记"的消耗。不登记的话面板会显示原始英文码，
  // 而那正是"我没聊几次怎么花了这么多"最需要看懂的地方。
  MEMORY_EXTRACT: '自动整理记忆',
  INTERVIEW_REVIEW: '面试点评',
  RESUME: '简历优化',
}

/**
 * 积分面板：还剩多少、每一笔花在哪（WORK-009 REQ-008）。
 *
 * <p>放在助手壳里而不是另开一个设置页：积分是**这一个助手**花的钱，与记忆面板同一类——
 * 顺着"它到底花了我多少"这个疑问点开壳就能看到。
 *
 * <p>**只读**：本项不做充值，所以这里没有任何写操作。积分只能由管理员发放，那是管理端的事；
 * 这里出现的每一笔都是既成事实。
 */
export function CreditsPanel({ api }: { api: AssistantApi }) {
  const [view, setView] = useState<CreditsView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    api
      .credits()
      .then((value) => {
        if (alive) {
          setView(value)
        }
      })
      .catch((cause: unknown) => {
        if (alive) {
          setError(errorMessage(cause))
        }
      })
    return () => {
      alive = false
    }
  }, [api])

  return (
    <div className="space-y-2" data-testid="credits-panel">
      {error === null ? null : (
        <p className="text-destructive text-xs" data-testid="credits-error">
          {error}
        </p>
      )}

      {view === null ? (
        <Spinner />
      ) : (
        <>
          <p className="text-sm">
            余额{' '}
            <span className="font-medium tabular-nums" data-testid="credits-balance">
              {view.balance}
            </span>{' '}
            积分
          </p>
          <p className="text-muted-foreground text-xs">
            按实际用量扣积分。当前没有自动充值——需要时请联系管理员。
          </p>

          {view.ledger.length === 0 ? (
            <p className="text-muted-foreground text-sm" data-testid="credits-empty">
              还没有任何消耗。
            </p>
          ) : (
            <ul className="space-y-1">
              {view.ledger.map((row, index) => (
                <li
                  key={`${row.createdAt ?? ''}-${row.kind}-${String(index)}`}
                  data-testid="credits-row"
                  className="border-border flex items-start justify-between gap-2 rounded border px-2 py-1"
                >
                  <span className="text-muted-foreground text-xs break-words">
                    {KIND_LABEL[row.kind] ?? row.kind}
                    {row.inputTokens === null ? '' : ` · 用 ${row.inputTokens}/${row.outputTokens ?? 0} token`}
                    {row.note === null ? '' : ` · ${row.note}`}
                  </span>
                  <span
                    className={`shrink-0 text-xs tabular-nums ${row.delta < 0 ? 'text-destructive' : ''}`}
                  >
                    {row.delta > 0 ? `+${row.delta}` : row.delta}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  )
}
