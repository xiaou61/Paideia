import rawMeta from './component-meta.json'

export interface ComponentMeta {
  /** 分类：布局 / 基元 / 表单 / 浮层 / 数据态 / 导航 / 反馈 / 数据展示 / Markdown / 主题 */
  category: string
  /** 该模块职责的一句话说明 */
  description: string
  /** 相对 packages/ui/src 的源文件路径 */
  source: string
}

/**
 * 组件目录元数据的唯一人工来源。key 是 `index.ts` 里 re-export 的源模块 specifier。
 *
 * 只维护「分类 / 一句话用途 / 源文件」这类源码里不明显的知识；组件名、子部件与导出关系
 * 由 `frontend/scripts/gen-ui-manifest.mjs` 从 `index.ts` 自动解析，不在这里重复。
 * 改完 JSON 后跑 `pnpm ui:manifest` 重新生成 `frontend/docs/ui-manifest.md`，
 * `pnpm ui:manifest:check` 会在 CI 里防止清单与源码漂移。
 */
export const COMPONENT_META = rawMeta as Record<string, ComponentMeta>
