import { createAuthApi, useAuth } from '@paideia/auth'
import { configFor, createIdentityApi } from '@paideia/identity'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  Input,
} from '@paideia/ui'
import { ChevronDown, LogOut, Menu, Plug, Search, UserRound, UserX } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'

import { api } from '../api'

/**
 * 学习者端页头：**主导航 + 搜索 + 账号区**放在一个组件里。
 *
 * <p>2026-10-12 修的一个真缺陷：知识库、条目页、我的自评三处页面互相都连着，但**没有人
 * 从首页指向它们**——登录后落在 `/`，页头只有用户名与登出，用户不可能自己走到内容里去。
 * 界面本身没坏，是**入口**从来没被当成需求写下来，所以两轮的验收标准（"在条目页能…"）
 * 都绕过了它。
 *
 * <p>导航与账号区刻意合在一个组件、而不是分别由各页面拼装：分开写的话，将来加一页就
 * 有可能只记得其一，而"某个页面忘了加入口"正是这次缺陷的形态。
 *
 * <p>激活态用 `aria-current="page"` 而不是只靠颜色——颜色只是它的一种表现。
 *
 * <p>**搜索框放在页头**（照文档站与笔记类产品的通例）：它是这一站最常用的动作，藏在某个
 * 页面里等于每次都要先走进去。它只负责把关键词带到条目列表页（`?q=`），真正的检索逻辑
 * 仍然只有一处。
 */
/**
 * 导航项。`key` 与 `@paideia/identity` 的 `preferredNav` 对齐，`group` 决定它落在哪个下拉里。
 *
 * <p>**不按身份隐藏入口**：身份只决定"哪一组排前面"，四个组对所有身份都出现。
 *
 * <p>为什么不横排：2026-10-22 加到 10 项时，1280 宽下每个标签被挤成竖排断字（"每日速/递"），
 * 于是拆成"主干 + 更多"；2026-10-26 用户仍说"可选择的项太多，要分类可下拉"，改成四组。
 * 分组之后页头只有四个触发器和一条搜索框，窄屏下也塞得进去。
 */
const NAV = [
  { key: 'learn' as const, group: 'learn' as const, to: '/learn', label: '从零开始', testId: 'learner-nav-learn' },
  { key: 'digest' as const, group: 'learn' as const, to: '/digest', label: '每日速递', testId: 'learner-nav-digest' },
  // 复习（WORK-034）：每天要看的入口。
  { key: 'review' as const, group: 'learn' as const, to: '/review', label: '复习', testId: 'learner-nav-review' },
  // 学习记录（WORK-053）：进度 / 卡关 / 到期 / 薄弱四块，外加三个 AI 入口。
  // 不进 preferredNav —— 身份排序只管那几个老入口，新入口固定排在后面。
  { key: 'progress' as const, group: 'learn' as const, to: '/progress', label: '学习记录', testId: 'learner-nav-progress' },
  // 训练营（WORK-029）：它是"还没有学习记录的人"的入口。
  // 不进 preferredNav —— 身份排序只管那几个老入口，新入口固定排在后面。
  { key: 'camp' as const, group: 'learn' as const, to: '/camp', label: '训练营', testId: 'learner-nav-camp' },
  { key: 'knowledge' as const, group: 'knowledge' as const, to: '/knowledge', label: '知识库', testId: 'learner-nav-knowledge' },
  // 书籍详解（WORK-046）：按书读，和"按主题查"并列的第二条轴。
  // 不进 preferredNav —— 它靠组内排后，不靠身份推荐。
  { key: 'books' as const, group: 'knowledge' as const, to: '/books', label: '书籍详解', testId: 'learner-nav-books' },
  { key: 'graph' as const, group: 'knowledge' as const, to: '/graph', label: '知识图谱', testId: 'learner-nav-graph' },
  { key: 'notes' as const, group: 'knowledge' as const, to: '/notes', label: '我的笔记', testId: 'learner-nav-notes' },
  { key: 'sql-practice' as const, group: 'practice' as const, to: '/practice/sql', label: 'SQL 练习', testId: 'learner-nav-sql-practice' },
  { key: 'my-assessments' as const, group: 'practice' as const, to: '/my-assessments', label: '我的自评', testId: 'learner-nav-my-assessments' },
  { key: 'interview' as const, group: 'job' as const, to: '/interview', label: '模拟面试', testId: 'learner-nav-interview' },
  // 简历（WORK-018）：求职者面上每次投递都要用的入口。
  // **不复用 `interview` 这个 key**：身份排序按 key 匹配，复用会让"面试排第一"这条既有
  // 验收失去观察点（2026-10-18 实测：identity.spec 里首链接断言因此变红）。
  { key: 'resume' as const, group: 'job' as const, to: '/resume', label: '我的简历', testId: 'learner-nav-resume' },
]

