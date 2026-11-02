export const meta = { module: 'learn', side: 'learner' }

export const cases = [
  {
    id: 'learn-tracks',
    title: '从零开始：轨道列表有核心/进阶分段',
    write: false,
    async run(s) {
      await s.step('打开学习页', async () => {
        await s.goto('/#/learn')
        await s.expectText('从零开始')
      })
      await s.step('分段标题可见', async () => {
        await s.expectText('核心')
      })
    },
  },
  {
    id: 'learn-track-map',
    title: '轨道地图：点第一条轨道能进关卡地图',
    write: false,
    async run(s) {
      await s.goto('/#/learn')
      await s.step('点第一条轨道', async () => {
        await s.waitFor('[data-testid="learn-track-python-from-zero"]', 'visible', 20_000)
        await s.clickFirstVisible('[data-testid="learn-track-python-from-zero"]')
        await s.waitFor('[data-testid="track-map"]')
      })
      await s.step('第一关可见', async () => {
        await s.waitFor('[data-testid="stage-1"]')
      })
    },
  },
  {
    id: 'learn-stage-entry',
    title: '关卡：第一关能点进正文',
    write: false,
    async run(s) {
      await s.goto('/#/learn')
      await s.waitFor('[data-testid="learn-track-python-from-zero"]', 'visible', 20_000)
      await s.clickFirstVisible('[data-testid="learn-track-python-from-zero"]')
      await s.waitFor('[data-testid="track-map"]')
      await s.step('点第一关', async () => {
        await s.clickCss('[data-testid="stage-1"]', '进入第一关')
        await s.waitFor('[data-testid="markdown-body"]')
      })
    },
  },
]
