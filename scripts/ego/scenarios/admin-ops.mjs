export const meta = { module: 'admin-ops', side: 'admin' }

const ADMIN = '/admin'

export const cases = [
  {
    id: 'admin-ops-panel',
    title: '运维入口：/ops 落到运维面板',
    write: false,
    async run(s) {
      await s.step('打开运维入口', async () => {
        await s.goto(`${ADMIN}/#/ops`)
        await s.expectUrl('/admin/#/ops/panel')
        await s.expectText('运维')
      })
    },
  },
  {
    id: 'admin-ops-incidents',
    title: '事件页：页面可开',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/ops/incidents`)
      await s.step('确认事件页', async () => {
        await s.expectText('事件')
      })
    },
  },
  {
    id: 'admin-ops-nav',
    title: '从运维面板点侧栏事件能到达事件页',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/ops/panel`)
      await s.step('点侧栏事件', async () => {
        await s.click('事件')
        await s.expectUrl('/admin/#/ops/incidents')
      })
    },
  },
]
