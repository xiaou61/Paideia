import { Button } from './button'

/**
 * 列表分页页脚（WORK-062 收敛）。
 *
 * <p>管理端五个列表各自抄了同一段页脚（其中积分审计页还漂移成了原生 `<button>`，
 * 与其余页的按钮不一致——这里一并统一回 {@link Button} `size="xs"`）。
 *
 * <p>`testIdPrefix` 生成三个既有锚点：`{prefix}-total` / `{prefix}-prev` / `{prefix}-next`
 * ——e2e 全靠它们等页面，名字不能改。
 */
export function ListPagination({
  total,
  page,
  totalPages,
  onPage,
  testIdPrefix,
}: {
  total: number
  page: number
  totalPages: number
  onPage: (page: number) => void
  testIdPrefix?: string
}) {
  return (
    <div className="border-border text-muted-foreground flex items-center justify-between gap-4 border-t px-(--card-spacing) py-3 text-xs">
      <span data-testid={testIdPrefix ? `${testIdPrefix}-total` : undefined}>共 {total} 条</span>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="xs"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
          data-testid={testIdPrefix ? `${testIdPrefix}-prev` : undefined}
        >
          上一页
        </Button>
        <span className="tabular-nums">
          {page} / {totalPages}
        </span>
        <Button
          variant="outline"
          size="xs"
          disabled={page >= totalPages}
          onClick={() => onPage(page + 1)}
          data-testid={testIdPrefix ? `${testIdPrefix}-next` : undefined}
        >
          下一页
        </Button>
      </div>
    </div>
  )
}