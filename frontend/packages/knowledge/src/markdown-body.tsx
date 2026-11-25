import { Markdown, preloadMarkdown, type MarkdownProps, type ProgressPort, type SandboxPort } from '@paideia/ui'
import { useEffect, useMemo, useRef } from 'react'

import { extractHeadings } from './heading-slug'
import { FigureWithLightbox } from './image-lightbox'
import { resolveMediaUrl } from './media'
import { createEntryProgressPort } from './source-reading-progress'

/**
 * 渲染器（约 500 kB）**在本模块被求值时就开下**，不等数据回来。
 *
 * <p>它是懒加载的（否则会拖累主包——`@paideia/knowledge` 的 barrel 被首页那些页面静态引入，
 * 静态引入渲染器等于把它塞进主包）。而本模块只在"要看 markdown 的那几个页面"的 chunk 里，
 * 所以这里发起下载是安全的：**与条目的接口请求并行**，而不是等正文到手才开始下。
 * 少了这一句，读长文的人会先看到一小段没排版的原文。
 */
preloadMarkdown()

/**
 * 知识库条目正文渲染。
 *
 * <p><b>渲染器不在这里</b>（WORK-012）：markdown 的呈现由 `@paideia/ui` 的 `Markdown` 统一提供，
 * 助手回答与这里跑的是同一条管线。本组件只做三件"知识库特有"的事：
 * 回填标题锚点（目录要用）、图片换成可放大的图注（{@link FigureWithLightbox}）、
 * 把正文里的相对附件地址补成绝对地址（{@link resolveMediaUrl}）。
 *
 * <p><b>标题 id 在 effect 里回填，而不是在组件里用一个计数闭包。</b>两种写法的差别在
 * 重复渲染上：React 在开发模式下会渲染两遍，若 id 由一个"渲染期间自增"的闭包给出，
 * 第二遍会把序号继续往后推，于是目录里的锚点全部对不上（而且只在开发模式暴露）。
 * 回填是幂等的：同样的 markdown 永远得到同一批 id。
 *
 * <p>与本包 {@link import('./entry-toc').EntryToc} 共用 `extractHeadings`，
 * 因此目录与正文按**同一顺序**编号，重复标题也不会串。
 *
 * <p><b>源码解读的接入点（WORK-014）</b>：传了 `entryId` 才有进度、传了 `onAskAssistant`
 * 才有「问 AI」、传了 `onOpenEntry` 才有站点跳转。三样都是可选，所以管理端预览与笔记预览
 * 什么都不用改：块照常渲染，只是不可点、不留痕。
 */
