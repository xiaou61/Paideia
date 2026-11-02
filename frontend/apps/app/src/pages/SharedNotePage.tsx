import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Spinner,
} from '@paideia/ui'
import { useAuth } from '@paideia/auth'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'

import { api } from '../api'
import { errorMessage } from '@paideia/core'

/** 分享端点返回的形状。**没有作者、没有时间**——服务端就不给。 */
interface SharedNote {
  title: string
  body: string
  entryId: number | null
}

/**
 * 只读分享页。**不需要登录**（用户裁决 12）。
 *
 * <p><b>用裸 fetch 而不是应用的 `api` 客户端</b>：那个客户端会附令牌、并在 401 时刷新重放——
 * 这两件事在这个页面上都没有意义（匿名访问者没有令牌，而这个端点也从不返回 401）。
 * 走它反而会让一次"分享链接失效"触发一次没必要的刷新尝试。
 *
 * <p>这里**不做 markdown 渲染**：分享的是用户自己的笔记（纯文本），而渲染 md 会引入
 * "笔记里的 HTML/链接在别人浏览器里执行"这一整类问题。站外页面按纯文本显示最稳。
 */
export function SharedNotePage() {
  const { shareId } = useParams<{ shareId: string }>()
  const { status } = useAuth()
  const [note, setNote] = useState<SharedNote | null>(null)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reportOpen, setReportOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [reported, setReported] = useState(false)

  useEffect(() => {
    let alive = true
    if (shareId === undefined) {
      setMissing(true)
      return
    }
    fetch(`/api/v1/public/shares/${encodeURIComponent(shareId)}`)
      .then(async (response) => {
        if (!alive) {
          return
        }
        if (response.status === 404) {
          // 过期、撤回、下架、编造的 id —— 服务端统一 404，这里也统一一句话，
          // 不区分原因（区分开等于告诉访问者"这条分享曾经存在"）
          setMissing(true)
          return
        }
        if (!response.ok) {
          throw new Error(`请求失败：HTTP ${response.status}`)
        }
        const envelope = (await response.json()) as { data: SharedNote }
        setNote(envelope.data)
      })
      .catch((cause: unknown) => {
        if (alive) {
          setError(errorMessage(cause))
        }
      })
    return () => {
      alive = false
    }
  }, [shareId])

  return (
    // 同一个 AppShell：分享页是免登录的独立面，但**观感要与站内一致**——
    // 不做的话点开一条分享链接会像跳到了另一个网站。
    <AppShell title="Paideia">

      {error === null ? null : (
        <Alert variant="destructive">
          <AlertTitle>没能打开</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {missing ? (
        <p className="text-muted-foreground" data-testid="share-missing">
          这条分享不存在或已失效。
        </p>
      ) : note === null ? (
        error === null ? <Spinner /> : null
      ) : (
        <Card>
          <CardHeader>
            <CardTitle data-testid="share-title">{note.title}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="whitespace-pre-wrap" data-testid="share-body">
              {note.body}
            </p>
            {note.entryId === null ? null : (
              <p className="text-muted-foreground mt-4 text-xs" data-testid="share-entry">
                相关条目 #{note.entryId}
              </p>
            )}

            {/*
              举报入口。**分享页是免登录的，但举报需要身份**：接口按"举报人有账号"设计
              （表里是 reporter_user_id），所以未登录时给的是登录引导而不是一个点了会 401 的按钮。
              这也是免鉴权分享面唯一的治理手段——没有它，被分享出去的不当内容只能靠管理员主动发现。
            */}
            <div className="border-border mt-6 border-t pt-3">
              {status !== 'signed-in' ? (
                <p className="text-muted-foreground text-xs" data-testid="share-report-need-login">
                  觉得内容不合适？<Link to="/login" className="underline">登录</Link>后可以举报。
                </p>
              ) : reported ? (
                <p className="text-muted-foreground text-xs" data-testid="share-reported">
                  已提交举报，管理员会处理。
                </p>
              ) : reportOpen ? (
                <div className="space-y-2">
                  <textarea
                    value={reason}
                    rows={2}
                    placeholder="说明一下问题（会转给管理员）"
                    aria-label="举报理由"
                    data-testid="share-report-reason"
                    onChange={(event) => setReason(event.target.value)}
                    className="border-border bg-background w-full rounded border px-2 py-1 text-base sm:text-sm"
                  />
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      disabled={reason.trim() === ''}
                      data-testid="share-report-submit"
                      onClick={() => {
                        void api
                          .requestEnvelope<boolean>('/api/v1/reports', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ shareId, reason }),
                          })
                          .then(() => {
                            setError(null)
                            setReported(true)
                            setReportOpen(false)
                          })
                          .catch((cause: unknown) =>
                            setError(errorMessage(cause)),
                          )
                      }}
                    >
                      提交举报
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setReportOpen(false)}>
                      取消
                    </Button>
                  </div>
                </div>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
                  data-testid="share-report-open"
                  onClick={() => setReportOpen(true)}
                >
                  举报这条分享
                </Button>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </AppShell>
  )
}
