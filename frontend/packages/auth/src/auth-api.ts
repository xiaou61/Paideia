import type { ApiClient } from '@paideia/core'
import { JSON_HEADERS } from '@paideia/core'

/**
 * 认证端点的调用。路径与后端 `AuthController` 一一对应。
 *
 * <p>用既有的 `ApiClient`（`requestEnvelope`）而不是裸 fetch：统一响应结构、错误码与
 * 追踪标识的解析都在那里，认证接口没有理由另搞一套。
 */

export type AuthRole = 'admin' | 'learner'

export interface AuthUser {
  id: string
  username: string
  /**
   * 可能为空：第三方注册的用户可能没有邮箱（平台不给，或用户没公开）。
   * 展示时必须处理空值，而不是当成必填字段直接用。
   */
  email: string | null
  role: AuthRole
}

export interface AuthTokens {
  accessToken: string
  accessExpiresAt: string
  refreshToken: string
  refreshExpiresAt: string
  user: AuthUser
}

export interface CaptchaChallenge {
  captchaId: string
  imageBase64: string
  expiresAt: string
}


function post(body: unknown): RequestInit {
  return { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }
}

/** 可用的第三方登录平台。未配置的平台后端不会返回，因此按钮自然不渲染。 */
export interface OAuthProviderView {
  key: string
  label: string
}

/** 账号当前绑定的登录方式（WORK-037）。providerUserId 不下发——界面上没有用处。 */
export interface OAuthBinding {
  provider: string
  label: string
  displayName: string | null
  boundAt: string
}

export interface OAuthIdentities {
  bound: OAuthBinding[]
  hasPassword: boolean
  available: OAuthProviderView[]
}

export interface AuthApi {
  captcha(): Promise<CaptchaChallenge>
  /** 可用的第三方平台；没有任何平台配置时返回空数组。 */
  oauthProviders(): Promise<{ providers: OAuthProviderView[] }>
  /**
   * 拿平台的授权页地址，调用方负责跳转（不是 fetch——那是要用户操作的页面）。
   *
   * @param desktop 桌面壳里发起时传 true：成功后回到应用，而不是留在浏览器
   */
  oauthStart(provider: string, desktop?: boolean): Promise<{ authorizeUrl: string }>
  /** 用回调页给的一次性握手码换令牌。 */
  oauthExchange(handoff: string): Promise<AuthTokens>
  oauthIdentities(): Promise<OAuthIdentities>
  oauthBindStart(provider: string): Promise<{ authorizeUrl: string }>
  oauthUnbind(provider: string): Promise<void>
  sendEmailCode(email: string, captchaId: string, captchaAnswer: string): Promise<void>
  register(input: {
    username: string
    email: string
    password: string
    code: string
  }): Promise<AuthTokens>
  login(identifier: string, password: string): Promise<AuthTokens>
  refresh(refreshToken: string): Promise<AuthTokens>
  logout(refreshToken: string): Promise<void>
}

export function createAuthApi(client: ApiClient): AuthApi {
  return {
    captcha: () => client.requestEnvelope<CaptchaChallenge>('/api/v1/auth/captcha', { method: 'POST' }),

    oauthProviders: () =>
      client.requestEnvelope<{ providers: OAuthProviderView[] }>('/api/v1/auth/oauth/providers'),

    oauthStart: (provider, desktop) =>
      client.requestEnvelope<{ authorizeUrl: string }>(
        `/api/v1/auth/oauth/${provider}/start${desktop === true ? '?desktop=true' : ''}`,
        { method: 'POST' },
      ),

    oauthExchange: (handoff) =>
      client.requestEnvelope<AuthTokens>('/api/v1/auth/oauth/exchange', post({ handoff })),

    oauthIdentities: () =>
      client.requestEnvelope<OAuthIdentities>('/api/v1/account/oauth/identities'),

    oauthBindStart: (provider) =>
      client.requestEnvelope<{ authorizeUrl: string }>(
        `/api/v1/account/oauth/${provider}/start`,
        { method: 'POST' },
      ),

    oauthUnbind: (provider) =>
      client.requestEnvelope<void>(`/api/v1/account/oauth/${provider}`, { method: 'DELETE' }),

    sendEmailCode: (email, captchaId, captchaAnswer) =>
      client.requestEnvelope<void>(
        '/api/v1/auth/email-code',
        post({ email, captchaId, captchaAnswer }),
      ),

    register: (input) => client.requestEnvelope<AuthTokens>('/api/v1/auth/register', post(input)),

    login: (identifier, password) =>
      client.requestEnvelope<AuthTokens>('/api/v1/auth/login', post({ identifier, password })),

    refresh: (refreshToken) =>
      client.requestEnvelope<AuthTokens>('/api/v1/auth/refresh', post({ refreshToken })),

    logout: (refreshToken) =>
      client.requestEnvelope<void>('/api/v1/auth/logout', post({ refreshToken })),
  }
}
