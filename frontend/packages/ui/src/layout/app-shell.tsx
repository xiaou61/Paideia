import type { ReactNode } from 'react'

import { cn } from 'cn'

import { ThemeToggle } from '../theme/theme-toggle'

/**
 * 应用外壳：页头 / 内容区 / 页脚三个插槽。
 *
 * <p>刻意只做布局，不含路由、不含角色判断、不读后端 —— 三个应用（学习者端、管理端、
 * 展览页）的导航与权限各不相同，那些属于各自应用；共用的只是"页面长什么样"。
 *
 * <p>页头照文档站/学习产品的通例做成**一条能装下"品牌 + 导航 + 搜索 + 账号"的固定横条**：
 * 左边是品牌（色块标识 + 名称 + 当前位置），中间留给导航与搜索（由各应用通过 `actions` 传入，
 * 里面自己摆一个 `flex-1` 撑开），右边固定是主题开关。高度只有 56px，不抢内容。
 *
 * <p>**品牌标识是色块 + 首字母**，不引图标也不是图片：这块地方是"这是一件产品"最便宜的信号，
 * 而它必须跟着主题走，所以颜色只来自令牌。
 *
 * <p><b>品牌可以点回首页（2026-11-09）</b>：用户反馈"点了 Paideia 没反应"——它是纯文本，
 * 在任何一个深页面上都只能靠浏览器后退。这条通例在文档站/后台里都是"点 logo 回首页"，
 * 缺了它第一个反应就是"这个站没做导航"。
 *
 * <p>这里**破例**带了一点路由假设：默认 {@code homeHref = '#/'}。理由是三个应用都用
 * Hash 路由、首页都在 {@code #/}，而 {@code <AppShell>} 在学习者端有 38 处调用——为一行
 * 链接改 38 个文件，收益远小于把这条约定写在这里。不想带链接的调用方传 {@code null}。
 * 用 {@code <a href>} 而不是 router 的 {@code <Link>}：设计系统不引 react-router，
 * 改 hash 会触发 HashRouter 的 hashchange，效果与 Link 一致。
 */
export function AppShell({
  title,
  subtitle,
  actions,
  children,
  wide = false,
  className,
  homeHref = '#/',
}: {
  title: string
  subtitle?: ReactNode
  /** 页头右侧的操作区：通常放应用自己的导航 + 账号区。 */
  actions?: ReactNode
  children: ReactNode
  /** 列表/总览类页面用宽档：窄档在 1440 屏上两侧留白太多，读起来像"没做完"。 */
  wide?: boolean
  className?: string
  /** 点品牌回到哪里；传 `null` 渲染成不可点的纯文本。 */
  homeHref?: string | null
}) {
  const brand = (
    <>
      <span
        aria-hidden
        className="from-primary to-primary/75 text-primary-foreground flex size-8 items-center justify-center rounded-lg bg-gradient-to-br text-sm font-bold"
      >
        {title.slice(0, 1).toUpperCase()}
      </span>
      <span className="text-[0.9375rem] font-semibold tracking-tight">{title}</span>
      {subtitle === undefined ? null : (
        <span className="border-border text-muted-foreground hidden rounded-full border px-2 py-0.5 text-xs sm:inline-block">
          {subtitle}
        </span>
      )}
    </>
  )

  return (
    <div className={cn('flex min-h-dvh flex-col', className)}>
      <header className="border-border bg-background/95 sticky top-0 z-30 border-b">
        <div className="safe-px mx-auto flex h-14 w-full max-w-[86rem] items-center gap-2 sm:gap-3 sm:px-6">
          {homeHref === null ? (
            <div className="flex shrink-0 items-center gap-2.5">{brand}</div>
          ) : (
            <a
              href={homeHref}
              data-testid="brand-home"
              aria-label={`回到首页：${title}`}
              title="回到首页"
              className="hover:bg-accent/60 focus-visible:ring-ring/50 -ml-1.5 flex shrink-0 items-center gap-2.5 rounded-lg px-1.5 py-1 transition-colors outline-none focus-visible:ring-3"
            >
              {brand}
            </a>
          )}

          <div className="flex min-w-0 flex-1 items-center gap-2">
            {actions}
            <ThemeToggle />
          </div>
        </div>
      </header>

      {/*
        原底部留白是给已下线的 AI 助手浮标让位的；浮标随 WORK-066 删除后这里不再需要额外 padding。
      */}
      <main
        className={cn(
          'mx-auto w-full flex-1 px-4 py-8 sm:px-6 sm:py-10',
          wide ? 'max-w-[86rem]' : 'max-w-3xl',
        )}
      >
        {children}
      </main>

      <footer className="border-border mt-8 border-t px-6 py-6">
        <div className="text-muted-foreground mx-auto flex w-full max-w-[86rem] flex-wrap items-center gap-x-3 gap-y-1 text-xs">
          <span className="bg-primary/10 text-primary flex size-5 items-center justify-center rounded text-[0.625rem] font-bold">
            {title.slice(0, 1).toUpperCase()}
          </span>
          <span>Paideia · 千人千面的 AI 个性化学习平台</span>
        </div>
      </footer>
    </div>
  )
}

/**
 * 页面的主标题。
 *
 * <p>字号用了阶梯里最大的一档（40px）——参考的文档站与学习产品在正文页都是这个量级，
 * 32px 在中英混排里撑不住"这是一篇文章"的第一印象。副标题从 14px 提到 16px 并放宽行高，
 * 它是**导语**不是标签。
 *
 * <p>**没有 eyebrow**：曾经有过一个标题上方的小标签属性，19 处调用写着"知识库""我的""复习"
 * 这类与标题同义的话。它占掉一行却不提供任何信息，而标题自己就该扛住那一屏的第一眼。
 * 真需要"当前位置"的页面，导航的选中态已经在说这件事了。
 */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string
  description?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="mb-8 flex items-end justify-between gap-6">
      <div className="min-w-0">
        <h1 className="text-3xl">{title}</h1>
        {description === undefined ? null : (
          <p className="text-muted-foreground mt-3 max-w-2xl text-base leading-7">{description}</p>
        )}
      </div>
      {actions === undefined ? null : <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  )
}

/** 页面里的一节：标签 + 说明 + 内容，各应用页面共用。 */
export function Section({
  title,
  description,
  actions,
  children,
  className,
}: {
  title: string
  description?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={cn('mb-12', className)}>
      <div className="mb-5 flex items-end justify-between gap-4">
        <div>
          {/* 二级标题统一 20px/600（base 层的 h2 默认 600，这里显式写死字号，
              免得各页面各用一档：同一页出现过 14px 与 20px 两种 h2） */}
          <h2 className="text-lg font-semibold">{title}</h2>
          {description === undefined ? null : (
            <p className="text-muted-foreground mt-1 text-sm">{description}</p>
          )}
        </div>
        {actions === undefined ? null : <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  )
}
