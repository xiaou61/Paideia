import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import {
  DEFAULT_SKIN,
  SKIN_KEY,
  THEME_KEY,
  isTheme,
  type SkinId,
  type Theme,
  type ThemeStorage,
} from './keys'
import { isSkin } from './skins'
import { THEME_CHANGE_EVENT } from './theme-events'

interface ThemeContextValue {
  theme: Theme
  /** 当前皮肤 id。`default` 是改造前的黑白主题。 */
  skin: SkinId
  setTheme: (theme: Theme) => void
  toggleTheme: () => void
  setSkin: (skin: SkinId) => void
}

const ThemeContext = createContext<ThemeContextValue | null>(null)

/**
 * 通知"读令牌的一方"重新取值（WORK-079）。
 *
 * <p>画布类组件（知识图谱的 sigma / 3d-force-graph、rough.js 手绘图）拿到的是颜色**字符串**，
 * 不是类名，所以 CSS 变量变了它们不会自动重画。事件比 Context 更合适：
 * 它们要的不是重新 render，而是"变色了，再读一遍"。
 */
function notifyThemeChange(): void {
  if (typeof document === 'undefined') {
    return
  }
  document.dispatchEvent(new Event(THEME_CHANGE_EVENT))
}

function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  notifyThemeChange()
}

/**
 * 皮肤落到 `<html data-skin>`。
 *
 * <p>`default` 也照样写上属性：它匹配不到任何皮肤块（默认皮肤就是 `:root` 本身），
 * 但"当前选了哪个"在 DOM 上必须可读 —— 外观页的选中态与 e2e 都读它。
 */
function applySkin(skin: SkinId): void {
  document.documentElement.dataset['skin'] = skin
  notifyThemeChange()
}

/**
 * 初始主题直接从 <html> 上已有的类读出来。
 *
 * 这里**刻意不重算一遍**（不读存储、不看系统偏好）：首屏脚本已经算过一次并落了类，
 * 视图层再算一次就可能得出不同结论 —— 那是"首屏颜色对、点一下又跳回去"这类
 * 只在部分环境下复现的 bug。唯一事实来源是 DOM 的当前状态。皮肤同理（WORK-079）。
 */
function readAppliedTheme(): Theme {
  if (typeof document === 'undefined') {
    return 'light'
  }
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

function readAppliedSkin(): SkinId {
  if (typeof document === 'undefined') {
    return DEFAULT_SKIN
  }
  const applied = document.documentElement.dataset['skin']
  return isSkin(applied) ? applied : DEFAULT_SKIN
}

const DARK_QUERY = '(prefers-color-scheme: dark)'

export function ThemeProvider({
  storage,
  children,
}: {
  /** 应用把 `platform.cache` 传进来；不传则主题只在本次会话有效。 */
  storage?: ThemeStorage
  children: ReactNode
}) {
  const [theme, setThemeState] = useState<Theme>(readAppliedTheme)
  const [skin, setSkinState] = useState<SkinId>(readAppliedSkin)

  const setTheme = useCallback(
    (next: Theme) => {
      applyTheme(next)
      setThemeState(next)
      storage?.set(THEME_KEY, next)
    },
    [storage],
  )

  const setSkin = useCallback(
    (next: SkinId) => {
      // 只接受注册表里的 id：存储里写进一个不存在的 id 会让用户重开页面后"皮肤没了"
      // 且没有任何解释。回退到默认皮肤是可解释的行为。
      const resolved = isSkin(next) ? next : DEFAULT_SKIN
      applySkin(resolved)
      setSkinState(resolved)
      storage?.set(SKIN_KEY, resolved)
    },
    [storage],
  )

  /**
   * 用户还没有显式选择过时跟随系统换色；一旦选择过就不再跟随，
   * 否则用户在系统设置里改了深色，会把他自己挑的主题覆盖掉。
   */
  useEffect(() => {
    if (isTheme(storage?.get(THEME_KEY))) {
      return
    }
    const query = globalThis.matchMedia?.(DARK_QUERY)
    if (query === undefined) {
      return
    }
    const onChange = (event: MediaQueryListEvent): void => {
      // 挂载之后用户可能已经显式选过主题。这个 effect 的依赖是 [storage]，setTheme 不会让它
      // 重跑，监听器会一直留着——不加这层判断，用户挑好的主题会被随后的系统换色覆盖。
      if (isTheme(storage?.get(THEME_KEY))) {
        return
      }
      const next: Theme = event.matches ? 'dark' : 'light'
      applyTheme(next)
      setThemeState(next)
    }
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [storage])

  const value = useMemo<ThemeContextValue>(
    () => ({
      theme,
      skin,
      setTheme,
      toggleTheme: () => setTheme(theme === 'dark' ? 'light' : 'dark'),
      setSkin,
    }),
    [theme, skin, setTheme, setSkin],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext)
  if (value === null) {
    throw new Error('缺少 ThemeProvider：主题必须通过 useTheme() 获取')
  }
  return value
}
