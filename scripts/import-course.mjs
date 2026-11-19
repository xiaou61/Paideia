#!/usr/bin/env node
/**
 * 课程导入脚本（WORK-020 TASK-005）。
 *
 * 走**已有的**管理端 API，不新增写接口：
 *   登录 → 取分类树 → 缺的父分类就建（不带 line_kind）→ 缺的分类就建（带 line_kind）
 *   → 按「分类 + 标题」幂等建/更条目 → 建 prerequisite 关系 → 登记学习轨道。
 *
 * 总览的 frontmatter 写了 `line_parent` 时，这条线会挂到该父分类下面（WORK-046 的书籍详解
 * 就是靠它把多本书收在一个根分类下）。不写则和以前一样是根分类。
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

import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { validateContent } from './check-content.mjs'

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
  // 父分类（WORK-046）：书籍详解这类"多条线挂在一个根下"的形态。
  // 只在总览里声明一次；没声明的目录保持现状，既有线一条都不用改。
  const parent = meta.line_parent ?? null
  if (parent === slug) {
    throw new Error(`${language}/ 的 line_parent 不能等于自己的 category（${slug}）`)
  }
  return {
    slug,
    name: meta.line_name ?? overview?.meta.title ?? slug,
    // 轨道列表卡片上的一句话介绍（WORK-024）
    summary: meta.line_summary ?? null,
    kind: meta.line_kind ?? 'course',
    // 有没有显式声明 line_kind = 这条线算不算学习轨道（见 desiredLineKindOf）。
    // 与 `kind` 分开：`kind` 有兜底值，缺省不等于"我声明了 course"。
    declaredKind: meta.line_kind ?? null,
    sortOrder: Number(meta.line_sort ?? 100),
    parent,
    parentName: meta.line_parent_name ?? null,
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
    // 简讲（WORK-040）：只建分类与条目，不登记学习轨道。
    // 简讲的用途是查阅，没有通关概念；建了轨道它就会以"一条线"的身份出现在 /learn。
    track: true,
  }
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index]
    if (arg === '--dry-run') options.dryRun = true
    else if (arg === '--no-track') options.track = false
    else if (arg === '--dir') options.dir = path.resolve(ROOT, argv[++index])
    else if (arg === '--base-url') options.baseUrl = argv[++index]
    else if (arg === '--help' || arg === '-h') {
      console.log(
        '用法：node scripts/import-course.mjs [--dry-run] [--no-track] [--dir <目录>] [--base-url <地址>]',
      )
      console.log('  --no-track  只建分类与条目，不登记学习轨道（简讲用）')
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

// 行尾与首尾空行不参与比较：库里既有 CRLF 也有 LF，且解析 CRLF 文件时 `text.slice(end + 4)`
// 会留下一个行首 "\r\n"（`/^\n+/` 剥不掉它）。不归一化的话每次导入都会重写同一批条目
// ——2026-10-29 实测 23 条纯空白差异。
const normalizeBody = (text) => text.replace(/\r\n/g, '\n').replace(/^\s+|\s+$/g, '')

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

/**
 * 这条线在库里落成什么 line_kind。null = 不是轨道。
 *
 * <p>判据是**总览里有没有写 line_kind**，不是命令行开关：以前靠 `--no-track`（一次性的
 * 全局开关）区分，`--dir content/reading` 会把整批线一起降成无轨道；而书籍、简讲这些
 * "只有阅读顺序"的线本来就是不写 line_kind 的，声明即语义（WORK-046 REQ-004）。
 *
 * <p>挂在父分类下不等于不是轨道：dsh-core / pi 这类核心源码解析既是 `/source` 里的一关，
 * 也仍在 `/learn` 有进度与关卡地图。两种入口共用同一份进度，不冲突。
 */
function desiredLineKindOf(spec, options) {
  return options.track === false ? null : spec.declaredKind
}

/**
 * 父分类（WORK-046）。只在 `line_parent` 声明时才有事做，返回要用的 parentId。
 *
 * <p>**绝不带 lineKind**：父分类只是一个分组容器，带了它就会变成一条没有正文的学习线
 * 出现在 `/learn`（REQ-004）。
 */
async function ensureParent(client, tree, spec, options) {
  if (spec.parent === null) {
    return null
  }
  const existing = findCategory(tree, spec.parent)
  if (existing) {
    // 父分类的名字跟着 frontmatter 走：改名（源码精读 → 核心源码解析）不用再去后台点一遍。
    // PUT 是整体替换，所以把 parentId 固定成 null、lineKind 固定成 null——父分类只是容器。
    const name = spec.parentName ?? spec.parent
    if (existing.name !== name) {
      if (options.dryRun) {
        console.log(`  [dry-run] 将改父分类名 ${existing.name} → ${name}`)
      } else {
        await client.put(`/api/v1/knowledge/admin/categories/${existing.id}`, {
          parentId: null,
          name,
          slug: existing.slug,
          sortOrder: existing.sortOrder ?? 0,
          lineKind: null,
        })
        console.log(`  ~ 父分类名 ${existing.name} → ${name}`)
      }
    }
    return existing.id
  }
  const name = spec.parentName ?? spec.parent
  if (options.dryRun) {
    console.log(`  [dry-run] 将创建父分类 ${name}（${spec.parent}）`)
    return -1
  }
  const id = await client.post('/api/v1/knowledge/admin/categories', {
    parentId: null,
    name,
    slug: spec.parent,
    sortOrder: 0,
    lineKind: null,
  })
  console.log(`  + 父分类 ${name}（id=${id}）`)
  return id
}

