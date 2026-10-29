/**
 * 源码解读五种围栏块的数据契约与校验（WORK-014）。
 *
 * <p>**为什么解析集中在一个文件**：五种块都会被同一个 markdown 渲染器加载，作者写错时的
 * 报错口径必须一致；校验又是纯函数，能独立单测，不需要挂载 React。校验失败一律返回
 * `{ ok: false, error }`，由视图降级成"提示 + 原始文本"，**绝不抛到渲染树**。
 *
 * <p>上限都是**内容侧的天花板**，不是性能调优：块是作者手写的，超过上限通常意味着
 * "这段应该拆成两篇"。数值集中在这里，作者指南与报错共用同一份。
 */

export type ParseResult<T> = { ok: true; value: T } | { ok: false; error: string }

import type { SqlNormalize } from './sql-judge'

const LIMITS = {
  id: 64,
  title: 120,
  repo: 120,
  file: 300,
  license: 40,
  explain: 2000,
  codeLines: 200,
  codeChars: 20_000,
  steps: 20,
  focus: 20,
  mapNodes: 24,
  mapEdges: 48,
  mapLayer: 12,
  pathSteps: 20,
  quizQuestions: 10,
  quizOptions: 6,
  challengeLines: 60,
  outputLines: 30,
  sketchTitle: 40,
  sketchNodeId: 24,
  sketchNodeTitle: 16,
  sketchNodeSub: 28,
  sketchRows: 4,
  sketchRowNodes: 5,
  sketchNodes: 12,
  sketchArrows: 16,
  sketchLabel: 12,
} as const

class BadInput extends Error {}

function need(condition: unknown, message: string): asserts condition {
  if (!condition) {
    throw new BadInput(message)
  }
}

function asObject(value: unknown, where: string): Record<string, unknown> {
  need(value !== null && typeof value === 'object' && !Array.isArray(value), `${where} 必须是一个对象`)
  return value as Record<string, unknown>
}

function asText(value: unknown, where: string, max: number): string {
  need(typeof value === 'string', `${where} 必须是字符串`)
  const text = (value as string).trim()
  need(text !== '', `${where} 不能为空`)
  need(text.length <= max, `${where} 超过 ${max} 字符`)
  return text
}

function optionalText(value: unknown, where: string, max: number): string | undefined {
  if (value === undefined || value === null) {
    return undefined
  }
  return asText(value, where, max)
}

function asInteger(value: unknown, where: string, min: number, max: number): number {
  need(typeof value === 'number' && Number.isInteger(value), `${where} 必须是整数`)
  const number = value as number
  need(number >= min && number <= max, `${where} 必须在 ${min}–${max} 之间`)
  return number
}

function asArray(value: unknown, where: string, min: number, max: number): unknown[] {
  need(Array.isArray(value), `${where} 必须是数组`)
  const items = value as unknown[]
  need(items.length >= min && items.length <= max, `${where} 必须有 ${min}–${max} 项`)
  return items
}

function asStringArray(value: unknown, where: string, min: number, max: number, itemMax: number): string[] {
  return asArray(value, where, min, max).map((item, index) => asText(item, `${where}[${index}]`, itemMax))
}

function parseJson(code: string): unknown {
  try {
    return JSON.parse(code) as unknown
  } catch (cause) {
    throw new BadInput(`不是合法 JSON：${cause instanceof Error ? cause.message : String(cause)}`)
  }
}

function parseWith<T>(code: string, build: (raw: unknown) => T): ParseResult<T> {
  try {
    return { ok: true, value: build(parseJson(code)) }
  } catch (cause) {
    return { ok: false, error: cause instanceof Error ? cause.message : String(cause) }
  }
}

/* ------------------------------------------------------------------ 学习路径 */

export interface PathStep {
  entryId?: number
  title: string
  summary?: string
  minutes?: number
}

export interface LearningPath {
  title?: string
  steps: PathStep[]
}

