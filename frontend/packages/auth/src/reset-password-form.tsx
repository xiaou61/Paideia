import { Alert, AlertDescription, Button, Input, Label, Spinner } from '@paideia/ui'
import { useState, type FormEvent } from 'react'

import { messageOf, useAuth } from './auth-context'

/**
 * 忘记密码的第二步：拿邮件里的一次性令牌换新密码。
 *
 * <p>令牌已经由页面从地址里取出来传进来，这里只负责校验与提交。失败文案直接用后端的
 * ——"链接无效或已过期"这句话的原因（过期、用过、重新申请过）只有服务端知道，
 * 前端猜一遍只会猜错。
 *
 * <p>长度上限 72 与后端一致，是 BCrypt 的输入上限：BCrypt 只看前 72 字节，
 * 更长的口令后半段根本不参与校验，收下来等于骗用户。
 */
const PASSWORD_MIN = 8
const PASSWORD_MAX = 72

export function ResetPasswordForm({ token }: { token: string }) {
  const { api } = useAuth()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (password !== confirm) {
      setError('两次输入的密码不一致。')
      return
    }
    setBusy(true)
    try {
      await api.confirmPasswordReset(token, password)
      setDone(true)
    } catch (failure) {
      setError(messageOf(failure))
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <div className="space-y-4" data-testid="reset-done">
        <Alert>
          <AlertDescription>
            密码已重置，该账号在其他设备上的登录也已失效。
          </AlertDescription>
        </Alert>
        <a
          href="#/login"
          className="bg-primary text-primary-foreground hover:bg-primary/90 flex h-10 items-center justify-center rounded-md text-sm font-medium"
          data-testid="reset-go-login"
        >
          用新密码登录
        </a>
      </div>
    )
  }

  return (
    <form className="space-y-5" onSubmit={submit} data-testid="reset-password-form">
      <div className="space-y-2">
        <Label htmlFor="reset-password">新密码</Label>
        <Input
          id="reset-password"
          data-testid="reset-password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          minLength={PASSWORD_MIN}
          maxLength={PASSWORD_MAX}
          className="h-10"
          required
        />
        <p className="text-muted-foreground text-xs">
          {PASSWORD_MIN}–{PASSWORD_MAX} 个字符。
        </p>
      </div>

      <div className="space-y-2">
        <Label htmlFor="reset-confirm">再输一次</Label>
        <Input
          id="reset-confirm"
          data-testid="reset-confirm"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(event) => setConfirm(event.target.value)}
          minLength={PASSWORD_MIN}
          maxLength={PASSWORD_MAX}
          className="h-10"
          required
        />
      </div>

      {error === null ? null : (
        <Alert variant="destructive" data-testid="reset-error">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Button type="submit" disabled={busy} data-testid="reset-submit" className="h-10 w-full">
        {busy ? <Spinner /> : null}
        重置密码
      </Button>
    </form>
  )
}
