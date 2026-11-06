'use client'

import { Badge, Card, CardContent, CardHeader, CardTitle, Checkbox } from '@paideia/ui'
import type { ReactNode } from 'react'

import { KIND_LABEL, type CampDay, type CampPlan, type CampTask } from './types'

/**
 * 每日计划（WORK-029）。
 *
 * <p>按天分组、每项可勾选。任务分三种去向：
 * <ul>
 *   <li>有 `entryId` 的读/做项 → 可以跳过去</li>
 *   <li>`gap` 缺口项 → 没有内容，给「先去知识库看看」的提示（组件只标注，不做跳转）</li>
 *   <li>排不下的项单独列在最后，写「时间不够，还有 N 项没排进来」</li>
 * </ul>
 *
 * <p>**分组断言容易被忽略的一种错**：`dayPlans` 是按天给的数组，不是扁平任务列表；
 * 把它当成扁平列表会把 7 天营的 40 项全塞进一天。
 */
export function PlanDays({
  plan,
  onToggle,
  pendingTaskId,
  onOpenEntry,
}: {
  plan: CampPlan
  onToggle: (task: CampTask, done: boolean) => void
  /** 正在提交的那一项的 id（用于禁掉重复点击）。 */
  pendingTaskId?: number | null
  onOpenEntry?: (entryId: number) => void
}): ReactNode {
  return (
    <div className="space-y-4" data-testid="camp-plan">
      <p className="text-muted-foreground text-sm">
        每天约 {plan.budgetMinutes} 分钟，共 {plan.days} 天。
      </p>

      <div className="grid gap-4 lg:grid-cols-2">
        {plan.dayPlans.map((day) => (
          <DayCard
            key={day.day}
            day={day}
            onToggle={onToggle}
            pendingTaskId={pendingTaskId ?? null}
            onOpenEntry={onOpenEntry}
          />
        ))}
      </div>

      {plan.unscheduled.length > 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>时间不够，还有 {plan.unscheduled.length} 项没排进来</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-1 text-sm">
              {plan.unscheduled.map((task) => (
                <li key={task.id} className="text-muted-foreground">
                  {task.topic} · {task.title}（{task.minutes} 分钟）
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      ) : null}
    </div>
  )
}

function DayCard({
  day,
  onToggle,
  pendingTaskId,
  onOpenEntry,
}: {
  day: CampDay
  onToggle: (task: CampTask, done: boolean) => void
  pendingTaskId: number | null
  onOpenEntry?: (entryId: number) => void
}): ReactNode {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>第 {day.day} 天</CardTitle>
      </CardHeader>
      <CardContent>
        {day.tasks.length === 0 ? (
          <p className="text-muted-foreground text-sm">今天没有安排。</p>
        ) : (
          <ul className="space-y-3">
            {day.tasks.map((task) => (
              <li key={task.id} className="flex items-start gap-3">
                <Checkbox
                  className="mt-1"
                  checked={task.done}
                  disabled={pendingTaskId === task.id}
                  onCheckedChange={(checked) => onToggle(task, checked === true)}
                  aria-label={`完成「${task.title}」`}
                />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="outline">{KIND_LABEL[task.kind]}</Badge>
                    <span className={task.done ? 'text-muted-foreground line-through' : 'font-medium'}>
                      {task.title}
                    </span>
                    <span className="text-muted-foreground text-xs tabular-nums">
                      {task.minutes} 分钟
                    </span>
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">{task.why}</p>
                  {task.entryId !== null && onOpenEntry !== undefined ? (
                    <button
                      type="button"
                      className="text-primary mt-1 text-xs underline-offset-4 hover:underline"
                      onClick={() => onOpenEntry(task.entryId as number)}
                    >
                      打开这一篇
                    </button>
                  ) : null}
                  {task.entryId === null && task.kind === 'gap' ? (
                    <p className="text-muted-foreground mt-1 text-xs">
                      平台还没有这一块的内容，可以先去知识库看看。
                    </p>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
