import { ApiError, errorMessage } from '@paideia/core'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'

import { createAuthApi, type AuthApi, type AuthTokens, type AuthUser } from './auth-api'
import type { TokenStore } from './token-store'

/**
 * 认证上下文：会话状态、登录/注册/登出、以及 401 刷新所用的 `refresh` 回调。
 *
 * <p>业务页面只从这里拿会话与动作，**不得直接读写令牌**——令牌读写只在本包内。
 */

export type AuthStatus = 'loading' | 'signed-in' | 'signed-out'

export interface AuthContextValue {
  status: AuthStatus
  user: AuthUser | null
  login(identifier: string, password: string): Promise<void>
  register(input: {
    username: string
    email: string
    password: string
    code: string
  }): Promise<void>
  /** 用第三方回调页给的一次性握手码完成登录（WORK-037）。 */
  completeOAuth(handoff: string): Promise<void>
  logout(): Promise<void>
  /** 供 `createAuthorizedFetch` 使用：用 refresh 换新令牌，成功返回 true。 */
  refreshSession(): Promise<boolean>
  /** 供验证码等表单直接调后端用。 */
  api: AuthApi
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({
  tokens,
  api,
  children,
  onNeedsLogin,
}: {
  tokens: TokenStore
  /** 与 fetch 层共用同一个客户端，避免两套解析。 */
  api: AuthApi
  children: ReactNode
  /** 会话彻底失效（refresh 也无效）时通知应用，用于跳回登录页。 */
  onNeedsLogin?: () => void
}) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [user, setUser] = useState<AuthUser | null>(null)
  // 首次挂载时尝试用已保存的 refresh 恢复会话
  const started = useRef(false)
  // 进行中的刷新：挂载恢复与 fetch 层的 401 回调必须共用同一次（见 refreshSession）
  const inFlightRefresh = useRef<Promise<boolean> | null>(null)

  const apply = useCallback(
    (issued: AuthTokens) => {
      tokens.set(issued)
      setUser(issued.user)
      setStatus('signed-in')
    },
    [tokens],
  )

  const clear = useCallback(() => {
    tokens.clear()
    setUser(null)
    setStatus('signed-out')
  }, [tokens])

  const refreshSession = useCallback(async () => {
    // 单飞：刚进页面时挂载恢复与首个 401 回调会各自调一次，而 refresh 是一次性的——
    // 两次并发必然一胜一败，败的那次会被后端判为"令牌重用"并吊销整条链，
    // 表现就是"刷新页面后随机被踢下线"。让它们共用同一个 Promise 即可。
    if (inFlightRefresh.current !== null) {
      return inFlightRefresh.current
    }
    const run = (async () => {
      const refreshToken = tokens.refresh()
      if (refreshToken === null) {
        return false
      }
      try {
        apply(await api.refresh(refreshToken))
        return true
      } catch (error) {
        // 只有服务端明确拒绝（ApiError）才算会话失效。网络抖动、超时抛的是别的错误，
        // 此时 refresh 仍然有效，清令牌等于因为断网把用户登出。
        if (error instanceof ApiError) {
          clear()
        }
        return false
      }
    })()
    inFlightRefresh.current = run
    try {
      return await run
    } finally {
      inFlightRefresh.current = null
    }
  }, [api, apply, clear, tokens])

  useEffect(() => {
    if (started.current) {
      return
    }
    started.current = true
    if (tokens.refresh() === null) {
      setStatus('signed-out')
      return
    }
    void refreshSession().then((ok) => {
      // 失败也必须收敛 status：留在 'loading' 会让 RequireAuth 永远显示"正在确认登录状态…"、
      // RequireGuest 返回 null（整页空白），onNeedsLogin 也永不触发——用户既进不去应用，
      // 也回不到登录页。网络类失败时令牌仍然保留（见 refreshSession），重新登录即可。
      if (!ok) {
        setStatus((current) => (current === 'loading' ? 'signed-out' : current))
      }
    })
  }, [refreshSession, tokens])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      api,
      refreshSession,
      login: async (identifier, password) => {
        apply(await api.login(identifier, password))
      },
      register: async (input) => {
        apply(await api.register(input))
      },
      completeOAuth: async (handoff) => {
        apply(await api.oauthExchange(handoff))
      },
      logout: async () => {
        const refreshToken = tokens.refresh()
        clear()
        if (refreshToken !== null) {
          try {
            await api.logout(refreshToken)
          } catch {
            // 登出失败不该把用户卡在已登录状态：本地已经清了，服务端那条 refresh 到期自然失效
          }
        }
      },
    }),
    [api, apply, clear, refreshSession, status, tokens, user],
  )

  // fetch 层刷新失败时回调这里，界面随之回到登录页
  useEffect(() => {
    if (status === 'signed-out' && onNeedsLogin !== undefined) {
      onNeedsLogin()
    }
  }, [onNeedsLogin, status])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (value === null) {
    throw new Error('缺少 AuthProvider：会话必须通过 useAuth() 获取')
  }
  return value
}

/** 把任意错误转成可展示的文案。后端给的是给用户看的中文，直接用。 */
export function messageOf(error: unknown): string {
  // 归一到 @paideia/core 的 errorMessage（WORK-062）：ApiError 是 Error 子类，
  // 原来的 ApiError 分支与"是 Error 就用 message"本就等价
  return errorMessage(error, '请求失败，请稍后重试')
}
