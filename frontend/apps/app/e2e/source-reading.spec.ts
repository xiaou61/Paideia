import { expect, test, type Page } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 源码解读专题的端到端检查（WORK-014）。
 *
 * <p>内容来自 `db/devdata/V953__seed_source_reading.sql`（React 渲染入口那篇，条目 10）。
 * **只在本地的种子库上跑**：部署实例里没有这篇；远程一轮混进这种红，看起来就像界面坏了。
 *
 * <p>这里验的是"规则"而不是"具体文案"：切步改变高亮、预测未答不给解释、地图与走读互相驱动、
 * 练习判定、进度刷新后还在、问 AI 只填不发、阅读不请求 GitHub。
 */
const ENTRY = '/#/knowledge/entries/10'

async function openEntry(page: Page): Promise<void> {
  await signIn(page)
  await page.goto(ENTRY)
  await expect(page.getByTestId('code-tour')).toBeVisible()
}

test.beforeEach(() => {
  test.skip(REMOTE, '依赖 db/devdata 的源码解读样板条目，部署实例里没有它')
})

test('走读：切步同时改变高亮行与解释，预测未答不给解释', async ({ page }) => {
  await openEntry(page)

  const firstFocus = page.getByTestId('code-tour-focus-line').first()
  await expect(firstFocus).toBeVisible()
  await expect(page.getByTestId('code-tour-explain')).toContainText('RenderContext')

  // 第二步带预测：解释被挡住，直到作答
  await page.getByTestId('code-tour-next').click()
  await expect(page.getByTestId('code-tour-predict')).toBeVisible()
  await expect(page.getByTestId('code-tour-explain')).toHaveCount(0)

  await page.getByTestId('code-tour-predict-0').click()
  await expect(page.getByTestId('code-tour-explain')).toBeVisible()
  await expect(page.getByTestId('code-tour-explain')).toContainText('接着上次做')
})

test('地图与走读双向联动', async ({ page }) => {
  await openEntry(page)

  // 地图 → 走读：点「重置工作栈」节点应跳到第 2 步
  await page.getByTestId('code-map-node-fresh').click()
  await expect(page.getByTestId('code-tour')).toContainText('第 2 步')
  await expect(page.getByTestId('code-map-node-fresh')).toHaveAttribute('data-active', 'true')

  // 走读 → 地图：再点下一步，高亮应移到「按片工作」
  await page.getByTestId('code-tour-next').click()
  await expect(page.getByTestId('code-map-node-loop')).toHaveAttribute('data-active', 'true')
  await expect(page.getByTestId('code-map-node-fresh')).toHaveAttribute('data-active', 'false')
})

test('练习与测验：判定与得分都给出明确反馈', async ({ page }) => {
  await openEntry(page)

  // 排序题：初始顺序是打乱的，按答案调成正确顺序
  // 排序题：初始只差一处（第 1、2 行对调），上移一次即可
  const order = page.locator('[data-testid="challenge"]').first()
  await order.getByLabel('上移').nth(1).click()
  await order.getByTestId('challenge-check').click()
  await expect(order.getByTestId('challenge-result')).toContainText('全对')

  // 找 bug：正确的行在种子数据里是第 4 行
  const bug = page.locator('[data-testid="challenge"]').nth(1)
  await bug.getByTestId('challenge-bug-line-4').click()
  await bug.getByTestId('challenge-check').click()
  await expect(bug.getByTestId('challenge-result')).toContainText('全对')

  // 小测（WORK-048 起在「测一测」面板里）：三题全对
  await page.getByTestId('practice-open').click()
  const dialog = page.getByTestId('practice-dialog')
  await expect(dialog).toBeVisible()
  for (const index of [0, 1]) {
    await dialog.getByTestId(`quiz-option-${index}-0`).click()
  }
  // 最后一题等接口真写完再往下走：紧接着要刷新，抢在写入之前会读到"没答过"
  const lastAttempt = page.waitForResponse(
    (response) =>
      response.url().includes('/api/v1/learning/attempts') && response.request().method() === 'POST',
  )
  await dialog.getByTestId('quiz-option-2-0').click()
  await lastAttempt
  await expect(dialog.getByTestId('quiz-score')).toContainText('答对 3 / 3')
  await page.getByTestId('practice-close').click()

  // 答过之后再进来要显示上次的成绩，而不是又回到"已答 0 / 3"
  await page.reload()
  await expect(page.getByTestId('practice-hint')).toContainText('上次答对 3 / 3')
  await page.getByTestId('practice-open').click()
  await expect(page.getByTestId('practice-dialog').getByTestId('quiz-score')).toContainText('答对 3 / 3')
  await page.getByTestId('practice-close').click()
})

