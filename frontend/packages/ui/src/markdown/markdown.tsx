import { cn } from 'cn'
import { lazy, Suspense, useCallback, useMemo, useSyncExternalStore } from 'react'
import type { Components, CustomRenderer, PluginConfig } from 'streamdown'

import { documentRenderers, SourceReadingProvider, type DocumentContextValue } from './renderers'

/**
 * markdown 渲染的**唯一实现**（WORK-012）。
 *
 * <p><b>为什么在这里而不是某个业务包</b>：学习者端的知识库条目、管理端的草稿预览、AI 助手的回答
 * 都要按 markdown 呈现。渲染器若跟业务概念走（原先在 `@paideia/knowledge`），"助手要说知识库的话"
 * 就得让助手依赖业务包；更要紧的是**两处各留一套就会在"预览与线上不一致"上翻车**，而预览的全部
 * 价值恰恰是"看到的就是用户看到的"（AC-008）。
 *
 * <p><b>两种模式，一条管线</b>：`mode` 是上游的公开开关，不是我们分叉出来的两条路。
 * 实测其语义（读的是 `streamdown` 打包产物里那行 `mode === "streaming" && parseIncompleteMarkdown
 * ? remend(text) : text`）：**只有流式模式才做未闭合补全**，静态模式原样使用文本。所以
 * `streaming` 这个 prop 的准确含义是"**这段文字可能还没长完**"——流式回答是，边打字边预览的笔记
 * 也是；已经定稿的知识库条目不是。
 *
 * <p><b>懒加载</b>：`streamdown` 基础包实测 +343KB，而它只有"真的要渲染 markdown"时才需要。
 * 面板一打开就 {@link preloadMarkdown}，别等第一个 token 到了才开始下载。
 *
 * <p><b>样式</b>：它的类名在它自己的 `dist` 里，靠 `globals.css` 的 `@source` 登记（漏了会静默缺样式）；
 * 它依赖的 `bg-sidebar` 令牌我们必须自己补（见该文件的注释）。
 */

const Streamdown = lazy(async () => {
  const module = await import('streamdown')
  return { default: module.Streamdown }
})

/** 预热渲染器（下载它的 chunk）。面板打开时调，让下载与用户打字并行。 */
export function preloadMarkdown(): void {
  void import('streamdown')
}

/**
 * 语法高亮插件是**懒加载**的（WORK-014）。
 *
 * <p><b>为什么不能直接 `import { code } from '@streamdown/code'`</b>：这个模块是
 * `@paideia/ui` barrel 的静态导出，静态引入会把 Shiki 拉进**应用主包**——实测主 chunk
 * 从 736 kB 涨到 951 kB（+215 kB，gzip +69 kB）。所以这里用动态 import + 一个极小的
 * 订阅器：插件到达之前，代码块按纯文本渲染（与接入前一致），到达后整篇重渲染一次。
 *
 * <p>这是**有意为之的降级**，不是缺陷：先有内容、后有着色，比为了着色把主包拖慢好。
 */
type CodeHighlighter = Awaited<typeof import('@streamdown/code')>['code']

let codePlugin: CodeHighlighter | undefined
let codeLoading: Promise<void> | null = null
const codeSubscribers = new Set<() => void>()
const NOOP_UNSUBSCRIBE = () => {}

function loadCodeHighlighting(): Promise<void> {
  if (codePlugin !== undefined) {
    return Promise.resolve()
  }
  codeLoading ??= import('@streamdown/code')
    .then((module) => {
      codePlugin = module.code
      codeSubscribers.forEach((notify) => notify())
    })
    .catch(() => {
      // 加载失败就保持"无高亮"。重试留给下一次页面打开，不为它引入重试状态机
      codeLoading = null
    })
  return codeLoading
}

/**
 * 只在**这段 markdown 真的有围栏代码块**时才开始下载高亮插件。
 *
 * <p>否则每个纯文字回答都要多下 196 kB（gzip 61 kB）——那是白花。判断用一次正则，
 * 代价可以忽略；`markdown` 变化时才重算。
 */
