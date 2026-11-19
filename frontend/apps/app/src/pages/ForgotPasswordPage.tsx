import { AuthLayout, ForgotPasswordForm, RequireGuest } from '@paideia/auth'

/**
 * 忘记密码页（第一步：发重置链接）。
 *
 * <p>与登录页共用 `AuthLayout`，并且同样套 `RequireGuest`：已登录的人不需要这条路，
 * 让他停在一个"发邮件给某个邮箱"的表单前只会造成混淆。
 */
export function ForgotPasswordPage() {
  return (
    <RequireGuest>
      <AuthLayout
        description="用注册邮箱收取一封重置链接。"
        footer={
          <>
            想起密码了？
            <a href="#/login" className="text-foreground ml-1 underline underline-offset-4">
              去登录
            </a>
          </>
        }
      >
        <ForgotPasswordForm />
      </AuthLayout>
    </RequireGuest>
  )
}
