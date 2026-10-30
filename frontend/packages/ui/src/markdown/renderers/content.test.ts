import fs from 'node:fs'
import path from 'node:path'

import { describe, expect, it } from 'vitest'

import { parseCodeChallenge, parseQuiz, parseSourceWalk } from './schema'

/**
 * 真实内容回归（WORK-057）：`content/source/**` 里的每一块都必须能过 TS 侧的校验。
 *
 * <p>`scripts/check-source-walks.mjs` 只查行号与文件存在（它不引 TS）。两份检查各管一段：
 * 脚本保证行号不漂移，这里保证字段真的能被解析器接受。**内容写错时先红的是这两处**，
 * 不是线上白屏。
 */
function contentRoot(): string {
  // 包目录是 `frontend/packages/ui`，仓库根在它上面三层；从 cwd 往上找比写死相对路径稳
  let dir = process.cwd()
  for (let depth = 0; depth < 6; depth += 1) {
    const candidate = path.join(dir, 'content', 'source')
    if (fs.existsSync(candidate)) {
      return candidate
    }
    dir = path.dirname(dir)
  }
  return path.join(process.cwd(), 'content', 'source')
}

const ROOT = contentRoot()

function markdownFiles(dir: string): string[] {
  const out: string[] = []
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

describe('content/source 的围栏块（WORK-057）', () => {
  it('source-walk / code-challenge / quiz 每一块都能被解析', () => {
    if (!fs.existsSync(ROOT)) {
      return
    }
    const failures: string[] = []
    let blocks = 0
    for (const file of markdownFiles(ROOT)) {
      const markdown = fs.readFileSync(file, 'utf8')
      const regex = /```(source-walk|code-challenge|quiz)[^\n]*\n([\s\S]*?)\n```/g
      let match: RegExpExecArray | null
      while ((match = regex.exec(markdown)) !== null) {
        blocks += 1
        const fence = match[1]
        const body = match[2] ?? ''
        const parsed =
          fence === 'source-walk'
            ? parseSourceWalk(body)
            : fence === 'code-challenge'
              ? parseCodeChallenge(body)
              : parseQuiz(body)
        if (!parsed.ok) {
          failures.push(`${path.relative(ROOT, file)}（${fence}）：${parsed.error}`)
        }
      }
    }
    expect(failures).toEqual([])
    expect(blocks).toBeGreaterThan(0)
  })
})
