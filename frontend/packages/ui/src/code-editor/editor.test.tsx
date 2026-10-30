import { render, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { CodeEditor } from './editor'

/**
 * 编辑器本身只有一件事值得测：**外部给的 `value` 必须进编辑器，外部改值必须同步进去**。
 * 这条写错的后果是"用户正在打的字被回退"或"光标每次输入都跳到末尾"，两者都不会让任何
 * 上层测试变红。高亮与补全交给 CodeMirror，不在这里重复断言它的内部实现。
 */
describe('CodeEditor', () => {
  it('初次挂载就带出 starter，外部改值会替换文档', async () => {
    const { container, rerender } = render(
      <CodeEditor value={'class Main {}'} onChange={vi.fn()} language="java" />,
    )
    await waitFor(() => expect(container.querySelector('.cm-content')?.textContent).toContain('class Main'))

    rerender(<CodeEditor value={'public class Other {}'} onChange={vi.fn()} language="java" />)
    await waitFor(() => {
      const text = container.querySelector('.cm-content')?.textContent ?? ''
      expect(text).toContain('public class Other')
      expect(text).not.toContain('class Main')
    })
    // 语法包真的接上了：`public` 被切成独立的带类名的 span（没接上时只有一个纯文本节点）
    expect(container.querySelector('.cm-content')?.innerHTML).toContain('<span class="')
  })
})
