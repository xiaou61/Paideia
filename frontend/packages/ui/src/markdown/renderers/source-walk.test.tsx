import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { SourceReadingProvider } from './context'
import { SourceWalk } from './source-walk'

/**
 * 整文件走读的最小行为验收（WORK-057）。
 *
 * <p>只盯三件在纯函数里测不到的事：文件真的被取到并渲染出行号、切步后高亮行真的换了、
 * 控件真的重排了步骤。其余（字段校验、过滤规则）在 `schema.test.ts` 里。
 */

const A = 'org/apache/ibatis/binding/MapperProxy.java'
const B = 'org/apache/ibatis/binding/MapperMethod.java'

const WALK = {
  id: 'demo',
  title: '演示',
  repo: 'mybatis/mybatis-3',
  ref: 'mybatis-3.5.19',
  commit: 'ee0d4f4831ffdd311b0183c202f4ad6492a3f404',
  license: 'Apache-2.0',
  base: 'source/mybatis-3.5.19',
  files: [
    { path: A, label: 'MapperProxy' },
    { path: B, label: 'MapperMethod' },
  ],
  controls: [{ id: 'cache', type: 'toggle', label: '开启一级缓存', default: true }],
  steps: [
    {
      title: '第一步',
      file: A,
      line: 3,
      lines: 2,
      explain: '先看这里。',
      predict: { question: '下一步会换文件吗？', options: ['会', '不会'], answer: 1, reveal: '不会，先看缓存分支。' },
    },
    { title: '缓存步', file: A, line: 7, lines: 1, explain: '缓存分支。', when: { cache: true } },
    {
      title: '换文件',
      file: B,
      line: 5,
      lines: 2,
      explain: '换到另一个文件。',
      refs: [{ label: '回到代理', file: A, line: 3 }],
    },
  ],
}

const TEXTS: Record<string, string> = {
  [A]: Array.from({ length: 12 }, (_, index) => `A 行 ${index + 1}`).join('\n'),
  [B]: Array.from({ length: 12 }, (_, index) => `B 行 ${index + 1}`).join('\n'),
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown) => {
      const url = String(input)
      const path = url.split('/source/mybatis-3.5.19/')[1] ?? ''
      const body = TEXTS[path]
      return {
        ok: body !== undefined,
        status: body === undefined ? 404 : 200,
        text: async () => body ?? '',
      } as Response
    }),
  )
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function renderWalk() {
  return render(
    <SourceReadingProvider>
      <SourceWalk code={JSON.stringify(WALK)} />
    </SourceReadingProvider>,
  )
}

describe('SourceWalk（WORK-057）', () => {
  it('取到整份文件，默认停在第一步的高亮行', async () => {
    renderWalk()
    const lines = await screen.findAllByTestId('source-walk-focus-line')
    expect(lines[0]?.textContent).toContain('A 行 3')
    expect(screen.getAllByTestId('source-walk-step')).toHaveLength(3)
  })

  it('切下一步：高亮行变化，跨文件时页签跟着换', async () => {
    renderWalk()
    await screen.findAllByTestId('source-walk-focus-line')
    fireEvent.click(screen.getByTestId('source-walk-next'))
    await waitFor(() => {
      expect(screen.getAllByTestId('source-walk-focus-line')[0]?.textContent).toContain('A 行 7')
    })
    fireEvent.click(screen.getByTestId('source-walk-next'))
    await waitFor(() => {
      expect(screen.getAllByTestId('source-walk-focus-line')[0]?.textContent).toContain('B 行 5')
    })
  })

  it('预测：选完显示揭晓', async () => {
    renderWalk()
    await screen.findAllByTestId('source-walk-focus-line')
    fireEvent.click(screen.getByRole('button', { name: '会' }))
    await waitFor(() => {
      expect(screen.getByTestId('source-walk-predict').textContent).toContain('不会，先看缓存分支。')
    })
  })

  it('跨文件定位：跳到目标行，返回后回到当前步', async () => {
    renderWalk()
    await screen.findAllByTestId('source-walk-focus-line')
    fireEvent.click(screen.getByTestId('source-walk-next'))
    fireEvent.click(screen.getByTestId('source-walk-next'))
    await waitFor(() => {
      expect(screen.getAllByTestId('source-walk-focus-line')[0]?.textContent).toContain('B 行 5')
    })
    fireEvent.click(screen.getByRole('button', { name: '回到代理' }))
    await waitFor(() => {
      const pinned = screen.getByTestId('source-walk-pin-line')
      expect(pinned.textContent).toContain('A 行 3')
    })
    fireEvent.click(screen.getByRole('button', { name: '返回' }))
    await waitFor(() => {
      expect(screen.queryByTestId('source-walk-pin-line')).toBeNull()
      expect(screen.getAllByTestId('source-walk-focus-line')[0]?.textContent).toContain('B 行 5')
    })
  })

  it('关掉开关后步骤真的变少', async () => {
    renderWalk()
    await screen.findAllByTestId('source-walk-focus-line')
    fireEvent.click(screen.getByTestId('source-walk-control-cache'))
    await waitFor(() => {
      expect(screen.getAllByTestId('source-walk-step')).toHaveLength(2)
    })
  })
})