/**
 * 从分类树里反推某个分类现在挂在谁下面：根分类返回 null，树里没有返回 undefined。
 *
 * <p>管理端返回的节点只有 id / name / slug / lineKind / children，没有 parentId，
 * 只能靠树形结构反查。
 */
function currentParentSlug(tree, slug) {
  const walk = (nodes, parentSlug) => {
    for (const node of nodes) {
      if (node.slug === slug) return { found: true, parent: parentSlug }
      const hit = walk(node.children ?? [], node.slug)
      if (hit.found) return hit
    }
    return { found: false, parent: undefined }
  }
  const hit = walk(tree, null)
  return hit.found ? hit.parent : undefined
}

/**
 * 已经建过的分类要和 frontmatter 对齐：改线名 / 补 line_kind 不用再去后台点一遍。
 *
 * <p>比 `name`、`lineKind`、**父分类**三项——管理端返回的分类树里没有 sortOrder / parentId，
 * 拿不准的字段一律原样带回去（PUT 是整体替换，parentId 传错会把子分类挪出去）。
 * 父分类从树里反推（`currentParentSlug`）：只看名字和 line_kind 的话，一条线新增
 * `line_parent` 时会因为"名字没变"被跳过，永远留在一级目录。
 *
 * <p>`lineKind` 以前写死 `existing.lineKind ?? null`，导致早先建过、当时漏写 line_kind 的
 * 分类永远补不上（实测操作系统/算法/人工智能/开源项目简讲 4 条轨道在库里 line_kind 为 NULL，
 * 前端只能靠 /learn/tracks 兜底）。这里改成按 frontmatter 的期望值对齐：简讲（--no-track
 * 或 books 这类父分类下的线）仍写 null，其余写 spec.kind（course / reading）。
 */
async function syncCategoryName(client, existing, spec, parentId, options, currentParent) {
  const desiredLineKind = desiredLineKindOf(spec, options)
  const currentLineKind = existing.lineKind ?? null
  const nameChanged = existing.name !== spec.name
  const lineKindChanged = currentLineKind !== desiredLineKind
  // 只在**声明了** line_parent 时纠正层级：没声明的线可能被手工挂在某个容器下
  // （如「操作系统」在「计算机基础」里），把"没写"当成"要移到根"会把它连人带内容挪走。
  const parentChanged = spec.parent !== null && (currentParent ?? null) !== spec.parent
  if (!nameChanged && !lineKindChanged && !parentChanged) {
    return
  }
  if (options.dryRun) {
    if (nameChanged) console.log(`  [dry-run] 将改分类名 ${existing.name} → ${spec.name}`)
    if (lineKindChanged) {
      console.log(`  [dry-run] 将改 ${spec.slug} 的 line_kind ${currentLineKind} → ${desiredLineKind}`)
    }
    if (parentChanged) {
      console.log(`  [dry-run] 将把 ${spec.slug} 挂到 ${spec.parent ?? '根分类'} 下`)
    }
    return
  }
  await client.put(`/api/v1/knowledge/admin/categories/${existing.id}`, {
    parentId,
    name: spec.name,
    slug: existing.slug,
    sortOrder: spec.sortOrder,
    lineKind: desiredLineKind,
  })
  if (nameChanged) console.log(`  ~ 分类名 ${existing.name} → ${spec.name}`)
  if (lineKindChanged) console.log(`  ~ ${spec.slug} 的 line_kind ${currentLineKind} → ${desiredLineKind}`)
  if (parentChanged) console.log(`  ~ ${spec.slug} 挂到 ${spec.parent ?? '根分类'} 下`)
}