export function parseLearningPath(code: string): ParseResult<LearningPath> {
  return parseWith(code, (input) => {
    const raw = asObject(input, '学习路径')
    const steps = asArray(raw['steps'], 'steps', 1, LIMITS.pathSteps).map((item, index) => {
      const step = asObject(item, `steps[${index}]`)
      const entryId = step['entryId'] === undefined ? undefined : asInteger(step['entryId'], `steps[${index}].entryId`, 1, Number.MAX_SAFE_INTEGER)
      return {
        entryId,
        title: asText(step['title'], `steps[${index}].title`, LIMITS.title),
        summary: optionalText(step['summary'], `steps[${index}].summary`, 200),
        minutes: step['minutes'] === undefined ? undefined : asInteger(step['minutes'], `steps[${index}].minutes`, 1, 600),
      }
    })
    return { title: optionalText(raw['title'], 'title', LIMITS.title), steps }
  })
}

/* ------------------------------------------------------------------ 架构地图 */

export interface MapNode {
  id: string
  label: string
  layer: number
  order: number
  file?: string
  line?: number
  tour?: string
  step?: number
  entryId?: number
}

export interface MapEdge {
  from: string
  to: string
  label?: string
}

export interface CodeMap {
  id?: string
  title?: string
  nodes: MapNode[]
  edges: MapEdge[]
}

export function parseCodeMap(code: string): ParseResult<CodeMap> {
  return parseWith(code, (input) => {
    const raw = asObject(input, '架构地图')
    const ids = new Set<string>()
    const nodes = asArray(raw['nodes'], 'nodes', 1, LIMITS.mapNodes).map((item, index) => {
      const node = asObject(item, `nodes[${index}]`)
      const id = asText(node['id'], `nodes[${index}].id`, LIMITS.id)
      need(!ids.has(id), `nodes[${index}].id 与前面的节点重复：${id}`)
      ids.add(id)
      return {
        id,
        label: asText(node['label'], `nodes[${index}].label`, LIMITS.title),
        layer: asInteger(node['layer'], `nodes[${index}].layer`, 0, LIMITS.mapLayer),
        order: asInteger(node['order'], `nodes[${index}].order`, 0, LIMITS.mapLayer),
        file: optionalText(node['file'], `nodes[${index}].file`, LIMITS.file),
        line: node['line'] === undefined ? undefined : asInteger(node['line'], `nodes[${index}].line`, 1, 100_000_000),
        tour: optionalText(node['tour'], `nodes[${index}].tour`, LIMITS.id),
        step: node['step'] === undefined ? undefined : asInteger(node['step'], `nodes[${index}].step`, 0, LIMITS.steps),
        entryId: node['entryId'] === undefined ? undefined : asInteger(node['entryId'], `nodes[${index}].entryId`, 1, Number.MAX_SAFE_INTEGER),
      }
    })
    const edges = asArray(raw['edges'] ?? [], 'edges', 0, LIMITS.mapEdges).map((item, index) => {
      const edge = asObject(item, `edges[${index}]`)
      const from = asText(edge['from'], `edges[${index}].from`, LIMITS.id)
      const to = asText(edge['to'], `edges[${index}].to`, LIMITS.id)
      need(ids.has(from), `edges[${index}].from 指向不存在的节点：${from}`)
      need(ids.has(to), `edges[${index}].to 指向不存在的节点：${to}`)
      return { from, to, label: optionalText(edge['label'], `edges[${index}].label`, 40) }
    })
    return { id: optionalText(raw['id'], 'id', LIMITS.id), title: optionalText(raw['title'], 'title', LIMITS.title), nodes, edges }
  })
}

/* ------------------------------------------------------------------ 代码走读 */

export interface Predict {
  question: string
  options: string[]
  answer: number
  reveal: string
}

export interface StateVar {
  name: string
  value: string
}

export interface HeapObject {
  id: string
  label: string
  fields?: Record<string, string>
}

export interface StepState {
  vars?: StateVar[]
  stack?: string[]
  heap?: HeapObject[]
  output?: string[]
}

export interface TourStep {
  title: string
  line: number
  code: string
  language?: string
  focus?: number[]
  explain: string
  predict?: Predict
  state?: StepState
}

export interface CodeTour {
  id: string
  title?: string
  repo: string
  commit: string
  license: string
  file: string
  steps: TourStep[]
}

export function lineCount(code: string): number {
  return code.split('\n').length
}

export function permalinkOf(tour: CodeTour, step: TourStep): string {
  const end = step.line + lineCount(step.code) - 1
  return `https://github.com/${tour.repo}/blob/${tour.commit}/${tour.file}#L${step.line}-L${end}`
}

