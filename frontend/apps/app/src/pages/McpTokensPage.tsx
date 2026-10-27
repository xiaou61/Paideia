import { useAuth } from '@paideia/auth'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  Checkbox,
  Input,
  Label,
  PageHeader,
  Spinner,
} from '@paideia/ui'
import { useState } from 'react'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'
import { createMcpTokensApi } from '../mcp-tokens-api'

const tokens = createMcpTokensApi(api)

const SCOPE_LABELS: Record<string, string> = { read: '只读', write: '写入', admin: '管理' }

/**
 * 可选工具组。
 *
 * <p>**这是 2026-10-22 补的**：在此之前权限只有「只读/写入」两档，工具组只能靠改 URL 路径
 * （`/mcp/x/knowledge`）挑，界面上没有任何入口。用户的原话是「可以控制的权限太少了」。
 *
 * <p>组名与后端 `ToolCatalog` 的工具组的 `toolset` 字段一一对应；新增组时这里补一条即可，
 * 后端不校验组名（传错只会让凭据看起来什么都不能调，不会放大权限）。
 */
const TOOLSETS: ReadonlyArray<{ key: string; label: string; hint: string }> = [
  { key: 'knowledge', label: '知识库', hint: '检索条目、读正文、列分类' },
  { key: 'note', label: '笔记', hint: '列出、读取、新建、追加' },
  { key: 'assessment', label: '自评', hint: '看标记、打「懂了/还不懂」' },
  { key: 'learning', label: '学习进度', hint: '读到哪、下一站是哪' },
  { key: 'account', label: '账号资料', hint: '身份与目标岗位' },
  { key: 'assistant', label: '平台助手', hint: '把问题交给平台 AI 回答' },
  { key: 'sql', label: 'SQL 数据集', hint: '看表结构与字段' },
  { key: 'resume', label: '简历', hint: '列出与读取我的简历' },
  { key: 'interview', label: '面试评估', hint: '历次模拟面试的评分与建议' },
  { key: 'review', label: '复习', hint: '待复习条目与复习计划' },
  { key: 'memory', label: '记忆', hint: '助手记住的关于我的事' },
  { key: 'admin_content', label: '内容管理', hint: '分类/条目/概念/关系的增改删' },
  { key: 'admin_users', label: '用户与积分', hint: '账号、角色、积分流水与调整' },
  { key: 'admin_ai', label: 'AI 观测', hint: '调用概览、趋势、明细与反馈' },
  { key: 'admin_review', label: '举报与数据集', hint: '笔记举报下架、数据集治理' },
  { key: 'admin_learning', label: '题目标注', hint: '题目概念标注与评估导出' },
  { key: 'admin_ops', label: '运维', hint: '服务、中间件与备份状态' },
  { key: 'admin_digest', label: '速递', hint: '每日速递条目查看与删除' },
]

/**
 * MCP 接入凭据（WORK-019）。
 *
 * <p>页面只做三件事：看一眼有哪些凭据、建一个、吊销一个。**没有任何"再显示一次"的按钮**——
 * 服务端不保存明文，刷新之后拿不回来；把这句话写在界面上，比事后解释好。
 *
 * <p>创建成功后明文卡片**留在页面上直到用户离开**：立刻切走会让"我还没来得及复制"变成
 * 一个无法补救的问题。
 */
