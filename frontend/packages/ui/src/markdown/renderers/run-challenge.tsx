import { Play } from 'lucide-react'
import { Suspense, lazy, useEffect, useState } from 'react'

import { Button } from '../../components/button'
import { Pill } from './blocks'
import { useSourceReading, type RunOutput, type TraceResult } from './context'
import { judgeRun, type RunChallenge, type RunCase } from './schema'
import { TracePlayer } from './trace-player'

/**
 * 带高亮与补全的代码编辑器（CodeMirror）。
 *
 * <p><b>必须懒加载</b>：本文件在主包里，而编辑器连同 Java/Python 语法约 100 kB gzip。
 * 静态 import 会让每个进首页的人为它付费，构建守卫 `scripts/check-lazy-editor.mjs` 会拦下。
 */
const CodeEditor = lazy(async () => {
  const module = await import('../../code-editor/editor')
  return { default: module.CodeEditor }
})

/** 预热编辑器 chunk：与沙箱探测并行，等用户真要改代码时通常已经下载完。 */
function preloadEditor(): void {
  void import('../../code-editor/editor')
}

/**
 * 运行题型（WORK-015）。
 *
 * <p><b>它自己不判题</b>：把代码发给沙箱、拿回实际输出，再用正文里的用例逐条比对。
 * 这样服务端不需要题库，也不需要知道"正确答案"。
 *
 * <p>**能力不探测就不渲染按钮**：管理端预览、后端没开沙箱、Docker 不在——三种情况都只显示代码。
 * 渲染一个必然失败的按钮比不渲染更糟：用户会以为是自己点错了。
 *
 * <p>`ready` 是异步的：探测期间按钮禁用并显示"检查运行环境…"，探测完成后才决定出还是不出。
 */
