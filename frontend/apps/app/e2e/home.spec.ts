import { expect, test } from '@playwright/test'

import { LEARNER } from './env'
import { clickNav, signIn } from './session'

/**
 * 学习者端的端到端检查。
 *
 * <p>**登录成了所有断言的入口**：整个学习者端要求登录，所以每个用例都以"登录种子账号"开场。
 * 原来那条"受保护接口在浏览器里确实被保护"的断言换了个形式：现在浏览器里已经带着令牌，
 * 因此用**不带令牌的直连请求**来证明接口仍然受保护——那才是这条断言真正要说的事。
 *
 * <p>种子账号来自 db/devdata（只有测试会加载它），口令是固定的测试值。
 */

// 这条要的是"没有任何会话"的上下文：全局 storageState 会让它一开局就是已登录
test.describe('未登录', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('未登录访问学习者端会被导向登录页，登录后进入首页', async ({ page }) => {
  await page.goto('/')

  // 未登录时看到的是登录表单，而不是任何内容（站内导航只在登录后存在）
  await expect(page.getByTestId('login-form')).toBeVisible()
  await expect(page.getByTestId('learner-nav-knowledge')).toHaveCount(0)

  await signIn(page)

  // 登录后能看到原本要去的首页，并显示当前用户
  await expect(page.getByTestId('current-user')).toHaveText(LEARNER.identifier)
  })
})

/**
 * 首页原来那一段「运行状态」（平台类型 / 健康检查原始 JSON / 当前身份）**已按用户要求删掉**
 * （2026-10-15：诊断信息的读者是开发者，不是学习者）。这里保留它真正要验的两件事，但重新
 * 落在**学习者看得见的东西**上：① 宿主标识挂在 `<html data-platform>` 上，仍是测试与排障的锚点；
 * ② 首页上的数字确实来自接口——拿它与条目列表页报的总数对照，两边都来自后端才可能相等。
 */
test('首页的数字来自后端，宿主标识挂在 html 上', async ({ page }) => {
  await signIn(page)

  await expect(page.locator('html')).toHaveAttribute('data-platform', 'web')

  const stat = page.getByTestId('home-stat-entries')
  // 数据没回来时这里只有标签，所以先等数字出现再读
  await expect(stat).toContainText(/\d/)
  const shown = (await stat.innerText()).replace(/[^\d]/g, '')

  await page.goto('/#/knowledge/entries')
  await expect(page.getByTestId('entry-total')).toContainText(shown)
})

test('每个请求都带上后端返回的追踪标识', async ({ page }) => {
  const response = await page.request.get('/actuator/health')

  expect(response.status()).toBe(200)
  expect(response.headers()['x-trace-id']).toBeTruthy()
})

test('受保护接口在没有令牌时被拒，带着身份时读得到内容', async ({ page }) => {
  // 不带 Authorization：能拿到接口地址，不等于能读到内容
  const unauthorized = await page.request.get('/api/v1/me')
  expect(unauthorized.status()).toBe(401)

  // 带着令牌（已登录）时受保护的数据真的读得到：首页的「最近更新」来自 /api/v1/knowledge/entries
  await signIn(page)
  await expect(
    page.getByTestId('home-recent').locator('a[href*="/knowledge/entries/"]').first(),
  ).toBeVisible()
})

test('登出后回到登录页', async ({ page }) => {
  await signIn(page)
  await expect(page.getByTestId('current-user')).toBeVisible()

  // 登出收在账号菜单里（低频且危险的动作不再摆在页头）
  await page.getByTestId('account-menu-trigger').click()
  await page.getByTestId('logout').click()

  await expect(page.getByTestId('login-form')).toBeVisible()
})

/**
 * 缺陷回归（2026-10-12）：页头以前只有用户名与登出，**没有任何通往内容的入口**——
 * 知识库那一簇页面彼此连通，却没有人从首页指向它们，用户只能手输地址。
 *
 * <p>这个盲区是测试自己造成的：此前每条用例都用 `page.goto('/#/knowledge')` 直达，
 * 于是"页面能用"一直被验证，"用户走得到"从来没有。所以这条刻意**只点不输**地址。
 */
