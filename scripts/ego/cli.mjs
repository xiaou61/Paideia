#!/usr/bin/env node
/**
 * WORK-059 命令入口（系统 Node 运行）。
 *
 *   node scripts/ego/cli.mjs --module knowledge --read-only
 *   node scripts/ego/cli.mjs --all --read-only
 *   node scripts/ego/cli.mjs --module notes --write
 *   node scripts/ego/cli.mjs --list
 *
 * 它把参数写进 scripts/ego/.run.json，再用 `ego-browser nodejs < run.mjs` 启动真正的执行器。
 */
import { writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { modules } from './modules.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '../..')

function parse(argv) {
  const out = { modules: [], all: false, mode: 'read-only', baseUrl: null, list: false, help: false }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--all') out.all = true
    else if (arg === '--write') out.mode = 'write'
    else if (arg === '--read-only') out.mode = 'read-only'
    else if (arg === '--list') out.list = true
    else if (arg === '--help' || arg === '-h') out.help = true
    else if (arg === '--module') {
      const value = argv[++i]
      if (!value) throw new Error('--module 需要模块 id')
      out.modules.push(...value.split(',').map((s) => s.trim()).filter(Boolean))
    } else if (arg === '--base-url') out.baseUrl = argv[++i]
    else throw new Error(`未知参数：${arg}`)
  }
  return out
}

function help() {
  console.log(`用法：
  node scripts/ego/cli.mjs --list
  node scripts/ego/cli.mjs --module <id>[,<id>] [--read-only|--write]
  node scripts/ego/cli.mjs --all [--read-only|--write]

模块 id：${modules.map((m) => m.id).join(', ')}`)
}

let args
try {
  args = parse(process.argv.slice(2))
} catch (error) {
  console.error(error.message)
  help()
  process.exit(2)
}

if (args.help) {
  help()
  process.exit(0)
}

if (args.list) {
  for (const mod of modules) {
    console.log(`${mod.id.padEnd(18)} ${mod.side.padEnd(8)} ${mod.name}（${mod.routes.length} 条路由）`)
  }
  process.exit(0)
}

if (!args.all && !args.modules.length) {
  console.error('要么 --all，要么 --module <id>')
  help()
  process.exit(2)
}

const unknown = args.modules.filter((id) => !modules.some((m) => m.id === id))
if (unknown.length) {
  console.error(`未知模块：${unknown.join(', ')}`)
  process.exit(2)
}

writeFileSync(
  path.join(HERE, '.run.json'),
  JSON.stringify({ all: args.all, modules: args.modules, mode: args.mode, baseUrl: args.baseUrl }, null, 2),
  'utf8',
)

// ego 的 stdin 脚本以 [eval] 为基准解析相对导入；用绝对 file URL 导入入口，
// 入口自己的相对导入就会按真实文件位置解析。路径必须保持 ASCII。
const entry = pathToFileURL(path.join(HERE, 'run.mjs')).href
const result = spawnSync('ego-browser', ['nodejs'], {
  cwd: ROOT,
  input: `await import('${entry}')\n`,
  stdio: ['pipe', 'inherit', 'inherit'],
  shell: true,
})

process.exit(result.status ?? 1)
