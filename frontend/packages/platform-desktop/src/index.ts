import type { KeyValueCache, Platform } from '@paideia/core'

/** 主进程提供的缓存操作。异步语义，因为要经 IPC。 */
export interface DesktopCacheBridge {
  get(key: string): Promise<string | null>
  set(key: string, value: string): Promise<void>
  remove(key: string): Promise<void>
}

/**
 * Tauri 宿主在受信任的渲染页注入的桥；需要跨进程的能力经这个桥走 IPC。
 */
export interface DesktopBridge {
  readonly cache: DesktopCacheBridge
  /**
   * 上次退出时缓存里已有的键值，由主进程在启动时读盘并注入。
   *
   * <p>**没有它，`cache.get` 在本次启动第一次写入之前永远是空的**：IPC 是异步的，
   * 而 `Platform.cache` 刻意是同步语义（见下），同步的 `get` 只能读一个进程内镜像，
   * 镜像没人填充就等于缓存不存在——表现是"用户的选择重启后丢失"。
   */
  readonly cacheSnapshot: Record<string, string>
  /** 渲染进程的服务地址，用于显示与排查。 */
  readonly rendererOrigin: string
  /** 开发渲染器不读正式缓存，也不消费正式 OAuth 握手码。 */
  readonly developmentRenderer: boolean
  /** 由宿主校验协议并交给系统浏览器。 */
  openExternal(url: string): Promise<void>
  /**
   * 订阅第三方登录握手码（WORK-037）。
   *
   * <p>主进程从 `paideia://oauth?handoff=…` 取到码后经这里送进来。
   * **只订阅、不发送**：页面拿不到"伪造一次协议唤起"的能力。
   *
   * @returns 取消订阅的函数
   */
  readonly onOauthHandoff?: (callback: (handoff: string) => void) => () => void
}

declare global {
  interface Window {
    paideiaDesktop?: DesktopBridge
  }
}

/**
 * 由桥与启动快照构造同步语义的缓存。
 *
 * <p>拆成纯函数是为了能测：镜像的填充时机正是本包唯一出过真 bug 的地方。
 */
export function createBridgeCache(
  bridge: DesktopCacheBridge,
  snapshot: Record<string, string> = {},
): KeyValueCache {
  const mirror = new Map<string, string>()
  for (const [key, value] of Object.entries(snapshot)) {
    if (typeof value === 'string') {
      mirror.set(key, value)
    }
  }

  return {
    // 同步读走镜像，因此能在首帧（主题脚本执行时）拿到上次的选择
    get: (key) => mirror.get(key) ?? null,
    set: (key, value) => {
      mirror.set(key, value)
      // 即发即忘会让"没落盘"彻底静默，而镜像已经改了——重启后用户的选择消失且没有任何线索。
      // ponytail: 只留一条日志、不回滚镜像；要真正保证镜像与磁盘一致，得等 IPC 确认再改内存，
      // 那会把同步的 get 语义拖进异步，暂不值当。
      bridge.set(key, value).catch((cause) => console.warn('缓存写入失败：', key, cause))
    },
    remove: (key) => {
      mirror.delete(key)
      bridge.remove(key).catch((cause) => console.warn('缓存删除失败：', key, cause))
    },
  }
}

function requireBridge(): DesktopBridge {
  const bridge = globalThis.window?.paideiaDesktop
  if (bridge === undefined) {
    throw new Error(
      '缺少桌面桥：这个包只能在 Tauri 宿主已注入 window.paideiaDesktop 的窗口里使用',
    )
  }
  return bridge
}

export function createDesktopPlatform(): Platform {
  const bridge = requireBridge()
  return { kind: 'desktop', cache: createBridgeCache(bridge.cache, bridge.cacheSnapshot) }
}

export function desktopRendererOrigin(): string {
  return requireBridge().rendererOrigin
}
