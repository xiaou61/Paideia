import { ThemeToggle } from '@paideia/ui'
import {
  Boxes,
  Bell,
  ChevronDown,
  ChevronRight,
  Coins,
  Database,
  FileText,
  Flag,
  FolderTree,
  LayoutDashboard,
  Library,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  Receipt,
  Users,
  Activity,
  Cpu,
  ListOrdered,
  MessageSquareWarning,
} from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router'

interface MenuItem {
  to: string
  label: string
  icon: typeof FileText
  /** 有子项的是一级菜单，点了展开/收起，自身不跳转。 */
  children?: MenuItem[]
}

/**
 * 侧边菜单。层级与路由表一一对应，父项只是分组、不指向任何页面。
 *
 * <p>放在管理端而不是设计系统里：学习者端是内容优先的单列阅读界面，两侧的信息架构本就
 * 不同，把侧栏做成共享组件等于给阅读界面塞一套它不需要的导航。
 */
const MENU: MenuItem[] = [
  { to: '/', label: '总览', icon: LayoutDashboard },
  {
    to: '/knowledge',
    label: '知识库',
    icon: Library,
    children: [
      { to: '/knowledge/categories', label: '分类管理', icon: FolderTree },
      { to: '/knowledge/entries', label: '条目管理', icon: FileText },
      { to: '/knowledge/concepts', label: '概念', icon: Boxes },
    ],
  },
  // 举报处理是学习者端分享出去的内容的**唯一治理入口**（用户裁决 11：由管理员处理）
  { to: '/reports', label: '举报处理', icon: Flag },
  // SQL 数据集（WORK-035）：社区数据集的治理。只能下架/恢复，不能编辑（内容属于作者）。
  {
    to: '/sql-datasets',
    label: 'SQL 数据集',
    icon: Database,
    children: [
      { to: '/sql-datasets/list', label: '数据集管理', icon: Database },
      { to: '/sql-datasets/reports', label: '举报处理', icon: Flag },
    ],
  },
  // 用户管理（WORK-030）：调积分、改角色、删号都在这里。原先独立的「积分调整」页
  // 已按用户裁决删除（U-04）——那页让人手敲内部主键，敲错一位就给另一个人发分。
  { to: '/users', label: '用户管理', icon: Users },
  // AI 积分（WORK-009）：本期不做充值，所以只剩"用量审计"这一个子页。
  // 它回答"谁用了多少、花了多少、谁给他发的"——发分本身在用户管理里。
  {
    to: '/ai-credits',
    label: 'AI 积分',
    icon: Coins,
    children: [
      { to: '/ai-credits/audit', label: '用量审计', icon: Receipt },
    ],
  },
  // AI 调用统计（WORK-022）：概览看趋势，明细下钻到步骤。
  {
    to: '/ai-runs',
    label: 'AI 调用',
    icon: Activity,
    children: [
      { to: '/ai-runs/overview', label: '调用概览', icon: Activity },
      { to: '/ai-runs/list', label: '调用明细', icon: ListOrdered },
      { to: '/ai-runs/feedback', label: '回答反馈', icon: MessageSquareWarning },
    ],
  },
  // 运维（WORK-028）：只有一页（摘要卡片 + 完整面板），所以是叶子菜单项而不是父项分组。
  { to: '/ops/panel', label: '运维', icon: Cpu },
  // 事件（WORK-043）：告警的生命周期与时间线，与运维诊断分开一页。
  { to: '/ops/incidents', label: '事件', icon: Bell },
]

/** 面包屑：按菜单层级写死的路径（首页固定在最前，不再逐级可点）。 */
const CRUMBS: Record<string, string[]> = {
  '/': ['总览'],
  '/knowledge/categories': ['知识库', '分类管理'],
  '/knowledge/entries': ['知识库', '条目管理'],
  '/knowledge/concepts': ['知识库', '概念'],
  '/reports': ['举报处理'],
  '/sql-datasets/list': ['SQL 数据集', '数据集管理'],
  '/sql-datasets/reports': ['SQL 数据集', '举报处理'],
  '/users': ['用户管理'],
  '/ai-credits/audit': ['AI 积分', '用量审计'],
  '/ai-runs/overview': ['AI 调用', '调用概览'],
  '/ai-runs/list': ['AI 调用', '调用明细'],
  '/ai-runs/feedback': ['AI 调用', '回答反馈'],
  '/ops/panel': ['运维'],
  '/ops/incidents': ['事件'],
}

