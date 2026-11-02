export const meta = { module: 'home', side: 'learner' }

export const cases = [
  {
    id: 'home-main',
    title: '首页主链路：登录后能看到问候与最近更新',
    write: false,
    async run(s) {
      await s.step('打开首页', async () => {
        await s.goto('/#/')
        await s.expectText('你好')
      })
      await s.step('首页有内容区', async () => {
        await s.expectText('最近更新')
      })
    },
  },
  {
    id: 'home-nav-groups',
    title: '导航四组都在页头',
    write: false,
    async run(s) {
      await s.goto('/#/')
      await s.step('四组导航可见', async () => {
        for (const label of ['求职', '学习', '知识', '练习']) await s.expectText(label)
      })
    },
  },
  {
    id: 'home-entry-knowledge',
    title: '从首页入口能进知识库',
    write: false,
    async run(s) {
      await s.goto('/#/')
      await s.step('点「去知识库」', async () => {
        await s.clickCss('[data-testid="hero-open-knowledge"]', '去知识库')
        await s.expectUrl('/#/knowledge')
      })
    },
  },
]
