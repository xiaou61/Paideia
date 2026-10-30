/**
 * 引用链接用的占位前缀。**渲染层认它，应用负责把它变成路由。**
 *
 * <p><b>为什么是 https + `.invalid` 而不是自定义协议</b>：渲染器（streamdown）会拦掉不认识的
 * 协议，把 `paideia:entry:1` 渲染成 `entry:1 [blocked]`（实测），于是我们的 `a` 覆盖根本收不到
 * href。`.invalid` 是 RFC 2606 保留的顶级域，永远不解析——就算有人绕过我们的点击拦截，
 * 也只是打不开，不会连到真实站点。
 */
export const CITATION_PREFIX = 'https://paideia.invalid/'

/**
 * 把回答里的对象引用变成链接（WORK-058）。
 *
 * <p>模型输出的约定是 `[[entry:123]]`——方括号比 markdown 链接省一个标签位，而且在没渲染时
 * 也不至于变成裸 URL。这里把它换成 markdown 链接，目标用自定义协议 `paideia:`，
 * 由面板的 `a` 覆盖接住（本包不认识路由，交给应用）。
 *
 * <p><b>只替换完整的 `[[...]]`</b>：流式输出到一半的 `[[entry:12` 会原样留着，等闭合了再换。
 *
 * <p>链接文字优先用附件给的标签（`第 13 关 异常`），没有就用 uri 本身——用户至少知道自己点的是什么。
 */
export function linkifyCitations(text: string, labels?: Map<string, string>): string {
  return text.replace(/\[\[([a-z][a-z-]*:[^\]\s]+)\]\]/g, (whole, uri: string) => {
    const label = labels?.get(uri) ?? uri
    // markdown 链接文字里的方括号会破坏解析：转成圆括号
    const safe = label.replace(/[[\]]/g, (bracket) => (bracket === '[' ? '（' : '）'))
    return `[${safe}](${CITATION_PREFIX}${uri})`
  })
}
