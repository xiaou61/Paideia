import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { SourceReadingProvider, type DocumentContextValue } from './context'
import { CodeTour } from './code-tour'
import { CodeMap } from './code-map'
import { LearningPath } from './learning-path'
import { CodeChallenge } from './code-challenge'
import { Quiz } from './quiz'
import { canonicalSha256, type SqlTable } from './sql-judge'

/**
 * 五种块的交互行为（WORK-014）。
 *
 * <p>直接渲染块组件、包一层 `SourceReadingProvider`，**不经过 markdown 渲染器**：
 * 这里要测的是"点了会怎样"，不是"围栏能不能被解析"——后者由 `markdown.test.tsx` 与
 * e2e 覆盖。这样每个用例都不依赖 streamdown 的懒加载，跑得快且不抖。
 */

// 这个包没有全局 cleanup：每个用例自己收尾，否则 `screen` 会同时看到上一轮的 DOM
afterEach(() => cleanup())

function renderBlock(node: React.ReactNode, context: DocumentContextValue = {}) {
  return render(<SourceReadingProvider value={context}>{node}</SourceReadingProvider>)
}

const TOUR = {
  id: 'demo',
  title: '演示走读',
  repo: 'facebook/react',
  commit: 'd75b0697',
  license: 'MIT',
  file: 'packages/react-reconciler/src/ReactFiberWorkLoop.js',
  steps: [
    {
      title: '第一步',
      line: 10,
      code: 'a();\nb();',
      focus: [1],
      explain: '先 a 再 b。',
    },
    {
      title: '第二步',
      line: 20,
      code: 'c();',
      explain: '然后 c。',
      predict: { question: '为什么先 a？', options: ['为了初始化', '为了 GC'], answer: 0, reveal: '因为要先初始化。' },
      state: { vars: [{ name: 'n', value: '1' }], stack: ['main'], output: ['hi'] },
    },
  ],
}

