import { describe, expect, it } from 'vitest'

import { errorMessage } from './error-message'

describe('errorMessage', () => {
  it('Error 取 message', () => {
    expect(errorMessage(new Error('boom'))).toBe('boom')
  })

  it('非 Error 无 fallback 时用 String(cause)（不再渲染 undefined）', () => {
    expect(errorMessage('plain')).toBe('plain')
    expect(errorMessage(undefined)).toBe('undefined')
  })

  it('非 Error 且有 fallback 时用 fallback', () => {
    expect(errorMessage({ code: 1 }, '请求失败，请稍后重试')).toBe('请求失败，请稍后重试')
  })

  it('fallback 不吞 Error 的 message（Error 优先）', () => {
    expect(errorMessage(new Error('具体原因'), '通用兜底')).toBe('具体原因')
  })
})