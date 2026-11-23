import { Alert, AlertDescription, Button, Input, Label, Spinner } from '@paideia/ui'
import { useCallback, useEffect, useState, type FormEvent } from 'react'

import type { CaptchaChallenge } from './auth-api'
import { messageOf, useAuth } from './auth-context'
import { CaptchaImage } from './captcha-image'
import { useResendCooldown } from './use-resend-cooldown'

/** 发信后的重发冷却秒数，与后端的"同邮箱 60 秒间隔"对齐。 */
const RESEND_SECONDS = 60

/**
 * 忘记密码的第一步：用邮箱换取一封重置链接。
 *
 * <p>**发信成功不能说明邮箱存在**——后端对未注册邮箱同样返回成功，否则这个端点就成了
 * "批量探测某邮箱有没有账号"的工具。因此成功文案写"如果该邮箱已注册"，并在界面上
 * 不提"未找到账号"这类区分。
 *
 * <p>图形验证码是发信的硬前置（与注册、邮箱验证码一致）：没有它，任何人都能拿这个端点
 * 当免费的邮件轰炸器。
 */
export function ForgotPasswordForm() {
  const { api } = useAuth()
  const [email, setEmail] = useState('')
  const [captchaAnswer, setCaptchaAnswer] = useState('')
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

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    // 每次提交只保留本次结果：否则换邮箱失败时，上一次的成功提示会与本次错误同时出现
    setSent(false)
    try {
      await api.requestPasswordReset(email, captcha?.captchaId ?? '', captchaAnswer)
      setSent(true)
      cooldown.start()
      // 图形验证码已被消费，换一张备下次重发
      await refreshCaptcha()
    } catch (failure) {
      setError(messageOf(failure))
      await refreshCaptcha()
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="space-y-5" onSubmit={submit} data-testid="forgot-password-form">
      <div className="space-y-2">
        <Label htmlFor="forgot-email">注册邮箱</Label>
        <Input
          id="forgot-email"
          data-testid="forgot-email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          className="h-10"
          required
        />
        <p className="text-muted-foreground text-xs">必须填注册时用的邮箱，重置链接只会发到那里。</p>
      </div>

      <div className="space-y-2">
        <Label htmlFor="forgot-captcha">图形验证码</Label>
        <div className="flex items-center gap-2">
          <Input
            id="forgot-captcha"
            data-testid="forgot-captcha"
            value={captchaAnswer}
            onChange={(event) => setCaptchaAnswer(event.target.value)}
            required
            className="h-10 flex-1"
          />
          <CaptchaImage challenge={captcha} onRefresh={() => void refreshCaptcha()} disabled={busy} />
        </div>
      </div>

      {sent ? (
        <Alert data-testid="forgot-sent">
          <AlertDescription>
            如果该邮箱已注册，重置链接已经发出。请到邮箱里点开链接继续（30 分钟内有效），
            没收到就先看垃圾邮件，或稍后用新验证码重发。
          </AlertDescription>
        </Alert>
      ) : null}

      {error === null ? null : (
        <Alert variant="destructive" data-testid="forgot-error">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <Button
        type="submit"
        disabled={busy || cooldown.remaining > 0 || captcha === null}
        data-testid="forgot-submit"
        className="h-10 w-full"
      >
        {busy ? <Spinner /> : null}
        {cooldown.remaining > 0 ? `${cooldown.remaining} 秒后可重发` : '发送重置链接'}
      </Button>
    </form>
  )
}
