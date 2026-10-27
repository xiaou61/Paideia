import { formatRelative } from '@paideia/core'
import { createKnowledgeApi } from '@paideia/knowledge'
import type { SelfAssessmentLevel } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Button,
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { Target } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

/**
 * 筛选档。用显式的 `'all'` 而不是空串：base-ui 的 Select 把空串当成"没有选中"，
 * 会去显示占位符而不是「全部」。
 */
const LEVEL_ITEMS = [
  { value: 'all', label: '全部' },
  { value: 'understood', label: '懂了' },
  { value: 'unsure', label: '还不懂' },
]

/**
 * 我标记过的条目。
 *
 * <p>这是本项目第一个"关于我自己"的页面：内容来自学习者自己的标记，而不是公共内容库。
 * 它的价值不是好看，而是证明"个人状态"这条数据线是通的——掌握度、学习路径将来都长在这上面。
 *
 * <p>2026-10-15 两处修正：① 页头那个「我的标记」位置标签去掉（导航里已经有「我的自评」，
 * 再挂一个不可点的标签只会把品牌区挤乱）；② 筛选从**原生 `<select>`** 换成设计系统的
 * `Select`（原生控件在深色主题下的下拉样式跟整站对不上，这是这个项目里明确禁过的一种写法）。
 */
export function MyAssessmentsPage() {
  const [level, setLevel] = useState<'all' | SelfAssessmentLevel>('all')

  const mine = useQuery({
    queryKey: ['my-assessments', level],
    queryFn: () => knowledge.myAssessments(level === 'all' ? undefined : level),
  })

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="我标记过的内容"
        description="标记只是你自己的记录，用来挑出还需要再看一遍的内容。"
      />

      <div className="mb-6 flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground text-xs">只看</span>
          <Select
            items={LEVEL_ITEMS}
            value={level}
            onValueChange={(value) => setLevel(value as 'all' | SelfAssessmentLevel)}
          >
            <SelectTrigger size="sm" className="w-32" data-testid="my-assessment-filter">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {LEVEL_ITEMS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {mine.isSuccess && mine.data.length > 0 ? (
          <span className="text-muted-foreground text-xs" data-testid="my-assessment-total">
            共 {mine.data.length} 条
          </span>
        ) : null}
        <Link
          to="/knowledge"
          className="text-muted-foreground hover:text-foreground ml-auto text-sm underline-offset-4 transition-colors hover:underline"
        >
          ← 回到知识库
        </Link>
      </div>

      {mine.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="my-assessment-state">
          <Spinner />
          加载中…
        </p>
      ) : null}
      {mine.isError ? (
        <Alert variant="destructive" data-testid="my-assessment-state">
          <AlertTitle>加载失败</AlertTitle>
          <AlertDescription>{(mine.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {mine.isSuccess && mine.data.length === 0 ? (
        <Empty data-testid="my-assessment-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <Target />
            </EmptyMedia>
            <EmptyTitle>还没有标记过任何内容</EmptyTitle>
            <EmptyDescription>
              打开一条内容，用「懂了 / 还不懂」标一下，之后就能在这里找到它。
            </EmptyDescription>
          </EmptyHeader>
          {/* 空态必须给一个能立刻做的动作：只说"去标一下"而不给入口，等于让人自己找路 */}
          <EmptyContent>
            <Button render={<Link to="/knowledge" />} data-testid="my-assessment-empty-action">
              去知识库挑一条
            </Button>
          </EmptyContent>
        </Empty>
      ) : null}

      {mine.isSuccess && mine.data.length > 0 ? (
        <div className="border-border divide-border divide-y overflow-hidden rounded-xl border" data-testid="my-assessment-list">
          {mine.data.map((item) => (
            <Link
              key={item.entryId}
              to={`/knowledge/entries/${item.entryId}`}
              className="hover:bg-accent/40 group flex items-center gap-3 px-4 py-3.5 transition-colors sm:gap-4 sm:px-5"
            >
              {/* 状态用语义色：懂了=success、还不懂=warning。原来一个 outline 一个 secondary，
                  两种灰在列表里几乎分不出来 */}
              <Badge variant={item.level === 'unsure' ? 'warning' : 'success'}>
                {item.level === 'unsure' ? '还不懂' : '懂了'}
              </Badge>
              <span className="group-hover:text-primary min-w-0 flex-1 truncate text-sm font-medium transition-colors">
                {item.title}
              </span>
              {/* 时间在窄屏不隐藏：它是"我最近判过哪条"的判断依据之一，藏掉等于丢信息。
                  标题已经 `truncate`，挤压只落在标题上，不撑破行 */}
              <span className="text-muted-foreground shrink-0 text-[0.6875rem] sm:text-xs">{formatRelative(item.updatedAt)}</span>
            </Link>
          ))}
        </div>
      ) : null}
    </AppShell>
  )
}