test('登录后从页头就能进入知识库，不必手输地址', async ({ page }) => {
  await signIn(page)

  await clickNav(page, 'learner-nav-knowledge')
  await expect(page).toHaveURL(/#\/knowledge$/)
  // 首屏是分类网格（二级结构的第一级），条目在点进去之后
  await expect(page.getByTestId('category-grid')).toBeVisible()
  await page.getByTestId('all-entries-card').click()
  await expect(page).toHaveURL(/#\/knowledge\/entries$/)
  await expect(page.getByTestId('entry-list')).toBeVisible()

  // 条目页的地址是 /knowledge/entries/:id，仍该算在"知识库"这一档里（前缀匹配）
  await page.getByTestId('entry-list').locator('a[data-testid^="entry-link-"]').first().click()
  await expect(page).toHaveURL(/#\/knowledge\/entries\/\d+$/)
  // 菜单收起时只有分组触发器可见，所以"我在哪一类"看触发器
  await expect(page.getByTestId('learner-nav-group-knowledge')).toHaveAttribute('aria-current', 'page')

  // 「我的自评」在「练习」组里。点完菜单会关闭，激活态同样看分组触发器。
  await clickNav(page, 'learner-nav-my-assessments')
  await expect(page).toHaveURL(/#\/my-assessments$/)
  await expect(page.getByTestId('learner-nav-group-practice')).toHaveAttribute('aria-current', 'page')
})

/**
 * 首页与"点导航就变样"的回归（2026-10-13，用户报的）：
 * ① 首页此前是骨架期诊断页，进去先看到的是 health 原始 JSON，**没有任何去处**；
 * ② `我的笔记` 页当时自己拼了 div+header，没走 AppShell ⇒ 点导航时整页宽度、页头、页脚全变。
 *
 * <p>这两条都只能"点着走"才验得到，所以这里从首页卡片一路点进笔记页，并断言
 * **三页共用同一套外壳**（页脚那句是 AppShell 独有的，三页都该有）。
 */
test('首页有去处；笔记页与知识库页共用同一套外壳', async ({ page }) => {
  await signIn(page)

  await expect(page.getByTestId('home-entries')).toBeVisible()
  await expect(page.getByTestId('home-entry-knowledge')).toBeVisible()
  const shellFooter = page.getByText('Paideia · 千人千面的 AI 个性化学习平台')
  await expect(shellFooter).toBeVisible()

  // 从首页卡片进笔记页（不是手输地址）
  await page.getByTestId('home-entry-notes').click()
  await expect(page).toHaveURL(/#\/notes$/)
  await expect(page.getByTestId('notes-create')).toBeVisible()
  // 同一个外壳：页脚还在（自己拼 div 的那版没有它）
  await expect(shellFooter).toBeVisible()

  // 再从页头切到知识库，外壳不变
  await clickNav(page, 'learner-nav-knowledge')
  await expect(page.getByTestId('category-grid')).toBeVisible()
  await expect(shellFooter).toBeVisible()
})

/**
 * 「八股文专区」入口（WORK-025 REQ-004，2026-10-21 补）。
 *
 * <p>此前后端抽题、分类、条目全在跑，学习者却没有可发现的路进去——**入口缺失是验收级缺口**，
 * 而它一直没被发现，是因为 e2e 全靠直接跳 URL 掩盖了这一点。
 *
 * <p>断言分两段，第二段才是关键：卡片存在只说明渲染了，**点进去到达的是那个分类页**才说明
 * id 解析对了。分类 id 在生产是 20、全新库是 900，写死任何一个都会让另一侧指向空分类。
 */
test('首页有「八股文专区」入口，点进去到达该分类', async ({ page }) => {
  await signIn(page)

  const entrance = page.getByTestId('home-entry-essay')
  await expect(entrance).toBeVisible()
  await entrance.click()

  // 分类页（不是"全部条目"）：url 带 categories 段
  await expect(page).toHaveURL(/#\/knowledge\/categories\/\d+$/)
  await expect(page.getByText('八股文 · 面经真题')).toBeVisible()
})