function useCodeHighlighting(enabled: boolean): CodeHighlighter | undefined {
  const subscribe = useCallback(
    (notify: () => void) => {
      if (!enabled) {
        return NOOP_UNSUBSCRIBE
      }
      codeSubscribers.add(notify)
      void loadCodeHighlighting()
      return () => {
        codeSubscribers.delete(notify)
      }
    },
    [enabled],
  )
  return useSyncExternalStore(subscribe, readCodePlugin, readCodePlugin)
}

function readCodePlugin(): CodeHighlighter | undefined {
  return codePlugin
}

/**
 * mermaid 同样懒加载，而且**只在长文里真的出现 ```` ```mermaid ```` 时**才下载——
 * 它带的是整个 mermaid 运行时（按图类型拆成动态 chunk，但核心本身就不小）。流式回答不启用：
 * 模型输出的 mermaid 是文本，不是我们要执行的渲染指令。
 *
 * <p>`themeCSS` 只让流程图的连线**流起来**（虚线位移动画），一眼能看出方向；系统开启
 * 「减少动态效果」时在 CSS 里自行关掉。不指定 `theme`：mermaid 的配色是按主题重度定制的，
 * 写死一套在另一种主题下会很难看。已知限制：深色主题下图内仍是浅底。
 */
type MermaidPlugin = Awaited<typeof import('@streamdown/mermaid')>['mermaid']

const MERMAID_CONFIG = {
  startOnLoad: false,
  securityLevel: 'strict',
  // 默认主题是奶油黄，与站点的中性/靛蓝色板打架；neutral 是灰阶，观感接近本仓的卡片。
  // 深色主题下仍是浅底（已知限制）：跟随主题要按当前主题重建插件实例，本期未做。
  theme: 'neutral',
  themeCSS:
    '.flowchart-link{stroke-dasharray:6 4;animation:pd-flow 1.2s linear infinite}' +
    '@keyframes pd-flow{to{stroke-dashoffset:-20}}' +
    '@media (prefers-reduced-motion:reduce){.flowchart-link{animation:none;stroke-dasharray:none}}',
} as const

let mermaidPlugin: MermaidPlugin | undefined
let mermaidLoading: Promise<void> | null = null
const mermaidSubscribers = new Set<() => void>()

function loadMermaidPlugin(): Promise<void> {
  if (mermaidPlugin !== undefined) {
    return Promise.resolve()
  }
  mermaidLoading ??= import('@streamdown/mermaid')
    .then((module) => {
      mermaidPlugin = module.createMermaidPlugin({ config: MERMAID_CONFIG })
      mermaidSubscribers.forEach((notify) => notify())
    })
    .catch(() => {
      // 加载失败就保持「不渲染图」。重试留给下一次页面打开
      mermaidLoading = null
    })
  return mermaidLoading
}

function useMermaidPlugin(enabled: boolean): MermaidPlugin | undefined {
  const subscribe = useCallback((notify: () => void) => {
    if (!enabled) {
      return NOOP_UNSUBSCRIBE
    }
    mermaidSubscribers.add(notify)
    void loadMermaidPlugin()
    return () => {
      mermaidSubscribers.delete(notify)
    }
  }, [enabled])
  return useSyncExternalStore(subscribe, readMermaidPlugin, readMermaidPlugin)
}

function readMermaidPlugin(): MermaidPlugin | undefined {
  return mermaidPlugin
}

