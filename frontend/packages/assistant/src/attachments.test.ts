import { describe, expect, it } from 'vitest'

import {
  ATTACHMENT_EXCERPT_LIMIT,
  ATTACHMENT_LIMIT,
  attachmentUri,
  parseAttachmentUri,
  putAttachment,
  removeAttachment,
  serializeAttachments,
  type Attachment,
} from './attachments'

describe('attachmentUri / parseAttachmentUri', () => {
  it('拼出合法的 uri，并原样解析回来', () => {
    expect(attachmentUri('entry', 123)).toBe('entry:123')
    expect(attachmentUri('level', 'java/13')).toBe('level:java/13')
    expect(parseAttachmentUri('entry:123')).toEqual({ type: 'entry', id: '123' })
    expect(parseAttachmentUri('level:java/13')).toEqual({ type: 'level', id: 'java/13' })
  })

  it('空 id 是调用方的 bug，直接抛', () => {
    expect(() => attachmentUri('note', '   ')).toThrow('id 不能为空')
  })

  it('未知类型、缺 id、带空白都解析成 null（调用方降级为纯文本）', () => {
    expect(parseAttachmentUri('planet:earth')).toBeNull()
    expect(parseAttachmentUri('entry:')).toBeNull()
    expect(parseAttachmentUri(':123')).toBeNull()
    expect(parseAttachmentUri('entry:12 3')).toBeNull()
    expect(parseAttachmentUri('entry123')).toBeNull()
  })
})

describe('putAttachment', () => {
  const item = (id: number): Attachment => ({ uri: `entry:${id}`, label: `第 ${id} 条` })

  it('同 uri 覆盖而不是追加', () => {
    const once = putAttachment([], { uri: 'entry:1', label: '旧', excerpt: 'a' })
    const twice = putAttachment(once, { uri: 'entry:1', label: '新', excerpt: 'b' })
    expect(twice).toHaveLength(1)
    expect(twice[0]?.label).toBe('新')
  })

  it(`超过 ${ATTACHMENT_LIMIT} 条丢最旧的`, () => {
    let list: Attachment[] = []
    for (let id = 1; id <= ATTACHMENT_LIMIT + 1; id += 1) {
      list = putAttachment(list, item(id))
    }
    expect(list).toHaveLength(ATTACHMENT_LIMIT)
    // 第 1 条被挤掉，最后一条是刚加的
    expect(list[0]?.uri).toBe('entry:2')
    expect(list[list.length - 1]?.uri).toBe(`entry:${ATTACHMENT_LIMIT + 1}`)
  })

  it('移除只移除指定那条', () => {
    const list = [item(1), item(2)]
    expect(removeAttachment(list, 'entry:1').map((entry) => entry.uri)).toEqual(['entry:2'])
    // 不存在时原样返回（不抛）
    expect(removeAttachment(list, 'entry:9')).toHaveLength(2)
  })
})

describe('serializeAttachments', () => {
  it('空列表不产生上下文', () => {
    expect(serializeAttachments([])).toBeUndefined()
  })

  it('每条带序号、uri 与标签；没有摘录就不加引用块', () => {
    const text = serializeAttachments([
      { uri: 'entry:12', label: '第 12 关 数组' },
      { uri: 'note:3', label: '我的笔记', excerpt: '一段正文' },
    ])
    expect(text).toContain('### 附件 1 · entry:12 · 第 12 关 数组')
    expect(text).toContain('### 附件 2 · note:3 · 我的笔记')
    expect(text).toContain('一段正文')
  })

  it('单条摘录超长时按上限截断并写明还剩多少', () => {
    const long = 'x'.repeat(ATTACHMENT_EXCERPT_LIMIT + 20)
    const text = serializeAttachments([{ uri: 'note:1', label: 'n', excerpt: long }]) ?? ''
    expect(text).toContain('后面还有 20 个字符没显示')
    expect(text).not.toContain('x'.repeat(ATTACHMENT_EXCERPT_LIMIT + 20))
  })
})
