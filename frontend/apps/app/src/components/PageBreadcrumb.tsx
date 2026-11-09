import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router'

/**
 * 面包屑（2026-11-09）。
 *
 * <p>**为什么做在应用里而不是设计系统里**：它必须会生成 `<Link>`，而 `@paideia/ui` 刻意不依赖
 * react-router；同一个理由让品牌链接用 `<a href>`（见 `AppShell` 的注释）。
 *
 * <p>**最后一项不可点**：它是"你在这里"，做成链接只会让人以为还能往下钻。用
 * `aria-current="page"` 而不是只靠颜色——颜色是表现，语义要单独给。
 *
 * <p>窄屏允许换行（`flex-wrap`）：深层分类的中文名很长，硬挤一行会把整条挤成省略号，
 * 那样面包屑就失去了"看得见我在哪一层"的意义。
 */
export interface Crumb {
  label: string
  /** 不给就是当前项（不可点）。 */
  to?: string
  testId?: string
}

export function PageBreadcrumb({
  items,
  testId = 'breadcrumb',
  lastLink = false,
}: {
  items: Crumb[]
  testId?: string
  /**
   * 最后一项也做成链接（默认不可点）。
   *
   * <p>条目页要用它：那里的"你在这里"是下面那个 H1 标题，最后一级面包屑是**所属分类**，
   * 它应该能点进去看同一分类下的其它内容。
   */
  lastLink?: boolean
}) {
  if (items.length === 0) {
    return null
  }
  return (
    <nav
      aria-label="面包屑"
      data-testid={testId}
      className="text-muted-foreground mb-4 flex flex-wrap items-center gap-x-1 gap-y-1 text-sm"
    >
      {items.map((item, index) => {
        const last = index === items.length - 1
        const linkable = item.to !== undefined && (!last || lastLink)
        return (
          <span key={`${item.label}-${String(index)}`} className="flex min-w-0 items-center gap-x-1">
            {index > 0 ? <ChevronRight className="size-3.5 shrink-0 opacity-60" aria-hidden /> : null}
            {linkable ? (
              <Link
                to={item.to!}
                data-testid={item.testId}
                className="hover:text-foreground focus-ring max-w-[18rem] truncate rounded transition-colors"
                title={item.label}
              >
                {item.label}
              </Link>
            ) : (
              <span
                className="text-foreground max-w-[18rem] truncate"
                aria-current={last ? 'page' : undefined}
                data-testid={item.testId}
                title={item.label}
              >
                {item.label}
              </span>
            )}
          </span>
        )
      })}
    </nav>
  )
}
