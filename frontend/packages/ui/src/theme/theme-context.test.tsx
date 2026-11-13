import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { DEFAULT_SKIN, SKIN_KEY, THEME_KEY, THEME_STORAGE_PREFIX, type ThemeStorage } from './keys'
import { THEME_CHANGE_EVENT } from './theme-events'
import { ThemeProvider, useTheme } from './theme-context'

/** 只用来把当前主题渲染出来，好让断言看得见。 */
function ThemeProbe() {
  const { theme, toggleTheme } = useTheme()
  return (
    <button type="button" onClick={toggleTheme} data-testid="probe">
      {theme}
    </button>
  )
}

/** 皮肤探针：显示当前皮肤，并在点击时换到另一个（模拟外观页的动作）。 */
function SkinProbe() {
  const { skin, setSkin } = useTheme()
  return (
    <button type="button" onClick={() => setSkin('terminal')} data-testid="skin-probe">
      {skin}
    </button>
  )
}

function memoryStorage(initial: Record<string, string> = {}) {
  const store = new Map(Object.entries(initial))
  const storage: ThemeStorage = {
    get: (key) => store.get(key) ?? null,
    set: (key, value) => {
      store.set(key, value)
    },
  }
  return { storage, store }
}

/** 可控的 prefers-color-scheme，用来验证"没存过就跟随系统"。 */
function stubColorScheme(matches: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches,
    media: query,
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.add(listener)
    },
    removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.delete(listener)
    },
  }))
  return {
    listenerCount: () => listeners.size,
    emit: (next: boolean) => {
      for (const listener of listeners) {
        listener({ matches: next } as MediaQueryListEvent)
      }
    },
  }
}

function workspaceRoot(): string {
  let directory = process.cwd()
  for (let depth = 0; depth < 6; depth += 1) {
    if (existsSync(path.join(directory, 'pnpm-workspace.yaml'))) {
      return directory
    }
    directory = path.dirname(directory)
  }
  throw new Error('向上找不到 pnpm-workspace.yaml，无法定位工作区根目录')
}

afterEach(() => {
  cleanup()
  document.documentElement.classList.remove('dark')
  document.documentElement.removeAttribute('data-skin')
  vi.unstubAllGlobals()
})

describe('主题', () => {
  it('初始值取自 <html> 上已落的类，不重算一遍', () => {
    document.documentElement.classList.add('dark')
    // 存储里故意放一个相反的值：视图层必须忽略它，否则就会出现
    // "首屏脚本说是深色、React 挂载后又跳回浅色"这种只在部分环境复现的闪烁。
    const { storage } = memoryStorage({ [THEME_KEY]: 'light' })

    render(
      <ThemeProvider storage={storage}>
        <ThemeProbe />
      </ThemeProvider>,
    )

    expect(screen.getByTestId('probe').textContent).toBe('dark')
  })

  it('切换时同时改类与写存储', () => {
    const { storage, store } = memoryStorage()

    render(
      <ThemeProvider storage={storage}>
        <ThemeProbe />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('probe').textContent).toBe('light')

    // 用 fireEvent 而不是原生 click：它会包在 act 里，状态才会同步刷新
    fireEvent.click(screen.getByTestId('probe'))

    expect(screen.getByTestId('probe').textContent).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(store.get(THEME_KEY)).toBe('dark')
  })

  it('没有已保存的偏好时跟随系统换色', () => {
    const colorScheme = stubColorScheme(false)
    const { storage } = memoryStorage()

    render(
      <ThemeProvider storage={storage}>
        <ThemeProbe />
      </ThemeProvider>,
    )
    expect(colorScheme.listenerCount()).toBe(1)

    // 系统换色是从 React 之外推过来的，必须包在 act 里才会被应用并刷新
    act(() => {
      colorScheme.emit(true)
    })

    expect(screen.getByTestId('probe').textContent).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('已保存偏好后不再跟随系统', () => {
    const colorScheme = stubColorScheme(false)
    const { storage } = memoryStorage({ [THEME_KEY]: 'light' })

    render(
      <ThemeProvider storage={storage}>
        <ThemeProbe />
      </ThemeProvider>,
    )

    expect(colorScheme.listenerCount()).toBe(0)
  })
})

describe('皮肤', () => {
  it('初始值取自 <html data-skin>，不重算一遍', () => {
    document.documentElement.dataset['skin'] = 'paper'
    // 存储里放另一个值：视图层必须忽略它，否则会出现"首屏脚本说是暖纸、挂载后跳回墨白"
    const { storage } = memoryStorage({ [SKIN_KEY]: 'terminal' })

    render(
      <ThemeProvider storage={storage}>
        <SkinProbe />
      </ThemeProvider>,
    )

    expect(screen.getByTestId('skin-probe').textContent).toBe('paper')
  })

  it('DOM 上是没登记过的脏值时回退默认皮肤', () => {
    document.documentElement.dataset['skin'] = 'skin-removed-last-release'

    render(
      <ThemeProvider storage={memoryStorage().storage}>
        <SkinProbe />
      </ThemeProvider>,
    )

    expect(screen.getByTestId('skin-probe').textContent).toBe(DEFAULT_SKIN)
  })

  it('换肤时同时改属性、写存储，并派发主题变更事件', () => {
    const { storage, store } = memoryStorage()
    const listener = vi.fn()
    document.addEventListener(THEME_CHANGE_EVENT, listener)

    render(
      <ThemeProvider storage={storage}>
        <SkinProbe />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('skin-probe').textContent).toBe(DEFAULT_SKIN)

    fireEvent.click(screen.getByTestId('skin-probe'))

    expect(screen.getByTestId('skin-probe').textContent).toBe('terminal')
    expect(document.documentElement.dataset['skin']).toBe('terminal')
    expect(store.get(SKIN_KEY)).toBe('terminal')
    // 画布类组件（知识图谱）靠这个事件重读令牌，漏派发就会"页面变了、图没变"
    expect(listener).toHaveBeenCalled()

    document.removeEventListener(THEME_CHANGE_EVENT, listener)
  })

  it('明暗切换也派发同一个事件（令牌整套换了）', () => {
    const listener = vi.fn()
    document.addEventListener(THEME_CHANGE_EVENT, listener)

    render(
      <ThemeProvider storage={memoryStorage().storage}>
        <ThemeProbe />
      </ThemeProvider>,
    )
    fireEvent.click(screen.getByTestId('probe'))

    expect(listener).toHaveBeenCalled()

    document.removeEventListener(THEME_CHANGE_EVENT, listener)
  })
})

describe('键名一致性', () => {
  it('localStorage 前缀与 packages/platform-web 的 KEY_PREFIX 相同', () => {
    // 首屏脚本直接读 localStorage，它只能拿到字面量，没法通过类型或导入保证一致；
    // 因此在这里把两处字面量对一次。不一致不会报错，只会让用户的选择在首屏失效，
    // 属于"不查就发现不了"的那类问题。
    const source = readFileSync(
      path.join(workspaceRoot(), 'packages', 'platform-web', 'src', 'index.ts'),
      'utf8',
    )
    const matched = /KEY_PREFIX\s*=\s*'([^']*)'/.exec(source)

    expect(matched, 'platform-web 里没有找到 KEY_PREFIX').not.toBeNull()
    expect(matched?.[1]).toBe(THEME_STORAGE_PREFIX)
  })
})
