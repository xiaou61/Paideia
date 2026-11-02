export const meta = { module: 'admin-knowledge', side: 'admin' }

const ADMIN = '/admin'

export const cases = [
  {
    id: 'admin-knowledge-entries',
    title: '知识库入口：/knowledge 落到条目管理',
    write: false,
    async run(s) {
      await s.step('打开知识库入口', async () => {
        await s.goto(`${ADMIN}/#/knowledge`)
        await s.expectUrl('/admin/#/knowledge/entries')
        await s.expectText('条目管理')
      })
    },
  },
  {
    id: 'admin-knowledge-categories',
    title: '分类管理：页面可开且侧栏可见',
    write: false,
    async run(s) {
      await s.step('打开分类管理', async () => {
        await s.goto(`${ADMIN}/#/knowledge/categories`)
        await s.expectText('分类管理')
      })
      await s.step('侧栏有知识库分组', async () => {
        await s.expectText('知识库')
      })
    },
  },
  {
    id: 'admin-knowledge-concepts',
    title: '概念页：页面可开且标题正确',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/knowledge/concepts`)
      await s.step('确认概念页标题', async () => {
        await s.expectText('概念')
      })
    },
  },
  {
    id: 'admin-knowledge-category-write',
    title: '分类写入：新建 → 列表可见 → 删除',
    write: true,
    async run(s) {
      const name = s.safeName('分类')
      const slug = `e2e-click-${s.runId}`
      await s.step('新建分类', async () => {
        await s.goto(`${ADMIN}/#/knowledge/categories`)
        await s.clickCss('[data-testid="admin-category-new"]', '新建分类')
        await s.waitFor('[data-testid="admin-category-editor"]')
        await s.fill('[data-testid="admin-category-name"]', name)
        await s.fill('[data-testid="admin-category-slug"]', slug)
        await s.clickCss('[data-testid="admin-category-create"]', '创建分类')
        await s.expectText(name, 10_000)
      })
      await s.step('删除分类', async () => {
        const clicked = await s.page.evaluate((needle) => {
          const row = [...document.querySelectorAll('tr')].find((item) => item.innerText.includes(needle))
          const button = row?.querySelector('[data-testid^="admin-category-delete-"]')
          if (!button) return false
          button.click()
          return true
        }, name)
        if (!clicked) throw new Error(`找不到分类「${name}」的删除按钮`)
        await s.waitFor('[data-testid="admin-delete-dialog"]')
        await s.clickCss('[data-testid="admin-delete-confirm"]', '确认删除分类')
        await s.expectNoText(name, 5000)
      })
    },
    async cleanup(s) {
      const name = s.safeName('分类')
      await s.goto(`${ADMIN}/#/knowledge/categories`)
      const exists = await s.page.evaluate((needle) => document.body.innerText.includes(needle), name)
      if (!exists) return
      const clicked = await s.page.evaluate((needle) => {
        const row = [...document.querySelectorAll('tr')].find((item) => item.innerText.includes(needle))
        const button = row?.querySelector('[data-testid^="admin-category-delete-"]')
        if (!button) return false
        button.click()
        return true
      }, name)
      if (!clicked) return
      await s.waitFor('[data-testid="admin-delete-dialog"]')
      await s.clickCss('[data-testid="admin-delete-confirm"]', '清理：确认删除分类')
    },
  },
]
