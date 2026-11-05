#!/usr/bin/env node
/**
 * 仓库侧内容漂移检查（WORK-074 C4）。
 *
 * <p>部署机看不到仓库里的 `content/`，所以"源改了但没导入"只能**在内容所在处**判定：
 * 这里算当前内容目录的哈希，与后端记录"最近一次导入的 sourceHash"比对，不一致即漂移、退出 1。
 *
 * <p>可用作发布前 / 手动检查的拦截；`--hash-only` 只打印当前哈希（离线自检）。
 *
 * 用法：
 *   node scripts/check-content-drift.mjs --hash-only
 *   PAIDEIA_ADMIN_USER=… PAIDEIA_ADMIN_PASSWORD=… node scripts/check-content-drift.mjs
 *   node scripts/check-content-drift.mjs --dir content/courses --base-url http://host:8081
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
let dir = path.join(ROOT, 'content', 'courses')
let baseUrl = process.env.PAIDEIA_BASE_URL ?? 'http://127.0.0.1:8081'
let hashOnly = false
const argv = process.argv.slice(2)
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--dir') dir = path.resolve(ROOT, argv[++i])
  else if (argv[i] === '--base-url') baseUrl = argv[++i]
  else if (argv[i] === '--hash-only') hashOnly = true
}

function contentHash(root) {
  const files = []
  const collect = (d) => {
    for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, entry.name)
      if (entry.isDirectory()) collect(full)
      else if (entry.name.endsWith('.md') && entry.name !== 'README.md') files.push(full)
    }
  }
  collect(root)
  files.sort()
  const hash = crypto.createHash('sha256')
  for (const file of files) {
    hash.update(path.relative(root, file).split(path.sep).join('/'))
    hash.update('\0')
    hash.update(fs.readFileSync(file))
    hash.update('\0')
  }
  return hash.digest('hex')
}

const current = contentHash(dir)
if (hashOnly) {
  console.log(current)
  process.exit(0)
}

const user = process.env.PAIDEIA_ADMIN_USER
const password = process.env.PAIDEIA_ADMIN_PASSWORD
if (!user || !password) {
  console.error('缺少 PAIDEIA_ADMIN_USER / PAIDEIA_ADMIN_PASSWORD')
  process.exit(2)
}
if (!baseUrl.endsWith('/')) baseUrl += '/'

async function call(method, route, token, body) {
  const response = await fetch(new URL(route.replace(/^\//, ''), baseUrl), {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : null
  if (!response.ok) throw new Error(`${method} ${route} → HTTP ${response.status}：${text.slice(0, 200)}`)
  return payload?.data ?? payload
}

const tokens = await call('POST', '/api/v1/auth/login', null, { identifier: user, password })
const recent = await call('GET', '/api/v1/ops/admin/content-import', tokens.accessToken)
const last = Array.isArray(recent) ? recent[0] : null
if (!last) {
  console.error('后端没有导入记录；先跑一次 scripts/import-course.mjs')
  process.exit(2)
}
if (last.sourceHash === current) {
  console.log('无漂移：当前内容哈希与最近一次导入一致')
  process.exit(0)
}
console.error(
  `检测到导入漂移：\n  当前内容哈希 ${current.slice(0, 12)}…\n  最近导入哈希 ${String(last.sourceHash).slice(0, 12)}…（导入于 ${last.at}）\n请重新运行 scripts/import-course.mjs。`,
)
process.exit(1)
