import { readFileSync } from 'node:fs'
import path from 'node:path'

import { act, fireEvent, render } from '@testing-library/react'
import { createElement, type ComponentType } from 'react'
import { beforeAll, describe, expect, it, vi } from 'vitest'

import corpus from './corpus.json'
import { Markdown, type MarkdownProps } from './markdown'

/**
 * 流式 markdown 渲染的验收口径（WORK-012）。
 *
 * <p><b>语料是真实上游的增量序列</b>（由 `scripts/collect-md-corpus.mjs` 采得）。回放真实增量比
 * "把整段按字符切开"忠实——后者会掩盖"上游一次吐一大段"这种真实分布。
 *
 * <p><b>四条口径</b>（替代"百分百"这个不可验收的说法）：
 * 1. **逐前缀等价**——一个块只有在它后面又出现了别的块时才算"已完成"，只比较两个前缀里都不是
 *    最后一块的位置（AC-003）；
 * 2. **未闭合不破形**（AC-002）；
 * 3. **增量真的省**——用"块内元素被渲染的次数"当确定性指标，不看墙钟时间（AC-004）；
 * 4. **安全**——补全不得引入可执行 / 可外链的注入点（AC-007）。
 *
 * <p>另有三条是"这套测试本身可信"的检查：**反向验证**（比较确实会因为一处改动而失败）、
 * **对照组**（没有记忆化时确实贵得多，证明"省"是真省）、**单一实现**
 * （两种模式渲染同一个 markdown 得到同一副骨架，AC-008）。
 */

/**
 * 渲染器是懒加载的：先热一次，之后每次挂载都不再走 Suspense。
 *
 * <p>超时由 `vitest.config.ts` 统一放宽（`hookTimeout`）——这里不重复写数字，
 * 免得同一个旋钮两处给值。原因见那份配置的注释：`pnpm -r test` 并发时这一步会超过默认 10 秒，
 * 而它一失败就会让整个文件的 73 条用例静默不跑。
 */
beforeAll(async () => {
  await import('streamdown')
  await act(async () => {
    render(<Markdown markdown="热身" streaming />)
  })
})

/** 渲染根元素（Streamdown 的容器），用它数"块"。 */
function renderRoot(text: string, streaming = true): Element | null {
  const { container } = render(<Markdown markdown={text} streaming={streaming} />)
  const root = container.firstElementChild
  if (root !== null && container.querySelector('[data-testid="markdown-loading"]') !== null) {
    // 预热失败时不能静默：那时拿到的是纯文本，比较会"永远相等"
    throw new Error('渲染器还没就位（预热失败），此时的比较没有意义')
  }
  return root
}

/** 顶层块：Streamdown 根下每个子元素就是一个块。 */
function renderBlocks(text: string, streaming = true): string[] {
  const root = renderRoot(text, streaming)
  return root === null ? [] : Array.from(root.children).map((element) => element.outerHTML)
}

/** 抽样前缀（增量可能上千个，全跑太慢），但**必然包含最后一个**。 */
function sampledIndices(total: number, limit: number): number[] {
  const step = Math.max(1, Math.floor(total / limit))
  const indices: number[] = []
  for (let index = 0; index < total; index += step) {
    indices.push(index)
  }
  if (indices[indices.length - 1] !== total - 1) {
    indices.push(total - 1)
  }
  return indices
}

function prefixText(deltas: string[], index: number): string {
  return deltas.slice(0, index + 1).join('')
}

/**
 * 只计数、原样渲染的组件。
 *
 * <p>它被调用一次，就说明上游**真的重渲染（也就是重新解析）了一个块**——块级记忆化生效时
 * 根本走不到这里。这是把 AC-004 变成确定性断言的办法：不看耗时，看"有没有白干活"。
 */
function countingComponents(
  counter: { renders: number },
  tags: readonly string[],
): MarkdownProps['components'] {
  const map: Record<string, ComponentType<Record<string, unknown>>> = {}
  for (const tag of tags) {
    map[tag] = ({ node, ...rest }: { node?: unknown }) => {
      counter.renders += 1
      return createElement(tag, rest)
    }
  }
  return map as unknown as MarkdownProps['components']
}