test('进度：刷新后回到上次那一步，练习成绩保留', async ({ page }) => {
  await openEntry(page)

  await page.getByTestId('code-tour-next').click()
  await page.getByTestId('code-tour-predict-0').click()
  await page.getByTestId('code-tour-next').click()
  await expect(page.getByTestId('code-tour')).toContainText('第 3 步')

  await page.reload()
  await expect(page.getByTestId('code-tour')).toContainText('第 3 步')
})

test('问 AI：只填进输入框、不发送', async ({ page }) => {
  await openEntry(page)

  let sent = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/api\/v1\/ai\/conversations\/\d+\/messages/.test(request.url())) {
      sent += 1
    }
  })

  await page.getByTestId('block-ask').first().click()
  const input = page.getByTestId('conversation-input')
  await expect(input).toBeVisible()
  await expect(input).toHaveValue(/第 1 步/)
  await expect(input).toHaveValue(/ReactFiberWorkLoop\.js:2794/)
  // 给潜在的在飞请求一点时间；没有点击发送就不该有
  await page.waitForTimeout(800)
  expect(sent).toBe(0)
})

test('阅读不请求 GitHub', async ({ page }) => {
  const github: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('github.com') || request.url().includes('githubusercontent.com')) {
      github.push(request.url())
    }
  })

  await openEntry(page)
  await page.waitForLoadState('networkidle')
  expect(github).toEqual([])
  // 出处仍然可见，只是它是一条普通外链
  await expect(page.getByTestId('code-tour')).toContainText('facebook/react@d75b0697')
  await expect(page.getByTestId('code-tour')).toContainText('MIT')
})

// 首页入口的用例已随 WORK-057 删除：源码解读分类下线，首页那张卡也一起删了。
// 块本身的覆盖仍在下面这些用例里（样板条目由 db/devdata 播种，只存在于测试库）。

test('窄屏 360px：不横向溢出，块按单列堆叠', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 720 })
  await openEntry(page)

  // 文档本身不能横向滚动；允许代码区自己滚
  const overflow = await page.evaluate(() => ({
    doc: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }))
  expect(overflow.doc).toBeLessThanOrEqual(1)
  expect(overflow.body).toBeLessThanOrEqual(1)

  // 走读的代码与解释在窄屏下上下堆叠：两块左边界相同、纵向不重叠
  const code = page.getByTestId('code-tour').locator('div.overflow-x-auto').first()
  const explain = page.getByTestId('code-tour-explain')
  const codeBox = await code.boundingBox()
  const explainBox = await explain.boundingBox()
  expect(codeBox).not.toBeNull()
  expect(explainBox).not.toBeNull()
  expect(Math.abs((codeBox!.x) - (explainBox!.x))).toBeLessThan(24)
  expect(explainBox!.y).toBeGreaterThanOrEqual(codeBox!.y + codeBox!.height - 1)
})

test('语法高亮真的上了色（Shiki 到达后围栏代码有颜色 token）', async ({ page }) => {
  await openEntry(page)

  // 种子正文里有一段普通 ```javascript 围栏（`## 先看调用形状`）。
  // Shiki 是懒加载的：到达后每个 token 变成带 `--sdm-c` 自定义属性的 span
  // （**不是**内联 `color:`——那是 streamdown 的渲染约定）。
  await expect
    .poll(
      async () =>
        page.evaluate(() =>
          Array.from(document.querySelectorAll('pre span')).filter(
            (span) =>
              (span.getAttribute('style') ?? '').includes('--sdm-c') && (span.textContent ?? '').trim() !== '',
          ).length,
        ),
      { timeout: 20_000 },
    )
    .toBeGreaterThan(10)
})

/**
 * 沙箱未开启时（本地默认就是关闭的）：运行题只说"没启用"，**不渲染一个点了必然失败的按钮**。
 *
 * <p>这条同时钉住 AC-009 的前半：关闭开关后其他题型照常可用。
 */
test('沙箱未开启：运行题不显示按钮，并说明原因', async ({ page }) => {
  await openEntry(page)

  const runBlock = page.getByTestId('challenge').filter({ hasText: '看看「按片工作」到底打印了什么' })
  await expect(runBlock).toBeVisible()
  await expect(runBlock.getByTestId('challenge-run')).toHaveCount(0)
  await expect(runBlock).toContainText('没有启用「运行」')

  // 其它题型不受影响：小测仍在，只是搬到了正文末尾的入口卡后面
  await expect(page.getByTestId('practice-entry')).toBeVisible()
})
