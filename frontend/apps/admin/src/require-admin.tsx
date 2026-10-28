import { AuthLayout, LoginForm, useAuth } from '@paideia/auth'
import { Badge, Button, PageHeader, Spinner } from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'

import { api } from './api'
import { AdminShell } from './components/AdminShell'
import { AdminDenied, canEnterAdmin } from './guard'

interface HealthResponse {
  status: string
  groups?: string[]
}

/**
 * 后端状态徽标。放在应用外壳里，因此**在所有状态下都会渲染** ——
 * 未登录或权限不足时同样能证明接口链路是通的，而不是"什么都连不上"。
 */
function BackendStatus() {
  const health = useQuery({
    queryKey: ['backend-health'],
    queryFn: () => api.request<HealthResponse>('/actuator/health'),
  })

  if (health.isPending) {
    return <Badge variant="secondary">后端查询中</Badge>
  }
  if (health.isError) {
    return <Badge variant="destructive">后端不可达</Badge>
  }
  return (
    <Badge variant={health.data.status === 'UP' ? 'outline' : 'destructive'}>
      后端 <span data-testid="admin-health-status">{health.data.status}</span>
    </Badge>
  )
}

/** 页头的账号区。 */
function AccountBar() {
  const { user, logout } = useAuth()
  if (user === null) {
    return null
  }
  return (
    <div className="flex items-center gap-2">
      <span className="text-muted-foreground text-xs" data-testid="admin-current-user">
        {user.username}
      </span>
      <Button variant="ghost" size="sm" data-testid="admin-logout" onClick={() => void logout()}>
        登出
      </Button>
    </div>
  )
}

/**
 * 管理端的门与外壳。
 *
 * <p>四种状态依次是：确认登录状态 → 未登录（一个真正的登录页）→ 已登录但非管理员
 * （明确告知被拒）→ 管理员（显示管理区）。第三种刻意不是"跳走"而是"说清楚"，
 * 授权缺失必须看得见。
 *
 * <p>未登录与加载中用 `AuthLayout`（裸页面），其余状态用 `AdminShell`：登录页不该带
 * 侧栏与面包屑，那些导航在未登录时也不可用。
 *
 * <p>**它只做界面分流，不是授权边界**——真正的拦截在后端 `/api/v1/knowledge/admin/**`
 * 的 `hasRole('ADMIN')` 规则上。这里集中在路由外层一处，是为了让"每个管理页面都必须
 * 先过这道门"成为构造上的事实，而不是每个页面自己记得包一层。
 */
export function RequireAdmin({ children, bare = false }: {
  children: ReactNode
  /**
   * 不带侧栏与顶栏（调用大屏用）。
   *
   * <p>只去掉**外壳**，门一分不少：登录、角色、被拒提示都还在。全屏显示不该等于放行，
   * 否则"大屏"就成了绕过授权的旁路。
   */
  bare?: boolean
}) {
  const { status, user } = useAuth()

  if (status === 'loading') {
    return (
      <div className="text-muted-foreground flex min-h-dvh items-center justify-center gap-2 text-sm">
        <Spinner />
        正在确认登录状态…
      </div>
    )
  }

  if (status === 'signed-out') {
    return (
      <div data-testid="admin-sign-in">
        <AuthLayout description="与学习者端共用账号；登录后还需具备管理员角色。">
          <LoginForm />
        </AuthLayout>
      </div>
    )
  }

  if (bare) {
    return canEnterAdmin(user) ? (
      <div data-testid="admin-console">{children}</div>
    ) : (
      <div className="p-8"><AdminDenied user={user} /></div>
    )
  }

  return (
    <AdminShell
      actions={
        <>
          <BackendStatus />
          <AccountBar />
        </>
      }
    >
      {canEnterAdmin(user) ? (
        <div data-testid="admin-console">{children}</div>
      ) : (
        <>
          <PageHeader title="管理端" description="当前账号没有管理权限。" />
          <AdminDenied user={user} />
        </>
      )}
    </AdminShell>
  )
}