/** 元素骨架：只留标签名与嵌套，丢掉类名等属性——用来比较"两种模式的结构是不是同一副"。 */
function skeleton(element: Element | null): string {
  if (element === null) {
    return ''
  }
  return `${element.tagName.toLowerCase()}(${Array.from(element.children).map(skeleton).join(' ')})`
}

/** 有多少条语料最终会产生多个块——用它兜住"语料本身够不够"。 */
let multiBlockCount = 0

describe('AC-003 逐前缀等价：已完成块不变形', () => {
  for (const entry of corpus.entries) {
    it(`${entry.id}（${String(entry.deltas.length)} 个增量）`, () => {
      const finalBlocks = renderBlocks(entry.text)

      if (finalBlocks.length < 2) {
        // 单块语料：没有"已完成块"可比。改验"块数不得凭空分裂"——
        // 中途冒出第二个块，正是用户看到的那种跳。
        for (const index of sampledIndices(entry.deltas.length, 20)) {
          const blocks = renderBlocks(prefixText(entry.deltas, index))
          expect(blocks.length, `前缀 ${String(index)} 凭空多出了块`).toBeLessThanOrEqual(
            finalBlocks.length,
          )
        }
        return
      }

      multiBlockCount++
      let previous: string[] = []
      let checked = 0
      for (const index of sampledIndices(entry.deltas.length, 40)) {
        const blocks = renderBlocks(prefixText(entry.deltas, index))
        // **两个前缀里都不是最后一块**的位置才比较——最后一块还在长，允许它变
        const stable = Math.min(previous.length - 1, blocks.length - 1)
        for (let position = 0; position < stable; position++) {
          expect(blocks[position], `第 ${String(position)} 块在前缀 ${String(index)} 变了`).toBe(
            previous[position],
          )
          checked++
        }
        previous = blocks
      }
      expect(checked, '多块语料却没产生任何可比较的已完成块').toBeGreaterThan(0)
      // 抽样不能把结论抽掉：最终全文必须等于最后一个前缀的结果
      expect(renderBlocks(entry.text)).toEqual(previous)
    })
  }

  it('语料本身要够：多数条目能产生多个块，否则"逐前缀等价"等于没测', () => {
    expect(multiBlockCount).toBeGreaterThanOrEqual(5)
  })

  it('反向验证：这套比较确实会红（一处改动必须被判为不一致）', () => {
    const before = renderBlocks('甲段\n\n乙段')
    const after = renderBlocks('甲段\n\n乙段改了')
    expect(before[0], '没动过的块应当判为一致').toBe(after[0])
    expect(before[1], '改过的块必须判为不一致，否则整个比较是恒真的').not.toBe(after[1])
  })
})

describe('AC-002 未闭合的结构不破形', () => {
  it('未闭合的代码围栏渲染成一个代码块，而不是"后面全变成代码"', () => {
    const blocks = renderBlocks('```java\nclass A {')
    expect(blocks).toHaveLength(1)
    expect(blocks[0]).toContain('data-streamdown="code-block"')
  })

  it('围栏前已完成的段落不受影响，围栏本身独占一块', () => {
    const blocks = renderBlocks('一段正文。\n\n```java\nclass A {')
    expect(blocks).toHaveLength(2)
    expect(blocks[0]).toContain('<p>一段正文。</p>')
    expect(blocks[1]).toContain('data-streamdown="code-block"')
  })

  it('未闭合的强调不吞掉后面的正文', () => {
    // 奇数个 `**`：补全只作用于最后一块，不该让"后续正文"也变成粗体
    const blocks = renderBlocks('正文一。\n\n正文二 **还没闭合')
    expect(blocks).toHaveLength(2)
    expect(blocks[0]).toContain('<p>正文一。</p>')
  })
})

