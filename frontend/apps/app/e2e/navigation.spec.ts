import { expect, test } from '@playwright/test'

import { signIn } from './session'

/**
 * 导航覆盖（AGENTS.md「加了页面就必须加入口」）。
 *
 * <p><b>这条测试的由来</b>：2026-10-22 用户说"线上内容怎么少了这么多"。查下来内容一条不少
 * （从零开始 54 篇、知识图谱、SQL 自由练习都在库里），但它们的页面**只出现在首页那排快捷卡**里——
 * 而首页要往下滚才看得到，导航栏一个都没有，于是"能直达 URL"等于"用户找不到"。
 *
 * <p>现在钉死：这几个主干功能必须出现在**导航栏**（窄屏是抽屉，同一份 NAV）。
 * 新增功能时在这里加一条——这比事后发现"上线了但没人能找到"便宜得多。
 */
/** 页头直接横排的主干入口。 */
const PRIMARY_NAV = [
  { testId: 'learner-nav-learn', label: '从零开始' },
  { testId: 'learner-nav-digest', label: '每日速递' },
  { testId: 'learner-nav-knowledge', label: '知识库' },
  { testId: 'learner-nav-notes', label: '我的笔记' },
  { testId: 'learner-nav-interview', label: '模拟面试' },
]

/**
 * 「更多」菜单里的入口。**它们同样算"有入口"**：AGENTS.md 那条规则要的是"用户能找到"，
 * 不是"必须横排"。10 项硬塞进横排实测会把每个标签挤成竖排断字（2026-10-22 实测）。
 */
const MORE_NAV = [
  // 复习（WORK-034）。它也在「更多」里——清单要跟着 NAV 走，加了页面就得加一条，
  // 否则"入口存在"这件事没有测试盯着。
  { testId: 'learner-nav-review', label: '复习' },
  { testId: 'learner-nav-graph', label: '知识图谱' },
  { testId: 'learner-nav-sql-practice', label: 'SQL 练习' },
  { testId: 'learner-nav-camp', label: '训练营' },
  { testId: 'learner-nav-my-assessments', label: '我的自评' },
  { testId: 'learner-nav-resume', label: '我的简历' },
]

test.describe('导航覆盖', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page)
  })

  test('主干入口在页头横排，且文字不被挤断', async ({ page }) => {
    await page.goto('/#/')
    for (const item of PRIMARY_NAV) {
      const link = page.getByTestId(item.testId)
      await expect(link, `导航里缺少「${item.label}」`).toBeVisible()
      await expect(link).toHaveText(item.label)
    }
  })

  test('次要入口都在「更多」菜单里，且一项不缺', async ({ page }) => {
    await page.goto('/#/')
    await page.getByTestId('learner-nav-more').click()
    for (const item of MORE_NAV) {
      await expect(page.getByTestId(item.testId), `「更多」里缺少「${item.label}」`).toBeVisible()
    }
  })

  /**
   * 「从零开始」入口有效（不是"必须有课程数据"）。
   *
   * <p>**原来断的是 `learn-tracks` 可见**，而它只在库里有学习轨道时才渲染——
   * `paideia_test` 是共享测试库，有没有课程取决于谁在里面跑过什么。于是这条用例会
   * 因为**与产品无关的数据状态**变红，而红的人会以为入口坏了。
   *
   * <p>改成断"落到了正确的页、且页面给了确定的结论"：有轨道就显示轨道，没有就显示空状态。
   * 两者都能证明入口是通的，而两者都不依赖库里恰好有什么。
   *
   * <p>WORK-038 之后轨道分两栏，`learn-tracks` 不再是单一容器；理论栏在两条线都在时也会渲染，
   * 所以拿它当锚点同样稳定。
   */
  test('「从零开始」点进去落到学习页，且有轨道或明确空状态', async ({ page }) => {
    await page.goto('/#/')
    await page.getByTestId('learner-nav-learn').click()
    await expect(page).toHaveURL(/#\/learn/)

    const tracks = page.getByTestId('learn-theory')
    const empty = page.getByTestId('learn-empty')
    // 加载中（learn-state）不算结论，所以等到两者之一出现为止
    await expect(tracks.or(empty)).toBeVisible({ timeout: 15_000 })
  })

  test('「SQL 练习」从「更多」点进去能到达自由练习页', async ({ page }) => {
    await page.goto('/#/')
    await page.getByTestId('learner-nav-more').click()
    await page.getByTestId('learner-nav-sql-practice').click()
    await expect(page).toHaveURL(/#\/practice\/sql/)
  })

  test('账号菜单里能到身份与目标、MCP 接入', async ({ page }) => {
    await page.goto('/#/')
    await page.getByTestId('account-menu-trigger').click()
    await expect(page.getByTestId('account-profile')).toBeVisible()
    await expect(page.getByTestId('account-mcp')).toBeVisible()
  })

  test('宽屏 1280 下导航不溢出、不换行', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/#/')
    await expect(page.getByTestId('learner-nav-learn')).toBeVisible()

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow, '1280 宽下出现横向滚动：导航项太多把页头挤破了').toBeLessThanOrEqual(1)

    // 导航必须是单行：高度超过一行说明被挤换行了
    const navbox = await page.locator('nav[aria-label="主导航"]').boundingBox()
    expect(navbox?.height ?? 0, '导航被挤成多行了').toBeLessThan(48)

    // 每个标签都不许断字（换行）——这正是 10 项硬塞时的实际后果
    for (const item of PRIMARY_NAV) {
      const box = await page.getByTestId(item.testId).boundingBox()
      expect(box?.height ?? 99, `「${item.label}」被挤成竖排了`).toBeLessThan(40)
    }
  })
})