/** 认得出「这一行起了一个 mermaid 围栏」就够了；具体图由插件自己解析。 */
const MERMAID_FENCE = /(^|\n)\s*```mermaid\b/

/**
 * 静态（长文）模式的呈现开关。**这两个是模块常量，不是每次渲染新建的字面量**——
 * 上游那个组件的记忆化比较里含 props 的引用相等，随手新建的对象会白让它重算一遍顶层。
 */
const DOCUMENT_CONTROLS = { table: false }
const DOCUMENT_LINK_SAFETY = { enabled: false }

/**
 * 一种围栏语言 → 我们自己的渲染器。
 *
 * <p>这是上游的公开扩展点（`plugins.renderers`），**加一种我们平台的元素不需要新依赖**：
 * 将来 ` ```面试评分 ` 或 ` ```架构图 ` 想渲染成平台特有的卡片，就是往这里加一项。
 */
export type MarkdownRenderer = CustomRenderer

export type MarkdownProps = {
  markdown: string
  /** 这段文字**可能还没长完**（流式回答、正在输入的预览）→ 走补全与动效。默认静态。 */
  streaming?: boolean
  className?: string
  /**
   * 调用方追加/覆盖的元素渲染（如知识库给图片挂灯箱）。
   * **必须是稳定引用**（`useMemo` 或模块常量）：它变了会让上游的块级记忆化失效，
   * 于是每个增量都重解析整篇——那正是本项要避免的事。
   */
  components?: Components
  /** 平台特有的围栏渲染器。同样是稳定引用。 */
  renderers?: MarkdownRenderer[]
  /**
   * 源码解读块需要的页级能力（WORK-014）：当前条目、问 AI、跳转条目、进度端口。
   * **只在文档模式生效**；流式回答不启用这些块，也不需要它。
   */
  documentContext?: DocumentContextValue
  /**
   * 是否启用源码解读的五种块。默认启用；解释文本的嵌套渲染传 `false`，
   * 否则 ```` ```code-tour ```` 出现在解释里会被当成组件递归渲染。
   */
  documentBlocks?: boolean
}

/**
 * 长文阅读的排版预设。
 *
 * <p>这些类原先写在知识库条目的正文里，是照"15px 正文、中文长文"调过的：标题与正文拉开距离，
 * 行高 8（`leading-8`）。**它只覆盖排版，不覆盖代码块与表格的呈现**——代码块/表格是上游的
 * 结构化组件（带复制按钮、行号、横向滚动），两种模式共用同一套，这才是 AC-008 说的"单一实现"。
 */
const documentComponents: Components = {
  h1: ({ node, ...rest }) => <h1 className="mt-12 mb-4 text-2xl font-semibold" {...rest} />,
  h2: ({ node, ...rest }) => <h2 className="mt-10 mb-3 text-xl font-semibold" {...rest} />,
  h3: ({ node, ...rest }) => <h3 className="mt-8 mb-2 text-lg font-semibold" {...rest} />,
  h4: ({ node, ...rest }) => <h4 className="mt-6 mb-2 text-base font-semibold" {...rest} />,
  p: ({ node, ...rest }) => <p className="my-4 leading-8" {...rest} />,
  ul: ({ node, ...rest }) => <ul className="my-4 list-disc space-y-1.5 pl-6" {...rest} />,
  ol: ({ node, ...rest }) => <ol className="my-4 list-decimal space-y-1.5 pl-6" {...rest} />,
  // 引用块不用左侧色条：粗色边是 AI 生成界面最好认的标志（impeccable 检测器报 side-tab）。
  // 用整块浅底 + 圆角表达"这是引文"，靠背景区分而不是靠一条竖线。
  blockquote: ({ node, ...rest }) => (
    <blockquote
      className="bg-muted/60 text-muted-foreground my-6 rounded-lg px-5 py-1 [&>p:first-child]:mt-3 [&>p:last-child]:mb-3"
      {...rest}
    />
  ),
  a: ({ node, ...rest }) => (
    <a {...rest} target="_blank" rel="noreferrer noopener" className="underline underline-offset-2" />
  ),
}

/**
 * @example
 * // 流式回答（AI 助手）
 * <Markdown markdown={content} streaming={isGenerating} className="text-sm" />
 *
 * @example
 * // 定稿的长文（知识库条目）
 * <Markdown markdown={entry.body} />
 */
