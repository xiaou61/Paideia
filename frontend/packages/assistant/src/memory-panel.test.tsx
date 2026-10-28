import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AssistantApi } from './client'
import { MemoryPanel } from './memory-panel'
import type { MemoryCandidate, MemorySettings, MemoryView } from './types'

/**
 * 记忆面板的三块界面语义（WORK-013）。
 *
 * <p>盯四件最容易写错、也最容易被误读的事：**关掉总开关不等于删了记忆**；
 * **「清空并关闭」必须先过二次确认**（未确认就发请求＝把不可撤销的动作绑在一个误点上）；
 * **关与清空是两个动作**（点开关绝不能顺手调清空）；以及**分页到底了要把按钮收掉**
 * （留着它会让人一直点一个永远为空的动作）。
 *
 * <p>断言直接看 `textContent`：本仓没有引 `@testing-library/jest-dom`，也不必为几条断言引它。
 */
// 本包没有把 vitest 配成 globals，RTL 就不会自动注册 afterEach 清理——
// 不显式清理的话，第二个用例会在上一个用例残留的 DOM 里找到多个同 testid 的元素。
afterEach(cleanup)

/** 与实现里的步长一致。改步长时这里会一起红，那是想要的信号。 */
const PAGE = 200

function memory(overrides: Partial<MemoryView> = {}): MemoryView {
  return {
    source: 'text',
    id: 1,
    text: '他喜欢先看例子再看原理',
    createdAt: '2026-10-16T10:00:00Z',
    origin: 'manual',
    reason: null,
    conversationId: null,
    ...overrides,
  }
}

