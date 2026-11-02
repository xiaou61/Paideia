export const meta = { module: 'admin-ai', side: 'admin' }

const ADMIN = '/admin'

export const cases = [
  {
    id: 'admin-ai-overview',
    title: 'AI 调用入口：/ai-runs 落到调用概览',
    write: false,
    async run(s) {
      await s.step('打开 AI 调用入口', async () => {
        await s.goto(`${ADMIN}/#/ai-runs`)
        await s.expectUrl('/admin/#/ai-runs/overview')
        await s.expectText('AI 调用')
      })
    },
  },
  {
    id: 'admin-ai-list',
    title: '调用明细：页面可开',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/ai-runs/list`)
      await s.step('确认调用明细页', async () => {
        await s.expectText('调用明细')
      })
    },
  },
  {
    id: 'admin-ai-feedback',
    title: '回答反馈：页面可开',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/ai-runs/feedback`)
      await s.step('确认回答反馈页', async () => {
        await s.expectText('回答反馈')
      })
    },
  },
  {
    id: 'admin-ai-credits',
    title: 'AI 积分入口：/ai-credits 落到用量审计',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/ai-credits`)
      await s.step('确认用量审计页', async () => {
        await s.expectUrl('/admin/#/ai-credits/audit')
        await s.expectText('用量审计')
      })
    },
  },
  {
    id: 'admin-ai-screen',
    title: 'AI 调用大屏：无侧栏也能打开',
    write: false,
    async run(s) {
      await s.goto(`${ADMIN}/#/ai-runs/screen`)
      await s.step('确认大屏标题', async () => {
        await s.expectText('AI 调用大屏')
      })
    },
  },
]
