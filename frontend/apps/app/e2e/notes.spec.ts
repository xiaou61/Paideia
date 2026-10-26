import { expect, test, type Page } from '@playwright/test'

import { clickNav, signIn } from './session'

/**
 * 我的笔记（AC-010 的界面一半）。
 *
 * <p>三条与实现同步的约定：
 * <ul>
 *   <li><b>新建走独立的一页</b>（`/notes/new`），建完回到列表页并自动选中那条。2026-10-15 用户
 *       要求"新建应该是弹窗或者一个页面，因为后面要在这上面加很多功能"——所以这里点的是
 *       `notes-create` → 在 `new-note-*` 上写内容 → `new-note-create`。</li>
 *   <li><b>删除/切走用应用自己的对话框</b>，不再接管 `window.confirm`。</li>
 *   <li><b>草稿状态必须说得清</b>：改过就有 `note-dirty`，保存后回到「与服务端一致」。</li>
 * </ul>
 *
 * <p>种子账号的笔记是持久的，所以每条用例自己建、自己删，并且标题带唯一后缀——
 * 否则重复跑会互相看见对方的残留。
 */

/** 走"新建笔记"独立页建一条，回来时列表页应当已经选中它。 */
async function createNote(page: Page, title: string, body: string): Promise<void> {
  await page.getByTestId('notes-create').click()
  await expect(page).toHaveURL(/#\/notes\/new$/)
  await page.getByTestId('new-note-title').fill(title)
  if (body !== '') {
    await page.getByTestId('new-note-body').fill(body)
  }
  await page.getByTestId('new-note-create').click()
  // 回到列表页，并且刚建的那条已经打开
  await expect(page).toHaveURL(/#\/notes$/)
  await expect(page.getByTestId('note-title')).toHaveValue(title)
}

test('新建走独立页：建档→改一次并保存→重载仍在→删除', async ({ page }) => {
  await signIn(page)
  // 入口在主导航里（"某个页面忘了加入口"是 2026-10-12 修过的真缺陷，这里顺带钉住）
  await clickNav(page, 'learner-nav-notes')
  await expect(page.getByTestId('notes-placeholder')).toBeVisible()

  const title = `e2e 笔记 ${Date.now()}`
  await createNote(page, title, '正文第一版')

  // 创建即保存，所以一回来就该是"与服务端一致"，正文也从服务端读到
  await expect(page.getByTestId('note-body')).toHaveValue('正文第一版')
  await expect(page.getByTestId('note-dirty')).toHaveCount(0)

  // 列表里出现标题与**摘要**——摘要是后端列表查询截的（LEFT(body,200)）
  await expect(page.getByTestId('notes-list')).toContainText(title)
  await expect(page.getByTestId('notes-list')).toContainText('正文第一版')

  // 改一次再保存："存了没有"必须说得清
  await page.getByTestId('note-body').fill('正文第二版')
  await expect(page.getByTestId('note-dirty')).toBeVisible()
  await page.getByTestId('note-save').click()
  await expect(page.getByTestId('note-dirty')).toHaveCount(0)
  await expect(page.getByTestId('note-save-state')).toHaveText('与服务端一致')

  // 重新加载：内容是从库里读回来的，不是内存里的草稿
  await page.reload()
  await clickNav(page, 'learner-nav-notes')
  await page.getByTestId('notes-list').getByText(title).click()
  await expect(page.getByTestId('note-body')).toHaveValue('正文第二版')

  // 删除要二次确认：现在是应用自己的对话框
  await page.getByTestId('note-delete').click()
  await expect(page.getByTestId('note-confirm-dialog')).toBeVisible()
  await page.getByTestId('note-confirm').click()
  // 断"这段文字从页面消失"，而不是断言列表容器里不含它：删掉唯一一条之后列表整个不渲染了
  // （变成空状态），对不存在的容器做 not.toContainText 会报 element not found
  await expect(page.getByText(title)).toHaveCount(0)
})

test('新建页能预览 markdown；详情里改没改说得清', async ({ page }) => {
  await signIn(page)
  await clickNav(page, 'learner-nav-notes')

  const title = `e2e 状态 ${Date.now()}`
  await page.getByTestId('notes-create').click()
  await expect(page).toHaveURL(/#\/notes\/new$/)
  await page.getByTestId('new-note-title').fill(title)
  // 用模板字符串写正文：里面的换行就是 markdown 的换行，不写成 \n 转义
  await page.getByTestId('new-note-body').fill(`## 小标题

一条带 **粗体** 的正文`)

  // 新建页的预览：markdown 真的被渲染成元素，而不是把源码当文字显示
  await page.getByTestId('new-note-tab-preview').click()
  await expect(page.getByTestId('new-note-preview').getByRole('heading', { name: '小标题' })).toBeVisible()
  await expect(page.getByTestId('new-note-preview')).toContainText('粗体')

  await page.getByTestId('new-note-tab-edit').click()
  await page.getByTestId('new-note-create').click()
  await expect(page.getByTestId('note-title')).toHaveValue(title)

  // 详情页里再改一次：徽章与底部文案都要跟着翻
  await page.getByTestId('note-body').fill('改过的正文')
  await expect(page.getByTestId('note-dirty')).toBeVisible()
  await expect(page.getByTestId('note-meta')).toContainText('尚未保存')
  await page.getByTestId('note-save').click()
  await expect(page.getByTestId('note-dirty')).toHaveCount(0)
  await expect(page.getByTestId('note-meta')).toContainText('最后编辑')

  // 详情页的预览也能渲染
  await page.getByTestId('note-tab-preview').click()
  await expect(page.getByTestId('note-preview')).toContainText('改过的正文')

  // 收尾
  await page.getByTestId('note-delete').click()
  await page.getByTestId('note-confirm').click()
  await expect(page.getByText(title)).toHaveCount(0)
})

test('新建时可以把笔记挂到一条知识库条目上', async ({ page }) => {
  await signIn(page)

  // 先从条目列表拿一条真实存在的条目当检索词（不写死标题，线上库与本地种子不一样）
  await page.goto('/#/knowledge/entries')
  const entryTitle = (await page.getByTestId('entry-list').locator('[data-testid=entry-title]').first().innerText()).trim()
  expect(entryTitle).not.toBe('')

  await clickNav(page, 'learner-nav-notes')
  const title = `e2e 挂靠 ${Date.now()}`
  await page.getByTestId('notes-create').click()
  await page.getByTestId('new-note-title').fill(title)

  await page.getByTestId('new-note-entry-search').fill(entryTitle)
  await expect(page.getByTestId('new-note-entry-results')).toBeVisible()
  await page.getByTestId('new-note-entry-results').locator('button').first().click()

  // 选中后变成一枚可取消的标签
  await expect(page.getByTestId('new-note-entry')).toBeVisible()
  await page.getByTestId('new-note-create').click()

  // 详情页的元信息行会写明它来自哪条条目
  await expect(page.getByTestId('note-entry')).toContainText('来自知识库条目')

  // 收尾
  await page.getByTestId('note-delete').click()
  await page.getByTestId('note-confirm').click()
  await expect(page.getByText(title)).toHaveCount(0)
})

test('分享：链接免登录可读，撤回后立刻失效', async ({ page, browser }) => {
  await signIn(page)
  await clickNav(page, 'learner-nav-notes')

  const title = `e2e 分享 ${Date.now()}`
  await createNote(page, title, '分享出去的正文')

  await page.getByTestId('note-share').click()
  // 打开只读分享对话框；**打开本身不产生副作用**，要显式点"生成"（原来点一下按钮就生成了）
  await page.getByTestId('note-share-create').click()

  const link = await page.getByTestId('note-share-link').inputValue()
  expect(link).toContain('/#/share/')

  // **全新的匿名上下文**：没有任何登录状态，这正是"免登录可读"要验的形态
  const anonymous = await browser.newContext()
  const guest = await anonymous.newPage()
  await guest.goto(link)
  await expect(guest.getByTestId('share-title')).toHaveText(title)
  await expect(guest.getByTestId('share-body')).toHaveText('分享出去的正文')
  // 举报入口在**未登录**时给的是登录引导（举报需要身份），而不是一个点了会 401 的按钮
  await expect(guest.getByTestId('share-report-need-login')).toBeVisible()

  // 撤回：同一个链接立刻不可读
  await page.getByTestId('note-revoke').click()
  // 撤回后对话框回到"还没有分享"的状态
  await expect(page.getByTestId('note-share-create')).toBeVisible()
  await guest.reload()
  await expect(guest.getByTestId('share-missing')).toBeVisible()

  await anonymous.close()
  await page.keyboard.press('Escape')
  // 收尾：删掉这条笔记（种子账号的数据是持久的）
  await page.getByTestId('note-delete').click()
  await page.getByTestId('note-confirm').click()
  await expect(page.getByText(title)).toHaveCount(0)
})

test('带着未保存的改动切走，会先问一句而不是静默丢掉', async ({ page }) => {
  await signIn(page)
  await clickNav(page, 'learner-nav-notes')

  const kept = `e2e 拦截A ${Date.now()}`
  await createNote(page, kept, '先保存下来的那条')
  const editing = `e2e 拦截B ${Date.now()}`
  await createNote(page, editing, '第二条')
  await expect(page.getByTestId('note-title')).toHaveValue(editing)

  // 在 B 上改而不保存
  await page.getByTestId('note-body').fill('还没保存的内容')
  await expect(page.getByTestId('note-dirty')).toBeVisible()

  // 点回 A：必须弹确认
  await page.getByTestId('notes-list').getByText(kept).click()
  await expect(page.getByTestId('note-confirm-dialog')).toBeVisible()
  await page.getByRole('button', { name: '取消' }).click()
  // 取消之后仍在 B 上，改动还在
  await expect(page.getByTestId('note-title')).toHaveValue(editing)
  await expect(page.getByTestId('note-body')).toHaveValue('还没保存的内容')

  // 这次确认丢掉，切到 A
  await page.getByTestId('notes-list').getByText(kept).click()
  await page.getByTestId('note-confirm').click()
  await expect(page.getByTestId('note-title')).toHaveValue(kept)

  // 收尾：两条都删掉（种子账号的笔记是持久的，留着会污染下一轮）
  for (const target of [kept, editing]) {
    await page.getByTestId('notes-list').getByText(target).click()
    await page.getByTestId('note-delete').click()
    await page.getByTestId('note-confirm').click()
    await expect(page.getByText(target)).toHaveCount(0)
  }
})

test('新建页没写东西就能直接返回；写了东西才拦一下', async ({ page }) => {
  await signIn(page)
  await clickNav(page, 'learner-nav-notes')

  // 空着返回：不该多问一句
  await page.getByTestId('notes-create').click()
  await page.getByTestId('new-note-back').click()
  await expect(page).toHaveURL(/#\/notes$/)
  await expect(page.getByTestId('new-note-discard')).toHaveCount(0)

  // 写了内容再取消：先拦一下，且"继续写"要能回到编辑状态
  await page.getByTestId('notes-create').click()
  await page.getByTestId('new-note-title').fill(`e2e 丢弃 ${Date.now()}`)
  await page.getByTestId('new-note-cancel').click()
  await expect(page.getByTestId('new-note-discard')).toBeVisible()
  await expect(page).toHaveURL(/#\/notes\/new$/)
  await page.getByTestId('new-note-discard').getByRole('button', { name: '继续写' }).click()
  await expect(page.getByTestId('new-note-discard')).toHaveCount(0)

  // 这次真的丢掉并返回，且**没有创建任何笔记**（列表里不该多出「未命名笔记」）
  const before = await page.getByTestId('new-note-title').inputValue()
  expect(before.startsWith('e2e 丢弃')).toBe(true)
  await page.getByTestId('new-note-cancel').click()
  await page.getByTestId('new-note-discard-confirm').click()
  await expect(page).toHaveURL(/#\/notes$/)
  /**
   * 断言落在**整页**而不是 `notes-list`。
   *
   * 原先断言的是 `getByTestId('notes-list')`，但那个容器**列表为空时根本不存在**——本文件每个
   * 笔记用例都在收尾时删掉自己建的笔记，dev 种子又不造笔记，所以走到这里列表按设计就是空的，
   * 于是这条断言报的是"元素找不到"，而不是它要验的东西。**它过去能过，靠的是上一次运行残留的
   * 笔记**（2026-10-16 一次集成测试跑了 `DELETE FROM notes` 把库清干净，它立刻红了）。
   *
   * 要验的是"那条草稿一个字都没落库"——整页都不该出现它，这样空列表与非空列表都对。
   */
  await expect(page.locator('body')).not.toContainText(before)
})

test('跟着助手的回答走：回答上带"存进笔记"入口（本环境没凭据，只验入口的挂载规则）', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge')
  await page.getByTestId('assistant-launcher').click()
  await page.getByTestId('assistant-tab-chat').click()

  // 还没有任何回答：不该出现存笔记入口（空正文与提示都不该被存成笔记）
  await expect(page.getByTestId('conversation-save-to-note')).toHaveCount(0)
  // 聊天输入照常在：面板本身是通的
  await expect(page.getByTestId('conversation-input')).toBeVisible()
})
