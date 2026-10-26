import { AuthLayout, messageOf, useAuth } from '@paideia/auth'
import { Alert, AlertDescription, Spinner } from '@paideia/ui'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'

/**
 * 第三方登录回调页（WORK-037）。
 *
 * <p>后端把令牌与一个 60 秒有效的一次性握手码关联起来，只把握手码放进这个地址。
 * 因此这里的任务只有一件：**用握手码换令牌，然后进入已登录状态**。
 *
 * <p><b>为什么不在后端直接发令牌到地址里</b>：refresh 有 14 天寿命，落进浏览器历史、
 * 截图或 Referer 就泄露了。握手码换一次就作废，且地址里不含任何长效凭据。
 *
 * <p>一次性是服务端保证的，这里不做去重：React 的 StrictMode 在开发期会挂载两次，
 * 而第二次必然失败（码已被消费）。所以用 ref 挡住重复提交——否则开发环境每次都会
 * 看到一个假的"登录链接已失效"。
 */
export function OAuthCallbackPage() {
  const { completeOAuth } = useAuth()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const started = useRef(false)

  useEffect(() => {
    if (started.current) {
      return
    }
    started.current = true

    const handoff = params.get('handoff')
    if (handoff === null || handoff === '') {
      setError('登录链接不完整，请重新登录。')
      return
    }

    // 桌面壳发起的登录（WORK-037）：把握手码交给自定义协议，由应用自己完成剩下的交换。
    // 这里**不消费**它——消费了应用就拿不到。所以只跳转、不调 completeOAuth。
    // 浏览器已在本页，说明用户是从应用外部的浏览器回来的；跳转会唤起应用。
    if (params.get('desktop') === '1') {
      // 先给一条提示再跳：协议未注册时浏览器是**静默失败**的，
      // 不提示的话页面会永远停在"正在完成登录"，用户既不知道为什么也退不出去。
      setError('已尝试唤起桌面客户端。若没有自动打开，请回到应用里重新登录。')
      window.location.assign(`paideia://oauth?handoff=${encodeURIComponent(handoff)}`)
      return
    }

    void completeOAuth(handoff)
      .then(() => {
        navigate('/', { replace: true })
      })
      .catch((failure) => {
        setError(messageOf(failure))
      })
  }, [completeOAuth, navigate, params])

  return (
    <AuthLayout description="正在完成登录…">
      <div className="space-y-4" data-testid="oauth-callback">
        {error === null ? (
          <div className="text-muted-foreground flex items-center gap-2 text-sm">
            <Spinner />
            正在完成登录，请稍候…
          </div>
        ) : (
          <>
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
            <a
              href="#/login"
              className="text-foreground text-sm underline underline-offset-4"
              data-testid="oauth-callback-back"
            >
              返回登录页
            </a>
          </>
        )}
      </div>
    </AuthLayout>
  )
}
