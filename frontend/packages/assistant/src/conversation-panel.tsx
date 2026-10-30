import { Alert, AlertDescription, AlertTitle, Button, Markdown, Spinner } from '@paideia/ui'
import { History, Plus, Send, ThumbsDown, ThumbsUp, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { AssistantApi } from './client'
import { serializeAttachments } from './attachments'
import { useAssistantContext } from './context'
import { extractSqlBlock, serializePageContext } from './page-context'
import { readStoredConversation, storeConversation } from './session-store'
import type { ChatMessage, ConversationView, NoteDraftInput } from './types'

/**
 * 对话面板：多轮 + 流式 + 思考折叠，**并记住上次那一段**（WORK-011）。
 *
 * <p>四条与后端语义对应的规则：
 * <ol>
 *   <li><b>思考默认折叠</b>（用户裁决）。用原生 `<details>`：展开／收起是平台能力，
 *       不需要为它维护一份状态。</li>
 *   <li><b>打开就回到上次的会话</b>，而不是每次从零开始；**只有点「新开对话」才另起一段**。</li>
 *   <li><b>换条目不再静默清空</b>：那条条目有绑定的会话就切过去；没有就保持当前会话不动。
 *       旧实现一换条目就把上下文丢掉，那是用户抱怨最直接的一点。</li>
 *   <li><b>用户收起面板即中断上游调用</b>：不断开的话，这次调用会继续计费到用户看不见的地方。
 *       收起 = 本组件卸载（外壳收起时走的是另一条 return 分支），所以卸载时的 abort 就够。</li>
 * </ol>
 *
 * <p>没有条目上下文时照样能聊（用户裁决：助手任意处可开，条目只是**可选**上下文）。
 *
 * <p>`onSaveNote` 由**应用**注入而不是本包直接调笔记接口：本包不认识笔记，让应用把"存进笔记"
 * 这件事接上。
 */
export function ConversationPanel({
  api,
  onSaveNote,
}: {
  api: AssistantApi
  onSaveNote?: (note: NoteDraftInput, entryId: number | null) => Promise<unknown>
}) {
  const { entryId, entryTitle, harness, pageContext, attachments, detach, applySql, pendingPrompt, clearPendingPrompt } =
    useAssistantContext()
  const [conversationId, setConversationId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sessions, setSessions] = useState<ConversationView[]>([])
  const [listOpen, setListOpen] = useState(false)
  /** 正在从服务端恢复历史——期间不显示"问点什么吧"的空态，否则会闪一下"没有对话"。 */
  const [restoring, setRestoring] = useState(true)
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [savedIndexes, setSavedIndexes] = useState<number[]>([])
  const [rememberedIndexes, setRememberedIndexes] = useState<number[]>([])
  const [drafts, setDrafts] = useState<{ title: string; body: string }[]>([])
  const [savedDrafts, setSavedDrafts] = useState<number[]>([])
  /**
   * 记忆总开关。**默认按开着算**：读不到设置时不能把「记住这条」锁死——一次读取失败不该
   * 让一个本来能用的功能变成灰的；真有问题是点下去时后端回话（它也会拒绝写入）。
   */
  const [memoryEnabled, setMemoryEnabled] = useState(true)
  const abortRef = useRef<AbortController | null>(null)
  /**
   * 用户是否已经自己动过这一段（发消息 / 新开 / 预填）。
   *
   * <p>挂载时的"恢复上次会话"是**异步**的（先列会话、再拉消息），而用户可能在这几十毫秒里已经
   * 点了发送。恢复完成后如果照旧写入，就会把在飞的那一轮 **abort 掉**或把消息覆盖回历史。
   *
   * <p>**只用于"首次挂载的那一次恢复"**：用户主动切换会话（openConversation）不该被它拦住，
   * 否则界面会显示旧会话的消息却挂在新的 conversationId 上。所以它一旦置真就不再复位，
   * 而首次恢复的 effect 也只认它一次。
   */
  const touchedRef = useRef(false)
  /** 会话切换的请求序号：只让最后一次切换的结果落地。 */
  const requestSeqRef = useRef(0)

  /** 切到某条会话：读回它的消息（旧 → 新）并记住。 */
  const openConversation = useCallback(
    async (id: number, options?: { abort?: boolean }) => {
      // 恢复那一路**不许打断**用户已经发起的调用（见 touchedRef 的注释）
      if (options?.abort !== false) {
        abortRef.current?.abort()
        abortRef.current = null
      }
      setConversationId(id)
      storeConversation(id)
      setDrafts([])
      setError(null)
      const seq = ++requestSeqRef.current
      try {
        const history = await api.messages(id)
        // 这里是**用户主动切换**，不是挂载恢复：不能看 touchedRef（发过消息的用户切历史
        // 会被这条直接 return，界面显示旧消息却挂着新的 conversationId）。
        // 只丢弃"已被下一次切换取代"的响应。
        if (requestSeqRef.current !== seq) {
          return
        }
        setMessages(
          history.map((message) => ({
            role: message.role,
            content: message.content,
            // 思考不落库，历史里没有它
            thinking: '',
            usage: message.usage,
            emptyHint: false,
            createdAt: message.createdAt,
          })),
        )
      } catch (cause) {
        // 序号已被更晚的一次切换取代：本次的失败不该把界面上那段会话清掉
        if (requestSeqRef.current !== seq) {
          return
        }
        // 会话过期（7 天滑动）是常态：清掉本地记忆，退回"新的一段"，而不是留一个打不开的 id
        storeConversation(null)
        setConversationId(null)
        setMessages([])
        setError(cause instanceof Error ? cause.message : String(cause))
      }
    },
    [api],
  )

  /** 第一次挂载：按本地记的三态决定看哪一段（见 session-store 的注释）。 */
  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const list = await api.conversations()
        if (!alive) {
          return
        }
        // 会话列表本身与"用户是否发过消息"无关：这里不能因为 touchedRef 为真就跳过，
        // 否则全新挂载（点「问 AI」打开面板）时 sessions 永远为空，「历史」按钮整段不出现。
        setSessions(list)
        if (touchedRef.current) {
          // 但"恢复上次那一段"必须让位——用户已经在当前这段里说上话了
          return
        }
        const stored = readStoredConversation()
        if (stored.kind === 'new') {
          // 刚点过「新开对话」：保持空白，**不要**再去恢复旧的
          return
        }
        if (stored.kind === 'id') {
          await openConversation(stored.id, { abort: false })
          return
        }
        // 从没选过：恢复最近一条；有当前条目时优先绑在这个条目上的那条
        const bound = list.find((item) => item.entryId !== null && item.entryId === entryId)
        const target = bound ?? list[0]
        if (target !== undefined) {
          await openConversation(target.id, { abort: false })
        }
      } catch (cause) {
        if (alive) {
          setError(cause instanceof Error ? cause.message : String(cause))
        }
      } finally {
        if (alive) {
          setRestoring(false)
        }
      }
    })()
    return () => {
      alive = false
    }
    // 只在挂载时跑一次：之后换条目由下面那个 effect 处理（且**不许**清空）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, openConversation])

  /** 换条目：该条目有绑定的会话就切过去，**没有就保持当前这一段**（不再静默清空）。 */
  useEffect(() => {
    const bound = sessions.find((item) => item.entryId !== null && item.entryId === entryId)
    if (bound !== undefined && bound.id !== conversationId) {
      void openConversation(bound.id)
    }
    // sessions 变化不该触发"切换"，只看 entryId
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entryId])

  // 卸载即中断：面板收起来之后还在计费的调用是纯浪费
  useEffect(() => () => abortRef.current?.abort(), [])

  /**
   * 预填（WORK-014）：把「问 AI」那句话放进输入框，然后清掉请求。
   *
   * <p><b>绝不调用 `send`</b>：用户点的是"帮我起个头"，发出去等于替他花积分。
   * 清空 pending 之后 `input` 保留——那是用户接下来要改的草稿。
   * 同时把 `touchedRef` 置位：恢复历史的那条异步路径不该再用旧消息覆盖这行草稿。
   */
  useEffect(() => {
    if (pendingPrompt === null) {
      return
    }
    touchedRef.current = true
    // 同上：预填之后在飞的恢复不能再用历史覆盖这行草稿
    requestSeqRef.current += 1
    setInput(pendingPrompt.text)
    clearPendingPrompt(pendingPrompt.id)
  }, [pendingPrompt, clearPendingPrompt])

  /**
   * 记忆总开关关着时要**提前**挡住「记住这条」（REQ-007）：后端也会拒绝，但让用户点了才吃一个
   * 报错是界面偷懒——他要的是"为什么不能记"，不是"服务端说你不能记"。
   */
  useEffect(() => {
    let alive = true
    api
      .settings()
      .then((value) => {
        if (alive) {
          setMemoryEnabled(value.memoryEnabled)
        }
      })
      .catch(() => {
        // 静默：这里报错会盖住对话本身，而它只影响一个按钮的可用性
      })
    return () => {
      alive = false
    }
  }, [api])

  /** 新开一段：清空界面并**把本地记成"空白"**，这样关掉再打开也不会把旧的拉回来。 */
  const startNew = () => {
    touchedRef.current = true
    // 让在飞的 openConversation / 挂载恢复失效：否则它回来时序号仍匹配，会把旧会话的历史
    // 写进这个刚刚清空的新会话里。
    requestSeqRef.current += 1
    abortRef.current?.abort()
    abortRef.current = null
    setConversationId(null)
    storeConversation(null)
    setMessages([])
    setDrafts([])
    setSavedIndexes([])
    setRememberedIndexes([])
    setError(null)
    setListOpen(false)
  }

  const refreshSessions = async () => {
    try {
      setSessions(await api.conversations())
    } catch {
      // 列表拉不到不影响当前这段对话，静默即可
    }
  }

  const patchLast = (patch: (message: ChatMessage) => ChatMessage) =>
    setMessages((previous) =>
      previous.map((message, index) => (index === previous.length - 1 ? patch(message) : message)),
    )

  /**
   * 这一轮要带上的上下文：页面场景 + 对象附件（WORK-058）。
   *
   * <p>两者拼成**一段文本**发给服务端，不改成结构化数组：服务端只把它拼进提示词，改数组要动
   * DTO、SSE 与历史回放三处，收益只是"更好看"（见 WORK-058 的 IC-002）。
   */
  const turnContext = [serializePageContext(pageContext), serializeAttachments(attachments)]
    .filter((part): part is string => part !== undefined)
    .join('\n\n')

  const send = async () => {
    const text = input.trim()
    if (text === '' || busy) {
      return
    }
    touchedRef.current = true
    // 让在飞的挂载恢复失效：恢复路径带 abort:false，不推进序号的话它回来会覆盖这一轮
    requestSeqRef.current += 1
    setBusy(true)
    setError(null)
    setInput('')
    setMessages((previous) => [
      ...previous,
      { role: 'user', content: text, thinking: '', usage: null, emptyHint: false },
      { role: 'assistant', content: '', thinking: '', usage: null, emptyHint: false },
    ])

    const controller = new AbortController()
    abortRef.current = controller
    try {
      let id = conversationId
      if (id === null) {
        id = (await api.createConversation(entryId, harness)).id
        setConversationId(id)
        storeConversation(id)
      }
      await api.sendMessage(
        id,
        text,
        (event) => {
          if (event.type === 'thinking') {
            patchLast((message) => ({ ...message, thinking: message.thinking + (event.delta ?? '') }))
          } else if (event.type === 'content') {
            patchLast((message) => ({ ...message, content: message.content + (event.delta ?? '') }))
          } else if (event.type === 'draft') {
            if (event.draft !== undefined) {
              setDrafts((previous) => [...previous, event.draft as { title: string; body: string }])
            }
          } else if (event.type === 'done') {
            patchLast((message) => ({
              ...message,
              usage: event.usage ?? null,
              emptyHint: event.empty === true,
              traceId: event.traceId ?? null,
            }))
          } else if (event.type === 'error') {
            setError(event.message ?? '生成失败，请稍后再试')
          }
        },
        controller.signal,
        // 页面场景与附件：只在这一轮进提示词，不落库（WORK-035 T-15 / WORK-058）
        turnContext === '' ? undefined : turnContext,
      )
      // 这一轮落库了，列表里的更新时间跟着变——顺手刷新，免得列表显示的时间是旧的
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
      // 这一轮没成：把占位的空气泡去掉，否则看起来像"助手回了空内容"
      setMessages((previous) => {
        const last = previous[previous.length - 1]
        if (last !== undefined && last.role === 'assistant' && last.content === '' && last.thinking === '') {
          return previous.slice(0, -1)
        }
        return previous
      })
    } finally {
      setBusy(false)
      abortRef.current = null
      // **成功与失败都要刷**：这一轮可能已经建好了会话（例如被积分闸拦下时），
      // 不刷的话「历史」按钮不会出现，用户以为这一段没被记住。
      void refreshSessions()
    }
  }

  const current = sessions.find((item) => item.id === conversationId)

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2" data-testid="conversation-panel">
      <div className="flex items-center justify-between gap-2">
        <p className="text-muted-foreground min-w-0 flex-1 truncate text-xs" data-testid="conversation-context">
          {[
            entryId === null ? '没有条目上下文，可直接提问。' : `上下文：${entryTitle ?? ''}`,
            attachments.length === 0 ? null : `附着 ${attachments.length} 个对象`,
          ]
            .filter((part) => part !== null)
            .join(' · ')}
        </p>
        <Button
          variant="ghost"
          size="sm"
          disabled={busy}
          data-testid="conversation-new"
          onClick={startNew}
        >
          <Plus className="size-3.5" />
          新开对话
        </Button>
        {sessions.length === 0 ? null : (
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={listOpen}
            data-testid="conversation-history"
            onClick={() => setListOpen((value) => !value)}
          >
            <History className="size-3.5" />
            历史
          </Button>
        )}
      </div>

      {listOpen ? (
        <ul
          className="border-border bg-popover max-h-48 space-y-0.5 overflow-y-auto rounded-lg border p-1.5 shadow-sm"
          data-testid="conversation-sessions"
        >
          {sessions.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                data-testid={`conversation-session-${item.id}`}
                data-active={item.id === conversationId ? 'true' : undefined}
                onClick={() => {
                  setListOpen(false)
                  void openConversation(item.id)
                }}
                className={`hover:bg-accent/60 flex w-full items-center justify-between gap-2 rounded-md px-2.5 py-1.5 text-left text-xs transition-colors ${
                  item.id === conversationId ? 'bg-accent text-accent-foreground' : 'text-muted-foreground'
                }`}
              >
                <span className="truncate">
                  {item.updatedAt === null ? '较早的一段' : new Date(item.updatedAt).toLocaleString('zh-CN', { hour12: false })}
                </span>
                {item.entryId === null ? null : <span className="shrink-0 opacity-70">条目 {item.entryId}</span>}
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      <div className="min-h-[8rem] flex-1 space-y-4 overflow-y-auto" data-testid="conversation-messages">
        {restoring ? (
          <Spinner />
        ) : messages.length === 0 ? (
          <p className="text-muted-foreground text-sm">问点什么吧，比如让它解释当前内容里的一个概念。</p>
        ) : (
          messages.map((message, index) => (
            <article
              key={index}
              data-testid={`conversation-message-${message.role}`}
              className={message.role === 'user' ? 'flex flex-col items-end' : ''}
            >
              {message.thinking === '' ? null : (
                <details
                  className="border-border bg-muted/40 mb-2 w-full rounded-lg border px-3 py-2 text-xs"
                  data-testid="conversation-thinking"
                >
                  <summary className="text-muted-foreground cursor-pointer select-none">思考过程</summary>
                  <p className="text-muted-foreground mt-1.5 leading-5 whitespace-pre-wrap">{message.thinking}</p>
                </details>
              )}
              {message.content === '' ? (
                message.role === 'assistant' && busy ? <Spinner /> : null
              ) : message.role === 'user' ? (
                <p className="bg-primary/10 text-foreground max-w-[85%] rounded-2xl rounded-br-md px-3.5 py-2 text-sm leading-6 whitespace-pre-wrap">
                  {message.content}
                </p>
              ) : message.emptyHint ? (
                // 这条是**我们自己**给的提示文案（"思考 token 吃满了"那类），不是模型输出——不走 markdown
                <p className="text-muted-foreground text-sm whitespace-pre-wrap">{message.content}</p>
              ) : (
                /**
                 * 模型输出：**按 markdown 渲染**（WORK-012），走 `@paideia/ui` 的统一渲染器。
                 *
                 * <p>`streaming` 只给**正在生成的这一条**：它的含义是"这段文字可能还没长完"，
                 * 于是会上未闭合补全。历史里的回答已经定稿，给它们开这个开关等于每帧白跑一遍补全。
                 */
                <>
                  <Markdown
                    markdown={message.content}
                    streaming={busy && index === messages.length - 1}
                    className="text-sm leading-6"
                  />
                  {/* 生成中的那条不给反馈按钮：回答还没长完，评价它没有意义 */}
                  {busy && index === messages.length - 1 ? null : (
                    <FeedbackRow
                      api={api}
                      conversationId={conversationId}
                      message={message}
                      onRated={(traceId, rating, reason) => {
                        // 状态放本地：一次会话里刷新页面的概率低，而多一次回读请求
                        // 就多一个失败面。代价是刷新后看不出已反馈过，这是有意的取舍。
                        setMessages((previous) => previous.map((item) =>
                          item === message ? { ...item, feedback: { rating, reason } } : item))
                      }}
                    />
                  )}
                </>
              )}
              {message.usage === null ? null : (
                <p className="text-muted-foreground mt-1 text-[11px]" data-testid="conversation-usage">
                  本次用量：{message.usage.totalTokens} tokens（输入 {message.usage.inputTokens}／输出{' '}
                  {message.usage.outputTokens}）
                </p>
              )}
              {/* 只对"真正的回答"给这两个入口：空正文的提示不该被存成笔记或记忆 */}
              {message.role !== 'assistant' || message.content === '' || message.emptyHint ? null : (
                <div className="border-border/70 mt-2 flex flex-wrap items-center gap-1 border-t pt-1.5">
                  {/*
                    填入 SQL 编辑器（WORK-035 T-15）：只在 SQL 工作台出现。
                    它把回答里**第一个 sql 代码块**填回编辑器；没有代码块就提示，不猜。
                    **不执行**——用户点"运行"才算数，与"自主只建议不动作"一致。
                  */}
                  {pageContext?.kind !== 'sql-workbench' ? null : (
                    <Button
                      variant="ghost"
                      size="sm"
                      data-testid="conversation-apply-sql"
                      onClick={() => {
                        const sql = extractSqlBlock(message.content)
                        if (sql === null) {
                          setError('这条回答里没有 SQL 代码块，没法填入编辑器')
                          return
                        }
                        setError(null)
                        applySql(sql)
                      }}
                    >
                      填入编辑器
                    </Button>
                  )}
                  {onSaveNote === undefined ? null : (
                    <Button
                      variant="ghost"
                      size="sm"
                      data-testid="conversation-save-to-note"
                      onClick={() => {
                        if (onSaveNote === undefined) {
                          return
                        }
                        void onSaveNote({ body: message.content }, entryId)
                          .then(() => {
                            setError(null)
                            setSavedIndexes((previous) => [...previous, index])
                          })
                          .catch((cause: unknown) =>
                            setError(cause instanceof Error ? cause.message : String(cause)),
                          )
                      }}
                    >
                      存进笔记
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={!memoryEnabled}
                    title={memoryEnabled ? undefined : '记忆总开关已关闭，先在「记忆」标签页把它打开'}
                    data-testid="conversation-remember"
                    onClick={() => {
                      void api
                        .remember(message.content)
                        .then(() => {
                          setError(null)
                          setRememberedIndexes((previous) => [...previous, index])
                        })
                        .catch((cause: unknown) =>
                          setError(cause instanceof Error ? cause.message : String(cause)),
                        )
                    }}
                  >
                    记住这条
                  </Button>
                  {/* 禁用而不说原因＝死路一条：灰按钮旁边得写清为什么 */}
                  {memoryEnabled ? null : (
                    <span className="text-muted-foreground text-[11px]">
                      记忆已关闭，可在「记忆」里打开
                    </span>
                  )}
                  {savedIndexes.includes(index) ? (
                    <span className="text-muted-foreground text-[11px]" data-testid="conversation-saved">
                      已存进笔记
                    </span>
                  ) : null}
                  {rememberedIndexes.includes(index) ? (
                    <span className="text-muted-foreground text-[11px]" data-testid="conversation-remembered">
                      已记住
                    </span>
                  ) : null}
                </div>
              )}
            </article>
          ))
        )}
      </div>

      {/* 助手提出的笔记草稿：**点了才存**。它不是回答的一部分，所以单独放在消息区下面 */}
      {drafts.length === 0 ? null : (
        <div className="space-y-2" data-testid="conversation-drafts">
          {drafts.map((draft, index) => (
            <div
              key={index}
              className="border-border bg-muted/30 rounded-lg border p-3"
              data-testid="conversation-draft"
            >
              <p className="text-xs font-medium">{draft.title}</p>
              <p className="text-muted-foreground mt-1 text-xs leading-5 whitespace-pre-wrap">{draft.body}</p>
              <div className="mt-2 flex items-center gap-2">
                {onSaveNote === undefined ? null : (
                  <Button
                    size="sm"
                    data-testid={`conversation-draft-save-${index}`}
                    onClick={() => {
                      if (onSaveNote === undefined) {
                        return
                      }
                      void onSaveNote({ title: draft.title, body: draft.body }, entryId)
                        .then(() => {
                          setError(null)
                          setSavedDrafts((previous) => [...previous, index])
                        })
                        .catch((cause: unknown) =>
                          setError(cause instanceof Error ? cause.message : String(cause)),
                        )
                    }}
                  >
                    保存为笔记
                  </Button>
                )}
                <span className="text-muted-foreground text-[11px]">
                  {savedDrafts.includes(index) ? '已保存' : '助手只是拟好，点了才存'}
                </span>
              </div>
            </div>
          ))}
        </div>
      )}

      {error === null ? null : (
        <Alert variant="destructive" data-testid="conversation-error">
          <AlertTitle>没能完成</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {current === undefined ? null : (
        <p className="text-muted-foreground text-[11px]" data-testid="conversation-current">
          正在这一段对话里（消息会留在服务端 7 天）
        </p>
      )}

      {/* 已附着的对象：随时可取下。放在输入框正上方，因为它是"这一条要带上的东西" */}
      {attachments.length === 0 ? null : (
        <div className="flex flex-wrap gap-1.5" data-testid="conversation-attachments">
          {attachments.map((item) => (
            <span
              key={item.uri}
              className="border-border bg-muted/40 inline-flex max-w-full items-center gap-1 rounded-full border py-0.5 pr-1 pl-2 text-[11px]"
            >
              <span className="truncate" title={item.uri}>
                {item.label}
              </span>
              <button
                type="button"
                aria-label={`取下 ${item.label}`}
                data-testid={`detach-${item.uri.replace(/[/:]/g, '-')}`}
                onClick={() => detach(item.uri)}
                className="hover:bg-accent rounded-full p-0.5"
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      <form
        className="border-border bg-background focus-within:border-ring focus-within:ring-ring/40 flex items-end gap-2 rounded-xl border p-2 transition-colors focus-within:ring-3"
        onSubmit={(event) => {
          event.preventDefault()
          void send()
        }}
      >
        <textarea
          value={input}
          onChange={(event) => setInput(event.target.value)}
          rows={2}
          placeholder="问点什么…"
          data-testid="conversation-input"
          className="placeholder:text-muted-foreground min-h-0 flex-1 resize-none bg-transparent px-1.5 py-1 text-sm leading-6 outline-none"
        />
        <Button
          type="submit"
          size="sm"
          className="rounded-lg"
          disabled={busy || input.trim() === ''}
          data-testid="conversation-send"
        >
          {busy ? <Spinner /> : <Send className="size-4" />}
          发送
        </Button>
      </form>
    </div>
  )
}

/** 点踩的原因。与后端的白名单**逐字对应**——多一个值会被服务端拒成 400。 */
const REASONS = [
  { value: 'off_topic', label: '答非所问' },
  { value: 'too_verbose', label: '太啰嗦' },
  { value: 'incorrect', label: '内容有错' },
  { value: 'other', label: '其它' },
] as const

/**
 * 一条回答下面的赞/踩（WORK-036）。
 *
 * <p>点踩才展开原因——不点踩的人不必先看到四个选项。**已评价后收起**并显示"已反馈"，
 * 但按钮仍可点（改了主意要能改，后端是覆盖）。
 *
 * <p>提交失败不弹窗打断对话，只在本地回退——反馈是**次要动作**，它不该让用户正在读的回答变吵。
 */
function FeedbackRow({ api, conversationId, message, onRated }: {
  api: AssistantApi
  conversationId: number | null
  message: ChatMessage
  onRated: (traceId: string | null, rating: 'up' | 'down', reason?: string | null) => void
}) {
  const [picking, setPicking] = useState(false)
  const [failed, setFailed] = useState(false)
  const rated = message.feedback

  const submit = async (rating: 'up' | 'down', reason?: string) => {
    setFailed(false)
    setPicking(false)
    try {
      await api.submitFeedback({
        conversationId,
        traceId: message.traceId ?? null,
        rating,
        ...(reason === undefined ? {} : { reason: reason as 'off_topic' | 'too_verbose' | 'incorrect' | 'other' }),
      })
      onRated(message.traceId ?? null, rating, reason ?? null)
    } catch {
      setFailed(true)
    }
  }

  return (
    <div className="mt-1 flex flex-wrap items-center gap-1.5" data-testid="conversation-feedback">
      {rated === undefined ? (
        <>
          <Button variant="ghost" size="xs" aria-label="这个回答有用"
            data-testid="feedback-up" onClick={() => { void submit('up') }}>
            <ThumbsUp className="size-3.5" />
          </Button>
          <Button variant="ghost" size="xs" aria-label="这个回答不好"
            data-testid="feedback-down" onClick={() => setPicking(picking !== true)}>
            <ThumbsDown className="size-3.5" />
          </Button>
          {picking ? REASONS.map((reason) => (
            <Button key={reason.value} variant="outline" size="xs"
              data-testid={`feedback-reason-${reason.value}`}
              onClick={() => { void submit('down', reason.value) }}>
              {reason.label}
            </Button>
          )) : null}
        </>
      ) : (
        <span className="text-muted-foreground text-[11px]" data-testid="feedback-done">
          已反馈{rated.rating === 'down' && rated.reason !== null && rated.reason !== undefined
            ? `：${REASONS.find((item) => item.value === rated.reason)?.label ?? rated.reason}`
            : ''}
        </span>
      )}
      {failed ? (
        <span className="text-destructive text-[11px]" data-testid="feedback-failed">
          没提交成功，稍后再试
        </span>
      ) : null}
    </div>
  )
}
