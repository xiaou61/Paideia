import {
  AssistantProvider,
  AssistantShell,
  createAssistantApi,
  parseAttachmentUri,
  type Attachment,
} from '@paideia/assistant'
import { AuthProvider, useAuth } from '@paideia/auth'
import { createKnowledgeApi } from '@paideia/knowledge'
import { ThemeProvider } from '@paideia/ui'
import '@paideia/ui/styles.css'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'

import { auth } from './auth'
import { notes, saveNote } from './notes-api'
import { platform } from './host'
import { PlatformProvider } from './platform'
import { router } from './router'

/**
 * AI 精简的客户端。与页面用的 `api` 同一个装配来源（带令牌、401 刷新重放），
 * 因此它同样不认识令牌本身。
 */
const assistantApi = createAssistantApi(auth.api)

/** `@` 引用要用的检索（WORK-058）：与页面同一个 api，因此同样带令牌与 401 刷新重放。 */
const knowledgeApi = createKnowledgeApi(auth.api)

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

/**
 * `@` 引用的搜索（WORK-058）。
 *
 * <p>笔记走**本地过滤**（服务端没有笔记检索端点，列表本来就全量返回）；条目走服务端检索。
 * 两边都用 `allSettled`：一边失败不该让另一边也空着。
 *
 * <p>shortcut: 每次 `@` 都会拉一次全量笔记列表，笔记上千条时这里会变慢，届时给笔记加检索端点。
 */
async function searchAttachments(query: string): Promise<Attachment[]> {
  const keyword = query.trim()
  const [notesResult, entriesResult] = await Promise.allSettled([
    notes.list(),
    knowledgeApi.entries(keyword === '' ? { size: 5 } : { q: keyword, size: 5 }),
  ])
  const found: Attachment[] = []
  if (notesResult.status === 'fulfilled') {
    const matched = notesResult.value.filter((item) => keyword === '' || item.title.includes(keyword))
    for (const note of matched.slice(0, 5)) {
      found.push({ uri: `note:${note.id}`, label: `笔记：${note.title}` })
    }
  }
  if (entriesResult.status === 'fulfilled') {
    for (const entry of entriesResult.value.items.slice(0, 5)) {
      found.push({ uri: `entry:${entry.id}`, label: `条目：${entry.title}` })
    }
  }
  return found
}

/**
 * 打开被附着的对象（WORK-058）。
 *
 * <p>uri 是助手的通用名字，路由是本应用的私事——助手包只把字符串交出来，怎么跳由这里决定。
 * **认不出来就什么也不做**：宁可不动，也不要跳到一个猜出来的地址。
 */
function openAttachment(uri: string) {
  const parsed = parseAttachmentUri(uri)
  if (parsed === null) {
    return
  }
  const head = parsed.id.split('/')[0] ?? ''
  if (parsed.type === 'entry' && head !== '') {
    void router.navigate(`/knowledge/entries/${head}`)
    return
  }
  if (parsed.type === 'note' && Number.isFinite(Number(head))) {
    // 笔记页本来就是用路由 state 选中一条（新建后跳转也走这条路）
    void router.navigate('/notes', { state: { selected: Number(head) } })
    return
  }
  if (parsed.type === 'track') {
    void router.navigate(`/learn/${parsed.id}`)
    return
  }
  if (parsed.type === 'book') {
    void router.navigate(`/books/${parsed.id}`)
    return
  }
  if (parsed.type === 'resume' && Number.isFinite(Number(head))) {
    void router.navigate(`/resume/${head}`)
  }
}

/**
 * 助手壳的挂载门。
 *
 * <p><b>必须等真正登录之后再挂</b>：壳一挂载就会去读"我的凭据"，那是受保护端点。
 * 未登录时——以及"刚刷新页面、只拿着 refresh token、状态还是 `loading`"的那一小段里——
 * 发出去必然是 401。而这个 401 会走进带令牌的 fetch，被判成"会话过期"并触发一次刷新：
 * 那次刷新既没有意义（本来就没有访问令牌），失败时还会把刚建立的会话清掉。
 *
 * <p>实测（WORK-006 TASK-003）：登录页上壳发出的两次 401 正是这条路径。它此前没被发现，
 * 是因为壳把读凭据的失败**静默降级**成了"未配置凭据"——看起来像正常状态。
 */
function AssistantMount() {
  const { status } = useAuth()
  if (status !== 'signed-in') {
    return null
  }
  // 把助手的回答接上笔记：助手包不认识笔记，这层接线放在组合根
  return (
    <AssistantShell
      api={assistantApi}
      onSaveNote={saveNote}
      onOpenUri={openAttachment}
      searchObjects={searchAttachments}
    />
  )
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
            {/* 悬浮助手壳挂在路由之外：它要**贯穿全站**常驻，而不是每个页面自己摆一个 */}
            <AssistantProvider>
              <RouterProvider router={router} />
              <AssistantMount />
            </AssistantProvider>
          </AuthProvider>
        </QueryClientProvider>
      </ThemeProvider>
    </PlatformProvider>
  </StrictMode>,
)
