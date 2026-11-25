/**
 * 笔记自动保存的可测状态机（WORK-092 / REQ-004）。
 *
 * <p>为什么单独抽出来：正文编辑页把"防抖保存 + 保存期间继续编辑"做成 React 里的 ref 逻辑时，
 * 没法在没有浏览器与后端的单测里验证。这里用纯 TS 复刻同一套语义，页面复用它，
 * 于是"慢保存期间继续输入，最终落库的是最新内容"可以用假计时器直接钉住。
 *
 * <p>关键缺陷（旧实现）：保存进行中再次触发保存会被直接丢弃（`if (saving) return`），
 * 期间的最后一次编辑永远不会落库。这里在保存期间把改动记为 pending，完成后再补存一次。
 */

/** 参与"是否已保存"比较的草稿字段（entryId 是关联条目，不参与脏判断）。 */
export interface NoteDraft {
  selected: number | null
  entryId: number | null
  title: string
  body: string
  category: string
  level: string
}

/** 两份草稿的可比字段是否完全一致（决定保存成功后能否清 dirty）。 */
export function noteDraftsEqual(a: NoteDraft, b: NoteDraft): boolean {
  return (
    a.selected === b.selected &&
    a.title === b.title &&
    a.body === b.body &&
    a.category === b.category &&
    a.level === b.level
  )
}

/**
 * 服务端文档能否覆盖编辑器本地内容。
 *
 * <p>只有当编辑器还没有载入这篇文档（{@code loadedDocId !== docId}）时才用服务端内容刷新；
 * 同一篇文档的后台刷新（保存后 invalidate、窗口聚焦重取）一律不覆盖本地，
 * 否则会把正在编辑、尚未保存的草稿冲掉。
 */
export function shouldApplyServerDocument(loadedDocId: number | null, docId: number): boolean {
  return loadedDocId !== docId
}

export interface NoteAutosaveOptions {
  /** 防抖间隔，默认 700ms。 */
  debounceMs?: number
  /** 真正落库的动作（现有 PUT）。抛出即视为失败，保留 dirty 以便重试。 */
  save: (draft: NoteDraft) => Promise<void>
  onError: (reason: unknown) => void
  onSavingChange?: (saving: boolean) => void
  onDirtyChange?: (dirty: boolean) => void
}

/** 自动保存状态机：防抖 + 保存期间继续编辑的补存。见文件头说明。 */
export class NoteAutosaver {
  private latest: NoteDraft
  private saving = false
  private pending = false
  private dirty = false
  private timer: ReturnType<typeof setTimeout> | null = null
  private readonly debounceMs: number

  constructor(
    initial: NoteDraft,
    private readonly options: NoteAutosaveOptions,
  ) {
    this.latest = initial
    this.debounceMs = options.debounceMs ?? 700
  }

  /** 每次渲染把最新草稿同步进来（不触发保存）。 */
  setDraft(draft: NoteDraft): void {
    this.latest = draft
  }

  /** 内容有改动：标脏并（重新）排一次防抖计时。 */
  markDirty(): void {
    this.setDirty(true)
    this.schedule()
  }

  /** 明确回到"与服务端一致"（切换文档载入完成时调用），并丢弃排队的保存。 */
  markClean(): void {
    this.clearTimer()
    this.pending = false
    this.setDirty(false)
  }

  /** 立即保存最新草稿（显式保存按钮）；保存中若有新编辑会在完成后补存。 */
  async flush(): Promise<void> {
    this.clearTimer()
    if (this.latest.selected === null) {
      return
    }
    if (this.saving) {
      // 已有保存在途：记下"还有更新要写"，不丢弃。
      this.pending = true
      return
    }
    this.saving = true
    this.options.onSavingChange?.(true)
    const sent = this.latest
    try {
      await this.options.save(sent)
      if (this.latest.selected !== sent.selected) {
        // 保存期间已切换到别的文档：本次结果属于旧文档，不能把新文档内容当"补存"
        // 立刻再写一遍；新文档的写入由载入/后续编辑触发。
      } else if (noteDraftsEqual(this.latest, sent)) {
        this.setDirty(false)
      } else {
        // 保存期间又改了：完成后立即补存最新。
        this.pending = true
      }
    } catch (reason) {
      this.options.onError(reason)
    } finally {
      this.saving = false
      this.options.onSavingChange?.(false)
      if (this.pending) {
        this.pending = false
        await this.flush()
      }
    }
  }

  /** 组件卸载：清掉未触发的防抖计时。 */
  dispose(): void {
    this.clearTimer()
  }

  private schedule(): void {
    this.clearTimer()
    this.timer = setTimeout(() => {
      this.timer = null
      void this.flush()
    }, this.debounceMs)
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer)
      this.timer = null
    }
  }

  private setDirty(value: boolean): void {
    if (this.dirty === value) {
      return
    }
    this.dirty = value
    this.options.onDirtyChange?.(value)
  }
}
