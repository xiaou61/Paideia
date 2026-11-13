import { afterEach, describe, expect, it } from 'vitest'

import { DEFAULT_SKIN, SKIN_STORAGE_KEY, THEME_STORAGE_KEY } from './keys'
import { SKIN_FAMILIES, SKINS, isSkin } from './skins'
import { declarationNames, loadThemeCss } from './theme-css'
import { themeInitPlugin } from './vite-plugin'

/**
 * 皮肤契约（WORK-079）。
 *
 * <p>这组断言存在的理由很实际：皮肤是会一直增加的数据，而"新增皮肤"这件事必须**只能错在测试里**，
 * 不能错在页面上。四类错误全部在这里拦：
 *
 * <ol>
 *   <li>亮色块写了、暗色块漏了 → 暗色下"半新半旧"，典型症状是深底配深字；</li>
 *   <li>令牌名拼错（`--muteds-foreground`）→ CSS 不报错、不生效，看起来像"皮肤没应用"；</li>
 *   <li>CSS 有块但注册表没登记 → 用户永远选不到（死代码）；</li>
 *   <li>注册表登记了但 CSS 没块 → 点了没反应。</li>
 * </ol>
 *
 * <p>分辨率要求：失败信息必须点名到"哪个皮肤、哪个令牌"，否则 17 个皮肤里找出那一个要重新读一遍 CSS。
 */
const css = loadThemeCss()

/** 已知令牌名 = `:root` 里定义的 + `@theme inline` 里定义的（`--radius-sm` 这类派生令牌只在后者出现）。 */
const knownTokens = new Set([...declarationNames(css.rootBody), ...css.themeTokens])

/**
 * 不许皮肤覆盖的令牌。
 *
 * <p>`--paper*` 是简历纸张（WORK-018）：纸永远白、字永远黑。如果某个皮肤顺手把它改成米色，
 * 打印出来与线上简历就变了色，而那种变化没人会去抽查 PDF。
 */
const RESERVED = (name: string): boolean => name.startsWith('paper')

function blockOf(id: string, dark: boolean) {
  return css.skinRules.find((rule) => rule.id === id && rule.dark === dark)
}

describe('皮肤注册表', () => {
  it('id 与名称都唯一', () => {
    const ids = SKINS.map((skin) => skin.id)
    const names = SKINS.map((skin) => skin.name)
    expect(new Set(ids).size, `皮肤 id 重复了：${ids.join(', ')}`).toBe(ids.length)
    expect(new Set(names).size, `皮肤名称重复了：${names.join(', ')}`).toBe(names.length)
  })

  it('默认皮肤在注册表里，且没有 CSS 块（它就是 :root 本身）', () => {
    expect(SKINS.some((skin) => skin.id === DEFAULT_SKIN)).toBe(true)
    expect(
      css.skinRules.filter((rule) => rule.id === DEFAULT_SKIN),
      '默认皮肤不该有皮肤块：它等价于"什么都不覆盖"。给它写块会让默认主题与 globals.css 里那套值分叉',
    ).toEqual([])
  })

  it('每个皮肤的 family 都在 SKIN_FAMILIES 里', () => {
    const known = new Set(SKIN_FAMILIES.map((family) => family.id))
    for (const skin of SKINS) {
      expect(known.has(skin.family), `${skin.id} 的 family「${skin.family}」没有在 SKIN_FAMILIES 里登记`).toBe(true)
    }
  })

  it('注册表与皮肤 CSS 双向一致', () => {
    const registered = new Set(SKINS.map((skin) => skin.id).filter((id) => id !== DEFAULT_SKIN))
    const styled = new Set(css.skinRules.map((rule) => rule.id))

    const missingCss = [...registered].filter((id) => !styled.has(id))
    const missingRegistry = [...styled].filter((id) => !registered.has(id))

    expect(missingCss, `这些皮肤在注册表里但没有 CSS 块（点了没反应）：${missingCss.join(', ')}`).toEqual([])
    expect(
      missingRegistry,
      `这些皮肤有 CSS 块但没在注册表登记（用户永远选不到）：${missingRegistry.join(', ')}`,
    ).toEqual([])
  })

  it('isSkin 只认已登记的 id', () => {
    expect(isSkin(DEFAULT_SKIN)).toBe(true)
    expect(isSkin('no-such-skin')).toBe(false)
    expect(isSkin(null)).toBe(false)
    expect(isSkin('')).toBe(false)
  })
})

