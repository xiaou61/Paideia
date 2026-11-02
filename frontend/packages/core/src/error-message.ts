/**
 * 错误对象转文案（WORK-062 收敛）。
 *
 * <p>全仓原有 40 处 `x instanceof Error ? x.message : String(x)` 与 20 处
 * `(x as Error).message`（后者遇到非 Error 会渲染出 `undefined`）。这里只留一份实现。
 *
 * <p>`@paideia/ui` **不适用**：ui 是设计系统、刻意不依赖 core，它内部需要时只做两行本地归一。
 */
export function errorMessage(cause: unknown, fallback?: string): string {
  if (cause instanceof Error) {
    return cause.message
  }
  return fallback ?? String(cause)
}