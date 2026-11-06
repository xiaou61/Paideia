import { expect, test, type Page, type Route } from '@playwright/test'

import { signIn } from './session'

/**
 * AI 训练营的界面主链路（WORK-029 AC-017 的 e2e 部分）。
 *
 * <p><b>训练营的接口全部用 Playwright 拦下来桩住</b>，不出网、不花积分、不需要种子数据：
 * 出题要调模型，而 e2e 的价值在"界面与后端接得通"，不是"模型出的题好不好"。
 * 真实的编排、判分与归属由 `CampIntegrationTest` 与 `ApiWalkthroughTest` 覆盖。
 *
 * <p>走完的链路：开营 → 一题一屏作答 → 结果 → 出计划 → 勾一项 → 复测 → 并排对比。
 */
const CAMP_ID = 4242

const SUMMARY = {
  id: CAMP_ID,
  days: 3,
  itemCount: 4,
  droppedCount: 0,
  resumeTitle: 'e2e 简历',
  createdAt: '2026-10-21T06:00:00Z',
  diagnosticSubmitted: false,
  retestSubmitted: false,
  hasPlan: false,
}

function item(id: number, seq: number, topic: string, stem: string) {
  return {
    id,
    seq,
    topic,
    difficulty: 'medium',
    stem,
    options: ['选项一', '选项二', '选项三', '选项四'],
  }
}

const DIAGNOSTIC = [
  item(1, 1, '集合', '第一题：HashMap 在并发扩容时可能发生什么？'),
  item(2, 2, '集合', '第二题：ConcurrentHashMap 如何保证安全？'),
  item(3, 3, '并发', '第三题：线程池的核心参数有哪些？'),
  item(4, 4, '并发', '第四题：volatile 能保证原子性吗？'),
]

/** 复测卷题干必须与诊断卷不同，否则界面上会出现"同一道题"。 */
const RETEST = [
  item(11, 1, '集合', '复测第一题：HashMap 的扩容机制是什么？'),
  item(12, 2, '集合', '复测第二题：红黑树什么时候会退化成链表？'),
  item(13, 3, '并发', '复测第三题：AQS 的等待队列是怎么工作的？'),
  item(14, 4, '并发', '复测第三题之外的题：CAS 的 ABA 问题是什么？'),
]

const RESULT = {
  topics: [
    { topic: '集合', correct: 1, total: 2, byDifficulty: { medium: { correct: 1, total: 2 } } },
    { topic: '并发', correct: 2, total: 2, byDifficulty: { medium: { correct: 2, total: 2 } } },
  ],
  weak: ['集合'],
  strong: ['并发'],
  previous: null,
  verdict: null,
}

const PLAN = {
  id: 900,
  days: 3,
  budgetMinutes: 90,
  weak: ['集合'],
  unscheduled: [],
  dayPlans: [
    {
      day: 1,
      tasks: [
        {
          id: 501,
          day: 1,
          kind: 'read',
          topic: '集合',
          title: 'HashMap 详解',
          minutes: 20,
          why: '共 2 题错 1 题',
          entryId: 1,
          done: false,
        },
      ],
    },
    {
      day: 2,
      tasks: [
        {
          id: 502,
          day: 2,
          kind: 'gap',
          topic: '集合',
          title: 'ConcurrentHashMap',
          minutes: 15,
          why: '这一概念没有可读内容',
          entryId: null,
          done: false,
        },
      ],
    },
    {
      day: 3,
      tasks: [
        {
          id: 503,
          day: 3,
          kind: 'review',
          topic: '集合',
          title: 'HashMap 详解',
          minutes: 5,
          why: '昨天读过的内容今天再回看一遍',
          entryId: 1,
          done: false,
        },
      ],
    },
  ],
}

