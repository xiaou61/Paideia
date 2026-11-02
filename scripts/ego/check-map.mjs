#!/usr/bin/env node
/**
 * WORK-059 覆盖闸门：路由 → 模块 → 场景，三者必须对得上。
 *
 * 用法：
 *   node scripts/ego/check-map.mjs
 *   node scripts/ego/check-map.mjs --json
 *   node scripts/ego/check-map.mjs --app-router <path> --admin-router <path> --map <path> --scenarios-dir <dir>
 *
 * 退出码：0 全部通过；1 有未登记/重复/缺场景/用例不足。
 */
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '../..')

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i]
    if (!key.startsWith('--')) continue
    const name = key.slice(2)
    if (name === 'json') out.json = true
    else out[name] = argv[++i]
  }
  return out
}

async function readRoutes(file) {
  const text = await readFile(file, 'utf8')
  const routes = [...text.matchAll(/path:\s*'([^']+)'/g)].map((m) => m[1])
  return [...new Set(routes)]
}

function compare(side, routerRoutes, modules) {
  const errors = []
  const owners = new Map()
  for (const mod of modules.filter((m) => m.side === side)) {
    for (const route of mod.routes) {
      if (owners.has(route)) {
        errors.push(`${side} 路由重复归属：${route} 同时属于 ${owners.get(route)} 与 ${mod.id}`)
      } else {
        owners.set(route, mod.id)
      }
    }
  }
  const routerSet = new Set(routerRoutes)
  for (const route of routerRoutes) {
    if (!owners.has(route)) errors.push(`${side} 路由未登记：${route}`)
  }
  for (const [route, mod] of owners) {
    if (!routerSet.has(route)) errors.push(`${side} 登记了不存在的路由：${route}（模块 ${mod}）`)
  }
  return { errors, covered: routerRoutes.filter((r) => owners.has(r)).length, total: routerRoutes.length }
}

async function checkScenarios(modules, dir) {
  const errors = []
  let totalCases = 0
  for (const mod of modules) {
    const file = path.join(dir, `${mod.scenario}.mjs`)
    let loaded
    try {
      loaded = await import(pathToFileURL(file).href)
    } catch (error) {
      errors.push(`模块 ${mod.id} 场景不可加载：${path.relative(ROOT, file)}（${error.message}）`)
      continue
    }
    const cases = Array.isArray(loaded.cases) ? loaded.cases : []
    totalCases += cases.length
    if (cases.length < mod.minCases) {
      errors.push(`模块 ${mod.id} 用例不足：${cases.length} < ${mod.minCases}（${path.relative(ROOT, file)}）`)
    }
    if (loaded.meta?.module && loaded.meta.module !== mod.id) {
      errors.push(`模块 ${mod.id} 场景 meta.module=${loaded.meta.module}，与清单不一致`)
    }
  }
  return { errors, totalCases }
}

const args = parseArgs(process.argv.slice(2))
const appRouter = args['app-router'] ?? path.join(ROOT, 'frontend/apps/app/src/router.tsx')
const adminRouter = args['admin-router'] ?? path.join(ROOT, 'frontend/apps/admin/src/router.tsx')
const mapPath = args.map ?? path.join(HERE, 'modules.mjs')
const scenariosDir = args['scenarios-dir'] ?? path.join(HERE, 'scenarios')

const { modules } = await import(pathToFileURL(mapPath).href)
const appRoutes = await readRoutes(appRouter)
const adminRoutes = await readRoutes(adminRouter)

const learner = compare('learner', appRoutes, modules)
const admin = compare('admin', adminRoutes, modules)
const scenarios = await checkScenarios(modules, scenariosDir)

const errors = [...learner.errors, ...admin.errors, ...scenarios.errors]
const summary = {
  learner: { covered: learner.covered, total: learner.total },
  admin: { covered: admin.covered, total: admin.total },
  modules: modules.length,
  cases: scenarios.totalCases,
  errors,
}

if (args.json) {
  console.log(JSON.stringify(summary, null, 2))
} else {
  console.log(`学习者端路由：${learner.covered}/${learner.total}`)
  console.log(`管理端路由：${admin.covered}/${admin.total}`)
  console.log(`模块：${modules.length}，场景用例：${scenarios.totalCases}`)
  if (errors.length) {
    console.log('\n失败：')
    for (const error of errors) console.log(`- ${error}`)
  } else {
    console.log('\n通过：所有路由已登记，所有场景满足最低用例数。')
  }
}

process.exitCode = errors.length ? 1 : 0
