import { useEffect, useMemo, useRef, useState } from 'react'
import type { CustomRendererProps } from 'streamdown'

import { BlockFallback } from './blocks'
import { parseSketch, type Sketch as SketchData } from './schema'
import { layoutSketch, makeMeasurer, SKETCH_FONT } from './sketch-layout'
import { paintSketch } from './sketch-paint'

/**
 * 手绘图（WORK-051）。
 *
 * <p>**只在文档模式启用**：挂进 `documentRenderers`，与 code-tour 等一致；助手流式回答里的
 * ```` ```sketch ```` 仍是文本。
 *
 * <p>**为什么先等字体再量宽**：框宽是量出来的。字体没加载完就量，会按回退字体算出一个宽度，
 * 等手写体落地后文字变宽、被框裁掉。这里先 `document.fonts.load` 再量；加载失败也不阻断——
 * 回退链会兜住，图不该因此消失。
 */
export function Sketch({ code }: CustomRendererProps) {
  const parsed = useMemo(() => parseSketch(code), [code])
  const [failure, setFailure] = useState<string | null>(null)
  const svgRef = useRef<SVGSVGElement>(null)

  useEffect(() => {
    if (!parsed.ok) {
      return
    }
    const svg = svgRef.current
    if (svg === null) {
      return
    }
    let cancelled = false
    setFailure(null)
    void (async () => {
      try {
        await waitForSketchFont()
        if (cancelled) {
          return
        }
        await paintSketch(svg, layoutSketch(parsed.value, makeMeasurer()), parsed.value)
      } catch (cause) {
        if (!cancelled) {
          setFailure(cause instanceof Error ? cause.message : String(cause))
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [parsed])

  if (!parsed.ok) {
    return <BlockFallback title="手绘图" error={parsed.error} raw={code} />
  }
  if (failure !== null) {
    return <BlockFallback title="手绘图" error={failure} raw={code} />
  }
  return (
    <figure className="my-6" data-testid="sketch-block">
      <svg ref={svgRef} className="sketch-block" role="img" aria-label={labelOf(parsed.value)} />
    </figure>
  )
}

async function waitForSketchFont(): Promise<void> {
  const fonts = (document as Document & { fonts?: FontFaceSet }).fonts
  if (fonts === undefined) {
    return
  }
  try {
    await fonts.load(`20px "${SKETCH_FONT}"`)
  } catch {
    // 字体没到手就用回退字体画：宁可字形不手写，也不要整块消失
  }
}

function labelOf(sketch: SketchData): string {
  if (sketch.title !== undefined) {
    return sketch.title
  }
  return sketch.rows
    .flat()
    .map((node) => (node.sub === undefined ? node.title : `${node.title}，${node.sub}`))
    .join('；')
}
