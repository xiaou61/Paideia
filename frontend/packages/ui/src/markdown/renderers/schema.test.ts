import { describe, expect, it } from 'vitest'

import {
  blankCount,
  judgeBug,
  judgeFill,
  judgeOrder,
  judgeRun,
  lineCount,
  parseCodeChallenge,
  parseCodeMap,
  parseCodeTour,
  parseLearningPath,
  parseQuiz,
  parseSourceWalk,
  permalinkOf,
  scoreQuiz,
  visibleSteps,
  walkDefaults,
  walkPermalink,
} from './schema'

/**
 * 解析与判定的边界。这里**不测"渲染出来好不好看"**——那是组件测试的事。
 * 重点是可预期的拒绝：作者写错时得到的是一条能定位到字段的错误，而不是白屏。
 */

const TOUR = {
  id: 'demo',
  repo: 'facebook/react',
  commit: 'd75b0697',
  license: 'MIT',
  file: 'packages/react-reconciler/src/ReactFiberWorkLoop.js',
  steps: [
    {
      title: '进入',
      line: 100,
      code: 'const root = prepare();\nworkLoop(root);',
      language: 'javascript',
      focus: [1],
      explain: '先准备再循环。',
    },
  ],
}

describe('parseCodeTour', () => {
  it('接受合法输入并算出行范围', () => {
    const result = parseCodeTour(JSON.stringify(TOUR))
    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }
    expect(result.value.steps[0]?.line).toBe(100)
    expect(permalinkOf(result.value, result.value.steps[0]!)).toBe(
      'https://github.com/facebook/react/blob/d75b0697/packages/react-reconciler/src/ReactFiberWorkLoop.js#L100-L101',
    )
  })

  it('拒绝非 JSON 并给出可读原因', () => {
    const result = parseCodeTour('{oops')
    expect(result.ok).toBe(false)
    if (result.ok) {
      return
    }
    expect(result.error).toContain('JSON')
  })

  it('拒绝跟随分支的 commit（必须是固定小写十六进制）', () => {
    const result = parseCodeTour(JSON.stringify({ ...TOUR, commit: 'main' }))
    expect(result.ok).toBe(false)
    if (result.ok) {
      return
    }
    expect(result.error).toContain('commit')
  })

  it('拒绝越界的 focus（块内行号必须落在代码里）', () => {
    const broken = { ...TOUR, steps: [{ ...TOUR.steps[0], focus: [5] }] }
    const result = parseCodeTour(JSON.stringify(broken))
    expect(result.ok).toBe(false)
  })

  it('拒绝超长代码与空 steps', () => {
    expect(parseCodeTour(JSON.stringify({ ...TOUR, steps: [] })).ok).toBe(false)
    const long = { ...TOUR, steps: [{ ...TOUR.steps[0], code: 'x\n'.repeat(201) }] }
    expect(parseCodeTour(JSON.stringify(long)).ok).toBe(false)
  })

  it('拒绝 predict 的越界答案', () => {
    const broken = {
      ...TOUR,
      steps: [
        {
          ...TOUR.steps[0],
          predict: { question: '为什么？', options: ['A', 'B'], answer: 3, reveal: '因为 A' },
        },
      ],
    }
    expect(parseCodeTour(JSON.stringify(broken)).ok).toBe(false)
  })

  it('拒绝 file 里的路径穿越', () => {
    expect(parseCodeTour(JSON.stringify({ ...TOUR, file: '../etc/passwd' })).ok).toBe(false)
    expect(parseCodeTour(JSON.stringify({ ...TOUR, file: '/etc/passwd' })).ok).toBe(false)
  })
})

