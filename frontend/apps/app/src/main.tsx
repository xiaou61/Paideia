import { AuthProvider, useAuth } from '@paideia/auth'
import { ThemeProvider } from '@paideia/ui'
import '@paideia/ui/styles.css'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'

import { auth } from './auth'
import { platform } from './host'
import { PlatformProvider } from './platform'
import { router } from './router'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 骨架期不做失败重试：连不上后端时希望立刻看到原因，而不是等几次重试
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

/**
 * 把认证上下文的刷新实现注册给带令牌的 fetch。
 *
 * <p>必须在上下文内部做（只有这里拿得到 `refreshSession`），而数据客户端是要给页面直接 import
 * 的稳定单例，两者只能这样衔接。见 `./auth` 的说明。
 */
function RefreshBridge() {
  const { refreshSession } = useAuth()
  useEffect(() => {
    auth.registerRefresh(refreshSession)
  }, [refreshSession])
  return null
}

/**
 * 桌面协议送来的握手码（WORK-037）。
 *
 * <p>桌面端点第三方登录后，授权在**系统浏览器**里完成（Electron 窗口不参与），
 * 回调落在远端服务器上，再由页面跳 `paideia://oauth?handoff=…` 把码交回应用。
 * 主进程转给这里，这里走与 Web 完全相同的一次性握手码交换。
 *
 * <p>Web 端没有这个桥，所以整段是空操作。
 */
function DesktopOauthBridge() {
  const { completeOAuth, status } = useAuth()
  useEffect(() => {
    const bridge = window.paideiaDesktop
    if (bridge?.onOauthHandoff === undefined) {
      return
    }
    return bridge.onOauthHandoff((handoff) => {
      // 已经登录时不再处理：协议链接可能是旧的（60 秒窗口内重复点击），
      // 覆盖当前会话没有意义，而且会打乱用户正在做的事
      if (status === 'signed-in') {
        return
      }
      void completeOAuth(handoff).catch(() => {
        // 失败时不弹窗：码过期是常见情况，用户重试即可。
        // 静默失败在这里是安全的——没有登录态就是没有登录态，界面停在登录页。
      })
    })
  }, [completeOAuth, status])
  return null
}

const container = document.getElementById('root')
if (container === null) {
  throw new Error('找不到挂载节点 #root')
}

/**
 * 宿主标识挂在 `<html data-platform>` 上。
 *
 * <p>它原本是首页"运行状态"那一块里的一枚可见徽章，2026-10-15 用户要求去掉那一段。但
 * "当前跑在哪个宿主上"确实是 e2e 要断言的东西（桌面端必须用 desktop 实现），所以它没有消失，
 * 只是从**界面**挪到了**元数据**：测试读属性，用户看不到。
 */
document.documentElement.dataset['platform'] = platform.kind

createRoot(container).render(
  <StrictMode>
    <PlatformProvider platform={platform}>
      <ThemeProvider storage={platform.cache}>
        <QueryClientProvider client={queryClient}>
          <AuthProvider tokens={auth.tokens} api={auth.authApi}>
            <RefreshBridge />
            <DesktopOauthBridge />
            <RouterProvider router={router} />
          </AuthProvider>
        </QueryClientProvider>
      </ThemeProvider>
    </PlatformProvider>
  </StrictMode>,
)
