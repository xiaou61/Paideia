import { HighlightStyle } from '@codemirror/language'
import { EditorView } from '@codemirror/view'
import { tags } from '@lezer/highlight'

/**
 * CodeMirror 的配色（WORK-035 的 SQL 编辑器与动手练习的代码编辑器共用一份）。
 *
 * <p><b>为什么不用 `basicSetup`</b>：它把 vim/emacs 键位与自带主题一起带上，实测多出约 20 kB min。
 * 本项目有自己的令牌配色，主题必须用 `--color-*` 变量重写，不能引它的默认配色
 * （否则深浅主题切换时编辑区不会跟着变）。
 *
 * <p>放在这里而不是各写一份：两套配色一定会漂移，而它们就并排出现在同一条知识条目里。
 * 本文件**必须只被懒加载的编辑器 chunk 引用**——它一进主包就把 CodeMirror 拖进主包。
 */
export const codeMirrorTheme = EditorView.theme({
  '&': {
    fontSize: '13px',
    backgroundColor: 'var(--muted)',
    color: 'var(--foreground)',
    border: '1px solid var(--border)',
    borderRadius: 'calc(var(--radius) - 2px)',
  },
  '.cm-content': {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    padding: '12px 0',
  },
  '.cm-gutters': {
    backgroundColor: 'transparent',
    color: 'var(--muted-foreground)',
    border: 'none',
  },
  '.cm-activeLine': { backgroundColor: 'color-mix(in oklab, var(--accent) 40%, transparent)' },
  '.cm-activeLineGutter': { backgroundColor: 'transparent' },
  '&.cm-focused': { outline: 'none', borderColor: 'var(--primary)' },
  '.cm-selectionBackground, ::selection': { backgroundColor: 'var(--accent)' },
  // 补全面板：默认是写死的浅灰底 + 蓝色选中行，深色主题下会是一块突兀的白板
  '.cm-tooltip': {
    backgroundColor: 'var(--popover)',
    color: 'var(--popover-foreground)',
    border: '1px solid var(--border)',
    borderRadius: 'calc(var(--radius) - 4px)',
  },
  '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
    backgroundColor: 'var(--accent)',
    color: 'var(--accent-foreground)',
  },
})

/**
 * 语法高亮：只用令牌里的语义色，不引 CodeMirror 自带的 `defaultHighlightStyle`。
 *
 * <p>自带样式是写死的十六进制色，深浅主题切换时不会跟着变——而本项目的配色全在
 * `--color-*` 变量里。所以这里自己定义一份最小的高亮规则。
 */
export const codeMirrorHighlight = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--primary)', fontWeight: '600' },
  { tag: [tags.string, tags.special(tags.string)], color: 'var(--success)' },
  { tag: [tags.number, tags.bool, tags.null], color: 'var(--warning)' },
  { tag: [tags.comment, tags.lineComment, tags.blockComment], color: 'var(--muted-foreground)', fontStyle: 'italic' },
  { tag: [tags.operator, tags.punctuation], color: 'var(--muted-foreground)' },
  { tag: [tags.typeName, tags.className], color: 'var(--info)' },
  // 声明处的类名/类型名（`class Container {`）在 Java 语法里是 `definition(className)`，
  // 单写 `tags.className` 只有用到的地方有色，声明处还是黑的，看起来像"没高亮完"
  { tag: [tags.definition(tags.className), tags.definition(tags.typeName)], color: 'var(--info)' },
  { tag: [tags.function(tags.variableName), tags.definition(tags.function(tags.variableName))], color: 'var(--info)' },
  { tag: tags.invalid, color: 'var(--destructive)' },
])