describe('parseCodeMap', () => {
  it('接受节点与边，并拒绝指向不存在节点的边', () => {
    const ok = parseCodeMap(
      JSON.stringify({
        nodes: [{ id: 'a', label: 'A', layer: 0, order: 0 }],
        edges: [],
      }),
    )
    expect(ok.ok).toBe(true)

    const broken = parseCodeMap(
      JSON.stringify({
        nodes: [{ id: 'a', label: 'A', layer: 0, order: 0 }],
        edges: [{ from: 'a', to: 'missing' }],
      }),
    )
    expect(broken.ok).toBe(false)
    if (broken.ok) {
      return
    }
    expect(broken.error).toContain('missing')
  })

  it('拒绝重复的节点 id', () => {
    const result = parseCodeMap(
      JSON.stringify({
        nodes: [
          { id: 'a', label: 'A', layer: 0, order: 0 },
          { id: 'a', label: 'B', layer: 0, order: 1 },
        ],
        edges: [],
      }),
    )
    expect(result.ok).toBe(false)
  })
})

describe('parseLearningPath', () => {
  it('entryId 可以缺省（该站不可点击）', () => {
    const result = parseLearningPath(JSON.stringify({ steps: [{ title: '总览' }] }))
    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }
    expect(result.value.steps[0]?.entryId).toBeUndefined()
  })

  it('拒绝空路径', () => {
    expect(parseLearningPath(JSON.stringify({ steps: [] })).ok).toBe(false)
  })
})

describe('parseCodeChallenge', () => {
  it('填空的空格数必须与选项组一致', () => {
    const ok = parseCodeChallenge(
      JSON.stringify({
        id: 'f',
        kind: 'fill',
        title: '补全',
        template: 'a({{0}});\nb({{1}});',
        options: [['x', 'y'], ['z', 'w']],
        answers: [0, 0],
      }),
    )
    expect(ok.ok).toBe(true)

    const broken = parseCodeChallenge(
      JSON.stringify({
        id: 'f',
        kind: 'fill',
        title: '补全',
        template: 'a({{0}});',
        options: [['x'], ['y']],
        answers: [0, 0],
      }),
    )
    expect(broken.ok).toBe(false)
  })

  it('排序的 answer 必须是完整排列', () => {
    expect(
      parseCodeChallenge(JSON.stringify({ id: 'o', kind: 'order', title: '排序', lines: ['a', 'b'], answer: [1, 0] })).ok,
    ).toBe(true)
    expect(
      parseCodeChallenge(JSON.stringify({ id: 'o', kind: 'order', title: '排序', lines: ['a', 'b'], answer: [0, 1, 2] })).ok,
    ).toBe(false)
  })

  it('找 bug 的行号必须落在代码里', () => {
    expect(
      parseCodeChallenge(JSON.stringify({ id: 'b', kind: 'bug', title: '找 bug', code: 'a\nb', bugLine: 2 })).ok,
    ).toBe(true)
    expect(
      parseCodeChallenge(JSON.stringify({ id: 'b', kind: 'bug', title: '找 bug', code: 'a\nb', bugLine: 9 })).ok,
    ).toBe(false)
  })

  it('判定函数与题干一致', () => {
    const fill = { id: 'f', kind: 'fill' as const, title: 't', template: '{{0}}', options: [['a', 'b']], answers: [1] }
    expect(judgeFill(fill, [1])).toBe(true)
    expect(judgeFill(fill, [0])).toBe(false)
    expect(judgeFill(fill, [])).toBe(false)

    const order = { id: 'o', kind: 'order' as const, title: 't', lines: ['a', 'b'], answer: [1, 0] }
    expect(judgeOrder(order, [1, 0])).toBe(true)
    expect(judgeOrder(order, [0, 1])).toBe(false)

    const bug = { id: 'b', kind: 'bug' as const, title: 't', code: 'a\nb', bugLine: 2 }
    expect(judgeBug(bug, 2)).toBe(true)
    expect(judgeBug(bug, 1)).toBe(false)
  })

  it('未知题型被拒绝', () => {
    expect(parseCodeChallenge(JSON.stringify({ id: 'x', kind: 'run', title: 't' })).ok).toBe(false)
  })
})

