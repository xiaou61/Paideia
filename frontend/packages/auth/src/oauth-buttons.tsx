import { Button, Spinner } from '@paideia/ui'
import { useEffect, useState } from 'react'

import { messageOf, useAuth } from './auth-context'
import type { OAuthProviderView } from './auth-api'
import { canOpenOAuthAuthorization, desktopOAuthHost, openOAuthAuthorization } from './oauth-navigation'

/**
 * 第三方登录按钮组（WORK-037）。
 *
 * <p><b>只渲染后端说可用的平台</b>：没配 clientId/secret 的平台不在列表里，因此按钮不出现。
 * 渲染一个点了只会报错的按钮，比不渲染更糟——用户会以为是自己操作错了。
 *
 * <p>浏览器整页跳转，桌面交给系统浏览器：授权页不在桌面渲染进程运行。
 */
export function OAuthButtons({ mode = 'login' }: { mode?: 'login' | 'bind' }) {
  const { api } = useAuth()
  const [providers, setProviders] = useState<OAuthProviderView[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const available = canOpenOAuthAuthorization()

  useEffect(() => {
    if (!available) return
    let cancelled = false
    void api
      .oauthProviders()
      .then((result) => {
        if (!cancelled) {
          setProviders(result.providers)
        }
      })
      .catch(() => {
        // 取不到平台列表就不显示按钮：这是附加功能，不该在登录页上砸出一个错误条
        if (!cancelled) {
          setProviders([])
        }
      })
    return () => {
      cancelled = true
    }
  }, [api, available])

  if (!available || providers.length === 0) {
    return null
  }

  async function start(provider: string) {
    setBusy(provider)
    setError(null)
    try {
      const { authorizeUrl } = mode === 'bind'
        ? await api.oauthBindStart(provider)
        : await api.oauthStart(provider, desktopOAuthHost() !== undefined)
      await openOAuthAuthorization(authorizeUrl)
    } catch (failure) {
      setError(messageOf(failure))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="space-y-3" data-testid="oauth-buttons">
      <div className="flex items-center gap-3">
        <span className="bg-border h-px flex-1" />
        <span className="text-muted-foreground text-xs">或用以下方式</span>
        <span className="bg-border h-px flex-1" />
      </div>
      <div className="grid gap-2">
        {providers.map((provider) => (
          <Button
            key={provider.key}
            type="button"
            variant="outline"
            className="h-10 w-full"
            data-testid={`oauth-${provider.key}`}
            disabled={busy !== null}
            onClick={() => void start(provider.key)}
          >
            {busy === provider.key ? <Spinner /> : null}
            {mode === 'bind' ? `绑定 ${provider.label}` : `用 ${provider.label} 登录`}
          </Button>
        ))}
      </div>
      {error === null ? null : (
        <p className="text-destructive text-xs" data-testid="oauth-error">
          {error}
        </p>
      )}
    </div>
  )
}
