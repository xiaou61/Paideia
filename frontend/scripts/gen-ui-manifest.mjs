#!/usr/bin/env node
/**
 * 从 packages/ui/src/index.ts 的 re-export 语句 + component-meta.json，
 * 生成 frontend/docs/ui-manifest.md（组件清单）。
 *
 *   pnpm ui:manifest        重新生成
 *   pnpm ui:manifest:check  只校验，不一致则退出码 1（CI / 提交前用）
 *
 * 设计取舍：组件名、子部件、导出关系一律从源码自动解析；分类 / 一句话用途 / 源文件
 * 这类源码里看不出来的知识由 component-meta.json 人工维护。两者任一对不上都直接失败，
 * 避免清单悄悄失真。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(here, '..')
const indexPath = path.join(frontendRoot, 'packages/ui/src/index.ts')
const metaPath = path.join(frontendRoot, 'packages/ui/src/component-meta.json')
const outPath = path.join(frontendRoot, 'docs/ui-manifest.md')
const jsonOutPath = path.join(frontendRoot, 'packages/ui/src/ui-manifest.json')

// 这些模块是基础设施、不是组件，不需要也不应该在清单里出现
const IGNORE_MODULES = new Set(['./component-meta'])

function fail(msg) {
  console.error(`✗ ${msg}`)
  process.exit(1)
}

const indexSrc = fs.readFileSync(indexPath, 'utf8')
let meta
try {
  meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'))
} catch (err) {
  fail(`读取 component-meta.json 失败：${err.message}`)
}

// 解析形如 export { A, type B } from './x' 的语句（含跨行）
const re = /export\s+(?:type\s+)?\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g
const modules = new Map() // specifier -> { symbols: [{ name, isType }] }
let match
while ((match = re.exec(indexSrc))) {
  const namesRaw = match[1]
  const specifier = match[2]
  if (IGNORE_MODULES.has(specifier)) continue
  if (!modules.has(specifier)) modules.set(specifier, { symbols: [] })
  const bucket = modules.get(specifier)
  for (const raw of namesRaw.split(',')) {
    const piece = raw.trim()
    if (!piece) continue
    const isType = /^type\s+/.test(piece)
    const name = piece.replace(/^type\s+/, '').trim()
    if (name) bucket.symbols.push({ name, isType })
  }
}

if (modules.size === 0) fail('index.ts 里没有解析到任何 re-export，正则可能过期了。')

// 元数据与源码双向对齐
const missing = [...modules.keys()].filter((spec) => !meta[spec])
const stale = Object.keys(meta).filter((spec) => !modules.has(spec))
if (missing.length) fail(`以下源模块缺少 component-meta.json 条目：\n  ${missing.join('\n  ')}`)
if (stale.length) fail(`component-meta.json 里有已不在 index.ts 的条目：\n  ${stale.join('\n  ')}`)

// 生成 Markdown（按 index.ts 出现顺序，稳定可复现）
const lines = []
lines.push('# UI 组件清单（自动生成，请勿手改）')
lines.push('')
lines.push('> 由 `pnpm ui:manifest` 从 `packages/ui/src/index.ts` 与 `packages/ui/src/component-meta.json` 生成。')
lines.push('> 需改组件用途请编辑 `component-meta.json` 后重跑；CI 用 `pnpm ui:manifest:check` 防漂移。')
lines.push('')
const rows = []
for (const [specifier, info] of modules) {
  const m = meta[specifier]
  for (const sym of info.symbols) {
    if (sym.isType) continue
    rows.push({ category: m.category, name: sym.name, description: m.description, source: m.source })
  }
}
lines.push(`共 ${modules.size} 个模块、${rows.length} 个组件/导出。`)
lines.push('')
lines.push('| 分类 | 导出 | 用途 | 源文件 |')
lines.push('| --- | --- | --- | --- |')
for (const r of rows) {
  lines.push(`| ${r.category} | \`${r.name}\` | ${r.description} | \`${r.source}\` |`)
}
lines.push('')
const content = lines.join('\n')
const jsonContent =
  JSON.stringify({ moduleCount: modules.size, exportCount: rows.length, exports: rows }, null, 2) + '\n'

const checkOnly = process.argv.includes('--check')
const existing = fs.existsSync(outPath) ? fs.readFileSync(outPath, 'utf8') : null
const existingJson = fs.existsSync(jsonOutPath) ? fs.readFileSync(jsonOutPath, 'utf8') : null

if (checkOnly) {
  const drift = []
  if (existing !== content) drift.push(path.relative(frontendRoot, outPath))
  if (existingJson !== jsonContent) drift.push(path.relative(frontendRoot, jsonOutPath))
  if (drift.length) {
    fail(`${drift.join('、')} 与源码 / 元数据不一致，请运行 \`pnpm ui:manifest\` 重新生成。`)
  }
  console.log('✓ ui-manifest 与源码一致（' + rows.length + ' 个导出）。')
} else {
  fs.mkdirSync(path.dirname(outPath), { recursive: true })
  fs.writeFileSync(outPath, content)
  fs.mkdirSync(path.dirname(jsonOutPath), { recursive: true })
  fs.writeFileSync(jsonOutPath, jsonContent)
  console.log(
    `✓ 已生成 ${path.relative(frontendRoot, outPath)} 与 ${path.relative(frontendRoot, jsonOutPath)}` +
      `（${rows.length} 个导出，${modules.size} 个模块）。`,
  )
}
