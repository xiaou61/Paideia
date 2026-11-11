import { createKnowledgeApi, plainText } from '@paideia/knowledge'
import {
  AppShell,
  Badge,
  Button,
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  ErrorAlert,
  LoadingRow,
  PageHeader,
  Section,
} from '@paideia/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, ChevronRight, FolderOpen, Layers } from 'lucide-react'
import { Link } from 'react-router'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

const knowledge = createKnowledgeApi(api)

/**
 * 知识库首屏：**分类清单**（取代原来的左侧窄树）。
 *
 * <p>原来把分类塞进一个 16rem 的侧栏里，一屏看得到七八条、每条只有名字，整页因此显得又空又单调
 * （用户反馈："分类这里就不能弄成二级页面那样吗……页面过于单调"）。改成"先看分类、点进去才是条目"
 * 之后，卡片能放下名字、子分类与条目去向，点击目标也大得多。
 *
 * <p><b>2026-11-11 第三次改版：封面卡片网格 → 与条目列表同一套密排表格</b>。上一版的每张卡都顶着
 * 一块 96px 的薰衣草色块（点阵纹理 + 文件夹图标），而那块色块**不承载任何信息**——名字就在它下面
 * 一行。用户看完二级页的改版后说这个风格可以一起换掉，于是这里也换成「一张卡 + 表头 + 分隔线行」：
 *
 * <ul>
 *   <li>分类名、子分类名、去向箭头各占一列，眼睛能纵向比对，而不是在九个方框之间跳；</li>
 *   <li>子分类从一排徽章改成一行文字（`·` 分隔、超长截断）——徽章原本要占两三行，把行高撑到
 *       色块那一侧才停得下来；</li>
 *   <li>「全部条目」「按目录阅读」这两个快捷入口从两块通栏渐变卡收成工具栏上的两个按钮：它们
 *       不是分类，跟分类摆成同样大小的卡片，反而让人以为是一类东西。</li>
 * </ul>
 */
