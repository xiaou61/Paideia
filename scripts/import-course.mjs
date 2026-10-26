#!/usr/bin/env node
/**
 * 课程导入脚本（WORK-020 TASK-005）。
 *
 * 走**已有的**管理端 API，不新增写接口：
 *   登录 → 取分类树 → 缺的分类就建（带 line_kind）→ 按「分类 + 标题」幂等建/更条目
 *   → 按 order 顺序发布（保证 published_at 单调，于是"上一篇/下一篇"与课号一致）
 *   → 建 prerequisite 关系。
 *
 * 三条纪律：
 * 1. **只碰本脚本负责的分类**：不认识的分类绝不写入，也不删除任何条目——内容源被删掉时
 *    线上条目保持原样（宁可留一条旧课，也不静默删数据）。
 * 2. **幂等**：标题、正文、站序、状态都相同时不发请求；第二次运行应当 `created 0 / updated 0`。
 * 3. **凭据只从环境变量读**，不写文件、不打印。
 *
 * 用法：
 *   PAIDEIA_ADMIN_USER=... PAIDEIA_ADMIN_PASSWORD=... node scripts/import-course.mjs --dry-run
 *   ... node scripts/import-course.mjs                     # 默认导 content/courses 下全部语言
 *   ... node scripts/import-course.mjs --dir content/courses/python
 *   ... node scripts/import-course.mjs --base-url http://127.0.0.1:8081
 */

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * 线的声明**全部来自课程文件**，脚本里没有任何一门课的名字。
 *
 * 总览（order 0）的 frontmatter 写：`category`（线的 slug）、`line_name`（线的名字）、
 * `line_kind`（course / reading）、`line_sort`（线在清单里的顺序）。缺省时按
 * "`course` + 总览标题 + 目录名做 slug" 兜底——仍然不需要改代码。
 */
function lineSpecOf(language, lessons) {
  const overview = lessons.find((lesson) => Number(lesson.meta.order) === 0)
  const meta = overview?.meta ?? lessons[0].meta
  const slug = meta.category
  if (!slug) {
    throw new Error(`${language}/ 的课程文件缺少 frontmatter category`)
  }
  const mode = meta.line_mode ?? 'theory'
  if (mode !== 'theory' && mode !== 'practice') {
    throw new Error(`${language}/ 的 line_mode 只能是 theory / practice，实际是 ${mode}`)
  }
  // 实战轨道必须声明验收方式：否则关卡做完了判不了对错（WORK-038 REQ-006）
  if (mode === 'practice' && !meta.verify) {
    throw new Error(`${language}/ 是实战轨道，frontmatter 必须写 verify（sql / run / project）`)
  }
  return {
    slug,
    name: meta.line_name ?? overview?.meta.title ?? slug,
    // 轨道列表卡片上的一句话介绍（WORK-024）
    summary: meta.line_summary ?? null,
    kind: meta.line_kind ?? 'course',
    sortOrder: Number(meta.line_sort ?? 100),
    // 理论 / 实战（WORK-038）；缺省 theory，老内容不用改
    mode,
    // 实战轨道指向的理论轨道：只用于"建议先看 X"，不构成门禁
    theoryTrackSlug: meta.line_theory ?? null,
    verify: meta.verify ?? null,
  }
}

/** 章节 → 默认难度。作者可以逐课用 `level:` 覆盖。 */
function levelOfChapter(chapter, index) {
  if (index === 0) return 'beginner'
  if (chapter === '入门' || chapter === '基础语法') return 'beginner'
  if (chapter === '面向对象' || chapter === '集合与字符串') return 'intermediate'
  return 'advanced'
}

function parseArgs(argv) {
  const options = {
    dir: path.join(ROOT, 'content', 'courses'),
    baseUrl: process.env.PAIDEIA_BASE_URL ?? 'http://127.0.0.1:8081',
    dryRun: false,
  }
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index]
    if (arg === '--dry-run') options.dryRun = true
    else if (arg === '--dir') options.dir = path.resolve(ROOT, argv[++index])
    else if (arg === '--base-url') options.baseUrl = argv[++index]
    else if (arg === '--help' || arg === '-h') {
      console.log('用法：node scripts/import-course.mjs [--dry-run] [--dir <目录>] [--base-url <地址>]')
      process.exit(0)
    } else {
      console.error(`不认识的参数：${arg}`)
      process.exit(2)
    }
  }
  if (!options.baseUrl.endsWith('/')) options.baseUrl += '/'
  return options
}