const COMPARISON = {
  topics: [
    {
      topic: '集合',
      diagnostic: { correct: 1, total: 2 },
      retest: { correct: 2, total: 2 },
      verdict: 'IMPROVED',
      reason: null,
    },
  ],
  onlyInDiagnostic: [],
  onlyInRetest: [],
  mismatched: [],
}

const envelope = (data: unknown) => ({ code: 0, message: 'ok', data, traceId: null })

/** 桩住训练营的全部端点。状态机在闭包里走，模拟后端在流程中的变化。 */
async function stubCamp(page: Page): Promise<void> {
  const state = {
    diagnosticSubmitted: false,
    retestSubmitted: false,
    hasPlan: false,
    retestGenerated: false,
    toggled: false,
  }

  const json = (route: Route, data: unknown) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(envelope(data)) })

  await page.route('**/api/v1/ai/camps**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()

    // 营本身
    if (path === '/api/v1/ai/camps' && method === 'GET') {
      return json(route, [{ ...SUMMARY, ...state }])
    }
    if (path === `/api/v1/ai/camps/${CAMP_ID}` && method === 'GET') {
      return json(route, { ...SUMMARY, ...state, hasPlan: state.hasPlan })
    }
    if (path === `/api/v1/ai/camps/${CAMP_ID}` && method === 'DELETE') {
      return json(route, null)
    }
    if (path === '/api/v1/ai/camps' && method === 'POST') {
      return json(route, { id: CAMP_ID, days: 3, itemCount: 4, droppedCount: 0, shortOfTarget: false })
    }

    // 卷子
    if (path.endsWith('/items')) {
      const form = url.searchParams.get('form')
      return json(route, form === 'retest' ? (state.retestGenerated ? RETEST : []) : DIAGNOSTIC)
    }
    if (path.endsWith('/submit')) {
      const body = JSON.parse(request.postData() ?? '{}') as { form?: string }
      if (body.form === 'retest') {
        state.retestSubmitted = true
        return json(route, {
          ...RESULT,
          previous: COMPARISON,
          verdict: 'IMPROVED',
        })
      }
      state.diagnosticSubmitted = true
      return json(route, RESULT)
    }
    if (path.endsWith('/result')) {
      const form = url.searchParams.get('form')
      if (form === 'retest') {
        if (!state.retestSubmitted) {
          return route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({ code: 40000, message: '这一卷还没有交卷，没有结果可看', data: null, traceId: null }),
          })
        }
        return json(route, { ...RESULT, previous: COMPARISON, verdict: 'IMPROVED' })
      }
      if (!state.diagnosticSubmitted) {
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ code: 40000, message: '这一卷还没有交卷，没有结果可看', data: null, traceId: null }),
        })
      }
      return json(route, RESULT)
    }

    // 计划
    if (path.endsWith('/plan') && method === 'POST') {
      state.hasPlan = true
      return json(route, PLAN)
    }
    if (path.endsWith('/plan') && method === 'GET') {
      if (!state.hasPlan) {
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ code: 40000, message: '还没有生成计划', data: null, traceId: null }),
        })
      }
      return json(route, PLAN)
    }
    if (path.includes('/tasks/') && method === 'PATCH') {
      state.toggled = true
      return json(route, true)
    }

    // 复测出卷
    if (path.endsWith('/retest') && method === 'POST') {
      state.retestGenerated = true
      return json(route, { id: CAMP_ID, days: 3, itemCount: 4, droppedCount: 0, shortOfTarget: false })
    }

    return json(route, null)
  })
}

