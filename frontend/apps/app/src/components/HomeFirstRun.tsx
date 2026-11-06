import { Button, Card, CardContent } from '@paideia/ui'
import { ArrowRight, Sparkles } from 'lucide-react'
import { Link } from 'react-router'

/**
 * 零数据首页的三步路径（WORK-034 REQ-007）。
 *
 * <p>只在「没自评、没学习记录、图谱一个概念都没碰过」时出现。三个去处按顺序：
 * 先补身份（决定推荐）、再从零开始、最后直接进第一条线的第一关。
 */
export function HomeFirstRun({
  entryHref,
  onDismiss,
}: {
  entryHref: string
  onDismiss: () => void
}) {
  const steps = [
    { title: '先说一下你是谁', detail: '在个人资料里选身份和目标，首页会按它调整。', to: '/profile' },
    { title: '挑一条线开始', detail: 'Python / Java / MySQL，一关二十分钟，边学边练。', to: '/learn' },
    { title: '直接进第一关', detail: '不用挑，从第一条线的第一课开始。', to: entryHref },
  ]
  return (
    <Card className="border-primary/30 bg-primary/[0.04] mb-6" data-testid="home-first-run">
      <CardContent className="pt-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="flex items-center gap-2 font-medium">
              <Sparkles className="text-primary size-4" />
              第一次来？三步就能开始
            </p>
            <p className="text-muted-foreground mt-1 text-sm">
              做完这三步，首页会开始按你的进度和薄弱点排序。
            </p>
          </div>
          <button
            type="button"
            onClick={onDismiss}
            data-testid="home-first-run-dismiss"
            className="text-muted-foreground hover:text-foreground min-h-11 text-xs underline underline-offset-2"
          >
            不用了
          </button>
        </div>
        <ol className="mt-4 grid gap-3 sm:grid-cols-3">
          {steps.map((step, index) => (
            <li key={step.to}>
              <Link
                to={step.to}
                className="border-border bg-background hover:border-primary/40 flex h-full items-start gap-3 rounded-lg border p-3 transition-colors"
              >
                <span className="bg-primary/10 text-primary flex size-7 shrink-0 items-center justify-center rounded-full text-sm">
                  {index + 1}
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{step.title}</span>
                  <span className="text-muted-foreground mt-0.5 block text-xs leading-5">
                    {step.detail}
                  </span>
                </span>
                <ArrowRight className="text-muted-foreground ml-auto size-4 shrink-0" />
              </Link>
            </li>
          ))}
        </ol>
        <p className="text-muted-foreground mt-3 text-xs">
          想自己找？也可以直接
          <Link to="/knowledge" className="text-primary ml-1 underline underline-offset-2">
            去知识库
          </Link>
          。
        </p>
      </CardContent>
    </Card>
  )
}