function candidate(overrides: Partial<MemoryCandidate> = {}): MemoryCandidate {
  return {
    id: 7,
    store: 'text',
    kind: null,
    content: '他最近在翻 DDIA',
    reason: '动机不明',
    conversationId: 3,
    status: 'pending',
    decisionReason: null,
    createdAt: '2026-10-28T10:00:00Z',
    expiresAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

function settings(overrides: Partial<MemorySettings> = {}): MemorySettings {
  return {
    memoryEnabled: true,
    referenceSavedMemories: true,
    referenceChatHistory: true,
    ...overrides,
  }
}

/**
 * 假的 `AssistantApi`：本包的单测**不发真请求**。
 *
 * <p>用不到的端点一律 reject 而不是返回空值——哪条用例误调了它，报错会直接指出来，
 * 而不是静默通过。
 */
function fakeApi(overrides: Partial<AssistantApi> = {}): AssistantApi {
  const unused = () => Promise.reject(new Error('这条用例不该用到这个端点'))
  return {
    credential: unused,
    credits: unused,
    summary: unused,
    generate: unused,
    createConversation: unused,
    conversations: unused,
    messages: unused,
    memories: vi.fn(async () => [] as MemoryView[]),
    pendingCandidates: vi.fn(async () => [] as MemoryCandidate[]),
    acceptCandidate: vi.fn(async () => undefined),
    rejectCandidate: vi.fn(async () => undefined),
    deleteMemory: vi.fn(async () => true),
    settings: vi.fn(async () => settings()),
    saveSettings: vi.fn(async (value: MemorySettings) => value),
    clearMemories: vi.fn(async () => ({ cleared: 2, settings: settings({ memoryEnabled: false }) })),
    reviewInterview: unused,
    interviewEvaluations: unused,
    remember: unused,
    sendMessage: unused,
    submitFeedback: unused,
    ...overrides,
  }
}

describe('记忆面板 · 开关区', () => {
  it('总开关关着时也写明"关掉不会删除已有记忆"，并指出真正会删的是哪个按钮', async () => {
    const api = fakeApi({ settings: vi.fn(async () => settings({ memoryEnabled: false })) })
    render(<MemoryPanel api={api} />)

    const note = await screen.findByTestId('memory-cost-note')
    // 少写这一句，用户点完"关"就会以为记忆已经没了
    expect(note.textContent).toContain('不会删除已有的记忆')
    expect(note.textContent).toContain('清空全部记忆并关闭')
    expect(note.textContent).toContain('消耗你的积分')
  })
})

describe('记忆面板 · 列表', () => {
  it('来源标签分得清"自动整理"与"你让它记的"，自动的还带出处与折叠的理由', async () => {
    const api = fakeApi({
      memories: vi.fn(async () => [
        memory({ id: 1, origin: 'manual' }),
        memory({
          id: 2,
          origin: 'auto',
          text: '他连问了两轮三次握手，还没搞懂',
          conversationId: 7,
          reason: '同一个点连问两轮，判定为还不知道',
        }),
      ]),
    })
    render(<MemoryPanel api={api} />)

    expect((await screen.findByTestId('memory-origin-text-1')).textContent).toContain('你让它记的')
    expect(screen.getByTestId('memory-origin-text-2').textContent).toContain('自动整理')

    expect(screen.getByText(/出自对话 7/)).toBeTruthy()
    // 理由**默认折叠**：它常常是一整句话，全展开会把列表撑得没法扫
    const reason = screen.getByTestId('memory-reason-text-2') as HTMLDetailsElement
    expect(reason.open).toBe(false)
    expect(reason.textContent).toContain('同一个点连问两轮')
  })

  it('保留"可逐条删除"这句承诺（逐条删除是既有能力，e2e 也盯着这行文案）', async () => {
    render(<MemoryPanel api={fakeApi({ memories: vi.fn(async () => [memory()]) })} />)

    await screen.findByTestId('memory-item')
    expect(screen.getByTestId('memory-panel').textContent).toContain('可逐条删除')
  })

  it('「加载更多」按 offset 拉下一页；拉回不足一页就把按钮收掉', async () => {
    const firstPage = Array.from({ length: PAGE }, (_, index) => memory({ id: index + 1 }))
    const memories = vi.fn(async (_limit = PAGE, offset = 0) =>
      offset === 0 ? firstPage : [memory({ id: 9999 })],
    )
    render(<MemoryPanel api={fakeApi({ memories })} />)

    fireEvent.click(await screen.findByTestId('memory-load-more'))
    expect(memories).toHaveBeenCalledWith(PAGE, 0)

    // 第二页的起点是已加载条数，不是页号
    await waitFor(() => expect(memories).toHaveBeenCalledWith(PAGE, PAGE))
    await waitFor(() => expect(screen.queryByTestId('memory-load-more')).toBeNull())
    expect(screen.getAllByTestId('memory-item')).toHaveLength(PAGE + 1)
  })
})

describe('记忆面板 · 危险区', () => {
  it('「清空并关闭」先过二次确认：确认前不调接口，确认后开关跟着响应变成关', async () => {
    const api = fakeApi({ memories: vi.fn(async () => [memory()]) })
    render(<MemoryPanel api={api} />)

    fireEvent.click(await screen.findByTestId('memory-clear'))
    expect(await screen.findByTestId('memory-clear-dialog')).toBeTruthy()
    // 只是打开了确认框：到这一步一个字节都还没删
    expect(api.clearMemories).not.toHaveBeenCalled()

    fireEvent.click(screen.getByTestId('memory-clear-confirm'))
    await waitFor(() => expect(api.clearMemories).toHaveBeenCalledTimes(1))

    // 关掉总开关是**后端那一次调用**做的，界面只能照响应更新
    await waitFor(() =>
      expect((screen.getByTestId('memory-enabled') as HTMLInputElement).checked).toBe(false),
    )
    expect(screen.getByTestId('memory-empty').textContent).toContain('还没有任何记忆')
  })

  it('关掉总开关不会顺手清空记忆——只关不删与清空并关闭是两件事', async () => {
    const api = fakeApi({ memories: vi.fn(async () => [memory()]) })
    render(<MemoryPanel api={api} />)

    fireEvent.click(await screen.findByTestId('memory-enabled'))

    await waitFor(() => expect(api.saveSettings).toHaveBeenCalledTimes(1))
    // 三个布尔一次给全：漏字段会被后端当成"关"
    expect(api.saveSettings).toHaveBeenCalledWith({
      memoryEnabled: false,
      referenceSavedMemories: true,
      referenceChatHistory: true,
    })
    expect(api.clearMemories).not.toHaveBeenCalled()
    expect(screen.getByTestId('memory-item')).toBeTruthy()
  })

  it('取消二次确认后不调接口，开关也不动', async () => {
    const api = fakeApi({ memories: vi.fn(async () => [memory()]) })
    render(<MemoryPanel api={api} />)

    fireEvent.click(await screen.findByTestId('memory-clear'))
    fireEvent.click(await screen.findByTestId('memory-clear-cancel'))

    expect(api.clearMemories).not.toHaveBeenCalled()
    expect((screen.getByTestId('memory-enabled') as HTMLInputElement).checked).toBe(true)
  })
})

describe('记忆面板 · 待确认候选（WORK-050）', () => {
  it('有候选时显示内容与理由，点「记住」调接受端点并同时重拉两张表', async () => {
    const acceptCandidate = vi.fn(async () => undefined)
    const pendingCandidates = vi.fn(async () => [candidate()])
    const api = fakeApi({ pendingCandidates, acceptCandidate })

    render(<MemoryPanel api={api} />)

    expect(await screen.findByTestId('memory-candidates')).toBeTruthy()
    expect(screen.getByText('他最近在翻 DDIA')).toBeTruthy()
    expect(screen.getByText('理由：动机不明')).toBeTruthy()

    fireEvent.click(screen.getByTestId('memory-candidate-accept-7'))

    await waitFor(() => expect(acceptCandidate).toHaveBeenCalledWith(7))
    // 接受之后既重拉记忆列表、也重拉候选：只刷一边会让"待确认"留着已经生效的那条
    await waitFor(() => expect(pendingCandidates).toHaveBeenCalledTimes(2))
  })

  it('被敏感信息拦下的那条只显示"没有记录"，不显示原文', async () => {
    const api = fakeApi({
      pendingCandidates: vi.fn(async () => [
        candidate({ id: 9, content: null, reason: null, status: 'filtered', decisionReason: 'sensitive' }),
      ]),
    })

    render(<MemoryPanel api={api} />)

    expect(await screen.findByText(/疑似含敏感信息/)).toBeTruthy()
    // 只能"知道了"（拒绝），不能"记住"——它没有内容可记
    expect(screen.getByTestId('memory-candidate-dismiss-9')).toBeTruthy()
    expect(screen.queryByTestId('memory-candidate-accept-9')).toBeNull()
  })
})
