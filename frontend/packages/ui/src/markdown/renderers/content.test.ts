import fs from 'node:fs'
import path from 'node:path'

import { describe, expect, it } from 'vitest'

import { parseCodeChallenge, parseCodeFlow, parseQuiz, parseSourceWalk } from './schema'

/**
 * 真实内容回归（WORK-057）。
 *
 * <p>`content/source/**` 的 source-walk / code-flow / code-challenge / quiz 每一块都必须能过 TS 侧校验。
 * `content/reading/**` 目前只回归新增的 code-flow（老的 code-challenge 由 check-content.mjs 按自己的规则管，
 * 两套口径不完全一致，不在这里混着断言）。
 *
 * <p>`scripts/check-source-walks.mjs` 只查行号与文件存在（它不引 TS）。两份检查各管一段：
 * 脚本保证行号不漂移，这里保证字段真的能被解析器接受。**内容写错时先红的是这两处**，不是线上白屏。
 */
function repoContent(): string {
  // 包目录是 `frontend/packages/ui`，仓库根在它上面三层；从 cwd 往上找比写死相对路径稳
  let dir = process.cwd()
  for (let depth = 0; depth < 6; depth += 1) {
    const candidate = path.join(dir, 'content', 'source')
    if (fs.existsSync(candidate)) {
      return path.join(dir, 'content')
    }
    dir = path.dirname(dir)
  }
  return path.join(process.cwd(), 'content')
}

const CONTENT = repoContent()
const SOURCE = path.join(CONTENT, 'source')
const READING = path.join(CONTENT, 'reading')

function markdownFiles(dir: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(dir)) {
    return out
  }
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      out.push(...markdownFiles(full))
    } else if (entry.name.endsWith('.md')) {
      out.push(full)
    }
  }
  return out
}

function checkBlocks(roots: string[], fences: string[]): { failures: string[]; blocks: number } {
  const failures: string[] = []
  let blocks = 0
  const regex = new RegExp(`\`\`\`(${fences.join('|')})[^\\n]*\\n([\\s\\S]*?)\\n\`\`\``, 'g')
  for (const root of roots) {
    for (const file of markdownFiles(root)) {
      const markdown = fs.readFileSync(file, 'utf8')
      let match: RegExpExecArray | null
      while ((match = regex.exec(markdown)) !== null) {
        blocks += 1
        const fence = match[1]
        const body = match[2] ?? ''
        const parsed =
          fence === 'source-walk'
            ? parseSourceWalk(body)
            : fence === 'code-flow'
              ? parseCodeFlow(body)
              : fence === 'code-challenge'
                ? parseCodeChallenge(body)
                : parseQuiz(body)
        if (!parsed.ok) {
          failures.push(`${path.relative(root, file)}（${fence}）：${parsed.error}`)
        }
      }
    }
  }
  return { failures, blocks }
}

describe('content/source 的围栏块', () => {
  it('source-walk / code-flow / code-challenge / quiz 每一块都能被解析', () => {
    const { failures, blocks } = checkBlocks([SOURCE], ['source-walk', 'code-flow', 'code-challenge', 'quiz'])
    expect(failures).toEqual([])
    expect(blocks).toBeGreaterThan(0)
  })
})

describe('content/reading 的 code-flow 块', () => {
  it('每个 code-flow 都能被解析（没写就是空）', () => {
    const { failures } = checkBlocks([READING], ['code-flow'])
    expect(failures).toEqual([])
  })
})
