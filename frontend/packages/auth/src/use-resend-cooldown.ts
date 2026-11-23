import { useCallback, useEffect, useState } from 'react'

/**
 * 发信按钮的冷却计时。
 *
 * <p>注册与忘记密码两处用的是同一条策略（与后端「同邮箱 60 秒间隔」对齐），
 * 之前两份实现逐行重复，改冷却规则时极易只改一处。这里抽成唯一实现。
 *
 * <p>用 `setTimeout` 每秒递减而不是 `setInterval`：后者每次 tick 都要重建 interval，
 * 且组件卸载时容易漏清理。
 */
export function useResendCooldown(seconds: number) {
  const [remaining, setRemaining] = useState(0)

  useEffect(() => {
    if (remaining <= 0) {
      return
    }
    const timer = globalThis.setTimeout(() => setRemaining((value) => value - 1), 1000)
    return () => globalThis.clearTimeout(timer)
  }, [remaining])

  const start = useCallback(() => setRemaining(seconds), [seconds])

  return { remaining, start }
}
