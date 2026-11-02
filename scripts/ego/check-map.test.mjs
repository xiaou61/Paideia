import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CHECK = path.join(HERE, 'check-map.mjs')

async function fixture({ learnerRoutes, adminRoutes, modules, cases = { x: 1, y: 1 } }) {
  const dir = await mkdtemp(path.join(tmpdir(), 'ego-map-'))
  const scenarios = path.join(dir, 'scenarios')
  await mkdir(scenarios)
  await writeFile(
    path.join(dir, 'app-router.tsx'),
    learnerRoutes.map((p) => `  { path: '${p}' },`).join('\n'),
  )
  await writeFile(
    path.join(dir, 'admin-router.tsx'),
    adminRoutes.map((p) => `  { path: '${p}' },`).join('\n'),
  )
  await writeFile(path.join(dir, 'map.mjs'), `export const modules = ${JSON.stringify(modules)}\n`)
  for (const [name, count] of Object.entries(cases)) {
    const body = Array.from({ length: count }, (_, i) => `  { id: '${name}-${i}' },`).join('\n')
    await writeFile(path.join(scenarios, `${name}.mjs`), `export const cases = [\n${body}\n]\n`)
  }
  return { dir, scenarios }
}

function run(dir, scenarios) {
  return spawnSync(
    process.execPath,
    [
      CHECK,
      '--app-router', path.join(dir, 'app-router.tsx'),
      '--admin-router', path.join(dir, 'admin-router.tsx'),
      '--map', path.join(dir, 'map.mjs'),
      '--scenarios-dir', scenarios,
    ],
    { encoding: 'utf8' },
  )
}

const baseModules = [
  { id: 'x', side: 'learner', scenario: 'x', minCases: 1, routes: ['/', '/a'] },
  { id: 'y', side: 'admin', scenario: 'y', minCases: 1, routes: ['/'] },
]

test('全部登记且有场景时通过', async () => {
  const { dir, scenarios } = await fixture({
    learnerRoutes: ['/', '/a'],
    adminRoutes: ['/'],
    modules: baseModules,
  })
  const result = run(dir, scenarios)
  assert.equal(result.status, 0, result.stdout + result.stderr)
  assert.match(result.stdout, /通过/)
})

test('路由漏登记时失败', async () => {
  const { dir, scenarios } = await fixture({
    learnerRoutes: ['/', '/a', '/missing'],
    adminRoutes: ['/'],
    modules: baseModules,
  })
  const result = run(dir, scenarios)
  assert.equal(result.status, 1)
  assert.match(result.stdout, /未登记：\/missing/)
})

test('路由重复归属时失败', async () => {
  const { dir, scenarios } = await fixture({
    learnerRoutes: ['/', '/a'],
    adminRoutes: ['/'],
    modules: [
      ...baseModules,
      { id: 'z', side: 'learner', scenario: 'x', minCases: 1, routes: ['/a'] },
    ],
  })
  const result = run(dir, scenarios)
  assert.equal(result.status, 1)
  assert.match(result.stdout, /重复归属：\/a/)
})

test('场景用例不足时失败', async () => {
  const { dir, scenarios } = await fixture({
    learnerRoutes: ['/', '/a'],
    adminRoutes: ['/'],
    modules: baseModules,
    cases: { x: 0, y: 1 },
  })
  const result = run(dir, scenarios)
  assert.equal(result.status, 1)
  assert.match(result.stdout, /模块 x 用例不足/)
})
