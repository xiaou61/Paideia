export const meta = { module: 'notes', side: 'learner' }

export const cases = [
  {
    id: 'notes-list',
    title: '我的笔记：列表或空态二选一',
    write: false,
    async run(s) {
      await s.step('打开笔记页', async () => {
        await s.goto('/#/notes')
        await s.expectText('我的笔记')
      })
      await s.step('有列表或空态', async () => {
        await s.waitForAny([
          '[data-testid="notes-list"]',
          '[data-testid="notes-empty"]',
          '[data-testid="notes-filter-empty"]',
        ])
      })
    },
  },
  {
    id: 'notes-new-page',
    title: '新建笔记：独立页面能打开且标题输入在',
    write: false,
    async run(s) {
      await s.goto('/#/notes/new')
      await s.step('确认新建页', async () => {
        await s.expectText('新建笔记')
        await s.waitFor('[data-testid="new-note-title"]')
      })
    },
  },
  {
    id: 'notes-share-page',
    title: '分享链接：无效分享给出提示而不是白屏',
    write: false,
    async run(s) {
      await s.goto('/#/share/not-a-real-share')
      await s.step('无效分享有确定状态', async () => {
        await s.expectText('分享')
      })
    },
  },
  {
    id: 'notes-create-delete',
    title: '笔记写入：新建 → 刷新仍在 → 删除',
    write: true,
    async run(s) {
      const title = s.safeName('笔记')
      await s.step('新建笔记', async () => {
        await s.goto('/#/notes/new')
        await s.fill('[data-testid="new-note-title"]', title)
        await s.clickCss('[data-testid="new-note-create"]', '创建笔记')
        await s.waitFor('[data-testid="note-title"]')
        const value = await s.valueOf('[data-testid="note-title"]')
        if (value !== title) throw new Error(`创建后标题是「${value}」，期望「${title}」`)
      })
      await s.step('刷新后仍在', async () => {
        await s.reload()
        await s.goto('/#/notes')
        await s.click(title)
        await s.waitFor('[data-testid="note-title"]')
        const value = await s.valueOf('[data-testid="note-title"]')
        if (value !== title) throw new Error(`刷新后标题是「${value}」，期望「${title}」`)
      })
      await s.step('删除并确认', async () => {
        await s.clickCss('[data-testid="note-delete"]', '删除笔记')
        await s.clickCss('[data-testid="note-confirm"]', '确认删除')
        await s.expectNoText(title, 5000)
      })
    },
    async cleanup(s) {
      const title = s.safeName('笔记')
      await s.goto('/#/notes')
      const exists = await s.page.evaluate((needle) => document.body.innerText.includes(needle), title)
      if (!exists) return
      await s.click(title)
      await s.waitFor('[data-testid="note-delete"]')
      await s.clickCss('[data-testid="note-delete"]', '清理：删除笔记')
      await s.clickCss('[data-testid="note-confirm"]', '清理：确认删除')
    },
  },
]
