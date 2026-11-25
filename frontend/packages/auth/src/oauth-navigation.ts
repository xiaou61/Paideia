interface DesktopOAuthHost {
  readonly developmentRenderer?: boolean
  openExternal?: (url: string) => Promise<void>
}

// The auth package does not depend on a native host implementation.
export function desktopOAuthHost(): DesktopOAuthHost | undefined {
  return (window as Window & { paideiaDesktop?: DesktopOAuthHost }).paideiaDesktop
}

export function canOpenOAuthAuthorization(): boolean {
  const desktop = desktopOAuthHost()
  return !desktop || (!desktop.developmentRenderer && typeof desktop.openExternal === 'function')
}

export async function openOAuthAuthorization(url: string): Promise<void> {
  const desktop = desktopOAuthHost()
  if (!desktop) {
    window.location.assign(url)
    return
  }
  if (desktop.developmentRenderer || !desktop.openExternal) {
    throw new Error('当前桌面版本不支持第三方授权，请在网页版操作。')
  }
  await desktop.openExternal(url)
}
