import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import type { AssistantApi } from './client'
import { AssistantProvider, useAssistantContext } from './context'
import { ConversationPanel } from './conversation-panel'

/**
 * 收起面板必须中断在飞的上游调用（WORK-011 REQ-011 / AC-009 后半）。
 *
 * <p>这条只在**这一层**验得动：真正要盯的是"卸载时的那个 cleanup effect"。放到 e2e 里就得伪造
 * 一个永不结束的流，那测的是桩，不是产品。
 *
 * <p>它会在什么时候红：那个 `useEffect(() => () => abortRef.current?.abort(), [])` 被删掉，
 * 或者"中断"被改成只在收起按钮里做——而除卸载外面板没有第二个中断入口（外壳收起走的是另一条
 * return 分支，等于卸载）。
 */
// 与 summary-card.test.tsx 同因：本包没把 vitest 配成 globals，RTL 不会自动清理。
afterEach(cleanup)

/**
 * 只实现这条用例走到的方法。
 *
 * <p>`sendMessage` **永不落定**——"在流式中"就是它不回来这件事本身；本用例不关心回复内容，
 * 只关心卸载时那个 signal 有没有被 abort。
 */
function stubApi(onSend: (signal: AbortSignal | undefined) => void): AssistantApi {
  return {
    conversations: async () => [],
    messages: async () => [],
    createConversation: async () => ({ id: 1 }),
    // 面板挂载时会读一次记忆开关（WORK-013）；三个都开着，与"没设置过"的后端默认一致。
    settings: async () => ({
      memoryEnabled: true,
      referenceSavedMemories: true,
      referenceChatHistory: true,
    }),
    sendMessage: (_id: number, _text: string, _onEvent: unknown, signal?: AbortSignal) => {
      onSend(signal)
      return new Promise<void>(() => {})
    },
  } as unknown as AssistantApi
}

describe('收起面板', () => {
  it('卸载即中断在飞的上游调用（否则会继续计费到用户看不见的地方）', async () => {
    let signal: AbortSignal | undefined
    const { unmount } = render(
      <AssistantProvider>
        <ConversationPanel api={stubApi((value) => { signal = value })} />
      </AssistantProvider>,
    )

    fireEvent.change(screen.getByTestId('conversation-input'), { target: { value: '问一句' } })
    fireEvent.click(screen.getByTestId('conversation-send'))

    await waitFor(() => expect(signal).toBeDefined())
    expect(signal?.aborted).toBe(false)

    unmount()

    expect(signal?.aborted).toBe(true)
  })
})

/**
 * 预填（WORK-014 AC-010）：点「问 AI」后**输入框有内容、但没有发出任何请求**。
 *
 * <p>它会在什么时候红：有人把 `ask()` 实现成"直接发送"，或者面板消费 pending 时顺手调了
 * `send()`。这两条都会让用户在没确认的情况下花积分，所以必须有一条测试钉住。
 */
describe('预填不发送', () => {
  it('把内容放进输入框，且 sendMessage 一次都没被调用', async () => {
    let sent = 0
    const api = {
      ...stubApi(() => {}),
      sendMessage: () => {
        sent += 1
        return new Promise<void>(() => {})
      },
    } as unknown as AssistantApi

    function Harness() {
      const { ask } = useAssistantContext()
      return (
        <>
          <button type="button" data-testid="ask-trigger" onClick={() => ask('结合《标题》第 2 步（a.js:10），解释这里。')}>
            问
          </button>
          <ConversationPanel api={api} />
        </>
      )
    }

    render(
      <AssistantProvider>
        <Harness />
      </AssistantProvider>,
    )

    fireEvent.click(screen.getByTestId('ask-trigger'))

    await waitFor(() => {
      const input = screen.getByTestId('conversation-input') as HTMLTextAreaElement
      expect(input.value).toContain('第 2 步')
    })
    expect(sent).toBe(0)
  })
})

