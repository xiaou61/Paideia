import { expect, test, type Page } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 对象附件（WORK-058）的逐页验证。
 *
 * <p>补齐 AC-001（条目页选中一段）、AC-002（笔记含未保存改动）、AC-010（375px 与键盘删附件）。
 * 关卡那一页在 `learn.spec.ts`（AC-003）、`@` 选择器在 `assistant.spec.ts`（AC-005），这里不重复。
 *
 * <p>**复习页（AC-004）不在这里**：到期列表要靠真实判分产生复习项，`db/devdata` 没有这份数据，
 * 空库上点不到任何一道题。那条按"实现 + 同一 `Attach` 组件路径"记录为未覆盖。
 */

/** 在正文里选中一段文字（浏览器 API 选，而不是拖鼠标：拖拽坐标在 CI 上不稳）。 */
async function selectFirstParagraph(page: Page): Promise<string> {
  return page.evaluate(() => {
    const paragraph = [...document.querySelectorAll('article p')].find(
      (node) => (node.textContent ?? '').trim().length >= 4,
    )
    if (paragraph === undefined) {
      return ''
    }
    const range = document.createRange()
    range.selectNodeContents(paragraph)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    return (paragraph.textContent ?? '').trim()
  })
}

test.describe('对象附件', () => {
  test('AC-001 条目页选中一段递给助手：上下文条出现这一段的摘录', async ({ page }) => {
    await signIn(page)

    await page.goto('/#/knowledge/entries')
    const link = page.getByTestId('entry-list').locator('a[data-testid^="entry-link-"]').first()
    await link.click()
    await expect(page.getByTestId('entry-attach-selection')).toBeVisible()

    const selected = await selectFirstParagraph(page)
    expect(selected.length).toBeGreaterThanOrEqual(4)

    // 按钮 onMouseDown 会 preventDefault，所以按下时选区还在
    await page.getByTestId('entry-attach-selection').click()
    await expect(page.getByTestId('conversation-context')).toContainText('附着 1 个对象')
    await expect(page.getByTestId('conversation-attachments')).toContainText('选中片段')
  })

  test('AC-002 未保存的笔记改动也递得过去', async ({ page }) => {
    await signIn(page)

    await page.goto('/#/notes')
    await page.getByTestId('notes-create').click()
    await expect(page).toHaveURL(/#\/notes\/new$/)
    const title = `附件-${Date.now()}`
    await page.getByTestId('new-note-title').fill(title)
    await page.getByTestId('new-note-body').fill('正文第一版')
    await page.getByTestId('new-note-create').click()
    await expect(page.getByTestId('note-title')).toHaveValue(title)

    // 改完**不等自动保存**就递过去：附件要的是编辑器里的当前内容
    const unsaved = `${title}-未保存`
    await page.getByTestId('note-title').fill(unsaved)
    await page.getByRole('button', { name: '问助手' }).first().click()

    await expect(page.getByTestId('conversation-context')).toContainText('附着 1 个对象')
    await expect(page.getByTestId('conversation-attachments')).toContainText(unsaved)
  })

  test('AC-004 到期复习题带着题干递给助手', async ({ page }) => {
    // 到期项来自 learning_progress.review_due_at <= now，只有 db/devdata 的 V959 种了一条；
    // 线上没有这份种子，跳过而不是红。
    test.skip(REMOTE, '到期复习项只在本地种子里有')
    await signIn(page)

    await page.goto('/#/review')
    const attach = page.getByTestId('attach-entry-1')
    await attach.waitFor({ timeout: 15_000 })
    await attach.click()

    await expect(page.getByTestId('conversation-context')).toContainText('附着 1 个对象')
    await expect(page.getByTestId('conversation-attachments')).toContainText('复习：')
    // 摘录（第几档、哪天到期）只在提示词里，附件条只显示标签——由 serializeAttachments 的单测覆盖
  })

  test('AC-010 375px 下附件条不撑破页面，键盘能取下', async ({ page }) => {
    await signIn(page)
    await page.setViewportSize({ width: 375, height: 800 })

    await page.goto('/#/notes')
    await page.getByTestId('notes-create').click()
    const title = `窄屏-${Date.now()}`
    await page.getByTestId('new-note-title').fill(title)
    await page.getByTestId('new-note-body').fill('窄屏正文')
    await page.getByTestId('new-note-create').click()
    await expect(page.getByTestId('note-title')).toHaveValue(title)

    await page.getByRole('button', { name: '问助手' }).first().click()
    await expect(page.getByTestId('conversation-attachments')).toBeVisible()

    // 没有横向溢出；附件条自身也不超出视口（375 是 AC 里写死的宽度）
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow).toBeLessThanOrEqual(1)
    const bar = await page.getByTestId('conversation-attachments').boundingBox()
    expect(bar).not.toBeNull()
    expect(bar!.x + bar!.width).toBeLessThanOrEqual(376)

    // 键盘删附件：取下按钮是 <button>，Tab 得到、Enter 生效
    const detach = page.getByRole('button', { name: /^取下 / }).first()
    await detach.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('conversation-attachments')).toHaveCount(0)
  })
})