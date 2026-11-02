import { Alert, AlertDescription, AlertTitle } from './alert'
import { Spinner } from './spinner'

/**
 * 查询三态的哑组件（WORK-062 收敛）。
 *
 * <p>各页面原来把"加载中 / 加载失败"的同一段 JSX 各抄一遍（加载行 20+ 处、错误块 15+ 处，
 * `data-testid="*-state"` 就是这套三连的锚点）。这里只收**形状**，不收差异：
 * 文案、testId、额外 className 都经 props 传；**组件本身不认识 react-query**——ui 是设计系统，
 * 不为它引任何数据层依赖。
 *
 * <p>空态（Empty 卡片）本轮不做成共享件：20 处各自带不同图标与说明排版，先收两类最统一的，
 * 空态留下一轮按页替换。
 */

/** 加载行：Spinner + 一行文案。默认样式与原 20+ 处逐字一致。 */
export function LoadingRow({
  label,
  testId,
  className,
}: {
  label: string
  testId?: string
  className?: string
}) {
  return (
    <p className={className ?? 'text-muted-foreground flex items-center gap-2 text-sm'} data-testid={testId}>
      <Spinner /> {label}
    </p>
  )
}

/**
 * 错误块：destructive Alert + 标题 + 错误文案。
 *
 * <p>错误文案内部两行归一（**不 import @paideia/core**，与 core.errorMessage 同口径）：
 * 非 Error 时给 String(cause) 而不是让 React 渲染 `undefined`。
 */
export function ErrorAlert({
  title,
  error,
  testId,
  className,
}: {
  title: string
  error: unknown
  testId?: string
  className?: string
}) {
  const text = error instanceof Error ? error.message : String(error)
  return (
    <Alert variant="destructive" className={className} data-testid={testId}>
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>{text}</AlertDescription>
    </Alert>
  )
}