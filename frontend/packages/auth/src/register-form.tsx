import { Alert, AlertDescription, Button, Input, Label, Spinner } from '@paideia/ui'
import { useCallback, useEffect, useState, type FormEvent } from 'react'

import type { CaptchaChallenge } from './auth-api'
import { messageOf, useAuth } from './auth-context'
import { CaptchaImage } from './captcha-image'
import { useResendCooldown } from './use-resend-cooldown'

/** 发信后的重发冷却秒数，与后端的"同邮箱 60 秒间隔"对齐。 */
const RESEND_SECONDS = 60

/**
 * 注册表单。两步式：先过图形验证码拿到邮箱验证码，再用验证码完成注册。
 *
 * <p>顺序与后端一致——图形验证码是"发信"的前置。前端把邮箱验证码输入框放在发信之后才出现，
 * 就是为了让这个顺序在界面上也成立，而不是让用户填一个还不知道该填什么的框。
 */
export function RegisterForm({ onSuccess }: { onSuccess?: () => void }) {
  const { api, register } = useAuth()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [captchaAnswer, setCaptchaAnswer] = useState('')
  const [code, setCode] = useState('')
  const [captcha, setCaptcha] = useState<CaptchaChallenge | null>(null)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const cooldown = useResendCooldown(RESEND_SECONDS)

  const refreshCaptcha = useCallback(async () => {
    try {
      setCaptcha(await api.captcha())
      setCaptchaAnswer('')
    } catch (failure) {
      setError(messageOf(failure))
    }
  }, [api])

  useEffect(() => {
    void refreshCaptcha()
  }, [refreshCaptcha])

  async function sendCode() {
    setBusy(true)
    setError(null)
    try {
      await api.sendEmailCode(email, captcha?.captchaId ?? '', captchaAnswer)
      setSent(true)
      cooldown.start()
      // 验证码已用过一次，图形验证码作废，换一张备下次重发
      await refreshCaptcha()
    } catch (failure) {
      setError(messageOf(failure))
      await refreshCaptcha()
    } finally {
      setBusy(false)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await register({ username, email, password, code })
      onSuccess?.()
    } catch (failure) {
      setError(messageOf(failure))
      await refreshCaptcha()
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="space-y-5" onSubmit={submit} data-testid="register-form">
      <div className="space-y-2">
        <Label htmlFor="register-username">用户名</Label>
        <Input
          id="register-username"
          data-testid="register-username"
          autoComplete="username"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          className="h-10"
          required
        />
        <p className="text-muted-foreground text-xs">3–64 个字符，可用中文、字母、数字、下划线或连字符。</p>
      </div>

      <div className="space-y-2">
        <Label htmlFor="register-email">邮箱</Label>
        <Input
          id="register-email"
          data-testid="register-email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          className="h-10"
          required
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="register-password">密码</Label>
        <Input
          id="register-password"
          data-testid="register-password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          className="h-10"
          required
        />
        <p className="text-muted-foreground text-xs">至少 8 位。</p>
      </div>

      <div className="space-y-2">
        <Label htmlFor="register-captcha">图形验证码</Label>
        <div className="flex items-center gap-2">
          <Input
            id="register-captcha"
            data-testid="register-captcha"
            value={captchaAnswer}
            onChange={(event) => setCaptchaAnswer(event.target.value)}
            required
            className="h-10 flex-1"
          />
          <CaptchaImage challenge={captcha} onRefresh={() => void refreshCaptcha()} disabled={busy} />
        </div>
      </div>

      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          onClick={() => void sendCode()}
        disabled={busy || cooldown.remaining > 0 || captcha === null}
          data-testid="register-send-code"
          className="h-10"
        >
        {cooldown.remaining > 0 ? `${cooldown.remaining} 秒后可重发` : '发送邮箱验证码'}
        </Button>
      </div>

      {sent ? (
        <div className="space-y-2">
          <Label htmlFor="register-code">邮箱验证码</Label>
          <Input
            id="register-code"
            data-testid="register-code"
            inputMode="numeric"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            className="h-10"
            required
          />
          <p className="text-muted-foreground text-xs">10 分钟内有效，最多尝试 5 次。</p>
        </div>
      ) : null}

      {error === null ? null : (
        <Alert variant="destructive" data-testid="register-error">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Button type="submit" disabled={busy || !sent} data-testid="register-submit" className="h-10 w-full">
        {busy ? <Spinner /> : null}
        注册
      </Button>
    </form>
  )
}
