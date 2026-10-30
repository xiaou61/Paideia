import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completeAnyWord,
  completeFromList,
  completionKeymap,
} from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { java } from '@codemirror/lang-java'
import { python } from '@codemirror/lang-python'
import { bracketMatching, indentOnInput, syntaxHighlighting } from '@codemirror/language'
import { EditorState, type Extension } from '@codemirror/state'
import {
  EditorView,
  drawSelection,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
} from '@codemirror/view'
import { useEffect, useRef } from 'react'

import { codeMirrorHighlight, codeMirrorTheme } from './theme'

/**
 * 动手练习的代码编辑器（WORK-015 的 `run` 题型）。
 *
 * <p>从纯 `<textarea>` 换成 CodeMirror，补的是两件学习者一直在抱怨的事：**没有高亮**、
 * **没有补全**。补全不是语言服务器：Java 补"文档里已有的标识符 + 关键字"，Python 用
 * `@codemirror/lang-python` 自带的局部名与内置名——对几十行的练习题，这已经覆盖了
 * 大部分输入（`Container`、`InitializingBean` 都在这份文件里）。
 *
 * <p><b>本文件必须只被动态 import</b>（见 `run-challenge.tsx` 的 `lazy()`）：
 * 它连同 Java/Python 语法一起约 100 kB gzip，静态引入会让每个进首页的人都为它付费，
 * 而 `run-challenge.tsx` 在主包里。构建守卫 `scripts/check-lazy-editor.mjs` 会盯着这一点。
 */

/** Java 没有自带补全源（`@codemirror/lang-java` 只给语法），关键字手动列一份。 */
const JAVA_KEYWORDS = [
  'public', 'private', 'protected', 'static', 'final', 'abstract', 'void', 'class', 'interface',
  'extends', 'implements', 'new', 'return', 'this', 'super', 'null', 'true', 'false', 'if', 'else',
  'for', 'while', 'do', 'switch', 'case', 'break', 'continue', 'try', 'catch', 'finally', 'throw',
  'throws', 'import', 'package', 'synchronized', 'instanceof', 'int', 'long', 'double', 'float',
  'boolean', 'char', 'byte', 'short', 'String', 'Integer', 'List', 'Map', 'Set', 'Override',
]

export interface CodeEditorProps {
  value: string
  onChange: (value: string) => void
  /** `spring` 题也是 Java 文件，调用方已归一。 */
  language: 'java' | 'python'
  ariaLabel?: string
}

/** 高度上限：与原来的 textarea（最多 20 行、可拉伸）等价，超出后在编辑器内部滚动。 */
const sizing = EditorView.theme({
  '&': { maxHeight: '70vh', minHeight: '12rem' },
  // 只给根设 maxHeight 不够：滚动发生在 scroller 上，它的默认 overflow 只管横向
  '.cm-scroller': { overflow: 'auto' },
})

function extensionsFor(language: 'java' | 'python'): Extension[] {
  return [
    lineNumbers(),
    highlightActiveLineGutter(),
    highlightSpecialChars(),
    history(),
    drawSelection(),
    EditorState.allowMultipleSelections.of(true),
    indentOnInput(),
    bracketMatching(),
    closeBrackets(),
    // Java 只有"文档里的词 + 关键字"；Python 的语言包自带局部名与内置名，别覆盖掉
    language === 'java'
      ? autocompletion({ override: [completeAnyWord, completeFromList(JAVA_KEYWORDS)] })
      : autocompletion(),
    highlightActiveLine(),
    keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, ...completionKeymap, indentWithTab]),
    language === 'java' ? java() : python(),
    codeMirrorTheme,
    sizing,
    syntaxHighlighting(codeMirrorHighlight),
  ]
}

export function CodeEditor({ value, onChange, language, ariaLabel = '练习代码' }: CodeEditorProps) {
  const host = useRef<HTMLDivElement | null>(null)
  const view = useRef<EditorView | null>(null)
  // onChange 进 ref：把它放进 effect 依赖会让每次输入都重建编辑器（光标与撤销栈全丢）
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  // 重建 effect 不依赖 value，但它要用**最新**的文本建 doc
  const valueRef = useRef(value)
  valueRef.current = value

  useEffect(() => {
    if (host.current === null) {
      return
    }
    const instance = new EditorView({
      parent: host.current,
      doc: valueRef.current,
      extensions: [
        ...extensionsFor(language),
        // 键入、粘贴、撤销、补全都经过这里，不用外层轮询
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            onChangeRef.current(update.state.doc.toString())
          }
        }),
      ],
    })
    view.current = instance
    return () => {
      instance.destroy()
      view.current = null
    }
  }, [language])

  // 外部改值（切题、AI 填回）时同步进编辑器。
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

  return <div ref={host} data-testid="challenge-code" aria-label={ariaLabel} role="textbox" className="w-full" />
}