describe('CodeTour', () => {
  it('渲染真实行号与 focus 高亮，并在切步后同时更新解释', () => {
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />)
    expect(screen.getByText('10')).toBeTruthy()
    expect(screen.getByText('11')).toBeTruthy()
    expect(screen.getByTestId('code-tour-focus-line')).toBeTruthy()
    expect(screen.getByText('先 a 再 b。')).toBeTruthy()

    fireEvent.click(screen.getByTestId('code-tour-next'))
    expect(screen.getByText('20')).toBeTruthy()
    expect(screen.queryByText('先 a 再 b。')).toBeNull()
  })

  it('预测未作答时不给解释，作答后才揭晓', () => {
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />)
    fireEvent.click(screen.getByTestId('code-tour-next'))

    // 第二步带 predict：解释被挡住
    expect(screen.queryByTestId('code-tour-explain')).toBeNull()
    fireEvent.click(screen.getByTestId('code-tour-predict-0'))
    expect(screen.getByTestId('code-tour-explain')).toBeTruthy()
    expect(screen.getByText(/✓ 猜对了/)).toBeTruthy()
  })

  it('答错也显示正确答案与解释（不只靠颜色）', () => {
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />)
    fireEvent.click(screen.getByTestId('code-tour-next'))
    fireEvent.click(screen.getByTestId('code-tour-predict-1'))
    expect(screen.getByText(/✗ 不对/)).toBeTruthy()
    expect(screen.getByText(/正确：为了初始化/)).toBeTruthy()
  })

  it('「直接看解释」解卡', () => {
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />)
    fireEvent.click(screen.getByTestId('code-tour-next'))
    fireEvent.click(screen.getByText('直接看解释'))
    expect(screen.getByTestId('code-tour-explain')).toBeTruthy()
  })

  it('状态快照随步骤出现，且只在该步有 state 时渲染', () => {
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />)
    expect(screen.queryByTestId('code-tour-state')).toBeNull()
    fireEvent.click(screen.getByTestId('code-tour-next'))
    expect(screen.getByTestId('code-tour-state')).toBeTruthy()
    expect(screen.getByText('n')).toBeTruthy()
    expect(screen.getByText(/main/)).toBeTruthy()
    expect(screen.getByText('hi')).toBeTruthy()
  })

  it('页脚给出固定 commit 的 permalink 与许可证', () => {
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />)
    expect(screen.getByText('facebook/react@d75b0697')).toBeTruthy()
    expect(screen.getByText('MIT')).toBeTruthy()
    const link = screen.getByText('查看 GitHub 原文 ↗').closest('a')
    expect(link?.getAttribute('href')).toBe(
      'https://github.com/facebook/react/blob/d75b0697/packages/react-reconciler/src/ReactFiberWorkLoop.js#L10-L11',
    )
  })

  it('切步时写进度', () => {
    const update = vi.fn()
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />, {
      entryId: 1,
      progress: { read: () => null, update, reset: () => {} },
    })
    fireEvent.click(screen.getByTestId('code-tour-next'))
    expect(update).toHaveBeenCalledWith({ tours: { demo: { step: 1, total: 2, done: true } } })
  })

  it('挂载时从本地进度恢复上次那一步（刷新不回到第一页）', () => {
    const read = vi.fn(() => ({
      updatedAt: '2026-10-17T00:00:00Z',
      tours: { demo: { step: 1, total: 2, done: true } },
      challenges: {},
      quizzes: {},
    }))
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />, {
      entryId: 1,
      progress: { read, update: () => {}, reset: () => {} },
    })
    expect(screen.getByTestId('code-tour').textContent).toContain('第 2 步')
  })

  it('没有 ask 能力时不渲染「问 AI」', () => {
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />)
    expect(screen.queryByTestId('block-ask')).toBeNull()
  })

  it('有 ask 时把条目标题、步骤与位置带进提示词（不发送）', () => {
    const ask = vi.fn()
    renderBlock(<CodeTour code={JSON.stringify(TOUR)} isIncomplete={false} language="code-tour" />, {
      entryTitle: '读懂更新流程',
      onAskAssistant: ask,
    })
    fireEvent.click(screen.getByTestId('block-ask'))
    expect(ask).toHaveBeenCalledTimes(1)
    const prompt = ask.mock.calls[0]?.[0] as string
    expect(prompt).toContain('读懂更新流程')
    expect(prompt).toContain('第 1 步')
    expect(prompt).toContain('ReactFiberWorkLoop.js:10')
  })

  it('坏数据只降级自身并保留原文', () => {
    renderBlock(<CodeTour code="{oops" isIncomplete={false} language="code-tour" />)
    expect(screen.getByTestId('block-fallback')).toBeTruthy()
    expect(screen.getByText(/数据格式有误/)).toBeTruthy()
    expect(screen.getByText('{oops')).toBeTruthy()
  })
})

describe('CodeMap', () => {
  const MAP = {
    title: '一次更新',
    nodes: [
      { id: 'schedule', label: 'schedule', layer: 0, order: 0, tour: 'demo', step: 0 },
      { id: 'render', label: 'render', layer: 1, order: 0, tour: 'demo', step: 1 },
    ],
    edges: [{ from: 'schedule', to: 'render' }],
  }

  it('点击节点写入 activeStep（驱动同页走读）', () => {
    const onOpenEntry = vi.fn()
    renderBlock(<CodeMap code={JSON.stringify(MAP)} isIncomplete={false} language="code-map" />, { onOpenEntry })
    fireEvent.click(screen.getByTestId('code-map-node-render'))
    // 有 tour/step 时走上下文，不触发条目跳转
    expect(onOpenEntry).not.toHaveBeenCalled()
  })

  it('节点带 entryId 时走条目跳转', () => {
    const map = { nodes: [{ id: 'a', label: 'A', layer: 0, order: 0, entryId: 42 }], edges: [] }
    const onOpenEntry = vi.fn()
    renderBlock(<CodeMap code={JSON.stringify(map)} isIncomplete={false} language="code-map" />, { onOpenEntry })
    fireEvent.click(screen.getByTestId('code-map-node-a'))
    expect(onOpenEntry).toHaveBeenCalledWith(42)
  })

  it('坏数据降级', () => {
    renderBlock(<CodeMap code="{}" isIncomplete={false} language="code-map" />)
    expect(screen.getByTestId('block-fallback')).toBeTruthy()
  })
})

