import { expect, test, type Page } from '@playwright/test'

import { clickNav, signIn } from './session'

/** 建一个唯一命名的知识库，返回名字。进入的是库列表页。 */
async function createBook(page: Page): Promise<string> {
  await clickNav(page, 'learner-nav-notes')
  await expect(page).toHaveURL(/#\/notes$/)
  const name = `e2e 知识库 ${Date.now()}`
  await page.getByRole('link', { name: '新建知识库' }).click()
  await expect(page).toHaveURL(/#\/notes\/new-book$/)
  await page.getByLabel('知识库名称').fill(name)
  await page.getByLabel('知识库描述').fill('e2e 自动化创建')
  await page.getByRole('button', { name: '创建知识库' }).click()
  await expect(page).toHaveURL(/#\/notes\/\d+$/)
  await page.goto('/#/notes')
  await expect(page.getByRole('link', { name })).toBeVisible()
  return name
}

/** 进入知识库并在根目录建一篇文档，返回标题。 */
async function createDoc(page: Page, book: string): Promise<string> {
  await page.getByRole('link', { name: book }).click()
  await expect(page).toHaveURL(/#\/notes\/\d+$/)
  await page.getByRole('button', { name: '新建文档' }).click()
  const dialog = page.getByRole('dialog')
  const title = `e2e 文档 ${Date.now()}`
  await dialog.getByLabel('文档标题').fill(title)
  await dialog.getByRole('button', { name: '创建' }).click()
  await expect(page.getByTestId('doc-title')).toHaveValue(title)
  return title
}

/** 回到库列表删除指定知识库（级联删库内文档）。 */
async function deleteBook(page: Page, name: string): Promise<void> {
  await page.goto('/#/notes')
  const card = page.locator('.group', { hasText: name })
  await card.getByRole('button', { name: '删除知识库' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByRole('link', { name })).toHaveCount(0)
}

test('知识库：建库→建文档→编辑保存→大纲与检索', async ({ page }) => {
  await signIn(page)
  const book = await createBook(page)
  const title = await createDoc(page, book)

  // 编辑正文并显式保存
  await page.getByTestId('note-doc-body').fill('# 一级标题\n\n正文里有倒排索引这个词')
  await page.getByTestId('doc-save').click()
  await expect(page.getByTestId('doc-save')).toHaveText('已保存')

  // 大纲里出现标题
  await expect(page.getByRole('button', { name: '一级标题' })).toBeVisible()

  // 全文检索能命中刚写的内容
  await page.getByLabel('检索笔记').fill('倒排索引')
  await page.getByRole('button', { name: '搜索' }).click()
  await expect(page.locator('ul').filter({ hasText: title }).first()).toBeVisible()

  await deleteBook(page, book)
})

test('整库分享：链接免登录可浏览文档树', async ({ page, browser }) => {
  await signIn(page)
  const book = await createBook(page)
  const title = await createDoc(page, book)

  // 打开"分享整个知识库"对话框并生成链接
  await page.getByRole('button', { name: '分享', exact: true }).click()
  await page.getByRole('button', { name: '生成新的分享链接' }).click()
  const code = page.locator('code').first()
  await expect(code).toBeVisible()
  const link = (await code.innerText()).trim()
  expect(link).toContain('/#/share/')

  // 全新的匿名上下文打开分享链接
  const anonymous = await browser.newContext()
  const guest = await anonymous.newPage()
  await guest.goto(link)
  await expect(guest.getByTestId('share-title')).toHaveText(book)
  await expect(guest.getByTestId('share-book-tree')).toContainText(title)
  await anonymous.close()

  await page.keyboard.press('Escape')
  await deleteBook(page, book)
})