/** 组的顺序与文案。`key` 与上面 NAV 的 `group` 对应。 */
const GROUPS = [
  { key: 'learn' as const, label: '学习', testId: 'learner-nav-group-learn' },
  { key: 'knowledge' as const, label: '知识', testId: 'learner-nav-group-knowledge' },
  { key: 'practice' as const, label: '练习', testId: 'learner-nav-group-practice' },
  { key: 'job' as const, label: '求职', testId: 'learner-nav-group-job' },
]

export function LearnerHeader() {
  const { user, logout } = useAuth()
  const { pathname } = useLocation()
  // 导航按身份重排（WORK-010 T-12）。读不到身份时退回默认配置、**不阻塞页头**：
  // 页头是每页都有的东西，不能因为一个推荐用的接口慢就整页转圈。
  const { data: profile } = useQuery({
    queryKey: ['profile'],
    queryFn: () => createIdentityApi(api).profile(),
    staleTime: 5 * 60_000,
    retry: false,
  })
  /**
   * 组内顺序固定，**组间按身份重排**：取组内最早出现的那个 `preferredNav` 位置当这组的名次，
   * 没被配置提到的组排最后（`sort` 稳定，所以并列时仍按 GROUPS 的顺序）。
   */
  const grouped = useMemo(() => {
    const preferred = configFor(profile?.identity).preferredNav as readonly string[]
    return GROUPS.map((group) => {
      const items = NAV.filter((item) => item.group === group.key)
      const ranks = items.map((item) => preferred.indexOf(item.key)).filter((rank) => rank >= 0)
      return { ...group, items, rank: ranks.length === 0 ? Number.MAX_SAFE_INTEGER : Math.min(...ranks) }
    }).sort((left, right) => left.rank - right.rank)
  }, [profile?.identity])
  /** 条目页的地址是 /knowledge/entries/:id，所以前缀匹配也算在那一项里。 */
  const isActive = (to: string) => pathname === to || pathname.startsWith(`${to}/`)
  const navigate = useNavigate()
  const [deleting, setDeleting] = useState(false)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [keyword, setKeyword] = useState('')
  const [navOpen, setNavOpen] = useState(false)
  /**
   * 这个账号有没有密码（WORK-037）。第三方注册的用户从来没有密码，
   * 让他们填密码等于永远注销不了，所以要按实际情况切换确认方式。
   * null = 还没查出来，此时按"有密码"处理（保守）。
   */
  const [hasPassword, setHasPassword] = useState<boolean | null>(null)

  if (user === null) {
    return null
  }

  const submitSearch = (event: FormEvent) => {
    event.preventDefault()
    const trimmed = keyword.trim()
    if (trimmed === '') {
      return
    }
    navigate(`/knowledge/entries?q=${encodeURIComponent(trimmed)}`)
    setKeyword('')
  }

  /**
   * 注销。**不可恢复**：会话、记忆、笔记与已发出的分享一并删除（用户裁决 10）。
   *
   * <p>要口令：只凭一个被偷走的令牌就能清空一个人的全部数据，代价不对等。
   * 服务端删完刷新记录之后，本地这份令牌也刷不出新会话了（会话最多活到访问令牌到期）。
   */
  const confirmDeletion = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.requestEnvelope<boolean>('/api/v1/me/deletion', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // 有密码的账号：密码就是确认；没有密码的账号：显式确认是唯一凭据。
        // 两个字段都发，服务端按账号实际情况取用（confirmed 在有密码时被忽略）。
        body: JSON.stringify({ password, confirmed: hasPassword === false }),
      })
      // 账号已经没了：登出只是一个本地收尾（服务端那条刷新记录也被删了，登出接口会失败，
      // 所以这里不 await 它的成败——本地令牌必须清掉，界面要回到登录门）
      void logout().catch(() => undefined)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {/*
        窄屏导航（WORK-016）：<md 时三项导航与搜索收进抽屉，页头只留品牌 + 菜单 + 账号。
        三个入口在 360px 上并排会挤掉用户名（WORK-014 实测溢出 70px），抽屉是唯一
        既保住全部入口又不压缩页头的形态。≥md 完全沿用原来的横排导航。
      */}
      <Dialog open={navOpen} onOpenChange={setNavOpen}>
        <DialogTrigger
          render={
            <Button
              variant="ghost"
              size="icon"
              className="md:hidden"
              aria-label="打开导航"
              data-testid="learner-nav-toggle"
            />
          }
        >
          <Menu className="size-5" />
        </DialogTrigger>
        <DialogContent className="sm:max-w-sm" data-testid="learner-nav-drawer">
          <DialogHeader>
            <DialogTitle>导航</DialogTitle>
            <DialogDescription>跳到知识库、笔记或自评。</DialogDescription>
          </DialogHeader>

          <nav aria-label="移动导航" className="flex flex-col gap-3">
            {grouped.map((group) => (
              <div key={group.key} className="flex flex-col gap-1">
                <p className="text-muted-foreground px-3 text-xs" data-testid={`${group.testId}-drawer`}>
                  {group.label}
                </p>
                {group.items.map((item) => {
                  const active = isActive(item.to)
                  return (
                    <Link
                      key={item.to}
                      to={item.to}
                      aria-current={active ? 'page' : undefined}
                      data-testid={`${item.testId}-drawer`}
                      onClick={() => setNavOpen(false)}
                      className={`flex min-h-11 items-center rounded-lg border px-3 text-sm transition-colors ${
                        active
                          ? 'bg-accent text-accent-foreground border-border font-medium'
                          : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground border-transparent'
                      }`}
                    >
                      {item.label}
                    </Link>
                  )
                })}
              </div>
            ))}
          </nav>

          {/* 搜索在窄屏原来是 `hidden md:block`，等于手机上根本没有入口——放进抽屉补回来 */}
          <form onSubmit={(event) => { submitSearch(event); setNavOpen(false) }}>
            <div className="relative">
              <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
              <Input
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="搜索知识库…"
                aria-label="搜索知识库"
                data-testid="header-search-drawer"
                className="h-11 rounded-full pl-9"
              />
            </div>
          </form>
        </DialogContent>
      </Dialog>

      <nav aria-label="主导航" className="hidden items-center gap-0.5 whitespace-nowrap md:flex">
        {grouped.map((group) => (
          <DropdownMenu key={group.key}>
            <DropdownMenuTrigger
              data-testid={group.testId}
              render={
                <button
                  type="button"
                  // 菜单收起时只有触发器可见，所以"我在哪一类"由触发器自己表示
                  aria-current={group.items.some((item) => isActive(item.to)) ? 'page' : undefined}
                  className={`flex items-center gap-1 rounded-full border px-3 py-1.5 text-sm transition-colors ${
                    group.items.some((item) => isActive(item.to))
                      ? 'bg-accent text-accent-foreground border-border font-medium'
                      : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground border-transparent'
                  }`}
                />
              }
            >
              {group.label}
              <ChevronDown className="size-3.5" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-44">
              {group.items.map((item) => (
                <DropdownMenuItem
                  key={item.to}
                  data-testid={item.testId}
                  aria-current={isActive(item.to) ? 'page' : undefined}
                  onClick={() => { void navigate(item.to) }}
                >
                  {item.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        ))}
      </nav>

      <form onSubmit={submitSearch} className="ml-2 hidden md:block">
        <div className="relative">
          <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2" />
          <Input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索知识库…"
            aria-label="搜索知识库"
            data-testid="header-search"
            className="h-9 w-56 rounded-full pl-8 text-sm"
          />
        </div>
      </form>

      <div className="flex-1" />

      <DropdownMenu>
        <DropdownMenuTrigger
          data-testid="account-menu-trigger"
          render={
            <button
              type="button"
              className="hover:bg-accent flex shrink-0 items-center gap-2 rounded-full border border-transparent py-1 pr-2.5 pl-1 transition-colors"
            />
          }
        >
          <span className="bg-primary text-primary-foreground flex size-7 items-center justify-center rounded-full text-xs font-semibold">
            {user.username.slice(0, 1).toUpperCase()}
          </span>
          {/* 用户名留在触发器里而不是菜单里：它同时是"我是谁"的显示位，
              也是 e2e 判断登录态的锚点 */}
          {/*
            窄屏只留头像：360px 下导航 + 头像 + 用户名 + 主题开关会把页头挤破（实测溢出 70px）。
            用户名不隐藏而是**挪进 `sr-only`**：`getByTestId('current-user')` 的 toHaveText 仍成立，
            读屏也照常读到"我是谁"，只是视觉上让位给导航。
          */}
          <span className="text-muted-foreground hidden text-xs sm:inline" data-testid="current-user">
            {user.username}
          </span>
          <span className="sr-only sm:hidden" data-testid="current-user-compact">
            {user.username}
          </span>
          <ChevronDown className="text-muted-foreground size-3.5" />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          {/* Base UI 的 GroupLabel 必须在 Group 里，否则展开时会抛 MenuGroupContext is missing */}
          <DropdownMenuGroup>
            <DropdownMenuLabel>
              <span className="block text-sm font-medium">{user.username}</span>
              <span className="text-muted-foreground block text-xs">
                {user.role === 'admin' ? '管理员' : '学习者'}
              </span>
            </DropdownMenuLabel>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          {/* 身份是"我是谁"的一部分，与账号同级；它不是权限设置，所以不放在"危险动作"那一簇 */}
          <DropdownMenuItem data-testid="account-profile" onClick={() => { void navigate('/profile'); setNavOpen(false) }}>
            <UserRound className="size-4" />
            身份与目标
          </DropdownMenuItem>
          {/* MCP 接入入口（WORK-019）：它是"给外部助手用的钥匙"，低频且带凭据含义，
              放账号菜单比放主导航稳妥——主航道上不该出现一个可能泄露 token 的入口。 */}
          <DropdownMenuItem data-testid="account-mcp" onClick={() => { void navigate('/settings/mcp'); setNavOpen(false) }}>
            <Plug className="size-4" />
            MCP 接入
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          {/* 注销与登出都收进菜单：它们是低频且危险的动作，摆在页头只会天天误点 */}
          <DropdownMenuItem
            data-testid="account-deletion-open"
            onClick={() => {
              setDeleting(true)
              // 打开时现查：这个账号有没有密码决定对话框长什么样。
              // 查不到时保持 null，按"有密码"处理——保守方向是让人多填一次，而不是少填。
              void createAuthApi(api)
                .oauthIdentities()
                .then((result) => setHasPassword(result.hasPassword))
                .catch(() => setHasPassword(null))
            }}
          >
            <UserX className="size-4" />
            注销账号
          </DropdownMenuItem>
          <DropdownMenuItem data-testid="logout" onClick={() => void logout()}>
            <LogOut className="size-4" />
            登出
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <Dialog open={deleting} onOpenChange={setDeleting}>
        <DialogContent data-testid="account-deletion-dialog">
          <DialogHeader>
            <DialogTitle>注销账号</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <Alert variant="destructive">
              <AlertTitle>这一步不可恢复</AlertTitle>
              <AlertDescription>
                你的会话、记忆、个人笔记与已发出的分享都会被删除，且无法找回。
              </AlertDescription>
            </Alert>
            {hasPassword === false ? (
              <p className="text-sm" data-testid="account-deletion-no-password">
                此账号未设置密码（第三方登录注册），确认后将直接注销。
              </p>
            ) : (
              <Input
                type="password"
                value={password}
                placeholder="输入当前密码确认"
                aria-label="当前密码"
                data-testid="account-deletion-password"
                onChange={(event) => setPassword(event.target.value)}
              />
            )}
            {error === null ? null : <p className="text-destructive text-sm">{error}</p>}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleting(false)} disabled={busy}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={busy || (hasPassword !== false && password === '')}
              data-testid="account-deletion-confirm"
              onClick={() => void confirmDeletion()}
            >
              确认注销
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
