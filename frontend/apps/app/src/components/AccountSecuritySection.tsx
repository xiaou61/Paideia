import {
  canOpenOAuthAuthorization,
  createAuthApi,
  desktopOAuthHost,
  messageOf,
  openOAuthAuthorization,
  type OAuthIdentities,
} from '@paideia/auth'
import { Alert, AlertDescription, Button, Spinner } from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link2, Unlink } from 'lucide-react'
import { useEffect, useState } from 'react'

import { api as authorizedApi } from '../api'

/**
 * 用**页面级带授权的客户端**，不用 `useAuth().api`。
 *
 * <p>后者是 `AuthProvider` 收到的那个实例，但本页其它数据（画像）一直走 `../api`——
 * 那是挂了 401 刷新与令牌注入的客户端。混用两套的结果是这一块稳定 401，
 * 而界面只显示一句"未认证"，看起来像后端没实现。
 */
const oauthApi = createAuthApi(authorizedApi)

/**
 * 账号与安全（WORK-037）。
 *
 * <p><b>它回答的问题只有一个</b>：这个账号能用哪几种方式进来，以及每一种能不能解掉。
 * 登录来源不影响任何权限，所以这一块里不出现角色、不出现"权限"字样。
 *
 * <p><b>解绑的按钮会禁用，但服务端也拒绝</b>：界面变灰只是提示，真正的闸在服务端——
 * 否则绕过界面直接调接口就能把账号解成再也登不进。
 */
export function AccountSecuritySection() {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const identities = useQuery<OAuthIdentities>({
    queryKey: ['oauth-identities'],
    queryFn: () => oauthApi.oauthIdentities(),
  })

  // Web 会整页重载；桌面授权留在系统浏览器，返回本窗口时重新查绑定状态。
  useEffect(() => {
    const desktop = desktopOAuthHost()
    if (desktop && !desktop.developmentRenderer) {
      const refresh = () => void queryClient.invalidateQueries({ queryKey: ['oauth-identities'] })
      window.addEventListener('focus', refresh)
      return () => window.removeEventListener('focus', refresh)
    }
    const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
    if (params.get('oauthBound') === '1') {
      void queryClient.invalidateQueries({ queryKey: ['oauth-identities'] })
    }
  }, [queryClient])

  const unbind = useMutation({
    mutationFn: (provider: string) => oauthApi.oauthUnbind(provider),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['oauth-identities'] })
    },
    onError: (failure) => {
      setError(messageOf(failure))
    },
    onSettled: () => {
      setBusy(null)
    },
  })

  if (identities.isPending) {
    return (
      <div className="text-muted-foreground flex items-center gap-2 text-sm">
        <Spinner />
        正在读取登录方式…
      </div>
    )
  }

  if (identities.isError) {
    // 这块读不出来不该让整页不能用：身份与目标仍然可以正常编辑
    return (
      <Alert variant="destructive" data-testid="security-load-error">
        <AlertDescription data-testid="security-load-error-msg">{messageOf(identities.error)}</AlertDescription>
      </Alert>
    )
  }

  const { bound, hasPassword, available } = identities.data
  // 剩下的方式 = 其它绑定 + 有没有密码。等于 1 时解掉最后一个就再也进不来了。
  const canUnbindAnything = bound.length - 1 + (hasPassword ? 1 : 0) > 0
  const unboundProviders = (canOpenOAuthAuthorization() ? available : []).filter(
    (provider) => !bound.some((item) => item.provider === provider.key),
  )

  async function startBind(provider: string) {
    setBusy(provider)
    setError(null)
    try {
      const { authorizeUrl } = await oauthApi.oauthBindStart(provider)
      await openOAuthAuthorization(authorizeUrl)
    } catch (failure) {
      setError(messageOf(failure))
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="border-border bg-background overflow-hidden rounded-xl border" data-testid="account-security">
      <header className="border-border border-b px-6 py-4">
        <h2 className="text-sm font-semibold">账号与安全</h2>
        <p className="text-muted-foreground mt-1 text-xs">
          这些只是你能用哪种方式登录，不影响你能做什么。
        </p>
      </header>

      <ul className="divide-border divide-y">
        <li className="flex flex-wrap items-center justify-between gap-3 px-6 py-4">
          <div>
            <p className="text-sm font-medium">密码</p>
            <p className="text-muted-foreground mt-0.5 text-xs" data-testid="security-password-state">
              {hasPassword ? '已设置' : '未设置（第三方登录注册的账号没有密码）'}
            </p>
          </div>
          {/* 设置密码本期不做：它需要单独的规则（旧密码、强度、与第三方账号的关系） */}
          {hasPassword ? null : (
            <span className="text-muted-foreground text-xs">暂不支持设置密码</span>
          )}
        </li>

        {bound.map((binding) => (
          <li
            key={binding.provider}
            className="flex flex-wrap items-center justify-between gap-3 px-6 py-4"
            data-testid={`security-bound-${binding.provider}`}
          >
            <div>
              <p className="text-sm font-medium">{binding.label}</p>
              <p className="text-muted-foreground mt-0.5 text-xs">
                {binding.displayName ?? '未提供昵称'} · 绑定于{' '}
                {new Date(binding.boundAt).toLocaleDateString('zh-CN')}
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              data-testid={`security-unbind-${binding.provider}`}
              disabled={!canUnbindAnything || busy !== null}
              title={canUnbindAnything ? undefined : '这是你唯一的登录方式'}
              onClick={() => {
                setBusy(binding.provider)
                setError(null)
                unbind.mutate(binding.provider)
              }}
            >
              {busy === binding.provider ? <Spinner /> : <Unlink className="size-4" />}
              解除绑定
            </Button>
          </li>
        ))}

        {unboundProviders.map((provider) => (
          <li
            key={provider.key}
            className="flex flex-wrap items-center justify-between gap-3 px-6 py-4"
            data-testid={`security-available-${provider.key}`}
          >
            <div>
              <p className="text-sm font-medium">{provider.label}</p>
              <p className="text-muted-foreground mt-0.5 text-xs">未绑定</p>
            </div>
            <Button
              variant="outline"
              size="sm"
              data-testid={`security-bind-${provider.key}`}
              disabled={busy !== null}
              onClick={() => void startBind(provider.key)}
            >
              {busy === provider.key ? <Spinner /> : <Link2 className="size-4" />}
              绑定
            </Button>
          </li>
        ))}
      </ul>

      {bound.length > 0 && !canUnbindAnything ? (
        <p className="text-muted-foreground border-border border-t px-6 py-3 text-xs">
          你只有这一种登录方式，解除后将无法登录。请先绑定其它账号。
        </p>
      ) : null}

      {error === null ? null : (
        <div className="border-border border-t px-6 py-3">
          <p className="text-destructive text-xs" data-testid="security-error">
            {error}
          </p>
        </div>
      )}
    </section>
  )
}
