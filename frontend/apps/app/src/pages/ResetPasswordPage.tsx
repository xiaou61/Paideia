import { AuthLayout, ResetPasswordForm } from '@paideia/auth'
import { Alert, AlertDescription } from '@paideia/ui'
import { useLocation } from 'react-router'

/**
 * 重置密码页（第二步：令牌换新口令）。
 *
 * <p>**没有套 `RequireGuest`**：这一步的凭据是邮件里的一次性令牌，不是当前会话。
 * 如果套上，恰好"在另一台设备上还登着"的人点开链接就会被弹回首页，而他那条会话
 * 正是这次重置要作废的东西。
 *
 * <p>令牌从 hash 路由的查询串里取（`#/reset-password?token=...`），与邮件里
 * `paideia.account.web.base-url` 拼出来的地址一致。
 */
export function ResetPasswordPage() {
  const location = useLocation()
  const token = new URLSearchParams(location.search).get('token') ?? ''

  if (token === '') {
    return (
      <AuthLayout
        description="这个重置链接不完整。"
        footer={
          <a href="#/forgot-password" className="text-foreground underline underline-offset-4">
            重新申请一封
          </a>
        }
      >
        <Alert variant="destructive" data-testid="reset-missing-token">
          <AlertDescription>
            地址里没有重置令牌。可能是邮件客户端把链接截断了，请重新申请一封，或直接复制整条链接到浏览器。
          </AlertDescription>
        </Alert>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout
      wide
      description="设置一个新密码，设置后原密码立即失效。"
      footer={
        <a href="#/login" className="text-foreground underline underline-offset-4">
          返回登录
        </a>
      }
    >
      <ResetPasswordForm token={token} />
    </AuthLayout>
  )
}
