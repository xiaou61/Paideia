/**
 * 令牌存储。
 *
 * <p><b>access 只放内存、refresh 落盘</b>：access 放内存让 XSS 无法跨刷新拿到它；
 * refresh 必须跨刷新与跨重启存活，所以走注入的存储——形状只有 `get`/`set`/`remove`，
 * 与 `packages/core` 的 `KeyValueCache` 结构化匹配，因此应用可以直接把 `platform.cache` 传进来，
 * 本包不必依赖平台实现。
 *
 * <p><b>天花板要说清</b>：refresh 落在 localStorage（Web）或主进程缓存（桌面）里，
 * 两者对 XSS 都是可读的。轮换买到的是"单个 access 寿命很短"与"盗用后可经重用检测发现"，
 * **买不到"XSS 拿不到令牌"**。
 */

export interface TokenStorage {
  get(key: string): string | null
  set(key: string, value: string): void
  remove(key: string): void
}

/** 端口层的键名，与 platform-web 的 `paideia:` 前缀一起构成 localStorage 的完整键。 */
export const REFRESH_TOKEN_KEY = 'refresh-token'

export interface IssuedTokens {
  accessToken: string
  refreshToken: string
}

export interface TokenStore {
  access(): string | null
  refresh(): string | null
  sessionId(): number
  sessionSignal(): AbortSignal
  /** 仅续期，不切换登录会话。 */
  set(tokens: IssuedTokens): void
  startSession(tokens: IssuedTokens): void
  clear(): void
}

export function createTokenStore(storage: TokenStorage): TokenStore {
  // 模块闭包里的内存变量：刷新页面即消失，这是刻意的最小暴露面
  let accessToken: string | null = null
  let refreshToken: string | null = storage.get(REFRESH_TOKEN_KEY)
  let sessionId = 0
  let session = new AbortController()

  function endPreviousSession() {
    const previous = session
    session = new AbortController()
    sessionId += 1
    previous.abort()
  }

  function set(tokens: IssuedTokens) {
    storage.set(REFRESH_TOKEN_KEY, tokens.refreshToken)
    accessToken = tokens.accessToken
    refreshToken = tokens.refreshToken
  }

  return {
    access: () => accessToken,
    refresh: () => refreshToken,
    sessionId: () => sessionId,
    sessionSignal: () => session.signal,
    set,
    startSession(tokens) {
      set(tokens)
      endPreviousSession()
    },
    clear() {
      // 先删存储再清内存：反过来的话，remove 抛异常（隐私模式、缓存接口异常）时
      // 内存已空、磁盘上却留着可用的 refresh，而调用方已经认为登出完成、不会重试。
      // 存储清理失败也要把内存清掉，否则界面会停在"已登录"；服务端那条 refresh
      // 已由 logout() 吊销，残留的串本身也用不了。
      try {
        storage.remove(REFRESH_TOKEN_KEY)
      } catch (error) {
        console.warn('清除本地刷新令牌失败：', error)
      } finally {
        accessToken = null
        refreshToken = null
        endPreviousSession()
      }
    },
  }
}