describe('parseQuiz', () => {
  const quiz = {
    id: 'q1',
    questions: [
      { question: '1+1?', options: ['1', '2'], answer: 1, explain: '当然是 2' },
      { question: '天空?', options: ['蓝', '绿'], answer: 0 },
    ],
  }

  it('接受合法小测并算分', () => {
    const result = parseQuiz(JSON.stringify(quiz))
    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }
    expect(scoreQuiz(result.value, [1, 0])).toEqual({ correct: 2, total: 2 })
    expect(scoreQuiz(result.value, [0, null])).toEqual({ correct: 0, total: 2 })
  })

  it('拒绝越界答案与超量题目', () => {
    expect(parseQuiz(JSON.stringify({ questions: [{ question: 'q', options: ['a'], answer: 0 }] })).ok).toBe(false)
    const many = { questions: Array.from({ length: 11 }, () => ({ question: 'q', options: ['a', 'b'], answer: 0 })) }
    expect(parseQuiz(JSON.stringify(many)).ok).toBe(false)
  })
})

describe('工具函数', () => {
  it('lineCount 与 blankCount', () => {
    expect(lineCount('a')).toBe(1)
    expect(lineCount('a\nb')).toBe(2)
    expect(blankCount('{{0}} + {{1}}')).toBe(2)
    expect(blankCount('no blanks')).toBe(0)
  })
})

describe('parseCodeChallenge · run（WORK-015）', () => {
  const RUN = {
    id: 'r1',
    kind: 'run',
    title: '打印',
    language: 'python',
    starter: 'print(1)',
    cases: [{ name: '样例', expected: '1' }],
  }

  it('接受合法运行题', () => {
    const result = parseCodeChallenge(JSON.stringify(RUN))
    expect(result.ok).toBe(true)
    if (!result.ok || result.value.kind !== 'run') {
      return
    }
    expect(result.value.cases[0]?.trim).toBe(true)
  })

  it('只允许 java / python', () => {
    expect(parseCodeChallenge(JSON.stringify({ ...RUN, language: 'ruby' })).ok).toBe(false)
  })

  it('至少要有一个用例', () => {
    expect(parseCodeChallenge(JSON.stringify({ ...RUN, cases: [] })).ok).toBe(false)
  })

  it('判定：输出相符且退出码 0 才算通过', () => {
    const testCase = { name: 't', expected: '42' }
    expect(judgeRun(testCase, { stdout: '42\n', exitCode: 0, timedOut: false, outOfMemory: false })).toBe(true)
    expect(judgeRun(testCase, { stdout: '42\n', exitCode: 1, timedOut: false, outOfMemory: false })).toBe(false)
    expect(judgeRun(testCase, { stdout: '42\n', exitCode: 0, timedOut: true, outOfMemory: false })).toBe(false)
    expect(judgeRun(testCase, { stdout: '43', exitCode: 0, timedOut: false, outOfMemory: false })).toBe(false)
  })

  it('trim:false 时逐字符比较', () => {
    const strict = { name: 't', expected: '42', trim: false }
    expect(judgeRun(strict, { stdout: '42\n', exitCode: 0, timedOut: false, outOfMemory: false })).toBe(false)
    expect(judgeRun(strict, { stdout: '42', exitCode: 0, timedOut: false, outOfMemory: false })).toBe(true)
  })
})

/* ---------------------------------------------------------------- 源码精读（WORK-057） */

const WALK = {
  id: 'demo-walk',
  repo: 'mybatis/mybatis-3',
  ref: 'mybatis-3.5.19',
  commit: 'ee0d4f4831ffdd311b0183c202f4ad6492a3f404',
  license: 'Apache-2.0',
  base: 'source/mybatis-3.5.19',
  files: [
    { path: 'org/apache/ibatis/binding/MapperProxy.java', label: 'MapperProxy' },
    { path: 'org/apache/ibatis/binding/MapperMethod.java', label: 'MapperMethod' },
  ],
  controls: [
    { id: 'cache', type: 'toggle', label: '开启一级缓存', default: true },
    {
      id: 'method',
      type: 'select',
      label: '调哪个方法',
      default: 'byId',
      options: [
        { value: 'byId', label: '查 id' },
        { value: 'byName', label: '查 name' },
      ],
    },
  ],
  steps: [
    { title: '入口', file: 'org/apache/ibatis/binding/MapperProxy.java', line: 62, lines: 8, explain: '入口。' },
    {
      title: '缓存命中',
      file: 'org/apache/ibatis/binding/MapperProxy.java',
      line: 70,
      lines: 4,
      explain: '走缓存。',
      when: { cache: true },
    },
    {
      title: '查 id',
      file: 'org/apache/ibatis/binding/MapperMethod.java',
      line: 40,
      lines: 6,
      explain: '按 id。',
      when: { method: 'byId' },
    },
    {
      title: '查 name',
      file: 'org/apache/ibatis/binding/MapperMethod.java',
      line: 50,
      lines: 6,
      explain: '按 name。',
      when: { method: 'byName' },
      refs: [{ label: '回到代理', file: 'org/apache/ibatis/binding/MapperProxy.java', line: 62 }],
    },
  ],
}

