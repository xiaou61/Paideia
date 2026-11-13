import { AppShell, Card, CardContent, PageHeader, SkinPicker, ThemeToggle } from '@paideia/ui'
import { Palette } from 'lucide-react'

import { LearnerHeader } from '../components/LearnerHeader'

/**
 * 外观页（WORK-079）。
 *
 * <p>为什么单独一页而不是塞进账号菜单的弹层：皮肤一多，"在弹层里滚 30 个卡片"挑不动——
 * 没有分组、没有搜索、没有足够大的预览。这一页的三件事就是**分组、搜索、预览**。
 *
 * <p>入口在账号菜单（`LearnerHeader`）里，不是主导航：换肤是低频的个人设置，摆进导航栏
 * 等于让它跟"知识库""复习"抢注意力。但按项目规则（AGENTS.md「加了页面就必须加入口」），
 * 它必须有可见入口——账号菜单就是那个入口。
 *
 * <p>明暗开关放在同一页：用户来看"外观"，脑子里想的就是"好不好看"，明暗是外观的一部分，
 * 让他为了一件小事跳到别的地方才奇怪。
 */
export function AppearancePage() {
  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="外观"
        description="换皮肤与明暗。只影响外观，内容、成绩与笔记都不变。"
        actions={<ThemeToggle />}
      />

      <Card className="mt-6">
        <CardContent className="space-y-5 pt-6">
          <div className="flex items-start gap-3">
            <span aria-hidden className="bg-accent text-accent-foreground flex size-9 items-center justify-center rounded-lg">
              <Palette className="size-4" />
            </span>
            <div className="space-y-1">
              <p className="text-sm font-medium">选一个你愿意天天看的皮肤</p>
              <p className="text-muted-foreground text-sm leading-relaxed">
                每张卡片就是那个皮肤的真实样子——卡片里的颜色、圆角、字体都来自它自己，
                不是画上去的示意图。点一下立刻全站生效，选中状态会记住。
              </p>
            </div>
          </div>

          <SkinPicker />
        </CardContent>
      </Card>
    </AppShell>
  )
}
