export const meta = { module: 'job', side: 'learner' }

export const cases = [
  {
    id: 'job-resume-list',
    title: '我的简历：列表或空态二选一',
    write: false,
    async run(s) {
      await s.step('打开简历页', async () => {
        await s.goto('/#/resume')
        await s.expectText('我的简历')
      })
      await s.step('列表或空态二选一', async () => {
        const list = await s.count('[data-testid="resume-list"]')
        const upload = await s.count('[data-testid="resume-upload-input"]')
        if (!list && !upload) throw new Error('简历页没有列表也没有上传入口')
      })
    },
  },
  {
    id: 'job-resume-create',
    title: '新建简历：独立页面能打开',
    write: false,
    async run(s) {
      await s.goto('/#/resume/create')
      await s.step('确认新建页', async () => {
        await s.expectText('新建简历')
        await s.waitFor('#resume-title')
      })
    },
  },
  {
    id: 'job-interview',
    title: '模拟面试：页面可开且给出开始入口或确定状态',
    write: false,
    async run(s) {
      await s.goto('/#/interview')
      await s.step('确认面试页', async () => {
        await s.expectText('模拟面试')
      })
      await s.step('开始入口或状态提示在', async () => {
        const start = await s.count('[data-testid="interview-start"]')
        const transcript = await s.count('[data-testid="interview-transcript"]')
        if (!start && !transcript) throw new Error('面试页既没有开始入口也没有会话区')
      })
    },
  },
]
