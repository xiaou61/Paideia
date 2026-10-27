import { type CategoryNode } from '@paideia/knowledge'
import { Card, CardContent, CardHeader, CardTitle, PageHeader } from '@paideia/ui'
import { ChevronRight, FileText, Flag, FolderTree, FilePen, Users } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'

import { api } from '../api'
import { admin } from '../knowledge-shared'

/**
 * 管理端首页：内容规模 + 管理入口。
 *
 * <p>统计值全部取真实接口（分类树与条目列表本就要为管理页加载），不额外加计数端点——
 * 三个数字不值得多一条后端查询。加载中显示 `…` 而不是 0：把"还不知道"画成 0 是在说谎。
 *
 * <p>没有的模块不摆假入口，只留一句说明，等对应需求进来再加。
 */
export function AdminHomePage() {
  const categories = useQuery({
    queryKey: ['admin-knowledge-categories'],
    queryFn: () => admin.categories(),
  })
  const entries = useQuery({
    queryKey: ['admin-knowledge-entries', 'all'],
    queryFn: () => admin.entries(),
  })

  // 运营数字（WORK-030）：原先只有知识库三个数，进后台看不出平台在跑什么。
  // 账号总数与待处理举报都走已有的管理端接口，不新开计数端点。
  const users = useQuery({
    queryKey: ['admin-home-users'],
    queryFn: () => api.requestEnvelope<{ total: number }>('/api/v1/account/admin/users?size=1'),
  })
  const reports = useQuery({
    queryKey: ['admin-home-reports'],
    queryFn: () =>
      api.requestEnvelope<unknown[]>('/api/v1/notes/admin/reports?status=pending'),
  })

  const items = entries.data?.items ?? []
  const stats = [
    { label: '分类', icon: FolderTree, value: categories.data === undefined ? undefined : count(categories.data) },
    { label: '条目', icon: FileText, value: entries.data?.total },
    { label: '其中草稿', icon: FilePen, value: entries.data === undefined ? undefined : items.filter((item) => item.status === 'draft').length },
    { label: '账号', icon: Users, value: users.data?.total, to: '/users' },
    { label: '待处理举报', icon: Flag, value: reports.data?.length, to: '/reports' },
  ]

  return (
    <>
      <PageHeader title="总览" description="管理端入口与当前内容规模。" />

      <div className="mb-6 grid gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {stats.map((stat) => (
          <Card key={stat.label} className="hover:shadow-card transition-shadow">
            <CardContent className="flex items-center justify-between gap-4">
              <div>
                <div className="text-muted-foreground text-xs">{stat.label}</div>
                <div className="mt-1 text-2xl font-semibold tabular-nums">
                  {stat.value === undefined ? '…' : stat.value}
                </div>
              </div>
              <span className="tile-surface border-border text-primary grid size-10 shrink-0 place-items-center rounded-lg border">
                <stat.icon className="size-4" />
              </span>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>内容管理</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <Link
            to="/knowledge/categories"
            data-testid="admin-categories-link"
            className="border-border hover:border-primary/40 hover:bg-accent/40 group flex items-center justify-between gap-4 rounded-xl border px-4 py-3.5 transition-all hover:-translate-y-0.5"
          >
            <span className="min-w-0">
              <span className="group-hover:text-primary block text-sm font-medium transition-colors">分类管理</span>
              <span className="text-muted-foreground mt-0.5 block text-xs">
                层级树；条目要挂在分类上，所以先有分类。
              </span>
            </span>
            <ChevronRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
          </Link>

          <Link
            to="/knowledge/entries"
            data-testid="admin-entries-link"
            className="border-border hover:border-primary/40 hover:bg-accent/40 group flex items-center justify-between gap-4 rounded-xl border px-4 py-3.5 transition-all hover:-translate-y-0.5"
          >
            <span className="min-w-0">
              <span className="group-hover:text-primary block text-sm font-medium transition-colors">条目管理</span>
              <span className="text-muted-foreground mt-0.5 block text-xs">
                录入与发布；发布前可用预览确认学习者看到的样子。
              </span>
            </span>
            <ChevronRight className="text-muted-foreground group-hover:text-primary size-4 shrink-0 transition-colors" />
          </Link>
        </CardContent>
      </Card>
    </>
  )
}

/** 分类树的节点总数（含各级子分类）。 */
function count(nodes: CategoryNode[]): number {
  return nodes.reduce((total, node) => total + 1 + count(node.children), 0)
}
