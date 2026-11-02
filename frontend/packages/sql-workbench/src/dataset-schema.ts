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

// ── 可视化建表（2026-11-02 用户要求：不写 SQL 也能造数据集）────────────────────

/** 可视化建表的一列。主键用单选（每表一个）——多主键是复合键，这里不做。 */
export interface DesignColumn {
  name: string
  type: string
  primaryKey: boolean
}

export interface DesignTable {
  name: string
  columns: DesignColumn[]
}

/**
 * 可视化建表给出的类型选项。
 *
 * <p>与 AI 工具 `planner_build_dataset` 同一套（INT / BIGINT / TINYINT / DECIMAL / VARCHAR /
 * DATE / DATETIME）：不是服务端限制，而是两边产出的 SQL 保持一个样，用户对照着学不会迷惑。
 */
export const DESIGN_TYPES = [
  'INT',
  'BIGINT',
  'TINYINT',
  'DECIMAL(10,2)',
  'VARCHAR(50)',
  'VARCHAR(100)',
  'DATE',
  'DATETIME',
] as const

/** 标识符规则，与后端 `SqlDatasetBuildTools` 的 IDENTIFIER 逐字一致。 */
const DESIGN_IDENTIFIER = /^[a-z][a-z0-9_]{0,29}$/

export interface DesignResult {
  /** 生成好的 CREATE TABLE + INSERT；输入还不足以生成时是空串。 */
  sql: string
  /** 填了但生成不了的项。**空表名/空列名不算**——那是"还没填完"，静默跳过不打扰。 */
  problems: string[]
}

/**
 * 按类型造一个确定性的样例值（列名 + 行号 → 值）。
 *
 * <p>**不用随机数**：同一个结构反复生成、以及"保存前在沙箱里干跑"看到的都是同一份 SQL，
 * 出错了才能对着看。主键列必须唯一：DATE/DATETIME 从 2026-01-01 起按行号逐天走
 * （最多 50 行，不会撞），TINYINT 主键用行号本身（非主键才用 0/1）。
 */
export function designValue(column: DesignColumn, row: number): string {
  const type = column.type.toUpperCase()
  if (type === 'TINYINT') {
    return String(column.primaryKey ? row : row % 2)
  }
  if (type === 'INT' || type === 'BIGINT') {
    return String(row)
  }
  if (type.startsWith('DECIMAL')) {
    return (row * 10.5).toFixed(2)
  }
  if (type.startsWith('VARCHAR')) {
    return `'${column.name}-${row}'`
  }
  const date = new Date(Date.UTC(2026, 0, 1) + (row - 1) * 86_400_000).toISOString().slice(0, 10)
  return type === 'DATE' ? `'${date}'` : `'${date} 09:00:00'`
}

/** 输入框里的名字：trim + 转小写（大小写是最常见的手误，直接替用户改掉）。 */
function designName(raw: string): string {
  return raw.trim().toLowerCase()
}

/**
 * 把可视化结构生成成可保存的 SQL。
 *
 * <p>产出只有 `CREATE TABLE` 与 `INSERT`（服务端白名单与"只允许建表与插数"口径一致）。
 * 行数是**每张表**插入的行数，夹在 1–50（与 AI 工具的上限同档）。
 *
 * <p>不完整的草稿（还没起名的表、还没起名的列）静默跳过：用户是一边加表一边看 SQL 长出来的，
 * 每次新加一行就跳一个红色错误会很难用。真的填错了（大写开头之外的中文/数字开头/超长）才报。
 */
export function buildDatasetSql(tables: DesignTable[], rows: number): DesignResult {
  const problems: string[] = []
  const blocks: string[] = []
  const rowCount = Math.max(1, Math.min(50, Math.trunc(rows)))

  for (const table of tables) {
    const tableName = designName(table.name)
    if (tableName === '') {
      continue
    }
    if (!DESIGN_IDENTIFIER.test(tableName)) {
      problems.push(`表名「${table.name.trim()}」不能用：要字母开头，只能用字母、数字、下划线，最多 30 个字符`)
      continue
    }

    const columns: DesignColumn[] = []
    for (const column of table.columns) {
      const columnName = designName(column.name)
      if (columnName === '') {
        continue
      }
      if (!DESIGN_IDENTIFIER.test(columnName)) {
        problems.push(`列名「${column.name.trim()}」不能用：要字母开头，只能用字母、数字、下划线，最多 30 个字符`)
        continue
      }
      if (columns.some((existing) => existing.name === columnName)) {
        problems.push(`表 ${tableName} 里列名「${column.name.trim()}」重复了`)
        continue
      }
      columns.push({ name: columnName, type: column.type, primaryKey: column.primaryKey })
    }
    if (columns.length === 0) {
      continue
    }

    const definitions = columns.map(
      (column) => `  ${column.name} ${column.type}${column.primaryKey ? ' PRIMARY KEY' : ''}`,
    )
    const columnList = columns.map((column) => column.name).join(', ')
    const values: string[] = []
    for (let row = 1; row <= rowCount; row++) {
      values.push('(' + columns.map((column) => designValue(column, row)).join(', ') + ')')
    }
    blocks.push(
      `CREATE TABLE ${tableName} (\n${definitions.join(',\n')}\n);\n` +
        `INSERT INTO ${tableName} (${columnList}) VALUES\n  ${values.join(',\n  ')};`,
    )
  }

  return { sql: blocks.join('\n\n'), problems }
}