function parsePredict(value: unknown, where: string): Predict {
  const raw = asObject(value, where)
  const options = asStringArray(raw['options'], `${where}.options`, 2, 4, 200)
  return {
    question: asText(raw['question'], `${where}.question`, 300),
    options,
    answer: asInteger(raw['answer'], `${where}.answer`, 0, options.length - 1),
    reveal: asText(raw['reveal'], `${where}.reveal`, 1000),
  }
}

function parseState(value: unknown, where: string): StepState {
  const raw = asObject(value, where)
  const vars = raw['vars'] === undefined
    ? undefined
    : asArray(raw['vars'], `${where}.vars`, 0, 30).map((item, index) => {
        const variable = asObject(item, `${where}.vars[${index}]`)
        return {
          name: asText(variable['name'], `${where}.vars[${index}].name`, 60),
          value: asText(variable['value'], `${where}.vars[${index}].value`, 200),
        }
      })
  const stack = raw['stack'] === undefined ? undefined : asStringArray(raw['stack'], `${where}.stack`, 0, 30, 200)
  const heap = raw['heap'] === undefined
    ? undefined
    : asArray(raw['heap'], `${where}.heap`, 0, 20).map((item, index) => {
        const object = asObject(item, `${where}.heap[${index}]`)
        const fields = object['fields'] === undefined ? undefined : asObject(object['fields'], `${where}.heap[${index}].fields`)
        const normalized: Record<string, string> = {}
        if (fields !== undefined) {
          for (const [key, field] of Object.entries(fields)) {
            normalized[key] = asText(field, `${where}.heap[${index}].fields.${key}`, 200)
          }
        }
        return {
          id: asText(object['id'], `${where}.heap[${index}].id`, 60),
          label: asText(object['label'], `${where}.heap[${index}].label`, 120),
          fields: fields === undefined ? undefined : normalized,
        }
      })
  const output = raw['output'] === undefined ? undefined : asStringArray(raw['output'], `${where}.output`, 0, LIMITS.outputLines, 300)
  return { vars, stack, heap, output }
}

export function parseCodeTour(code: string): ParseResult<CodeTour> {
  return parseWith(code, (input) => {
    const raw = asObject(input, '代码走读')
    const repo = asText(raw['repo'], 'repo', LIMITS.repo)
    need(/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo), 'repo 必须是 owner/name 形式')
    const commit = asText(raw['commit'], 'commit', 40)
    need(/^[0-9a-f]{7,40}$/.test(commit), 'commit 必须是 7–40 位小写十六进制（固定 commit，不跟分支）')
    const file = asText(raw['file'], 'file', LIMITS.file)
    need(!file.startsWith('/') && !file.includes('..'), 'file 必须是仓库内相对路径，且不能包含 ..')
    need(/^[A-Za-z0-9._/-]+$/.test(file), 'file 只能包含字母、数字、点、下划线、斜杠与连字符')

    const steps = asArray(raw['steps'], 'steps', 1, LIMITS.steps).map((item, index) => {
      const step = asObject(item, `steps[${index}]`)
      const stepCode = asText(step['code'], `steps[${index}].code`, LIMITS.codeChars)
      const lines = lineCount(stepCode)
      need(lines <= LIMITS.codeLines, `steps[${index}].code 超过 ${LIMITS.codeLines} 行，请拆成两步`)
      const focus = step['focus'] === undefined
        ? undefined
        : asArray(step['focus'], `steps[${index}].focus`, 0, LIMITS.focus).map((value, position) =>
            asInteger(value, `steps[${index}].focus[${position}]`, 1, lines),
          )
      return {
        title: asText(step['title'], `steps[${index}].title`, LIMITS.title),
        line: asInteger(step['line'], `steps[${index}].line`, 1, 100_000_000),
        code: stepCode,
        language: optionalText(step['language'], `steps[${index}].language`, 40),
        focus,
        explain: asText(step['explain'], `steps[${index}].explain`, LIMITS.explain),
        predict: step['predict'] === undefined ? undefined : parsePredict(step['predict'], `steps[${index}].predict`),
        state: step['state'] === undefined ? undefined : parseState(step['state'], `steps[${index}].state`),
      }
    })

    return {
      id: asText(raw['id'], 'id', LIMITS.id),
      title: optionalText(raw['title'], 'title', LIMITS.title),
      repo,
      commit,
      license: asText(raw['license'], 'license', LIMITS.license),
      file,
      steps,
    }
  })
}

