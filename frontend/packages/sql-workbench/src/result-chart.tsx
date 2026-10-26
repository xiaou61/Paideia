import { Button } from '@paideia/ui'
import { useMemo, useState } from 'react'

import { NULL_MARKER, looksNumeric, type ResultTable } from './result-tables'

/**
 * 结果图表（WORK-035 T-06）。
 *
 * <p><b>不引图表库，直接画 SVG</b>：只需要柱状与折线两种，且必须**手动选轴**（见下）。
 * 图表库的自动布局、动画、响应式容器在这里都用不上，而它们会带来一个几百 kB 的依赖
 * （与 T-01 的体积纪律冲突）。两条折线/柱子的坐标计算是几十行数学。
 *
 * <p><b>为什么必须手动选轴</b>：SQL 结果集是任意形状，没有任何元信息说明"哪列是维度、
 * 哪列是度量"。自动猜一定会猜错——比如把 ID 当度量画出一条锯齿线，看起来像数据，
 * 其实是把标识符当成了数值。猜错比不做更糟，因为用户会拿它下结论。
 */

type ChartKind = 'bar' | 'line'

/** 把单元格解析成数值。NULL 与空串视为缺失（不参与绘图，不当作 0）。 */
export function toNumber(value: string | undefined): number | null {
  if (value === undefined || value === NULL_MARKER || value === '') {
    return null
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** 哪些列可以作为 Y 轴（度量）：必须看起来是数值。 */
export function numericColumns(table: ResultTable): number[] {
  return table.columns
    .map((_, index) => index)
    .filter((index) => looksNumeric(table.rows, index))
}

export function ResultChart({ table }: { table: ResultTable }) {
  const [kind, setKind] = useState<ChartKind>('bar')
  const [xColumn, setXColumn] = useState(0)
  const [yColumn, setYColumn] = useState<number | null>(null)

  const yCandidates = useMemo(() => numericColumns(table), [table])
  // 换结果集时把列索引夹回合法范围：组件在同一个位置复用，重新执行 SQL 后
  // 上一次选的 xColumn 可能越界（列变少了），会让 X 轴标签全空。
  const safeX = xColumn < table.columns.length ? xColumn : 0
  const safeY = yColumn !== null && yColumn < table.columns.length ? yColumn : null
  // 默认选第一个非 X 的数值列；没有就保持 null（让用户看到提示而不是一张空图）
  const effectiveY = safeY ?? yCandidates.find((index) => index !== safeX) ?? null

  if (yCandidates.length === 0) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="chart-unavailable">
        这个结果集里没有可当 Y 轴的数值列，画不了图。图表需要一列文字当 X 轴、一列数字当 Y 轴。
      </p>
    )
  }

  const points = table.rows
    .map((row) => ({
      label: row[xColumn] === NULL_MARKER ? 'NULL' : (row[xColumn] ?? ''),
      value: effectiveY === null ? null : toNumber(row[effectiveY]),
    }))
    // 缺值的行不画：把 NULL 当 0 会在图上凭空造出一个数据点
    .filter((point): point is { label: string; value: number } => point.value !== null)

  if (effectiveY === null || points.length === 0) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="chart-unavailable">
        选中的列没有可用的数值，画不了图。
      </p>
    )
  }

  const values = points.map((point) => point.value)
  const max = Math.max(...values)
  const min = Math.min(...values, 0)
  const range = max - min || 1

  const WIDTH = 520
  const HEIGHT = 200
  const PAD_L = 40
  const PAD_B = 28
  const plotW = WIDTH - PAD_L - 12
  const plotH = HEIGHT - PAD_B - 12

  const x = (index: number) =>
    PAD_L + (points.length === 1 ? plotW / 2 : (index / (points.length - 1)) * plotW)
  const y = (value: number) => 12 + plotH - ((value - min) / range) * plotH

  return (
    <div className="space-y-3" data-testid="result-chart">
      <div className="flex flex-wrap items-end gap-3 text-sm">
        <label className="flex items-center gap-1.5">
          X 轴
          <select
            className="border-border bg-card rounded-md border px-2 py-1 text-sm"
            value={xColumn}
            onChange={(event) => setXColumn(Number(event.target.value))}
            data-testid="chart-x"
          >
            {table.columns.map((column, index) => (
              <option key={column} value={index}>
                {column}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-1.5">
          Y 轴
          <select
            className="border-border bg-card rounded-md border px-2 py-1 text-sm"
            value={effectiveY}
            onChange={(event) => setYColumn(Number(event.target.value))}
            data-testid="chart-y"
          >
            {yCandidates.map((index) => (
              <option key={index} value={index}>
                {table.columns[index]}
              </option>
            ))}
          </select>
        </label>
        <div className="flex gap-1">
          <Button
            type="button"
            size="sm"
            variant={kind === 'bar' ? 'default' : 'ghost'}
            onClick={() => setKind('bar')}
          >
            柱状
          </Button>
          <Button
            type="button"
            size="sm"
            variant={kind === 'line' ? 'default' : 'ghost'}
            onClick={() => setKind('line')}
          >
            折线
          </Button>
        </div>
      </div>

      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        className="border-border w-full rounded-lg border"
        role="img"
        aria-label={`${table.columns[xColumn]} 对 ${table.columns[effectiveY]} 的${kind === 'bar' ? '柱状' : '折线'}图`}
      >
        {/* 零轴与顶部刻度：只画两条参考线，避免刻度线喧宾夺主 */}
        <line x1={PAD_L} y1={y(min)} x2={WIDTH - 12} y2={y(min)} stroke="var(--border)" />
        <line x1={PAD_L} y1={y(max)} x2={WIDTH - 12} y2={y(max)} stroke="var(--border)" strokeDasharray="3 3" />
        <text x={4} y={y(max) + 4} fontSize={10} fill="var(--muted-foreground)">
          {max}
        </text>
        <text x={4} y={y(min) + 4} fontSize={10} fill="var(--muted-foreground)">
          {min}
        </text>

        {kind === 'bar'
          ? points.map((point, index) => {
              const barW = Math.max(2, Math.min(36, plotW / Math.max(points.length, 1) - 4))
              return (
                <rect
                  key={index}
                  x={x(index) - barW / 2}
                  y={y(Math.max(point.value, 0))}
                  width={barW}
                  height={Math.abs(y(point.value) - y(Math.max(0, min)))}
                  fill="var(--primary)"
                  opacity={0.85}
                />
              )
            })
          : (
            <polyline
              fill="none"
              stroke="var(--primary)"
              strokeWidth={2}
              points={points.map((point, index) => `${x(index)},${y(point.value)}`).join(' ')}
            />
          )}

        {kind === 'line'
          ? points.map((point, index) => (
              <circle key={index} cx={x(index)} cy={y(point.value)} r={2.5} fill="var(--primary)" />
            ))
          : null}

        {/* X 轴标签：点太多时只标首、中、尾，否则会糊成一片 */}
        {points.map((point, index) => {
          const show =
            points.length <= 8 || index === 0 || index === points.length - 1 || index === Math.floor(points.length / 2)
          if (!show) {
            return null
          }
          return (
            <text
              key={`label-${index}`}
              x={x(index)}
              y={HEIGHT - 8}
              fontSize={10}
              textAnchor="middle"
              fill="var(--muted-foreground)"
            >
              {point.label.length > 8 ? `${point.label.slice(0, 8)}…` : point.label}
            </text>
          )
        })}
      </svg>

      <p className="text-muted-foreground text-xs">
        共 {points.length} 个数据点。缺值的行没有画出来——把 NULL 当 0 会在图上凭空多出一个点。
      </p>
    </div>
  )
}
