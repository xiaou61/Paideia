/**
 * 主题的键名与类型。单独成文件是因为首屏脚本要注入到 HTML 里，
 * 它只能拿到这些字面量，不能把 React 模块拖进 Vite 配置。
 */

export type Theme = 'light' | 'dark'

/** 端口层的键名：`Platform.cache` 用的是这个名字。 */
export const THEME_KEY = 'theme'

/**
 * 皮肤（WORK-079）。
 *
 * <p>皮肤 id 是**字符串**而不是联合字面量：皮肤是会一直增加的数据，把 id 写成联合类型
 * 意味着每加一个皮肤都要改一处类型定义，而类型不该成为加皮肤的关口。
 * "这个 id 是否合法"由注册表 `skins.ts` 的 `isSkin()` 回答。
 */
export type SkinId = string

/** 端口层的皮肤键名。与 `THEME_KEY` 分开：明暗与皮肤是两个互相独立的维度。 */
export const SKIN_KEY = 'skin'

/**
 * 默认皮肤：就是改造之前那套黑白主题（`globals.css` 的 `:root` / `.dark` 本身）。
 *
 * <p>它**没有** CSS 块——"什么都不覆盖"等价于"用默认令牌"。所以 `data-skin="default"` 是合法状态，
 * 只是匹配不到任何皮肤块；`skin-contract.test.ts` 会因此把 `default` 从"CSS 与注册表一一对应"
 * 的检查里排除。
 */
export const DEFAULT_SKIN: SkinId = 'default'

/**
 * localStorage 里实际落盘的完整键名。
 *
 * 前缀必须与 `packages/platform-web` 的 `KEY_PREFIX` 一致 —— 那里给所有缓存键加前缀，
 * 以避免与同源的其他页面冲突。两处不一致的后果是首屏拿不到用户已保存的偏好、
 * 退化到系统偏好（界面仍然可用，但用户的选择好像没记住）。
 * 这条一致性由 `theme-context.test.tsx` 里的检查兜住，不靠人记住。
 */
export const THEME_STORAGE_PREFIX = 'paideia:'
export const THEME_STORAGE_KEY = THEME_STORAGE_PREFIX + THEME_KEY

/**
 * 皮肤的落盘键名。前缀同上，理由见 `THEME_STORAGE_PREFIX` 的注释。
 * 首屏脚本（`vite-plugin.ts`）也读这个值，所以它必须与 `platform-web` 的 `KEY_PREFIX` 一致。
 */
export const SKIN_STORAGE_KEY = THEME_STORAGE_PREFIX + SKIN_KEY

/**
 * 主题持久化只需要读写两个操作，所以这里只声明这两个方法。
 * `packages/core` 的 `KeyValueCache`（get/set/remove）在结构上正好满足它，
 * 因此应用可以直接把 `platform.cache` 传进来，而 `packages/ui` 不必依赖 core。
 */
export interface ThemeStorage {
  get(key: string): string | null
  set(key: string, value: string): void
}

export function isTheme(value: string | null | undefined): value is Theme {
  return value === 'light' || value === 'dark'
}
