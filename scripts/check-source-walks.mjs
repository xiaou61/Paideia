#!/usr/bin/env node
/**
 * 源码精读内容守卫（WORK-057）。
 *
 * 只查"写错了会让读者看到坏块"的几件事，不重复 TS 里的全部字段校验：
 *   1. ```` ```source-walk ```` 块能解析成 JSON；
 *   2. 每个步骤的 `file` 在 `files` 里，且文件真的存在于 `frontend/apps/app/public/<base>`；
 *   3. `line + lines - 1` 不超过文件行数（行号漂移是这类内容最常见的坏法）；
 *   4. 预测题的 `answer` 下标合法；
 *   5. 同一个 `base` 下所有块的 repo / ref / commit 一致。
 *
 * 用法：node scripts/check-source-walks.mjs [目录...]
 * 默认扫 `content/source`。任何一条不合格退出码 1。
 */

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const ROOT = process.cwd()
const PUBLIC = path.join(ROOT, 'frontend', 'apps', 'app', 'public')

function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      out.push(...walk(full))
    } else if (entry.name.endsWith('.md')) {
      out.push(full)
    }
  }
  return out
}

/** 取出正文里所有 source-walk 围栏块；返回 `{ json, line }`。 */
function blocks(markdown) {
  const out = []
  const regex = /```source-walk[^\n]*\n([\s\S]*?)\n```/g
  let match
  while ((match = regex.exec(markdown)) !== null) {
    out.push({
      json: match[1],
      line: markdown.slice(0, match.index).split('\n').length,
    })
  }
  return out
}

const errors = []
const seen = new Map()
let checked = 0

const targets = process.argv.slice(2)
const dirs = targets.length === 0 ? [path.join(ROOT, 'content', 'source')] : targets

for (const dir of dirs) {
  const target = path.resolve(dir)
  if (!fs.existsSync(target)) {
    errors.push(`${target}：目录不存在`)
    continue
  }
  for (const file of walk(target)) {
    const markdown = fs.readFileSync(file, 'utf8')
    for (const block of blocks(markdown)) {
      const where = `${path.relative(ROOT, file)}:${block.line}`
      let walk
      try {
        walk = JSON.parse(block.json)
      } catch (cause) {
        errors.push(`${where}：不是合法 JSON（${cause.message}）`)
        continue
      }
      checked += 1
      for (const key of ['id', 'repo', 'ref', 'commit', 'license', 'base', 'files', 'steps']) {
        if (walk[key] === undefined) {
          errors.push(`${where}：缺字段 ${key}`)
        }
      }
      if (!Array.isArray(walk.files) || !Array.isArray(walk.steps)) {
        continue
      }
      const baseDir = path.join(PUBLIC, walk.base ?? '')
      const paths = new Set(walk.files.map((item) => item.path))
      for (const item of walk.files) {
        if (!fs.existsSync(path.join(baseDir, item.path))) {
          errors.push(`${where}：快照里没有 ${item.path}（${path.relative(ROOT, baseDir)}）`)
        }
      }
      for (const [index, step] of walk.steps.entries()) {
        if (!paths.has(step.file)) {
          errors.push(`${where}：steps[${index}].file 不在 files 里：${step.file}`)
          continue
        }
        const full = path.join(baseDir, step.file)
        if (!fs.existsSync(full)) {
          continue
        }
        const lines = fs.readFileSync(full, 'utf8').split('\n').length
        if (!Number.isInteger(step.line) || step.line < 1 || !Number.isInteger(step.lines) || step.lines < 1) {
          errors.push(`${where}：steps[${index}] 的 line/lines 必须是正整数`)
        } else if (step.line + step.lines - 1 > lines) {
          errors.push(
            `${where}：steps[${index}] 行号越界（${step.line}-${step.line + step.lines - 1}，文件只有 ${lines} 行）`,
          )
        }
        if (step.predict !== undefined) {
          const { answer, options, question, reveal } = step.predict
          if (typeof question !== 'string' || !Array.isArray(options) || typeof reveal !== 'string') {
            errors.push(`${where}：steps[${index}].predict 需要 question / options / reveal 三个字段`)
          }
          if (!Array.isArray(options) || !Number.isInteger(answer) || answer < 0 || answer >= options.length) {
            errors.push(`${where}：steps[${index}].predict.answer 下标不合法`)
          }
        }
      }
      if (typeof walk.base === 'string' && typeof walk.commit === 'string') {
        const previous = seen.get(walk.base)
        if (previous !== undefined && previous !== walk.commit) {
          errors.push(`${where}：base ${walk.base} 的 commit 与前面不一致（${previous} vs ${walk.commit}）`)
        }
        seen.set(walk.base, walk.commit)
      }
      console.log(`ok  ${where}  ${walk.id}  ${walk.steps.length} 步`)
    }
  }
}

if (errors.length > 0) {
  console.error('\n不合格：')
  for (const error of errors) {
    console.error(` - ${error}`)
  }
  process.exit(1)
}

console.log(`\n通过：${checked} 个 source-walk 块`)