/* ------------------------------------------------------------------ 动手练习 */

export interface FillChallenge {
  id: string
  kind: 'fill'
  title: string
  language?: string
  template: string
  options: string[][]
  answers: number[]
  explain?: string
}

export interface OrderChallenge {
  id: string
  kind: 'order'
  title: string
  lines: string[]
  answer: number[]
  explain?: string
}

export interface BugChallenge {
  id: string
  kind: 'bug'
  title: string
  language?: string
  code: string
  bugLine: number
  explain?: string
}

/**
 * 运行题型（WORK-015）：读者改代码、点运行，结果与用例逐条比对。
 *
 * <p>**用例在正文里**，因此"隐藏用例"并不存在——读者本来就看得到期望值。这不是缺陷：
 * 本项的目标是"能验证自己懂了"，不是考试防作弊；真正需要防泄漏的判定应该放到服务端，
 * 而那会引入题库表，与"练习长在正文里"的模型冲突。
 */
export interface RunCase {
  name: string
  stdin?: string
  expected: string
  /** 比较前是否 trim（默认 true）。输出带尾随空行时用它放宽。 */
  trim?: boolean
}

export interface RunChallenge {
  id: string
  kind: 'run'
  title: string
  /** `spring` = 整项目跑 `mvn test`（WORK-055）：必须配 `editablePath` 与 `files`。 */
  language: 'java' | 'python' | 'spring'
  /**
   * 项目模式下**学习者编辑的那份文件**的路径（相对项目根）。
   * java / python 缺省是 `Main.java` / `main.py`；spring 必须显式给，因为骨架里文件名不止一个。
   */
  editablePath?: string
  starter: string
  /**
   * 只读的支撑文件（WORK-038）：项目模式下与 `starter`（学习者编辑的那个文件）一起提交。
   * 典型用法是放测试入口，让学习者改自己的实现、跑固定的测试。
   *
   * <p>可编辑文件的路径按语言固定：Java 是 `Main.java`，Python 是 `main.py`——它是约定，
   * 不另开一个配置项。
   */
  files?: RunFile[]
  cases: RunCase[]
  explain?: string
}

/** 项目模式里的一个文件。路径只允许相对路径与 `[A-Za-z0-9_./-]`。 */
export interface RunFile {
  path: string
  content: string
}

export type CodeChallenge = FillChallenge | OrderChallenge | BugChallenge | RunChallenge | SqlChallenge

/**
 * SQL 写查询 / 改错（WORK-032）。
 *
 * <p>**期望值不在题面里**：它由参考解在数据集上真跑得出，题面只带 `expectHash`
 * （规范序列化的 SHA-256）。客户端把自己跑出的结果按同一口径规范化后比哈希——
 * 这样隐藏数据集的内容与答案都不必下发，判题仍留在客户端（服务端不认识"正确答案"，
 * 与 WORK-015 同一条纪律）。
 *
 * <p>`antiPatterns` / `alternatives` 只在内容源里给门禁用，不随题面下发。
 */
export interface SqlChallenge {
  id: string
  kind: 'sql-write' | 'sql-fix'
  title: string
  /** 这题考什么认知，一句话。 */
  goal?: string
  /** 对应的本课误区标题（内容门禁用它防超纲）。 */
  trap?: string
  /** 可用于本题的数据集 key。客户端只拿到 key，内容在服务端。 */
  dataset: string[]
  /** 编辑器初始内容：`sql-write` 是骨架，`sql-fix` 是那段写坏的 SQL。 */
  starter: string
  /** 归一化口径，与 `sql-judge.ts` / `sql_judge.py` 同构。 */
  normalize?: SqlNormalize
  /** 数据集 key → 规范序列化哈希。 */
  expectHash: Record<string, string>
  explain?: string
}

export function blankCount(template: string): number {
  return [...template.matchAll(/\{\{(\d+)\}\}/g)].length
}

