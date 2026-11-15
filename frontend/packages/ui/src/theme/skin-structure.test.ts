import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'

import { describe, expect, it } from 'vitest'

import { SKINS } from './skins'
import { parseRules } from './theme-css'

/**
 * 结构层白名单（WORK-080）。
 *
 * <p>WORK-079 把「皮肤」定义成**只覆盖设计令牌**：换色、换质感、换圆角。这保证了 17 个皮肤
 * 全都能被对比度测试逐条验证，但也挡住了一类用户真正想要的皮肤——粗野主义要零圆角 + 硬边，
 * 杂志排版要衬线大标题，90 年代要 outset 立体边。它们的共同点是**改形状**，不是改颜色。
 *
 * <p>放开这个口子最怕的是「皮肤顺手把布局改了」：某个皮肤把侧栏 `display: none`，用户在
 * 那个皮肤下就少了一半功能，而这类问题在 17 个皮肤里几乎不会被发现。所以这里用**白名单**
 * 而不是黑名单——默认拒绝，只放行"看起来是形状"的属性。
 *
 * <p>注意白名单只能拦"碰了布局属性"，拦不住"改得难看"。后者只能靠人眼抽查截图，这一点
 * 写在 `tasks.md` 里，不要指望测试。
 */

/** 允许的 CSS 属性。默认拒绝：没在名单里的属性一律红。 */
const ALLOWED = new Set([
  'background-color',
  'background-image',
  'background-repeat',
  'background-size',
  'box-shadow',
  'caret-color',
  'color',
  'font-family',
  'font-size',
  'font-style',
  'font-weight',
  'letter-spacing',
  'line-height',
  'mix-blend-mode',
  'opacity',
  'padding',
  'padding-bottom',
  'padding-left',
  'padding-right',
  'padding-top',
  'text-decoration-line',
  'text-decoration-thickness',
  'text-transform',
])

/**
 * 边框类：`border` + 零到两个方向段 + 宽度/样式/颜色/圆角。
 * 写成规则而不是穷举（`border-top-left-radius` 也要能过），省得以后加方向还要改名单。
 */
const BORDER = /^border(-(top|right|bottom|left|inline|block)){0,2}-(width|style|color|radius)$/

/** 明令禁止的布局属性：命中时给"为什么不行"的具体理由，比只说"不在白名单"好排查。 */
const LAYOUT = new Set([
  'position',
  'display',
  'width',
  'height',
  'z-index',
  'overflow',
  'transform',
  'inset',
  'margin',
  'top',
  'left',
  'right',
  'bottom',
])

interface Declaration {
  name: string
  value: string
}

/** 按 `;` 切声明，但跳过括号内的分号（渐变、多值函数里可能出现）。 */
function declarations(body: string): Declaration[] {
  const result: Declaration[] = []
  let depth = 0
  let start = 0
  const push = (end: number): void => {
    const chunk = body.slice(start, end).trim()
    start = end + 1
    if (chunk === '') {
      return
    }
    const colon = chunk.indexOf(':')
    if (colon < 0) {
      return
    }
    result.push({ name: chunk.slice(0, colon).trim(), value: chunk.slice(colon + 1).trim() })
  }
  for (let index = 0; index < body.length; index += 1) {
    const char = body[index]
    if (char === '(') {
      depth += 1
    } else if (char === ')') {
      depth -= 1
    } else if (char === ';' && depth === 0) {
      push(index)
    }
  }
  push(body.length)
  return result
}

/** 括号感知的选择器拆分：`:is(h1, h2, h3)` 里的逗号不是选择器分隔符。 */
function splitTopLevel(prelude: string): string[] {
  const parts: string[] = []
  let depth = 0
  let current = ''
  for (const char of prelude) {
    if (char === '(') {
      depth += 1
    } else if (char === ')') {
      depth -= 1
    }
    if (char === ',' && depth === 0) {
      parts.push(current)
      current = ''
      continue
    }
    current += char
  }
  parts.push(current)
  return parts.map((part) => part.replace(/\s+/g, ' ').trim()).filter((part) => part !== '')
}

interface StructureRule {
  file: string
  skin: string
  selector: string
  declarations: Declaration[]
}

