import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  NoteAutosaver,
  noteDraftsEqual,
  shouldApplyServerDocument,
  type NoteDraft,
} from './note-autosave'

function draft(patch: Partial<NoteDraft> = {}): NoteDraft {
  return { selected: 1, entryId: null, title: '', body: '', category: '', level: '', ...patch }
}

/** 反复让出微任务，推进 flush() 内部的 await 链（不依赖真实计时器）。 */
async function drainMicrotasks(times = 10): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await Promise.resolve()
  }
}

describe('NoteAutosaver', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('慢保存期间继续编辑，完成后补存最新内容（旧实现会丢弃最后一次编辑）', async () => {
    const calls: NoteDraft[] = []
    const deferred: Array<{ resolve: () => void }> = []
    const save = vi.fn(
      (sent: NoteDraft) =>
        new Promise<void>((resolve) => {
          calls.push(sent)
          deferred.push({ resolve })
        }),
    )
    const errors: unknown[] = []
    const dirtyChanges: boolean[] = []
    const autosaver = new NoteAutosaver(draft({ body: '' }), {
      debounceMs: 700,
      save,
      onError: (reason) => errors.push(reason),
      onDirtyChange: (value) => dirtyChanges.push(value),
    })

    autosaver.setDraft(draft({ body: 'v1' }))
    autosaver.markDirty()
    expect(dirtyChanges.at(-1)).toBe(true)

    // 防抖 700ms 后开始保存 v1，此时 save 挂起未完成
    vi.advanceTimersByTime(700)
    expect(save).toHaveBeenCalledTimes(1)
    expect(calls[0]?.body).toBe('v1')

    // 保存未完成期间继续输入 v2 并再次触发：不应并发发起第二个保存，而是记为待补存
    autosaver.setDraft(draft({ body: 'v2' }))
    autosaver.markDirty()
    vi.advanceTimersByTime(700)
    expect(save).toHaveBeenCalledTimes(1)

    // 首个保存完成 → 期间改过，自动补存最新 v2
    deferred[0]?.resolve()
    await drainMicrotasks()
    expect(save).toHaveBeenCalledTimes(2)
    expect(calls[1]?.body).toBe('v2')

    // 补存完成且内容未再变化 → 回到"与服务端一致"
    deferred[1]?.resolve()
    await drainMicrotasks()
    expect(errors).toEqual([])
    expect(dirtyChanges.at(-1)).toBe(false)
    expect(calls[1] === undefined ? null : noteDraftsEqual(calls[1], draft({ body: 'v2' }))).toBe(true)
  })

  it('保存失败时保留 dirty，不误报已保存', async () => {
    const save = vi.fn(() => Promise.reject(new Error('network down')))
    const errors: unknown[] = []
    const dirtyChanges: boolean[] = []
    const autosaver = new NoteAutosaver(draft(), {
      save,
      onError: (reason) => errors.push(reason),
      onDirtyChange: (value) => dirtyChanges.push(value),
    })

    autosaver.setDraft(draft({ body: 'x' }))
    autosaver.markDirty()
    await autosaver.flush()
    await drainMicrotasks()

    expect(errors).toHaveLength(1)
    expect(dirtyChanges).toEqual([true])
  })

  it('markClean 丢弃排队中的保存并回到一致', () => {
    const save = vi.fn(() => Promise.resolve())
    const dirtyChanges: boolean[] = []
    const autosaver = new NoteAutosaver(draft(), {
      save,
      onError: () => undefined,
      onDirtyChange: (value) => dirtyChanges.push(value),
    })
    autosaver.markDirty()
    autosaver.markClean()
    // 计时已被清掉：再推进时间也不会触发保存
    vi.advanceTimersByTime(2000)
    expect(save).not.toHaveBeenCalled()
    expect(dirtyChanges).toEqual([true, false])
  })
})

describe('shouldApplyServerDocument', () => {
  it('同一篇文档不覆盖本地（编辑期间的后台刷新）', () => {
    expect(shouldApplyServerDocument(5, 5)).toBe(false)
  })

  it('首次载入或切换到别的文档才覆盖', () => {
    expect(shouldApplyServerDocument(null, 5)).toBe(true)
    expect(shouldApplyServerDocument(4, 5)).toBe(true)
  })
})
