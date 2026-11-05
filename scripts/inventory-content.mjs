#!/usr/bin/env node
/**
 * 内容形态盘点（WORK-073 TASK-001）。**只读**。
 *
 * 扫描 content/ 下的 markdown（跳过 README.md 与 schema/ 目录），统计 frontmatter
 * 键的出现次数与取值、正文围栏块（```lang）的种类与数量。
 *
 * `--check-schema` 时与 content/schema/lesson.schema.json 比对：
 *   - 每个实际出现的 frontmatter 键都已在 schema 声明；
 *   - schema 里的必填键出现在全部内容文件；
 *   - 声明了 enum 的键，实际取值都在枚举里；
 *   - 每个实际出现的围栏块类型都已在 schema 声明。
 * 不一致退出 1。
 *
 * 用法：
 *   node scripts/inventory-content.mjs
 *   node scripts/inventory-content.mjs --check-schema
 */
import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import process from 'node:process'

const ROOT = process.cwd()
const CONTENT = join(ROOT, 'content')
const SCHEMA = join(CONTENT, 'schema', 'lesson.schema.json')
const FENCE = /^```([A-Za-z0-9_-]+)/gm

async function walk(dir) {
  const out = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'schema') continue // schema 不是内容
      out.push(...(await walk(full)))
    } else if (entry.name.endsWith('.md') && entry.name !== 'README.md') {
      out.push(full)
    }
  }
  return out
}

function parseFrontmatter(text) {
  const normalized = text.replace(/^\uFEFF/, '')
  const lines = normalized.split(/\r?\n/)
  if (lines[0].trim() !== '---') return null
  let end = -1
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') { end = i; break }
  }
  if (end === -1) return null
  const meta = {}
  for (let i = 1; i < end; i++) {
    const m = lines[i].match(/^([A-Za-z_][\w-]*):\s*(.*)$/)
    if (!m) continue
    let v = m[2].trim()
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1)
    }
    meta[m[1]] = v
  }
  return { meta }
}

async function main() {
  const files = await walk(CONTENT)
  const keyCount = new Map()
  const keyValues = new Map()
  const blockCount = new Map()
  const unparsable = []

  for (const file of files) {
    const text = await readFile(file, 'utf8')
    const fm = parseFrontmatter(text)
    if (!fm) { unparsable.push(relative(ROOT, file)); continue }
    for (const [k, v] of Object.entries(fm.meta)) {
      keyCount.set(k, (keyCount.get(k) ?? 0) + 1)
      if (!keyValues.has(k)) keyValues.set(k, new Set())
      keyValues.get(k).add(v)
    }
    for (const m of text.matchAll(FENCE)) {
      blockCount.set(m[1], (blockCount.get(m[1]) ?? 0) + 1)
    }
  }

  console.log(`内容文件：${files.length} 篇（跳过 README.md）`)
  if (unparsable.length) console.log(`无 frontmatter：${unparsable.join(', ')}`)
  console.log(`\nfrontmatter 键：${keyCount.size}`)
  for (const [k, c] of [...keyCount.entries()].sort((a, b) => b[1] - a[1])) {
    const values = [...keyValues.get(k)]
    const shown = values.length <= 12 ? values.join(' | ') : `(${values.length} 种)`
    console.log(`  ${k.padEnd(16)} ${String(c).padStart(4)}  ${shown}`)
  }
  console.log(`\n围栏块：${blockCount.size} 种`)
  for (const [b, c] of [...blockCount.entries()].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${b.padEnd(16)} ${String(c).padStart(5)}`)
  }

  if (process.argv.includes('--check-schema')) {
    const schema = JSON.parse(await readFile(SCHEMA, 'utf8'))
    const problems = []
    const declared = new Set(Object.keys(schema.fields ?? {}))
    for (const k of keyCount.keys()) {
      if (!declared.has(k)) problems.push(`schema 未声明 frontmatter 键：${k}`)
    }
    for (const [k, spec] of Object.entries(schema.fields ?? {})) {
      const c = keyCount.get(k) ?? 0
      if (spec.required && c !== files.length) problems.push(`必填键 ${k} 只出现在 ${c}/${files.length} 篇`)
      if (spec.enum) {
        for (const v of keyValues.get(k) ?? []) {
          if (!spec.enum.includes(v)) problems.push(`键 ${k} 取值 ${JSON.stringify(v)} 不在枚举内`)
        }
      }
    }
    const declaredBlocks = new Set(schema.blocks ?? [])
    for (const b of blockCount.keys()) {
      if (!declaredBlocks.has(b)) problems.push(`schema 未声明围栏块类型：${b}`)
    }
    if (problems.length) {
      console.error('\n--check-schema 失败：')
      for (const p of problems) console.error('  ✗ ' + p)
      process.exitCode = 1
    } else {
      console.log('\n--check-schema：schema 与实际情况一致 ✓')
    }
  }
}

main().catch((e) => { console.error(e); process.exit(1) })