/**
 * 管理端外壳：左侧菜单 + 顶栏（面包屑 / 操作区）+ 内容区。
 *
 * <p>与学习者端的 `AppShell` 是两种骨架：那边是顶部横条 + 单列窄内容，这边是后台常见
 * 的"深色边框侧栏 + 灰底内容 + 白卡片"。共用的是色板与组件，不是布局——两边的信息
 * 密度差一个量级，硬套同一个外壳会让这两边都不好用。
 *
 * <p>侧栏可收起（宽窄两档），收起后只留图标并用 `title` 兜住可读性：后台的表单页常需要
 * 横向空间，这是这个布局最该给的一个开关。
 */
export function AdminShell({ actions, children }: { actions?: ReactNode; children: ReactNode }) {
  const { pathname } = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  /** 窄屏抽屉（WORK-016）：<md 时侧栏默认在屏幕外，由顶栏 ☰ 拉开。 */
  const [drawerOpen, setDrawerOpen] = useState(false)
  // 只记录"用户手动点过"的父菜单；没点过的跟着当前路由走
  const [toggled, setToggled] = useState<Record<string, boolean>>({})
  const crumbs = CRUMBS[pathname] ?? ['管理端']

  // 选中菜单后自动收起：手机上抽屉盖住内容，不关的话还要再点一次遮罩
  useEffect(() => {
    setDrawerOpen(false)
  }, [pathname])

  return (
    <div className="flex min-h-dvh">
      {/*
        窄屏遮罩。放在侧栏之前、用 z-40（侧栏 z-50），点它关闭抽屉。
        桌面端不渲染——>=md 的 `md:hidden` 保证它不会挡住表格。
      */}
      {drawerOpen ? (
        <button
          type="button"
          aria-label="关闭菜单"
          data-testid="admin-drawer-backdrop"
          onClick={() => setDrawerOpen(false)}
          className="bg-overlay fixed inset-0 z-40 cursor-default md:hidden"
        />
      ) : null}

      <aside
        data-open={drawerOpen ? 'true' : 'false'}
        data-testid="admin-sidebar"
        className={`border-border bg-card fixed inset-y-0 left-0 z-50 flex w-64 shrink-0 flex-col border-r transition-transform duration-200 md:sticky md:top-0 md:z-auto md:h-dvh md:translate-x-0 md:transition-[width] ${
          drawerOpen ? 'translate-x-0' : '-translate-x-full'
        } ${collapsed ? 'md:w-14' : 'md:w-56'}`}
      >
        <div className="border-border flex h-14 items-center gap-2.5 border-b px-3">
          <span className="bg-primary text-primary-foreground grid size-7 shrink-0 place-items-center rounded-md text-xs font-bold">
            P
          </span>
          <div className={`min-w-0 ${collapsed ? 'md:hidden' : ''}`}>
            <div className="truncate text-sm leading-tight font-semibold">Paideia</div>
            <div className="text-muted-foreground truncate text-[11px] leading-tight">管理端</div>
          </div>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto p-2">
          {MENU.map((item) => {
            const Icon = item.icon
            if (item.children === undefined) {
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  label={item.label}
                  icon={Icon}
                  collapsed={collapsed}
                  active={pathname === item.to}
                />
              )
            }

            // 展开与否：用户手动点过的以手动为准，没点过就跟着当前路由走（进到子页面自动展开）
            const open = toggled[item.label] ?? item.children.some((child) => child.to === pathname)
            const active = item.children.some((child) => child.to === pathname)
            return (
              <div key={item.to}>
                <button
                  type="button"
                  title={item.label}
                  aria-expanded={open}
                  data-testid={`admin-menu-${item.label}`}
                  onClick={() => {
                    // 收起状态下点父菜单先把侧栏展开——否则子项根本没地方显示
                    if (collapsed) {
                      setCollapsed(false)
                      setToggled((current) => ({ ...current, [item.label]: true }))
                      return
                    }
                    setToggled((current) => ({ ...current, [item.label]: !open }))
                  }}
                  className={`flex h-8 w-full items-center gap-2 rounded-md px-2 text-sm transition-colors ${
                    active
                      ? 'text-foreground font-medium'
                      : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground'
                  } ${collapsed ? 'md:justify-center' : ''}`}
                >
                  <Icon className="size-4 shrink-0" />
                  <span className={`flex min-w-0 flex-1 items-center gap-2 ${collapsed ? 'md:hidden' : ''}`}>
                    <span className="flex-1 truncate text-left">{item.label}</span>
                    <ChevronDown
                      className={`size-3.5 shrink-0 transition-transform ${open ? '' : '-rotate-90'}`}
                    />
                  </span>
                </button>
                {open && !collapsed ? (
                  <div className="border-border mt-1 ml-4 space-y-1 border-l pl-1">
                    {item.children.map((child) => (
                      <NavLink
                        key={child.to}
                        to={child.to}
                        label={child.label}
                        icon={child.icon}
                        collapsed={false}
                        active={pathname === child.to}
                      />
                    ))}
                  </div>
                ) : null}
              </div>
            )
          })}
        </nav>

        <div className="border-border hidden border-t p-2 md:block">
          <button
            type="button"
            onClick={() => setCollapsed((value) => !value)}
            title={collapsed ? '展开菜单' : '收起菜单'}
            aria-label={collapsed ? '展开菜单' : '收起菜单'}
            data-testid="admin-menu-toggle"
            className={`text-muted-foreground hover:bg-muted hover:text-foreground flex h-8 w-full items-center gap-2 rounded-md px-2 text-sm ${
              collapsed ? 'md:justify-center' : ''
            }`}
          >
            {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
            {collapsed ? null : <span>收起菜单</span>}
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-border bg-background sticky top-0 z-10 flex h-14 shrink-0 items-center justify-between gap-2 border-b px-3 sm:gap-4 sm:px-4">
          {/* 窄屏的菜单入口：桌面端侧栏常驻，不需要它 */}
          <button
            type="button"
            aria-label="打开菜单"
            aria-expanded={drawerOpen}
            data-testid="admin-drawer-toggle"
            onClick={() => setDrawerOpen(true)}
            className="hover:bg-accent -ml-1 flex size-11 shrink-0 items-center justify-center rounded-lg md:hidden"
          >
            <Menu className="size-5" />
          </button>
          <nav aria-label="面包屑" className="flex min-w-0 items-center gap-1.5 text-sm">
            <span className="text-muted-foreground shrink-0">首页</span>
            {crumbs.map((crumb, index) => (
              <span key={crumb} className="flex min-w-0 items-center gap-1.5">
                <ChevronRight className="text-muted-foreground size-3.5 shrink-0" />
                <span className={index === crumbs.length - 1 ? 'truncate font-medium' : 'text-muted-foreground truncate'}>
                  {crumb}
                </span>
              </span>
            ))}
          </nav>
          <div className="flex shrink-0 items-center gap-2">
            {actions}
            <ThemeToggle />
          </div>
        </header>

        <main className="bg-muted/60 flex-1 p-3 sm:p-6">{children}</main>
      </div>
    </div>
  )
}

/** 菜单项（一级与子项共用）：收起状态只剩图标，用 `title` 与无障碍名称兜住可读性。 */
function NavLink({
  to,
  label,
  icon: Icon,
  collapsed,
  active,
}: {
  to: string
  label: string
  icon: typeof FileText
  collapsed: boolean
  active: boolean
}) {
  return (
    <Link
      to={to}
      title={label}
      data-testid={`admin-nav-${label}`}
      aria-current={active ? 'page' : undefined}
      data-active={active ? 'true' : undefined}
      className={`flex h-9 items-center gap-2 rounded-lg border px-2.5 text-sm transition-colors ${
        active
          ? 'border-border bg-accent text-accent-foreground font-medium'
          : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground border-transparent'
      } ${collapsed ? 'md:justify-center' : ''}`}
    >
      <Icon className="size-4 shrink-0" />
      <span className={`truncate ${collapsed ? 'md:hidden' : ''}`}>{label}</span>
    </Link>
  )
}
