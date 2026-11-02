export const meta = { module: 'assistant', side: 'learner' }

export const cases = [
  {
    id: 'assistant-open',
    title: '助手：点悬浮按钮能打开面板',
    write: false,
    async run(s) {
      await s.goto('/#/')
      await s.step('点开助手', async () => {
        await s.openAssistant()
      })
    },
  },
  {
    id: 'assistant-tabs',
    title: '助手：对话/精简/记忆/积分四个页签都在',
    write: false,
    async run(s) {
      await s.goto('/#/')
      await s.openAssistant()
      await s.step('四个页签可见', async () => {
        for (const tab of ['chat', 'summary', 'memory', 'credits']) {
          await s.waitFor(`[data-testid="assistant-tab-${tab}"]`)
        }
      })
    },
  },
  {
    id: 'assistant-memory',
    title: '助手：记忆页签能打开并给出说明',
    write: false,
    async run(s) {
      await s.goto('/#/')
      await s.openAssistant()
      await s.step('切到记忆页签', async () => {
        await s.clickCss('[data-testid="assistant-tab-memory"]', '打开记忆页签')
        await s.expectText('记忆')
      })
    },
  },
]
