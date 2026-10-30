import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completionKeymap,
} from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { MySQL, sql } from '@codemirror/lang-sql'
import { bracketMatching, indentOnInput, syntaxHighlighting } from '@codemirror/language'
import { EditorState } from '@codemirror/state'
import { codeMirrorHighlight, codeMirrorTheme } from '@paideia/ui/code-editor-theme'
import {
  EditorView,
  drawSelection,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  placeholder as placeholderExt,
} from '@codemirror/view'
import { useEffect, useRef } from 'react'

/**
 * SQL 编辑器（WORK-035 T-01）。
 *
 * <p><b>为什么不用 `basicSetup`</b>：它把 vim/emacs 键位与自带主题一起带上，实测多出约 20 kB min。
 * 本项目有自己的令牌配色，CodeMirror 的主题必须用 `--color-*` 变量重写，不能引它的默认配色
 * （否则深浅主题切换时编辑区不会跟着变）。
 *
 * <p><b>这个文件必须只被 `@paideia/sql-workbench` 的入口按需引用</b>：它是 CodeMirror 的唯一
 * 接触面，一旦被别的模块静态 import，整个编辑器就会进主 chunk。
 */
export interface SqlSchema {
  /** 表名 → 列名数组。CodeMirror 靠它补全表名与列名。 */
  [table: string]: string[]
}

export interface SqlEditorProps {
  value: string
  onChange: (value: string) => void
  /** 补全用的 schema。为空时只补全 SQL 关键字。 */
  schema?: SqlSchema
  /** 只读（运行中或权限不足时）。 */
  readOnly?: boolean
  placeholder?: string
  /** 供测试与外部聚焦用。 */
  ariaLabel?: string
}

/**
 * 配色与高亮规则在 `@paideia/ui/code-editor-theme`：动手练习的代码编辑器用的是同一份。
 * 两个编辑区会并排出现在同一条知识条目里，各留一套一定会漂移。
 */

export function SqlEditor({
  value,
  onChange,
  schema,
  readOnly = false,
  placeholder,
  ariaLabel = 'SQL 编辑器',
}: SqlEditorProps) {
  const host = useRef<HTMLDivElement | null>(null)
  const view = useRef<EditorView | null>(null)
  // onChange 进 ref：把它放进 effect 依赖会让每次输入都重建编辑器（光标与撤销栈全丢）
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  // 重建 effect 不依赖 value（schema 变化才重建），但它要用**最新**的文本建 doc：
  // 直接用闭包里的 value 会把用户已经输入的内容回退到上一次同步时的值。
  const valueRef = useRef(value)
  valueRef.current = value
  /**
   * schema 只在**内容**变化时才重建编辑器。
   *
   * <p>直接拿对象当依赖会被"每次渲染都是新引用"打中：调用方只要写一句
   * `schema={toCompletionSchema(...)}`，打一个字就会重建一次编辑器、丢一次焦点
   * （2026-10-28 线上就这么坏过）。用内容键做依赖，调用方 memo 不 memo 都不影响可用性。
   */
  const schemaRef = useRef(schema)
  schemaRef.current = schema
  const schemaKey = JSON.stringify(schema ?? {})

  useEffect(() => {
    if (host.current === null) {
      return
    }
    const extensions = [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightSpecialChars(),
      history(),
      drawSelection(),
      EditorState.allowMultipleSelections.of(true),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      autocompletion(),
      highlightActiveLine(),
      keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, ...completionKeymap, indentWithTab]),
      sql({ dialect: MySQL, schema: schemaRef.current ?? {}, upperCaseKeywords: true }),
      codeMirrorTheme,
      syntaxHighlighting(codeMirrorHighlight),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          onChangeRef.current(update.state.doc.toString())
        }
      }),
    ]
    if (placeholder !== undefined) {
      extensions.push(placeholderExt(placeholder))
    }
    if (readOnly) {
      extensions.push(EditorState.readOnly.of(true))
    }
    const instance = new EditorView({
      parent: host.current,
      doc: valueRef.current,
      extensions,
    })
    view.current = instance
    return () => {
      instance.destroy()
      view.current = null
    }
    // schema 的**内容** / placeholder / readOnly 变化时重建：schema 进补全源后无法热替换
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schemaKey, placeholder, readOnly])

  // 外部改值（AI 填回、载入关卡题）时同步进编辑器。
  // 必须比对再写：无条件 dispatch 会在用户每次输入后把光标重置到末尾。
  useEffect(() => {
    const instance = view.current
    if (instance === null) {
      return
    }
    const current = instance.state.doc.toString()
    if (current !== value) {
      instance.dispatch({ changes: { from: 0, to: current.length, insert: value } })
    }
  }, [value])

  return (
    <div
      ref={host}
      data-testid="sql-practice-editor"
      aria-label={ariaLabel}
      role="textbox"
      className="min-h-56"
    />
  )
}