export function MarkdownBody({
  markdown,
  className,
  mediaBaseUrl,
  entryId,
  entryTitle,
  progressAccountId,
  onAskAssistant,
  onOpenEntry,
  sandbox,
  progress: injectedProgress,
  recordAttempt,
}: {
  markdown: string
  className?: string
  /**
   * 正文里相对附件地址（`/api/v1/knowledge/files/...`）要拼上的后端基址。
   * 由调用方从自己的 `API_BASE_URL` 传进来——本包不认识部署形态，也不该认识。
   */
  mediaBaseUrl?: string
  /** 当前条目 id；有账号和条目时才记录本地阅读进度。 */
  entryId?: number
  entryTitle?: string
  progressAccountId?: string
  /** 由应用注入：把一句话交给助手输入框（不自动发送）。 */
  onAskAssistant?: (prompt: string) => void
  /** 由应用注入：学习路径里点击某站时跳转。 */
  onOpenEntry?: (entryId: number) => void
  /** 由应用注入：代码执行（WORK-015）。不传时「运行」按钮不渲染。 */
  sandbox?: SandboxPort
  /**
   * 由应用注入的进度端口（WORK-020）。不传时用本地 `localStorage` 实现——
   * 课程页会传一个"本地 + 服务端合并"的端口，管理端预览与笔记预览什么都不用改。
   */
  progress?: ProgressPort
  /** 逐题作答上报（WORK-033）；不传就只在本地判分。 */
  recordAttempt?: (input: { blockKey: string; questionKey: string; choice: number }) => void
}) {
  const container = useRef<HTMLDivElement>(null)
  // 只依赖 markdown：同一份正文的标题序列是确定的
  const headings = useMemo(() => extractHeadings(markdown), [markdown])

  useEffect(() => {
    const root = container.current
    if (root === undefined || root === null) {
      return
    }
    /*
     * 只给**正文自己的**标题回填 id。
     *
     * <p>交互块（`code-tour` 的解释、`quiz` 的题干等）内部也会渲染 markdown，其中的
     * `###` 同样是 `h1..h4` 节点，会被 `querySelectorAll` 一起抓到。按 DOM 顺序按下标
     * 分配 id 时，它们会**挤占**正文标题的 id：目录第 5 项指向的是某个代码块解释里的小标题，
     * 点下去滚到错误位置，且真正的第 5 段没有 id。
     *
     * <p>判据是"最近的带 testid 的祖先是不是容器自己"——块容器都带 `data-testid`
     * （见 `markdown/renderers/blocks.tsx`），正文标题的最近带 testid 祖先就是 `markdown-body`。
     */
    const nodes = Array.from(root.querySelectorAll<HTMLElement>('h1, h2, h3, h4')).filter(
      (node) => node.closest('[data-testid]') === root,
    )
    nodes.forEach((node, index) => {
      const heading = headings[index]
      if (heading !== undefined) {
        node.id = heading.id
      }
    })

    // 渲染器是懒加载的（Streamdown 走 Suspense）：首次执行这个 effect 时容器里往往还没有标题，
    // 等 chunk 就绪后标题才挂上来，而 Suspense 在子组件内部解析、不会让本组件重渲染，
    // 依赖 [headings] 也不会重跑——首个条目的标题就永远没有 id，目录点了不跳。
    // 用 MutationObserver 补一次：只要正文子树变化就重新按顺序回填。
    const observer = new MutationObserver(() => {
      const late = Array.from(root.querySelectorAll<HTMLElement>('h1, h2, h3, h4')).filter(
        (node) => node.closest('[data-testid]') === root && node.id === '',
      )
      late.forEach((node) => {
        const position = (
          Array.from(root.querySelectorAll<HTMLElement>('h1, h2, h3, h4')).filter(
            (candidate) => candidate.closest('[data-testid]') === root,
          ) as HTMLElement[]
        ).indexOf(node)
        const heading = headings[position]
        if (heading !== undefined) {
          node.id = heading.id
        }
      })
    })
    observer.observe(root, { childList: true, subtree: true })
    return () => observer.disconnect()
  }, [headings])

  // 必须是稳定引用：它变了会让上游的块级记忆化失效（长文每次重渲染都重解析整篇）
  const components = useMemo<MarkdownProps['components']>(
    () => ({
      img: (props) => (
        <FigureWithLightbox
          src={resolveMediaUrl(typeof props.src === 'string' ? props.src : '', mediaBaseUrl)}
          alt={typeof props.alt === 'string' ? props.alt : ''}
        />
      ),
    }),
    [mediaBaseUrl],
  )

  const localProgress = useMemo(
    () => createEntryProgressPort(progressAccountId, entryId, entryTitle),
    [progressAccountId, entryId, entryTitle],
  )
  const progress = injectedProgress ?? localProgress

  const documentContext = useMemo(
    () => ({ entryId, entryTitle, onAskAssistant, onOpenEntry, progress, sandbox, recordAttempt }),
    [entryId, entryTitle, onAskAssistant, onOpenEntry, progress, sandbox, recordAttempt],
  )

  return (
    <div
      ref={container}
      data-testid="markdown-body"
      className={className ?? 'text-[0.9375rem] leading-8'}
    >
      <Markdown markdown={markdown} components={components} documentContext={documentContext} />
    </div>
  )
}
