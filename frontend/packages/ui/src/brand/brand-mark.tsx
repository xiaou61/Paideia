import { cn } from 'cn'

/**
 * 品牌字标：墨色圆角砖 + 实心几何 P。
 *
 * <p>字形来自 Segoe UI Bold 的「P」，取真实字形轮廓（不是描边骨架），所以笔画是有粗细变化的
 * 实心形，在小尺寸与放大时都立得住，不会像描边字母那样显得单薄。字形已转成纯路径、不依赖字体，
 * 换机器、换字号都不走样——旧实现直接取 `title.slice(0,1)` 当字模，本质是把字体当 logo。
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
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.225) }}
    >
      {/*
        viewBox 512：字形路径已按约 19% 内边距缩放并居中（See build_icons.py），
        高度约占砖块 62%，是方图标里字标的常见占比。fill 用 currentColor，颜色由父级文字色决定。
      */}
      <svg viewBox="0 0 512 512" width={size} height={size} aria-hidden focusable="false">
        <path
          d="M211.51 311.36V420.72H140.00V103.28H252.02Q372.00 103.28 372.00 204.44Q372.00 252.26 337.57 281.81Q303.15 311.36 245.60 311.36ZM211.51 158.18V257.13H239.62Q296.73 257.13 296.73 207.10Q296.73 158.18 239.62 158.18Z"
          fill="currentColor"
        />
      </svg>
    </span>
  )
}
