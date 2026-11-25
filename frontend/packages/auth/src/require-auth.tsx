import { Spinner } from '@paideia/ui'
import { Fragment, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'

import { useAuth } from './auth-context'

/**
 * 登录门：未登录时把用户导向登录页，并**记住他原本要去的地址**，登录后回到那里。
 *
 * <p>它只做界面分流，**不是授权边界**：真正的拦截必须在后端每个接口上。
 * 前端守卫的价值是"不让人白等一次失败请求"，不是安全。
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, sessionId } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return (
      <div className="text-muted-foreground flex min-h-dvh items-center justify-center gap-2 text-sm">
        <Spinner />
        正在确认登录状态…
      </div>
    )
  }

  if (status === 'signed-out') {
    const target = `${location.pathname}${location.search}`
    const redirect = target === '/' ? '' : `?redirect=${encodeURIComponent(target)}`
    return <Navigate to={`/login${redirect}`} replace />
  }

  return <Fragment key={sessionId}>{children}</Fragment>
}

/**
 * 只接受站内相对路径，其余一律当作没有。
 *
 * <p>三个条件缺一不可：必须 `/` 开头；不能是 `//` 或 `/\` 开头（那是协议相对地址，
 * 会被浏览器当成外站跳转，等于开放重定向）；不能含反斜杠或空白字符——浏览器解析 URL 时
 * 会把反斜杠归一化成 `/`、把制表符与换行直接剥掉，于是 `/\evil.com`、`/\t//evil.com`
 * 都能绕过只查前缀的写法。
 */
function safeRedirect(value: string | null): string | null {
  if (value === null || !value.startsWith('/') || value.startsWith('//') || /[\\\s]/.test(value)) {
    return null
  }
  return value
}

/** 已登录时的反向门：登录页在已登录状态下不应再展示。 */
export function RequireGuest({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return null
  }
  if (status === 'signed-in') {
    const params = new URLSearchParams(location.search)
    return <Navigate to={safeRedirect(params.get('redirect')) ?? '/'} replace />
  }
  return <>{children}</>
}

/** 从地址里取登录后要回的目标地址（默认首页）。 */
export function redirectTarget(search: string): string {
  return safeRedirect(new URLSearchParams(search).get('redirect')) ?? '/'
}
