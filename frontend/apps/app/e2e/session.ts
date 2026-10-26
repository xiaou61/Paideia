import { expect, type Page } from '@playwright/test'

import { LEARNER } from './env'

/**
 * 登录，或复用已经登录的会话。
 *
 * <p>**线上有登录限流**：每条用例各登一次，跑一轮就会撞 429（实测 18 条用例把 e2e-learner 打到
 * 429）。所以正常路径是 `global-setup.ts` 登录一次、把 storageState 交给所有用例复用；
 * 这个函数只在真的没有会话时才走登录表单。
 */
export async function signIn(page: Page): Promise<void> {
  await page.goto('/')
  const identifier = page.getByTestId('login-identifier')
  /**
   * 登录态的锚点用**账号菜单**而不是主导航：WORK-016 之后 `<md` 主导航收进了抽屉，
   * `learner-nav-knowledge` 在手机上不可见——还拿它当锚点的话，移动 project 里每条用例
   * 都会以为"没登录"而去填表单。账号菜单在两种宽度下都在页头。
   */
  const signedIn = page.getByTestId('account-menu-trigger')

  // **等一个确定状态**，不能只问"有没有登录表单"：带会话进来时 App 启动有一段"加载中"，
  // 那一刻既没有表单也还没进站，问得太早会直接返回、后续断言全部落空（实测 16 条红）。
  await expect(identifier.or(signedIn)).toBeVisible({ timeout: 20_000 })
  if (await signedIn.isVisible()) {
    return
  }
  await identifier.fill(LEARNER.identifier)
  await page.getByTestId('login-password').fill(LEARNER.password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('login-submit')).toHaveCount(0)
  // 登录成功后 `LoginPage` 还会 `navigate('/')` 一次。等它落地再返回，否则调用方紧接着的
  // 深链 `goto` 会被这次跳转覆盖回首页——2026-10-17 整套跑里偶发落到首页、单跑不复现。
  await expect(page).toHaveURL(/#\/$/)
}

/**
 * 页头导航按用途分了组，点某一项之前要先展开它所在的那一组。
 *
 * <p>分组是 2026-10-26 用户要求的（"可选择的项太多，要分类可下拉"）。这份表是**测试侧的镜像**：
 * `LearnerHeader` 里改了分组，这里得跟着改，否则用例会停在"点不到那一项"上——这比静默点到
 * 别的页面好，但仍然是每次调导航都要付的成本，所以只在这一处维护。
 */
const NAV_GROUP_OF = {
  'learner-nav-learn': 'learner-nav-group-learn',
  'learner-nav-digest': 'learner-nav-group-learn',
  'learner-nav-review': 'learner-nav-group-learn',
  'learner-nav-camp': 'learner-nav-group-learn',
  'learner-nav-knowledge': 'learner-nav-group-knowledge',
  'learner-nav-graph': 'learner-nav-group-knowledge',
  'learner-nav-notes': 'learner-nav-group-knowledge',
  'learner-nav-sql-practice': 'learner-nav-group-practice',
  'learner-nav-my-assessments': 'learner-nav-group-practice',
  'learner-nav-interview': 'learner-nav-group-job',
  'learner-nav-resume': 'learner-nav-group-job',
} as const

/** 从页头导航点进某一项：先展开它那一组，再点它。 */
export async function clickNav(page: Page, testId: keyof typeof NAV_GROUP_OF): Promise<void> {
  await page.getByTestId(NAV_GROUP_OF[testId]).click()
  await page.getByTestId(testId).click()
}
