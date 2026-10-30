import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { readResumeTemplate, RESUME_TEMPLATES, ResumeSheet, saveResumeTemplate } from './templates'
import { emptyResume, type ResumeData } from './types'

// 这个包的 vitest 没开 globals，testing-library 不会自动清理，三套版式会叠在同一个 body 里
afterEach(cleanup)

/**
 * 三套版式都必须把**同一份数据**画出来。
 *
 * <p>这里只断言"内容在"与"版式真的换了"：版式是排版，不是逻辑，像素级差异交给人工看。
 * 重点是防止某套版式漏掉一节（教育 highlights 在改版前就是全都没渲染）。
 */
const data: ResumeData = {
  ...emptyResume(),
  basics: { name: '张三', phone: '13800000000', email: 'zhang@example.com', city: '杭州', links: [] },
  intent: { role: '后端工程师', city: '', availability: '' },
  work: [
    {
      company: '某公司',
      title: '实习生',
      start: '2025-01',
      end: '2025-06',
      summary: '做订单系统',
      bullets: ['把接口响应从 800ms 降到 200ms'],
    },
  ],
  education: [
    {
      school: '某大学',
      degree: '本科',
      major: '软件工程',
      start: '2021-09',
      end: '2025-06',
      highlights: ['专业前 5%'],
    },
  ],
  skills: [{ group: '语言', items: ['Java', 'SQL'] }],
  selfEvaluation: '喜欢把问题查到底',
}

describe('ResumeSheet', () => {
  it.each(RESUME_TEMPLATES.map((item) => item.id))('%s 版式渲染全部内容', (template) => {
    const { container } = render(<ResumeSheet data={data} template={template} />)

    for (const text of ['张三', '13800000000', '后端工程师', '某公司', '把接口响应从 800ms 降到 200ms']) {
      expect(screen.getByText(new RegExp(text))).toBeTruthy()
    }
    // 教育 highlights 三套版式都不能丢
    expect(container.textContent).toContain('专业前 5%')
    expect(container.textContent).toContain('喜欢把问题查到底')
  })

  it('侧栏版式把联系方式放进左栏，单栏版式没有左栏', () => {
    const sidebar = render(<ResumeSheet data={data} template="sidebar" />)
    expect(sidebar.container.querySelector('aside')).not.toBeNull()
    sidebar.unmount()

    const classic = render(<ResumeSheet data={data} template="classic" />)
    expect(classic.container.querySelector('aside')).toBeNull()
  })
})

describe('版式偏好', () => {
  it('存进去再读出来是同一个；不认识的值退回 classic', () => {
    saveResumeTemplate('compact')
    expect(readResumeTemplate()).toBe('compact')

    localStorage.setItem('paideia:resume-template', 'modern-2027')
    expect(readResumeTemplate()).toBe('classic')
  })
})
