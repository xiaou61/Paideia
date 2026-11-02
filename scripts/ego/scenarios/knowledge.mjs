export const meta = { module: 'knowledge', side: 'learner' }

export const cases = [
  {
    id: 'knowledge-categories',
    title: '知识库：分类页有分类网格与全部条目入口',
    write: false,
    async run(s) {
      await s.step('打开分类页', async () => {
        await s.goto('/#/knowledge')
        await s.expectText('挑一个分类读起')
        await s.expectText('全部条目')
      })
    },
  },
  {
    id: 'knowledge-entries',
    title: '全部条目：列表或空态二选一，搜索框在',
    write: false,
    async run(s) {
      await s.step('打开全部条目', async () => {
        await s.goto('/#/knowledge/entries')
        await s.expectText('全部条目')
      })
      await s.step('搜索框可见', async () => {
        await s.waitFor('[data-testid="knowledge-search"]')
      })
    },
  },
  {
    id: 'knowledge-entry-detail',
    title: '条目正文：点第一条能进正文并看到自评',
    write: false,
    async run(s) {
      await s.goto('/#/knowledge/entries')
      await s.step('点第一条条目', async () => {
        await s.waitFor('[data-testid="entry-list"]', 'visible', 20_000)
        await s.clickFirstVisible('[data-testid="entry-list"] [data-testid^="entry-link-"]')
        await s.waitFor('[data-testid="markdown-body"]')
      })
      await s.step('自评区可见', async () => {
        await s.waitFor('[data-testid="self-assessment"]')
      })
    },
  },
]
