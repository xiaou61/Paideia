import { AuthLayout, LoginForm, redirectTarget, RequireGuest } from '@paideia/auth'
import { Alert, AlertDescription } from '@paideia/ui'
import { useLocation, useNavigate } from 'react-router'

/**
 * 登录页。
 *
 * <p>用 `AuthLayout` 而不是应用外壳：外壳的横条与页脚在未登录时读起来像设置页，
 * 而那些导航本来就不可用。登录后回到原本要去的地址（由登录门记在 `redirect` 参数里）。
 *
 * <p>第三方登录失败会带着 `oauthError` 回到这里（WORK-037）。**文案在这里定，不在后端定**：
 * 后端只给粗粒度码，避免把平台的原始错误转述给用户（那里可能提到账号状态或内部细节）。
 */
const OAUTH_ERRORS: Record<string, string> = {
  denied: '你取消了授权。',
  expired: '授权已超时，请重新点击登录。',
  disabled: '该登录方式尚未启用。',
  failed: '授权失败，请重试。',
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const oauthError = new URLSearchParams(location.search).get('oauthError')

  return (
    <RequireGuest>
      <AuthLayout
        description="登录以继续你的学习。"
        footer={
          <>
            <a
              href="#/forgot-password"
              className="text-foreground underline underline-offset-4"
              data-testid="login-forgot-password"
            >
              忘记密码？
            </a>
            <span className="mx-2">·</span>
            还没有账号？
            <a href="#/register" className="text-foreground ml-1 underline underline-offset-4">
              立即注册
            </a>
          </>
        }
      >
        {oauthError === null ? null : (
          <Alert variant="destructive" className="mb-4" data-testid="oauth-error-banner">
            <AlertDescription>{OAUTH_ERRORS[oauthError] ?? OAUTH_ERRORS.failed}</AlertDescription>
          </Alert>
        )}
        <LoginForm onSuccess={() => navigate(redirectTarget(location.search), { replace: true })} />
      </AuthLayout>
    </RequireGuest>
  )
}
