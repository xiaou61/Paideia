import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { PlanDays } from './plan'
import type { CampPlan } from './types'
import { ComparisonTable, WeaknessCards } from './weakness'

const plan: CampPlan = {
  id: 1,
  days: 3,
  budgetMinutes: 90,
  weak: ['并发'],
  unscheduled: [],
  dayPlans: [
    {
      day: 1,
      tasks: [
        {
          id: 11,
          day: 1,
          kind: 'read',
          topic: '并发',
          title: '并发入门',
          minutes: 20,
          why: '共 4 题错 3 题',
          entryId: 5,
          done: false,
        },
      ],
    },
    {
      day: 2,
      tasks: [
        {
          id: 12,
          day: 2,
          kind: 'gap',
          topic: '集合',
          title: '集合',
          minutes: 15,
          why: '这一概念没有可读内容',
          entryId: null,
          done: true,
        },
      ],
    },
    { day: 3, tasks: [] },
  ],
}

describe('PlanDays', () => {
  it('按天分组，不会把七天营的任务挤进一天', () => {
    render(<PlanDays plan={plan} onToggle={vi.fn()} />)
    expect(screen.getByText('第 1 天')).toBeTruthy()
    expect(screen.getByText('第 2 天')).toBeTruthy()
    expect(screen.getByText('第 3 天')).toBeTruthy()
    expect(screen.getByText('今天没有安排。')).toBeTruthy()
  })

  it('缺口项给出「先去知识库看看」而不是一个死链接', () => {
    render(<PlanDays plan={plan} onToggle={vi.fn()} onOpenEntry={vi.fn()} />)
    expect(screen.getByText(/可以先去知识库看看/)).toBeTruthy()
    // 只有第一项有 entryId，所以"打开这一篇"只出现一次
    expect(screen.getAllByText('打开这一篇')).toHaveLength(1)
  })

  it('没有传 onOpenEntry 时不渲染跳转按钮（页面层没接入口就不给死链接）', () => {
    render(<PlanDays plan={plan} onToggle={vi.fn()} />)
    expect(screen.queryAllByText('打开这一篇')).toHaveLength(0)
  })

  it('排不下的项单独列出来并写明数量', () => {
    render(
      <PlanDays
        plan={{
          ...plan,
          unscheduled: [
            {
              id: 99,
              day: 0,
              kind: 'gap',
              topic: '事务',
              title: '事务',
              minutes: 15,
              why: '没有可读内容',
              entryId: null,
              done: false,
            },
          ],
        }}
        onToggle={vi.fn()}
      />,
    )
    expect(screen.getByText(/时间不够，还有 1 项没排进来/)).toBeTruthy()
  })
})

describe('WeaknessCards', () => {
  it('只显示主题计数，不出现总分或通过率这类词', () => {
    const { container } = render(
      <WeaknessCards
        topics={[
          { topic: '并发', correct: 1, total: 4, byDifficulty: { hard: { correct: 0, total: 1 } } },
          { topic: '集合', correct: 4, total: 4, byDifficulty: {} },
        ]}
        weak={['并发']}
        strong={['集合']}
      />,
    )
    expect(container.textContent).toContain('并发')
    expect(container.textContent).toContain('1 / 4 题答对')
    for (const banned of ['通过率', '总分', '百分制', '等级', '得分']) {
      expect(container.textContent).not.toContain(banned)
    }
  })
})

describe('ComparisonTable', () => {
  it('题量不一致时显示「仅供参考」并带上原因', () => {
    const { container } = render(
      <ComparisonTable
        compared={[
          {
            topic: '并发',
            diagnostic: { correct: 2, total: 3 },
            retest: { correct: 2, total: 4 },
            verdict: 'INCONCLUSIVE',
            reason: '两次题量不同（3 题 vs 4 题），对比仅供参考',
          },
        ]}
        onlyInDiagnostic={[]}
        onlyInRetest={[]}
      />,
    )
    expect(container.textContent).toContain('仅供参考')
    expect(container.textContent).toContain('两次题量不同')
  })

  it('只用一侧出现的主题单独说明且不给结论', () => {
    const { container } = render(
      <ComparisonTable compared={[]} onlyInDiagnostic={['事务']} onlyInRetest={['索引']} />,
    )
    expect(container.textContent).toContain('只在诊断卷出现：事务')
    expect(container.textContent).toContain('只在复测卷出现：索引')
    expect(container.textContent).toContain('不给结论')
  })
})
