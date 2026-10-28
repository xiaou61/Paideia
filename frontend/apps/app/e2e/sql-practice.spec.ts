import { expect, test } from '@playwright/test'
// 用真类型标注 mock：e2e 已纳入 tsc（tsconfig 的 include 含 e2e），
// 所以接口形状一改，这里会**编译报错**而不是等运行时崩页面。
// 2026-10-23 就是这么漏的：datasets 从 string[] 改成对象数组，手写 mock 不报错。
import type { SandboxStatus } from '@paideia/knowledge'

import { signIn } from './session'

/**
 * SQL 自由练习（WORK-032）。
 *
 * <p>只断三件不依赖沙箱具体状态的事：
 * <ol>
 *   <li>**入口能到**——从首页卡片点进去，落在 `/practice/sql`；</li>
 *   <li>**页面结构成立**——有编辑器与运行按钮，或者明确说明"暂不可用"；</li>
 *   <li>**不写学习进度**——这一条是 AC-011，必须用接口断，不能靠肉眼。练习是"不计分"的，
 *       一旦它偷偷写进度，用户会莫名其妙看到某个关卡被点亮。</li>
 * </ol>
 *
 * <p>**不断言真的跑出一条 SQL**：沙箱开关、镜像、每日配额都是环境状态，线上也不一定开着。
 * 那些由 `SandboxIntegrationTest` 与人工验证覆盖。这里只保证界面不会把用户带进死路。
 */
