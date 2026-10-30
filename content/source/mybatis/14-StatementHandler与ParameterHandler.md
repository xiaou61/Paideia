---
title: StatementHandler 与 ParameterHandler：谁造语句、谁绑参数
category: mybatis-source
order: 14
minutes: 25
level: intermediate
status: published
---

## 这一关要读什么

到了 JDBC 边界：`Executor` 已经知道要执行哪条语句，接下来要有人

- 造一个 `PreparedStatement`（或 `Statement` / `CallableStatement`）；
- 把参数按位置绑上去；
- 执行并把 `ResultSet` 交给结果映射。

这三件事分别由 `StatementHandler` 与 `ParameterHandler` 负责。

```source-walk
{
  "id": "statement-handler",
  "title": "从 Routing 到 PreparedStatement",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java", "label": "RoutingStatementHandler" },
    { "path": "org/apache/ibatis/executor/statement/BaseStatementHandler.java", "label": "BaseStatementHandler" },
    { "path": "org/apache/ibatis/executor/statement/PreparedStatementHandler.java", "label": "PreparedStatementHandler" },
    { "path": "org/apache/ibatis/scripting/defaults/DefaultParameterHandler.java", "label": "DefaultParameterHandler" }
  ],
  "controls": [
    {
      "id": "statementType",
      "type": "select",
      "label": "statementType",
      "default": "PREPARED",
      "options": [
        { "value": "PREPARED", "label": "PREPARED（默认）" },
        { "value": "STATEMENT", "label": "STATEMENT" },
        { "value": "CALLABLE", "label": "CALLABLE" }
      ]
    }
  ],
  "steps": [
    {
      "title": "Routing：按 statementType 造不同的 handler",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 39,
      "lines": 18,
      "explain": "`switch (ms.getStatementType())`：STATEMENT → `SimpleStatementHandler`，PREPARED → `PreparedStatementHandler`，CALLABLE → `CallableStatementHandler`。构造完就把一切委派给它。",
      "state": {
        "tables": [["delegate", "PreparedStatementHandler"]]
      }
    },
    {
      "title": "Base：四个 handler 共享的初始化",
      "file": "org/apache/ibatis/executor/statement/BaseStatementHandler.java",
      "line": 53,
      "lines": 22,
      "explain": "存 executor / ms / rowBounds，拿 TypeHandlerRegistry 与 ObjectFactory；`boundSql == null` 时先生成主键再取 BoundSql；最后建 `ParameterHandler` 与 `ResultSetHandler`。",
      "state": {
        "tables": [["parameterHandler", "DefaultParameterHandler"], ["resultSetHandler", "DefaultResultSetHandler"]]
      }
    },
    {
      "title": "PREPARED：带 ? 的语句",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 46,
      "lines": 3,
      "explain": "默认就是它。SQL 里已经是 `?`，参数由 `ParameterHandler` 按位置绑。",
      "when": { "statementType": "PREPARED" },
      "state": {
        "tables": [["handler", "PreparedStatementHandler"]]
      }
    },
    {
      "title": "STATEMENT：没有占位符，SQL 必须已经拼好",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 42,
      "lines": 5,
      "explain": "`SimpleStatementHandler` 直接把 `BoundSql.getSql()` 交给 `Statement.execute`。`#{ }` 在这种模式下会被当成普通文本——所以不能混用。",
      "when": { "statementType": "STATEMENT" },
      "state": {
        "tables": [["handler", "SimpleStatementHandler"], ["参数", "不绑定"]]
      }
    },
    {
      "title": "CALLABLE：存储过程",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 49,
      "lines": 3,
      "explain": "`CallableStatementHandler` 额外处理 OUT 参数，`{call proc(?, ?)}` 这种写法就是它。",
      "when": { "statementType": "CALLABLE" },
      "state": {
        "tables": [["handler", "CallableStatementHandler"], ["OUT 参数", "额外处理"]]
      }
    },
    {
      "title": "造 PreparedStatement：预编译与主键",
      "file": "org/apache/ibatis/executor/statement/PreparedStatementHandler.java",
      "line": 77,
      "lines": 20,
      "explain": "按 `resultSetType` / `resultSetConcurrency` 调 `connection.prepareStatement(...)`；配了 `keyProperty` 的 INSERT 还要带 `RETURN_GENERATED_KEYS`。语句超时也在这里设。",
      "state": {
        "vars": [["sql", "select ... where id = ?"], ["generatedKeys", "true（INSERT）"]]
      }
    },
    {
      "title": "绑参数只占一行",
      "file": "org/apache/ibatis/executor/statement/PreparedStatementHandler.java",
      "line": 96,
      "lines": 3,
      "explain": "`parameterize` 把活全交给 `parameterHandler.setParameters(ps)`。职责切得很干净：StatementHandler 管 JDBC 语句，ParameterHandler 管值。",
      "state": {
        "stack": ["PreparedStatementHandler.parameterize", "DefaultParameterHandler.setParameters"]
      }
    },
    {
      "title": "执行只有两行",
      "file": "org/apache/ibatis/executor/statement/PreparedStatementHandler.java",
      "line": 63,
      "lines": 5,
      "explain": "`ps.execute()` 然后 `resultSetHandler.handleResultSets(ps)`。到这里 JDBC 的部分结束，后面全是映射。",
      "state": {
        "stack": ["ps.execute()", "ResultSetHandler.handleResultSets"]
      }
    },
    {
      "title": "绑参数：按 ParameterMapping 逐个取",
      "file": "org/apache/ibatis/scripting/defaults/DefaultParameterHandler.java",
      "line": 62,
      "lines": 22,
      "explain": "遍历 `parameterMappings`：先从 additionalParameters 找（`__frch_*`），再判断参数本身、最后用 `MetaObject` 反射。取到值再交给 `typeHandler.setParameter`。",
      "state": {
        "tables": [["#{id}", "LongTypeHandler"], ["#{user.name}", "StringTypeHandler"]]
      }
    }
  ]
}
```

## 这条链上谁负责什么

| 角色 | 负责 | 不负责 |
| --- | --- | --- |
| `Executor` | 缓存、事务、批处理 | JDBC 语句长什么样 |
| `StatementHandler` | 造语句、执行、拿 ResultSet | 参数值从哪来 |
| `ParameterHandler` | 按 ParameterMapping 绑值 | 结果映射 |
| `ResultSetHandler` | 结果集 → 对象 | SQL 与参数 |

```quiz
{
  "id": "statement-handler-quiz",
  "title": "小测：JDBC 边界",
  "questions": [
    {
      "question": "statementType=STATEMENT 时，#{id} 还会被绑定吗？",
      "options": ["会", "不会，它被当普通文本", "看数据库"],
      "answer": 1,
      "explain": "SimpleStatementHandler 不调 ParameterHandler，`?` 也没有，#{id} 原样进 SQL。"
    },
    {
      "question": "PreparedStatementHandler.parameterize 实际做了什么？",
      "options": ["自己遍历参数", "转调 parameterHandler.setParameters", "重新拼 SQL"],
      "answer": 1,
      "explain": "只有一行：`parameterHandler.setParameters((PreparedStatement) statement)`。"
    },
    {
      "question": "RETURN_GENERATED_KEYS 在哪一步用到？",
      "options": ["prepareStatement", "execute", "ResultSetHandler"],
      "answer": 0,
      "explain": "`instantiateStatement` 里按 keyProperty 决定要不要带这个标志。"
    }
  ]
}
```

下一关：主键回填与批量执行——`Jdbc3KeyGenerator` 到底在什么时候把 id 写回对象。
