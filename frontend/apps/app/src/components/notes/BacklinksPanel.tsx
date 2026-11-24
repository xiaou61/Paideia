import type { NoteLinks } from '../../notes-api'

interface BacklinksPanelProps {
  links: NoteLinks | undefined
  onOpen: (noteId: number) => void
}

/** 双链面板：出链（这篇指向谁）与反链（谁指向这篇）。 */
export function BacklinksPanel({ links, onOpen }: BacklinksPanelProps) {
  const outbound = links?.outbound ?? []
  const inbound = links?.inbound ?? []
  return (
    <div className="space-y-4 text-sm">
      <div>
        <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          反向链接（{inbound.length}）
        </p>
        {inbound.length === 0 ? (
          <p className="text-muted-foreground">还没有别的文档链接到这里。</p>
        ) : (
          <ul className="space-y-1">
            {inbound.map((ref) => (
              <li key={ref.noteId}>
                <button
                  type="button"
                  className="truncate text-left text-primary hover:underline"
                  onClick={() => onOpen(ref.noteId)}
                >
                  {ref.title}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div>
        <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          出链（{outbound.length}）
        </p>
        {outbound.length === 0 ? (
          <p className="text-muted-foreground">这篇还没有指向别的文档。</p>
        ) : (
          <ul className="space-y-1">
            {outbound.map((ref, index) => (
              <li key={`${ref.noteId}-${index}`}>
                {ref.noteId === 0 ? (
                  <span className="text-muted-foreground">{ref.title}（未找到）</span>
                ) : (
                  <button
                    type="button"
                    className="truncate text-left text-primary hover:underline"
                    onClick={() => onOpen(ref.noteId)}
                  >
                    {ref.title}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
