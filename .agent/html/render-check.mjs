#!/usr/bin/env node
/**
 * 用真浏览器打开每个专题页，确认：
 *   每张 `pre.mermaid` 都渲染出了 SVG、页面里没有 "Syntax error"、控制台没有报错、侧栏注入了 11 条导航。
 *
 * 存在的理由：mermaid 的语法错误不会让页面崩，只会把图换成一块报错文字——
 * 光看文件发现不了，必须真渲染一次。而 mermaid 的官方 CLI 要另装依赖，
 * 仓里已有 playwright + chromium，直接用。
 *
 * 用法：node .agent/html/render-check.mjs
 *       node .agent/html/render-check.mjs --shots   # 顺带把每页整页截图存到系统临时目录
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
let total = 0

for (const file of pages) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const problems = []
  page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text()}`) })
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`))

  await page.goto(pathToFileURL(join(HERE, file)).href)

  const expected = await page.locator('pre.mermaid').count()
  if (expected > 0) {
    // 渲染是异步的：等到每个 pre 里都长出 svg，或者超时后按实际情况报告。
    await page.waitForFunction(() => {
      const blocks = [...document.querySelectorAll('pre.mermaid')]
      return blocks.length > 0 && blocks.every((b) => b.querySelector('svg'))
    }, null, { timeout: 20000 }).catch(() => {})
  }

  const rendered = await page.locator('pre.mermaid svg').count()
  const nav = await page.locator('aside.sidebar nav a').count()
  const text = await page.locator('body').innerText()
  const syntax = (text.match(/Syntax error/gi) || []).length

  if (rendered !== expected) problems.push(`${expected} 张图只渲染出 ${rendered} 张`)
  if (syntax > 0) problems.push(`页面里有 ${syntax} 处 "Syntax error"`)
  if (nav !== 11) problems.push(`侧栏导航是 ${nav} 条，应为 11 条`)

  total += expected
  if (problems.length) {
    bad++
    console.log(`✗ ${file}（${rendered}/${expected} 张图）`)
    for (const p of problems) console.log(`    ${p}`)
  } else {
    console.log(`✓ ${file}（${expected} 张图）`)
  }
  if (SHOTS) await page.screenshot({ path: join(shotDir, file.replace('.html', '.png')), fullPage: true })
  await page.close()
}

await browser.close()
console.log(`\n${pages.length} 个页面 / ${total} 张图，失败 ${bad} 个页面。`)
process.exit(bad ? 1 : 0)
