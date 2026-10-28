import { expect, test, type Page } from '@playwright/test'

import { signIn } from './session'

/**
 * 移动端布局与形态（WORK-016 / AC-001、AC-002、AC-003、AC-005、AC-009）。
 *
 * <p>这些用例跑在 `mobile` project 下（Pixel 7，412×915 的 CSS 视口），只覆盖"形态换了"
 * 这件事——桌面语义的正确性由既有的 44 条用例负责，不在这里重复。
 *
 * <p>判据统一是"用户在手机上能不能完成这一页的主任务"，不是"看起来像不像 App"。
 */

/** 页面的横向溢出量。局部 `overflow-x-auto` 容器（代码块、表格）不算。 */
async function overflowX(page: Page): Promise<number> {
  return page.evaluate(() => {
    const doc = document.documentElement
    return doc.scrollWidth - doc.clientWidth
  })
}

/**
 * 等到横向溢出消失。
 *
 * <p><b>为什么用轮询而不是直接断言</b>：Chromium 的移动模拟在**导航瞬间**会有两三帧
 * `innerWidth` 大于 `clientWidth`（实测 471 vs 412），此刻所有 `position: fixed` 元素
 * 被按 471 定位，于是 `documentElement.scrollWidth` 短暂多出 59px。那一帧里没有任何
 * **正常流**元素越界——所以它是模拟器的布局视口重算，不是 CSS 溢出。
 *
 * <p>真实溢出会一直存在，`expect.poll` 到超时仍然失败；只有瞬时的模拟伪影会被放过。
 */
async function expectNoOverflow(page: Page, label: string): Promise<void> {
  await expect.poll(() => overflowX(page), { timeout: 5000, intervals: [100, 200, 400] }).toBeLessThanOrEqual(1)
  expect(await overflowX(page), `${label} 仍有横向溢出`).toBeLessThanOrEqual(1)
}

/** 命中的短边（44px 是 iOS HIG 与 Material 共同的下限）。 */
async function shortSide(page: Page, testId: string): Promise<number> {
  const box = await page.getByTestId(testId).first().boundingBox()
  expect(box, `${testId} 应该有布局盒`).not.toBeNull()
  return Math.min(box!.width, box!.height)
}

/**
 * 11 条路由的冒烟：每一页都要能打开且不出现横向溢出。
 *
 * 分享页需要真实存在的分享 id，这里退化成"打不开也要是正常错误态而不是撑破布局"，
 * 所以用任意 id 也能验布局。
 */
const ROUTES = [
  { path: '/#/', name: '首页' },
  { path: '/#/knowledge', name: '知识分类' },
  { path: '/#/knowledge/entries', name: '条目列表' },
  { path: '/#/notes', name: '我的笔记' },
  { path: '/#/notes/new', name: '新建笔记' },
  { path: '/#/my-assessments', name: '我的自评' },
  // 简历（WORK-018）：这三页在手机上也要能打开且不撑破布局。
  // 编辑页与提案页是三栏/两栏布局，窄屏必须自动堆叠——那正是最容易出横向溢出的地方。
  { path: '/#/resume', name: '我的简历' },
  { path: '/#/resume/create', name: '新建简历' },
  // 书籍详解（WORK-046）：书列表与章节目录在手机上也要能打开且不撑破布局。
  { path: '/#/books', name: '书籍详解' },
  { path: '/#/books/books-man-month', name: '书籍章节' },
  { path: '/#/login', name: '登录' },
  { path: '/#/register', name: '注册' },
  { path: '/#/share/00000000-0000-0000-0000-000000000000', name: '分享页' },
]

