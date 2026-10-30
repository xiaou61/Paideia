/**
 * 对话输入框里的 `@` 引用（WORK-058）。
 *
 * <p>规则只有三条，但每条都决定了"什么时候不该弹选择器"：
 * <ol>
 *   <li>取**光标前最后一个** `@`——用户可能写了半句又回头补一个。</li>
 *   <li>`@` 前必须是行首或空白：`a@b.com` 里的 `@` 是邮箱，不是引用。</li>
 *   <li>`@` 与光标之间不能有空白，且长度有限（默认 40）：否则整段话里的旧 `@` 会一直算"正在引用"。</li>
 * </ol>
 */
export function findMention(
  text: string,
  caret: number,
  maxQuery = 40,
): { start: number; query: string } | null {
  if (caret < 0 || caret > text.length) {
    return null
  }
  const before = text.slice(0, caret)
  const at = before.lastIndexOf('@')
  if (at < 0) {
    return null
  }
  const query = before.slice(at + 1)
  if (query.length > maxQuery || /\s/.test(query)) {
    return null
  }
  const prev = at === 0 ? '' : before[at - 1] ?? ''
  if (prev !== '' && !/\s/.test(prev)) {
    return null
  }
  return { start: at, query }
}

/** 选中候选后把 `@查询` 从输入里删掉（其余文字原样保留）。 */
export function stripMention(text: string, start: number, caret: number): string {
  return `${text.slice(0, start)}${text.slice(caret)}`
}