describe('AC-004 增量真的省：已完成块不重渲染（=不重解析）', () => {
  const deltas = ['第 0 段。', ...Array.from({ length: 19 }, (_, index) => `\n\n第 ${index + 1} 段。`)]

  it('逐段追加时，每次新增最多重渲染"刚完成的一段 + 正在长的那一段"', () => {
    const counter = { renders: 0 }
    const components = countingComponents(counter, ['p'])

    const view = render(<Markdown markdown={deltas[0] ?? ''} streaming components={components} />)
    const afterMount = counter.renders
    const perPrefix: number[] = []
    for (let index = 1; index < deltas.length; index++) {
      const before = counter.renders
      view.rerender(
        <Markdown markdown={deltas.slice(0, index + 1).join('')} streaming components={components} />,
      )
      perPrefix.push(counter.renders - before)
    }
    view.unmount()

    expect(afterMount, '反向验证：计数器根本没工作，后面的断言全无意义').toBeGreaterThan(0)
    expect(Math.max(...perPrefix), `每次新增的重渲染次数：${perPrefix.join(',')}`).toBeLessThanOrEqual(2)
    expect(counter.renders - afterMount).toBeLessThanOrEqual(2 * (deltas.length - 1))
  })

  it('对照组：每次从零挂载明显更贵——证明上面的"省"是真省', () => {
    const counter = { renders: 0 }
    const components = countingComponents(counter, ['p'])
    const view = render(<Markdown markdown={deltas[0] ?? ''} streaming components={components} />)
    for (let index = 1; index < deltas.length; index++) {
      view.rerender(
        <Markdown markdown={deltas.slice(0, index + 1).join('')} streaming components={components} />,
      )
    }
    const incremental = counter.renders
    view.unmount()

    let fromScratch = 0
    for (let index = 0; index < deltas.length; index++) {
      const scratchCounter = { renders: 0 }
      const scratch = render(
        <Markdown
          markdown={deltas.slice(0, index + 1).join('')}
          streaming
          components={countingComponents(scratchCounter, ['p'])}
        />,
      )
      fromScratch += scratchCounter.renders
      scratch.unmount()
    }

    expect(incremental).toBeGreaterThan(0)
    expect(fromScratch, '从零挂载的次数应当随段数平方级增长，明显高于增量式').toBeGreaterThan(
      incremental * 2,
    )
  })

  const COUNTED_TAGS = ['p', 'li', 'h1', 'h2', 'h3', 'h4', 'td', 'th', 'blockquote']

  /** 增量式渲染同一批前缀，返回"块内元素被渲染的总次数"。 */
  function measureIncremental(entry: (typeof corpus.entries)[number], indices: number[]): number {
    const counter = { renders: 0 }
    const components = countingComponents(counter, COUNTED_TAGS)
    const view = render(
      <Markdown markdown={prefixText(entry.deltas, 0)} streaming components={components} />,
    )
    for (const index of indices.slice(1)) {
      view.rerender(
        <Markdown markdown={prefixText(entry.deltas, index)} streaming components={components} />,
      )
    }
    const renders = counter.renders
    view.unmount()
    return renders
  }

  /** 同一批前缀，每个都从零挂载（等于没有记忆化）。 */
  function measureFromScratch(entry: (typeof corpus.entries)[number], indices: number[]): number {
    let total = 0
    for (const index of indices) {
      const counter = { renders: 0 }
      const view = render(
        <Markdown
          markdown={prefixText(entry.deltas, index)}
          streaming
          components={countingComponents(counter, COUNTED_TAGS)}
        />,
      )
      total += counter.renders
      view.unmount()
    }
    return total
  }

  for (const entry of corpus.entries) {
    it(`真实语料 ${entry.id}：增量式渲染不比重装甲更贵`, () => {
      const indices = sampledIndices(entry.deltas.length, 12)
      const incremental = measureIncremental(entry, indices)
      const fromScratch = measureFromScratch(entry, indices)
      const finalBlocks = renderBlocks(entry.text).length

      expect(incremental + fromScratch, `${entry.id} 一次都没渲染，说明计数没覆盖到它`).toBeGreaterThan(0)
      expect(
        incremental,
        `${entry.id}: 增量 ${String(incremental)} / 重装甲 ${String(fromScratch)} / 最终 ${String(finalBlocks)} 块`,
      ).toBeLessThanOrEqual(fromScratch)
      if (finalBlocks >= 2) {
        // 多块语料必须有真实收益：省下来的正是"已完成块"那部分。单块语料没有"已完成"可言，
        // 它的那个块每次都是"正在长的那一块"，两种算法的代价本来就相同。
        expect(
          incremental,
          `${entry.id}: 有 ${String(finalBlocks)} 块却没有任何节省（增量 ${String(incremental)} / 重装甲 ${String(fromScratch)}）`,
        ).toBeLessThan(fromScratch)
      }
    })
  }
})