/* ------------------------------------------------------------------ 课程解析 */

/** 极简 frontmatter：只认 `key: value`，与 verify_course.py 同一口径。 */
function parseLesson(file) {
  const text = fs.readFileSync(file, 'utf8')
  if (!text.startsWith('---')) {
    throw new Error(`${file}：缺少 frontmatter`)
  }
  const end = text.indexOf('\n---', 3)
  if (end < 0) {
    throw new Error(`${file}：frontmatter 没有结束标记`)
  }
  const meta = {}
  for (const line of text.slice(3, end).split('\n')) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue
    const split = line.indexOf(':')
    if (split < 0) continue
    meta[line.slice(0, split).trim()] = line.slice(split + 1).trim()
  }
  for (const key of ['title', 'category', 'order', 'status']) {
    if (!meta[key]) throw new Error(`${file}：frontmatter 缺少 ${key}`)
  }
  return { file, meta, body: text.slice(end + 4).replace(/^\n+/, '') }
}

function collectLessons(dir) {
  const languages = new Map()
  // `--dir` 直接指向某一条线的目录时（目录里就是 .md），把它当成一条线。
  // 这一条决定了"新增一条线只跑一条命令"，不必先建一个上层目录。
  if (fs.existsSync(dir) && fs.readdirSync(dir).some((name) => name.endsWith('.md'))) {
    const lessons = fs
      .readdirSync(dir)
      .filter((name) => name.endsWith('.md') && name !== 'README.md')
      .map((name) => parseLesson(path.join(dir, name)))
      .sort((a, b) => Number(a.meta.order) - Number(b.meta.order))
    if (lessons.length > 0) {
      languages.set(path.basename(dir), lessons)
      return languages
    }
  }
  const entries = fs.existsSync(dir) ? fs.readdirSync(dir, { withFileTypes: true }) : []
  for (const entry of entries) {
    if (!entry.isDirectory()) continue
    const language = entry.name
    const files = fs
      .readdirSync(path.join(dir, language))
      .filter((name) => name.endsWith('.md') && name !== 'README.md')
      .map((name) => path.join(dir, language, name))
    if (files.length === 0) continue
    const lessons = files.map(parseLesson).sort((a, b) => Number(a.meta.order) - Number(b.meta.order))
    languages.set(language, lessons)
  }
  return languages
}

/* ------------------------------------------------------------------ HTTP */

