import { expect, test, type Page } from '@playwright/test'

import { REMOTE } from './env'
import { signIn } from './session'

/**
 * 学习者端知识库的端到端检查。
 *
 * <p><b>断言"规则"而不是"某一篇的标题"</b>：同一批用例要在两种库上都能跑——本地是 `db/devdata`
 * 的种子，指向部署实例时是另一套真实内容。写死标题／编号的版本只在有那份种子的机器上绿，
 * 打线上会红成一片，而红的原因跟界面毫无关系（实测 5 条）。所以这里改成通用不变量：
 * 目录与正文标题数相等、相邻导航能往返、搜到的必须是列表里真有的那篇、不存在的编号不许白屏。
 * 真正离不开种子的那几条用 {@link REMOTE} 明确跳过。
 *
 * <p>地址用的是 Hash 路由（`/#/knowledge`）——桌面壳从自定义协议加载页面，路径路由在那
 * 种来源下会失效，所以整个应用用 Hash。
 */

/** 列表里第一条条目的链接。编号不写死：编号只在某一份种子里有意义。 */
function firstEntryLink(page: Page) {
  return page.getByTestId('entry-list').locator('a[data-testid^="entry-link-"]').first()
}

/**
 * 列表卡片里条目的标题。
 *
 * <p>**必须按 `entry-title` 取，不能取"卡片的第一行"**：卡片现在顶着一块封面色块
 * （里面是分类徽章），它在 DOM 里排在标题前面——按第一行取会读到"未分类"这三个字，
 * 三条用例因此一起红（2026-10-15 实测）。
 */
async function firstEntryTitle(page: Page): Promise<string> {
  const title = (await firstEntryLink(page).getByTestId('entry-title').innerText()).trim()
  expect(title).not.toBe('')
  return title
}

/**
 * 从标题里挑一个检索词。
 *
 * <p>不直接拿整条标题去搜：真实标题里带 `HTTP/1.1`、`C++` 这类符号，MySQL 全文索引把它们当
 * 分词边界，整串短语很可能匹配不上——那是分词规则，不是检索坏了。改取最长的一段中文
 * （标题基本都是中文），提高命中率；标题里没有中文时才退回整串。
 */
function searchTerm(title: string): string {
  const runs = title.match(/[\p{Script=Han}]+/gu) ?? []
  const longest = runs.reduce((best, run) => (run.length > best.length ? run : best), '')
  return longest.length >= 3 ? longest : title
}

/** 打开"全部条目"页。 */
async function openAllEntries(page: Page): Promise<void> {
  await page.goto('/#/knowledge/entries')
  // 终态只有"有列表"和"空结果"两种：entry-state 同时用于"加载中"与"加载失败"，**不能当终态等**。
  // 超时之后再把 entry-state 的文字带进报错——只报"元素不存在"的话，2026-10-16 那次偶发
  // （整套跑红、单跑绿）就没有任何线索可查。
  const list = page.getByTestId('entry-list')
  try {
    await expect(list.or(page.getByTestId('entry-empty'))).toBeVisible()
  } catch (cause) {
    const state = page.getByTestId('entry-state')
    const text = (await state.count()) === 0 ? '（没有任何状态被渲染出来）' : (await state.innerText()).trim()
    throw new Error(`条目页没有列表，实际状态：「${text}」`, { cause })
  }
}

test('未登录访问知识库被导向登录页', async ({ page }) => {
  // 全局 storageState 里带着 refresh 令牌，这条用例必须**真的从没有会话开始**：
  // 不清掉的话，首屏那一刻的登录门会让断言假通过（竞态，不是真的被拦住了）。
  await page.addInitScript(() => {
    try {
      window.localStorage.clear()
    } catch {
      // about:blank 这类不透明来源上访问 localStorage 会抛，忽略即可
    }
  })
  await page.goto('/#/knowledge')

  await expect(page.getByTestId('login-identifier')).toBeVisible()
  await expect(page.getByTestId('category-grid')).toHaveCount(0)
  // 等这一页彻底安静，确认没有残留会话随后把它带进知识库
  await page.waitForLoadState('networkidle')
  await expect(page.getByTestId('category-grid')).toHaveCount(0)
  await expect(page.getByTestId('login-identifier')).toBeVisible()
})

