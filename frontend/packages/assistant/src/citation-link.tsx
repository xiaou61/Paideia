import type { ReactNode } from 'react'

import { CITATION_PREFIX } from './citation'

/**
 * 回答里的一条链接（WORK-058）。
 *
 * <p>认 `CITATION_PREFIX` 前缀的当引用：渲染成按钮、点一下把 uri 交回应用（**本包不认识路由**）。
 * 其它链接保持外链行为。拆成独立组件是为了能直接单测——渲染器本身是懒加载的，
 * 在包级测试里挂载 Markdown 只会停在 Suspense 占位。
 */
export function CitationLink({
  href,
  children,
  onOpenUri,
}: {
  href?: string
  children?: ReactNode
  onOpenUri?: (uri: string) => void
}) {
  const uri =
    href !== undefined && href.startsWith(CITATION_PREFIX) ? href.slice(CITATION_PREFIX.length) : null

  if (uri === null) {
    return (
      <a href={href} target="_blank" rel="noreferrer noopener" className="underline underline-offset-2">
        {children}
      </a>
    )
  }

  return (
    <button
      type="button"
      className="underline underline-offset-2"
      title={`打开 ${uri}`}
      data-testid={`citation-${uri.replace(/[/:]/g, '-')}`}
      onClick={() => onOpenUri?.(uri)}
    >
      {children}
    </button>
  )
}
