import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { CodeFlow } from './code-flow'
import { SourceReadingProvider } from './context'
import { parseCodeFlow } from './schema'

afterEach(() => cleanup())

function renderFlow(code: string) {
  return render(
    <SourceReadingProvider value={{}}>
      <CodeFlow code={code} isIncomplete={false} language="code-flow" />
    </SourceReadingProvider>,
  )
}

const FLOW = {
  id: 'demo-flow',
  title: '一次请求的旅程',
  nodes: [
    { id: 'browser', label: '浏览器', layer: 0, order: 0 },
    { id: 'server', label: 'webserver', layer: 1, order: 0 },
    { id: 'loop', label: 'agent-loop', layer: 2, order: 0 },
  ],
  edges: [
    { from: 'browser', to: 'server', label: 'POST /api/chat' },
    { from: 'server', to: 'loop', label: 'web/request' },
  ],
  steps: [
    {
      title: '浏览器发出请求',
      desc: '页面 POST /api/chat',
      file: 'apps/web/public/index.html',
      line: 58,
      code: 'const res = await fetch("/api/chat")\nconst reader = res.body',
      focus: [1],
      nodes: ['browser', 'server'],
      edges: ['browser>server'],
      log: [{ k: '总线事件', v: 'POST /api/chat' }],
      explain: '页面把问题 POST 出去。',
    },
    {
      title: '进入 agent 循环',
      desc: 'webserver 转交 agent-loop',
      file: 'packages/webserver/src/index.ts',
      line: 20,
      code: 'await ctx.agentLoop.run(session, message, res)',
      focus: [1],
      nodes: ['server', 'loop'],
      edges: ['server>loop'],
      log: [{ k: '总线事件', v: 'agent/request' }],
      explain: 'loop 是整轮对话的调度器。',
    },
  ],
}

describe('parseCodeFlow', () => {
  it('接受合法流程', () => {
    const parsed = parseCodeFlow(JSON.stringify(FLOW))
    expect(parsed.ok).toBe(true)
    if (parsed.ok) {
      expect(parsed.value.steps).toHaveLength(2)
      expect(parsed.value.nodes).toHaveLength(3)
    }
  })

  it('拒绝指向不存在节点的连线', () => {
    const bad = {
      ...FLOW,
      edges: [{ from: 'browser', to: 'ghost' }],
    }
    const parsed = parseCodeFlow(JSON.stringify(bad))
    expect(parsed.ok).toBe(false)
    if (!parsed.ok) {
      expect(parsed.error).toContain('ghost')
    }
  })

  it('拒绝步骤里引用未声明的连线', () => {
    const bad = {
      ...FLOW,
      steps: [{ ...FLOW.steps[0], edges: ['server>browser'] }],
    }
    const parsed = parseCodeFlow(JSON.stringify(bad))
    expect(parsed.ok).toBe(false)
  })
})

describe('CodeFlow', () => {
  it('首屏高亮第一步的节点与代码行，并显示日志', () => {
    renderFlow(JSON.stringify(FLOW))
    expect(screen.getByTestId('code-flow')).toBeTruthy()
    expect(screen.getByTestId('code-flow-node-browser').getAttribute('data-active')).toBe('true')
    expect(screen.getByTestId('code-flow-node-loop').getAttribute('data-active')).toBe('false')
    expect(screen.getByTestId('code-flow-focus-line')).toBeTruthy()
    expect(screen.getByTestId('code-flow-log').textContent).toContain('POST /api/chat')
    expect(screen.getByTestId('code-flow-step-indicator').textContent).toContain('第 1 / 2 步')
  })

  it('点下一步后节点、代码与日志一起切换', () => {
    renderFlow(JSON.stringify(FLOW))
    fireEvent.click(screen.getByTestId('code-flow-next'))
    expect(screen.getByTestId('code-flow-node-loop').getAttribute('data-active')).toBe('true')
    expect(screen.getByTestId('code-flow-node-browser').getAttribute('data-active')).toBe('false')
    expect(screen.getByTestId('code-flow-log').textContent).toContain('agent/request')
    expect(screen.getByTestId('code-flow-step-indicator').textContent).toContain('第 2 / 2 步')
  })
})