describe('LearningPath', () => {
  const PATH = {
    title: '路径',
    steps: [
      { entryId: 1, title: '第一站', minutes: 10 },
      { title: '未发布的站' },
    ],
  }

  it('无进度时全部未开始；没有 onOpenEntry 时不可点', () => {
    renderBlock(<LearningPath code={JSON.stringify(PATH)} isIncomplete={false} language="learning-path" />)
    expect(screen.getByText('0 / 2 已完成')).toBeTruthy()
    expect((screen.getByTestId('learning-path-step-0') as HTMLButtonElement).disabled).toBe(true)
  })

  it('读取其他条目的进度并标出已完成', () => {
    const read = vi.fn((id?: number) =>
      id === 1
        ? {
            updatedAt: '2026-10-17T00:00:00Z',
            tours: { demo: { step: 2, total: 3, done: true } },
            challenges: {},
            quizzes: {},
          }
        : null,
    )
    const onOpenEntry = vi.fn()
    renderBlock(<LearningPath code={JSON.stringify(PATH)} isIncomplete={false} language="learning-path" />, {
      entryId: 9,
      progress: { read, update: () => {}, reset: () => {} },
      onOpenEntry,
    })
    expect(screen.getByText('1 / 2 已完成')).toBeTruthy()
    expect(screen.getByText('已完成')).toBeTruthy()
    fireEvent.click(screen.getByTestId('learning-path-step-0'))
    expect(onOpenEntry).toHaveBeenCalledWith(1)
  })
})

describe('CodeChallenge', () => {
  it('填空题：选项正确才全对，并给出解析', () => {
    const challenge = {
      id: 'fill-1',
      kind: 'fill',
      title: '补上调用',
      template: '{{0}}(root);',
      options: [['prepare', 'flush']],
      answers: [0],
      explain: '先 prepare。',
    }
    renderBlock(<CodeChallenge code={JSON.stringify(challenge)} isIncomplete={false} language="code-challenge" />)
    fireEvent.change(screen.getByTestId('challenge-blank-0'), { target: { value: '1' } })
    fireEvent.click(screen.getByTestId('challenge-check'))
    expect(screen.getByText(/还对不上/)).toBeTruthy()
    expect(screen.getByTestId('challenge-explain')).toBeTruthy()
  })

  it('排序题：上下移动到位后判定全对', () => {
    const challenge = { id: 'order-1', kind: 'order', title: '排顺序', lines: ['b', 'a'], answer: [1, 0] }
    renderBlock(<CodeChallenge code={JSON.stringify(challenge)} isIncomplete={false} language="code-challenge" />)
    fireEvent.click(screen.getAllByLabelText('上移')[1]!)
    fireEvent.click(screen.getByTestId('challenge-check'))
    expect(screen.getByText('✓ 全对')).toBeTruthy()
  })

  it('找 bug：选对行才算通过', () => {
    const challenge = { id: 'bug-1', kind: 'bug', title: '找 bug', code: 'int a = 1;\nint b = a / 0;', bugLine: 2, explain: '除零。' }
    renderBlock(<CodeChallenge code={JSON.stringify(challenge)} isIncomplete={false} language="code-challenge" />)
    fireEvent.click(screen.getByTestId('challenge-bug-line-2'))
    fireEvent.click(screen.getByTestId('challenge-check'))
    expect(screen.getByText('✓ 全对')).toBeTruthy()
  })

  it('成绩写进进度', () => {
    const update = vi.fn()
    const challenge = { id: 'bug-2', kind: 'bug', title: '找 bug', code: 'a\nb', bugLine: 1 }
    renderBlock(<CodeChallenge code={JSON.stringify(challenge)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      progress: { read: () => null, update, reset: () => {} },
    })
    fireEvent.click(screen.getByTestId('challenge-bug-line-1'))
    fireEvent.click(screen.getByTestId('challenge-check'))
    expect(update).toHaveBeenCalledWith({ challenges: { 'bug-2': { score: 1, total: 1 } } })
  })
})