export function Markdown({
  markdown,
  streaming = false,
  className,
  components,
  renderers,
  documentContext,
  documentBlocks = true,
}: MarkdownProps) {
  const hasFence = useMemo(() => /(^|\n)\s*```/.test(markdown), [markdown])
  const hasMermaid = useMemo(() => MERMAID_FENCE.test(markdown), [markdown])
  const codePlugin = useCodeHighlighting(hasFence)
  const mermaidPlugin = useMermaidPlugin(!streaming && hasMermaid)
  // 合并后的对象身份必须稳定：它进上游的块级记忆化比较，每次渲染换一个新对象会让记忆化全失效
  const mergedComponents = useMemo(
    () => (streaming ? components : { ...documentComponents, ...components }),
    [streaming, components],
  )
  /**
   * `plugins` 的引用同样会被上游记忆化比较。`codePlugin` 到达之前为 undefined（无高亮），
   * 到达后换一次引用、整篇重渲染——这正是"先内容后着色"的落点。
   * **流式模式不带任何平台块**：模型输出的 `code-tour` 只是文本，不该被当组件执行。
   */
  const plugins = useMemo<PluginConfig | undefined>(() => {
    const highlight = codePlugin === undefined ? {} : { code: codePlugin }
    const diagram = mermaidPlugin === undefined ? {} : { mermaid: mermaidPlugin }
    if (streaming) {
      return renderers === undefined ? (codePlugin === undefined ? undefined : highlight) : { ...highlight, renderers }
    }
    const merged = documentBlocks ? [...documentRenderers, ...(renderers ?? [])] : (renderers ?? [])
    return { ...highlight, ...diagram, renderers: merged }
  }, [streaming, renderers, documentBlocks, codePlugin, mermaidPlugin])

  /**
   * 解释文本的嵌套渲染入口：把"用同一套渲染器"这件事实现在**这一处**，块组件不必依赖
   * `markdown.tsx`（那会形成循环引用）。嵌套渲染关闭平台块，防止递归。
   */
  const renderText = useCallback(
    (text: string) =>
      text.includes('```') || text.includes('`') ? (
        <Markdown markdown={text} documentBlocks={false} />
      ) : (
        <p className="whitespace-pre-wrap">{text}</p>
      ),
    [],
  )

  const content = (
    <Streamdown
      mode={streaming ? 'streaming' : 'static'}
      isAnimating={streaming}
      className={className}
      components={mergedComponents}
      plugins={plugins}
      // 长文里的外链是管理员写进去的，直接打开；流式回答里的链接是模型生成的，走上游的确认弹窗
      linkSafety={streaming ? undefined : DOCUMENT_LINK_SAFETY}
      // 长文里不需要表格的复制/下载下拉——那是聊天场景里"把我给你的表拿走"才需要的
      controls={streaming ? undefined : DOCUMENT_CONTROLS}
    >
      {markdown}
    </Streamdown>
  )

  // 只有文档模式且有平台块时才需要页级上下文；流式回答与嵌套渲染都不包，少一层无关状态
  const wrapped =
    streaming || !documentBlocks ? content : (
      <SourceReadingProvider value={documentContext} renderText={renderText}>
        {content}
      </SourceReadingProvider>
    )

  return <Suspense fallback={<MarkdownLoading className={className} markdown={markdown} />}>{wrapped}</Suspense>
}

/**
 * 渲染器还没下载完时的样子：**纯文本**。
 *
 * <p>这不是"随便给个占位"——换渲染器之前，助手回答本来就是纯文本，所以这里恰好是**上一版的真实
 * 行为**：网络慢时用户看到的是自己上一版看到的东西，而不是空白或骨架屏。文字内容一字不少，
 * 排版晚到而已。
 */
export function MarkdownLoading({ markdown, className }: { markdown: string; className?: string }) {
  return (
    <div className={cn('whitespace-pre-wrap', className)} data-testid="markdown-loading">
      {markdown}
    </div>
  )
}
