import type { EntryProgress, ProgressPatch, ProgressPort } from '@paideia/ui'

import type { BlockScore, EntryProgressView, ProgressReport } from './learning-types'
import { createEntryProgressPort } from './source-reading-progress'

/**
 * 课程条目页的服务端进度端口（WORK-020）。
 *
 * <p><b>为什么还要合并 localStorage</b>：`localStorage` 里已经存着源码解读的阅读进度，
 * 而本项新增的是"服务端记录"。两者都可能有数据——第一次上报之前，服务端那份是空的。
 * 只读服务端会让老用户看到"进度清零"；只读本地则跨设备丢失。所以读的时候取并集，
 * 逐块取更高的分、走读取更远的步骤。
 *
 * <p><b>为什么写是同步签名</b>：`ProgressPort` 被六个渲染块依赖，接口是同步的，改异步等于
 * 全仓返工。这里在内存里先合并、再 600ms 去抖上报——块完全无感。
 *
 * <p><b>已知边界</b>：去抖窗口内关掉页面，最后一次得分可能没上报。代价很小（下次重做会补），
 * 而为此引入 beforeunload 同步请求会让关闭变慢。`flush()` 留给页面在卸载时调用。
 */

const DEBOUNCE_MS = 600

/** 上限：一个块最多多少题，与服务端校验一致。超出说明数据坏了，取服务端值。 */
function better(left: BlockScore | undefined, right: BlockScore | undefined): BlockScore | undefined {
  if (left === undefined) return right
  if (right === undefined) return left
  if (right.total <= 0) return left
  if (left.total <= 0) return right
  return right.score * left.total >= left.score * right.total ? right : left
}

function mergeScores(
  local: Record<string, BlockScore> | undefined,
  remote: Record<string, BlockScore> | undefined,
): Record<string, BlockScore> {
  const merged: Record<string, BlockScore> = { ...(remote ?? {}) }
  for (const [id, score] of Object.entries(local ?? {})) {
    const winner = better(merged[id], score)
    if (winner !== undefined) {
      merged[id] = winner
    }
  }
  return merged
}

/**
 * 合并两份进度：逐块取更高分，走读取更远步骤。
 *
 * <p>纯函数、单独导出，单测直接盯着它——"取更高的那一份"这类规则写反了不会报错，
 * 只会让用户看到进度倒退。
 */
export function mergeProgress(remote: EntryProgressView | null, local: EntryProgress | null): EntryProgress {
  const challenges = mergeScores(local?.challenges, remote?.challenges)
  const quizzes = mergeScores(local?.quizzes, remote?.quizzes)
  return {
    updatedAt: remote?.reviewDueAt ?? local?.updatedAt ?? new Date().toISOString(),
    title: remote?.title ?? local?.title,
    tours: local?.tours ?? {},
    challenges,
    quizzes,
  }
}

export interface ServerProgressPort extends ProgressPort {
  /** 立刻把待上报的分数发出去（页面卸载时调用）。 */
  flush(): void
}

/**
 * 造一个"先本地合并、再异步上报"的端口。
 *
 * @param initial 页面在渲染正文前取到的服务端视图；没取到就传 null（照常本地可用）
 * @param report  上报函数；抛错时只记日志，不影响界面
 * @param clear   重置函数（删服务端记录）
 */
export function createServerProgressPort({
  accountId,
  sessionSignal,
  entryId,
  title,
  initial,
  report,
  clear,
}: {
  accountId: string
  sessionSignal: AbortSignal
  entryId: number
  title?: string
  initial: EntryProgressView | null
  report: (body: ProgressReport) => Promise<unknown>
  clear: () => Promise<unknown>
}): ServerProgressPort {
  const local = createEntryProgressPort(accountId, entryId, title)
  let state = mergeProgress(initial, local?.read() ?? null)
  let timer: ReturnType<typeof setTimeout> | null = null
  let pending: ProgressReport = {}

  const send = () => {
    timer = null
    const body = pending
    pending = {}
    if (sessionSignal.aborted || (body.tourStep === undefined && body.challenges === undefined && body.quizzes === undefined)) {
      return
    }
    // 不 await：界面不等网络。失败只丢这一次上报，下一次得分会重新带上
    report(body).catch(() => {
      // 静默：学习记录不是交易，丢了下次重做还在
    })
  }

  const schedule = () => {
    if (timer !== null) {
      clearTimeout(timer)
    }
    timer = setTimeout(send, DEBOUNCE_MS)
  }

  return {
    read(targetId?: number): EntryProgress | null {
      if (targetId !== undefined && targetId !== entryId) {
        // 学习路径块要读别的站：服务端没有那一站的视图，退回本地
        return local?.read(targetId) ?? null
      }
      return state
    },

    update(patch: ProgressPatch): void {
      if (sessionSignal.aborted) return
      const challenges = mergeScores(state.challenges, patch.challenges as Record<string, BlockScore> | undefined)
      const quizzes = mergeScores(state.quizzes, patch.quizzes as Record<string, BlockScore> | undefined)
      state = {
        ...state,
        updatedAt: new Date().toISOString(),
        tours: { ...state.tours, ...(patch.tours ?? {}) },
        challenges,
        quizzes,
      }
      // 本地仍然写一份：离线或接口失败时至少这次阅读不白读
      local?.update(patch)
      pending = {
        tourStep: maxTourStep(state),
        challenges: patch.challenges as Record<string, BlockScore> | undefined,
        quizzes: patch.quizzes as Record<string, BlockScore> | undefined,
      }
      schedule()
    },

    reset(): void {
      if (sessionSignal.aborted) return
      state = { updatedAt: new Date().toISOString(), tours: {}, challenges: {}, quizzes: {} }
      pending = {}
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      local?.reset()
      clear().catch(() => {})
    },

    flush(): void {
      if (timer !== null) {
        clearTimeout(timer)
        send()
      }
    },
  }
}

/** 走读进度里最远的一步。没有走读块时是 0。 */
function maxTourStep(progress: EntryProgress): number {
  let furthest = 0
  for (const tour of Object.values(progress.tours)) {
    if (tour.step > furthest) {
      furthest = tour.step
    }
  }
  return furthest
}
