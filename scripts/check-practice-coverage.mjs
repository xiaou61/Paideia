#!/usr/bin/env node
/**
 * 练习块缺口盘点（WORK-068 优化点 12）。
 *
 * <p>一条线"能不能练"取决于正文里有没有 `quiz` / `code-challenge` / `sql-challenge` 围栏。
 * 这里只读内容源文件，不连数据库：导入前就能看出哪些关是纯阅读。
 *
 * 用法：
 *   node scripts/check-practice-coverage.mjs            # 按线汇总
 *   node scripts/check-practice-coverage.mjs --missing  # 再列出没有练习的关
 */
import { readdir, readFile, stat } from 'node:fs/promises'
import { join, basename } from 'node:path'

const ROOTS = ['content/courses', 'content/reading']
const PRACTICE = /^```(quiz|code-challenge|sql-challenge)\b/gm

async function walk(dir) {
  const out = []
  for (const name of await readdir(dir)) {
    const path = join(dir, name)
    if ((await stat(path)).isDirectory()) {
      out.push(...(await walk(path)))
    } else if (name.endsWith('.md') && name !== 'README.md') {
      // `00-总览.md` 是线路说明，不是一关；把它算进"纯阅读"会让每条线都多一条假缺口
      if (!name.startsWith('00-')) {
        out.push(path)
      }
    }
  }
  return out
}

const lines = new Map()
for (const root of ROOTS) {
  for (const file of await walk(root)) {
    const dir = file.slice(0, file.lastIndexOf('\\') === -1 ? file.lastIndexOf('/') : file.lastIndexOf('\\'))
    const key = `${root.split('/')[1]}:${basename(dir)}`
    const body = await readFile(file, 'utf8')
    const count = (body.match(PRACTICE) ?? []).length
    // 围栏内容是 JSON：裸双引号（尤其中文里手写的 "xxx"）会让整关小测解析失败
    for (const match of body.matchAll(/^```(quiz|code-challenge)\n([\s\S]*?)^```$/gm)) {
      try {
        JSON.parse(match[2])
      } catch (error) {
        console.error(`${file}: ${match[1]} JSON 不合法 — ${error.message}`)
        process.exitCode = 1
      }
    }
    const line = lines.get(key) ?? { files: 0, bare: 0, blocks: 0, missing: [] }
    line.files += 1
    line.blocks += count
    if (count === 0) {
      line.bare += 1
      line.missing.push(basename(file))
    }
    lines.set(key, line)
  }
}

const rows = [...lines.entries()].sort((a, b) => b[1].bare - a[1].bare || a[0].localeCompare(b[0]))
const total = rows.reduce(
  (sum, [, line]) => ({ files: sum.files + line.files, bare: sum.bare + line.bare, blocks: sum.blocks + line.blocks }),
  { files: 0, bare: 0, blocks: 0 },
)

console.log(`线 ${rows.length} 条 · 关 ${total.files} 篇 · 练习块 ${total.blocks} 个 · 纯阅读 ${total.bare} 篇\n`)
for (const [key, line] of rows) {
  console.log(`${key.padEnd(28)} ${String(line.files).padStart(3)} 篇 · ${String(line.blocks).padStart(3)} 练习 · 纯阅读 ${line.bare}`)
}

if (process.argv.includes('--missing')) {
  console.log('\n没有练习块的关：')
  for (const [key, line] of rows) {
    if (line.missing.length === 0) {
      continue
    }
    console.log(`\n[${key}]`)
    for (const name of line.missing) {
      console.log(`  ${name}`)
    }
  }
}