describe('CodeChallenge · SQL 题', () => {
  /**
   * 判题口径本身由 `sql-judge.test.ts` 的语料覆盖；这里只验**关卡里的接线**：
   * 跑出来的结果集比对之后，成绩真的写进了进度（WORK-032 TASK-010 的验收）。
   *
   * <p>哈希用 `canonicalSha256` 现算，不写死常量——否则格式一变这里就红，
   * 而真正该红的是语料那条。
   */
  const TABLE = { columns: ['n'], rows: [['1']] }
  const challenge = {
    id: 'sql-1',
    kind: 'sql-write',
    title: '查出 1',
    dataset: ['public'],
    starter: 'SELECT 1 AS n;',
    normalize: { types: { n: 'int' } },
    expectHash: { public: canonicalSha256(TABLE, { types: { n: 'int' } }) },
  }

  function sqlSandbox(tables: SqlTable[]) {
    return {
      ready: async () => true,
      run: async () => ({
        stdout: '',
        stderr: '',
        exitCode: 0,
        durationMs: 10,
        timedOut: false,
        outOfMemory: false,
        truncated: false,
        tables,
      }),
    }
  }

  it('结果与期望一致：判定通过并写进进度', async () => {
    const update = vi.fn()
    renderBlock(<CodeChallenge code={JSON.stringify(challenge)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      progress: { read: () => null, update, reset: () => {} },
      sandbox: sqlSandbox([TABLE]),
    })
    fireEvent.click(await screen.findByRole('button', { name: '运行' }))
    await waitFor(() => expect(screen.getByTestId('sql-verdict').textContent).toContain('通过'))
    expect(update).toHaveBeenCalledWith({ challenges: { 'sql-1': { score: 1, total: 1 } } })
  })

  it('结果不一致：判定失败，进度记 0 分而不是不记', async () => {
    const update = vi.fn()
    renderBlock(<CodeChallenge code={JSON.stringify(challenge)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      progress: { read: () => null, update, reset: () => {} },
      sandbox: sqlSandbox([{ columns: ['n'], rows: [['2']] }]),
    })
    fireEvent.click(await screen.findByRole('button', { name: '运行' }))
    await waitFor(() =>
      expect(screen.getByTestId('sql-verdict').textContent).toContain('结果与期望不一致'),
    )
    expect(update).toHaveBeenCalledWith({ challenges: { 'sql-1': { score: 0, total: 1 } } })
  })

  it('沙箱不可用：不渲染运行按钮，只说明只能阅读', async () => {
    renderBlock(<CodeChallenge code={JSON.stringify(challenge)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: { ready: async () => false, run: async () => { throw new Error('不该被调用') } },
    })
    await waitFor(() => expect(screen.getByText(/暂时只能阅读/)).toBeTruthy())
    expect(screen.queryByRole('button', { name: '运行' })).toBeNull()
  })
})

