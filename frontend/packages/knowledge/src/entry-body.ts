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

/**
 * 看起来已经排好版的 markdown（有换行、列表、标题、加粗、行内代码或引用）。
 *
 * <p>命中就整段原样返回——这些答案是模型按结构化要求写的，再加工只会把排版搞坏。
 */
// 列表标记必须锚在行首：`(^|\s)` 会把行内算术当成列表——「时间复杂度 O(n + m)」
// 「a * b」里的 ` + `、` * ` 都会命中，于是最常见的单段技术答案被当成"已排好版"原样返回，
// 段落化静默失效。
const HAS_BLOCK_MARKDOWN = /\n|(^|\n)\s*([-*+]|\d+\.)\s|^#{1,6}\s|\*\*|`|^>\s/

/** 句末标点。分号也算：面经解析里常用「；」并排几个并列点。 */
const SENTENCE_END = '。！？；'

/** 紧跟句末标点的收尾引号/括号，要一起算作句末，否则会把引号拆散。 */
const CLOSERS = '」』”’)）'

/** 只按句末标点断句，保留标点，避免依赖正则后行断言（老引擎不认）。 */
function splitSentences(text: string): string[] {
  const out: string[] = []
  let buffer = ''
  const chars = [...text]
  for (let i = 0; i < chars.length; i += 1) {
    const char = chars[i] ?? ''
    buffer += char
    // 「他提醒「先看文档。」再动手。」这种句子必须在 `」` 之后才断，
    // 否则第一段是 `他提醒「先看文档。`、第二段以孤立的 `」` 开头。
    const endsSentence =
      SENTENCE_END.includes(char) || (CLOSERS.includes(char) && SENTENCE_END.includes(chars[i - 1] ?? ''))
    if (endsSentence) {
      const sentence = buffer.trim()
      if (sentence !== '') {
        out.push(sentence)
      }
      buffer = ''
    }
  }
  const tail = buffer.trim()
  if (tail !== '') {
    out.push(tail)
  }
  return out
}

/**
 * 段首形如 `结论：……` 的短标签加粗，突出要点。
 *
 * <p>标签限制在 14 字内、且不含「，、」——含逗号的多半是完整句子，加粗整句反而更乱。
 */
function boldLabel(sentence: string): string {
  const match = /^([^。！？；：:]{1,14})[：:]\s*(.+)$/.exec(sentence)
  if (match === null) {
    return sentence
  }
  const label = match[1]?.trim() ?? ''
  const rest = match[2]?.trim() ?? ''
  // 冒号可能是 URL 协议的一部分（`文档在 https://…`）或时间/比例（`12:30`），
  // 这两种都不是"段首标签"，加粗会把链接拆断或把时间改成全角。
  if (
    label === '' ||
    rest === '' ||
    label.includes('，') ||
    label.includes('、') ||
    /https?$/.test(label) ||
    rest.startsWith('//') ||
    /^\d+$/.test(label)
  ) {
    return sentence
  }
  return `**${label}：** ${rest}`
}

/**
 * 把「一整段纯文本」的面经解析排成便于阅读的 markdown 段落（无损）。
 *
 * <p>背景（2026-11-19）：面经管线 v3 之前生成的 answer 大多是一整段，句间只用
 * 「。」「；」，渲染出来就是一大坨，用户反馈"没人想看这种"。这里做一次**不增删任何文字**的
 * 轻量整理：
 * <ul>
 *   <li>已有块级 markdown（换行/列表/标题/加粗/代码/引用）的答案原样返回，不破坏模型排好的版；</li>
 *   <li>只对没有换行的单段文本按句末标点断成多段，每段一行，读起来不再是一堵墙；</li>
 *   <li>每段开头形如 `结论：` 的短标签加粗，让要点更醒目。</li>
 * </ul>
 * 纯函数，不做任何语义改写——引号、术语、标点全都保留。
 */
export function formatAnswerParagraphs(answer: string): string {
  const text = answer.trim()
  if (text === '' || HAS_BLOCK_MARKDOWN.test(text)) {
    return answer
  }
  const sentences = splitSentences(text)
  if (sentences.length === 0) {
    return answer
  }
  const formatted = sentences.map(boldLabel)
  if (formatted.length === 1) {
    // 单句也允许把段首「标签：」加粗，但不多造空段
    return formatted[0] === sentences[0] ? answer : (formatted[0] ?? answer)
  }
  return formatted.join('\n\n')
}

/**
 * 面经管线的渲染尾注：`---` 分隔线 + `> 题目来源：[标题](url)`。
 *
 * <p>见 `EssayCollectionService.render()`：正文永远是 `<answer>\n\n---\n\n> 题目来源：...`。
 * 条目页顶部已经单独渲染了来源链接，这段尾注是重复的，而且它自带换行会让整篇"看起来像
 * 已经是 markdown"，把段落化逻辑挡在外面。这里把它切掉。
 */
const ESSAY_SOURCE_NOTE = /\n*\s*-{3,}\s*\n+\s*>\s*题目来源[:：][\s\S]*$/

/**
 * 面经条目正文的展示预处理：切掉重复的来源尾注，只把答案部分做段落化。
 *
 * <p>与 {@link formatAnswerParagraphs} 的区别是它**认识面经的尾注结构**——整篇 body 里一定
 * 有 `---` 与 `>` 的换行块，直接丢给段落化函数会因为"已含块级 markdown"而原样返回，等于没做事。
 * 所以先按尾注切开，只对 answer 那一段排版。
 */
export function formatEssayAnswer(markdown: string): string {
  const match = ESSAY_SOURCE_NOTE.exec(markdown)
  const answer = match === null ? markdown : markdown.slice(0, match.index)
  return formatAnswerParagraphs(answer)
}
