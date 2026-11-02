/**
 * 时间与时长的展示格式化（WORK-062 收敛）。
 *
 * <p>管理端原有 9 份 `when`/`formatTime`（三档兜底各不相同：`'—'`、原样返回、
 * 无保护直接渲染 `Invalid Date`）与 3 份逐字相同的 `ms`。这里统一为：
 * **无值 → fallback；给了但解析失败 → 原样返回**（不吞、不猜）。
 *
 * <p>刻意保留本地的两处：AnalyticsPage 的"只要时分秒"（`toLocaleTimeString`）与
 * AiRunsScreenPage 的 `clock`（按 hour/day 双子刻度）——它们的展示语义不同。
 */
export function formatLocalDateTime(value: string | null | undefined, fallback = '—'): string {
  if (value === null || value === undefined) {
    return fallback
  }
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('zh-CN', { hour12: false })
}

export function formatDurationMs(value: number | null | undefined, fallback = '—'): string {
  if (value === null || value === undefined) {
    return fallback
  }
  return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`
}