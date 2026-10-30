import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { CitationLink } from './citation-link'

afterEach(cleanup)

describe('CitationLink', () => {
  it('引用前缀的链接渲染成按钮，点了把 uri 交回应用', () => {
    const opened: string[] = []
    render(<CitationLink href="https://paideia.invalid/entry:1" onOpenUri={(uri) => opened.push(uri)}>第 1 关</CitationLink>)

    fireEvent.click(screen.getByTestId('citation-entry-1'))
    expect(opened).toEqual(['entry:1'])
  })

  it('普通外链仍是新窗口打开，不拦截', () => {
    render(<CitationLink href="https://example.com/x">外链</CitationLink>)
    const link = screen.getByText('外链')
    expect(link.tagName).toBe('A')
    expect(link.getAttribute('href')).toBe('https://example.com/x')
    expect(link.getAttribute('target')).toBe('_blank')
  })

  it('没有 href 时当普通文本，不抛', () => {
    render(<CitationLink>裸文本</CitationLink>)
    expect(screen.getByText('裸文本').tagName).toBe('A')
  })
})
