import { describe, expect, it } from 'vitest'

import { formatDurationMs, formatLocalDateTime } from './format'

describe('formatLocalDateTime', () => {
  it('null / undefined 用 fallback', () => {
    expect(formatLocalDateTime(null)).toBe('—')
    expect(formatLocalDateTime(undefined, '（空）')).toBe('（空）')
  })

  it('解析失败原样返回（不渲染 Invalid Date）', () => {
    expect(formatLocalDateTime('不是时间')).toBe('不是时间')
  })

  it('合法 ISO 按 zh-CN / 24 小时制展示', () => {
    expect(formatLocalDateTime('2026-11-02T08:00:00.000Z')).toContain('2026')
  })
})

describe('formatDurationMs', () => {
  it('null / undefined 用 fallback', () => {
    expect(formatDurationMs(null)).toBe('—')
  })

  it('不足 1 秒用 ms，超过用秒（一位小数）', () => {
    expect(formatDurationMs(120)).toBe('120 ms')
    expect(formatDurationMs(1500)).toBe('1.5 s')
  })
})