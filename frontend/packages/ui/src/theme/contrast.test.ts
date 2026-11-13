import { describe, expect, it } from 'vitest'

import { SKINS } from './skins'
import { loadThemeCss } from './theme-css'

/**
 * 对比度检查。令牌是手写的感观值，改一个数字就可能把某处文字压到 AA 以下，
 * 而那种问题肉眼看不出来（尤其在半透明层上）。所以直接在测试里算 WCAG 对比度。
 *
 * <p><b>WORK-079 起，检查对象从"浅色/深色两套"变成"每个皮肤 × 每个明暗"</b>。
 * 这是"支持很多皮肤"能成立的唯一前提：皮肤是数据，数据的正确性只能靠逐条断言。
 * 17 个皮肤 × 2 种模式 = 34 套令牌，人工看不过来；而失败信息里带皮肤 id，定位成本是零。
 *
 * <p>解析的是**级联结果**而不是单个块：亮色 = `:root` ← 皮肤亮色块；暗色 = `:root` ← `.dark`
 * ← 皮肤亮色块 ← 皮肤暗色块（顺序即 CSS 特异性顺序，见 `theme-css.ts` 与 `skins.css` 的说明）。
 * 只读某个块本身会漏掉"皮肤只覆盖了一部分令牌、其余继承"这件事。
 *
 * <p>为什么算"合成后颜色"：半透明层上的文字落在合成色上，而不是落在令牌原值上；
 * 按原值算会高估对比度。
 *
 * <p>已知未覆盖：静止状态的 input/border 是极细的低对比线（这是刻意的观感方向，
 * Geist 自己的 `--ds-gray-400` 就是 hsl(0 0% 92%)）。WCAG 1.4.11 真正要求的是**焦点指示**
 * 可辨识，所以这里断言的是 ring 合成后的对比度；若连静止边框也提到 3:1，观感会明显变重。
 */

interface Color {
  rgb: [number, number, number]
  alpha: number
}

/** 解析一个声明块里的颜色令牌，oklch 与 hsl 两种写法都支持。 */
function parseColors(body: string): Record<string, Color> {
  const tokens: Record<string, Color> = {}
  for (const line of body.split(';')) {
    const declaration = /--([a-z0-9-]+)\s*:\s*(oklch|hsl)\(([^)]*)\)/.exec(line)
    if (declaration === null) {
      continue
    }
    const name = declaration[1]
    const space = declaration[2]
    const [channelPart = '', alphaPart] = (declaration[3] ?? '').trim().split(/\s*\/\s*/)
    const channels = channelPart.trim().split(/\s+/)
    const rawAlpha = alphaPart?.trim()
    const alpha =
      rawAlpha === undefined || rawAlpha === ''
        ? 1
        : rawAlpha.endsWith('%')
          ? Number(rawAlpha.slice(0, -1)) / 100
          : Number(rawAlpha)
    if (name === undefined) {
      continue
    }
    const rgb =
      space === 'oklch'
        ? oklchToSrgb(Number(channels[0]), Number(channels[1]), Number(channels[2]))
        : hslToSrgb(Number(channels[0]), Number(channels[1]?.replace('%', '')), Number(channels[2]?.replace('%', '')))
    tokens[name] = { rgb, alpha }
  }
  return tokens
}

/** OKLCH → 线性 sRGB → sRGB（gamma 编码），通道范围 0..1。 */
function oklchToSrgb(l: number, c: number, h: number): [number, number, number] {
  const hue = (h * Math.PI) / 180
  const a = c * Math.cos(hue)
  const b = c * Math.sin(hue)

  const ll = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const mm = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const ss = (l - 0.0894841775 * a - 1.291485548 * b) ** 3

  return [
    encodeGamma(4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss),
    encodeGamma(-1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss),
    encodeGamma(-0.0041960863 * ll - 0.7034186147 * mm + 1.707614701 * ss),
  ]
}

function hslToSrgb(h: number, s: number, l: number): [number, number, number] {
  const saturation = s / 100
  const lightness = l / 100
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation
  const sector = (h / 60) % 6
  const second = chroma * (1 - Math.abs((sector % 2) - 1))
  const offset = lightness - chroma / 2

  const table: [number, number, number][] = [
    [chroma, second, 0],
    [second, chroma, 0],
    [0, chroma, second],
    [0, second, chroma],
    [second, 0, chroma],
    [chroma, 0, second],
  ]
  const base = table[Math.floor(sector) % 6] ?? [0, 0, 0]
  return [base[0] + offset, base[1] + offset, base[2] + offset]
}

function encodeGamma(value: number): number {
  const clamped = Math.min(1, Math.max(0, value))
  return clamped <= 0.0031308 ? clamped * 12.92 : 1.055 * clamped ** (1 / 2.4) - 0.055
}