test.describe('移动端布局', () => {
  for (const route of ROUTES) {
    test(`${route.name} 无横向溢出`, async ({ page }) => {
      await page.goto(route.path)
      await expectNoOverflow(page, `${route.name} 在 ${page.viewportSize()?.width}px 下`)
    })
  }

  test('书籍章节正文在窄屏不横向溢出（含概念图与出处头）', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/books/books-man-month')
    const chapters = page.locator('[data-testid^="book-chapter-"]')
    await expect(chapters.first()).toBeVisible({ timeout: 15_000 })

    // 首条是总览；种子里它带一张 code-map 与一段引用块（出处头的正文形态）
    await chapters.first().click()
    await expect(page.getByTestId('markdown-body')).toBeVisible()
    await expect(page.getByTestId('code-map')).toBeVisible()

    await expectNoOverflow(page, '书籍章节正文')
  })

  test('条目详情无横向溢出，且长文有可展开的本页目录', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/knowledge/entries')
    // 列表是异步拉的：先等容器出现，再判断"有没有内容"，否则会在加载态就判定为空
    await expect(page.getByTestId('entry-list')).toBeVisible({ timeout: 15_000 })
    const firstEntry = page.locator('[data-testid^="entry-link-"]').first()
    await firstEntry.click()
    await expect(page.getByTestId('markdown-body')).toBeVisible()

    await expectNoOverflow(page, '条目详情')

    // 目录在窄屏不是消失，而是折叠（AC-003）
    const toc = page.getByTestId('entry-toc-collapsed')
    await expect(toc).toBeVisible()
    await toc.locator('summary').click()
    // EntryToc 少于两个标题时自己不渲染，所以这里只断言"折叠区可展开"
    await expect(toc).toHaveAttribute('open', '')
  })
})

test.describe('移动导航抽屉', () => {
  test('汉堡打开抽屉、三项导航与搜索都在、选中后关闭', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/')

    // 桌面主导航在窄屏不该占位
    await expect(page.getByTestId('learner-nav-knowledge')).toBeHidden()

    await page.getByTestId('learner-nav-toggle').click()
    const drawer = page.getByTestId('learner-nav-drawer')
    await expect(drawer).toBeVisible()

    await expect(page.getByTestId('learner-nav-knowledge-drawer')).toBeVisible()
    // 书籍详解（WORK-046）在手机上必须有入口：新加的导航项不该只在桌面出现
    await expect(page.getByTestId('learner-nav-books-drawer')).toBeVisible()
    await expect(page.getByTestId('learner-nav-notes-drawer')).toBeVisible()
    await expect(page.getByTestId('learner-nav-my-assessments-drawer')).toBeVisible()
    // 搜索框在手机上原来完全没有入口，抽屉里必须补回来
    await expect(page.getByTestId('header-search-drawer')).toBeVisible()

    await page.getByTestId('learner-nav-notes-drawer').click()
    await expect(page).toHaveURL(/#\/notes/)
    await expect(drawer).toHaveCount(0)
  })

  test('抽屉里的导航项命中区不小于 44px', async ({ page }) => {
    await signIn(page)
    await page.getByTestId('learner-nav-toggle').click()
    expect(await shortSide(page, 'learner-nav-knowledge-drawer')).toBeGreaterThanOrEqual(44)
  })
})

test.describe('助手底部抽屉', () => {
  test('窄屏是贴底抽屉，覆盖而不占位，可点遮罩关闭', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/knowledge')
    const before = await page.locator('main').boundingBox()

    await page.getByTestId('assistant-launcher').click()
    const shell = page.getByTestId('assistant-shell')
    await expect(shell).toBeVisible()
    await expect(shell).toHaveAttribute('data-narrow', 'true')

    // 覆盖不占位：正文列宽不因助手改变（WORK-011 的 AC-008 在窄屏同样成立）
    expect((await page.locator('main').boundingBox())?.width).toBe(before?.width)

    // 贴底、留出高度上限
    const box = await shell.boundingBox()
    const vh = page.viewportSize()!.height
    expect(box!.y + box!.height).toBeLessThanOrEqual(vh + 1)
    expect(box!.height).toBeLessThanOrEqual(vh * 0.86)

    // 遮罩点击关闭，且关闭后入口重新出现
    await page.getByTestId('assistant-backdrop').click({ position: { x: 5, y: 5 } })
    await expect(shell).toHaveCount(0)
    await expect(page.getByTestId('assistant-launcher')).toBeVisible()
  })

  test('Esc 也能关闭', async ({ page }) => {
    await signIn(page)
    await page.getByTestId('assistant-launcher').click()
    await expect(page.getByTestId('assistant-shell')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('assistant-shell')).toHaveCount(0)
  })
})

test.describe('触控目标', () => {
  test('默认按钮的主档命中区不小于 44px', async ({ page }) => {
    await signIn(page)
    await page.goto('/#/knowledge')
    // 分类卡片是可点的整块，是这一页最主要的目标
    await expect(page.getByTestId('category-grid')).toBeVisible({ timeout: 15_000 })
    const card = page.locator('[data-testid^="category-card-"]').first()
    const box = await card.boundingBox()
    expect(Math.min(box!.width, box!.height)).toBeGreaterThanOrEqual(44)
  })
})
