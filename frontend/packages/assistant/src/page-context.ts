import { PAGE_CONTEXT_LIMITS, truncateContext, type PageContext } from './context'

/**
 * 把页面场景转成**一段给模型看的文本**（WORK-035 T-15）。
 *
 * <p><b>为什么在客户端转成文本而不是把对象发给服务端</b>：服务端不认识"SQL 工作台"
 * 这个概念，也不该认识——它只需要一段可以放进提示词的说明。把结构留在前端，
 * 加新页面场景时不必改后端。
 *
 * <p><b>截断在这里做</b>：草稿与报错的长度由前端控制，服务端只做兜底上限
 * （见 `ConversationService.MAX_PAGE_CONTEXT_CHARS`）。
 *
 * <p><b>不放数据行</b>：`lastResult` 只该是"报错原文"或"返回 3 列 12 行"这类摘要。
 * 结果集可能有上千行，塞进提示词既贵又可能把用户没想分享的数据送进模型。
 */
export function serializePageContext(context: PageContext | null): string | undefined {
  if (context === null) {
    return undefined
  }
  if (context.kind === 'sql-workbench') {
    const parts = [
      `页面：SQL 工作台`,
      `当前数据集：${context.datasetKey}`,
      '',
      '编辑器里的 SQL（用户还没提交，可能没写完）：',
      '```sql',
      truncateContext(context.draftSql, PAGE_CONTEXT_LIMITS.draftSql),
      '```',
    ]
    if (context.lastResult.trim() !== '') {
      parts.push('', '最近一次运行的结果：', truncateContext(context.lastResult, PAGE_CONTEXT_LIMITS.lastResult))
    }
    return parts.join('\n')
  }
  if (context.kind === 'study-log') {
    const block = (text: string) => truncateContext(text, PAGE_CONTEXT_LIMITS.studyLogBlock)
    return [
      '页面：学习记录',
      '以下四块是平台算好的事实（数字不会骗人，缺的块会显示"暂无"）：',
      '',
      '各线进度：',
      block(context.lines),
      '',
      '最近没拿满分的关卡：',
      block(context.failing),
      '',
      '到期的复习：',
      block(context.dueReviews),
      '',
      '掌握度最低的概念：',
      block(context.weakConcepts),
    ].join('\n')
  }
  return undefined
}

/**
 * 从一段 markdown 里取出**第一个 sql 代码块**的内容（WORK-035 T-15）。
 *
 * <p>**取不到就返回 null，不猜**：把整段解释文字塞进编辑器比"什么都不做"更糟——
 * 用户会以为自己写错了，而其实是助手说的那句"你可以这样写"。
 *
 * <p>认 ` ```sql `、` ```SQL `、` ```mysql ` 三种围栏：模型三种都会写。
 */
export function extractSqlBlock(markdown: string): string | null {
  const match = /```(?:sql|mysql)\s*\n([\s\S]*?)```/i.exec(markdown)
  if (match === null) {
    return null
  }
  const body = match[1]?.trim() ?? ''
  return body === '' ? null : body
}