/** CSS 的 alpha 合成发生在 sRGB（非线性）空间，与浏览器行为一致。 */
function composite(top: Color, bottom: [number, number, number]): [number, number, number] {
  return [
    top.alpha * top.rgb[0] + (1 - top.alpha) * bottom[0],
    top.alpha * top.rgb[1] + (1 - top.alpha) * bottom[1],
    top.alpha * top.rgb[2] + (1 - top.alpha) * bottom[2],
  ]
}

function relativeLuminance(rgb: [number, number, number]): number {
  const linear = rgb.map((channel) =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  ) as [number, number, number]
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrastRatio(a: number, b: number): number {
  const lighter = Math.max(a, b)
  const darker = Math.min(a, b)
  return (lighter + 0.05) / (darker + 0.05)
}

const css = loadThemeCss()
const rootColors = parseColors(css.rootBody)
const darkColors = parseColors(css.darkBody)

/**
 * 某个皮肤在某个明暗下的**实际生效**令牌。
 *
 * @param skinId 皮肤 id（`default` 没有任何皮肤块，因此结果就是基础两套之一）
 * @param mode 明暗
 */
function resolveTokens(skinId: string, mode: 'light' | 'dark'): Record<string, Color> {
  const resolved: Record<string, Color> = { ...rootColors }
  if (mode === 'dark') {
    Object.assign(resolved, darkColors)
  }
  // 亮色块的优先级高于 `.dark`（`:root[data-skin]` 是 0,2,0，`.dark` 是 0,1,0），
  // 所以暗色下也要先叠亮色块、再叠暗色块——这正是"皮肤漏写暗色值"会露馅的地方。
  for (const rule of css.skinRules.filter((item) => item.id === skinId && !item.dark)) {
    Object.assign(resolved, parseColors(rule.body))
  }
  for (const rule of css.skinRules.filter((item) => item.id === skinId && item.dark)) {
    Object.assign(resolved, parseColors(rule.body))
  }
  return resolved
}

const TEXT_MINIMUM = 4.5

const cases = SKINS.flatMap((skin) =>
  (['light', 'dark'] as const).map((mode) => ({
    skinId: skin.id,
    skinName: skin.name,
    mode,
    label: mode === 'light' ? '亮色' : '暗色',
  })),
)

describe.each(cases)('$skinName（$skinId）· $label 的对比度', ({ skinId, mode }) => {
  const tokens = resolveTokens(skinId, mode)
  const background = relativeLuminance(tokens['background']!.rgb)
  /**
   * 卡面可能是半透明的（玻璃族：`--card: hsl(0 0% 100% / 78%)`）。这时文字落在**卡面与
   * 页面底色的合成色**上，按令牌原值算会高估对比度——那正是玻璃类皮肤最容易骗过测试的地方。
   */
  const card = relativeLuminance(
    tokens['card']!.alpha < 1 ? composite(tokens['card']!, tokens['background']!.rgb) : tokens['card']!.rgb,
  )
  const solid = relativeLuminance(tokens['card-solid']!.rgb)
  // 半透明玻璃层上的文字：最坏情形是它叠在页面底色上（页面底色就是它背后最亮/最暗的东西）
  const glass = relativeLuminance(composite(tokens['glass']!, tokens['background']!.rgb))

  it('正文在页面底色、卡面、不透明底与玻璃层上都达到 4.5:1', () => {
    const foreground = relativeLuminance(tokens['foreground']!.rgb)

    expect(contrastRatio(foreground, background)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
    expect(contrastRatio(foreground, card)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
    expect(contrastRatio(foreground, solid)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
    expect(contrastRatio(foreground, glass)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
  })

  it('次要文本在页面底色、卡面与玻璃层上都达到 4.5:1', () => {
    const muted = relativeLuminance(tokens['muted-foreground']!.rgb)

    expect(contrastRatio(muted, background)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
    expect(contrastRatio(muted, card)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
    expect(contrastRatio(muted, glass)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
  })

  it('主按钮与次级面上的文字都达到 4.5:1', () => {
    const onPrimary = relativeLuminance(tokens['primary-foreground']!.rgb)
    const primary = relativeLuminance(tokens['primary']!.rgb)
    const onSecondary = relativeLuminance(tokens['secondary-foreground']!.rgb)
    const secondary = relativeLuminance(tokens['secondary']!.rgb)

    expect(contrastRatio(onPrimary, primary)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
    expect(contrastRatio(onSecondary, secondary)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
  })

  it('危险色当文字用（提示与危险按钮）也达到 4.5:1', () => {
    // 组件里危险色是以文字形式出现的：Alert 的 text-destructive 落在卡面上，
    // 危险按钮是 bg-destructive/10 + text-destructive，底色近于卡面。
    const destructive = relativeLuminance(tokens['destructive']!.rgb)

    expect(contrastRatio(destructive, card)).toBeGreaterThanOrEqual(TEXT_MINIMUM)
  })

  it('焦点指示（ring 合成后）达到 UI 组件要求的 3:1', () => {
    const ring = relativeLuminance(composite(tokens['ring']!, tokens['background']!.rgb))

    expect(contrastRatio(ring, background)).toBeGreaterThanOrEqual(3)
  })
})