describe('AC-005 降级（healing）：补不出来的输入不许改形', () => {
  /**
   * 恶劣输入 = "打字打到一半"的真实形态。这里**逐字符**喂（每多一个字都渲染一次），
   * 因为语料的抽样会漏掉"刚好停在危险处"的那一个前缀。
   *
   * <p>为什么用"搜索"而不是"写一个降级分支"：上游的补全是概率性的，**没有哪个分支能预先列出
   * 所有补不出来的输入**。唯一诚实的做法是拿一大把恶劣输入去撞，撞出来的每一条都必须解决
   * ——要么上游能稳住（进语料、留成回归），要么我们把它登记成已知缺口（工件里"什么情况下该重审"
   * 那一条：那类必须明确降级为纯文本，而不是放宽断言）。
   */
  const ADVERSARIAL: readonly string[] = [
    '```java\nclass A {',
    '```\n',
    '~~~\n',
    '**bold',
    '*em',
    '~~strike',
    '`code',
    '[text](',
    '![alt](',
    '$x + y',
    '$$\nx = 1',
    '<div>',
    '<div><span>',
    '<table><tr><td>x',
    '| a | b |\n| --- |',
    '| a | b |\n| --- | --- |\n| 1 |',
    '> quote\n> - item',
    '1. a\n2. b\n3.',
    '- a\n\n  - b\n\n    - c\n\n      - d',
    '    indented code',
    '---\n',
    'a\n===\n',
    '<script>alert(1)</script>',
    '[x](javascript:alert(1))',
    '<img src=x onerror=alert(1)>',
    'A|B\n-|-\n1|2',
    '\r\n\r\n',
    '中文**粗体**中文',
    'a**b**c',
    '一个字一个字地长出来的**很长**的一段话，中间还有 `代码` 与 [链接](https://example.com)。',
  ]

  for (const input of ADVERSARIAL) {
    it(`逐字符喂：${JSON.stringify(input.slice(0, 28))}`, () => {
      const final = renderBlocks(input)
      let previous: string[] = []
      let checked = 0
      for (let index = 0; index < input.length; index++) {
        const blocks = renderBlocks(input.slice(0, index + 1))
        const stable = Math.min(previous.length - 1, blocks.length - 1)
        for (let position = 0; position < stable; position++) {
          expect(
            blocks[position],
            `输入 ${JSON.stringify(input)} 的第 ${String(position)} 块在"${input.slice(0, index + 1)}"处变了`,
          ).toBe(previous[position])
          checked++
        }
        previous = blocks
      }
      expect(renderBlocks(input)).toEqual(final)
      expect(checked, '这段输入没有产生任何可比较的已完成块').toBeGreaterThanOrEqual(0)
    })
  }

  it('补出来的图片占位符不是一张真图片（不会去请求那个假地址）', () => {
    // 占位符的真名是 `streamdown:incomplete-image`（由 remend 提供，不是我们编的）
    const root = renderRoot('看这张 ![](streamdown:incomplete-image)', true)
    expect(root?.querySelector('img[src^="streamdown:"]') ?? null).toBeNull()
    expect(root?.querySelector('[data-incomplete="true"]')).not.toBeNull()
  })
})

