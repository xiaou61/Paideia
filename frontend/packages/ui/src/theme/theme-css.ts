import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'

/**
 * 主题 CSS 的解析工具（WORK-079）。
 *
 * <p>为什么需要它：皮肤一多，"这套令牌到底长什么样"就不再是两个写死的块（`:root` / `.dark`），
 * 而是 `:root` + `.dark` + 若干皮肤块的**级联结果**。对比度测试与契约测试都必须按这个语义
 * 把令牌解析出来，否则会出现"测试过了、页面是错的"。
 *
 * <p>为什么不用 jsdom/happy-dom 的 `getComputedStyle`：它们对自定义属性与 `@layer` 的级联
 * 支持不完整，而且拿不到"某个皮肤在 `.dark` 下解析成什么"（要真的挂类、真的重算）。
 * 直接解析文本反而更可控，也更容易在失败信息里指出是哪个块。
 */

export interface CssRule {
  /** 选择器列表原文（未拆分），例如 `:root[data-skin='paper'],` 换行后的组合。 */
  prelude: string
  /** 花括号内的声明原文。 */
  body: string
}

export interface SkinRule {
  id: string
  dark: boolean
  body: string
}

export interface ThemeCss {
  /** `:root { … }` 的声明原文。 */
  rootBody: string
  /** `.dark { … }` 的声明原文。 */
  darkBody: string
  /** `@theme inline { … }` 里定义的令牌名（`--radius-sm` 这类只在这里出现）。 */
  themeTokens: ReadonlySet<string>
  /** 全部皮肤块（每族一个文件，扁平收集）。 */
  skinRules: readonly SkinRule[]
}

/** 把 CSS 切成一条条顶层规则。注释先剥掉，否则注释里的花括号会打乱配对。 */
export function parseRules(css: string): CssRule[] {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const rules: CssRule[] = []
  let cursor = 0
  let preludeStart = 0
  while (cursor < clean.length) {
    const char = clean[cursor]
    if (char === '{') {
      const prelude = clean.slice(preludeStart, cursor).trim()
      let depth = 1
      let end = cursor + 1
      while (end < clean.length && depth > 0) {
        const inner = clean[end]
        if (inner === '{') {
          depth += 1
        } else if (inner === '}') {
          depth -= 1
        }
        end += 1
      }
      rules.push({ prelude, body: clean.slice(cursor + 1, end - 1) })
      // 整个块跳过去：皮肤块与 `:root` 都是顶层规则，嵌套规则（`@layer`/`@media` 里的）
      // 不是我们要断言的对象，跳过它们反而避免了"把 @media print 里的覆盖当成常态"。
      cursor = end
      preludeStart = end
      continue
    }
    if (char === '}') {
      preludeStart = cursor + 1
    }
    cursor += 1
  }
  return rules
}

/** 选择器列表拆成单个选择器（先把换行与多余空白压成一个空格）。 */
export function splitSelectors(prelude: string): string[] {
  return prelude
    .split(',')
    .map((selector) => selector.replace(/\s+/g, ' ').trim())
    .filter((selector) => selector !== '')
}

/** 一个声明块里所有 `--name:` 的名字（不带 `--`）。 */
export function declarationNames(body: string): string[] {
  const names: string[] = []
  for (const matched of body.matchAll(/--([a-z0-9-]+)\s*:/g)) {
    if (matched[1] !== undefined && !names.includes(matched[1])) {
      names.push(matched[1])
    }
  }
  return names
}

/**
 * 读本地样式文件：`src/styles/globals.css` + `src/styles/theme/skins/*.css`。
 *
 * <p>皮肤文件是**目录扫描**而不是写死清单：加一族皮肤不应该还要改测试，
 * 否则"加皮肤"的第一步就变成"先改测试"，久而久之就没人加了。
 */
export function loadThemeCss(): ThemeCss {
  const stylesDirectory = path.join(process.cwd(), 'src', 'styles')
  const globals = readFileSync(path.join(stylesDirectory, 'globals.css'), 'utf8')
  const skinDirectory = path.join(stylesDirectory, 'theme', 'skins')
  const skinSources = readdirSync(skinDirectory)
    .filter((file) => file.endsWith('.css'))
    .map((file) => readFileSync(path.join(skinDirectory, file), 'utf8'))

  let rootBody = ''
  let darkBody = ''
  const themeTokens = new Set<string>()
  const skinRules: SkinRule[] = []

  for (const rule of parseRules(globals)) {
    const selectors = splitSelectors(rule.prelude)
    if (rule.prelude.trim() === '@theme inline') {
      for (const name of declarationNames(rule.body)) {
        themeTokens.add(name)
      }
      continue
    }
    if (selectors.includes(':root')) {
      rootBody = rule.body
      continue
    }
    if (selectors.includes('.dark')) {
      darkBody = rule.body
    }
  }

  for (const source of skinSources) {
    for (const rule of parseRules(source)) {
      for (const selector of splitSelectors(rule.prelude)) {
        const matched = /^:root\[data-skin=["']?([\w-]+)["']?\](\.[\w-]+)?$/.exec(selector)
        if (matched === null || matched[1] === undefined) {
          continue
        }
        skinRules.push({
          id: matched[1],
          dark: matched[2] === '.dark',
          body: rule.body,
        })
      }
    }
  }

  if (rootBody === '' || darkBody === '') {
    throw new Error('globals.css 里找不到 :root 或 .dark 的令牌块')
  }

  return { rootBody, darkBody, themeTokens, skinRules }
}
