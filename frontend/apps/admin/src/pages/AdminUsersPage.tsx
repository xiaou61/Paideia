import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Card,
  CardContent,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Input,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@paideia/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Users } from 'lucide-react'
import { useEffect, useState } from 'react'

import { api } from '../api'
import { ConfirmDeleteDialog } from '../knowledge-shared'
import { errorMessage, formatLocalDateTime } from '@paideia/core'

/**
 * 用户管理（WORK-030）。
 *
 * <p><b>这一页替换的是「积分调整」</b>：那一页只有一个「用户 id」文本框，而它自己的注释
 * 写着「用户 id 是内部主键，敲错一位就会给另一个人发分」。发积分不可撤销，正确性却挂在
 * 人工核对一串数字上——这一页把那个动作换成「搜到人、看见他是谁、再操作」。
 *
 * <p>四条写在页面上的约束：
 * <ul>
 *   <li>搜索**输入即筛**（300ms 防抖），不做"回车才查"；</li>
 *   <li>调积分**必须填原因**（后端也强制），流水要能回答"为什么给他发"；</li>
 *   <li>**不能操作自己**——改自己角色会把自己锁在管理端外面；按钮变灰只是体验，后端同样拒绝；</li>
 *   <li>删除是二次确认，且不可逆（与自助注销共用同一段级联）。</li>
 * </ul>
 */
interface UserRow {
  id: number
  username: string
  email: string
  role: 'admin' | 'learner'
  createdAt: string | null
  balance: number
  usedCredits: number
  runCount: number
  lastActiveAt: string | null
}

interface UserPage {
  total: number
  page: number
  size: number
  items: UserRow[]
}

const PAGE_SIZE = 20

const ROLE_FILTERS = [
  { value: 'all', label: '全部角色' },
  { value: 'admin', label: '管理员' },
  { value: 'learner', label: '学习者' },
]

function listUsers(query: string, role: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) })
  if (query.trim() !== '') {
    params.set('query', query.trim())
  }
  if (role !== 'all') {
    params.set('role', role)
  }
  return api.requestEnvelope<UserPage>(`/api/v1/account/admin/users?${params.toString()}`)
}

/** 时间按本机时区显示；解析不出来就原样显示。 */
function when(value: string | null): string {
  return formatLocalDateTime(value)
}

