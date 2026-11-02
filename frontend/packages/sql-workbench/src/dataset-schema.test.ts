import { describe, expect, it } from 'vitest'

import { parseCreateTableNames, parseSchema, primaryKeys, toCompletionSchema } from './dataset-schema'

/**
 * 数据集结构（WORK-035 T-03）。
 *
 * <p>语料取自 `scripts/gen-dataset-schema.mjs` 在真实镜像上生成的快照形状。
 */
const REAL = JSON.stringify({
  tables: [
    {
      name: 'customers',
      columns: [
        { name: 'id', type: 'int', nullable: false, primaryKey: true },
        { name: 'name', type: 'varchar(20)', nullable: false, primaryKey: false },
        { name: 'note', type: 'varchar(20)', nullable: true, primaryKey: false },
      ],
    },
    {
      name: 'orders',
      columns: [
        { name: 'id', type: 'int', nullable: false, primaryKey: true },
        { name: 'customer_id', type: 'int', nullable: false, primaryKey: false },
      ],
    },
  ],
  relations: [],
})

describe('parseSchema', () => {
  it('解析真实快照形状', () => {
    const schema = parseSchema(REAL)
    expect(schema?.tables).toHaveLength(2)
    expect(schema?.tables[0]?.name).toBe('customers')
    expect(schema?.tables[0]?.columns[0]?.primaryKey).toBe(true)
  })

  /** 坏数据不能让页面崩——结构是"锦上添花"，缺了也要能用编辑器。 */
  it('坏数据返回 null 而不是抛异常', () => {
    expect(parseSchema(null)).toBeNull()
    expect(parseSchema(undefined)).toBeNull()
    expect(parseSchema('')).toBeNull()
    expect(parseSchema('not json')).toBeNull()
    expect(parseSchema('{"foo":1}')).toBeNull()
  })

  it('缺 relations 时补空数组', () => {
    expect(parseSchema('{"tables":[]}')?.relations).toEqual([])
  })
})

describe('toCompletionSchema', () => {
  it('转成 { 表名: [列名] }，不带类型', () => {
    const completion = toCompletionSchema(parseSchema(REAL))
    expect(completion).toEqual({
      customers: ['id', 'name', 'note'],
      orders: ['id', 'customer_id'],
    })
    // 类型不该进补全列表：否则补全里会出现 int / varchar(20) 这种噪声
    expect(Object.values(completion).flat()).not.toContain('int')
  })

  it('schema 为 null 时返回空对象', () => {
    expect(toCompletionSchema(null)).toEqual({})
  })
})

describe('primaryKeys', () => {
  it('列出主键列', () => {
    const schema = parseSchema(REAL)
    expect(primaryKeys(schema!.tables[0]!)).toEqual(['id'])
  })

  it('没有主键时返回空数组', () => {
    expect(primaryKeys({ name: 't', columns: [{ name: 'a', type: 'int', nullable: true, primaryKey: false }] })).toEqual([])
  })
})

describe('parseCreateTableNames（保存前预览）', () => {
  it('数出标准生成的建表语句', () => {
    const sql = `
      CREATE TABLE customers (id INT PRIMARY KEY);
      CREATE TABLE orders (id INT PRIMARY KEY);
      CREATE TABLE order_items (id INT PRIMARY KEY);
      INSERT INTO customers VALUES (1);
    `
    expect(parseCreateTableNames(sql)).toEqual(['customers', 'orders', 'order_items'])
  })

  it('认 IF NOT EXISTS、反引号与大小写', () => {
    expect(parseCreateTableNames('create table if not exists `Orders` (id int);')).toEqual(['orders'])
  })

  it('没有建表语句时返回空数组', () => {
    expect(parseCreateTableNames('SELECT * FROM customers;')).toEqual([])
    expect(parseCreateTableNames('')).toEqual([])
  })
})