describe('Quiz', () => {
  const QUIZ = {
    id: 'q1',
    questions: [
      { question: '第一题', options: ['对', '错'], answer: 0, explain: '就是对的' },
      { question: '第二题', options: ['A', 'B'], answer: 1 },
    ],
  }

  it('逐题即时反馈，答完显示得分', () => {
    renderBlock(<Quiz code={JSON.stringify(QUIZ)} isIncomplete={false} language="quiz" />)
    fireEvent.click(screen.getByTestId('quiz-option-0-0'))
    expect(screen.getByTestId('quiz-explain-0')).toBeTruthy()
    expect(screen.getByText('答对了。')).toBeTruthy()
    fireEvent.click(screen.getByTestId('quiz-option-1-0'))
    expect(screen.getByTestId('quiz-score').textContent).toContain('答对 1 / 2')
  })

  it('已答的题不能再改（先答先得，避免改到对为止）', () => {
    renderBlock(<Quiz code={JSON.stringify(QUIZ)} isIncomplete={false} language="quiz" />)
    fireEvent.click(screen.getByTestId('quiz-option-0-1'))
    expect((screen.getByTestId('quiz-option-0-0') as HTMLButtonElement).disabled).toBe(true)
  })

  it('完赛时写成绩', () => {
    const update = vi.fn()
    renderBlock(<Quiz code={JSON.stringify(QUIZ)} isIncomplete={false} language="quiz" />, {
      entryId: 1,
      progress: { read: () => null, update, reset: () => {} },
    })
    fireEvent.click(screen.getByTestId('quiz-option-0-0'))
    fireEvent.click(screen.getByTestId('quiz-option-1-1'))
    expect(update).toHaveBeenCalledWith({ quizzes: { q1: { score: 2, total: 2 } } })
  })
})

/**
 * 运行题型（WORK-015）。
 *
 * <p>这里**不测真容器**：本机没有 Docker。测的是"沙箱端口给了什么、界面就呈现什么"——
 * 有端口才出现运行按钮、判定按用例逐条比对、超时与 OOM 算失败。
 */
describe('CodeChallenge · run', () => {
  const RUN = {
    id: 'run-1',
    kind: 'run',
    title: '打印一行',
    language: 'python',
    starter: 'print("hi")',
    cases: [{ name: '样例一', expected: 'hi' }],
    explain: '就这么简单。',
  }

  function fakeSandbox(outputs: Record<string, Partial<{ stdout: string; stderr: string; exitCode: number; durationMs: number; timedOut: boolean; outOfMemory: boolean; truncated: boolean }>>) {
    return {
      ready: async () => true,
      run: async (input: { caseId?: string }) => {
        const key = (input.caseId ?? '').split(':').pop() ?? ''
        const partial = outputs[key] ?? {}
        return {
          stdout: '',
          stderr: '',
          exitCode: 0,
          durationMs: 10,
          timedOut: false,
          outOfMemory: false,
          truncated: false,
          ...partial,
        }
      },
    }
  }

  it('没有沙箱端口时只显示说明，不渲染运行按钮', () => {
    renderBlock(<CodeChallenge code={JSON.stringify(RUN)} isIncomplete={false} language="code-challenge" />)
    expect(screen.queryByTestId('challenge-run')).toBeNull()
    expect(screen.getByText(/没有启用「运行」/)).toBeTruthy()
  })

  it('有端口时出现运行按钮；通过时报全过', async () => {
    renderBlock(<CodeChallenge code={JSON.stringify(RUN)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: fakeSandbox({ 样例一: { stdout: 'hi\n' } }),
    })
    const button = await screen.findByTestId('challenge-run')
    fireEvent.click(button)
    await waitFor(() => expect(screen.getByTestId('challenge-run-results')).toBeTruthy())
    expect(screen.getByText(/通过 1 \/ 1/)).toBeTruthy()
  })

  it('输出不符时判失败，并显示期望与实际', async () => {
    renderBlock(<CodeChallenge code={JSON.stringify(RUN)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: fakeSandbox({ 样例一: { stdout: 'bye\n' } }),
    })
    fireEvent.click(await screen.findByTestId('challenge-run'))
    await waitFor(() => expect(screen.getByTestId('challenge-run-results')).toBeTruthy())
    expect(screen.getByText(/通过 0 \/ 1/)).toBeTruthy()
    expect(screen.getByTestId('challenge-stdout-0').textContent).toContain('bye')
  })

  it('超时算失败（不当成"输出碰巧一致"）', async () => {
    renderBlock(<CodeChallenge code={JSON.stringify(RUN)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: fakeSandbox({ 样例一: { stdout: 'hi', timedOut: true, exitCode: -1 } }),
    })
    fireEvent.click(await screen.findByTestId('challenge-run'))
    await waitFor(() => expect(screen.getByText(/通过 0 \/ 1/)).toBeTruthy())
    expect(screen.getByText(/超时/)).toBeTruthy()
  })
})

