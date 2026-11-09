import { expect, test } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 目标与阶段 + 导航顺序（原 WORK-010 T-03 / T-09 / T-12；2026-11-09 去掉身份选择）。
 *
 * <p>只验**界面上真能走通的那部分**：资料页写读回、导航组顺序固定、面试页能发起会话。
 *
 * <p><b>为什么还留着这个文件名</b>：它原来叫「身份与导航」，身份选择删掉之后剩下的正是
 * "资料能改能读回 + 导航顺序"这两件事；改名会连带改 AGENTS.md 里的 spec 对应表，
 * 收益不抵成本。文件名沿用，内容改成现在测的东西。
 *
 * <p><b>不断言"回复内容好不好"</b>：那取决于模型与积分（种子账号余额为 0 时这一轮会被积分闸
 * 拦下，界面显示"积分不足"）。这里验的是"入口在、请求发出去了、状态说清楚了"。
 */
test('目标与阶段：能改画像并读回，页面上不再有身份选择', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/profile')

  // 身份选择整块移除（2026-11-09 用户要求）：页面上不该再出现那个按钮或弹窗选项。
  await expect(page.getByTestId('profile-identity')).toHaveCount(0)
  await expect(page.getByTestId('identity-option-job_seeker')).toHaveCount(0)

  // 真正被消费的四个字段还在，且页头说清了它们各自影响什么。
  await expect(page.getByTestId('profile-target-role')).toBeVisible()
  await expect(page.getByTestId('profile-stage')).toBeVisible()
  await expect(page.getByTestId('profile-timeline')).toBeVisible()

  const role = `后端开发-${String(Date.now())}`
  await page.getByTestId('profile-target-role').fill(role)
  await page.getByTestId('profile-save').click()

  // 保存成功后**自动回上一页**（2026-11-06 用户要求：从首页第 1 步点进来，保存完要能接着做第 2 步）。
  // 这条用例是直接开 /profile 的，没有上一页，所以落回首页。
  await expect(page).toHaveURL(/\/#\/?$/, { timeout: 15_000 })

  // 刷新后仍在：说明真的落库了，不是只存在表单状态里
  await page.goto('/#/profile')
  await expect(page.getByTestId('profile-target-role')).toHaveValue(role)
})

test('导航组顺序固定：「求职」在第一位，且组内有面试入口', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge')
  const nav = page.getByRole('navigation', { name: '主导航' })

  // 2026-10-26 起导航分成四个下拉组；2026-11-09 起组间顺序是常量（面试与速递在前），
  // 不再随画像变化——这条断言把那个顺序钉住，改成别处时应先想清楚谁排前面。
  const firstGroup = nav.locator('button').first()
  await expect(firstGroup).toHaveAttribute('data-testid', 'learner-nav-group-job')
  await expect(firstGroup).toHaveText('求职')

  // 组里确实有面试入口，不是只挂了个组名
  await firstGroup.click()
  await expect(page.getByTestId('learner-nav-interview')).toBeVisible()
})

test('面试页：能发起会话，且无论余额够不够都给出确定状态（不静默失败）', async ({ page }) => {
  // 线上账号有积分，这一轮会**真调上游并扣费**；每跑一次 e2e 都花一次钱，所以线上跳过。
  // 本地不再假设"余额必为 0"（T-14 要求发分，发完这个假设就不成立了），改成断言确定状态。
  test.skip(REMOTE, '会真调上游并消耗线上账号积分；这条只在本地跑')
  // 有积分时会真调上游（思考 + 生成通常 10–40 秒），默认 30 秒的用例超时不够
  test.setTimeout(180_000)
  await signIn(page)
  await page.goto('/#/interview')

  // 隐私口径要明示：笔记默认不送上游
  await expect(page.getByText('你的笔记默认不参与')).toBeVisible()

  await page.getByTestId('interview-start').click()
  // WORK-077：开始前多了一层"会扣积分"的二次确认
  await page.getByTestId('interview-start-confirm').click()

  /**
   * **不断言余额为 0**：种子账号的余额是可变的——WORK-010 T-14 明确要求管理员给验收账号发积分，
   * 发完之后"必定被积分闸拦下"这条假设就失效了（实测会真拿到回答，于是原断言变红）。
   *
   * 断言的是真正的不变量：发起之后**要么有非空的回答，要么有可读的失败原因**，
   * 且绝不留下一条空的"面试官"气泡。候选人那行是乐观插入的（点下去立刻出现），
   * 所以必须等到本轮结束（输入框不再 disabled）再判，否则等于什么都没验。
   * 注意不能用「发送」按钮当信号——它在输入为空时本来就是 disabled。
   */
  const input = page.getByTestId('interview-input')
  await expect(input).toBeVisible()
  await expect(input).toBeEnabled({ timeout: 120_000 })

  const interviewerText = (await page.getByTestId('interview-line-interviewer').textContent())?.trim() ?? ''
  const failed = await page.getByText('没成功').isVisible()
  expect(interviewerText.length > 0 || failed).toBe(true)
  // 失败时不该留一条空的"面试官"气泡在那装样子
  if (failed) {
    expect(interviewerText.length).toBe(0)
  }
})
