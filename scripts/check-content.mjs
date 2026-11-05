#!/usr/bin/env node
/**
 * 统一内容静态校验（WORK-073 TASK-002）。**只读，不修改任何内容文件。**
 *
 * 依 content/schema/lesson.schema.json 校验：
 *   - frontmatter：必填键、类型、枚举、未声明的键；
 *   - 围栏块：块类型是否在 schema 里登记；
 *   - 结构化块（quiz / code-challenge / sketch / learning-path / source-walk /
 *     code-map / sql-challenge）必须是合法 JSON；
 *   - prerequisite 指向的标题必须存在；
 *   - 同一 category 内 order 必须唯一且连续（不要求从 0 起）。
 *
 * 可复用于 import-course.mjs：`import { validateContent } from './check-content.mjs'`。
 *
 * 用法：
 *   node scripts/check-content.mjs [--scope <目录>] [--json]
 *   node scripts/check-content.mjs --scope tests/content-schema/bad
 *   node scripts/check-content.mjs --exec -- [verify_course.py 的参数，如 --no-run]
 */
import { readdir, readFile } from 'node:fs/promises'
import { join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import process from 'node:process'

const ROOT = process.cwd()
const SCHEMA_PATH = join(ROOT, 'content', 'schema', 'lesson.schema.json')
const WHITELIST_PATH = join(ROOT, 'content', 'schema', 'whitelist.json')

async function walk(dir) {
  const out = []
  let entries
  try {
    entries = await readdir(dir, { withFileTypes: true })
  } catch {
    return out
  }
  for (const entry of entries) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'schema' || entry.name === 'node_modules') continue
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
  const keyLine = {}
  for (let i = 1; i < end; i++) {
    const m = lines[i].match(/^([A-Za-z_][\w-]*):\s*(.*)$/)
    if (!m) continue
    let v = m[2].trim()
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1)
    }
    meta[m[1]] = v
    keyLine[m[1]] = i + 1
  }
  return { meta, keyLine, startLine: 1 }
}

function parseBlocks(text) {
  const lines = text.split(/\r?\n/)
  const blocks = []
  let i = 0
  const OPEN = /^```([A-Za-z0-9_-]+)[ \t]*$/
  while (i < lines.length) {
    const m = lines[i].match(OPEN)
    if (!m) { i++; continue }
    const lang = m[1]
    const startLine = i + 1
    let j = i + 1
    const buf = []
    while (j < lines.length && lines[j].trim() !== '```') { buf.push(lines[j]); j++ }
    blocks.push({ lang, startLine, content: buf.join('\n') })
    i = j + 1
  }
  return blocks
}

function rel(p) { return relative(ROOT, p).split('\\').join('/') }

// —— 结构化块细则（原 check_quiz_explain / check_shell_cases 的判定，WORK-073 TASK-004）——
const MAX_JUDGE = 20000
const MAX_SEED_FILES = 20
const MAX_SEED_FILE = 64000
const MAX_SEED_TOTAL = 256000
const PATH_OK = /^[A-Za-z0-9_./-]+$/

function validateQuizBlock(data, file, line, errors) {
  const questions = data && data.questions
  if (!Array.isArray(questions) || questions.length === 0) {
    errors.push({ file, line, rule: 'block.quiz', message: 'quiz 块没有 questions' })
    return
  }
  questions.forEach((q, i) => {
    const ex = q && q.explain
    if (typeof ex !== 'string' || !ex.trim()) {
      errors.push({ file, line, rule: 'block.quiz.explain', message: `quiz 第 ${i + 1} 题缺 explain` })
    }
  })
}

function validateShellBlock(data, file, line, errors) {
  if (!data || data.kind !== 'shell') return
  if (typeof data.id !== 'string' || !data.id) {
    errors.push({ file, line, rule: 'block.challenge.id', message: 'shell 题缺 id' })
  }
  if (typeof data.judge !== 'string' || !data.judge.trim()) {
    errors.push({ file, line, rule: 'block.challenge.judge', message: 'shell 题 judge 为空（会永远判不出结果）' })
  } else if (data.judge.length > MAX_JUDGE) {
    errors.push({ file, line, rule: 'block.challenge.judge', message: `judge 超过 ${MAX_JUDGE} 字符` })
  }
  if (data.starter != null && typeof data.starter === 'string' && data.starter.length > MAX_JUDGE) {
    errors.push({ file, line, rule: 'block.challenge.starter', message: `starter 超过 ${MAX_JUDGE} 字符` })
  }
  const seed = data.seed ?? []
  if (!Array.isArray(seed)) {
    errors.push({ file, line, rule: 'block.challenge.seed', message: 'seed 必须是数组' })
    return
  }
  if (seed.length > MAX_SEED_FILES) {
    errors.push({ file, line, rule: 'block.challenge.seed', message: `seed 超过 ${MAX_SEED_FILES} 个文件` })
  }
  let total = 0
  seed.forEach((item, i) => {
    if (!item || typeof item !== 'object') {
      errors.push({ file, line, rule: 'block.challenge.seed', message: `seed[${i}] 不是对象` })
      return
    }
    const p = item.path
    if (typeof p !== 'string' || !p) {
      errors.push({ file, line, rule: 'block.challenge.seed', message: `seed[${i}] 缺 path` })
      return
    }
    if (!PATH_OK.test(p) || p.startsWith('/') || p.includes('..')) {
      errors.push({ file, line, rule: 'block.challenge.seed', message: `seed[${i}] 路径不合法：${p}` })
    }
    const c = item.content ?? ''
    if (typeof c !== 'string') {
      errors.push({ file, line, rule: 'block.challenge.seed', message: `seed[${i}] content 必须是字符串` })
      return
    }
    if (c.length > MAX_SEED_FILE) {
      errors.push({ file, line, rule: 'block.challenge.seed', message: `seed[${i}] 内容超过 ${MAX_SEED_FILE} 字符` })
    }
    total += c.length
  })
  if (total > MAX_SEED_TOTAL) {
    errors.push({ file, line, rule: 'block.challenge.seed', message: `seed 合计 ${total} 字符，超过上限 ${MAX_SEED_TOTAL}` })
  }
}

