---
title: SQLGlot：把 SQL 当成语法树来改
category: open-source
order: 3
minutes: 7
level: intermediate
status: published
---

## 一句话定位

**SQLGlot 是一个纯 Python 的 SQL 解析器与转译器**：把 SQL 解析成语法树，你可以遍历、改写，再生成回 SQL——包括换成另一种方言。

```sketch
{
  "title": "SQL 当成树来改，再生成回去",
  "rows": [
    [
      { "id": "parse", "title": "解析成语法树", "sub": "不是字符串替换", "tone": "blue" },
      { "id": "edit", "title": "遍历与改写节点", "sub": "按结构改，不怕格式", "tone": "blue" },
      { "id": "gen", "title": "生成另一种方言", "sub": "同一棵树换输出", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "parse", "to": "edit" },
    { "from": "edit", "to": "gen" }
  ]
}
```

## 它解决什么问题

只要你的系统要处理"用户写的 SQL"，就会撞上三类需求：

1. **校验**：这条语句能不能跑？是不是只读？
2. **改写**：给表名加租户前缀、给查询加 `LIMIT`、把 `SELECT *` 展开成具体列。
3. **跨库**：同一份逻辑要跑在 MySQL 和 PostgreSQL 上。

用字符串正则做这三件事，一定会出错——SQL 有嵌套、有注释、有引号内的同形字。你需要一个真正的解析器。

## 核心机制

1. **手写递归下降解析器 + 明确的 AST 节点类型**。`parse_one("SELECT a FROM t")` 返回一个 `Select` 节点，它的 `expressions` 是 `[Column(a)]`，`from` 是 `From(this=Table(t))`。所有 SQL 结构都是可预测的 Python 类，可以 `isinstance` 判断、可以 `.find_all(exp)` 遍历。
2. **方言是一等公民**。`parse_one(sql, dialect="mysql")` 用 MySQL 的词法，`node.sql(dialect="postgres")` 用 PostgreSQL 的语法生成。方言差异（`LIMIT` vs `TOP`、反引号 vs 双引号、日期函数）被封装在 dialect 类里。
3. **改写就是改树**。`node.transform(fn)` 自底向上传每个节点，返回新节点。想做"给所有表名加前缀"，写一个 visitor 找 `Table` 节点改 `name` 就行——不需要拼接字符串。
4. **纯 Python、无原生依赖**。这是它相对 `sqlparse`/`pglast` 的关键差异：`pglast` 绑定 PostgreSQL 的解析器（解析更权威但要装 libpg_query），SQLGlot 自己写解析器（能跨方言但可能有边角不覆盖）。
5. **能算血缘（lineage）**。`lineage(node)` 给出"这个字段来自哪张表哪一列"，做数据治理时直接可用。

## 什么时候该用

- SQL 编辑器/工作台要做语法校验、自动补全、格式化
- 数据平台要做字段血缘、影响分析
- 多方言支持（一套逻辑输出到 MySQL/PG/Spark）
- 需要在执行前**静态判断**语句是否安全（是否含 `DELETE`/`DROP`）
- 给查询加审计所需的东西（限制返回行数、注入注释）

## 什么时候别用

- **只做简单校验**。如果只需判断"是不是 SELECT 开头"，正则就够，不用引入解析器。
- **需要 100% 与数据库自己的解析器一致**。SQLGlot 是重新实现的解析器，极端边角语法可能解析失败或语义不同。要绝对权威就得用目标数据库自己的解析器（`pglast`、或让数据库 `EXPLAIN` 一次）。
- **性能敏感的每请求路径**。解析有成本（毫秒级），极高的 QPS 下要缓存解析结果。
- **不写 Python**。它是 Python 库；Java 侧对应的是 Apache Calcite（更重）、JS 侧是 `node-sql-parser`（方言少）。

## 替代品

| 场景 | 替代 |
| --- | --- |
| 只要格式化 | `sqlformat`、`sqlparse` |
| 要 PostgreSQL 权威解析 | `pglast`（绑定真实解析器） |
| Java 生态 | Apache Calcite（能优化执行计划，但学习曲线陡） |
| 只要血缘 | `sqllineage`（内部也用 SQLGlot） |

## 出处

- **许可证**：MIT
- **仓库**：https://github.com/tobymao/sqlglot
- **对应版本**：25.x（`lineage` 与多数方言在此版本稳定）

## 一个真实的使用边界

本仓库的 SQL 沙箱选择的是**更笨但更可靠**的做法：用执行结果比对判题，而不是解析 SQL 判对错。原因是——"这条 SQL 写得对不对"在语义上等于"它返回了期望的结果"，用解析器判断"写法是否符合预期"会把正确答案误判为错。

SQLGlot 适合的是**安全与治理**这一类"看结构就够"的判断（是不是只读、有没有全表扫），不适合"判断答案对不对"。