async function ensureCategory(client, language, lessons, options) {
  const spec = lineSpecOf(language, lessons)
  // 是不是学习轨道由总览的 line_kind 决定（书籍、简讲这类不写，见 desiredLineKindOf）。
  const tree = await client.get('/api/v1/knowledge/admin/categories')
  // 先建父分类再找自己：否则"分类手工建过、父分类没建"会留下一个游离的子分类
  const parentId = await ensureParent(client, tree, spec, options)
  const existing = findCategory(tree, spec.slug)
  if (existing) {
    const currentParent = currentParentSlug(tree, spec.slug)
    // 没声明 line_parent 的线保持现状：PUT 是整体替换，把 parentId 写成 null 等于"移到根"。
    // 所以把它现在就挂着的容器 id 原样带回去。
    const keptParentId =
      spec.parent === null && currentParent != null
        ? (findCategory(tree, currentParent)?.id ?? null)
        : parentId
    await syncCategoryName(client, existing, spec, keptParentId, options, currentParent)
    return existing
  }
  if (options.dryRun) {
    console.log(
      `  [dry-run] 将创建分类 ${spec.name}（${spec.slug}, ` +
        `${desiredLineKindOf(spec, options) ?? '无 line_kind'}）`,
    )
    return { id: -1, slug: spec.slug, name: spec.name, children: [] }
  }
  const id = await client.post('/api/v1/knowledge/admin/categories', {
    parentId,
    name: spec.name,
    slug: spec.slug,
    sortOrder: spec.sortOrder,
    lineKind: desiredLineKindOf(spec, options),
  })
  console.log(`  + 分类 ${spec.name}（id=${id}）`)
  return { id, slug: spec.slug, name: spec.name, children: [] }
}

/**
 * 管理端条目列表要翻页取全。
 *
 * <p>服务端把 size 收敛到 200，只取第一页时条目超过 200 后较老的内容会"看不见"，
 * 导入脚本就会把已存在的标题当新条目再建一遍（2026-10-26 加篇时库里已 222 条）。
 */
async function existingEntries(client) {
  const items = []
  for (let page = 1; ; page++) {
    const result = await client.get(`/api/v1/knowledge/admin/entries?size=200&page=${page}`)
    const batch = result?.items ?? []
    items.push(...batch)
    if (batch.length === 0 || items.length >= (result?.total ?? 0)) {
      return items
    }
  }
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
    // 行尾不参与比较：库里的历史内容 CRLF 与 LF 混着，检出的行尾由 .gitattributes 决定，
    // 不归一化会让每次导入都重写同一批条目（2026-10-29 实测 23 条纯行尾差异）。
    const sameBody = normalizeBody(detail.body ?? "") === normalizeBody(lesson.body)
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
  if (desiredLineKindOf(spec, options) === null) {
    // 不是轨道（书籍详解、开源项目简讲、MyBatis 源码精读）：连关卡都不登记，
    // 否则 /learn 会多出一条没有 line_kind 的线，点进去也没有通关概念。
    return { created: created.length, updated: updates, relations, stages: 0 }
  }
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
  } else {
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

/**
 * 内容目录的确定性哈希（WORK-074 C4）：按相对路径排序，逐个把"相对路径 + 内容"喂进 sha256。
 * 供仓库侧 `check-content-drift.mjs` 判断"源改了但没导入"。
 */
function contentHash(dir) {
  const files = []
  const collect = (d) => {
    for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, entry.name)
      if (entry.isDirectory()) collect(full)
      else if (entry.name.endsWith('.md') && entry.name !== 'README.md') files.push(full)
    }
  }
  collect(dir)
  files.sort()
  const hash = crypto.createHash('sha256')
  for (const file of files) {
    hash.update(path.relative(dir, file).split(path.sep).join('/'))
    hash.update('\0')
    hash.update(fs.readFileSync(file))
    hash.update('\0')
  }
  return hash.digest('hex')
}

async function main() {
  const options = parseArgs(process.argv.slice(2))

  // 写入前先做统一内容校验（WORK-073 TASK-005）：有 error 即中止，不发起任何写请求。
  {
    const scope = path.relative(ROOT, options.dir) || 'content'
    const { errors } = await validateContent({ scope })
    if (errors.length > 0) {
      console.error(`内容校验未通过（${errors.length} 个问题），已中止导入，库未做任何变更：`)
      for (const e of errors.slice(0, 50)) console.error(`  ${e.file}:${e.line}  ${e.rule}  ${e.message}`)
      if (errors.length > 50) console.error(`  … 其余 ${errors.length - 50} 条省略`)
      process.exit(1)
    }
  }

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

  // 导入汇总上报给管理端（WORK-074 C3）。**best-effort**：上报失败不影响已完成的导入。
  // dry-run 下即使带了管理凭据（登录只为读取已存在条目算 created/updated）也**不得上报**：
  // 上报会写一条"当前内容哈希"的导入记录，破坏只读语义，还会让 check-content-drift 误判无漂移。
  if (client && !options.dryRun) {
    try {
      await client.post('/api/v1/ops/admin/content-import', {
        source: options.dir,
        sourceHash: contentHash(options.dir),
        created: totals.created,
        updated: totals.updated,
        relations: totals.relations,
      })
    } catch (error) {
      console.warn(`  ! 导入汇总上报失败（不影响导入）：${error.message}`)
    }
  }
}

main().catch((error) => {
  console.error(error.message)
  process.exit(1)
})
