interface OutlinePanelProps {
  body: string
  onJump: (heading: string) => void
}

interface Heading {
  level: number
  text: string
}

/** 从 markdown 正文里抽 # 标题做大纲。 */
function headingsOf(body: string): Heading[] {
  const result: Heading[] = []
  for (const line of body.split('\n')) {
    const match = /^(#{1,6})\s+(.*)$/.exec(line)
    if (match !== null) {
      result.push({ level: (match[1] ?? '').length, text: (match[2] ?? '').trim() })
    }
  }
  return result
}

/** 大纲面板：列出正文里的标题，点一下滚到那一节。 */
export function OutlinePanel({ body, onJump }: OutlinePanelProps) {
  const headings = headingsOf(body)
  return (
    <div className="text-sm">
      <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">大纲</p>
      {headings.length === 0 ? (
        <p className="text-muted-foreground">正文里还没有标题。</p>
      ) : (
        <ul className="space-y-1">
          {headings.map((heading, index) => (
            <li key={`${heading.text}-${index}`} style={{ paddingLeft: `${(heading.level - 1) * 12}px` }}>
              <button
                type="button"
                className="w-full truncate text-left text-muted-foreground hover:text-foreground"
                onClick={() => onJump(heading.text)}
              >
                {heading.text}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
