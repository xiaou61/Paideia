/**
 * 数据集结构的类型与转换（WORK-035 T-03）。
 *
 * <p>服务端返回的是**构建期生成的 JSON 字符串**（`scripts/gen-dataset-schema.mjs` 产出的快照），
 * 不是运行时抓的。这里负责把它转成 CodeMirror 的补全 schema 与表结构侧栏要的形状。
 */

/** 一列。`type` 是 MySQL 的原始类型字符串（如 `decimal(10,2)`），不归一化。 */
export interface SchemaColumn {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
}

export interface SchemaTable {
  name: string
  columns: SchemaColumn[]
}

/**
 * 作者手工标注的表间关联。
 *
 * <p>**不能只靠外键**：课程第 08 关专门讲"很多团队不用外键"，所以数据集里可能一张外键都没有。
 * 这时 ER 图只画表与列、不画连线，并在界面上说明原因——画一条猜出来的连线比不画更糟。
 */
export interface SchemaRelation {
  from: string
  to: string
  /** 可选说明，如 `orders.customer_id → customers.id`。 */
  note?: string
}

export interface DatasetSchema {
  tables: SchemaTable[]
  relations: SchemaRelation[]
}

/** 解析服务端返回的 JSON 字符串。坏数据返回 null，让调用方走"结构不可用"分支而不是崩。 */
export function parseSchema(raw: string | null | undefined): DatasetSchema | null {
  if (raw === null || raw === undefined || raw.trim() === '') {
    return null
  }
  try {
    const parsed = JSON.parse(raw) as Partial<DatasetSchema>
    if (!Array.isArray(parsed.tables)) {
      return null
    }
    return {
      tables: parsed.tables.filter((table): table is SchemaTable => typeof table?.name === 'string'),
      relations: Array.isArray(parsed.relations) ? parsed.relations : [],
    }
  } catch {
    return null
  }
}

/**
 * 从 SQL 里数出 `CREATE TABLE` 的表名（WORK-060，只用于保存前预览）。
 *
 * <p>**不是 SQL 解析器**：只认字面量，注释或字符串里出现同样字样也会被算进去。
 * 真正的结构由保存时的沙箱干跑决定，这里只给用户一个「大概几张表」的确认感。
 *
 * shortcut: 正则不处理注释/字符串/临时表，结构以沙箱干跑为准；要精确预览时改成服务端 dry-run。
 */
export function parseCreateTableNames(sql: string): string[] {
  const names: string[] = []
  const pattern = /create\s+table\s+(?:if\s+not\s+exists\s+)?[`"[]?([A-Za-z_][A-Za-z0-9_]*)[`"\]]?/gi
  for (const match of sql.matchAll(pattern)) {
    const name = match[1]
    if (name !== undefined) {
      names.push(name.toLowerCase())
    }
  }
  return names
}

/**
 * 转成 CodeMirror `@codemirror/lang-sql` 的 schema 形状：`{ 表名: [列名] }`。
 *
 * <p>它只吃列名，所以这里丢掉类型信息——类型留给表结构侧栏用。把类型也塞进去不会报错，
 * 但补全列表里会出现 `int` 这种噪声。
 */
export function toCompletionSchema(schema: DatasetSchema | null): Record<string, string[]> {
  if (schema === null) {
    return {}
  }
  const result: Record<string, string[]> = {}
  for (const table of schema.tables) {
    result[table.name] = table.columns.map((column) => column.name)
  }
  return result
}

/** 主键列名，给侧栏加标记用。 */
export function primaryKeys(table: SchemaTable): string[] {
  return table.columns.filter((column) => column.primaryKey).map((column) => column.name)
}