test.describe('训练营', () => {
  test.beforeEach(async ({ page }) => {
    await stubCamp(page)
    await signIn(page)
  })

  test('开营 → 作答 → 结果 → 计划 → 复测 → 并排对比', async ({ page }) => {
    await page.goto('/#/camp')
    await expect(page.getByRole('heading', { name: '用简历开一个训练营' })).toBeVisible()

    // 列表里已有这个营（桩返回了一条）
    await expect(page.getByTestId(`camp-card-${CAMP_ID}`)).toBeVisible()

    await page.goto(`/#/camp/${CAMP_ID}`)
    await expect(page.getByTestId('camp-quiz')).toBeVisible()
    await expect(page.getByTestId('camp-progress')).toContainText('第 1 / 4 题')

    // 一题一屏：答完当前题才能进下一题
    for (let question = 0; question < 4; question++) {
      await page.getByTestId('camp-option-0').click()
      if (question < 3) {
        await page.getByRole('button', { name: '下一题' }).click()
      }
    }

    // 四题都答完，交卷按钮才可用
    await expect(page.getByTestId('camp-submit')).toBeEnabled()
    await page.getByTestId('camp-submit').click()

    // 交卷后诊断面板变成"已经交过"，结果页出现弱项
    await expect(page.getByText('诊断卷已经交过了，结果在「结果」标签里。')).toBeVisible()
    await page.getByRole('tab', { name: '结果' }).click()
    await expect(page.getByTestId('camp-result')).toBeVisible()
    await expect(page.getByText('需要补的')).toBeVisible()
    await expect(page.getByText('1 / 2 题答对')).toBeVisible()

    // 页面上不出现打分口径的词
    const body = (await page.locator('body').innerText()).replace(/\s+/g, '')
    for (const banned of ['总分', '百分制', '通过率', '得分', '等级']) {
      expect(body).not.toContain(banned)
    }

    // 出计划
    await page.getByTestId('camp-build-plan').click()
    await page.getByRole('tab', { name: '计划' }).click()
    await expect(page.getByTestId('camp-plan')).toBeVisible()
    await expect(page.getByText('第 1 天')).toBeVisible()
    await expect(page.getByText('第 2 天')).toBeVisible()
    await expect(page.getByText('第 3 天')).toBeVisible()
    // 缺口项给出提示而不是死链接
    await expect(page.getByText(/可以先去知识库看看/)).toBeVisible()

    // 勾一项
    await page.getByRole('checkbox').first().click()

    // 复测
    await page.getByRole('tab', { name: '复测' }).click()
    await page.getByTestId('camp-retest').click()
    await expect(page.getByTestId('camp-quiz')).toBeVisible()
    for (let question = 0; question < 4; question++) {
      await page.getByTestId('camp-option-0').click()
      if (question < 3) {
        await page.getByRole('button', { name: '下一题' }).click()
      }
    }
    await page.getByTestId('camp-submit').click()

    // 并排对比
    await expect(page.getByTestId('camp-comparison')).toBeVisible()
    await expect(page.getByText('进步')).toBeVisible()
    await expect(page.getByText('诊断 1 / 2 题答对')).toBeVisible()
    await expect(page.getByText('复测 2 / 2 题答对')).toBeVisible()
  })

  test('没答完不能交卷，并写明还差几题', async ({ page }) => {
    await page.goto(`/#/camp/${CAMP_ID}`)
    await expect(page.getByTestId('camp-quiz')).toBeVisible()

    // 一题都没答时，"交卷"根本不在最后一屏；先跳到最后
    await page.getByTestId('camp-option-0').click()
    for (let question = 0; question < 3; question++) {
      await page.getByRole('button', { name: '下一题' }).click()
    }

    await expect(page.getByTestId('camp-submit')).toBeDisabled()
    await expect(page.getByTestId('camp-incomplete')).toContainText('还有 3 题没答')
  })

  test('390px 下作答页与结果页无横向滚动', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/#/camp/${CAMP_ID}`)
    await expect(page.getByTestId('camp-quiz')).toBeVisible()

    const overflowX = () =>
      page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    await expect.poll(overflowX, { timeout: 5000, intervals: [100, 200, 400] }).toBeLessThanOrEqual(1)

    await page.getByRole('tab', { name: '结果' }).click()
    await expect
      .poll(overflowX, { timeout: 5000, intervals: [100, 200, 400] })
      .toBeLessThanOrEqual(1)
  })
})
