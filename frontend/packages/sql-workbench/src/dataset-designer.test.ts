import { describe, expect, it } from 'vitest'

import { buildDatasetSql, designValue, type DesignTable } from './dataset-schema'

/** 一份最小可用结构。 */
function table(over: Partial<DesignTable> = {}): DesignTable {
  return {
    name: 'books',
    columns: [
      { name: 'id', type: 'INT', primaryKey: true },
      { name: 'title', type: 'VARCHAR(50)', primaryKey: false },
    ],
    ...over,
  }
}

describe('可视化建表（buildDatasetSql）', () => {
  it('生成 CREATE TABLE 与 INSERT，行数按参数', () => {
    const result = buildDatasetSql([table()], 3)

    expect(result.problems).toEqual([])
    expect(result.sql).toContain('CREATE TABLE books')
    expect(result.sql).toContain('id INT PRIMARY KEY')
    expect(result.sql).toContain('title VARCHAR(50)')
    expect(result.sql).toContain('INSERT INTO books (id, title) VALUES')
    expect(result.sql).toContain("(1, 'title-1')")
    expect(result.sql).toContain("(3, 'title-3')")
    expect(result.sql).not.toContain('(4,')
  })

  it('行数夹在 1–50：0 → 1 行，999 → 50 行', () => {
    expect(buildDatasetSql([table()], 0).sql).toContain("(1, 'title-1')")
    expect(buildDatasetSql([table()], 0).sql).not.toContain("(2, 'title-2')")

    const big = buildDatasetSql([table()], 999)
    expect(big.sql).toContain("(50, 'title-50')")
    expect(big.sql).not.toContain("(51, 'title-51')")
  })

  it('表名与列名大小写自动转小写（最常见的手误）', () => {
    const result = buildDatasetSql(
      [table({ name: 'Books', columns: [{ name: 'Title', type: 'VARCHAR(50)', primaryKey: false }] })],
      1,
    )
    expect(result.problems).toEqual([])
    expect(result.sql).toContain('CREATE TABLE books')
    expect(result.sql).toContain('title VARCHAR(50)')
  })

  it('非法名字报警、不进 SQL；空名字（还没填完）静默跳过', () => {
    const bad = buildDatasetSql(
      [
        table({ name: '1books' }),
        table({
          name: 'orders',
          columns: [
            { name: '订单号', type: 'INT', primaryKey: false },
            { name: '', type: 'INT', primaryKey: false },
            { name: 'amount', type: 'DECIMAL(10,2)', primaryKey: false },
          ],
        }),
        table({ name: '' }),
      ],
      2,
    )

    expect(bad.problems).toHaveLength(2)
    expect(bad.problems[0]).toContain('表名「1books」不能用')
    expect(bad.problems[1]).toContain('列名「订单号」不能用')
    expect(bad.sql).toContain('CREATE TABLE orders')
    expect(bad.sql).toContain('amount DECIMAL(10,2)')
    expect(bad.sql).not.toContain('1books')

    // 全是还没填完的草稿：没有 SQL，也没有报错
    const empty = buildDatasetSql([table({ name: '', columns: [{ name: '', type: 'INT', primaryKey: false }] })], 5)
    expect(empty.sql).toBe('')
    expect(empty.problems).toEqual([])
  })

  it('同一张表里列名重复 → 报警且只留第一列', () => {
    const result = buildDatasetSql(
      [
        table({
          columns: [
            { name: 'id', type: 'INT', primaryKey: true },
            { name: 'ID', type: 'BIGINT', primaryKey: false },
          ],
        }),
      ],
      1,
    )
    expect(result.problems[0]).toContain('列名「ID」重复')
    expect(result.sql).toContain('id INT PRIMARY KEY')
    expect(result.sql).not.toContain('BIGINT')
  })

  it('多张表按顺序各生成一块', () => {
    const result = buildDatasetSql([table({ name: 'a' }), table({ name: 'b', columns: [{ name: 'x', type: 'INT', primaryKey: true }] })], 2)
    const indexA = result.sql.indexOf('CREATE TABLE a')
    const indexB = result.sql.indexOf('CREATE TABLE b')
    expect(indexA).toBeGreaterThanOrEqual(0)
    expect(indexB).toBeGreaterThan(indexA)
  })

  it('生成结果只含建表与插数（与后端白名单同口径的朴素断言）', () => {
    const sql = buildDatasetSql([table()], 5).sql
    for (const forbidden of ['TRIGGER', 'PROCEDURE', 'LOAD DATA', 'DROP', 'UPDATE', 'DELETE']) {
      expect(sql.toUpperCase()).not.toContain(forbidden)
    }
  })
})

describe('designValue：样例值确定性且主键唯一', () => {
  it('同结构反复生成得到同样的值', () => {
    const column = { name: 'title', type: 'VARCHAR(50)', primaryKey: false }
    expect(designValue(column, 2)).toBe(designValue(column, 2))
  })

  it('日期型主键逐天走、不重复（最多 50 行也唯一）', () => {
    const column = { name: 'day', type: 'DATE', primaryKey: true }
    const values = new Set(Array.from({ length: 50 }, (_, index) => designValue(column, index + 1)))
    expect(values.size).toBe(50)
    expect(designValue(column, 1)).toBe("'2026-01-01'")
    expect(designValue(column, 2)).toBe("'2026-01-02'")
  })

  it('TINYINT 主键用行号（唯一），非主键才用 0/1', () => {
    expect(designValue({ name: 'flag', type: 'TINYINT', primaryKey: true }, 2)).toBe('2')
    expect(designValue({ name: 'flag', type: 'TINYINT', primaryKey: false }, 2)).toBe('0')
  })
})