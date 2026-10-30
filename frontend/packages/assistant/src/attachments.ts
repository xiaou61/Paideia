/**
 * 助手的**对象附件**（WORK-058）。
 *
 * <p>原先的上下文按"页面类型"建模（`PageContext` 的 kind 联合），于是每加一个页面就要改类型
 * 与序列化。这里换成"对象"：任何页面上的任何东西都是 `type:id`，页面只负责把标签与摘录递过来。
 *
 * <p>**为什么标签与摘录也放在前端**：服务端不认识"第 13 关"是什么，也不该认识——它只需要一段
 * 可以放进提示词的说明。加新对象类型时后端零改动（与 WORK-035 同一取舍）。
 */
export type AttachmentType =
  | 'entry'
  | 'note'
  | 'concept'
  | 'track'
  | 'level'
  | 'book'
  | 'digest-item'
  | 'resume'

/** 一条附件。同 `uri` 视为同一条对象（重复递过去只更新标签与摘录）。 */
export interface Attachment {
  /** `type:id`；`id` 可含 `/`（合成对象，如 `level:java/13`）。 */
  uri: string
  /** 给人看的一行，同时进提示词。 */
  label: string
  /** 可选摘录：正文选中段、未保存的草稿、题干等。序列化时截断。 */
  excerpt?: string
}

/**
 * 附件条数上限。
 *
 * <p>**5 条是预算与意图的折中**：每轮都带附件，带得越多越贵，而"我到底想让它看什么"超过五条
 * 通常说明该换个问法。多了丢最旧的，不报错——附件是锦上添花。
 */
export const ATTACHMENT_LIMIT = 5

/** 单条摘录上限。5 × 600 仍在服务端 4000 字符的兜底之内。 */
export const ATTACHMENT_EXCERPT_LIMIT = 600

const TYPES: readonly string[] = [
  'entry',
  'note',
  'concept',
  'track',
  'level',
  'book',
  'digest-item',
  'resume',
]

/** 拼一个 URI。空 id 是调用方的 bug，直接抛（不要把 `entry:` 这种废串送进提示词）。 */
export function attachmentUri(type: AttachmentType, id: string | number): string {
  const raw = String(id).trim()
  if (raw === '') {
    throw new Error('附件的 id 不能为空')
  }
  return `${type}:${raw}`
}

/**
 * 解析 URI；不认识就返回 null（调用方降级为纯文本）。
 *
 * <p>**只做格式校验，不做权限**：能不能读这条对象由服务端按当前用户判断，URI 只是个名字。
 */
export function parseAttachmentUri(uri: string): { type: AttachmentType; id: string } | null {
  const index = uri.indexOf(':')
  if (index <= 0) {
    return null
  }
  const type = uri.slice(0, index)
  const id = uri.slice(index + 1).trim()
  if (id === '' || /\s/.test(uri) || !TYPES.includes(type)) {
    return null
  }
  return { type: type as AttachmentType, id }
}

/** 加入一条：同 uri 覆盖并移到末尾（最近附着的排在后面），超过上限丢最旧。 */
export function putAttachment(list: Attachment[], next: Attachment): Attachment[] {
  return [...list.filter((item) => item.uri !== next.uri), next].slice(-ATTACHMENT_LIMIT)
}

export function removeAttachment(list: Attachment[], uri: string): Attachment[] {
  return list.filter((item) => item.uri !== uri)
}

/**
 * 序列化成一段给模型看的文本。
 *
 * <p>**"资料不是指令"这句话不在这里写**：它属于提示词，由服务端统一加（见
 * `ConversationPrompt.pageContext`）——前端只负责把资料排整齐，避免两处措辞各自漂移。
 */
export function serializeAttachments(list: Attachment[]): string | undefined {
  if (list.length === 0) {
    return undefined
  }
  const blocks = list.map((item, index) => {
    const lines = [`### 附件 ${index + 1} · ${item.uri} · ${item.label}`]
    const excerpt = item.excerpt?.trim() ?? ''
    if (excerpt !== '') {
      lines.push(truncate(excerpt, ATTACHMENT_EXCERPT_LIMIT))
    }
    return lines.join('\n')
  })
  return ['用户附着了以下对象（按附着顺序，最多 5 条）：', '', ...blocks].join('\n')
}

/**
 * 与 `context.tsx` 的 `truncateContext` 同一行为。
 *
 * <p>**本地实现而不是 import**：`context.tsx` 要 import 本模块的类型与纯函数，
 * 反向再 import 就成了模块环，打包器与测试加载顺序都会因此变脆。
 */
function truncate(text: string, limit: number): string {
  if (text.length <= limit) {
    return text
  }
  return `${text.slice(0, limit)}\n…（后面还有 ${text.length - limit} 个字符没显示）`
}