export function parseCodeChallenge(code: string): ParseResult<CodeChallenge> {
  return parseWith(code, (input) => {
    const raw = asObject(input, '动手练习')
    const id = asText(raw['id'], 'id', LIMITS.id)
    const title = asText(raw['title'], 'title', LIMITS.title)
    const explain = optionalText(raw['explain'], 'explain', LIMITS.explain)
    const kind = asText(raw['kind'], 'kind', 20)

    if (kind === 'fill') {
      const template = asText(raw['template'], 'template', LIMITS.codeChars)
      const options = asArray(raw['options'], 'options', 1, LIMITS.focus).map((item, index) =>
        asStringArray(item, `options[${index}]`, 2, 8, 120),
      )
      const answers = asArray(raw['answers'], 'answers', 1, LIMITS.focus).map((item, index) =>
        asInteger(item, `answers[${index}]`, 0, options[index] === undefined ? 0 : options[index].length - 1),
      )
      need(options.length === answers.length, 'options 与 answers 的项数必须一致')
      need(blankCount(template) === options.length, `template 里的空格数（${blankCount(template)}）必须与 options 的项数（${options.length}）一致`)
      return { id, kind, title, language: optionalText(raw['language'], 'language', 40), template, options, answers, explain }
    }

    if (kind === 'order') {
      const lines = asStringArray(raw['lines'], 'lines', 2, LIMITS.challengeLines, 300)
      const answer = asArray(raw['answer'], 'answer', lines.length, lines.length).map((item, index) =>
        asInteger(item, `answer[${index}]`, 0, lines.length - 1),
      )
      const sorted = [...answer].sort((a, b) => a - b)
      need(sorted.every((value, index) => value === index), 'answer 必须是 0…n-1 的一个排列')
      return { id, kind, title, lines, answer, explain }
    }

    if (kind === 'run') {
      const language = asText(raw['language'], 'language', 16).toLowerCase()
      need(language === 'java' || language === 'python' || language === 'spring',
        'language 只能是 java / python / spring')
      const editablePath = raw['editablePath'] === undefined
        ? undefined
        : asText(raw['editablePath'], 'editablePath', 200)
      if (editablePath !== undefined) {
        need(/^[A-Za-z0-9_./-]+$/.test(editablePath) && !editablePath.startsWith('/')
          && !editablePath.includes('..'), `editablePath 不合法：${editablePath}`)
      }
      // spring 必须给路径与支撑文件：骨架在镜像里，缺任一项都会"跑起来但什么都没验"
      if (language === 'spring') {
        need(editablePath !== undefined, 'spring 关卡必须写 editablePath（学习者改哪个文件）')
        need(raw['files'] !== undefined, 'spring 关卡必须写 files（测试文件等支撑文件）')
      }
      const starter = asText(raw['starter'], 'starter', LIMITS.codeChars)
      const cases = asArray(raw['cases'], 'cases', 1, 10).map((item, index) => {
        const entry = asObject(item, `cases[${index}]`)
        return {
          name: asText(entry['name'], `cases[${index}].name`, 80),
          stdin: optionalText(entry['stdin'], `cases[${index}].stdin`, 4000),
          expected: typeof entry['expected'] === 'string' ? entry['expected'] : '',
          trim: entry['trim'] === undefined ? true : Boolean(entry['trim']),
        }
      })
      // 项目模式（WORK-038）：可选的多文件。路径要能被拼进容器的写文件脚本，
      // 所以这里就按服务端同一条口径挡住（不乱码、不含 `..`、不以 / 开头）。
      const files = raw['files'] === undefined ? undefined : asArray(raw['files'], 'files', 1, 20).map(
        (item, index) => {
          const entry = asObject(item, `files[${index}]`)
          const path = asText(entry['path'], `files[${index}].path`, 200)
          need(/^[A-Za-z0-9_./-]+$/.test(path) && !path.startsWith('/') && !path.includes('..'),
            `files[${index}].path 不合法：${path}`)
          return { path, content: typeof entry['content'] === 'string' ? entry['content'] : '' }
        },
      )
      return {
        id, kind, title, language: language as 'java' | 'python' | 'spring',
        editablePath, starter, files, cases, explain,
      }
    }

    if (kind === 'bug') {
      const codeText = asText(raw['code'], 'code', LIMITS.codeChars)
      const lines = lineCount(codeText)
      need(lines <= LIMITS.challengeLines, `code 超过 ${LIMITS.challengeLines} 行`)
      return {
        id,
        kind,
        title,
        language: optionalText(raw['language'], 'language', 40),
        code: codeText,
        bugLine: asInteger(raw['bugLine'], 'bugLine', 1, lines),
        explain,
      }
    }

    if (kind === 'sql-write' || kind === 'sql-fix') {
      const starter = asText(raw['starter'], 'starter', LIMITS.codeChars)
      const dataset = asStringArray(raw['dataset'], 'dataset', 1, LIMITS.focus, 64)
      const expectHash = asObject(raw['expectHash'], 'expectHash')
      const normalize = asObject(raw['normalize'] ?? {}, 'normalize')
      const rowOrder = normalize['rowOrder']
      const columnOrder = normalize['columnOrder']
      need(rowOrder === undefined || rowOrder === 'ignore' || rowOrder === 'strict',
        'normalize.rowOrder 只能是 ignore 或 strict')
      need(columnOrder === undefined || columnOrder === 'ignore' || columnOrder === 'strict',
        'normalize.columnOrder 只能是 ignore 或 strict')
      const types: Record<string, import('./sql-judge').SqlColumnType> = {}
      const rawTypes = asObject(normalize['types'] ?? {}, 'normalize.types')
      for (const [name, value] of Object.entries(rawTypes)) {
        need(value === 'string' || value === 'int' || value === 'decimal' || value === 'datetime',
          `normalize.types.${name} 只能是 string / int / decimal / datetime（不支持 float：误差会让判题不稳）`)
        types[name] = value as import('./sql-judge').SqlColumnType
      }
      const hashes: Record<string, string> = {}
      for (const key of dataset) {
        // 每个数据集都必须有期望值哈希，否则那一个数据集无法判题
        hashes[key] = asText(expectHash[key], `expectHash.${key}`, 128)
      }
      return {
        id,
        kind,
        title,
        goal: optionalText(raw['goal'], 'goal', 300),
        trap: optionalText(raw['trap'], 'trap', 300),
        dataset,
        starter,
        normalize: { rowOrder, columnOrder, types } as SqlNormalize,
        expectHash: hashes,
        explain,
      }
    }

    throw new BadInput('kind 只能是 fill / order / bug / run / sql-write / sql-fix')
  })
}

