import { Fragment, type ReactNode } from 'react'

import {
  Card,
  CardContent,
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  ErrorAlert,
  ListPagination,
  LoadingRow,
  PageHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@paideia/ui'

/** 列表页的一列。`render` 负责把一行数据渲染成单元格内容（使用 `renderRow` 时可不填）。 */
export interface ListPageColumn<T> {
  key: string
  header: ReactNode
  render?: (row: T) => ReactNode
  /** 追加到单元格上的类名 */
  className?: string
  align?: 'left' | 'right'
}

export interface ListPagePagination {
  total: number
  page: number
  totalPages: number
  onPage: (page: number) => void
  testIdPrefix?: string
}

export interface ListPageProps<T> {
  title: string
  description?: ReactNode
  actions?: ReactNode
  /** 标题下方、表格上方的筛选区（输入框、下拉等由调用方提供） */
  filters?: ReactNode
  columns: ListPageColumn<T>[]
  rows: T[]
  getRowKey: (row: T) => string | number
  loading?: boolean
  error?: unknown
  onRetry?: () => void
  loadingLabel?: string
  /** 覆盖默认的 <LoadingRow/>（例如只要一个 <Spinner/>） */
  loadingNode?: ReactNode
  emptyTitle?: string
  emptyDescription?: ReactNode
  emptyAction?: ReactNode
  emptyIcon?: ReactNode
  emptyTestId?: string
  tableTestId?: string
  /** 追加到表格外层 CardContent 的类名（例如 `px-0` 让表格贴边） */
  tableContentClassName?: string
  /** 自定义整行渲染：返回一个或多个 <TableRow>（可含展开行）。给了它时忽略各列的 render。 */
  renderRow?: (row: T) => ReactNode
  pagination?: ListPagePagination
}

/**
 * 列表页模板（Astryx 风格的组合层）。
 *
 * <p>只负责"列表页长什么样"：页头、筛选槽、表格、分页与加载/错误/空三态。取数、状态、
 * 事件都由调用方在外部完成（react-query 之类不进这个包），因此它不认识数据层、不引路由。
 * 需要可展开行等特殊行时用 `renderRow` 接管整行，模板仍提供外壳与三态。
 */
export function ListPage<T>({
  title,
  description,
  actions,
  filters,
  columns,
  rows,
  getRowKey,
  loading = false,
  error,
  onRetry,
  loadingLabel = '加载中…',
  loadingNode,
  emptyTitle = '暂无数据',
  emptyDescription,
  emptyAction,
  emptyIcon,
  emptyTestId,
  tableTestId,
  tableContentClassName,
  renderRow,
  pagination,
}: ListPageProps<T>) {
  // 错误 → 加载 → 空 → 表格四个分支用 if/else 而不是嵌套三元：
  // 以后再加状态（如"部分行加载失败"）时，嵌套三元极易插错层把错误态盖掉。
  let body: ReactNode
  if (error !== undefined && error !== null) {
    body = <ErrorAlert title="加载失败" error={error} onRetry={onRetry} />
  } else if (loading) {
    body = loadingNode ?? <LoadingRow label={loadingLabel} />
  } else if (rows.length === 0) {
    body = (
      <Card>
        <CardContent className="pt-6">
          <Empty data-testid={emptyTestId}>
            <EmptyHeader>
              {emptyIcon === undefined ? null : <EmptyMedia variant="icon">{emptyIcon}</EmptyMedia>}
              <EmptyTitle>{emptyTitle}</EmptyTitle>
              {emptyDescription === undefined ? null : (
                <EmptyDescription>{emptyDescription}</EmptyDescription>
              )}
            </EmptyHeader>
            {emptyAction === undefined ? null : <EmptyContent>{emptyAction}</EmptyContent>}
          </Empty>
        </CardContent>
      </Card>
    )
  } else {
    body = (
      <Card>
        <CardContent className={tableContentClassName}>
          <Table data-testid={tableTestId}>
              <TableHeader>
                <TableRow>
                  {columns.map((column) => (
                    <TableHead
                      key={column.key}
                      className={column.align === 'right' ? 'text-right' : undefined}
                    >
                      {column.header}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) =>
                  renderRow === undefined ? (
                    <TableRow key={getRowKey(row)}>
                      {columns.map((column) => (
                        <TableCell
                          key={column.key}
                          className={
                            [column.className, column.align === 'right' ? 'text-right' : undefined]
                              .filter(Boolean)
                              .join(' ') || undefined
                          }
                        >
                          {column.render?.(row) ?? null}
                        </TableCell>
                      ))}
                    </TableRow>
                  ) : (
                    <Fragment key={getRowKey(row)}>{renderRow(row)}</Fragment>
                  ),
                )}
              </TableBody>
          </Table>
        </CardContent>
        {pagination === undefined ? null : (
          <ListPagination
            total={pagination.total}
            page={pagination.page}
            totalPages={pagination.totalPages}
            onPage={pagination.onPage}
            testIdPrefix={pagination.testIdPrefix}
          />
        )}
      </Card>
    )
  }

  return (
    <div>
      <PageHeader title={title} description={description} actions={actions} />
      {filters === undefined ? null : <div className="mb-5">{filters}</div>}
      {body}
    </div>
  )
}