/**
 * Linux 判题题型（WORK-056）。
 *
 * <p>三件事必须成立：没端口/端口没实现时**不渲染运行按钮**；结论由服务端给
 * （前端不重算）；`未通过` 与 `未能判定` 必须是两个不同的显示——混在一起会让学习者
 * 对着一个没跑起来的容器改半小时命令。
 */
describe('shell 判题块', () => {
  const SHELL = {
    id: 'linux-04-chmod',
    kind: 'shell',
    title: '把密钥文件收成只有属主可读',
    seed: [{ path: 'app/secrets.env', content: 'TOKEN=demo\n' }],
    starter: 'chmod ??? app/secrets.env',
    judge: 'test "$(stat -c %a app/secrets.env)" = 600',
    explain: '600 只有属主可读写。',
  }

  const outcome = (verdict: 'passed' | 'failed' | 'undetermined', extra = {}) => ({
    verdict,
    runExit: 0,
    judgeExit: verdict === 'passed' ? 0 : 1,
    stdout: '',
    stderr: '',
    judgeStdout: '',
    judgeStderr: verdict === 'failed' ? '权限应为 600' : '',
    diff: '> -rw------- 12 ./app/secrets.env',
    durationMs: 900,
    truncated: false,
    reason: verdict === 'undetermined' ? '超时（超过 15 秒）' : null,
    usedToday: 3,
    dailyLimit: 200,
    ...extra,
  })

  const shellSandbox = (result: ReturnType<typeof outcome>) => ({
    ready: async () => true,
    run: async () => {
      throw new Error('不该被调用')
    },
    runShell: async () => result,
  })

  it('没有端口时不渲染运行按钮', () => {
    renderBlock(<CodeChallenge code={JSON.stringify(SHELL)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
    })
    expect(screen.queryByTestId('challenge-run')).toBeNull()
    expect(screen.getByText(/没有接上执行能力/)).toBeTruthy()
  })

  it('通过时显示通过徽标与文件变化', async () => {
    renderBlock(<CodeChallenge code={JSON.stringify(SHELL)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: shellSandbox(outcome('passed')),
    })
    fireEvent.click(await screen.findByTestId('challenge-run'))
    await waitFor(() => expect(screen.getByText('通过')).toBeTruthy())
    expect(screen.getByTestId('challenge-shell-result').textContent).toContain('app/secrets.env')
  })

  it('未通过时把判定脚本的说明显示出来', async () => {
    renderBlock(<CodeChallenge code={JSON.stringify(SHELL)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: shellSandbox(outcome('failed')),
    })
    fireEvent.click(await screen.findByTestId('challenge-run'))
    await waitFor(() => expect(screen.getByText('未通过')).toBeTruthy())
    expect(screen.getByText(/权限应为 600/)).toBeTruthy()
  })

  it('判定脚本用 echo 写的说明也会显示（它落在 stdout 上）', async () => {
    // 2026-10-29 上线后实测发现的坑：作者用 `echo` 写说明时它走 stdout，
    // 只渲染 stderr 会让「未通过」变成没有理由的未通过
    renderBlock(<CodeChallenge code={JSON.stringify(SHELL)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: shellSandbox(outcome('failed', { judgeStderr: '', judgeStdout: '现在是 644，期望 600' })),
    })
    fireEvent.click(await screen.findByTestId('challenge-run'))
    await waitFor(() => expect(screen.getByText(/期望 600/)).toBeTruthy())
  })

  it('未能判定与未通过分开显示，并给出原因', async () => {
    renderBlock(<CodeChallenge code={JSON.stringify(SHELL)} isIncomplete={false} language="code-challenge" />, {
      entryId: 1,
      sandbox: shellSandbox(outcome('undetermined')),
    })
    fireEvent.click(await screen.findByTestId('challenge-run'))
    await waitFor(() => expect(screen.getByText('未能判定')).toBeTruthy())
    expect(screen.getByTestId('challenge-shell-reason').textContent).toContain('超时')
  })
})
