import { DESIGN_TYPES, type DesignColumn, type DesignResult, type DesignTable } from '@paideia/sql-workbench/schema'
import { Button, Input } from '@paideia/ui'
import { Plus, X } from 'lucide-react'

/**
 * 可视化建表（2026-11-02 用户要求：不写 SQL 也能造数据集）。
 *
 * <p>点着加表、加列、选类型，右侧实时看到生成的 SQL——生成逻辑在
 * `@paideia/sql-workbench/schema` 的 `buildDatasetSql`（纯函数、有单测）。
 * SQL 只作预览：保存仍走页面原有的上传接口（白名单 + 沙箱干跑）。
 *
 * <p>主键用**互斥勾选**而不是 radio：radio 选上就不能取消，而"这张表不要主键"
 * 是合法需求。勾第二个自动取消第一个，再点自己就全不选。
 */
export function DatasetDesigner({
  tables,
  onTablesChange,
  rows,
  onRowsChange,
  result,
}: {
  tables: DesignTable[]
  onTablesChange: (tables: DesignTable[]) => void
  rows: number
  onRowsChange: (rows: number) => void
  result: DesignResult
}) {
  const patchTable = (tableIndex: number, patch: Partial<DesignTable>) => {
    onTablesChange(tables.map((table, index) => (index === tableIndex ? { ...table, ...patch } : table)))
  }

  const patchColumn = (tableIndex: number, columnIndex: number, patch: Partial<DesignColumn>) => {
    onTablesChange(
      tables.map((table, index) =>
        index === tableIndex
          ? {
              ...table,
              columns: table.columns.map((column, columnIndex_) =>
                columnIndex_ === columnIndex ? { ...column, ...patch } : column,
              ),
            }
          : table,
      ),
    )
  }

  /** 互斥勾选：选中这一列时清掉同表其它列；再点已选中的列则取消。 */
  const togglePrimaryKey = (tableIndex: number, columnIndex: number) => {
    onTablesChange(
      tables.map((table, index) =>
        index === tableIndex
          ? {
              ...table,
              columns: table.columns.map((column, columnIndex_) => ({
                ...column,
                primaryKey: columnIndex_ === columnIndex ? !column.primaryKey : false,
              })),
            }
          : table,
      ),
    )
  }

  const addTable = () => {
    onTablesChange([
      ...tables,
      {
        name: '',
        columns: [
          { name: 'id', type: 'INT', primaryKey: true },
          { name: '', type: 'VARCHAR(50)', primaryKey: false },
        ],
      },
    ])
  }

  const removeTable = (tableIndex: number) => {
    onTablesChange(tables.filter((_, index) => index !== tableIndex))
  }

  const addColumn = (tableIndex: number) => {
    onTablesChange(
      tables.map((table, index) =>
        index === tableIndex
          ? { ...table, columns: [...table.columns, { name: '', type: 'VARCHAR(50)', primaryKey: false }] }
          : table,
      ),
    )
  }

  const removeColumn = (tableIndex: number, columnIndex: number) => {
    onTablesChange(
      tables.map((table, index) =>
        index === tableIndex
          ? { ...table, columns: table.columns.filter((_, columnIndex_) => columnIndex_ !== columnIndex) }
          : table,
      ),
    )
  }

  return (
    <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_320px]">
      <div className="space-y-3">
        {tables.map((table, tableIndex) => (
          <div key={tableIndex} className="border-border space-y-2 rounded-lg border p-3" data-testid={`sql-designer-table-card-${tableIndex}`}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-muted-foreground text-xs">表 {tableIndex + 1}</span>
              <Input
                value={table.name}
                onChange={(event) => patchTable(tableIndex, { name: event.target.value })}
                placeholder="表名，如 books"
                aria-label={`第 ${tableIndex + 1} 张表的表名`}
                data-testid={`sql-designer-table-${tableIndex}`}
                className="max-w-52"
              />
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={`删除第 ${tableIndex + 1} 张表`}
                data-testid={`sql-designer-remove-table-${tableIndex}`}
                onClick={() => removeTable(tableIndex)}
              >
                <X className="size-4" />
              </Button>
            </div>

            <div className="space-y-1.5">
              {table.columns.map((column, columnIndex) => (
                <div key={columnIndex} className="flex flex-wrap items-center gap-2">
                  <Input
                    value={column.name}
                    onChange={(event) => patchColumn(tableIndex, columnIndex, { name: event.target.value })}
                    placeholder="列名"
                    aria-label={`第 ${tableIndex + 1} 张表第 ${columnIndex + 1} 列的列名`}
                    data-testid={`sql-designer-column-${tableIndex}-${columnIndex}`}
                    className="w-36"
                  />
                  <select
                    value={column.type}
                    onChange={(event) => patchColumn(tableIndex, columnIndex, { type: event.target.value })}
                    aria-label="列类型"
                    data-testid={`sql-designer-type-${tableIndex}-${columnIndex}`}
                    className="border-border bg-card rounded-md border px-2 py-1 text-sm"
                  >
                    {DESIGN_TYPES.map((type) => (
                      <option key={type} value={type}>
                        {type}
                      </option>
                    ))}
                  </select>
                  <label className="flex items-center gap-1 text-xs">
                    <input
                      type="checkbox"
                      checked={column.primaryKey}
                      onChange={() => togglePrimaryKey(tableIndex, columnIndex)}
                      data-testid={`sql-designer-pk-${tableIndex}-${columnIndex}`}
                    />
                    主键
                  </label>
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`删除第 ${columnIndex + 1} 列`}
                    data-testid={`sql-designer-remove-column-${tableIndex}-${columnIndex}`}
                    onClick={() => removeColumn(tableIndex, columnIndex)}
                  >
                    <X className="size-3.5" />
                  </Button>
                </div>
              ))}
              <Button
                type="button"
                size="sm"
                variant="ghost"
                data-testid={`sql-designer-add-column-${tableIndex}`}
                onClick={() => addColumn(tableIndex)}
              >
                <Plus className="size-3.5" />
                加一列
              </Button>
            </div>
          </div>
        ))}

        <div className="flex flex-wrap items-center gap-3">
          <Button type="button" size="sm" variant="outline" data-testid="sql-designer-add-table" onClick={addTable}>
            <Plus className="size-4" />
            加一张表
          </Button>
          <label className="flex items-center gap-1 text-xs" htmlFor="sql-designer-rows">
            每张表插入
          </label>
          <Input
            id="sql-designer-rows"
            type="number"
            min={1}
            max={50}
            value={rows}
            onChange={(event) => onRowsChange(Math.max(1, Math.min(50, Number(event.target.value) || 1)))}
            className="w-20"
            aria-label="每张表插入行数"
            data-testid="sql-designer-rows"
          />
          <span className="text-xs">行</span>
        </div>
      </div>

      <div className="space-y-2">
        <p className="text-muted-foreground text-xs">生成的 SQL（保存前会在沙箱里真跑一遍）</p>
        {result.problems.map((problem) => (
          <p key={problem} className="text-destructive text-xs">
            {problem}
          </p>
        ))}
        <pre
          className="border-border bg-muted/40 max-h-72 overflow-auto rounded-lg border p-2 font-mono text-[11px] whitespace-pre"
          data-testid="sql-designer-preview"
        >
          {result.sql === ''
            ? '把表名和列名填上，这里会实时生成 SQL'
            : result.sql}
        </pre>
      </div>
    </div>
  )
}