export function AdminUsersPage() {
  const queryClient = useQueryClient()
  const [keyword, setKeyword] = useState('')
  const [debounced, setDebounced] = useState('')
  const [role, setRole] = useState('all')
  const [page, setPage] = useState(1)
  const [error, setError] = useState<string | null>(null)
  const [pendingDelete, setPendingDelete] = useState<UserRow | null>(null)
  const [editing, setEditing] = useState<UserRow | null>(null)

  // 输入即筛：300ms 防抖，避免每敲一个字符就查一次库
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebounced(keyword)
      setPage(1)
    }, 300)
    return () => clearTimeout(timer)
  }, [keyword])

  const users = useQuery({
    queryKey: ['admin-users', debounced, role, page],
    queryFn: () => listUsers(debounced, role, page),
  })

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ['admin-users'] })
  }

  const changeRole = useMutation({
    mutationFn: (input: { id: number; role: string }) =>
      api.requestEnvelope<UserRow>(`/api/v1/account/admin/users/${input.id}/role`, {
        method: 'POST',
        body: JSON.stringify({ role: input.role }),
      }),
    onSuccess: async () => {
      setEditing(null)
      await invalidate()
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const adjustCredits = useMutation({
    mutationFn: (input: { id: number; delta: number; note: string }) =>
      api.requestEnvelope<{ userId: number; balance: number }>(
        `/api/v1/account/admin/users/${input.id}/credits`,
        {
          method: 'POST',
          body: JSON.stringify({ delta: input.delta, note: input.note }),
        },
      ),
    onSuccess: async () => {
      setEditing(null)
      await invalidate()
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const remove = useMutation({
    mutationFn: (id: number) =>
      api.requestEnvelope<boolean>(`/api/v1/account/admin/users/${id}`, { method: 'DELETE' }),
    onSuccess: async () => {
      setPendingDelete(null)
      await invalidate()
    },
    onError: (failure: Error) => setError(failure.message),
  })

  const rows = users.data?.items ?? []
  const totalPages = Math.max(1, Math.ceil((users.data?.total ?? 0) / PAGE_SIZE))

  return (
    <>
      <PageHeader
        title="用户管理"
        description="搜到人再操作：看他的积分与 AI 消耗、调积分、改角色、删除账号。"
      />

      {error === null ? null : (
        <Alert variant="destructive" className="mb-6" data-testid="admin-user-error">
          <AlertTitle>操作失败</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
          <Button variant="ghost" size="sm" className="mt-2" onClick={() => setError(null)}>
            知道了
          </Button>
        </Alert>
      )}

      <Card>
        <CardContent className="px-0">
          <div className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-(--card-spacing) py-3">
            <div>
              <h2 className="text-sm font-medium">账号</h2>
              <p className="text-muted-foreground mt-0.5 text-xs" data-testid="admin-user-total">
                {users.isSuccess ? `共 ${users.data.total} 个账号` : '加载中…'}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Input
                className="h-9 w-56"
                placeholder="按用户名或邮箱搜索"
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                data-testid="admin-user-search"
              />
              <Select
                items={ROLE_FILTERS}
                value={role}
                onValueChange={(value) => {
                  setRole(value as string)
                  setPage(1)
                }}
              >
                <SelectTrigger size="sm" className="w-32" data-testid="admin-user-role-filter">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLE_FILTERS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          {users.isPending ? <Spinner /> : null}

          {users.isError ? (
            <div className="px-(--card-spacing) pb-3">
              <Alert variant="destructive">
                <AlertTitle>账号加载失败</AlertTitle>
                <AlertDescription>{errorMessage(users.error)}</AlertDescription>
              </Alert>
            </div>
          ) : null}

          {users.isSuccess && rows.length === 0 ? (
            <Empty data-testid="admin-users-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <Users />
                </EmptyMedia>
                <EmptyTitle>没有匹配的用户</EmptyTitle>
                <EmptyDescription>换个用户名或邮箱片段再试。</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : null}

          {rows.length > 0 ? (
            <>
              <Table data-testid="admin-users-table">
                <TableHeader>
                  <TableRow>
                    <TableHead>用户名</TableHead>
                    <TableHead>邮箱</TableHead>
                    <TableHead>角色</TableHead>
                    <TableHead className="text-right">余额</TableHead>
                    <TableHead className="text-right">已用</TableHead>
                    <TableHead className="text-right">调用</TableHead>
                    <TableHead>最近活跃</TableHead>
                    <TableHead>注册时间</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((row) => (
                    <TableRow key={row.id} data-testid={`admin-user-row-${row.id}`}>
                      <TableCell className="font-medium">{row.username}</TableCell>
                      <TableCell className="text-muted-foreground text-xs">
                        {/* 第三方注册的用户可能没有邮箱（WORK-037）。空着看起来像数据丢了 */}
                        {row.email ?? '无邮箱'}
                      </TableCell>
                      <TableCell>
                        <Badge variant={row.role === 'admin' ? 'default' : 'secondary'}>
                          {row.role === 'admin' ? '管理员' : '学习者'}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{row.balance}</TableCell>
                      <TableCell className="text-right tabular-nums">{row.usedCredits}</TableCell>
                      <TableCell className="text-right tabular-nums">{row.runCount}</TableCell>
                      <TableCell className="text-muted-foreground text-xs">
                        {when(row.lastActiveAt)}
                      </TableCell>
                      <TableCell className="text-muted-foreground text-xs">
                        {when(row.createdAt)}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="outline"
                            size="xs"
                            onClick={() => setEditing(row)}
                            data-testid={`admin-user-edit-${row.id}`}
                          >
                            调整
                          </Button>
                          <Button
                            variant="ghost"
                            size="xs"
                            onClick={() => setPendingDelete(row)}
                            data-testid={`admin-user-delete-${row.id}`}
                          >
                            删除
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              <div className="border-border text-muted-foreground flex items-center justify-between gap-4 border-t px-(--card-spacing) py-3 text-xs">
                <span>共 {users.data?.total ?? 0} 条</span>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="xs"
                    disabled={page <= 1}
                    onClick={() => setPage(page - 1)}
                  >
                    上一页
                  </Button>
                  <span className="tabular-nums">
                    {page} / {totalPages}
                  </span>
                  <Button
                    variant="outline"
                    size="xs"
                    disabled={page >= totalPages}
                    onClick={() => setPage(page + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </>
          ) : null}
        </CardContent>
      </Card>

      {editing === null ? null : (
        <UserEditor
          user={editing}
          busy={changeRole.isPending || adjustCredits.isPending}
          onChangeRole={(next) => changeRole.mutate({ id: editing.id, role: next })}
          onAdjust={(delta, note) => adjustCredits.mutate({ id: editing.id, delta, note })}
          onClose={() => setEditing(null)}
        />
      )}

      <ConfirmDeleteDialog
        open={pendingDelete !== null}
        title="删除账号"
        label="账号"
        name={pendingDelete?.username ?? ''}
        hint="这个账号的会话、记忆、笔记、简历与积分流水会一并删除，且无法恢复。"
        confirmLabel="永久删除"
        busy={remove.isPending}
        onConfirm={() => {
          if (pendingDelete !== null) {
            remove.mutate(pendingDelete.id)
          }
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </>
  )
}

/**
 * 单个用户的三件事：改角色、调积分、看他自己。
 *
 * <p>用模态而不是跳到新页：跳走会丢掉列表的搜索词与页码，管理员一次要处理好几个人。
 */
function UserEditor({
  user,
  busy,
  onChangeRole,
  onAdjust,
  onClose,
}: {
  user: UserRow
  busy: boolean
  onChangeRole: (role: string) => void
  onAdjust: (delta: number, note: string) => void
  onClose: () => void
}) {
  const [delta, setDelta] = useState('100')
  const [note, setNote] = useState('')

  const amount = Number(delta)
  const valid = Number.isFinite(amount) && amount !== 0 && note.trim() !== ''

  return (
    <div
      className="bg-overlay fixed inset-0 z-50 grid place-items-center p-4"
      role="dialog"
      aria-modal="true"
      data-testid="admin-user-editor"
    >
      <Card className="w-full max-w-lg">
        <CardContent className="space-y-4 py-4">
          <div>
            <h3 className="text-base font-semibold">{user.username}</h3>
            <p className="text-muted-foreground text-xs">{user.email ?? '无邮箱'}</p>
          </div>

          <div className="text-muted-foreground flex flex-wrap gap-3 text-xs">
            <span>余额 {user.balance} 分</span>
            <span>已用 {user.usedCredits} 分</span>
            <span>调用 {user.runCount} 次</span>
            <span>最近活跃 {when(user.lastActiveAt)}</span>
          </div>

          <div className="space-y-2">
            <span className="text-sm font-medium">角色</span>
            <div className="flex gap-2">
              <Button
                variant={user.role === 'learner' ? 'default' : 'outline'}
                size="sm"
                disabled={busy || user.role === 'learner'}
                onClick={() => onChangeRole('learner')}
                data-testid="admin-user-set-learner"
              >
                学习者
              </Button>
              <Button
                variant={user.role === 'admin' ? 'default' : 'outline'}
                size="sm"
                disabled={busy || user.role === 'admin'}
                onClick={() => onChangeRole('admin')}
                data-testid="admin-user-set-admin"
              >
                管理员
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">
              改角色会让这个人的其它设备重新登录一次。
            </p>
          </div>

          <div className="space-y-2">
            <span className="text-sm font-medium">调整积分</span>
            <div className="flex gap-2">
              <Input
                className="h-9 w-28"
                inputMode="numeric"
                value={delta}
                onChange={(event) => setDelta(event.target.value)}
                data-testid="admin-user-delta"
              />
              <Input
                className="h-9 flex-1"
                placeholder="原因（必填，会写进流水）"
                value={note}
                onChange={(event) => setNote(event.target.value)}
                data-testid="admin-user-note"
              />
            </div>
            <p className="text-muted-foreground text-xs">
              正数为发放、负数为回收。调整不可撤销，流水里会留下操作人与原因。
            </p>
          </div>

          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={onClose} data-testid="admin-user-cancel">
              关闭
            </Button>
            <Button
              size="sm"
              disabled={busy || !valid}
              onClick={() => onAdjust(amount, note.trim())}
              data-testid="admin-user-submit"
            >
              调整积分
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
