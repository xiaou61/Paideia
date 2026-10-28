import { expect, test } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 悬浮助手壳的端到端检查（AC-001 的界面一半，外加 WORK-009 的积分闸与积分面板）。
 *
 * <p>**不调真实模型**：本环境不该花谁的钱。所以这里只验"用户看得见、点得动、并且不会误触发
 * 调用"这三点——生成本身由后端的集成测试用桩覆盖。
 *
 * <p>2026-10-16 按 WORK-009 改写：凭据改由**平台**持有（用户不再配置 Key），于是
 * "没配凭据"这个状态对用户侧不再可达。取而代之要验的是两件新事：
 * ① **积分闸**——种子账号余额为 0，发送会在调模型**之前**被拒（所以这条不需要上游，也不产生费用）；
 * ② **积分面板**——能看到余额与空状态。
 */

test('壳常驻全站；不在条目页时说清"打开一条内容后可用"', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge')

  await expect(page.getByTestId('assistant-launcher')).toBeVisible()
  await page.getByTestId('assistant-launcher').click()

  await expect(page.getByTestId('assistant-shell')).toBeVisible()
  // 没有条目时默认落在「对话」（助手任意处可开），精简那页才说"打开一条内容后可用"
  await expect(page.getByTestId('conversation-input')).toBeVisible()
  await page.getByTestId('assistant-tab-summary').click()
  await expect(page.getByTestId('assistant-no-entry')).toHaveText('打开一条内容后可用。')
  // 凭据由平台持有：用户侧看到的是"服务就绪没有"，不是"我配没配"
  await expect(page.getByTestId('assistant-credential-state')).toHaveText('AI 服务已就绪')
})

test('记忆面板：能看到"它记住了什么"，并说明可逐条删除', async ({ page }) => {
  // 断言的是"种子账号还没有记忆"这条空状态；线上验收账号有真实记忆，空态不成立
  test.skip(REMOTE, '断言依赖种子账号没有记忆；部署实例上的账号有真实记忆')
  await signIn(page)
  await page.goto('/#/knowledge')
  await page.getByTestId('assistant-launcher').click()
  await page.getByTestId('assistant-tab-memory').click()

  await expect(page.getByTestId('memory-panel')).toBeVisible()
  await expect(page.getByTestId('memory-panel')).toContainText('可逐条删除')
  // 种子账号没有记忆：这里断的是"空状态说得清楚"，而不是"列表里有什么"
  await expect(page.getByTestId('memory-empty')).toHaveText('还没有任何记忆。')
  // 没有候选时「待确认」整块不渲染（WORK-050）：空标题比不渲染更像坏掉
  await expect(page.getByTestId('memory-candidates')).toHaveCount(0)
})

test('积分面板：能看到余额与空状态，并说明没有自动充值', async ({ page }) => {
  test.skip(REMOTE, '断言的是"种子账号余额 0、无消耗"，部署实例上的验收账号有积分与流水')
  await signIn(page)
  await page.goto('/#/knowledge')
  await page.getByTestId('assistant-launcher').click()
  await page.getByTestId('assistant-tab-credits').click()

  await expect(page.getByTestId('credits-panel')).toBeVisible()
  // 种子账号没有被发过积分
  await expect(page.getByTestId('credits-balance')).toHaveText('0')
  await expect(page.getByTestId('credits-empty')).toHaveText('还没有任何消耗。')
  await expect(page.getByTestId('credits-panel')).toContainText('没有自动充值')
})

test('积分不足时发送给出明确提示，而不是静默失败（也不会打到上游）', async ({ page }) => {
  // 线上跳过不只是"数据对不上"：部署实例的账号有积分，这一轮会**真的调上游并扣费**
  test.skip(REMOTE, '依赖"余额 0 才会被闸拦下"；线上账号有积分，跑下去会真调上游')
  // 这一轮会真的发 POST：余额为 0 时后端在调模型**之前**就拒了，
  // 所以它验证的是"接线通了且失败可见"，不会产生任何上游调用与费用。
  await signIn(page)
  await page.goto('/#/knowledge')
  await page.getByTestId('assistant-launcher').click()

  await expect(page.getByTestId('conversation-context')).toHaveText('没有条目上下文，可直接提问。')

  /*
   * 新开一段再发。
   *
   * <p>面板默认会**恢复服务端最近一段**会话（设计如此）。测试库里的这段无条目会话会跨轮次
   * 累积消息，于是 `conversation-message-user` 会匹配到历史里的多条，撞 Playwright 严格模式。
   * 这不是被测行为变了，而是本用例隐含假设"库里只有一条消息"。2026-10-18 连跑复现。
   */
  await page.getByTestId('conversation-new').click()
  await expect(page.getByTestId('conversation-message-user')).toHaveCount(0)

  await page.getByTestId('conversation-input').fill('解释一下导数')
  await page.getByTestId('conversation-send').click()

  await expect(page.getByTestId('conversation-error')).toContainText('积分不足')
  // 用户那句话要留在界面上：他确实说过，失败不该把它抹掉
  await expect(page.getByTestId('conversation-message-user')).toContainText('解释一下导数')
  // 没成的那一轮不留空气泡
  await expect(page.getByTestId('conversation-message-assistant')).toHaveCount(0)
})

