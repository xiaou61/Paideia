import { createKnowledgeApi, plainText } from '@paideia/knowledge'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  AppShell,
  Badge,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Button,
  PageHeader,
  Section,
  Spinner,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, FolderOpen, Layers } from 'lucide-react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

/**
 * 知识库首屏：**分类卡片网格**（取代原来的左侧窄树）。
 *
 * <p>原来把分类塞进一个 16rem 的侧栏里，一屏看得到七八条、每条只有名字，整页因此显得又空又单调
 * （用户反馈："分类这里就不能弄成二级页面那样吗……页面过于单调"）。改成"先看分类、点进去才是条目"
 * 之后：卡片能放下名字、子分类与条目去向，点击目标也大得多。
 *
 * <p>2026-10-15 再进一步：卡片按参考的文档站那样**加一块封面**（色块 + 点阵纹理 + 归类图标），
 * 名字从"小字一行"提到 `text-lg`，子分类从徽章改成文字清单。上一版的卡片是一枚小徽章加几行
 * 小字，整页因此像一张没填完的表。
 */
export function KnowledgeCategoriesPage() {
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  // 分类下面那块"最近更新"用得到：分类只有三五个时，光一个网格会让下半屏空着
  const recent = useQuery({
    queryKey: ['knowledge-entries', null, '', 'recent'],
    queryFn: () => knowledge.entries({ size: 6 }),
  })

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="挑一个分类读起"
        description="分类是层级树；点进去是这个分类下的条目。不想挑分类就直奔全部条目。"
      />

      <Link
        to="/knowledge/entries"
        data-testid="all-entries-card"
        className="border-border from-primary/10 to-background hover:border-primary/40 hover:shadow-card group mb-8 flex items-center justify-between rounded-xl border bg-gradient-to-r p-5 transition-all hover:-translate-y-0.5"
      >
        <span className="flex items-center gap-4">
          <span className="bg-primary text-primary-foreground flex size-11 items-center justify-center rounded-xl">
            <Layers className="size-5" />
          </span>
          <span>
            <span className="block font-medium">全部条目</span>
            <span className="text-muted-foreground block text-sm">不挑分类，按更新时间看全部内容</span>
          </span>
        </span>
        <ArrowRight className="text-muted-foreground group-hover:text-primary size-5 transition-colors" />
      </Link>

      {categories.isPending ? (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="category-state">
          <Spinner />
          加载分类…
        </p>
      ) : null}
      {categories.isError ? (
        <Alert variant="destructive" data-testid="category-state">
          <AlertTitle>分类加载失败</AlertTitle>
          <AlertDescription>{(categories.error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {categories.isSuccess && categories.data.length === 0 ? (
        <Empty data-testid="category-empty">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FolderOpen />
            </EmptyMedia>
            <EmptyTitle>还没有建分类</EmptyTitle>
            <EmptyDescription>分类由管理员在管理端建立。</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}

      {categories.isSuccess && categories.data.length > 0 ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" data-testid="category-grid">
          {categories.data.map((category, index) => (
            <Link
              key={category.id}
              to={`/knowledge/categories/${category.id}`}
              data-testid={`category-card-${category.id}`}
              className="border-border bg-card hover:border-primary/40 hover:shadow-card group animate-fade-up flex flex-col overflow-hidden rounded-xl border transition-all hover:-translate-y-0.5"
              // 逐个淡入：一次性铺满的网格没有任何"进入"的感觉
              style={{ animationDelay: `${index * 40}ms` }}
            >
              <span className="tile-surface border-border relative flex h-24 items-center justify-center border-b">
                <FolderOpen className="text-primary/70 group-hover:text-primary size-8 transition-colors" />
                {/* 学习轨道与普通分类混在一起，但前者有关卡顺序；标出来才知道去 /learn 有一条引导路线 */}
                {category.lineKind ? (
                  <Badge variant="secondary" className="absolute top-3 right-3">
                    从零开始
                  </Badge>
                ) : null}
              </span>
              <span className="flex flex-1 flex-col p-5">
                <span className="group-hover:text-primary text-lg transition-colors">{category.name}</span>
                <span className="text-muted-foreground mt-1 text-xs">
                  {category.children.length === 0
                    ? '这个分类下没有子分类'
                    : `${category.children.length} 个子分类`}
                </span>
                {category.children.length === 0 ? null : (
                  <span className="mt-3 flex flex-1 flex-wrap gap-1.5">
                    {category.children.slice(0, 4).map((child) => (
                      <Badge key={child.id} variant="secondary">
                        {child.name}
                      </Badge>
                    ))}
                    {category.children.length > 4 ? (
                      <Badge variant="outline">+{category.children.length - 4}</Badge>
                    ) : null}
                  </span>
                )}
                <span className="text-muted-foreground group-hover:text-primary mt-4 flex items-center gap-1 text-xs transition-colors">
                  进入分类
                  <ArrowRight className="size-3.5" />
                </span>
              </span>
            </Link>
          ))}
        </div>
      ) : null}

      {/*
        分类下面是"最近更新"。分类只有三五个时，光一个网格会让整页在下半屏空着
        （用户对上一版的抱怨之一就是"这么多空白"）。这块填的是**真内容**：最近更新的条目，
        顺带给不想挑分类的人第二条进路。
      */}
      {recent.isSuccess && recent.data.items.length > 0 ? (
        <Section
          title="最近更新"
          description="不挑分类也可以从这儿直接进。"
          className="mt-12"
          actions={
            <Button variant="ghost" size="sm" render={<Link to="/knowledge/entries" />}>
              全部条目
            </Button>
          }
        >
          <div className="border-border divide-border divide-y overflow-hidden rounded-xl border" data-testid="recent-updated">
            {recent.data.items.map((item) => (
              <Link
                key={item.id}
                to={`/knowledge/entries/${item.id}`}
                className="hover:bg-accent/40 group flex items-start gap-4 px-5 py-4 transition-colors"
              >
                <span className="min-w-0 flex-1">
                  <span className="group-hover:text-primary block font-medium transition-colors">
                    {item.title}
                  </span>
                  {item.excerpt === null || plainText(item.excerpt) === '' ? null : (
                    <span className="text-muted-foreground mt-1 line-clamp-2 block text-sm leading-6">
                      {plainText(item.excerpt)}
                    </span>
                  )}
                </span>
                <span className="flex shrink-0 items-center gap-3">
                  {item.categoryName === null ? null : (
                    <Badge variant="secondary">{item.categoryName}</Badge>
                  )}
                  <ArrowRight className="text-muted-foreground group-hover:text-primary size-4 transition-colors" />
                </span>
              </Link>
            ))}
          </div>
        </Section>
      ) : null}
    </AppShell>
  )
}
