import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY } from './env'

/**
 * 运维复盘页（WORK-086 TASK-010）。
 *
 * <p>只证明「从侧栏进得去、空状态/列表说得清楚、状态筛选能点」；复盘的状态机
 * （DRAFT→APPROVED→ARCHIVED、编辑已确认的退回草稿、同事件只留一个 APPROVED）
 * 与知识召回由 `PostmortemServiceTest` / `PostmortemKnowledgeTest` 覆盖。
 *
 * <p>不在 e2e 里伪造一次诊断：起草复盘需要一条 DONE 的 `OpsDiagnosis`，
 * 假造它会绕过「复盘必须来自真实诊断」这条约束，测出来的东西没有意义。
 */
async function signIn(page: Page) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(ADMIN.identifier)
  await page.getByTestId('login-password').fill(ADMIN.password)
  await page.getByTestId('login-submit').click()
}

test('复盘页从侧栏进得去，空状态/列表有明确文案', async ({ page }) => {
  await signIn(page)
  await expect(page.getByTestId('admin-console')).toBeVisible()

  await page.getByRole('link', { name: '复盘' }).click()

  await expect(page.getByRole('heading', { name: '运维复盘' })).toBeVisible()
  // 空与有数据是两台不同分支，任一成立都算通过；文案必须明确，不能白屏。
  await expect(
    page.getByText('还没有复盘').or(page.getByTestId('postmortem-list')),
  ).toBeVisible()
})

test('状态筛选可切换，且不改变页面结构', async ({ page }) => {
  await signIn(page)
  await expect(page.getByTestId('admin-console')).toBeVisible()
  await page.getByRole('link', { name: '复盘' }).click()

  await page.getByTestId('postmortem-status-filter').click()
  await page.getByRole('option', { name: '已确认' }).click()

  // 筛选后仍是同一页：标题在、列表区（空状态或列表）在。
  await expect(page.getByRole('heading', { name: '运维复盘' })).toBeVisible()
  await expect(
    page.getByText('还没有复盘').or(page.getByTestId('postmortem-list')),
  ).toBeVisible()
})
