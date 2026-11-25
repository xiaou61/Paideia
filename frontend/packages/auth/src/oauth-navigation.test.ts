// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest'

import { canOpenOAuthAuthorization, openOAuthAuthorization } from './oauth-navigation'

const authorization = 'https://example.invalid/authorize'

afterEach(() => {
  Reflect.deleteProperty(window, 'paideiaDesktop')
  vi.restoreAllMocks()
})

describe('OAuth authorization navigation', () => {
  it('keeps normal web navigation', async () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    expect(canOpenOAuthAuthorization()).toBe(true)
    await openOAuthAuthorization(authorization)
    expect(assign).toHaveBeenCalledWith(authorization)
  })

  it('lets the installed host open the provider without navigating the WebView', async () => {
    const openExternal = vi.fn(async (_url: string) => {})
    Reflect.set(window, 'paideiaDesktop', { openExternal })
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    expect(canOpenOAuthAuthorization()).toBe(true)
    await openOAuthAuthorization(authorization)
    expect(openExternal).toHaveBeenCalledWith(authorization)
    expect(assign).not.toHaveBeenCalled()
  })

  it('rejects navigation in the dedicated development renderer', async () => {
    const openExternal = vi.fn(async (_url: string) => {})
    Reflect.set(window, 'paideiaDesktop', { developmentRenderer: true, openExternal })
    expect(canOpenOAuthAuthorization()).toBe(false)
    await expect(openOAuthAuthorization(authorization)).rejects.toThrow('当前桌面版本不支持第三方授权')
    expect(openExternal).not.toHaveBeenCalled()
  })
})
