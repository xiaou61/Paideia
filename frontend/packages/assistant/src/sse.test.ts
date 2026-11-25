import { describe, expect, it } from 'vitest'

import { parseSseBlock, readEventStream, type AssistantEvent } from './sse'

describe('parseSseBlock', () => {
  it('取出事件名与 JSON 负载', () => {
    expect(parseSseBlock('event:content\ndata:{"delta":"你好"}')).toEqual({
      type: 'content',
      delta: '你好',
    })
  })

  it('done 事件带用量与 empty 标志', () => {
    expect(parseSseBlock('event:done\ndata:{"usage":{"input":1,"output":2,"total":3},"empty":false}')).toEqual({
      type: 'done',
      usage: { input: 1, output: 2, total: 3 },
      empty: false,
    })
  })

  it('draft 事件归一成 {title, body}，不把两个字段摊在事件顶层', () => {
    expect(parseSseBlock('event:draft\ndata:{"title":"草稿","body":"正文\\n第二行"}')).toEqual({
      type: 'draft',
      draft: { title: '草稿', body: '正文\n第二行' },
    })
  })

  it('draft 事件缺字段时不崩，缺的补成空串', () => {
    expect(parseSseBlock('event:draft\ndata:{"body":"只有正文"}')).toEqual({
      type: 'draft',
      draft: { title: '', body: '只有正文' },
    })
  })

  it('注释心跳与认不出的事件名都返回 null', () => {
    expect(parseSseBlock(':keep-alive')).toBeNull()
    expect(parseSseBlock('event:ping\ndata:{}')).toBeNull()
    expect(parseSseBlock('event:content')).toBeNull()
  })

  it('负载不是合法 JSON 时丢掉这一块，而不是抛异常打断整轮', () => {
    expect(parseSseBlock('event:content\ndata:{"del')).toBeNull()
  })
})

/** 把若干字符串块做成"一个字节块一个 chunk"的假响应，用来复现事件被切断的情形。 */
function responseFromChunks(chunks: string[]): Response {
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

describe('readEventStream', () => {
  it('按空行切分事件，并跨字节块还原被切断的负载', async () => {
    const events: AssistantEvent[] = []
    // 第二个事件被切成两块：JSON 从中间断开
    const response = responseFromChunks([
      'event:thinking\ndata:{"delta":"先想"}\n\n',
      'event:content\ndata:{"del',
      'ta":"答案"}\n\n',
      'event:done\ndata:{"usage":null,"empty":false}\n\n',
    ])

    const outcome = await readEventStream(response, (event) => events.push(event))

    expect(events).toEqual([
      { type: 'thinking', delta: '先想' },
      { type: 'content', delta: '答案' },
      { type: 'done', usage: null, empty: false },
    ])
    expect(outcome.terminated).toBe(true)
  })

  it('兼容 \\r\\n 换行与最后一块没有空行结尾', async () => {
    const events: AssistantEvent[] = []
    const response = responseFromChunks(['event:content\r\ndata:{"delta":"尾"}\r\n\r\n', 'event:done\ndata:{"empty":true}'])

    const outcome = await readEventStream(response, (event) => events.push(event))

    expect(events).toEqual([{ type: 'content', delta: '尾' }, { type: 'done', empty: true }])
    expect(outcome.terminated).toBe(true)
  })

  it('没有响应体时安静结束', async () => {
    const events: AssistantEvent[] = []
    const outcome = await readEventStream(new Response(null, { status: 200 }), (event) => events.push(event))
    expect(events).toEqual([])
    expect(outcome.terminated).toBe(false)
  })

  it('只有正文、没有 done/error 的截断流标记为未终止', async () => {
    const events: AssistantEvent[] = []
    const response = responseFromChunks(['event:content\ndata:{"delta":"半截回答"}\n\n'])

    const outcome = await readEventStream(response, (event) => events.push(event))

    expect(events).toEqual([{ type: 'content', delta: '半截回答' }])
    expect(outcome.terminated).toBe(false)
  })

  it('error 事件也算已终止，不会在客户端再补一条未完整提示', async () => {
    const events: AssistantEvent[] = []
    const response = responseFromChunks(['event:error\ndata:{"message":"上游失败"}\n\n'])

    const outcome = await readEventStream(response, (event) => events.push(event))

    expect(events).toEqual([{ type: 'error', message: '上游失败' }])
    expect(outcome.terminated).toBe(true)
  })
})
