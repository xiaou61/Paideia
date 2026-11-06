import { Spinner } from '@paideia/ui'
import { useEffect, useState } from 'react'

import type { AssistantApi } from './client'
import type { CreditsView } from './types'
import { errorMessage } from '@paideia/core'

/**
 * 积分摘要：只剩「余额多少」这一行（WORK-009 REQ-008；2026-11-06 做减法）。
 *
 * <p>通用助手与记忆面板下线后，明细流水被砍掉，只把余额并进「身份与目标」页——
 * 用户需要知道的只有“我还能不能用 AI”，而不是每一笔的 token 账。
 *
 * <p>**只读**：本项不做充值，所以这里没有任何写操作。积分只能由管理员发放，那是管理端的事。
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
    <div className="space-y-1" data-testid="credits-panel">
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
            分
          </p>
          <p className="text-muted-foreground text-xs">
            按实际用量扣分，暂不支持自助充值——需要时请联系管理员。
          </p>
        </>
      )}
    </div>
  )
}