describe('皮肤令牌契约', () => {
  const styledSkins = [...new Set(css.skinRules.map((rule) => rule.id))]

  it('每个皮肤都有亮色块与暗色块', () => {
    for (const id of styledSkins) {
      expect(blockOf(id, false), `${id} 缺少亮色块`).toBeDefined()
      expect(blockOf(id, true), `${id} 缺少暗色块（暗色下会变成"半新半旧"）`).toBeDefined()
    }
  })

  it('每个皮肤亮暗两套覆盖的令牌完全相同', () => {
    for (const id of styledSkins) {
      const light = new Set(declarationNames(blockOf(id, false)?.body ?? ''))
      const dark = new Set(declarationNames(blockOf(id, true)?.body ?? ''))
      const onlyLight = [...light].filter((name) => !dark.has(name))
      const onlyDark = [...dark].filter((name) => !light.has(name))

      expect(
        onlyLight,
        `${id} 的亮色块覆盖了这些令牌但暗色块没给值：${onlyLight.map((name) => `--${name}`).join(', ')}`,
      ).toEqual([])
      expect(
        onlyDark,
        `${id} 的暗色块覆盖了这些令牌但亮色块没给值：${onlyDark.map((name) => `--${name}`).join(', ')}`,
      ).toEqual([])
    }
  })

  it('皮肤只覆盖已知令牌名', () => {
    const problems: string[] = []
    for (const rule of css.skinRules) {
      for (const name of declarationNames(rule.body)) {
        if (!knownTokens.has(name)) {
          problems.push(`${rule.id}${rule.dark ? '/dark' : ''} 里的 --${name} 不是已知令牌（拼错了？）`)
        }
      }
    }
    expect(problems).toEqual([])
  })

  it('纸张令牌不在任何皮肤的覆盖范围', () => {
    const problems: string[] = []
    for (const rule of css.skinRules) {
      for (const name of declarationNames(rule.body)) {
        if (RESERVED(name)) {
          problems.push(`${rule.id}${rule.dark ? '/dark' : ''} 覆盖了 --${name}（简历纸张，必须保持纸色）`)
        }
      }
    }
    expect(problems).toEqual([])
  })

  it('装饰层默认关闭（不给默认主题带来视觉变化）', () => {
    const rootTokens = declarationNames(css.rootBody)
    expect(rootTokens).toContain('decor-image')
    expect(rootTokens).toContain('decor-opacity')

    const opacity = /--decor-opacity\s*:\s*([^;]+);/.exec(css.rootBody)?.[1]?.trim()
    expect(opacity, '--decor-opacity 的默认值必须是 0，否则默认主题会多出一层纹理').toBe('0')
  })
})

describe('首屏脚本', () => {
  afterEach(() => {
    document.documentElement.classList.remove('dark')
    document.documentElement.removeAttribute('data-skin')
    globalThis.localStorage.clear()
  })

  /** 把 vite 插件注入的那段脚本真跑一遍：它是字符串，类型系统帮不上忙，只能执行验证。 */
  function runInitScript(): void {
    const transform = themeInitPlugin().transformIndexHtml
    if (typeof transform !== 'function') {
      throw new Error('themeInitPlugin 没有提供 transformIndexHtml')
    }
    // 插件钩子的签名要求 `this` 是 vite 的插件上下文，测试里没有它也不需要有：
    // 这里的实现是纯函数（只看配置、不碰 this），把类型放宽到"能调用"即可。
    const invoke = transform as unknown as (html: string, context: unknown) => unknown
    const tags = invoke('', {}) as { children?: string }[]
    const script = tags[0]?.children
    if (typeof script !== 'string') {
      throw new Error('首屏脚本没有生成')
    }
    new Function(script)()
  }

  it('已保存的皮肤在首屏就写到 <html> 上，不用等 React 挂载', () => {
    globalThis.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    globalThis.localStorage.setItem(SKIN_STORAGE_KEY, 'paper')

    runInitScript()

    expect(document.documentElement.dataset['skin']).toBe('paper')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('没有保存过皮肤时落到默认皮肤', () => {
    globalThis.localStorage.setItem(THEME_STORAGE_KEY, 'light')

    runInitScript()

    expect(document.documentElement.dataset['skin']).toBe(DEFAULT_SKIN)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })
})
