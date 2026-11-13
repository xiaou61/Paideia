import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
  AppShell,
  PageHeader,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Checkbox,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  Input,
  Label,
  Section,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Separator,
  Skeleton,
  SkinPicker,
  Spinner,
  Switch,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
  toast,
  useTheme,
  type Theme,
} from '@paideia/ui'
import { InboxIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import type { ReactNode } from 'react'

/** 变体行的统一容器：一行放得下就一行，窄屏自动换行。 */
function Row({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-center gap-3">{children}</div>
}

function ThemeReadout({ theme }: { theme: Theme }) {
  return (
    <span className="text-muted-foreground text-xs" data-testid="current-theme">
      当前主题：{theme === 'dark' ? '深色' : '浅色'}
    </span>
  )
}

/**
 * 组件展览。每个组件一节，渲染它的全部变体与状态 —— 只放一个实例的话，
 * 看不出 variant 与 disabled/错误态长什么样，这一页就失去意义了。
 *
 * 新增组件时手工加一节：各组件的变体布局不同，自动列表只能列个名字，价值不大。
 */
export function UiKitPage() {
  const { theme } = useTheme()

  return (
    <AppShell
      title="Paideia 组件展览"
      subtitle="设计令牌、层次与全部基元组件"
      actions={<ThemeReadout theme={theme} />}
    >
      <div data-testid="ui-kit-page">
        <PageHeader
          title="组件展览"
          description="令牌取值来自 Vercel Geist 设计系统的公开事实：纯白底、近黑正文、一条极细的浅灰分隔线、6px 圆角、标题 semibold 带负字距。层次由字号、留白与那条线建立。具体数值只写在 globals.css 一处。"
        />

        <Section
          title="字号阶梯"
          description="标题档位是 semibold + 负字距，且字距随字号收紧；正文与标签沿用 12/14/16px。改动只看令牌，不必逐处手写字距。"
        >
          <div className="space-y-3">
            {[
              ['text-3xl', '个性化学习路径'],
              ['text-2xl', '个性化学习路径'],
              ['text-xl', '学习者模型与掌握度'],
              ['text-lg', '本节标题的下一档'],
              ['text-base', '正文与卡面标题使用这一档'],
              ['text-sm', '次要说明与表单标签'],
              ['text-xs', '元信息与时间戳'],
            ].map(([size, sample]) => (
              <div key={size} className="flex items-baseline gap-4">
                <span className="text-muted-foreground w-20 shrink-0 font-mono text-xs">{size}</span>
                <span className={size}>{sample}</span>
              </div>
            ))}
          </div>
        </Section>

        <Section
          title="层次与描边"
          description="卡面是不透明 + 1px 细线 + 多层低透明阴影。玻璃层保留为可选工具类，默认不使用——需要有内容可透的场景才有意义，例如浮在图片或滚动列表之上的顶栏。"
        >
          <div className="grid gap-6 sm:grid-cols-3">
            <Card data-testid="solid-sample">
              <CardHeader>
                <CardTitle>卡面（默认）</CardTitle>
                <CardDescription>1px 细线 + 多层阴影</CardDescription>
              </CardHeader>
              <CardContent className="space-y-2">
                <p data-testid="contrast-foreground">
                  正文文字：使用 foreground 令牌（近黑），按 4.5:1 要求。
                </p>
                <p className="text-muted-foreground" data-testid="contrast-muted">
                  次要文字：使用 muted-foreground 令牌，同样按 4.5:1 要求。
                </p>
              </CardContent>
            </Card>

            <div className="border-border bg-muted rounded-lg border p-4" data-testid="muted-surface">
              <div className="text-base font-semibold">次级面</div>
              <div className="text-muted-foreground mt-1 text-xs">bg-muted 令牌</div>
              <p className="text-muted-foreground mt-3 text-sm">
                用于分区、代码块与禁用态，不参与层次的主线。
              </p>
            </div>

            <Card className="glass" data-testid="glass-sample">
              <CardHeader>
                <CardTitle>玻璃层（可选）</CardTitle>
                <CardDescription>默认不使用</CardDescription>
              </CardHeader>
              <CardContent>
                <p className="text-muted-foreground text-sm">
                  叠在纯白底上几乎看不出差别——这正是它默认不用的原因。降级形态仍在：不支持
                  backdrop-filter 时退回不透明底，不影响可读性。
                </p>
              </CardContent>
            </Card>
          </div>
        </Section>

        <Section title="Button" description="variant × size、禁用态与内嵌加载指示。">
          <Row>
            <Button data-testid="button-default">主要按钮</Button>
            <Button variant="outline">描边</Button>
            <Button variant="secondary">次要</Button>
            <Button variant="ghost">幽灵</Button>
            <Button variant="link">链接</Button>
            <Button variant="destructive">危险</Button>
          </Row>
          <Row>
            <Button size="sm">小</Button>
            <Button size="default">中</Button>
            <Button size="lg">大</Button>
            <Button size="icon" aria-label="新增">
              <PlusIcon />
            </Button>
          </Row>
          <Row>
            <Button disabled>禁用</Button>
            <Button variant="outline" disabled>
              <Trash2Icon />
              禁用带图标
            </Button>
            <Button>
              <Spinner />
              处理中
            </Button>
          </Row>
        </Section>

        <Section title="表单基元" description="正常、聚焦、禁用与校验失败态。">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="kit-input">输入框</Label>
              <Input id="kit-input" placeholder="请输入内容" data-testid="input-normal" />
              <Input placeholder="禁用状态" disabled />
              <Input placeholder="校验失败" aria-invalid defaultValue="格式不正确" />
            </div>

            <div className="space-y-4">
              <div className="flex items-center gap-2">
                <Checkbox id="kit-check" defaultChecked />
                <Label htmlFor="kit-check">已勾选</Label>
              </div>
              <div className="flex items-center gap-2">
                <Checkbox id="kit-check-off" />
                <Label htmlFor="kit-check-off">未勾选</Label>
              </div>
              <div className="flex items-center gap-2">
                <Checkbox id="kit-check-disabled" disabled />
                <Label htmlFor="kit-check-disabled">禁用</Label>
              </div>
              <div className="flex items-center gap-2">
                <Switch id="kit-switch" defaultChecked />
                <Label htmlFor="kit-switch">开关</Label>
              </div>
              <div className="space-y-2">
                <Label htmlFor="kit-select">下拉选择</Label>
                <Select defaultValue="中文">
                  <SelectTrigger id="kit-select" className="w-48">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="中文">中文</SelectItem>
                    <SelectItem value="数学">数学</SelectItem>
                    <SelectItem value="物理">物理</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          </div>
        </Section>

        <Section title="展示基元" description="Badge、Separator、Skeleton、Spinner。">
          <Row>
            <Badge>默认</Badge>
            <Badge variant="secondary">次要</Badge>
            <Badge variant="outline">描边</Badge>
            <Badge variant="destructive">危险</Badge>
            <Badge variant="ghost">幽灵</Badge>
          </Row>
          <div className="my-4 max-w-md">
            <Separator />
          </div>
          <Row>
            <Spinner />
            <Skeleton className="h-8 w-40" />
            <Skeleton className="size-8 rounded-full" />
          </Row>
        </Section>

        <Section title="Table" description="有数据与空表体两种形态。">
          <Card>
            <CardContent className="pt-6">
              <Table>
                <TableCaption>示例数据，不是真实业务数据</TableCaption>
                <TableHeader>
                  <TableRow>
                    <TableHead>知识点</TableHead>
                    <TableHead>掌握度</TableHead>
                    <TableHead className="text-right">练习次数</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow>
                    <TableCell>一元二次方程</TableCell>
                    <TableCell>已掌握</TableCell>
                    <TableCell className="text-right">12</TableCell>
                  </TableRow>
                  <TableRow>
                    <TableCell>因式分解</TableCell>
                    <TableCell>待巩固</TableCell>
                    <TableCell className="text-right">3</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </CardContent>
          </Card>
          <Card className="mt-4">
            <CardContent className="pt-6">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>知识点</TableHead>
                    <TableHead>掌握度</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody />
              </Table>
            </CardContent>
          </Card>
        </Section>

        <Section title="Empty 与 Alert" description="空态、普通提示与错误提示。">
          <div className="grid gap-4 sm:grid-cols-2">
            <Card className="glass">
              <CardContent className="pt-6">
                <Empty data-testid="empty-state">
                  <EmptyHeader>
                    <EmptyMedia variant="icon">
                      <InboxIcon />
                    </EmptyMedia>
                    <EmptyTitle>还没有学习记录</EmptyTitle>
                    <EmptyDescription>完成一次练习后，这里会显示进度。</EmptyDescription>
                  </EmptyHeader>
                  <EmptyContent>
                    <Button size="sm">开始练习</Button>
                  </EmptyContent>
                </Empty>
              </CardContent>
            </Card>

            <div className="space-y-4">
              <Alert data-testid="alert-default">
                <AlertTitle>提示</AlertTitle>
                <AlertDescription>这是一条普通提示，用于说明状态。</AlertDescription>
                <AlertAction>
                  <Button size="sm" variant="ghost">
                    知道了
                  </Button>
                </AlertAction>
              </Alert>
              <Alert variant="destructive" data-testid="alert-destructive">
                <AlertTitle>请求失败</AlertTitle>
                <AlertDescription>后端没有响应，请确认服务已启动。</AlertDescription>
              </Alert>
            </div>
          </div>
        </Section>

        <Section title="Dialog / DropdownMenu / Tooltip" description="覆盖层组件，键盘可达。">
          <Row>
            <Dialog>
              <DialogTrigger
                data-testid="dialog-trigger"
                render={<Button variant="outline" />}
              >
                打开对话框
              </DialogTrigger>
              <DialogContent data-testid="dialog-content">
                <DialogHeader>
                  <DialogTitle>确认删除</DialogTitle>
                  <DialogDescription>
                    删除后无法恢复。这里只作展示，不会真的删除任何东西。
                  </DialogDescription>
                </DialogHeader>
                <DialogFooter>
                  <DialogClose data-testid="dialog-cancel" render={<Button variant="ghost" />}>
                    取消
                  </DialogClose>
                  <Button variant="destructive">确认删除</Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>

            <DropdownMenu>
              <DropdownMenuTrigger
                data-testid="menu-trigger"
                render={<Button variant="outline" />}
              >
                打开菜单
              </DropdownMenuTrigger>
              <DropdownMenuContent data-testid="menu-content" align="start">
                {/* Base UI 版的 GroupLabel 必须在 Group 里，否则点开时会抛
                    "MenuGroupContext is missing" 并把子树整块崩掉 */}
                <DropdownMenuGroup>
                  <DropdownMenuLabel>账户</DropdownMenuLabel>
                  <DropdownMenuItem>个人资料</DropdownMenuItem>
                  <DropdownMenuItem>学习偏好</DropdownMenuItem>
                </DropdownMenuGroup>
                <DropdownMenuSeparator />
                <DropdownMenuItem>退出登录</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>

            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger
                  data-testid="tooltip-trigger"
                  render={<Button variant="outline" />}
                >
                  悬停查看提示
                </TooltipTrigger>
                <TooltipContent>这是提示内容</TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </Row>
        </Section>

        <Section title="Tabs" description="默认（浅底）与 line（下划线）两种外观。">
          <Tabs defaultValue="progress">
            <TabsList>
              <TabsTrigger value="progress">学习进度</TabsTrigger>
              <TabsTrigger value="history">作答历史</TabsTrigger>
            </TabsList>
            <TabsContent value="progress">
              <Card className="glass">
                <CardContent className="pt-6 text-sm">学习进度面板的内容。</CardContent>
              </Card>
            </TabsContent>
            <TabsContent value="history">
              <Card className="glass">
                <CardContent className="pt-6 text-sm">作答历史面板的内容。</CardContent>
              </Card>
            </TabsContent>
          </Tabs>
        </Section>

        <Section title="Toast" description="点击按钮触发一条通知。">
          <Row>
            <Button
              variant="outline"
              data-testid="toast-trigger"
              onClick={() => toast('已保存', { description: '进度已同步到服务端' })}
            >
              普通通知
            </Button>
            <Button variant="outline" onClick={() => toast.error('保存失败')}>
              错误通知
            </Button>
          </Row>
        </Section>

        {/*
          皮肤（WORK-079）。这一页是设计系统的陈列窗，皮肤属于设计系统的一部分：
          想确认"某个皮肤在组件上还成不成立"，在这里切一下比跑四个应用快。
          真正的入口在学习者端账号菜单（外观页）。
        */}
        <Section title="皮肤" description="每个皮肤的真实令牌，卡片内部就是一份迷你主题；点一下全站生效。">
          <SkinPicker />
        </Section>
      </div>
    </AppShell>
  )
}