export function KnowledgeCategoriesPage() {
  const categories = useQuery({
    queryKey: ['knowledge-categories'],
    queryFn: () => knowledge.categories(),
  })
  // 分类下面那块"最近更新"用得到：分类只有三五个时，光一张表会让下半屏空着
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

      {/*
        快捷入口（2026-11-11）：从通栏卡片收成工具栏按钮。
        更细的一层理由：它们是**动作**（去某个列表 / 换一种读法），不是**分类**——原来两块卡与
        分类卡同尺寸同外观，扫视时会被当成"两个奇怪的分类"。testid 保持原样，既有 e2e 仍走同一条路。
      */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Button
          variant="outline"
          data-testid="all-entries-card"
          render={<Link to="/knowledge/entries" />}
        >
          <Layers className="size-4" />
          全部条目
        </Button>
        <Button
          variant="outline"
          data-testid="wiki-reader-card"
          render={<Link to="/knowledge/wiki" />}
        >
          <FolderOpen className="size-4" />
          按目录阅读
        </Button>
      </div>

      {categories.isError ? (
        <ErrorAlert
          title="分类加载失败"
          error={categories.error}
          testId="category-state"
          onRetry={() => void categories.refetch()}
        />
      ) : null}

      {categories.isError ? null : (
        <div className="border-border bg-card overflow-hidden rounded-xl border">
          {/*
            表头与行**同一套两级结构**（外层 flex-1 容器 + 右箭头占位），列宽才对得上：
            桌面是「分类 224px / 子分类 自适应」两列，窄屏折成「名字 + 子分类」两行。
          */}
          <div className="border-border bg-muted/50 text-muted-foreground flex h-9 items-center gap-4 border-b px-4 text-xs">
            <span className="flex min-w-0 flex-1 flex-col md:flex-row md:items-center md:gap-4">
              <span className="md:w-56 md:shrink-0">分类</span>
              <span className="hidden min-w-0 md:block md:flex-1">子分类</span>
            </span>
            <span aria-hidden className="size-4 shrink-0" />
          </div>

          {categories.isPending ? (
            <LoadingRow
              label="加载分类…"
              testId="category-state"
              className="text-muted-foreground flex items-center gap-2 px-4 py-6 text-sm"
            />
          ) : null}

          {categories.isSuccess && categories.data.length === 0 ? (
            <Empty className="border-0 py-16" data-testid="category-empty">
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
            <div data-testid="category-grid" className="divide-border divide-y">
              {categories.data.map((category) => (
                <Link
                  key={category.id}
                  to={`/knowledge/categories/${category.id}`}
                  data-testid={`category-card-${category.id}`}
                  className="focus-ring group hover:bg-accent/40 flex items-start gap-4 px-4 py-3 transition-colors md:items-center"
                >
                  <span className="flex min-w-0 flex-1 flex-col md:flex-row md:items-center md:gap-4">
                    <span className="flex min-w-0 items-center gap-2 md:w-56 md:shrink-0">
                      <span className="group-hover:text-primary min-w-0 truncate font-medium transition-colors">
                        {category.name}
                      </span>
                      {/* 学习轨道与普通分类混在一起，但前者有关卡顺序；标出来才知道去 /learn 有一条引导路线 */}
                      {category.lineKind ? (
                        <Badge variant="secondary" className="shrink-0">
                          从零开始
                        </Badge>
                      ) : null}
                    </span>

                    {/*
                      子分类用一行文字而不是一排徽章：徽章要占两三行，行高就撑成卡片了。
                      空的情况在桌面画一个短横（六行都是「没有子分类」会像没填完的表），
                      窄屏则**整个不出现**——那一行折叠后只剩名字，多一个短横纯属噪音。
                      短横只是形状，语义由 sr-only 补，别让"没有"只存在于颜色里。
                    */}
                    <span
                      className="text-muted-foreground mt-1 block min-w-0 truncate text-xs md:mt-0 md:flex-1 md:text-sm"
                      title={category.children.length === 0 ? undefined : childrenText(category.children)}
                    >
                      {category.children.length === 0 ? (
                        <>
                          <span aria-hidden className="hidden md:inline">
                            —
                          </span>
                          <span className="sr-only">没有子分类</span>
                        </>
                      ) : (
                        childrenText(category.children)
                      )}
                    </span>
                  </span>

                  <ChevronRight className="text-muted-foreground group-hover:text-primary mt-1 size-4 shrink-0 transition-colors md:mt-0" />
                </Link>
              ))}
            </div>
          ) : null}

          {/* 表尾与条目列表同构：总数常驻，另一个入口放在右侧 */}
          {categories.isSuccess && categories.data.length > 0 ? (
            <div className="border-border flex flex-wrap items-center justify-between gap-2 border-t px-4 py-2.5">
              <span className="text-muted-foreground text-xs">
                共 {categories.data.length} 个分类
              </span>
              <Link
                to="/knowledge/entries"
                className="text-muted-foreground hover:text-primary focus-ring flex items-center gap-1 rounded text-xs transition-colors"
              >
                不挑分类，看全部条目
                <ArrowRight className="size-3.5" />
              </Link>
            </div>
          ) : null}
        </div>
      )}

      {/*
        分类下面是"最近更新"。分类只有三五个时，光一张表会让整页在下半屏空着
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

/**
 * 子分类清单压成一行（2026-11-11）。
 *
 * <p>最多列 4 个，其余折成「等 N 个」：子分类可以到十几个，全列出来这一列会换行，
 * 行高就不是"一行一个分类"了。用 `·` 而不是徽章：徽章有内边距与圆角，一行里十几个小方块
 * 比文字更难读，而这里要的只是"有哪些子类"。
 */
function childrenText(children: { name: string }[]): string {
  const shown = children.slice(0, 4).map((child) => child.name)
  const rest = children.length - shown.length
  return rest > 0 ? `${shown.join(' · ')} 等 ${children.length} 个` : shown.join(' · ')
}
