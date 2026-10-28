#!/usr/bin/env node
/**
 * 用真浏览器打开每个页面，确认：
 *   控制台无报错、没有图横向溢出、侧栏注入 11 条导航、没有残留任何外部脚本。
 *
 * 图现在是手写 HTML/CSS，不需要渲染库——所以这里查的是「排版有没有挤破」而不是「SVG 有没有生成」。
 * 溢出检查对应最常见的退化：某个盒子写太宽，1440 下看不见，1280 的笔记本上就被裁掉。
 *
 * 用法：node .agent/html/render-check.mjs [--shots]
 * 退出码：0 全部通过；1 有页面失败；2 环境缺 playwright/chromium。
 */
import { readdir, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const SHOTS = process.argv.includes('--shots')
const HERE = dirname(fileURLToPath(import.meta.url))
// 从 app 包解析，pnpm 的符号链接与 .pnpm 布局都能走通；不写死版本号。
const requireFromApp = createRequire(join(HERE, '..', '..', 'frontend', 'apps', 'app', 'package.json'))

let chromium
try {
  const mod = await import(pathToFileURL(requireFromApp.resolve('@playwright/test')).href)
  chromium = (mod.default ?? mod).chromium
  if (!chromium) throw new Error('@playwright/test 里没有 chromium')
} catch (error) {
  console.error(`✗ 找不到可用的 playwright：${error.message}`)
  console.error('  先跑一次 `pnpm install`（在 frontend/ 下），并确认 chromium 已下载。')
  process.exit(2)
}

const pages = (await readdir(HERE)).filter((f) => f.endsWith('.html')).sort()
const browser = await chromium.launch()
const shotDir = join(tmpdir(), 'paideia-arch-shots')
if (SHOTS) await mkdir(shotDir, { recursive: true })
let bad = 0

for (const file of pages) {
  const problems = []
  // 1440 与 1280 各查一次：常见桌面宽度 + 更窄的笔记本，两个都过才算没被裁。
  for (const width of [1440, 1280]) {
    const page = await browser.newPage({ viewport: { width, height: 900 } })
    const errors = []
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`) })
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
    await page.goto(pathToFileURL(join(HERE, file)).href)

    const nav = await page.locator('aside.sidebar nav a').count()
    // 10px 容差：边框与四舍五入会造出一两像素的假溢出。
    const overflow = await page.evaluate(() => {
      const out = []
      document.querySelectorAll('.d').forEach((box) => {
        if (box.scrollWidth > box.clientWidth + 10) out.push(`${box.scrollWidth}>${box.clientWidth}`)
      })
      return out
    })
    // 图表库一旦被重新引进来，页面就会重新变慢；这条守着「不要再装回去」。
    const libs = await page.evaluate(() =>
      [...document.querySelectorAll('script[src]')]
        .map((s) => s.getAttribute('src'))
        .filter((src) => src !== 'nav.js'))

    if (errors.length) problems.push(`[${width}px] ${errors.join(' | ')}`)
    if (overflow.length) problems.push(`[${width}px] ${overflow.length} 个图溢出：${overflow.join(', ')}`)
    if (libs.length) problems.push(`[${width}px] 引了外部脚本：${libs.join(', ')}`)
    if (width === 1440 && nav !== 15) problems.push(`侧栏导航是 ${nav} 条，应为 15 条`)
    if (width === 1440 && SHOTS) {
      await page.screenshot({ path: join(shotDir, file.replace('.html', '.png')), fullPage: true })
    }
    await page.close()
  }

  if (problems.length) {
    bad++
    console.log(`✗ ${file}`)
    for (const p of problems) console.log(`    ${p}`)
  } else {
    console.log(`✓ ${file}`)
  }
}

await browser.close()
console.log(`\n${pages.length} 个页面（1440px 与 1280px 各查一次），失败 ${bad} 个。`)
process.exit(bad ? 1 : 0)