/**
 * 回答下方的赞/踩（WORK-036 AC-010 的前端一半）。
 *
 * <p>钉住三件事：点踩**先展开原因**而不是直接提交、选完原因才真正调接口、
 * 提交后显示"已反馈"。这三件事任何一件错位，用户会以为"我点了但没记录"。
 *
 * <p>它会在什么时候红：有人把点踩改成直接提交（那会丢掉原因），
 * 或者 `traceId` 没从 done 事件透传下来（反馈会挂不到任何一轮上，且不报错）。
 */
describe('回答反馈', () => {
  it('点踩展开原因，选完才提交，并显示已反馈', async () => {
    const submitted: unknown[] = []
    const api = {
      conversations: async () => [],
      messages: async () => [],
      createConversation: async () => ({ id: 1 }),
      settings: async () => ({
        memoryEnabled: true,
        referenceSavedMemories: true,
        referenceChatHistory: true,
      }),
      sendMessage: async (
        _id: number, _text: string,
        onEvent: (event: Record<string, unknown>) => void,
      ) => {
        onEvent({ type: 'content', delta: '这是一条回答' })
        onEvent({ type: 'done', usage: null, empty: false, traceId: 'abc123' })
      },
      submitFeedback: async (input: unknown) => { submitted.push(input) },
    } as unknown as AssistantApi

    render(
      <AssistantProvider>
        <ConversationPanel api={api} />
      </AssistantProvider>,
    )
    fireEvent.change(screen.getByTestId('conversation-input'), { target: { value: '问一句' } })
    fireEvent.click(screen.getByTestId('conversation-send'))

    const down = await screen.findByTestId('feedback-down')
    // 还没选原因：一次都不该提交
    expect(submitted).toHaveLength(0)

    fireEvent.click(down)
    fireEvent.click(await screen.findByTestId('feedback-reason-incorrect'))

    await waitFor(() => expect(submitted).toHaveLength(1))
    expect(submitted[0]).toMatchObject({
      traceId: 'abc123',
      rating: 'down',
      reason: 'incorrect',
    })
    // 状态要落到这条消息上，否则用户看不出自己评过
    expect(await screen.findByTestId('feedback-done')).toBeDefined()
  })
})

/**
 * AC-006（WORK-058）：回答里的 `[[entry:123]]` 在**面板里**真的变成可点引用。
 *
 * <p>为什么不用 e2e：正文渲染器是 `lazy()` 引入的，e2e 里要么喂桩消息（试过没跑通），
 * 要么真调一次上游（不确定且有成本）。这里用桩 `sendMessage` 走完整的面板渲染路径，
 * 验的正是"提示词约定 → linkify → CitationLink → 交回应用"这条接线。
 */
describe('回答里的对象引用', () => {
  it('渲染成可点引用，点了把 uri 交回应用', async () => {
    const opened: string[] = []
    const api = {
      conversations: async () => [],
      messages: async () => [],
      createConversation: async () => ({ id: 1 }),
      settings: async () => ({
        memoryEnabled: true,
        referenceSavedMemories: true,
        referenceChatHistory: true,
      }),
      sendMessage: async (
        _id: number,
        _text: string,
        onEvent: (event: Record<string, unknown>) => void,
      ) => {
        onEvent({ type: 'content', delta: '先看 [[entry:123]] 这一段。' })
        onEvent({ type: 'done', usage: null })
      },
    } as unknown as AssistantApi

    render(
      <AssistantProvider>
        <ConversationPanel api={api} onOpenUri={(uri) => opened.push(uri)} />
      </AssistantProvider>,
    )

    fireEvent.change(screen.getByTestId('conversation-input'), { target: { value: '它在说什么' } })
    fireEvent.click(screen.getByTestId('conversation-send'))

    const link = await screen.findByTestId('citation-entry-123')
    fireEvent.click(link)
    expect(opened).toEqual(['entry:123'])
  })
})
