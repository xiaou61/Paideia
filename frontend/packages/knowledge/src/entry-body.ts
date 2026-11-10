/**
 * 条目正文开头的一级标题如果就是条目标题，就不再渲染它。
 *
 * <p>课程正文以 `# 26 · 枚举与常量` 开头，面经正文以 `## <题目>` 开头，而页面本身已经渲染了条目标题，于是顶部并排出现
 * 两条同文大标题（加上面包屑是三处同文）。判据是"同文"而不是"有 H1"：正文里真正另起
 * 一级标题的（与标题不同的那种）保持原样。
 *
 * <p>要跟 `EntryToc` 一起用——目录与正文按**同一份 markdown** 的标题顺序编号（见
 * `MarkdownBody` 的注释），只给正文剥掉标题会让目录多一条、点下去错位一节。
 */

/**
 * 开头的 `#` 或 `##` 标题；允许前面有空行。
 *
 * <p>为什么含 `##`（2026-11-10）：面经管线的正文是从原帖抽取后拼的，首行是
 * `## <题目>`，而"题目"本身就是条目标题。只认 `#` 的结果是**每一条面经都把问题在标题下面
 * 重复一遍**（线上抽查 12 条，12 条都以 `## 标题` 开头、12 条与标题同文）。
 * 判据仍是"同文"，与标题不同的二级标题保持原样。
 */
const LEADING_H1 = /^\s*(#{1,2})\s+([^\n]+?)\s*(?:\n|$)/

/** 去掉课号前缀（`26 · `）再比，因为总览类正文只写课程名、不带课号。 */
const normalize = (text: string): string =>
  text
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^\d+\s*·\s*/, '')

export function stripDuplicateTitle(markdown: string, entryTitle?: string): string {
  if (entryTitle === undefined || entryTitle.trim() === '') {
    return markdown
  }
  const match = LEADING_H1.exec(markdown)
  const heading = match?.[2]
  if (match === null || heading === undefined || normalize(heading) !== normalize(entryTitle)) {
    return markdown
  }
  return markdown.slice(match[0].length).replace(/^\n+/, '')
}