function createClient(baseUrl, token) {
  async function call(method, route, body) {
    const response = await fetch(new URL(route.replace(/^\//, ''), baseUrl), {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    let payload = null
    try {
      payload = text ? JSON.parse(text) : null
    } catch {
      payload = { raw: text }
    }
    if (!response.ok) {
      throw new Error(`${method} ${route} → HTTP ${response.status}：${text.slice(0, 300)}`)
    }
    return payload?.data ?? payload
  }
  return {
    get: (route) => call('GET', route),
    post: (route, body) => call('POST', route, body),
    put: (route, body) => call('PUT', route, body),
  }
}

async function login(baseUrl) {
  const user = process.env.PAIDEIA_ADMIN_USER
  const password = process.env.PAIDEIA_ADMIN_PASSWORD
  if (!user || !password) {
    throw new Error('缺少 PAIDEIA_ADMIN_USER / PAIDEIA_ADMIN_PASSWORD（凭据不进仓库，只从环境变量读）')
  }
  const client = createClient(baseUrl, null)
  const tokens = await client.post('/api/v1/auth/login', { identifier: user, password })
  if (!tokens?.accessToken) {
    throw new Error('登录没有拿到 accessToken')
  }
  return createClient(baseUrl, tokens.accessToken)
}

/* ------------------------------------------------------------------ 导入 */

/** slug 全库唯一，所以按 slug 找就够，不必关心层级。 */
function findCategory(nodes, slug) {
  if (!Array.isArray(nodes)) return null
  for (const node of nodes) {
    if (node.slug === slug) return node
    const hit = findCategory(node.children, slug)
    if (hit) return hit
  }
  return null
}

async function ensureCategory(client, language, lessons, options) {
  const spec = lineSpecOf(language, lessons)
  const tree = await client.get('/api/v1/knowledge/admin/categories')
  const existing = findCategory(tree, spec.slug)
  if (existing) {
    return existing
  }
  if (options.dryRun) {
    console.log(`  [dry-run] 将创建分类 ${spec.name}（${spec.slug}, ${spec.kind}）`)
    return { id: -1, slug: spec.slug, name: spec.name, children: [] }
  }
  const id = await client.post('/api/v1/knowledge/admin/categories', {
    parentId: null,
    name: spec.name,
    slug: spec.slug,
    sortOrder: spec.sortOrder,
    lineKind: spec.kind,
  })
  console.log(`  + 分类 ${spec.name}（id=${id}）`)
  return { id, slug: spec.slug, name: spec.name, children: [] }
}

async function existingEntries(client) {
  const page = await client.get('/api/v1/knowledge/admin/entries?size=200')
  return page?.items ?? []
}

async function importLanguage(client, language, lessons, options, allEntries) {
  // 轨道声明与分类声明同一份来源（课程文件的 frontmatter），这里重算一次而不是传给两层
  const spec = lineSpecOf(language, lessons)
  const category = await ensureCategory(client, language, lessons, options)
  if (category.id < 0) {
    // dry-run 且分类不存在：把计划打出来就够
    for (const lesson of lessons) {
      console.log(`  [dry-run] 将创建/更新条目 ${lesson.meta.title}（order=${lesson.meta.order}）`)
    }
    return { created: 0, updated: 0, relations: 0 }
  }

  const inCategory = allEntries.filter((entry) => entry.categoryId === category.id)
  const byTitle = new Map(inCategory.map((entry) => [entry.title, entry]))
  const created = []
  let updates = 0

  for (const lesson of lessons) {
    // 内容即代码：frontmatter 写 published 就是 published。新建与更新用同一份 payload，
    // 不再"先草稿、回头再统一发布"——那个两段式正是在更新路径上把已发布条目降级的原因。
    const payload = {
      title: lesson.meta.title,
      body: lesson.body,
      categoryId: category.id,
      status: lesson.meta.status === 'published' ? 'published' : 'draft',
      sortOrder: Number(lesson.meta.order),
    }
    const existing = byTitle.get(lesson.meta.title)
    if (!existing) {
      if (options.dryRun) {
        console.log(`  [dry-run] 将创建 ${lesson.meta.title}`)
      } else {
        const id = await client.post('/api/v1/knowledge/admin/entries', payload)
        created.push({ ...payload, id })
        console.log(`  + ${lesson.meta.title}`)
      }
      continue
    }
    const detail = await client.get(`/api/v1/knowledge/admin/entries/${existing.id}`)
    const sameBody = (detail.body ?? '') === lesson.body
    const sameOrder = (detail.sortOrder ?? null) === payload.sortOrder
    if (sameBody && sameOrder && detail.status === payload.status) {
      continue
    }
    if (options.dryRun) {
      console.log(`  [dry-run] 将更新 ${lesson.meta.title}`)
    } else {
      await client.put(`/api/v1/knowledge/admin/entries/${existing.id}`, payload)
      console.log(`  ~ ${lesson.meta.title}${payload.status === 'published' ? '' : '（草稿）'}`)
    }
    updates++
  }

  // 新建的条目在上面已经按 frontmatter 的状态落库，这里不再补发。
  // （原来的两段式"先草稿再统一发布"只在首次导入时保证过 published_at 单调；
  //  代价是更新路径会把已发布条目降级，那个代价远大于收益。）

  let relations = 0
  for (const lesson of lessons) {
    const prerequisiteTitle = lesson.meta.prerequisite
    if (!prerequisiteTitle) continue
    const from = byTitle.get(prerequisiteTitle) ?? created.find((item) => item.title === prerequisiteTitle)
    const to = byTitle.get(lesson.meta.title) ?? created.find((item) => item.title === lesson.meta.title)
    if (!from || !to) {
      console.warn(`  ! 前置关系跳过：${prerequisiteTitle} → ${lesson.meta.title}`)
      continue
    }
    if (options.dryRun) {
      console.log(`  [dry-run] 将建关系 ${prerequisiteTitle} → ${lesson.meta.title}`)
      continue
    }
    try {
      await client.post('/api/v1/knowledge/admin/relations', {
        fromEntryId: from.id,
        toEntryId: to.id,
        relationType: 'prerequisite',
      })
      relations++
    } catch (error) {
      // 唯一键冲突 = 关系已存在，是幂等的正常情况
      if (!String(error.message).includes('409')) {
        console.warn(`  ! 关系未建立：${prerequisiteTitle} → ${lesson.meta.title}：${error.message}`)
      }
    }
  }

  // 学习轨道（WORK-024）：把这条线登记成一条**轨道 + 关卡序列**。
  //
  // 与 WORK-020 的区别：那时线只是分类标记；现在要有自己的顺序、章节与前置依赖，
  // 才能画成关卡地图。前置默认取"上一关"——轨道是有序的，不需要作者逐关手写 requires。
  //
  // **导读（order 0）不是关卡**：它没有小测也没有动手，永远无法通关。以前把它也登记成
  // seq 0，于是每条线的"下一关"永远停在导读、进度永远到不了 100%，地图上还多出一格。
  const stageLessons = lessons.filter((lesson) => Number(lesson.meta.order) !== 0)
  if (stageLessons.length === 0) {
    console.warn(`  ! ${spec.slug} 没有可登记的关卡（只有导读）`)
    return { created: created.length, updated: updates, relations, stages: 0 }
  }
  const stageTargets = stageLessons.map((lesson) => {
    const match = byTitle.get(lesson.meta.title)
    const id = match?.id ?? created.find((item) => item.title === lesson.meta.title)?.id
    return { lesson, entryId: id }
  }).filter((item) => item.entryId !== undefined)

  const stages = stageTargets.map((item, index) => ({
    entryId: item.entryId,
    seq: Number(item.lesson.meta.order ?? index),
    chapter: item.lesson.meta.chapter ?? '',
    requiresEntryId: index === 0 ? null : stageTargets[index - 1].entryId,
    // 难度：作者的 frontmatter 优先，缺省按章节推——老内容不改也有合理值
    level: item.lesson.meta.level ?? levelOfChapter(item.lesson.meta.chapter ?? '', index),
  }))
  // 导读永远不可通关；一旦被漏回来，地图的"下一关"会永远停在它身上。
  if (stages.some((stage) => stage.seq === 0)) {
    throw new Error(`${spec.slug}：导读（order 0）不能登记成关卡`)
  }

  if (options.dryRun) {
    console.log(`  [dry-run] 将登记轨道 ${spec.slug}（${stages.length} 关，${spec.mode}）`)
  } else if (options.track !== false) {
    // 配对引用指向不存在的轨道时只警告不阻断：内容可以先写、后导入（WORK-038 已知限制）
    if (spec.theoryTrackSlug) {
      const known = await client.get('/api/v1/learn/tracks')
      if (!known.some((item) => item.slug === spec.theoryTrackSlug)) {
        console.warn(`  ! ${spec.slug} 的 line_theory 指向不存在的轨道 ${spec.theoryTrackSlug}`)
      }
    }
    await client.post('/api/v1/knowledge/admin/tracks', {
      slug: spec.slug,
      name: spec.name,
      summary: spec.summary,
      kind: spec.kind,
      sortOrder: spec.sortOrder,
      mode: spec.mode,
      theoryTrackSlug: spec.theoryTrackSlug,
      stages,
    })
    console.log(`  ~ 轨道 ${spec.slug}（${stages.length} 关，${spec.mode}）`)
  }

  return { created: created.length, updated: updates, relations, stages: stages.length }
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  const languages = collectLessons(options.dir)
  if (languages.size === 0) {
    console.log(`没有找到课程（${options.dir}）`)
    return
  }

  const client = options.dryRun && !process.env.PAIDEIA_ADMIN_USER ? null : await login(options.baseUrl)
  const entries = client ? await existingEntries(client) : []
  const totals = { created: 0, updated: 0, relations: 0, stages: 0 }

  for (const [language, lessons] of languages) {
    console.log(`${language}：${lessons.length} 课`)
    const dryClient = client ?? {
      get: async () => [],
      
      post: async () => 0,
      put: async () => 0,
    }
    const result = await importLanguage(dryClient, language, lessons, options, entries)
    totals.created += result.created
    totals.updated += result.updated
    totals.relations += result.relations
    totals.stages += result.stages ?? 0
  }

  console.log(
    `\n${options.dryRun ? '[dry-run] ' : ''}created ${totals.created} / updated ${totals.updated} / relations ${totals.relations}`,
  )
}

main().catch((error) => {
  console.error(error.message)
  process.exit(1)
})