export function judgeFill(challenge: FillChallenge, selected: number[]): boolean {
  return selected.length === challenge.answers.length && selected.every((value, index) => value === challenge.answers[index])
}

export function judgeOrder(challenge: OrderChallenge, order: number[]): boolean {
  return order.length === challenge.answer.length && order.every((value, index) => value === challenge.answer[index])
}

export function judgeBug(challenge: BugChallenge, line: number): boolean {
  return line === challenge.bugLine
}

/**
 * 运行题的单用例判定。
 *
 * <p>退出码非 0 一律算失败——编译错误与运行时崩溃都不该被"输出碰巧一致"蒙过去。
 * 超时与 OOM 同理：它们是结果里的失败态，不是可比较的输出。
 */
export function judgeRun(testCase: RunCase, output: {
  stdout: string
  exitCode: number
  timedOut: boolean
  outOfMemory: boolean
}): boolean {
  if (output.exitCode !== 0 || output.timedOut || output.outOfMemory) {
    return false
  }
  const actual = testCase.trim === false ? output.stdout : output.stdout.trim()
  const expected = testCase.trim === false ? testCase.expected : testCase.expected.trim()
  return actual === expected
}

/* ------------------------------------------------------------------ 小测 */

export interface QuizQuestion {
  question: string
  options: string[]
  answer: number
  explain?: string
}

export interface Quiz {
  id?: string
  title?: string
  questions: QuizQuestion[]
}