function validateDoc(file, text, schema) {
  const errors = []
  const fm = parseFrontmatter(text)
  if (!fm) {
    errors.push({ file, line: 1, rule: 'frontmatter.missing', message: '缺少 frontmatter（文件应以 --- 开头并以 --- 结束）' })
    return { meta: null, errors, shellIds: [] }
  }
  const { meta, keyLine } = fm
  for (const [k, spec] of Object.entries(schema.fields)) {
    const present = Object.prototype.hasOwnProperty.call(meta, k)
    if (spec.required && !present) {
      errors.push({ file, line: fm.startLine, rule: 'frontmatter.required', message: `缺少必填键 ${k}` })
      continue
    }
    if (!present) continue
    const val = meta[k]
    const ln = keyLine[k] ?? fm.startLine
    if (spec.type === 'integer' && !/^-?\d+$/.test(val)) {
      errors.push({ file, line: ln, rule: 'frontmatter.type', message: `键 ${k} 应为整数，实际 "${val}"` })
    }
    if (spec.enum && !spec.enum.includes(val)) {
      errors.push({ file, line: ln, rule: 'frontmatter.enum', message: `键 ${k} 取值 "${val}" 不在允许值内（${spec.enum.join(' / ')}）` })
    }
  }
  for (const k of Object.keys(meta)) {
    if (!(k in schema.fields)) {
      errors.push({ file, line: keyLine[k] ?? fm.startLine, rule: 'frontmatter.unknown', message: `未声明的 frontmatter 键 ${k}` })
    }
  }
  const allowed = new Set(schema.blocks)
  const structured = new Set(schema.structuredBlocks)
  const shellIds = []
  for (const b of parseBlocks(text)) {
    if (!allowed.has(b.lang)) {
      errors.push({ file, line: b.startLine, rule: 'block.unknown', message: `未登记的块类型 "${b.lang}"` })
      continue
    }
    if (!structured.has(b.lang)) continue
    let data
    try {
      data = JSON.parse(b.content)
    } catch (e) {
      errors.push({ file, line: b.startLine, rule: 'block.json', message: `结构化块 ${b.lang} 的 JSON 不合法：${e.message}` })
      continue
    }
    if (b.lang === 'quiz') validateQuizBlock(data, file, b.startLine, errors)
    if (b.lang === 'code-challenge') {
      validateShellBlock(data, file, b.startLine, errors)
      if (data && data.kind === 'shell' && typeof data.id === 'string' && data.id) {
        shellIds.push({ id: data.id, file, line: b.startLine })
      }
    }
  }
  return { meta, errors, shellIds }
}

