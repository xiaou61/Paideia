import { AuthProvider, useAuth } from '@paideia/auth'
import { ThemeProvider } from '@paideia/ui'
import '@paideia/ui/styles.css'
import { MutationCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode, useEffect, useMemo } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'

import { auth, platform } from './auth'
import { router } from './router'

function SessionRouter() {
  const { sessionId } = useAuth()
  const queryClient = useMemo(() => {
    const checkSession = () => {
      if (auth.tokens.sessionId() !== sessionId) throw new DOMException('登录会话已结束', 'AbortError')
    }
    return new QueryClient({
      mutationCache: new MutationCache({ onMutate: checkSession, onSuccess: checkSession }),
      defaultOptions: {
        queries: { retry: false, refetchOnWindowFocus: false },
        // 写请求离线时直接失败，不等联网后用另一个会话的凭据发送。
        mutations: { networkMode: 'always', retry: false },
      },
    })
  }, [sessionId])
  useEffect(() => () => {
    if (auth.tokens.sessionId() !== sessionId) queryClient.clear()
  }, [queryClient, sessionId])
  return <QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>
}

/** 把认证上下文的刷新实现注册给带令牌的 fetch（原因见 apps/app/src/auth.ts 的说明）。 */
function RefreshBridge() {
  const { refreshSession } = useAuth()
  useEffect(() => {
    auth.registerRefresh(refreshSession)
  }, [refreshSession])
  return null
}

const container = document.getElementById('root')
if (container === null) {
  throw new Error('找不到挂载节点 #root')
}

createRoot(container).render(
  <StrictMode>
    <ThemeProvider storage={platform.cache}>
      <AuthProvider tokens={auth.tokens} api={auth.authApi}>
        <RefreshBridge />
        <SessionRouter />
      </AuthProvider>
    </ThemeProvider>
  </StrictMode>,
)