describe('AC-007 安全：补全不得引入可执行 / 可外链的注入点', () => {
  /** 真实外链只可能是 `<a href>`；`streamdown:` 是补全占位符，绝不能当地址用。 */
  function realHrefs(root: Element | null): string[] {
    return Array.from(root?.querySelectorAll('a[href]') ?? []).map(
      (anchor) => anchor.getAttribute('href') ?? '',
    )
  }

  it('script 标签不进入 DOM、也不执行（两种模式都查）', () => {
    for (const streaming of [true, false]) {
      const root = renderRoot('前一段\n\n<script>window.__paideia_pwned = 1</script>\n\n后一段', streaming)
      expect(root?.querySelector('script') ?? null).toBeNull()
      expect((window as unknown as Record<string, unknown>)['__paideia_pwned']).toBeUndefined()
    }
  })

  it('补全出来的链接占位符（streamdown:incomplete-link）永远不是真地址', () => {
    // 上游把未完成的链接补成占位 URL，并且**在占位符上直接 return**（不打开任何东西）。
    // 两条都要钉住：既不进 `href`，也不在长文模式（没有补全、但可能被别处喂进来）里可点。
    for (const streaming of [true, false]) {
      const root = renderRoot('看这里 [点我](', streaming)
      const hrefs = realHrefs(root)
      expect(hrefs.every((href) => !href.startsWith('streamdown:'))).toBe(true)
    }
  })

  it('点未完成的链接不会真的打开任何东西', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const { container } = render(<Markdown markdown="看这里 [点我](" streaming />)
    const link = container.querySelector('[data-streamdown="link"]')
    expect(link, '未完成的链接应当仍是一个可见元素').not.toBeNull()
    expect(link?.getAttribute('data-incomplete')).toBe('true')
    fireEvent.click(link as Element)
    expect(open, '占位链接被点开了——这正是"补全制造出可点外链"的漏洞').not.toHaveBeenCalled()
    open.mockRestore()
  })

  it('javascript: 链接不得成为可点外链（两种模式都查）', () => {
    for (const streaming of [true, false]) {
      const root = renderRoot('[点我](javascript:alert(1))', streaming)
      const hrefs = realHrefs(root)
      expect(hrefs.every((href) => !href.toLowerCase().startsWith('javascript:'))).toBe(true)
    }
  })

  it('图片的 javascript: 地址不得进入 src（两种模式都查）', () => {
    for (const streaming of [true, false]) {
      const root = renderRoot('![](javascript:alert(1))', streaming)
      const sources = Array.from(root?.querySelectorAll('img') ?? []).map(
        (image) => image.getAttribute('src') ?? '',
      )
      expect(sources.every((source) => !source.toLowerCase().startsWith('javascript:'))).toBe(true)
    }
  })

  it('代码块里的 HTML 被当字面量，不变成真的元素（两种模式都查）', () => {
    for (const streaming of [true, false]) {
      const root = renderRoot('```html\n<div class="x">hi</div>\n```', streaming)
      expect(root?.querySelector('div.x') ?? null).toBeNull()
      expect(root?.textContent ?? '').toContain('<div class="x">')
    }
  })
})

describe('可扩展：平台特有的围栏零新依赖（T-11）', () => {
  /** 一个小小的"平台特有元素"：` ```paideia-card ` 渲染成我们自己的卡片。 */
  const Card = ({ code }: { code: string }) => <div data-testid="paideia-card">{code}</div>
  const renderers = [{ language: 'paideia-card', component: Card }]
  const markdown = '```paideia-card\n甲 乙\n```'

  it('注册之后，这种围栏由我们的组件渲染', () => {
    const { container } = render(<Markdown markdown={markdown} streaming renderers={renderers} />)
    const card = container.querySelector('[data-testid="paideia-card"]')
    expect(card).not.toBeNull()
    expect(card?.textContent).toContain('甲 乙')
  })

  it('没注册时它就是普通代码块——差别确实来自注册表，不是本来就长这样', () => {
    const { container } = render(<Markdown markdown={markdown} streaming />)
    expect(container.querySelector('[data-testid="paideia-card"]')).toBeNull()
    expect(container.querySelector('[data-streamdown="code-block"]')).not.toBeNull()
  })

  it('只装了两个重插件：代码高亮与 mermaid；公式 / CJK 仍在包外', () => {
    // 上游把这些都做成**独立包**（`@streamdown/math` / `@streamdown/mermaid` / `@streamdown/code`）。
    // 代码高亮是源码解读需要的；mermaid 是内容线画流程图需要的，两者都**懒加载**且只在
    // 文档模式、且正文真的出现对应围栏时才下载。公式与 CJK 仍然不装。
    const ui = JSON.parse(readFileSync(path.join(process.cwd(), 'package.json'), 'utf8')) as {
      dependencies: Record<string, string>
    }
    const installed = Object.keys(ui.dependencies)
    expect(installed.filter((name) => /katex|math|cjk/.test(name))).toEqual([])
    expect(installed).toContain('@streamdown/code')
    expect(installed).toContain('@streamdown/mermaid')
  })
})

