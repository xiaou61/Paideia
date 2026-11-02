export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId: string | null
}

/**
 * JSON 请求头（WORK-062 收敛）：全仓原有 4 个同名局部常量 + 约 30 处内联字面量。
 *
 * <p>名字是复数、因为**只能整体当 headers 用**：multipart 上传刻意不能手设 Content-Type
 * （写了会破坏 boundary），所以那三处不适用本常量。
 */
export const JSON_HEADERS: Record<string, string> = { 'Content-Type': 'application/json' }

/** 后端错误契约的客户端表示：保留错误码与追踪标识，便于把界面报错和日志对上。 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId: string | null

  constructor(code: number, message: string, traceId: string | null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

export interface ApiClientOptions {
  /** 为空表示同源（开发期由 Vite 代理转发）。 */
  baseUrl?: string
  getToken?: () => string | null
  fetchImpl?: typeof fetch
}

export interface ApiClient {
  /** 取原始响应体，用于非统一结构的端点（例如 Actuator 健康检查）。 */
  request<T>(path: string, init?: RequestInit): Promise<T>
  /** 取统一结构的 data 字段；code 非 0 或 HTTP 失败时抛 ApiError。 */
  requestEnvelope<T>(path: string, init?: RequestInit): Promise<T>
  /**
   * 流式请求（SSE）：成功时返回**未读完**的 Response，由调用方读 `body` 的 reader。
   * 非 2xx 时读完错误体并抛 {@link ApiError}。
   */
  stream(path: string, init?: RequestInit): Promise<Response>
}

const SUCCESS_CODE = 0

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return typeof value === 'object' && value !== null && 'code' in value && 'message' in value
}

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const baseUrl = options.baseUrl ?? ''
  const doFetch = options.fetchImpl ?? globalThis.fetch

  async function send(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    const token = options.getToken?.() ?? null
    if (token !== null && token !== '') {
      headers.set('Authorization', `Bearer ${token}`)
    }
    return doFetch(`${baseUrl}${path}`, { ...init, headers })
  }

  function parse(text: string): unknown {
    if (text === '') {
      return null
    }
    try {
      return JSON.parse(text) as unknown
    } catch {
      return undefined
    }
  }

  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await send(path, init)
    const body = parse(await response.text())
    if (!response.ok) {
      if (isEnvelope(body)) {
        throw new ApiError(body.code, body.message, body.traceId)
      }
      throw new ApiError(response.status, `请求失败：HTTP ${response.status}`, null)
    }
    if (body === undefined) {
      throw new ApiError(response.status, '响应不是合法 JSON', null)
    }
    return body as T
  }

  async function requestEnvelope<T>(path: string, init?: RequestInit): Promise<T> {
    const envelope = await request<unknown>(path, init)
    if (!isEnvelope(envelope)) {
      throw new ApiError(-1, '响应不符合统一结构', null)
    }
    if (envelope.code !== SUCCESS_CODE) {
      throw new ApiError(envelope.code, envelope.message, envelope.traceId)
    }
    return envelope.data as T
  }

  /**
   * 发一个**流式**请求，把未读完的 `Response` 交回调用方（SSE 用）。
   *
   * <p>为什么不复用 {@link request}：那个会把响应体 `text()` 读完再解析 JSON。流式响应的全部价值
   * 就在于"边到边读"，读完再返回等于把流式退化成一次性等待。
   *
   * <p>两条与普通请求不同的处理：
   * <ul>
   *   <li>`Accept` 声明为 `text/event-stream, application/json`。**不能只写前者**：服务端在开流
   *       <b>之前</b>做的校验（未登录、会话不存在、没配凭据）要用普通 JSON 错误响应返回，
   *       只接受 SSE 会让那些错误渲染不出来，退化成 500。带上 `application/json` 后，
   *       成功仍是 SSE（该端点只 produces 这一种），失败则是可读的 JSON。</li>
   *   <li>非 2xx 时把错误体读完并按统一结构抛 {@link ApiError}——错误是**有限**的，
   *       必须在这层转成异常，否则调用方要先判断状态码再决定要不要当流读。</li>
   * </ul>
   *
   * <p>鉴权与 401 刷新重放照旧：本方法走同一个 `fetchImpl`，`createAuthorizedFetch` 对它一样生效。
   */
  async function stream(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers)
    // 两个都声明：成功走 SSE，开流前的校验失败走 JSON（只写前者会让那些错误变成 500）
    headers.set('Accept', 'text/event-stream, application/json')
    const token = options.getToken?.() ?? null
    if (token !== null && token !== '') {
      headers.set('Authorization', `Bearer ${token}`)
    }
    const response = await doFetch(`${baseUrl}${path}`, { ...init, headers })
    if (!response.ok) {
      const body = parse(await response.text())
      if (isEnvelope(body)) {
        throw new ApiError(body.code, body.message, body.traceId)
      }
      throw new ApiError(response.status, `请求失败：HTTP ${response.status}`, null)
    }
    return response
  }

  return { request, requestEnvelope, stream }
}