export async function validateContent({ scope = 'content' } = {}) {
  const schema = JSON.parse(await readFile(SCHEMA_PATH, 'utf8'))
  let whitelist = []
  try {
    const raw = JSON.parse(await readFile(WHITELIST_PATH, 'utf8'))
    whitelist = Array.isArray(raw) ? raw : (raw.entries ?? [])
  } catch {
    whitelist = []
  }
  const files = await walk(join(ROOT, scope))
  const errors = []
  const docs = []
  const shellIds = []

  for (const abs of files) {
    const file = rel(abs)
    const text = await readFile(abs, 'utf8')
    const { meta, errors: errs, shellIds: ids } = validateDoc(file, text, schema)
    errors.push(...errs)
    shellIds.push(...ids)
    docs.push({ file, text, meta })
  }

  // 块 id 全仓唯一（沿用 check_shell_cases 的规则）
  const seenId = new Map()
  for (const s of shellIds) {
    if (seenId.has(s.id)) {
      errors.push({ file: s.file, line: s.line, rule: 'block.challenge.id', message: `块 id 重复："${s.id}"（首次出现于 ${seenId.get(s.id)}）` })
    } else {
      seenId.set(s.id, s.file)
    }
  }

  // prerequisite 引用完整性
  const titles = new Set(docs.filter((d) => d.meta && d.meta.title).map((d) => d.meta.title))
  for (const d of docs) {
    if (d.meta && d.meta.prerequisite && !titles.has(d.meta.prerequisite)) {
      const fm = parseFrontmatter(d.text)
      errors.push({
        file: d.file,
        line: fm?.keyLine?.prerequisite ?? 1,
        rule: 'frontmatter.prerequisite',
        message: `prerequisite 指向的标题不存在："${d.meta.prerequisite}"`,
      })
    }
  }

  // order 连续性（按 category 分组）
  const groups = new Map()
  for (const d of docs) {
    if (!d.meta || !d.meta.category || !/^-?\d+$/.test(d.meta.order ?? '')) continue
    const key = d.meta.category
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push({ file: d.file, order: Number(d.meta.order), line: parseFrontmatter(d.text)?.keyLine?.order ?? 1 })
  }
  for (const [category, items] of groups) {
    const seen = new Map()
    for (const it of items) {
      if (seen.has(it.order)) {
        errors.push({ file: it.file, line: it.line, rule: 'frontmatter.order.duplicate', message: `分类 ${category} 的 order ${it.order} 重复（同见 ${seen.get(it.order)}）` })
      } else {
        seen.set(it.order, it.file)
      }
    }
    const nums = [...seen.keys()].sort((a, b) => a - b)
    if (nums.length > 1 && nums[nums.length - 1] - nums[0] + 1 !== nums.length) {
      const anchor = items.find((it) => it.order === nums[nums.length - 1])
      errors.push({ file: anchor.file, line: anchor.line, rule: 'frontmatter.order.gap', message: `分类 ${category} 的 order 不连续：[${nums.join(', ')}]` })
    }
  }

  // 白名单：file+rule 命中则降级为 warning（条目必须写 reason）
  const wl = new Map()
  for (const e of whitelist) {
    wl.set(`${e.file}||${e.rule}`, e.reason)
  }
  const warnings = []
  const finalErrors = []
  for (const e of errors) {
    const key = `${e.file}||${e.rule}`
    if (wl.has(key)) {
      warnings.push({ ...e, message: `${e.message}（白名单：${wl.get(key)}）` })
    } else {
      finalErrors.push(e)
    }
  }

  const sortFn = (a, b) => a.file.localeCompare(b.file) || a.line - b.line || a.rule.localeCompare(b.rule)
  finalErrors.sort(sortFn)
  warnings.sort(sortFn)
  return { checked: docs.length, errors: finalErrors, warnings }
}

async function main() {
  const argv = process.argv.slice(2)
  let scope = 'content'
  let json = false
  let shrink = false
  let exec = false
  let passthrough = []
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--') { passthrough = argv.slice(i + 1); break }
    else if (argv[i] === '--scope') { scope = argv[++i] }
    else if (argv[i] === '--json') { json = true }
    else if (argv[i] === '--exec') { exec = true }
    else if (argv[i] === '--check-whitelist-shrink') { shrink = true }
  }
  if (shrink) {
    let raw = { entries: [], maxEntries: 0 }
    try { raw = JSON.parse(await readFile(WHITELIST_PATH, 'utf8')) } catch { /* 无白名单视为空 */ }
    const entries = Array.isArray(raw) ? raw : (raw.entries ?? [])
    const max = Array.isArray(raw) ? entries.length : (raw.maxEntries ?? 0)
    if (entries.length > max) {
      console.error(`白名单膨胀：${entries.length} 条 > 上限 ${max}。只允许减少——请修正内容，而不是加白名单。`)
      process.exitCode = 1
      return
    }
    console.log(`白名单：${entries.length} 条 ≤ 上限 ${max} ✓`)
    return
  }
  const result = await validateContent({ scope })
  if (json) {
    console.log(JSON.stringify(result, null, 2))
  } else {
    const shown = result.errors.slice(0, 200)
    for (const e of shown) console.log(`${e.file}:${e.line}  ${e.rule}  ${e.message}`)
    if (result.errors.length > shown.length) console.log(`… 其余 ${result.errors.length - shown.length} 条省略（用 --json 看全部）`)
    for (const w of result.warnings) console.log(`(warn) ${w.file}:${w.line}  ${w.rule}  ${w.message}`)
    console.log(`\n合计：${result.errors.length} error / ${result.warnings.length} warning（检查 ${result.checked} 篇）`)
  }
  process.exitCode = result.errors.length ? 1 : 0
  if (exec) {
    if (result.errors.length) return // 静态没过就不进执行档
    const py = process.env.PYTHON ?? 'python'
    const run = spawnSync(py, [join(ROOT, 'scripts', 'verify_course.py'), ...passthrough], { stdio: 'inherit' })
    if (run.error) {
      console.error('无法运行 verify_course.py：' + run.error.message)
      process.exitCode = 1
      return
    }
    process.exitCode = run.status ?? 1
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => { console.error(e); process.exit(1) })
}