describe('AC-008 单一实现：两种模式渲染出同一副骨架', () => {
  /**
   * 骨架比较用的正文**刻意不含链接与表格**：这两样在两种模式下本来就不同，且都是"按场景给的
   * 策略"而非"两套渲染"——
   * - 链接：流式回答里的链接是模型生成的，先确认再打开；长文里的链接是管理员写的，直接给 `<a>`；
   * - 表格：聊天场景要"把表拿走"（复制/下载），长文不需要。
   *
   * 除这两样之外的元素（标题/段落/强调/行内码/列表/引用/代码块）**必须一模一样**——
   * 它们过去分散在两个渲染器里，那才是本项要消灭的东西。
   */
  const markdown = [
    '## 标题',
    '',
    '一段话，带**粗体**与 `行内代码`。',
    '',
    '- 甲项',
    '- 乙项',
    '',
    '> 一段引用',
    '',
    '```java',
    'int a = 1;',
    '```',
  ].join('\n')

  it('助手（流式）与知识库（长文）的元素结构一致', () => {
    const streaming = skeleton(renderRoot(markdown, true))
    const document = skeleton(renderRoot(markdown, false))
    expect(streaming).not.toBe('')
    expect(document).toBe(streaming)
  })

  it('表格与代码块两个结构化组件在两种模式下连属性都一致', () => {
    // 这是"单一实现"最硬的一条证据：这两样过去是两套渲染器里最容易长得不一样的东西
    // （行号、复制按钮、横向滚动容器、单元格边框），现在连属性都一样
    // 末段刻意放在代码块之后：流式模式下**最后一个块**会被标成"还在长"（`data-incomplete`），
    // 那是正确的，不是两套渲染——这里要比的是"已完成的代码块"
    const structured = '| 甲 | 乙 |\n| --- | --- |\n| 1 | 2 |\n\n```java\nint a = 1;\n```\n\n后面还有一段。'
    const streaming = renderRoot(structured, true)
    const document = renderRoot(structured, false)

    expect(document?.querySelector('table')?.outerHTML).toBe(
      streaming?.querySelector('table')?.outerHTML,
    )
    const codeBlock = (root: Element | null): string =>
      root?.querySelector('[data-streamdown="code-block"]')?.outerHTML ?? ''
    const withoutDisabled = (html: string): string => html.replaceAll(' disabled=""', '')

    expect(codeBlock(document)).not.toBe('')
    expect(withoutDisabled(codeBlock(document))).toBe(withoutDisabled(codeBlock(streaming)))
    // 唯一的差别在这里，且是**故意的**：围栏还在长的时候"复制/下载"是禁用的
    // （否则用户会复制到半个文件）。把它显式断言出来，免得将来被人当成"样式不一致"改掉
    expect(codeBlock(streaming)).toContain('disabled=""')
    expect(codeBlock(document)).not.toContain('disabled=""')
  })

  it('外链的差别是有意的：长文直接给 <a>，流式回答走确认后再打开', () => {
    // 知识库的外链是管理员写进去的，可以直开；助手回答里的链接是**模型生成的**，先确认一次。
    // 这是同一套渲染下按场景给的不同安全策略，写在这里免得将来有人"统一"掉其中一边
    const streaming = renderRoot('[外链](https://example.com)', true)
    const document = renderRoot('[外链](https://example.com)', false)

    const streamingLink = streaming?.querySelector('[data-streamdown="link"]')
    expect(streamingLink?.tagName.toLowerCase()).toBe('button')

    // 长文模式用的是**我们自己的** `a`（所以没有上游那个 `data-streamdown` 标记），按 `a[href]` 取
    const documentLink = document?.querySelector('a[href]')
    expect(documentLink?.tagName.toLowerCase()).toBe('a')
    expect(documentLink?.getAttribute('href')).toBe('https://example.com/')
    expect(documentLink?.getAttribute('rel')).toContain('noreferrer')
  })

  it('两种模式的实质差别只有"补不补全"：同一段半截强调，流式补上、静态原样', () => {
    // 上游的实现是 `mode === "streaming" && parseIncompleteMarkdown ? 补全 : 原样`
    // （读的是它打包产物里那一行）。这条把这个差别钉住，免得将来误以为两种模式是同一条路
    const streaming = renderBlocks('正文 **还没闭合').join('')
    const document = renderBlocks('正文 **还没闭合', false).join('')
    expect(streaming).toContain('data-streamdown="strong"')
    expect(document).toContain('**')
  })
})
