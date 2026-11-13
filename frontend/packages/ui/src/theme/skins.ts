import { DEFAULT_SKIN, type SkinId } from './keys'
import { NIGHT_SKINS } from './skins/night'
import { PAPER_TONE_SKINS } from './skins/paper-tone'
import { TERMINAL_SKINS } from './skins/terminal'

/**
 * 皮肤注册表（WORK-079）。
 *
 * <p>皮肤分两层：**样式**在 `styles/theme/skins/*.css`（令牌覆盖），**目录信息**在这里
 * （id、名字、一句话说明、风格族、标签）。分层的理由是两者变更频率与审查方式不同：
 * CSS 要过对比度测试，TS 只影响"挑得出来"。
 *
 * <p>**加一个皮肤 = 一个 CSS 块 + 这里的一条**。`skin-contract.test.ts` 会校验两边一一对应：
 * CSS 有块但这里没登记 → 用户永远选不到（死代码）；这里登记了但 CSS 没块 → 点了没反应。
 *
 * <p>`family` 是"轴"的落点：外观页按族分组、搜索按标签过滤。它**不参与运行时组合**——
 * 不做"色板 × 质感 × 字体"的运行期数学组合，因为那样每个组合的对比度就没法逐条断言了。
 * 轴的收益（好挑、好筛、以后能加自定义滑块）由元数据拿到，正确性由"每个皮肤写全自己的值"保住。
 */
export interface SkinFamily {
  id: string
  label: string
}

export interface Skin {
  /** CSS 里的 `data-skin` 值，也是落盘值。全小写、连字符分隔。 */
  id: SkinId
  name: string
  /** 卡片上的一句话。写"看起来像什么"，不写设计术语。 */
  summary: string
  /** 必须是 `SKIN_FAMILIES` 里的 id。 */
  family: string
  /** 搜索关键词。中文为主，允许几个用户会搜的英文（dark、mono 这类）。 */
  tags: readonly string[]
}

/**
 * 风格族。**顺序即外观页的分组顺序**：最常见的排在前面。
 *
 * <p>八个族是 2026-11-13 与用户确认的覆盖范围（极简黑白 / 暖纸复古 / 霓虹暗夜 / 护眼自然 /
 * 玻璃拟态 / 终端像素 / 粉彩 / 高对比无障碍）。族先声明、皮肤后填：外观页只渲染有皮肤的分组，
 * 所以暂时还没皮肤的族不会在界面上留下空标题。
 */
export const SKIN_FAMILIES: readonly SkinFamily[] = [
  { id: 'minimal', label: '极简黑白' },
  { id: 'paper', label: '暖纸复古' },
  { id: 'nature', label: '护眼自然' },
  { id: 'pastel', label: '粉彩' },
  { id: 'night', label: '霓虹暗夜' },
  { id: 'terminal', label: '终端像素' },
  { id: 'glass', label: '玻璃拟态' },
  { id: 'contrast', label: '高对比无障碍' },
]

/**
 * 全部皮肤。`default` 排第一：它是"现在的样子"，也是新用户进来看到的样子。
 *
 * <p>各族追加皮肤时只改自己那个文件（`skins/<族>.ts`），这里只负责拼装——
 * 并行写几个族的时候不会抢同一个文件。
 */
export const SKINS: readonly Skin[] = [
  {
    id: DEFAULT_SKIN,
    name: '墨白',
    summary: '黑白灰、细线、留白，默认主题',
    family: 'minimal',
    tags: ['浅色', '极简', '默认', '黑白'],
  },
  ...PAPER_TONE_SKINS,
  ...NIGHT_SKINS,
  ...TERMINAL_SKINS,
]

/** 这个字符串是不是一个已登记的皮肤 id。存储里读到脏值时用它回退默认皮肤。 */
export function isSkin(value: string | null | undefined): value is SkinId {
  if (typeof value !== 'string' || value === '') {
    return false
  }
  return SKINS.some((skin) => skin.id === value)
}

/** 按 id 取皮肤；取不到时返回默认皮肤（界面上永远有东西可显示）。 */
export function skinById(id: SkinId): Skin {
  return SKINS.find((skin) => skin.id === id) ?? (SKINS[0] as Skin)
}