test('分类网格 → 分类页 → 条目列表 → 条目正文，一级级都能走通', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/knowledge')
  await expect(page.getByTestId('category-grid')).toBeVisible()

  // "全部条目"这一档
  await expect(page.getByTestId('all-entries-card')).toBeVisible()
  await page.getByTestId('all-entries-card').click()
  await expect(page).toHaveURL(/#\/knowledge\/entries$/)
  await expect(page.getByTestId('entry-list')).toBeVisible()

  // 回上一级、再从某个分类卡片进去（这是新加的那一跳）
  await page.getByTestId('back-to-categories').click()
  await expect(page.getByTestId('category-grid')).toBeVisible()
  await page.getByTestId('category-grid').locator('a').first().click()
  await expect(page).toHaveURL(/#\/knowledge\/categories\/\d+$/)
  await expect(page.getByTestId('back-to-categories')).toBeVisible()
  // 二级页必须给出明确状态：有列表，或明确的空态。**不能假定第一个分类里有条目**——
  // 测试库里真的存在空分类（集成测试建过又没删掉的），线上每条分类都非空，两种库都要能跑。
  await expect(page.getByTestId('entry-list').or(page.getByTestId('entry-empty'))).toBeVisible()

  // 进正文走"全部条目"这条路：它覆盖全库，不依赖某个分类是否为空
  await openAllEntries(page)
  const title = await firstEntryTitle(page)
  await firstEntryLink(page).click()
  await expect(page).toHaveURL(/#\/knowledge\/entries\/\d+$/)
  await expect(page.getByRole('heading', { level: 1 }).first()).toHaveText(title)

  const body = page.getByTestId('markdown-body')
  await expect(body).toBeVisible()
  // 把源码当纯文本显示时这里一个块级元素都不会有。**不要顺带断言"必有标题"**：
  // 纯散文条目是合法内容（本地种子里就有一条），标题渲染交给下面那条目录用例。
  //
  // 用**会自动等待的断言**而不是数一次：WORK-012 起渲染器是懒加载的，正文先以纯文本兜底出现
  // （`data-testid="markdown-loading"`），chunk 到了才换成排版后的结构。数一次会数到兜底那一帧。
  await expect(body.locator('p, ul, ol, pre, table, blockquote').first()).toBeVisible()
  await expect(body.locator('[data-testid="markdown-loading"]')).toHaveCount(0)
})

test('目录与正文标题一一对应，且点击不改地址栏', async ({ page }) => {
  await signIn(page)
  await openAllEntries(page)

  // 不假定"第一条恰好有两个以上标题"：往后找一条真有标题层次的，两个分支都要断言到
  const links = page.getByTestId('entry-list').locator('a[data-testid^="entry-link-"]')
  const testIds = (await links.evaluateAll((all) => all.map((link) => link.getAttribute('data-testid') ?? ''))).slice(0, 8)
  expect(testIds.length).toBeGreaterThan(0)

  let verified = false
  for (const testId of testIds) {
    await page.goto(`/#/knowledge/entries/${testId.replace('entry-link-', '')}`)
    await expect(page.getByTestId('markdown-body')).toBeVisible()
    /*
     * 等增量渲染收尾再数标题。
     *
     * <p>正文是逐块上屏的（WORK-012）：`markdown-body` 一可见就数，会数到只渲染了第一个
     * 标题的那一帧；而目录是**一次性**从 markdown 文本算出来的，那一刻它已经有两条了。
     * 于是 `count < 2` 成立、`entry-toc` 却存在，断言自相矛盾。2026-10-18 实测复现。
     */
    await expect(page.getByTestId('markdown-body').locator('[data-testid="markdown-loading"]')).toHaveCount(0)

    // 目录与正文由**同一份 markdown** 算出，所以两侧数量必须相等。这条同时验了
    // "标题被渲染成元素"与"目录取到了同一批标题"，且不依赖任何一篇的具体标题。
    //
    // **只数正文自己的标题**：交互块内部的 markdown（code-tour 的解释等）也会产出 h1..h4，
    // 但它们不是正文结构，目录里本来就不该有。2026-10-18 实测：源码解读那篇有 7 个这样的标题，
    // 不排除时这条会撞 17 vs 10。
    const count = await page.getByTestId('markdown-body').evaluate((root) =>
      Array.from(root.querySelectorAll('h1, h2, h3, h4')).filter(
        (node) => node.closest('[data-testid]') === root,
      ).length,
    )
    const toc = page.getByTestId('entry-toc')

    if (count < 2) {
      // 标题少于两个时目录刻意不出现（一个标题没有导航价值）
      await expect(toc).toHaveCount(0)
      continue
    }

    await expect(toc).toBeVisible()
    await expect(toc.locator('button')).toHaveCount(count)

    // Hash 路由下地址栏的 hash 就是路由；目录若用 href="#x" 会跳到空白页
    const hashBefore = new URL(page.url()).hash
    await toc.locator('button').first().click()
    expect(new URL(page.url()).hash).toBe(hashBefore)
    verified = true
    break
  }

  test.skip(!verified, '这个库里前几条的正文标题都少于两个，目录没有可验的样本')
})

test('同分类内的上一篇／下一篇能往返', async ({ page }) => {
  await signIn(page)
  await openAllEntries(page)

  // 相邻导航只在"同分类下有两篇及以上"时才有值。逐条试比"先挑一个条目多的分类"更稳：
  // 列表按更新时间排，某个分类里排第一的那篇未必是发布序里的第一篇（它的"下一篇"就可能是空）。
  const links = page.getByTestId('entry-list').locator('a[data-testid^="entry-link-"]')
  const testIds = (await links.evaluateAll((all) => all.map((link) => link.getAttribute('data-testid') ?? ''))).slice(0, 12)
  expect(testIds.length).toBeGreaterThan(0)

  let verified = false
  for (const testId of testIds) {
    await page.goto(`/#/knowledge/entries/${testId.replace('entry-link-', '')}`)
    // **先等页面进入确定状态再判**：`entry-next` 只在条目加载成功后才渲染，
    // 直接问"有没有链接"会在查询还没回来时一律得到"没有"，于是整条用例静默跳过
    // （实测就栽在这：本地明明有相邻两篇，却因为这一脚永远走到 skip）。
    await expect(page.getByTestId('entry-next')).toBeVisible()
    const next = page.getByTestId('entry-next').getByRole('link')
    if ((await next.count()) === 0) {
      continue
    }

    const title = (await page.getByRole('heading', { level: 1 }).first().innerText()).trim()
    await next.click()
    // 往返：下一篇自己那一页的"上一篇"必须指回刚离开的那篇
    await expect(page.getByTestId('entry-previous')).toContainText(title)
    verified = true
    break
  }

  test.skip(!verified, '这个库里没有"同分类下两篇及以上"的分类，相邻导航无法验证')
})

test('中文关键词能搜到列表里真有的那篇', async ({ page }) => {
  await signIn(page)
  await openAllEntries(page)

  // 检索词取自列表本身，因此不依赖库里具体是哪几篇
  const title = await firstEntryTitle(page)
  await page.getByTestId('knowledge-search').fill(searchTerm(title))
  await page.getByTestId('knowledge-search-submit').click()
  await expect(page.getByTestId('entry-list')).toContainText(title)
})

test('不存在的编号给错误提示而不是白屏', async ({ page }) => {
  await signIn(page)
  // 挑一个大到不可能存在的编号：它走的是与"未发布"完全相同的 404 分支
  await page.goto('/#/knowledge/entries/99999999')
  await expect(page.getByTestId('entry-state')).toContainText('打不开这条内容')
})

test('种子里的草稿对学习者不可见', async ({ page }) => {
  test.skip(REMOTE, '依赖 db/devdata 里的那条草稿，部署实例的库里没有它')
  await signIn(page)
  await openAllEntries(page)

  // 条目 4 是种子里的草稿：列表里没有，直接开地址也打不开
  await expect(page.getByTestId('entry-list')).not.toContainText('虚拟线程')
  await page.goto('/#/knowledge/entries/4')
  await expect(page.getByTestId('entry-state')).toContainText('打不开这条内容')

  // 「草稿」只出现在未发布条目的正文里；学习者侧任何列表都不该返回它
  await openAllEntries(page)
  await page.getByTestId('knowledge-search').fill('草稿')
  await page.getByTestId('knowledge-search-submit').click()
  await expect(page.getByTestId('entry-empty')).toBeVisible()
})

test('自评标记刷新后仍在，并出现在「我标记过的内容」里', async ({ page }) => {
  await signIn(page)
  await openAllEntries(page)
  const title = await firstEntryTitle(page)
  await firstEntryLink(page).click()
  await expect(page).toHaveURL(/#\/knowledge\/entries\/\d+$/)

  const understood = page.getByTestId('self-assessment-understood')
  await expect(understood).toBeVisible()
  // 先清干净：库里可能带着上一次运行留下的标记
  if ((await understood.getAttribute('aria-pressed')) === 'true') {
    await understood.click()
    await expect(understood).toHaveAttribute('aria-pressed', 'false')
  }

  await understood.click()
  await expect(understood).toHaveAttribute('aria-pressed', 'true')

  // 刷新后仍在——这条才证明标记真的落库了，而不是只存在内存里
  await page.reload()
  await expect(page.getByTestId('self-assessment-understood')).toHaveAttribute('aria-pressed', 'true')

  await page.goto('/#/my-assessments')
  await expect(page.getByTestId('my-assessment-list')).toContainText(title)

  // 收尾：留下标记会污染后续断言（这个端点本来就是幂等的）
  await openAllEntries(page)
  await firstEntryLink(page).click()
  await page.getByTestId('self-assessment-understood').click()
  await expect(page.getByTestId('self-assessment-understood')).toHaveAttribute('aria-pressed', 'false')
})

test('打开条目会记一次浏览（WORK-021）：请求真的发出去了', async ({ page }) => {
  await signIn(page)
  await openAllEntries(page)
  await firstEntryLink(page).click()
  await expect(page).toHaveURL(/#\/knowledge\/entries\/\d+$/)

  // 只在接口层验"记了"：浏览是旁路数据，界面上不该有任何可见痕迹。
  // 这里用一个在导航之后才挂的监听器，再刷新一次页面，观察真实请求。
  const viewCall = page.waitForRequest(
    (request) => request.method() === 'POST' && /\/api\/v1\/knowledge\/entries\/\d+\/view$/.test(request.url()),
    { timeout: 15_000 },
  )
  await page.reload()
  await viewCall
})

test('AI 出一道题：弹窗里作答并看到解析（接口打桩）', async ({ page }) => {
  await signIn(page)
  await page.route('**/api/v1/ai/quiz', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        traceId: null,
        data: {
          question: '这段代码为什么不会死循环？',
          options: ['选项一', '选项二', '选项三', '选项四'],
          answer: 1,
          explain: '因为边界在递减',
        },
      }),
    })
  })

  await openAllEntries(page)
  await firstEntryLink(page).click()
  await expect(page.getByTestId('markdown-body')).toBeVisible()

  await page.getByTestId('entry-quiz-generate').click()
  await expect(page.getByTestId('entry-quiz-question')).toContainText('死循环')
  await page.getByTestId('entry-quiz-option-1').click()
  await expect(page.getByTestId('entry-quiz-explain')).toContainText('边界在递减')
})
