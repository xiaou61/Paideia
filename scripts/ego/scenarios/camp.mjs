export const meta = { module: 'camp', side: 'learner' }

export const cases = [
  {
    id: 'camp-list',
    title: '训练营：列表页可开，有列表或空态',
    write: false,
    async run(s) {
      await s.step('打开训练营', async () => {
        await s.goto('/#/camp')
        await s.expectText('用简历开一个训练营')
      })
      await s.step('列表或空态二选一', async () => {
        const list = await s.count('[data-testid="camp-list"]')
        const empty = await s.count('[data-testid="camp-empty"]')
        if (!list && !empty) throw new Error('训练营页没有列表也没有空态')
      })
    },
  },
  {
    id: 'camp-create-entry',
    title: '训练营：开营入口在页面上',
    write: false,
    async run(s) {
      await s.goto('/#/camp')
      await s.step('开营入口可见', async () => {
        await s.waitFor('[data-testid="camp-create"]')
      })
    },
  },
  {
    id: 'camp-detail-guard',
    title: '训练营详情：不存在的编号给可读提示',
    write: false,
    async run(s) {
      await s.goto('/#/camp/99999999')
      await s.step('有确定状态', async () => {
        await s.expectText('训练营')
      })
    },
  },
]