export function McpTokensPage() {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const isAdmin = user?.role === 'admin'
  const { data, isLoading, error } = useQuery({ queryKey: ['mcp-tokens'], queryFn: () => tokens.list() })

  const [name, setName] = useState('')
  const [write, setWrite] = useState(false)
  const [adminScope, setAdminScope] = useState(false)
  const [neverExpires, setNeverExpires] = useState(false)
  /** 每日调用上限的输入值；空串 = 不限（WORK-044）。 */
  const [dailyLimit, setDailyLimit] = useState('')
  /** 勾选了的工具组；**空集合 = 全部**（与改动前签发的凭据语义一致）。 */
  const [toolsets, setToolsets] = useState<string[]>([])
  const [created, setCreated] = useState<string | null>(null)
  const [copyState, setCopyState] = useState<'idle' | 'copied'>('idle')
  const [createError, setCreateError] = useState<string | null>(null)

  const createToken = useMutation({
    mutationFn: () =>
      tokens.create({
        name: name.trim(),
        scopes: ['read', ...(write ? ['write'] : []), ...(isAdmin && adminScope ? ['admin'] : [])],
        toolsets,
        expiresInDays: neverExpires ? 0 : 90,
        // 空串与 0 都表示不限：服务端把 0 归一成 null
        dailyCallLimit: dailyLimit.trim() === '' ? 0 : Number(dailyLimit),
      }),
    onSuccess: async (result) => {
      setCreated(result.token)
      setCopyState('idle')
      setName('')
      setDailyLimit('')
      setCreateError(null)
      await queryClient.invalidateQueries({ queryKey: ['mcp-tokens'] })
    },
    onError: (cause: unknown) => {
      // 不动 created：上一份明文可能还没被复制，而服务端不留它——清掉等于永久丢一把仍有效的钥匙
      setCreateError(cause instanceof Error ? cause.message : String(cause))
    },
  })

  const revokeToken = useMutation({
    mutationFn: (id: number) => tokens.revoke(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['mcp-tokens'] })
    },
  })

  const revokeNow = (id: number, tokenName: string) => {
    // 二次确认：吊销不可撤销，而列表里每行长得一样，误点一次就等于让对方重新配一遍
    const confirmed = window.confirm(`吊销「${tokenName}」？用到它的外部助手会立刻失去访问权，无法恢复。`)
    if (confirmed) {
      revokeToken.mutate(id)
    }
  }

  const copy = async () => {
    if (created === null) {
      return
    }
    try {
      await navigator.clipboard.writeText(created)
      setCopyState('copied')
    } catch {
      // 剪贴板可能被浏览器策略拒绝：不报错，让用户手动选中复制
      setCopyState('idle')
    }
  }

  const toggleToolset = (key: string, on: boolean) => {
    setToolsets((previous) => (on ? [...previous, key] : previous.filter((item) => item !== key)))
  }

  const nameMissing = name.trim() === ''

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />}>
      <div className="mx-auto max-w-3xl space-y-6">
        <PageHeader
          title="MCP 接入"
          description="让 ZCode、Claude Desktop 这类外部助手直接读写你的知识库与笔记。每个凭据只显示一次明文，丢了就重新建一个。"
        />

        {created === null ? null : (
          <Card className="border-primary/40">
            <CardContent className="space-y-3 pt-6">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-medium">凭据已创建</p>
                  <p className="text-muted-foreground text-xs">
                    只显示这一次。刷新页面后无法再查看，丢了只能吊销重建。
                  </p>
                </div>
                <Button size="sm" variant="outline" data-testid="mcp-token-copy" onClick={() => { void copy() }}>
                  {copyState === 'copied' ? '已复制' : '复制'}
                </Button>
              </div>
              <code
                data-testid="mcp-token-plaintext"
                className="bg-muted block w-full overflow-x-auto rounded-md p-3 font-mono text-xs"
              >
                {created}
              </code>
            </CardContent>
          </Card>
        )}

        <Card>
          <CardContent className="space-y-5 pt-6">
            <div>
              <p className="text-sm font-medium">新建凭据</p>
              <p className="text-muted-foreground mt-1 text-xs">名称用于自己区分用途，不影响权限。</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="mcp-token-name">名称</Label>
              <Input
                id="mcp-token-name"
                data-testid="mcp-token-name"
                placeholder="例如：家里的 Claude Desktop"
                value={name}
                onChange={(event) => { setName(event.target.value) }}
              />
            </div>

            {/* 工具组：不勾任何一项 = 全部工具组 */}
            <div className="space-y-2">
              <Label>可以访问</Label>
              <div className="grid gap-2 sm:grid-cols-2" data-testid="mcp-token-toolsets">
                {(isAdmin ? TOOLSETS : TOOLSETS.filter((item) => !item.key.startsWith('admin_'))).map((item) => {
                  const checked = toolsets.includes(item.key)
                  return (
                    <label
                      key={item.key}
                      className="border-border hover:border-primary/40 flex cursor-pointer items-start gap-2.5 rounded-lg border p-3 transition-colors"
                    >
                      <Checkbox
                        className="mt-0.5"
                        data-testid={`mcp-toolset-${item.key}`}
                        checked={checked}
                        onCheckedChange={(value) => { toggleToolset(item.key, value === true) }}
                      />
                      <span className="min-w-0">
                        <span className="block text-sm font-medium">{item.label}</span>
                        <span className="text-muted-foreground block text-xs">{item.hint}</span>
                      </span>
                    </label>
                  )
                })}
              </div>
              <p className="text-muted-foreground text-xs">
                {toolsets.length === 0
                  ? '一项都不勾 = 全部工具组都能访问。'
                  : `只允许 ${toolsets.length} 个组；其余组在外部助手的清单里根本不会出现。`}
              </p>
            </div>

            <div className="space-y-2.5">
              <label className="flex cursor-pointer items-center gap-2.5 text-sm">
                <Checkbox
                  data-testid="mcp-token-write"
                  checked={write}
                  onCheckedChange={(value) => { setWrite(value === true) }}
                />
                允许写入（建笔记、打自评标记）
              </label>
              {isAdmin ? (
                <label className="flex cursor-pointer items-center gap-2.5 text-sm">
                  <Checkbox
                    data-testid="mcp-token-admin"
                    checked={adminScope}
                    onCheckedChange={(value) => { setAdminScope(value === true) }}
                  />
                  允许后台管理（管理工具需要这项；只有管理员能建）
                </label>
              ) : null}
              <label className="flex cursor-pointer items-center gap-2.5 text-sm">
                <Checkbox
                  data-testid="mcp-token-never-expires"
                  checked={neverExpires}
                  onCheckedChange={(value) => { setNeverExpires(value === true) }}
                />
                永不过期（默认 90 天）
              </label>
            </div>

            <div className="space-y-2">
              <Label htmlFor="mcp-token-daily-limit">每日调用上限</Label>
              <Input
                id="mcp-token-daily-limit"
                data-testid="mcp-token-daily-limit"
                type="number"
                min={0}
                placeholder="不填 = 不限"
                value={dailyLimit}
                onChange={(event) => { setDailyLimit(event.target.value) }}
              />
              <p className="text-muted-foreground text-xs">
                到上限后这个凭据当天不能再调，第二天自动恢复。按 UTC 日切。
              </p>
            </div>

            <div className="space-y-2">
              <Button
                data-testid="mcp-token-create"
                disabled={createToken.isPending || nameMissing}
                onClick={() => { createToken.mutate() }}
              >
                {createToken.isPending ? '创建中…' : '创建凭据'}
              </Button>
              {nameMissing ? (
                <p className="text-muted-foreground text-xs">先填一个名称，下方的「创建凭据」才会亮起来。</p>
              ) : null}
            </div>

            {createError === null ? null : (
              <Alert variant="destructive">
                <AlertTitle>没建成功</AlertTitle>
                <AlertDescription>{createError}</AlertDescription>
              </Alert>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardContent className="space-y-3 pt-6">
            <p className="text-sm font-medium">我的凭据</p>
            {isLoading ? <Spinner /> : null}
            {error === null ? null : (
              <Alert variant="destructive">
                <AlertTitle>读不到凭据列表</AlertTitle>
                <AlertDescription>{error instanceof Error ? error.message : String(error)}</AlertDescription>
              </Alert>
            )}
            {data !== undefined && data.length === 0 ? (
              <p className="text-muted-foreground text-sm" data-testid="mcp-token-empty">
                还没有任何凭据。
              </p>
            ) : null}
            <ul className="space-y-3">
              {(data ?? []).map((token) => (
                <li
                  key={token.id}
                  data-testid="mcp-token-row"
                  className="border-border hover:border-primary/30 flex flex-wrap items-start justify-between gap-3 rounded-lg border p-4 transition-colors"
                >
                  <div className="min-w-0 flex-1 space-y-1.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="truncate text-sm font-medium">{token.name}</span>
                      {token.active ? (
                        <Badge variant="success">有效</Badge>
                      ) : (
                        <Badge variant="destructive">已失效</Badge>
                      )}
                    </div>
                    <p className="text-muted-foreground font-mono text-xs">{token.prefix}…</p>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {token.scopes.map((scope) => (
                        <Badge key={scope} variant="secondary">
                          {SCOPE_LABELS[scope] ?? scope}
                        </Badge>
                      ))}
                      {token.toolsets.length === 0 ? (
                        <Badge variant="outline">全部工具组</Badge>
                      ) : (
                        token.toolsets.map((toolset) => (
                          <Badge key={toolset} variant="outline">
                            {TOOLSETS.find((item) => item.key === toolset)?.label ?? toolset}
                          </Badge>
                        ))
                      )}
                    </div>
                    <p className="text-muted-foreground text-xs">
                      {token.expiresAt === null ? '永不过期' : `到期 ${formatDate(token.expiresAt)}`}
                      {' · '}
                      最近使用 {token.lastUsedAt === null ? '从未' : formatDate(token.lastUsedAt)}
                    </p>
                    <p className="text-muted-foreground text-xs" data-testid="mcp-token-usage">
                      {token.dailyCallLimit === null
                        ? `今日 ${token.callsToday} 次（不限）`
                        : `今日 ${token.callsToday} / ${token.dailyCallLimit} 次`}
                      {' · '}
                      累计 {token.totalCalls} 次
                      {token.totalErrors > 0 ? ` · 失败 ${token.totalErrors} 次` : ''}
                    </p>
                  </div>
                  {token.revokedAt === null ? (
                    <Button
                      size="sm"
                      variant="outline"
                      data-testid="mcp-token-revoke"
                      disabled={revokeToken.isPending}
                      onClick={() => { revokeNow(token.id, token.name) }}
                    >
                      吊销
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="space-y-2 pt-6 text-sm">
            <p className="font-medium">怎么接</p>
            <p className="text-muted-foreground">
              在外部助手的 MCP 配置里填地址与本页签发的 token：
            </p>
            <code className="bg-muted block overflow-x-auto rounded-md p-3 font-mono text-xs">
              {`{"type":"http","url":"<站点地址>/mcp","headers":{"Authorization":"Bearer <token>"}}`}
            </code>
            <p className="text-muted-foreground text-xs">
              <code>/mcp</code> 是只读默认视图；<code>/mcp/x/all</code> 是全部只读；
              写工具需要上面勾过「允许写入」。工具组已在凭据里限定，不必再改路径。
            </p>
          </CardContent>
        </Card>
      </div>
    </AppShell>
  )
}

function formatDate(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}
