export const meta = { module: 'admin-users', side: 'admin' }

const ADMIN = '/admin'

export const cases = [
  {
    id: 'admin-users-list',
    title: '用户管理：页面可开且账号区可见',
    write: false,
    async run(s) {
      await s.step('打开用户管理', async () => {
        await s.goto(`${ADMIN}/#/users`)
        await s.expectText('用户管理')
      })
      await s.step('页面有账号列表', async () => {
        await s.expectText('账号')
      })
    },
  },
  {
    id: 'admin-users-reports',
    title: '举报处理：页面可开且说明可见',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/reports`)
      await s.step('确认举报处理页', async () => {
        await s.expectText('举报处理')
        await s.expectText('被举报的分享')
      })
    },
  },
  {
    id: 'admin-users-nav',
    title: '从举报页点侧栏用户管理能到达用户页',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/reports`)
      await s.step('点侧栏用户管理', async () => {
        await s.click('用户管理')
        await s.expectUrl('/admin/#/users')
      })
    },
  },
]
