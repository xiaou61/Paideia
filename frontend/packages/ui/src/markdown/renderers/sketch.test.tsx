import { cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { Sketch } from './sketch'

/**
 * 手绘图组件（WORK-051）：降级、字体时序。
 *
 * <p>roughjs 在这里被替换成桩：本文件要测的是"什么时候开始画"和"画坏了显示什么"，
 * 真实线条由 `sketch-paint.test.ts` 覆盖。
 */
vi.mock('roughjs', () => ({
  default: {
    svg: () => ({
      rectangle: () => document.createElementNS('http://www.w3.org/2000/svg', 'path'),
      path: () => document.createElementNS('http://www.w3.org/2000/svg', 'path'),
      line: () => document.createElementNS('http://www.w3.org/2000/svg', 'path'),
    }),
  },
}))

const CODE = JSON.stringify({
  title: '一张小图',
  rows: [
    [
      { id: 'a', title: '起点' },
      { id: 'b', title: '终点' },
    ],
  ],
  arrows: [{ from: 'a', to: 'b' }],
})

function stubFonts(load: () => Promise<unknown>) {
  Object.defineProperty(document, 'fonts', { configurable: true, value: { load: vi.fn(load) } })
}

afterEach(cleanup)

describe('手绘图组件', () => {
  it('数据写坏时降级成「原因 + 原始文本」', () => {
    stubFonts(() => Promise.resolve([]))
    const { container } = render(<Sketch code='{ "rows": [' isIncomplete={false} language="sketch" />)
    expect(container.querySelector('[data-testid="block-fallback"]')).not.toBeNull()
    expect(container.textContent).toContain('不是合法 JSON')
  })

  it('正常数据画成 svg，并给读屏一个名字', async () => {
    stubFonts(() => Promise.resolve([]))
    const { container } = render(<Sketch code={CODE} isIncomplete={false} language="sketch" />)
    await waitFor(() => expect(container.querySelectorAll('[data-node]')).toHaveLength(2))
    const svg = container.querySelector('svg')!
    expect(svg.getAttribute('role')).toBe('img')
    expect(svg.getAttribute('aria-label')).toBe('一张小图')
  })

  it('字体没到手之前不画——否则按回退字体量出的宽度会裁掉字', async () => {
    let release: () => void = () => {}
    stubFonts(
      () =>
        new Promise((resolve) => {
          release = () => resolve([])
        }),
    )
    const { container } = render(<Sketch code={CODE} isIncomplete={false} language="sketch" />)
    // 让 roughjs 的桩与所有微任务跑完：此刻唯一没回来的只有字体
    await Promise.resolve()
    await Promise.resolve()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(container.querySelectorAll('[data-node]')).toHaveLength(0)

    release()
    await waitFor(() => expect(container.querySelectorAll('[data-node]')).toHaveLength(2))
  })
})
