/**
 * SSE 解析。
 *
 * <p>单独成一个文件、并且导出纯函数 `parseSseBlock`：流式最难的从来不是"读字节"，
 * 而是**事件被切断在不同字节块里**（`data:{"del` / `ta":"你好"}`）。把切分逻辑抽出来，
 * 就能用一个假的流反复验证边界，不必真起一个 SSE 服务。
 *
 * <p>只实现本项目用到的最小面：`event:` 名 + 单行 `data:`。不做重连、不处理 `id:`/`retry:`
 * ——服务端不产生它们，写了也是没有测试覆盖的死代码。
 */

/** 服务端定义的四类事件。 */
export type AssistantEventType = 'thinking' | 'content' | 'draft' | 'done' | 'error'

/**
 * `done` 事件里用量的**线上形状**（`input`/`output`/`total`）。
 *
 * <p>与 REST 的 `TokenUsage`（`inputTokens`/`…`）刻意分开：这一层只负责如实解析字节，
 * 归一成界面用的类型是客户端（`client.ts`）的事。混在两个文件里各改一半，最容易出现
 * "某一条路径忘了换"。
 */
export interface TokenUsageWire {
  input: number
  output: number
  total: number
}

export interface AssistantEvent {
  type: AssistantEventType
  /** `thinking` / `content` 的增量。 */
  delta?: string
  /** `error` 的可见消息。 */
  message?: string
  /** `done` 的用量（线上形状）；上游没给时为 `null`。 */
  usage?: TokenUsageWire | null
  /** `done` 且正文为空（服务端用可见提示兜底）时为 true。 */
  empty?: boolean
  /** `done` 的这一轮轮次 id（WORK-036）；采集关闭时为 `null`。点赞/点踩靠它挂到具体一轮上。 */
  traceId?: string | null
  /** `draft` 事件：助手提出的一条**待确认**笔记草稿（它还没落库）。 */
  draft?: { title: string; body: string }
}

/**
 * 解析一个 SSE 事件块（已按空行切开）。
 *
 * @returns 认不出的块返回 `null`（例如心跳注释 `:keep-alive`），调用方直接跳过
 */
export function parseSseBlock(block: string): AssistantEvent | null {
  let name = ''
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) {
      continue
    }
    if (line.startsWith('event:')) {
      name = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      // SSE 规定：冒号后若有一个空格要去掉，其余（含前导空格）属于数据
      const value = line.slice('data:'.length)
      dataLines.push(value.startsWith(' ') ? value.slice(1) : value)
    }
  }
  if (name === '' || dataLines.length === 0) {
    return null
  }
  if (name !== 'thinking' && name !== 'content' && name !== 'draft' && name !== 'done' && name !== 'error') {
    return null
  }
  let payload: Record<string, unknown> = {}
  const raw = dataLines.join('\n')
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed === 'object' && parsed !== null) {
      payload = parsed as Record<string, unknown>
    }
  } catch {
    // 服务端恒发 JSON；解析不了说明连接被截断，这一块丢掉比抛出去打断整轮更好
    return null
  }
  if (name === 'draft') {
    // 草稿的负载形状与其它事件不同（title/body），单独归一，免得让它以 title/body 两个
    // 顶层字段混进事件类型里
    const title = typeof payload.title === 'string' ? payload.title : ''
    const body = typeof payload.body === 'string' ? payload.body : ''
    return { type: 'draft', draft: { title, body } }
  }
  return { type: name, ...payload } as AssistantEvent
}

/**
 * 读流的结果。
 *
 * <p>`terminated=true` 表示收到过 `done` 或 `error`——服务端明确宣告这一轮结束。
 * 连接在既没有 `done` 也没有 `error` 的情况下断流时为 `false`：调用方据此提示"回答未完整"，
 * 而不是把半截正文当成完整回答（WORK-092 TASK-007）。
 */
export interface StreamOutcome {
  terminated: boolean
}

/** 按块读取响应体并逐个回调事件。返回时表示流已结束（或读取被 abort）。 */
export async function readEventStream(
  response: Response,
  onEvent: (event: AssistantEvent) => void,
): Promise<StreamOutcome> {
  const body = response.body
  if (body === null) {
    return { terminated: false }
  }
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let terminated = false
  const emit = (event: AssistantEvent): void => {
    if (event.type === 'done' || event.type === 'error') {
      terminated = true
    }
    onEvent(event)
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    // 统一换行：SSE 规范允许 \r\n，只在 \n 上切会让每个块都带上尾随 \r
    buffer += decoder.decode(value, { stream: true }).replaceAll('\r\n', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = parseSseBlock(block)
      if (event !== null) {
        emit(event)
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
  // 收尾：某些实现最后一块不带空行结尾
  const tail = parseSseBlock(buffer)
  if (tail !== null) {
    emit(tail)
  }
  return { terminated }
}
