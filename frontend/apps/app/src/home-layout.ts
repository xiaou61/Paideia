import { JSON_HEADERS, type ApiClient } from '@paideia/core'

import { api } from './api'

/**
 * 首页自定义布局（服务端同步）。
 *
 * <p>结构由**前端拥有**，服务端只做透明存取（一张 `user_home_layout` 表、一个 JSON 列）。
 * 这样以后给布局加字段不需要改表/改后端；老配置缺字段时由下面的 merge 函数补默认。
 */
export type HomeBlockId = 'essay' | 'continue' | 'categories' | 'entries'

/** 可排序/可显示隐藏的版块（hero 问候区固定在最上，不在此列）。 */
export const HOME_BLOCK_ORDER: HomeBlockId[] = ['essay', 'continue', 'categories', 'entries']

export const HOME_BLOCK_LABELS: Record<HomeBlockId, string> = {
  essay: '面经专区',
  continue: '继续学习',
  categories: '按分类浏览',
  entries: '所有入口',
}

export interface HomeBlockPref {
  id: HomeBlockId
  visible: boolean
}

export interface HomeQuickLinkPref {
  /** 'preset' 指内置入口（用 path 识别）；'custom' 是用户自建链接。 */
  type: 'preset' | 'custom'
  /** preset: 内置入口的 to；custom: 目标地址。 */
  path: string
  visible: boolean
  /** custom 专用：显示名。 */
  title?: string
  /** custom 专用：一句描述。 */
  description?: string
}

export interface HomeLayoutConfig {
  version: number
  blocks?: HomeBlockPref[]
  quickLinks?: HomeQuickLinkPref[]
}

/** 版块顺序合并：保留用户的顺序与显隐，忽略未知 id，把新增内置版块补到末尾（默认可见）。 */
export function mergeBlockPrefs(saved?: HomeBlockPref[]): HomeBlockPref[] {
  const known = new Set<HomeBlockId>(HOME_BLOCK_ORDER)
  const out: HomeBlockPref[] = []
  const seen = new Set<HomeBlockId>()
  for (const block of saved ?? []) {
    if (known.has(block.id) && !seen.has(block.id)) {
      out.push({ id: block.id, visible: block.visible !== false })
      seen.add(block.id)
    }
  }
  for (const id of HOME_BLOCK_ORDER) {
    if (!seen.has(id)) {
      out.push({ id, visible: true })
    }
  }
  return out
}

export interface HomeLayoutApi {
  get(): Promise<HomeLayoutConfig>
  save(layout: HomeLayoutConfig): Promise<HomeLayoutConfig>
}

export function createHomeLayoutApi(client: ApiClient): HomeLayoutApi {
  const base = '/api/v1/account/home-layout'
  return {
    get: () => client.requestEnvelope<HomeLayoutConfig>(base),
    save: (layout) =>
      client.requestEnvelope<HomeLayoutConfig>(base, {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(layout),
      }),
  }
}

/** 应用级单例，页面直接 import。 */
export const homeLayoutApi = createHomeLayoutApi(api)
