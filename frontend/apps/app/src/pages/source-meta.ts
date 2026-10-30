/**
 * 从总览正文里读项目的版本信息（WORK-057）。
 *
 * <p>元信息不另存表：它本来就是 `source-walk` 块的一部分（repo / ref / commit / license），
 * 再存一份就会漂移。解析失败返回 null，页面只显示项目名——**不猜**。
 */
export interface SourceMeta {
  repo: string
  ref: string
  commit: string
  license: string
}

export function readSourceMeta(markdown: string): SourceMeta | null {
  const match = /```source-walk\s*\n([\s\S]*?)\n```/.exec(markdown)
  if (match === null) {
    return null
  }
  try {
    const raw = JSON.parse(match[1] ?? '') as Record<string, unknown>
    const pick = (key: string): string | null => (typeof raw[key] === 'string' ? (raw[key] as string) : null)
    const repo = pick('repo')
    const ref = pick('ref')
    const commit = pick('commit')
    const license = pick('license')
    return repo !== null && ref !== null && commit !== null && license !== null ? { repo, ref, commit, license } : null
  } catch {
    return null
  }
}
