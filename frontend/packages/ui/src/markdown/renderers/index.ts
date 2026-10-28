import type { CustomRenderer, CustomRendererProps } from 'streamdown'

import { guard } from './blocks'
import { CodeChallenge } from './code-challenge'
import { CodeMap } from './code-map'
import { CodeTour } from './code-tour'
import { LearningPath } from './learning-path'
import { Quiz } from './quiz'
import { Sketch } from './sketch'

/**
 * 源码解读的块注册表（WORK-014）。
 *
 * <p><b>只在文档模式启用</b>：助手流式回答里模型也可能输出 ```` ```code-tour ````，
 * 那是文本，不是我们要执行的组件。由 `markdown.tsx` 按 `streaming` 决定用不用这张表。
 *
 * <p>`guard()` 返回的是**模块级常量**：每次渲染新建一个组件类型会让 React 把整棵块子树
 * 卸载重建，展开状态、选中的题目都会丢。
 */
export const SourceReadingTour = guard('代码走读', CodeTour)
export const SourceReadingMap = guard('架构地图', CodeMap)
export const SourceReadingPath = guard('学习路径', LearningPath)
export const SourceReadingChallenge = guard('动手练习', CodeChallenge)
export const SourceReadingQuiz = guard('小测', Quiz)
export const DocumentSketch = guard('手绘图', Sketch)

export const documentRenderers: CustomRenderer[] = [
  { language: ['code-tour', 'tour'], component: SourceReadingTour },
  { language: ['code-map'], component: SourceReadingMap },
  { language: ['learning-path'], component: SourceReadingPath },
  { language: ['code-challenge', 'challenge'], component: SourceReadingChallenge },
  { language: ['quiz'], component: SourceReadingQuiz },
  { language: ['sketch'], component: DocumentSketch },
]

export { SourceReadingProvider, useSourceReading } from './context'
export type {
  DocumentContextValue,
  EntryProgress,
  ProgressPatch,
  ProgressPort,
  RunOutput,
  SandboxPort,
} from './context'
export * from './schema'