/** 收集"结构规则"：含非自定义属性的规则。纯 `--x: value` 的令牌块不算。 */
function structureRules(): StructureRule[] {
  const directory = path.join(process.cwd(), 'src', 'styles', 'theme', 'skins')
  const rules: StructureRule[] = []
  for (const file of readdirSync(directory).filter((name) => name.endsWith('.css'))) {
    for (const rule of parseRules(readFileSync(path.join(directory, file), 'utf8'))) {
      if (rule.prelude.startsWith('@')) {
        continue
      }
      const all = declarations(rule.body)
      const structural = all.filter((declaration) => !declaration.name.startsWith('--'))
      if (structural.length === 0) {
        continue
      }
      for (const selector of splitTopLevel(rule.prelude)) {
        const matched = /^\[data-skin=["']([\w-]+)["']\]/.exec(selector)
        rules.push({ file, skin: matched?.[1] ?? '', selector, declarations: structural })
      }
    }
  }
  return rules
}

const rules = structureRules()
const skinIds = new Set(SKINS.map((skin) => skin.id))

/** `:root` 与 `@theme inline` 里出现过的令牌名，用来验证 `var()` 引用不是空指向。 */
const KNOWN = (() => {
  const directory = path.join(process.cwd(), 'src', 'styles')
  const globals = readFileSync(path.join(directory, 'globals.css'), 'utf8')
  const names = new Set<string>()
  for (const rule of parseRules(globals)) {
    for (const declaration of declarations(rule.body)) {
      if (declaration.name.startsWith('--')) {
        names.add(declaration.name)
      }
    }
  }
  return names
})()

describe('结构层白名单', () => {
  it('确实存在结构规则（防止解析静默失灵后全部断言空跑通过）', () => {
    expect(rules.length, '一条结构规则都没解析到——十有八九是解析器或文件路径坏了').toBeGreaterThan(10)
  })

  it('每条结构规则都作用域在某个具体皮肤上', () => {
    const problems = rules
      .filter((rule) => rule.skin === '')
      .map((rule) => `${rule.file} 的「${rule.selector}」没有以 [data-skin='…'] 打头，会影响到所有皮肤`)
    expect(problems).toEqual([])
  })

  it('结构规则里的皮肤 id 都是已登记的皮肤（写错就是"某个皮肤没生效"）', () => {
    const problems = rules
      .filter((rule) => rule.skin !== '' && !skinIds.has(rule.skin))
      .map((rule) => `${rule.file} 里的「${rule.selector}」指向未登记的皮肤 ${rule.skin}`)
    expect(problems).toEqual([])
  })

  it('只改形状，不改布局（布局属性一律拒绝）', () => {
    const problems: string[] = []
    for (const rule of rules) {
      for (const declaration of rule.declarations) {
        if (LAYOUT.has(declaration.name)) {
          problems.push(
            `${rule.file}「${rule.selector}」改了布局属性 ${declaration.name}——` +
              '布局必须在所有皮肤下一致，否则某些皮肤下功能会消失',
          )
          continue
        }
        if (!ALLOWED.has(declaration.name) && !BORDER.test(declaration.name)) {
          problems.push(
            `${rule.file}「${rule.selector}」用了不在白名单里的 ${declaration.name}` +
              '（要加就明确写进 skin-structure.test.ts 的 ALLOWED，别绕过）',
          )
        }
      }
    }
    expect(problems).toEqual([])
  })

  it('不用 !important 靠优先级硬压工具类', () => {
    const problems = rules
      .filter((rule) => rule.declarations.some((declaration) => declaration.value.includes('!important')))
      .map((rule) => `${rule.file}「${rule.selector}」用了 !important`)
    expect(problems).toEqual([])
  })

  it('不碰简历纸张语义（--paper*）', () => {
    const problems: string[] = []
    for (const rule of rules) {
      for (const declaration of rule.declarations) {
        if (/var\(--paper/.test(declaration.value)) {
          problems.push(`${rule.file}「${rule.selector}」的 ${declaration.name} 引用了 --paper* 令牌`)
        }
      }
    }
    expect(problems).toEqual([])
  })

  it('结构规则只覆盖字面量或既有令牌（不新造令牌名）', () => {
    const problems: string[] = []
    for (const rule of rules) {
      for (const declaration of rule.declarations) {
        for (const matched of declaration.value.matchAll(/var\((--[\w-]+)\)/g)) {
          const name = matched[1]
          if (name === undefined) {
            continue
          }
          // 结构规则只能引用令牌；`--x` 引用一个不存在的令牌等于静默失效（浏览器当无效值丢弃）
          if (!KNOWN.has(name)) {
            problems.push(`${rule.file}「${rule.selector}」引用了不存在的令牌 ${name}`)
          }
        }
      }
    }
    expect(problems).toEqual([])
  })
})