export function RunBody({
  challenge,
  onGraded,
}: {
  challenge: RunChallenge
  onGraded: (correct: number, total: number) => void
}) {
  const { sandbox } = useSourceReading()
  const [ready, setReady] = useState<boolean | null>(null)
  const [code, setCode] = useState(challenge.starter)
  const [running, setRunning] = useState(false)
  const [results, setResults] = useState<{ testCase: RunCase; output: RunOutput; passed: boolean }[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 逐步录制（WORK-023）。**只在 Python 且端口实现了 trace 时才出现按钮**：
  // 渲染一个点了会说"不支持"的按钮比不渲染更糟。
  const [trace, setTrace] = useState<TraceResult | null>(null)
  const [tracing, setTracing] = useState(false)
  const canTrace = sandbox !== undefined && typeof sandbox.trace === 'function'
    && challenge.language === 'python'

  useEffect(() => {
    if (sandbox === undefined) {
      setReady(false)
      return
    }
    preloadEditor()
    let alive = true
    sandbox
      .ready()
      .then((value) => {
        if (alive) {
          setReady(value)
        }
      })
      .catch(() => {
        // 探测失败按"不可用"处理：这时给一个按钮只会让用户白点
        if (alive) {
          setReady(false)
        }
      })
    return () => {
      alive = false
    }
  }, [sandbox])

  const run = async () => {
    if (sandbox === undefined) {
      return
    }
    setRunning(true)
    setError(null)
    setResults(null)
    try {
      // 逐用例串行跑：并发会把全局并发闸占满，用户自己的第二个用例反而被拒
      const collected: { testCase: RunCase; output: RunOutput; passed: boolean }[] = []
      // 项目模式（WORK-038）：可编辑文件的路径按语言固定，其余文件原样带上。
      // 支撑文件里若也写了这个路径，以编辑器内容为准——否则学习者改的不是被执行的那份。
      const editablePath = challenge.editablePath
        ?? (challenge.language === 'python' ? 'main.py' : 'Main.java')
      const files = challenge.files === undefined
        ? undefined
        : [
            ...challenge.files.filter((file) => file.path !== editablePath),
            { path: editablePath, content: code },
          ]
      for (const testCase of challenge.cases) {
        const output = await sandbox.run({
          language: challenge.language,
          code,
          stdin: testCase.stdin,
          files,
          caseId: `${challenge.id}:${testCase.name}`,
        })
        // Spring 项目判分看退出码：输出里混着 mvn 日志，逐字比 expected 不可靠；
        // `mvn test` 有失败用例时退出码非 0（镜像里的脚本会再把汇总打出来）。
        const passed = challenge.language === 'spring' ? output.exitCode === 0 : judgeRun(testCase, output)
        collected.push({ testCase, output, passed })
      }
      setResults(collected)
      const passed = collected.filter((item) => item.passed).length
      onGraded(passed, collected.length)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setRunning(false)
    }
  }

  const startTrace = async () => {
    if (sandbox?.trace === undefined) {
      return
    }
    setTracing(true)
    setError(null)
    setTrace(null)
    try {
      setTrace(await sandbox.trace({
        language: challenge.language,
        code,
        caseId: `${challenge.id}:trace`,
      }))
    } catch (cause) {
      // 降级：录制不可用时退回"一次性运行"，把原因说清楚而不是静默失败
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setTracing(false)
    }
  }

  if (ready === false) {
    return (
      <div className="space-y-2">
        <p className="text-sm">这一题可以改代码，但当前环境没有启用「运行」。</p>
        <p className="text-muted-foreground text-xs">（练习的其它题型不受影响。）</p>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <p className="text-muted-foreground text-xs">
        改完点「运行」：会在一次性的隔离环境里执行，没有网络、内存与时间都有上限。
      </p>
      <Suspense
        fallback={
          // 编辑器 chunk 还没到时先给可编辑的 textarea：能立刻开始改，到达后无缝换成高亮版
          <textarea
            value={code}
            onChange={(event) => setCode(event.target.value)}
            rows={Math.min(20, Math.max(8, code.split('\n').length + 1))}
            spellCheck={false}
            aria-label="练习代码"
            data-testid="challenge-code"
            className="border-border bg-muted/30 w-full resize-y rounded-lg border p-3 font-mono text-[12.5px] leading-6 outline-none"
          />
        }
      >
        <CodeEditor
          value={code}
          onChange={setCode}
          language={challenge.language === 'python' ? 'python' : 'java'}
        />
      </Suspense>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="sm"
          data-testid="challenge-run"
          disabled={running || ready === null}
          onClick={() => void run()}
        >
          <Play className="size-3.5" />
          {running ? '运行中…' : ready === null ? '检查运行环境…' : '运行'}
        </Button>
        {results === null ? null : (
          <Pill tone={results.every((item) => item.passed) ? 'success' : 'danger'}>
            通过 {results.filter((item) => item.passed).length} / {results.length}
          </Pill>
        )}
        {canTrace ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            data-testid="challenge-trace"
            disabled={tracing || ready === null}
            onClick={() => void startTrace()}
          >
            {tracing ? '录制中…' : '逐步执行'}
          </Button>
        ) : null}
        {results === null ? null : (
          <Button type="button" variant="outline" size="sm" onClick={() => setResults(null)}>
            清空结果
          </Button>
        )}
      </div>

      {error === null ? null : (
        <p className="text-destructive text-sm" data-testid="challenge-run-error">
          {error}
        </p>
      )}

      {trace === null ? null : (
        <div className="rounded-lg border p-3">
          <p className="mb-2 text-xs font-medium">逐步执行</p>
          <TracePlayer trace={trace} />
        </div>
      )}

      {results === null ? null : (
        <div className="space-y-2" data-testid="challenge-run-results">
          {results.map((item, index) => (
            <details
              key={index}
              open={!item.passed}
              className={`rounded-lg border p-3 ${item.passed ? 'border-success/40' : 'border-destructive/40'}`}
            >
              <summary className="cursor-pointer text-sm">
                <span className={item.passed ? 'text-success' : 'text-destructive'}>
                  {item.passed ? '✓' : '✗'} {item.testCase.name}
                </span>
                <span className="text-muted-foreground ml-2 text-xs">{item.output.durationMs} ms</span>
              </summary>
              <div className="mt-2 space-y-2 text-xs">
                <Field label="期望" value={item.testCase.expected} />
                <Field label="实际输出" value={item.output.stdout} testId={`challenge-stdout-${index}`} />
                {item.output.stderr === '' ? null : <Field label="stderr" value={item.output.stderr} />}
                {item.output.timedOut ? <p className="text-destructive">超时：程序没在上限内结束。</p> : null}
                {item.output.outOfMemory ? <p className="text-destructive">超出内存上限。</p> : null}
                {item.output.exitCode === 0 ? null : (
                  <p className="text-destructive">退出码 {item.output.exitCode}</p>
                )}
                {item.output.truncated ? <p className="text-muted-foreground">输出过长，已截断。</p> : null}
              </div>
            </details>
          ))}
        </div>
      )}

      {results !== null && challenge.explain !== undefined ? (
        <p className="text-muted-foreground text-sm leading-7">{challenge.explain}</p>
      ) : null}
    </div>
  )
}

function Field({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <div>
      <div className="text-muted-foreground">{label}</div>
      <pre
        data-testid={testId}
        className="border-border bg-muted/40 max-h-40 overflow-auto rounded border p-2 font-mono whitespace-pre-wrap"
      >
        {value === '' ? '（空）' : value}
      </pre>
    </div>
  )
}
