export const meta = { module: 'identity', side: 'learner' }

export const cases = [
  {
    id: 'identity-profile',
    title: '身份与目标：页面可开且画像控件在',
    write: false,
    async run(s) {
      await s.step('打开身份与目标', async () => {
        await s.goto('/#/profile')
        await s.expectText('身份与目标')
      })
      await s.step('画像控件可见', async () => {
        await s.waitFor('[data-testid="profile-identity"]')
      })
    },
  },
  {
    id: 'identity-mcp',
    title: 'MCP 接入：页面可开',
    write: false,
    async run(s) {
      await s.goto('/#/settings/mcp')
      await s.step('确认 MCP 接入页', async () => {
        await s.expectText('MCP 接入')
      })
    },
  },
  {
    id: 'identity-account-menu',
    title: '账号菜单：能看到身份入口与登出',
    write: false,
    async run(s) {
      await s.goto('/#/')
      await s.step('打开账号菜单', async () => {
        await s.clickCss('[data-testid="account-menu-trigger"]', '打开账号菜单')
        await s.expectText('身份与目标')
        await s.expectText('登出')
        await s.hotkey('Escape')
      })
    },
  },
]
