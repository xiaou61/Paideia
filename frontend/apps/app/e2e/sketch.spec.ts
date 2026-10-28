import { expect, test } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 手绘图围栏块（WORK-051）。
 *
 * <p>内容来自 `db/devdata/V956__seed_sketch_block.sql`（条目 61）。**只在本地的种子库上跑**：
 * 部署实例里没有这篇。
 *
 * <p>这里验三件只有真浏览器才能验的事：SVG 真的画出来了、手写字体真的加载了、
 * 同一篇刷新两次线条不变（抖动 seed 来自内容而不是随机数）。
 */
const ENTRY = '/#/knowledge/entries/61'

test.beforeEach(() => {
  test.skip(REMOTE, '依赖 db/devdata 的 sketch 示例条目，部署实例里没有它')
})

test('手绘图渲染成内联 SVG：节点数与 JSON 一致，字体真的加载，刷新后线条不变', async ({ page }) => {
  await signIn(page)
  await page.goto(ENTRY)

  const block = page.getByTestId('sketch-block')
  await expect(block).toBeVisible()
  await expect(block.locator('[data-node]')).toHaveCount(3)

  const family = await block
    .locator('svg')
    .evaluate((element) => getComputedStyle(element).fontFamily.toLowerCase())
  expect(family, '标签必须是手写体，否则图就退回成普通流程图').toContain('paideia sketch')

  const loaded = await page.evaluate(() => document.fonts.check('20px "Paideia Sketch"'))
  expect(loaded, '字体没有真的加载——子集没打进产物，或 @font-face 的路径错了').toBe(true)

  const paths = () => block.locator('path').evaluateAll((list) => list.map((path) => path.getAttribute('d')))
  const first = await paths()
  expect(first.length).toBeGreaterThan(3)

  await page.reload()
  await expect(block.locator('[data-node]')).toHaveCount(3)
  expect(await paths(), '刷新后线条变了，说明抖动 seed 用了随机数').toEqual(first)
})
