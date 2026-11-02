export const meta = { module: 'progress', side: 'learner' }

export const cases = [
  {
    id: 'progress-page',
    title: '学习记录：四块区域都在',
    write: false,
    async run(s) {
      await s.step('打开学习记录', async () => {
        await s.goto('/#/progress')
        await s.expectText('学习记录')
      })
      await s.step('四块区域可见', async () => {
        for (const block of ['progress-lines', 'progress-failing', 'progress-reviews', 'progress-weak']) {
          await s.waitFor(`[data-testid="${block}"]`)
        }
      })
    },
  },
  {
    id: 'progress-review-page',
    title: '复习：页面给出待复习列表或空态',
    write: false,
    async run(s) {
      await s.goto('/#/review')
      await s.step('确认复习页', async () => {
        await s.expectText('今天该回头看的')
      })
      await s.step('列表或空态二选一', async () => {
        const due = await s.count('[data-testid="review-due"]')
        const empty = await s.count('[data-testid="review-empty"]')
        const state = await s.count('[data-testid="review-state"]')
        if (!due && !empty && !state) throw new Error('复习页没有列表、空态或状态提示')
      })
    },
  },
  {
    id: 'progress-assessments',
    title: '我的自评：页面可开',
    write: false,
    async run(s) {
      await s.goto('/#/my-assessments')
      await s.step('确认自评页', async () => {
        await s.expectText('我标记过的内容')
      })
    },
  },
]
