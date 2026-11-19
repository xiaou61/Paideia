/**
 * 共享认证包。
 *
 * 令牌读写只在本包内；应用侧只从这里拿会话与动作（`useAuth` / `RequireAuth`）。
 */
export {
  createAuthApi,
  type AuthApi,
  type AuthRole,
  type AuthTokens,
  type AuthUser,
  type CaptchaChallenge,
  type OAuthBinding,
  type OAuthIdentities,
  type OAuthProviderView,
} from './auth-api'
export {
  AuthProvider,
  messageOf,
  useAuth,
  type AuthContextValue,
  type AuthStatus,
} from './auth-context'
export { createAuthorizedFetch, type AuthorizedFetchOptions } from './authorized-fetch'
export { AuthLayout } from './auth-layout'
export { CaptchaImage } from './captcha-image'
export { ForgotPasswordForm } from './forgot-password-form'
export { LoginForm } from './login-form'
export { OAuthButtons } from './oauth-buttons'
export { RegisterForm } from './register-form'
export { ResetPasswordForm } from './reset-password-form'
export { redirectTarget, RequireAuth, RequireGuest } from './require-auth'
export { createTokenStore, REFRESH_TOKEN_KEY, type IssuedTokens, type TokenStorage, type TokenStore } from './token-store'