export function parseQuiz(code: string): ParseResult<Quiz> {
  return parseWith(code, (input) => {
    const raw = asObject(input, '小测')
    const questions = asArray(raw['questions'], 'questions', 1, LIMITS.quizQuestions).map((item, index) => {
      const question = asObject(item, `questions[${index}]`)
      const options = asStringArray(question['options'], `questions[${index}].options`, 2, LIMITS.quizOptions, 200)
      return {
        question: asText(question['question'], `questions[${index}].question`, 300),
        options,
        answer: asInteger(question['answer'], `questions[${index}].answer`, 0, options.length - 1),
        explain: optionalText(question['explain'], `questions[${index}].explain`, 1000),
      }
    })
    return { id: optionalText(raw['id'], 'id', LIMITS.id), title: optionalText(raw['title'], 'title', LIMITS.title), questions }
  })
}

export function scoreQuiz(quiz: Quiz, answers: (number | null)[]): { correct: number; total: number } {
  return {
    correct: quiz.questions.filter((question, index) => answers[index] === question.answer).length,
    total: quiz.questions.length,
  }
}

/* ------------------------------------------------------------------ 手绘图 */

export type SketchTone = 'gray' | 'blue' | 'amber' | 'green' | 'violet'

export interface SketchNode {
  id: string
  title: string
  sub?: string
  tone: SketchTone
}

export interface SketchArrow {
  from: string
  to: string
  label?: string
  /** `back` 从图下方绕回来，用于"下一步又回到开头"这类循环。 */
  style: 'straight' | 'back'
}

export interface Sketch {
  title?: string
  rows: SketchNode[][]
  arrows: SketchArrow[]
}

export const SKETCH_TONES: readonly SketchTone[] = ['gray', 'blue', 'amber', 'green', 'violet']

/**
 * 手绘图（WORK-051）。坐标不在契约里：框宽由文字量出来，行由布局算——作者只写"有哪些框、谁连谁"。
 * 上限是内容侧天花板：超过 4 行 12 框通常意味着该拆成两张图。
 */
export function parseSketch(code: string): ParseResult<Sketch> {
  return parseWith(code, (input) => {
    const raw = asObject(input, '手绘图')
    const rows = asArray(raw['rows'], 'rows', 1, LIMITS.sketchRows).map((row, rowIndex) => {
      const where = `rows[${rowIndex}]`
      return asArray(row, where, 1, LIMITS.sketchRowNodes).map((item, index) => {
        const node = asObject(item, `${where}[${index}]`)
        const tone = node['tone'] ?? 'gray'
        need(
          typeof tone === 'string' && SKETCH_TONES.includes(tone as SketchTone),
          `${where}[${index}].tone 只能是 ${SKETCH_TONES.join(' / ')}`,
        )
        return {
          id: asText(node['id'], `${where}[${index}].id`, LIMITS.sketchNodeId),
          title: asText(node['title'], `${where}[${index}].title`, LIMITS.sketchNodeTitle),
          sub: optionalText(node['sub'], `${where}[${index}].sub`, LIMITS.sketchNodeSub),
          tone: tone as SketchTone,
        }
      })
    })

    const nodes = rows.flat()
    need(nodes.length <= LIMITS.sketchNodes, `一张图最多 ${LIMITS.sketchNodes} 个框，当前 ${nodes.length} 个`)
    const ids = new Set<string>()
    for (const node of nodes) {
      need(!ids.has(node.id), `节点 id 重复：「${node.id}」`)
      ids.add(node.id)
    }

    const arrows = (raw['arrows'] === undefined ? [] : asArray(raw['arrows'], 'arrows', 0, LIMITS.sketchArrows)).map(
      (item, index) => {
        const where = `arrows[${index}]`
        const arrow = asObject(item, where)
        const from = asText(arrow['from'], `${where}.from`, LIMITS.sketchNodeId)
        const to = asText(arrow['to'], `${where}.to`, LIMITS.sketchNodeId)
        need(ids.has(from), `${where}.from 指向不存在的节点「${from}」`)
        need(ids.has(to), `${where}.to 指向不存在的节点「${to}」`)
        const style = arrow['style'] ?? 'straight'
        need(style === 'straight' || style === 'back', `${where}.style 只能是 straight / back`)
        return {
          from,
          to,
          label: optionalText(arrow['label'], `${where}.label`, LIMITS.sketchLabel),
          style: style as 'straight' | 'back',
        }
      },
    )

    return { title: optionalText(raw['title'], 'title', LIMITS.sketchTitle), rows, arrows }
  })
}