test('打开条目后壳取到当前内容；不会偷偷发起生成，也不再出现"配置凭据"入口', async ({ page }) => {
  // 依赖 db/devdata 的种子条目 id=3；线上库里没有这条数据
  test.skip(REMOTE, '依赖种子条目 id=3，部署实例上没有')
  // 只盯 **POST**：壳打开时会发一个 GET 读已有结果，那是免费的读路径（命中缓存的意义就在这）。
  // 会花钱的只有 POST（生成），"打开条目不偷偷花钱"这条要验的正是没有 POST。
  const generateCalls: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/v1/ai/summaries')) {
      generateCalls.push(request.url())
    }
  })

  await signIn(page)
  // 条目列表在二级页；这条用例验的是助手取上下文，不是导航
  await page.goto('/#/knowledge/entries')
  await page.getByTestId('entry-link-3').click()

  await page.getByTestId('assistant-launcher').click()
  await expect(page.getByTestId('assistant-shell')).toBeVisible()
  // 壳里显示的是当前条目的标题（上下文由条目页提供）
  await expect(page.getByTestId('assistant-no-entry')).toHaveCount(0)

  // **WORK-009：用户侧不再有"配置凭据"这个入口**——Key 由平台持有，用户没有可填的地方
  await expect(page.getByTestId('assistant-open-credential')).toHaveCount(0)
  await expect(page.getByTestId('assistant-api-key')).toHaveCount(0)

  // 关键：整条流程一次生成都没有发出（打开条目不会偷偷花钱）
  expect(generateCalls).toEqual([])
})

test('WORK-011：面板可拖动、可缩放、可收起，且刷新后保持（不自动弹开）', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge')
  await page.getByTestId('assistant-launcher').click()

  const shell = page.getByTestId('assistant-shell')
  await expect(shell).toBeVisible()
  const before = (await shell.boundingBox()) ?? { x: 0, y: 0, width: 0, height: 0 }

  // ① 拖标题栏把它挪走——"随心所欲拖到任意位置"这条要能被自动化盯住
  const bar = page.getByTestId('assistant-drag')
  const barBox = await bar.boundingBox()
  expect(barBox).not.toBeNull()
  if (barBox !== null) {
    await page.mouse.move(barBox.x + 40, barBox.y + barBox.height / 2)
    await page.mouse.down()
    await page.mouse.move(barBox.x - 220, barBox.y + barBox.height / 2 - 60, { steps: 5 })
    await page.mouse.up()
  }
  const moved = (await shell.boundingBox()) ?? before
  expect(moved.x).toBeLessThan(before.x - 100)

  // ② 拖右下角握把往右 → 变宽（右下角握把往右/往下都是变大）
  const grip = page.getByTestId('assistant-resize')
  const gripBox = await grip.boundingBox()
  expect(gripBox).not.toBeNull()
  if (gripBox !== null) {
    await page.mouse.move(gripBox.x + gripBox.width / 2, gripBox.y + gripBox.height / 2)
    await page.mouse.down()
    await page.mouse.move(gripBox.x + 80, gripBox.y + gripBox.height / 2, { steps: 4 })
    await page.mouse.up()
  }
  const resized = (await shell.boundingBox()) ?? moved
  expect(resized.width).toBeGreaterThan(moved.width)

  // ③ 刷新后位置与尺寸都保持（存在本地）
  await page.reload()
  await expect(shell).toBeVisible()
  const reloaded = (await shell.boundingBox()) ?? resized
  expect(Math.abs(reloaded.width - resized.width)).toBeLessThan(2)
  expect(Math.abs(reloaded.x - resized.x)).toBeLessThan(2)

  // ④ 收起 → 刷新后仍是收起的：**不许每次导航都自动弹开**
  await page.getByTestId('assistant-collapse').click()
  await expect(page.getByTestId('assistant-launcher')).toBeVisible()
  await page.reload()
  await expect(page.getByTestId('assistant-launcher')).toBeVisible()
  await expect(page.getByTestId('assistant-shell')).toHaveCount(0)
})

test('WORK-011：快捷键开关面板', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge')
  await expect(page.getByTestId('assistant-launcher')).toBeVisible()

  await page.keyboard.press('Control+j')
  await expect(page.getByTestId('assistant-shell')).toBeVisible()
  await page.keyboard.press('Control+j')
  await expect(page.getByTestId('assistant-shell')).toHaveCount(0)
})

