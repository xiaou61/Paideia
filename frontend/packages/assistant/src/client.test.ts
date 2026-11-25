import { describe, expect, it } from 'vitest'

import type { ApiClient } from '@paideia/core'

import { createAssistantApi } from './client'
import type { ChatEvent } from './types'

/** 把若干字符串块做成 SSE 假响应。 */
function streamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder()
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk))
        }
        controller.close()
      },
    }),
    { status: 200, headers: { 'content-type': 'text/event-stream' } },
  )
}

/** 只实现 sendMessage 用到的 `stream`，其余方法不会被这轮测试碰到。 */
function clientStreaming(body: Response): ApiClient {
  return {
    stream: async () => body,
    requestEnvelope: async () => {
      throw new Error('requestEnvelope 不应在流式路径被调用')
    },
  } as unknown as ApiClient
}

describe('sendMessage 终止判定', () => {
  it('收到 done 时不补错误事件，用量归一为界面形状', async () => {
    const events: ChatEvent[] = []
    const api = createAssistantApi(
      clientStreaming(
        streamResponse([
          'event:content\ndata:{"delta":"答案"}\n\n',
          'event:done\ndata:{"usage":{"input":1,"output":2,"total":3}}\n\n',
        ]),
      ),
    )

    await api.sendMessage(1, '问', (event) => events.push(event))

    expect(events.map((event) => event.type)).toEqual(['content', 'done'])
    expect(events[1]?.usage).toEqual({ inputTokens: 1, outputTokens: 2, totalTokens: 3 })
  })

  it('只有正文、没有 done/error 就断流时补一条"未完整"错误，而不是静默结束', async () => {
    const events: ChatEvent[] = []
    const api = createAssistantApi(
      clientStreaming(streamResponse(['event:content\ndata:{"delta":"半截"}\n\n'])),
    )

    await api.sendMessage(1, '问', (event) => events.push(event))

    expect(events.map((event) => event.type)).toEqual(['content', 'error'])
    expect(events[1]?.message).toContain('未能完整结束')
  })

  it('服务端已发 error 时不再叠加一条未完整提示', async () => {
    const events: ChatEvent[] = []
    const api = createAssistantApi(
      clientStreaming(streamResponse(['event:error\ndata:{"message":"上游失败"}\n\n'])),
    )

    await api.sendMessage(1, '问', (event) => events.push(event))

    expect(events.map((event) => event.type)).toEqual(['error'])
    expect(events[0]?.message).toBe('上游失败')
  })
})
