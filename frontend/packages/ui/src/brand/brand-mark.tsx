import { cn } from 'cn'

/**
 * 品牌字标：圆角方砖 + 几何 P。
 *
 * <p>这是「这是一件产品」最便宜也最稳的信号：一块墨色圆角砖，里面一个用单笔描边画出来的
 * 几何 P（圆头圆角、留白克制），在 16px 的标签页与 64px 的首屏同样立得住。字形不依赖字体，
 * 换机器、换字号都不会走样——旧实现直接取 `title.slice(0,1)` 当字模，本质是把字体当 logo。
 *
 * <p>**颜色只来自令牌**（`bg-primary` / `text-primary-foreground`），所以明暗主题自动反相：
 * 浅色下是深墨砖白字，深色下是近白砖深字；字标用 `currentColor` 绘制，跟着前景色走。
 * 没有引入任何色相（DESIGN.md 的 No-Color Rule），也不做玻璃模糊或大色块渐变装饰。
 *
 * <p>默认是装饰性的（`aria-hidden`）：它总与可见的文字品牌（“Paideia”）并列，或位于一个带
 * `aria-label` 的链接内，重复朗读没有意义。单独使用、周围没有文字时传 `label` 让它成为 `role="img"`。
 */
export function BrandMark({
  size = 32,
  className,
  label,
}: {
  /** 砖块边长（px）。字标按同一比例缩放，视觉重心居中。 */
  size?: number
  className?: string
  /** 单独出现（无相邻文字 / 无外层 aria-label）时提供可读名称。 */
  label?: string
}) {
  return (
    <span
      aria-hidden={label === undefined ? true : undefined}
      role={label === undefined ? undefined : 'img'}
      aria-label={label}
      className={cn(
        'text-primary-foreground from-primary to-primary/85 inline-flex shrink-0 items-center justify-center bg-gradient-to-br',
        className,
      )}
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.22) }}
    >
      {/*
        viewBox 512：字标路径自带约 14% 的内边距（上下），落进方形砖后高度约占 70%，
        是方图标里字标的常见占比。stroke 用 currentColor，颜色由父级文字色决定。
      */}
      <svg viewBox="0 0 512 512" width={size} height={size} aria-hidden focusable="false">
        <path
          d="M147 408 V104 h100 a118 118 0 0 1 0 236 h-100"
          fill="none"
          stroke="currentColor"
          strokeWidth={66}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </span>
  )
}
