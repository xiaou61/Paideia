import { expect, test } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 身份化与模拟面试（WORK-010 T-03 / T-09 / T-12 / AC-002）。
 *
 * <p>只验**界面上真能走通的那部分**：设置页写读回、导航按身份重排、面试页能发起会话。
 *
 * <p><b>不断言"回复内容好不好"</b>：那取决于模型与积分（种子账号余额为 0 时这一轮会被积分闸
 * 拦下，界面显示"积分不足"）。这里验的是"入口在、请求发出去了、状态说清楚了"，
 * 不把上游质量当成验收对象。
 */
test('身份与目标：能改画像并读回，且明写"不影响你能做什么"', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/profile')

  // 这句话是这一页最要紧的文案：身份不是第二套权限。
  // 用更长的前缀定位：登录方式那一段也有"不影响你能做什么"，短词会同时命中两处（strict mode）。
  await expect(page.getByText('身份只影响推荐顺序与助手的形态')).toBeVisible()

  // 身份选择是弹窗式卡片：一期只有求职者可点，其余在弹窗里标「准备中」
  await page.getByTestId('profile-identity').click()
  await expect(page.getByTestId('identity-option-postgrad')).toBeDisabled()
  await page.getByTestId('identity-option-job_seeker').click()

  const role = `后端开发-${String(Date.now())}`
  await page.getByTestId('profile-target-role').fill(role)
  await page.getByTestId('profile-save').click()
  await expect(page.getByTestId('profile-saved')).toBeVisible()

  // 刷新后仍在：说明真的落库了，不是只存在表单状态里
  await page.reload()
  await expect(page.getByTestId('profile-target-role')).toHaveValue(role)
})

test('导航按身份重排：求职者有面试入口、且排在第一', async ({ page }) => {
  await signIn(page)
  // 先把身份确认为求职者（默认就是，但显式存一次避免被别的用例改过）
  await page.goto('/#/profile')
  await page.getByTestId('profile-identity').click()
  await page.getByTestId('identity-option-job_seeker').click()
  await page.getByTestId('profile-save').click()
  await expect(page.getByTestId('profile-saved')).toBeVisible()

  await page.goto('/#/knowledge')
  const nav = page.getByRole('navigation', { name: '主导航' })

  // 2026-10-26 起导航分成四个下拉组，所以"面试排第一"落在**组间顺序**上：
  // 身份的 preferredNav 里 interview 在首位 ⇒「求职」组是第一个触发器。
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
