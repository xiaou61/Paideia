export const meta = { module: 'admin-datasets', side: 'admin' }

const ADMIN = '/admin'

export const cases = [
  {
    id: 'admin-datasets-list',
    title: 'SQL 数据集入口：/sql-datasets 落到列表页',
    write: false,
    async run(s) {
      await s.step('打开数据集入口', async () => {
        await s.goto(`${ADMIN}/#/sql-datasets`)
        await s.expectUrl('/admin/#/sql-datasets/list')
        await s.expectText('SQL 数据集')
      })
    },
  },
  {
    id: 'admin-datasets-reports',
    title: '数据集举报：页面可开',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/sql-datasets/reports`)
      await s.step('确认数据集举报页', async () => {
        await s.expectText('数据集举报')
      })
    },
  },
  {
    id: 'admin-datasets-community',
    title: '数据集列表显示社区数据集区',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/sql-datasets/list`)
      await s.step('确认社区数据集区', async () => {
        await s.expectText('社区数据集')
      })
    },
  },
]
