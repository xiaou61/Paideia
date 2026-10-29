import { Play } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Button } from '../../components/button'
import { Pill } from './blocks'
import { useSourceReading, type ShellOutcome } from './context'
import type { ShellChallenge } from './schema'

/**
 * Linux 判题练习（WORK-056）。
 *
 * <p><b>它不做判定</b>：把「命令 + 题目自带的 judge」发给服务端，拿回 `passed` / `failed` /
 * `undetermined` 三种结论。判定脚本不下发到客户端——它要判的是最终状态（权限位、文件内容），
 * 把它发下来等于把答案一起发下来。
 *
 * <p>**能力不探测就不渲染按钮**：与 `RunBody` 同一条纪律。渲染一个必然失败的按钮比不渲染更糟。
 */
export function ShellBody({
  challenge,
  onGraded,
}: {
  challenge: ShellChallenge
  onGraded: (correct: number, total: number) => void
}) {
  const { sandbox } = useSourceReading()
  const [ready, setReady] = useState<boolean | null>(null)
  const [code, setCode] = useState(challenge.starter ?? '')
  const [running, setRunning] = useState(false)
  const [outcome, setOutcome] = useState<ShellOutcome | null>(null)
  const [error, setError] = useState<string | null>(null)

  const canRun = sandbox !== undefined && typeof sandbox.runShell === 'function'

  useEffect(() => {
    if (!canRun) {
      setReady(false)
      return
    }
    let alive = true
    sandbox
      .ready()
      .then((value) => {
        if (alive) {
          setReady(value)
        }
      })
      .catch(() => {
        if (alive) {
          setReady(false)
        }
      })
    return () => {
      alive = false
    }
  }, [sandbox, canRun])

  if (!canRun) {
    return (
      <div className="space-y-2">
        <p className="text-sm">这一题要在隔离环境里敲命令，但当前页面没有接上执行能力。</p>
        <p className="text-muted-foreground text-xs">（可以照着这道题在本地终端里练，判定标准写在下方。）</p>
      </div>
    )
  }

  const run = async () => {
    if (sandbox?.runShell === undefined) {
      return
    }
    setRunning(true)
    setError(null)
    setOutcome(null)
    try {
      const result = await sandbox.runShell({
        code,
        judge: challenge.judge,
        seed: challenge.seed,
        caseId: challenge.id,
      })
      setOutcome(result)
      onGraded(result.verdict === 'passed' ? 1 : 0, 1)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-muted-foreground text-xs">
        改完点「运行」：会在一次性的隔离环境里执行，没有网络、内存与时间都有上限；判定看的是执行后的最终状态。
      </p>
      {challenge.seed.length === 0 ? null : (
        <p className="text-muted-foreground text-xs" data-testid="challenge-shell-seed">
          工作区里已经有：{challenge.seed.map((file) => file.path).join('、')}
        </p>
      )}
      <textarea
        value={code}
        onChange={(event) => setCode(event.target.value)}
        rows={Math.min(20, Math.max(8, code.split('\n').length + 1))}
        spellCheck={false}
        aria-label="练习命令"
        data-testid="challenge-code"
        className="border-border bg-muted/30 w-full resize-y rounded-lg border p-3 font-mono text-[12.5px] leading-6 outline-none"
      />
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="sm"
          data-testid="challenge-run"
          disabled={running || ready === null || ready === false}
          onClick={() => void run()}
        >
          <Play className="size-3.5" />
          {running ? '运行中…' : ready === null ? '检查运行环境…' : '运行'}
        </Button>
        {outcome === null ? null : <Verdict outcome={outcome} />}
      </div>

      {ready === false ? (
        <p className="text-muted-foreground text-sm">当前环境没有启用「运行」，本题只作为阅读材料。</p>
      ) : null}

      {error === null ? null : (
        <p className="text-destructive text-sm" data-testid="challenge-shell-error">
          {error}
        </p>
      )}

      {outcome === null ? null : (
        <div className="space-y-2" data-testid="challenge-shell-result">
          {outcome.reason === null ? null : (
            <p className="text-sm" data-testid="challenge-shell-reason">
              {outcome.reason}
            </p>
          )}
          {outcome.stdout.trim() === '' ? null : (
            <Output title="你的命令输出" text={outcome.stdout} />
          )}
          {outcome.stderr.trim() === '' ? null : (
            <Output title="错误输出（stderr）" text={outcome.stderr} />
          )}
          {judgeMessage(outcome) === '' ? null : (
            <Output title="判定说哪里不对" text={judgeMessage(outcome)} />
          )}
          {outcome.diff.trim() === '' ? (
            <p className="text-muted-foreground text-xs">工作区没有任何文件变化。</p>
          ) : (
            <Output title="文件变化" text={outcome.diff} />
          )}
          <p className="text-muted-foreground text-xs">
            用时 {outcome.durationMs} ms · 今天还剩 {Math.max(0, outcome.dailyLimit - outcome.usedToday)} 次
          </p>
        </div>
      )}

      {challenge.explain === undefined ? null : (
        <p className="text-muted-foreground text-xs">解析：{challenge.explain}</p>
      )}
    </div>
  )
}

function Verdict({ outcome }: { outcome: ShellOutcome }) {
  if (outcome.verdict === 'passed') {
    return <Pill tone="success">通过</Pill>
  }
  if (outcome.verdict === 'failed') {
    return <Pill tone="danger">未通过</Pill>
  }
  // 与「未通过」分开：这时不知道对错，让他去查原因而不是改命令
  return <Pill>未能判定</Pill>
}

/**
 * 判定脚本的人类可读输出。
 *
 * <p>**两段都显示**：作者写 `echo` 时它在 stdout，写 `>&2` 时在 stderr——让内容作者记住
 * 该用哪个流没有意义，漏一个就变成"判了但不说为什么"（WORK-056 首次上线就踩了）。
 */
function judgeMessage(outcome: ShellOutcome): string {
  const parts = [outcome.judgeStdout.trim(), outcome.judgeStderr.trim()]
  return parts.filter((part) => part !== '').join('\n')
}

function Output({ title, text }: { title: string; text: string }) {
  return (
    <div className="rounded-lg border p-3">
      <p className="mb-1 text-xs font-medium">{title}</p>
      <pre className="overflow-x-auto font-mono text-[12px] leading-6 whitespace-pre-wrap">{text}</pre>
    </div>
  )
}
