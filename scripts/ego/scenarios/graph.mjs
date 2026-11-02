export const meta = { module: 'graph', side: 'learner' }

export const cases = [
  {
    id: 'graph-page',
    title: '知识图谱：页面可开且标题正确',
    write: false,
    async run(s) {
      await s.step('打开图谱', async () => {
        await s.goto('/#/graph')
        await s.expectText('你已经碰到什么')
      })
    },
  },
  {
    id: 'graph-state',
    title: '知识图谱：有图、空态或状态提示',
    write: false,
    async run(s) {
      await s.goto('/#/graph')
      await s.step('给出确定状态', async () => {
        const stats = await s.count('[data-testid="graph-stats"]')
        const empty = await s.count('[data-testid="graph-empty"]')
        const state = await s.count('[data-testid="graph-state"]')
        if (!stats && !empty && !state) throw new Error('图谱页没有图、空态或状态提示')
      })
    },
  },
  {
    id: 'graph-mode',
    title: '知识图谱：模式切换控件在',
    write: false,
    async run(s) {
      await s.goto('/#/graph')
      await s.step('模式切换可见', async () => {
        await s.waitFor('[data-testid="graph-mode-toggle"]')
      })
    },
  },
]
