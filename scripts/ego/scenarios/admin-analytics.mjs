export const meta = { module: 'admin-analytics', side: 'admin' }

const ADMIN = '/admin'

export const cases = [
  {
    id: 'admin-analytics-overview',
    title: '管理端总览：能打开且显示管理端标识',
    write: false,
    async run(s) {
      await s.step('打开管理端总览', async () => {
        await s.goto(`${ADMIN}/#/`)
        await s.expectText('管理端')
      })
      await s.step('侧栏显示总览入口', async () => {
        await s.expectText('总览')
      })
    },
  },
  {
    id: 'admin-analytics-screen',
    title: '数据大屏：标题与说明可见，不出现加载失败',
    write: false,
    async run(s) {
      await s.step('打开数据大屏', async () => {
        await s.goto(`${ADMIN}/#/analytics`)
        await s.expectText('数据大屏')
      })
      await s.step('页面给出确定状态而不是白屏', async () => {
        await s.expectText('数据从哪来')
        await s.expectNoText('加载失败', 3000)
      })
    },
  },
  {
    id: 'admin-analytics-nav',
    title: '从数据大屏点侧栏总览能回到总览',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/analytics`)
      await s.step('点侧栏总览', async () => {
        await s.click('总览')
        await s.expectUrl('/admin/#/')
      })
    },
  },
]