test('WORK-011：失败的一轮也会被记住——历史里选得到，新开之后仍在', async ({ page }) => {
  test.skip(REMOTE, '依赖"余额 0 才会被闸拦下"；线上账号有积分，这一轮会真调上游')
  await signIn(page)
  await page.goto('/#/knowledge')
  await page.getByTestId('assistant-launcher').click()

  await page.getByTestId('conversation-input').fill('这一轮会被积分闸拦住')
  await page.getByTestId('conversation-send').click()

  // 种子账号余额为 0：这一轮被拦下，但**会话本身已经建好了**
  await expect(page.getByTestId('conversation-error')).toContainText('积分不足')
  await expect(page.getByTestId('conversation-history')).toBeVisible()

  await page.getByTestId('conversation-history').click()
  await expect(page.getByTestId('conversation-sessions').locator('li').first()).toBeVisible()
  // 关掉列表再新开一段：界面清空，但旧会话仍在历史里
  await page.getByTestId('conversation-history').click()
  await page.getByTestId('conversation-new').click()
  await expect(page.getByTestId('conversation-history')).toBeVisible()
})

/**
 * 关掉面板再打开，会话要恢复（AC-004）。
 *
 * <p>2026-10-17：这条曾被登记为 `test.fixme`，因为当时被积分闸拦下的那一轮里用户消息会被
 * `prepare` 的事务回滚掉（库上只剩会话行、`ai_messages` 一行都没有），本环境造不出"有消息可恢复"
 * 的会话。回滚已修（`prepare` 不再包事务：先提交用户消息，再做积分预留），这条现在可以真跑。
 */
test('WORK-011：关掉面板再打开，上一次那一段还在（不是空白）', async ({ page }) => {
  test.skip(REMOTE, '依赖"余额 0 才会被闸拦下"；线上账号有积分，这一轮会真调上游')
  await signIn(page)
  await page.goto('/#/knowledge')
  await page.getByTestId('assistant-launcher').click()

  /**
   * 先显式新开一段。
   *
   * <p>没有本地会话 id 时，面板会**恢复服务端最近一段**（设计如此）；而测试库里的这段无条目
   * 会话会跨轮次累积消息，于是下面 `conversation-message-user` 会同时匹配到历史里的多条，
   * 撞上 Playwright 的严格模式。这不是被测行为变了，而是这条用例默认依赖"库里只有一条消息"。
   * 2026-10-18 实测：连跑两轮后这条必红（历史里已有 4 条用户消息）。
   */
  await page.getByTestId('conversation-new').click()
  await expect(page.getByTestId('conversation-message-user')).toHaveCount(0)

  await page.getByTestId('conversation-input').fill('关掉再打开也该还在')
  await page.getByTestId('conversation-send').click()
  // **先等服务端把这一轮收尾**（余额 0 → 积分不足）。气泡是乐观画的：一看到它就收起面板，
  // 会把 POST 掐在 prepare 之前，用户消息根本来不及落库（实测会话建出来了、消息 0 条）。
  await expect(page.getByTestId('conversation-error')).toContainText('积分不足')
  // 用户那句话要留在界面上——它确实说过，而且已经落库了
  await expect(page.getByTestId('conversation-message-user')).toContainText('关掉再打开也该还在')

  // 收起 = 面板卸载：界面上什么都不剩，只剩本地记着的那个会话 id
  await page.getByTestId('assistant-collapse').click()
  await expect(page.getByTestId('assistant-shell')).toHaveCount(0)

  // 重新打开要回到那一段，而不是一个空白新对话（AC-004）
  await page.getByTestId('assistant-launcher').click()
  await expect(page.getByTestId('conversation-message-user')).toContainText('关掉再打开也该还在')
})

test('WORK-011：窄视口下面板覆盖而不占位——正文列不被压窄，面板留在可视区', async ({ page }) => {
  await page.setViewportSize({ width: 700, height: 800 })
  await signIn(page)
  await page.goto('/#/knowledge')

  const main = page.locator('main')
  const before = await main.boundingBox()
  expect(before).not.toBeNull()

  await page.getByTestId('assistant-launcher').click()
  await expect(page.getByTestId('assistant-shell')).toBeVisible()

  // 正文列宽度**不因面板而变**：面板是 fixed 覆盖，不是占去布局的永久侧栏（AC-008）
  expect((await main.boundingBox())?.width).toBe(before?.width)

  // 面板整个留在可视区内：窄屏下不能被丢到屏幕外（夹回逻辑）
  const box = await page.getByTestId('assistant-shell').boundingBox()
  expect(box).not.toBeNull()
  if (box !== null) {
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.y).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(700)
    expect(box.y + box.height).toBeLessThanOrEqual(800)
  }
})