describe('parseSourceWalk（WORK-057）', () => {
  it('合法输入解析出文件、控件与步骤', () => {
    const parsed = parseSourceWalk(JSON.stringify(WALK))
    expect(parsed.ok).toBe(true)
    if (!parsed.ok) return
    expect(parsed.value.files).toHaveLength(2)
    expect(parsed.value.controls).toHaveLength(2)
    expect(parsed.value.repoPath).toBe('src/main/java')
  })

  it('缺 files 或步骤指向未声明的文件都拒绝', () => {
    expect(parseSourceWalk(JSON.stringify({ ...WALK, files: [] })).ok).toBe(false)
    const bad = { ...WALK, steps: [{ ...WALK.steps[0], file: 'nope/Nope.java' }] }
    expect(parseSourceWalk(JSON.stringify(bad)).ok).toBe(false)
  })

  it('只接受相对路径：绝对路径与 .. 都拒绝', () => {
    expect(parseSourceWalk(JSON.stringify({ ...WALK, base: '/etc' })).ok).toBe(false)
    expect(parseSourceWalk(JSON.stringify({ ...WALK, base: 'source/../../etc' })).ok).toBe(false)
  })

  it('when 只能引用已声明的控件，且类型要一致', () => {
    const unknown = { ...WALK, steps: [{ ...WALK.steps[0], when: { nope: true } }] }
    expect(parseSourceWalk(JSON.stringify(unknown)).ok).toBe(false)
    const wrongType = { ...WALK, steps: [{ ...WALK.steps[0], when: { cache: 'yes' } }] }
    expect(parseSourceWalk(JSON.stringify(wrongType)).ok).toBe(false)
  })

  it('select 的默认值必须在 options 里', () => {
    const bad = {
      ...WALK,
      controls: [{ ...WALK.controls[1], default: 'byAge' }],
    }
    expect(parseSourceWalk(JSON.stringify(bad)).ok).toBe(false)
  })

  it('行区间必须为正数', () => {
    const bad = { ...WALK, steps: [{ ...WALK.steps[0], lines: 0 }] }
    expect(parseSourceWalk(JSON.stringify(bad)).ok).toBe(false)
  })
})

describe('visibleSteps（WORK-057）', () => {
  const walk = parseSourceWalk(JSON.stringify(WALK))
  if (!walk.ok) throw new Error(walk.error)

  it('默认值下：缓存步与 byId 分支可见，byName 不可见', () => {
    const steps = visibleSteps(walk.value, walkDefaults(walk.value))
    expect(steps.map((step) => step.title)).toEqual(['入口', '缓存命中', '查 id'])
  })

  it('关掉缓存后步骤真的变少；切到 byName 后分支真的换掉', () => {
    const noCache = visibleSteps(walk.value, { cache: false, method: 'byId' })
    expect(noCache.map((step) => step.title)).toEqual(['入口', '查 id'])
    const byName = visibleSteps(walk.value, { cache: true, method: 'byName' })
    expect(byName.map((step) => step.title)).toEqual(['入口', '缓存命中', '查 name'])
  })

  it('permalink 拼上 repoPath 与行区间', () => {
    expect(walkPermalink(walk.value, 'org/apache/ibatis/binding/MapperProxy.java', 62, 8)).toBe(
      'https://github.com/mybatis/mybatis-3/blob/ee0d4f4831ffdd311b0183c202f4ad6492a3f404/src/main/java/org/apache/ibatis/binding/MapperProxy.java#L62-L69',
    )
  })
})
