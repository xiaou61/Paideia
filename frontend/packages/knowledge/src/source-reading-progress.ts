import type { EntryProgress, ProgressPatch, ProgressPort } from '@paideia/ui'

/**
 * 源码解读的**本地阅读进度**（WORK-014）。
 *
 * <p><b>为什么不上服务端</b>：v1 不建表、不加端点。进度只是"下次接着读"的便利，
 * 丢了不损失内容；而一张进度表会连带出接口、鉴权、迁移与集成测试。
 *
 * <p>三条纪律：**读写全部容错**（隐私模式、配额满、别的标签页写坏 JSON 都不该让页面挂）、
 * **一次写一个条目**、**按账号隔离**。旧版本的共享记录无法确定归属，不迁移。
 */
function storageKey(accountId: string): string {
  return `paideia:source-reading:v2:${accountId}`
}

interface StoredEntry {
  updatedAt: string
  title?: string
  tours?: Record<string, { step: number; total: number; done: boolean }>
  challenges?: Record<string, { score: number; total: number }>
  quizzes?: Record<string, { score: number; total: number }>
}

type Store = Record<string, StoredEntry>

function readStore(accountId: string): Store {
  try {
    const raw = window.localStorage.getItem(storageKey(accountId))
    if (raw === null) {
      return {}
    }
    const parsed = JSON.parse(raw) as unknown
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return {}
    }
    return parsed as Store
  } catch {
    // 损坏或不可用：当作空进度。**不清理**——别的标签页可能正在写，删了会互相踩
    return {}
  }
}

function writeStore(accountId: string, store: Store): void {
  try {
    window.localStorage.setItem(storageKey(accountId), JSON.stringify(store))
  } catch {
    // 隐私模式/配额满：静默。阅读不因为存不下而失败
  }
}

function toProgress(entry: StoredEntry | undefined): EntryProgress | null {
  if (entry === undefined) {
    return null
  }
  return {
    updatedAt: entry.updatedAt,
    title: entry.title,
    tours: entry.tours ?? {},
    challenges: entry.challenges ?? {},
    quizzes: entry.quizzes ?? {},
  }
}

/** 某个条目的进度端口。`entryId` 为空时返回 `undefined`（管理端预览不记录）。 */
export function createEntryProgressPort(accountId?: string, entryId?: number, title?: string): ProgressPort | undefined {
  if (accountId === undefined || entryId === undefined) {
    return undefined
  }
  return {
    read(targetId?: number): EntryProgress | null {
      const store = readStore(accountId)
      const key = String(targetId ?? entryId)
      return toProgress(store[key])
    },
    update(patch: ProgressPatch): void {
      const store = readStore(accountId)
      const key = String(entryId)
      const previous = store[key]
      store[key] = {
        updatedAt: new Date().toISOString(),
        title: title ?? previous?.title,
        tours: { ...(previous?.tours ?? {}), ...(patch.tours ?? {}) },
        challenges: { ...(previous?.challenges ?? {}), ...(patch.challenges ?? {}) },
        quizzes: { ...(previous?.quizzes ?? {}), ...(patch.quizzes ?? {}) },
      }
      writeStore(accountId, store)
    },
    reset(): void {
      const store = readStore(accountId)
      delete store[String(entryId)]
      writeStore(accountId, store)
    },
  }
}

export interface RecentReading {
  entryId: number
  title: string
  visited: string
}

/** 最近读过的条目（按更新时间倒序），首页「继续学习」用。 */
export function recentReading(accountId: string | undefined, limit = 3): RecentReading[] {
  if (accountId === undefined) return []
  const store = readStore(accountId)
  return Object.entries(store)
    .map(([key, entry]) => ({
      entryId: Number(key),
      title: entry.title ?? `条目 #${key}`,
      visited: entry.updatedAt,
    }))
    .filter((item) => Number.isInteger(item.entryId) && item.entryId > 0)
    .sort((left, right) => right.visited.localeCompare(left.visited))
    .slice(0, limit)
}
