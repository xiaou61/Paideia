import { cn } from 'cn'
import { useMemo, useState } from 'react'

import { Input } from '../components/input'
import { SKIN_FAMILIES, SKINS, skinById, type Skin } from './skins'
import { useTheme } from './theme-context'

/**
 * 皮肤选择器（WORK-079）。
 *
 * <p><b>预览卡是"活的迷你主题"</b>：卡片自己带 `data-skin`（必要时再加 `dark` 类），于是卡内
 * 那些 `bg-primary` / `bg-card` / `text-muted-foreground` 解析成那个皮肤的颜色——不需要在 TS 里
 * 复制一份色板。TS 里写颜色会撞 `tokens.test.ts`（"颜色只能来自令牌"），也会与 CSS 漂移；
 * 而这个做法连"皮肤改了什么"都不用关心。
 *
 * <p>按族分组 + 关键词过滤：皮肤一多，"一屏 40 个卡片"挑不动。族顺序即 `SKIN_FAMILIES` 的顺序，
 * 搜索命中 `name` / `summary` / `tags` 任一即可。
 *
 * <p>选中态用 `aria-pressed` 而不是 `role="radio"`：单选语义要求方向键在组内移动焦点，
 * 没实现就标成 radio 是骗读屏用户。按钮 + pressed 是诚实的表达。
 */
export function SkinPicker({ className }: { className?: string }) {
  const { theme, skin, setSkin } = useTheme()
  const [keyword, setKeyword] = useState('')

  const grouped = useMemo(() => {
    const needle = keyword.trim().toLowerCase()
    const matched =
      needle === ''
        ? SKINS
        : SKINS.filter((item) =>
            [item.name, item.summary, ...item.tags].some((field) => field.toLowerCase().includes(needle)),
          )
    return SKIN_FAMILIES.map((family) => ({
      ...family,
      items: matched.filter((item) => item.family === family.id),
    })).filter((family) => family.items.length > 0)
  }, [keyword])

  return (
    <div className={cn('space-y-6', className)}>
      <div className="space-y-2">
        <Input
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder="搜索皮肤：暖纸、终端、暗色、衬线…"
          aria-label="搜索皮肤"
          data-testid="skin-filter"
        />
        <p className="text-muted-foreground text-xs">
          换的只是外观：内容、成绩、笔记都不受影响。当前使用「{skinById(skin).name}」。
        </p>
      </div>

      {grouped.length === 0 ? (
        <div
          data-testid="skin-empty"
          className="border-border text-muted-foreground rounded-xl border border-dashed px-4 py-10 text-center text-sm"
        >
          没有匹配「{keyword.trim()}」的皮肤，换个词试试。
        </div>
      ) : null}

      {grouped.map((family) => (
        <section key={family.id} aria-label={family.label} className="space-y-2.5">
          <h3 className="text-muted-foreground text-xs font-medium tracking-wide">{family.label}</h3>
          <div role="group" aria-label={family.label} className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {family.items.map((item) => (
              <SkinCard
                key={item.id}
                item={item}
                selected={item.id === skin}
                dark={theme === 'dark'}
                onSelect={() => setSkin(item.id)}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

function SkinCard({
  item,
  selected,
  dark,
  onSelect,
}: {
  item: Skin
  selected: boolean
  /** 当前站点的明暗。预览要跟着走，否则深色站点里 17 张亮色卡会很刺眼。 */
  dark: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      data-testid={`skin-card-${item.id}`}
      data-skin={item.id}
      // 卡片自己成为一份迷你主题：卡内令牌全部解析成这个皮肤的值（含暗色变体）
      className={cn(
        'flex flex-col gap-2 rounded-xl border p-3 text-left transition-colors',
        // `dark` 类挂在自己身上，卡片才拿得到 `[data-skin='x'].dark` 那一套值。
        // 注意这也会让卡片的边框/底色跟着走——那正是"预览"该有的样子。
        dark && 'dark',
        selected ? 'border-primary ring-2 ring-ring/60' : 'border-border hover:border-input',
      )}
      aria-pressed={selected}
      onClick={onSelect}
    >
      <span aria-hidden className="block rounded-lg border border-border bg-background p-3">
        <span className="flex items-center gap-1.5">
          <span className="size-4 rounded-full bg-primary" />
          <span className="size-4 rounded-full bg-accent" />
          <span className="size-4 rounded-full bg-secondary" />
          <span className="ml-auto h-4 w-6 rounded-full border border-border bg-card" />
        </span>
        <span className="mt-2.5 block h-1.5 w-3/4 rounded-full bg-foreground/85" />
        <span className="mt-1.5 block h-1.5 w-1/2 rounded-full bg-muted-foreground/70" />
        <span className="mt-3 flex items-center gap-1.5">
          <span className="h-5 w-12 rounded-md bg-primary" />
          <span className="h-5 w-9 rounded-md border border-border bg-card" />
        </span>
      </span>
      <span className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">{item.name}</span>
        {selected ? (
          <span className="border-border text-muted-foreground rounded-full border px-1.5 py-0.5 text-[0.625rem]">
            使用中
          </span>
        ) : null}
      </span>
      <span className="text-muted-foreground block text-xs leading-relaxed">{item.summary}</span>
    </button>
  )
}
