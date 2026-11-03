/**
 * WORK-059 runner：在 ego-browser 的 Node 运行时里执行。
 *
 * 正常入口是 `node scripts/ego/cli.mjs ...`；cli 会把参数写进 `.run.json` 再用
 * `ego-browser nodejs < run.mjs` 启动本文件。
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { createSession, ROOT } from './harness.mjs'
import { modules as ALL_MODULES } from './modules.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const RUN_FILE = path.join(HERE, '.run.json')
const CONFIG_FILE = path.join(ROOT, '.secrets/ego.local.json')
const RUNS_DIR = path.join(ROOT, '.agent/testing/runs')
const ARTIFACTS_ROOT = path.join(ROOT, '.agent/testing/.artifacts')

function stamp(date = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`
}

function git(args) {
  try {
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' }).trim()
  } catch {
    return ''
  }
}

function redactUrl(url) {
  return String(url).replace(/^https?:\/\/[^/]+/i, '')
}

function redactText(value) {
  return String(value).replace(/https?:\/\/[^/\s"]+/gi, 'online')
}

async function readJson(file) {
  return JSON.parse(await readFile(file, 'utf8'))
}

async function writeReport({ runId, target, mode, startedAt, durationMs, results, failures, cleanupFailures, loginError }) {
  await mkdir(RUNS_DIR, { recursive: true })
  const file = path.join(RUNS_DIR, `${runId}-ego-${target}.md`)
  const commit = git(['rev-parse', '--short', 'HEAD']) || 'unknown'
  const dirty = git(['status', '--porcelain']).length > 0
  const passed = results.filter((r) => r.status === 'passed').length
  const failed = results.filter((r) => r.status === 'failed').length
  const skipped = results.filter((r) => r.status === 'skipped').length

  const lines = [
    '---',
    'artifact: test-run',
    `run_id: ${runId}`,
    'layer: e2e',
    `target: ${target}`,
    `commit: ${commit}`,
    `workspace_dirty: ${dirty}`,
    `status: ${failed || loginError ? 'failed' : 'passed'}`,
    `started: ${startedAt}`,
    `duration_s: ${Math.round(durationMs / 1000)}`,
    '---',
    '',
    `# 自动化点击 ${runId}`,
    '',
    '## 环境',
    '',
    `- 代码：\`${commit}\`${dirty ? '（工作区有未提交改动）' : ''}`,
    `- 目标：${target}`,
    `- 模式：${mode}`,
    '',
    '## 结果',
    '',
    '| 模块 | 用例 | 通过 | 失败 | 跳过 | 耗时 |',
    '| --- | --- | --- | --- | --- | --- |',
  ]

  const byModule = new Map()
  for (const result of results) {
    const row = byModule.get(result.module) ?? { total: 0, passed: 0, failed: 0, skipped: 0, durationMs: 0 }
    row.total += 1
    row[result.status] += 1
    row.durationMs += result.durationMs
    byModule.set(result.module, row)
  }
  for (const [module, row] of byModule) {
    lines.push(`| ${module} | ${row.total} | ${row.passed} | ${row.failed} | ${row.skipped} | ${(row.durationMs / 1000).toFixed(1)}s |`)
  }
  lines.push('', `合计：${results.length} 条，通过 ${passed}，失败 ${failed}，跳过 ${skipped}。`, '')

  if (loginError) {
    lines.push('## 登录失败', '', loginError, '')
  }

  lines.push('## 失败明细', '')
  if (!failures.length) {
    lines.push('无。', '')
  } else {
    for (const failure of failures) {
      lines.push(`### ${failure.module} / ${failure.case}`, '')
      lines.push(`- 步骤：${failure.step ?? '未知'}`)
      lines.push(`- 地址：${failure.url ?? '未知'}`)
      if (failure.screenshot) lines.push(`- 截图：${failure.screenshot}`)
      lines.push(`- 错误：${failure.error}`, '')
    }
  }

  if (cleanupFailures.length) {
    lines.push('## 清理失败（需要人工处理）', '')
    for (const item of cleanupFailures) lines.push(`- ${item.module} / ${item.case}：${item.error}`)
    lines.push('')
  }

  await writeFile(file, lines.join('\n'), 'utf8')
  return file
}

const args = await readJson(RUN_FILE)
const config = await readJson(CONFIG_FILE)
const baseUrl = (args.baseUrl ?? config.baseUrl ?? '').replace(/\/+$/, '')
if (!baseUrl) throw new Error('缺少 baseUrl：在 .secrets/ego.local.json 里配置，或用 --base-url 传入')

const target = /localhost|127\.0\.0\.1/.test(baseUrl) ? 'local' : 'online'
const mode = args.mode === 'write' ? 'write' : 'read-only'
const selected = args.all ? ALL_MODULES : ALL_MODULES.filter((m) => args.modules.includes(m.id))
if (!selected.length) throw new Error(`没有匹配的模块：${args.modules.join(', ') || '(空)'}`)

const runId = stamp()
const startedAt = new Date().toISOString()
const startedMs = Date.now()
const artifactsDir = path.join(ARTIFACTS_ROOT, runId)
await mkdir(artifactsDir, { recursive: true })

const space = await taskSpace(`paideia-ego-${runId}`, config.profileId ? { profileId: config.profileId } : undefined)
const results = []
const failures = []
const cleanupFailures = []

async function runSide(side, page) {
  const sideModules = selected.filter((m) => m.side === side)
  if (!sideModules.length) return
  const credentials = config[side]
  if (!credentials?.username || !credentials?.password) {
    throw new Error(`.secrets/ego.local.json 缺少 ${side} 账号`)
  }
  const loginSession = createSession({ page, baseUrl, role: side, runId, artifactsDir })
  await loginSession.login(credentials)

  for (const mod of sideModules) {
    const scenarioPath = path.join(HERE, 'scenarios', `${mod.scenario}.mjs`)
    const scenario = await import(pathToFileURL(scenarioPath).href)
    for (const item of scenario.cases) {
      if (item.write && mode !== 'write') {
        results.push({ module: mod.id, case: item.id, title: item.title, status: 'skipped', durationMs: 0 })
        console.log(`- ${mod.id}/${item.id}（写入用例，只读模式跳过）`)
        continue
      }
      const session = createSession({ page, baseUrl, role: side, runId, artifactsDir })
      const caseStart = Date.now()
      try {
        if (item.start) await session.goto(item.start)
        // 菜单、弹窗、助手面板都会跨页面保留；先收掉，避免遮挡点击目标。
        await session.hotkey('Escape').catch(() => {})
        await item.run(session)
        results.push({ module: mod.id, case: item.id, title: item.title, status: 'passed', durationMs: Date.now() - caseStart })
        console.log(`✓ ${mod.id}/${item.id}（${((Date.now() - caseStart) / 1000).toFixed(1)}s）`)
      } catch (error) {
        let screenshot = ''
        try {
          screenshot = await session.shot(`${mod.id}-${item.id}`)
        } catch {
          // 截图失败不覆盖原始错误
        }
        const failure = {
          module: mod.id,
          case: item.id,
          step: error.step ?? session.steps.at(-1)?.name ?? '未知',
          url: redactUrl(await page.url().catch(() => '')),
          screenshot: screenshot ? path.relative(ROOT, screenshot) : '',
          error: redactText(error.message),
        }
        failures.push(failure)
        results.push({ module: mod.id, case: item.id, title: item.title, status: 'failed', durationMs: Date.now() - caseStart })
        console.log(`✗ ${mod.id}/${item.id}：${error.message}`)
      } finally {
        if (item.cleanup) {
          try {
            await item.cleanup(session)
          } catch (error) {
            cleanupFailures.push({ module: mod.id, case: item.id, error: error.message })
            console.log(`! ${mod.id}/${item.id} 清理失败：${error.message}`)
          }
        }
      }
    }
  }
}

let loginError = ''
try {
  const learnerModules = selected.filter((m) => m.side === 'learner')
  const adminModules = selected.filter((m) => m.side === 'admin')
  if (learnerModules.length) {
    await runSide('learner', space.page('p1'))
  }
  if (adminModules.length) {
    const adminPage = (await space.pages()).find((p) => p.label === 'p2') ?? (await space.newPage())
    await runSide('admin', adminPage)
  }
} catch (error) {
  loginError = error.message
  console.error(`登录/准备失败：${error.message}`)
}

const durationMs = Date.now() - startedMs
const report = await writeReport({
  runId,
  target,
  mode,
  startedAt,
  durationMs,
  results,
  failures,
  cleanupFailures,
  loginError,
})

console.log(`\n报告：${path.relative(ROOT, report)}`)
console.log(`通过 ${results.filter((r) => r.status === 'passed').length} / 失败 ${failures.length} / 跳过 ${results.filter((r) => r.status === 'skipped').length}`)

if (failures.length || loginError) process.exitCode = 1
else if (cleanupFailures.length) process.exitCode = 2
else process.exitCode = 0

await space.finish({ keep: failures.length || loginError ? (await space.pages()).map((p) => p.label) : [] })
