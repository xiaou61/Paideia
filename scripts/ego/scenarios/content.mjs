export const meta = { module: 'content', side: 'learner' }

export const cases = [
  {
    id: 'content-digest',
    title: '每日速递：页面可开，日/周页签在',
    write: false,
    async run(s) {
      await s.step('打开速递', async () => {
        await s.goto('/#/digest')
        await s.expectText('今天值得知道的事')
      })
      await s.step('日/周页签可见', async () => {
        await s.waitFor('[data-testid="digest-tab-daily"]')
        await s.waitFor('[data-testid="digest-tab-weekly"]')
      })
    },
  },
  {
    id: 'content-books',
    title: '书籍详解：列表或空态二选一',
    write: false,
    async run(s) {
      await s.goto('/#/books')
      await s.step('确认书籍页', async () => {
        await s.expectText('书籍详解')
        const grid = await s.count('[data-testid="books-grid"]')
        const empty = await s.count('[data-testid="books-empty"]')
        if (!grid && !empty) throw new Error('书籍页没有列表也没有空态')
      })
    },
  },
  {
    id: 'content-source',
    title: '源码精读：列表或空态二选一',
    write: false,
    async run(s) {
      await s.goto('/#/source')
      await s.step('确认源码页', async () => {
        await s.expectText('源码精读')
        const empty = await s.count('[data-testid="source-empty"]')
        const state = await s.count('[data-testid="source-state"]')
        const projects = await s.count('a[href*="/source/"]')
        if (!empty && !state && !projects) throw new Error('源码页没有列表、空态或状态提示')
      })
    },
  },
]