test.describe('SQL 自由练习', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page)
  })

  test('首页入口进得去，页面有编辑器或明确说明不可用', async ({ page }) => {
    await page.goto('/#/')
    const entry = page.getByTestId('home-entry-sql-practice')
    await expect(entry).toBeVisible()
    await entry.click()

    await expect(page).toHaveURL(/#\/practice\/sql/)
    await expect(page.getByRole('heading', { name: /写一段 SQL/ })).toBeVisible()

    // 两种合法形态：可用（有编辑器 + 运行按钮）或不可用（有明确提示）。
    // 不能两种都没有——那说明页面白屏了。
    const editor = page.getByTestId('sql-practice-editor')
    const unavailable = page.getByTestId('sql-practice-unavailable')
    await expect(editor.or(unavailable)).toBeVisible({ timeout: 15_000 })
  })

  /**
   * 执行计划入口（WORK-035 T-05）。
   *
   * <p>只断"按钮在、且它发的是 explain 请求"——真跑一次要起容器，线上沙箱不一定开着。
   * 用请求拦截确认 `explain: true` 真的发出去了：这一条比"按钮可见"有意义得多，
   * 因为按钮可见而请求没带 explain 时，用户会拿到一份数据结果却以为是执行计划。
   */
  test('「看执行计划」发出的是 explain 请求', async ({ page }) => {
    // 连能力探测一起 mock：本地/线上的沙箱开关是环境状态，不开时按钮根本不渲染，
    // 这条用例就会变成"跳过"——而跳过证明不了任何事。mock 掉才能真的验到请求体。
    await page.route('**/api/v1/sandbox/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          traceId: null,
          data: {
            enabled: true,
            ready: true,
            runtime: null,
            languages: ['sql'],
            dailyLimit: 50,
            usedToday: 0,
            sqlDailyLimit: 20,
            sqlUsedToday: 0,
            datasets: [{ key: 'orders-public', name: 'orders-public', source: 'builtin' }],
            reason: null,
          } satisfies SandboxStatus,
        }),
      })
    })

    const bodies: string[] = []
    await page.route('**/api/v1/sandbox/run', async (route) => {
      bodies.push(route.request().postData() ?? '')
      // 回一个最小的合法结果，避免真的起容器
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          traceId: null,
          data: {
            stdout: '',
            stderr: '',
            exitCode: 0,
            durationMs: 1,
            timedOut: false,
            outOfMemory: false,
            truncated: false,
            tables: [
              {
                statementIndex: 0,
                columns: ['EXPLAIN'],
                rows: [['-> Table scan on customers  (cost=0.55 rows=3)']],
              },
            ],
          },
        }),
      })
    })

    await page.goto('/#/practice/sql')
    await page.getByTestId('sql-practice-explain').click()

    await expect.poll(() => bodies.length).toBeGreaterThan(0)
    expect(bodies[0]).toContain('"explain":true')
    // 计划里的全表扫必须被标出来（节点级标记；汇总徽章写的是"有全表扫描"，故用精确匹配）
    await expect(page.getByTestId('explain-tree')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('全表扫', { exact: true })).toBeVisible()
  })

  /**
   * 表结构侧栏与 ER 图（WORK-035 T-04 / AC-003）。
   *
   * <p>结构来自构建期快照，所以这里也 mock 掉 schema 端点——真实快照的表名必须与断言一致
   * （`customers` / `orders` / `order_items` 是 `orders-public.sql` 里真实存在的三张表）。
   */
  test('表结构侧栏列出真实的表，可切到关系图', async ({ page }) => {
    await page.route('**/api/v1/sandbox/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          traceId: null,
          data: {
            enabled: true,
            ready: true,
            runtime: null,
            languages: ['sql'],
            dailyLimit: 50,
            usedToday: 0,
            sqlDailyLimit: 20,
            sqlUsedToday: 0,
            datasets: [{ key: 'orders-public', name: 'orders-public', source: 'builtin' }],
            reason: null,
          } satisfies SandboxStatus,
        }),
      })
    })
    await page.route('**/api/v1/sandbox/datasets/*/schema', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          traceId: null,
          data: JSON.stringify({
            tables: [
              {
                name: 'customers',
                columns: [
                  { name: 'id', type: 'int', nullable: false, primaryKey: true },
                  { name: 'name', type: 'varchar(20)', nullable: false, primaryKey: false },
                ],
              },
              { name: 'orders', columns: [{ name: 'id', type: 'int', nullable: false, primaryKey: true }] },
            ],
            relations: [],
          }),
        }),
      })
    })

    await page.goto('/#/practice/sql')
    const sidebar = page.getByTestId('schema-sidebar')
    await expect(sidebar).toBeVisible({ timeout: 10_000 })
    await expect(sidebar).toContainText('customers')
    await expect(sidebar).toContainText('orders')

    // 切到关系图：没有关联时必须给说明，而不是画一张空图
    await page.getByTestId('sql-toggle-er').click()
    await expect(page.getByTestId('er-diagram')).toBeVisible()
    await expect(page.getByText(/没有标注表间关联/)).toBeVisible()
  })

  test('练习不写学习进度（AC-011）', async ({ page }) => {
    /**
     * **用网络层证据，不用数据库条数**。
     *
     * <p>一开始写的是"查一次进度条数，练完再查一次，应当不变"。这条路走不通：
     * 这个应用是纯 Bearer + refresh 轮换，测试进程里拿不到访问令牌（`page.request` 401），
     * 而从页面里用 refresh 令牌换也会因为轮换后过期而 401（两种都实测过）。
     *
     * <p>更直接也更强的做法：**监视页面发出的请求**。学习进度只会通过
     * `PUT /api/v1/learning/entries/{id}` 写入，只要整个练习过程里一个都没发出去，
     * 就不可能有进度被写——这比"条数没变"更接近要证明的事。
     */
    const writes: string[] = []
    page.on('request', (request) => {
      const url = request.url()
      if (request.method() !== 'GET' && /\/api\/v1\/learning\//.test(url)) {
        writes.push(`${request.method()} ${url}`)
      }
    })

    await page.goto('/#/practice/sql')
    await expect(page.getByRole('heading', { name: /写一段 SQL/ })).toBeVisible()

    // 编辑一下并等渲染稳定：这期间任何进度写入都该被上面那个监听抓到
    //
    // 编辑器在 WORK-035 起是 CodeMirror（contenteditable），**不再支持 `fill()` 与
    // `toHaveValue()`**——那两个只对 textarea/input 有效。改用聚焦 + 键盘输入，
    // 断言编辑区里出现了输入的文字。
    const editor = page.getByTestId('sql-practice-editor')
    if (await editor.isVisible()) {
      await editor.click()
      await page.keyboard.press('ControlOrMeta+a')
      await page.keyboard.type('SELECT 1;')
      await expect(editor).toContainText('SELECT 1;')
    }
    await page.waitForTimeout(500)

    expect(writes, '自由练习不允许写 learning_progress').toEqual([])
  })
  /**
   * 回归（2026-10-28 用户实测报上来）：编辑器打一个字母就失焦。
   *
   * <p>根因不是编辑器本身，是调用方每次渲染都传一个新的 schema 对象，而编辑器的 effect
   * 依赖它——于是每输入一个字符就销毁重建一次。这条用例必须"连续输入"，只输入一个字符
   * 是抓不到这个 bug 的（第一个字符总是打得进去）。
   */
  test('能连续输入：每输一个字符不该丢焦点', async ({ page }) => {
    await page.goto('/#/practice/sql')
    const editor = page.getByTestId('sql-practice-editor')
    // 沙箱不可用（本机没有 Docker）时编辑器根本不渲染——显式跳过，别让套件在本地变红，
    // 也别用 `if 可见` 糊过去：那种写法会把"没测到"伪装成"不需要测"。
    const appeared = await editor
      .waitFor({ state: 'visible', timeout: 20_000 })
      .then(() => true)
      .catch(() => false)
    test.skip(!appeared, '沙箱不可用（没有 Docker），编辑器不渲染；在带 Docker 的环境里这条会真跑')
    await editor.click()
    await page.keyboard.type('SELECT 1')
    await expect(editor).toContainText('SELECT 1')
    // 焦点还在编辑区：丢了的话后面的字符会打到 body 上，而内容刚好停在第一个字母
    expect(await editor.evaluate((node) => node.contains(document.activeElement))).toBe(true)
  })
})
