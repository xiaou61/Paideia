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

```code-flow
{
  "id": "statement-handler",
  "title": "从 Routing 到 PreparedStatement",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
  "nodes": [
    {
      "id": "api",
      "label": "Mapper 接口 / SqlSession",
      "sub": "调用入口",
      "layer": 0,
      "order": 0
    },
    {
      "id": "config",
      "label": "Configuration",
      "sub": "配置与映射",
      "layer": 1,
      "order": 0
    },
    {
      "id": "proxy",
      "label": "MapperProxy",
      "sub": "动态代理",
      "layer": 2,
      "order": 0
    },
    {
      "id": "executor",
      "label": "Executor",
      "sub": "执行器",
      "layer": 3,
      "order": 0
    },
    {
      "id": "statement",
      "label": "StatementHandler",
      "sub": "JDBC 语句",
      "layer": 4,
      "order": 0
    },
    {
      "id": "result",
      "label": "ResultSetHandler",
      "sub": "结果映射",
      "layer": 5,
      "order": 0
    },
    {
      "id": "base",
      "label": "基础模块",
      "sub": "反射 / 类型 / 缓存 / 插件",
      "layer": 6,
      "order": 0
    }
  ],
  "edges": [
    {
      "from": "api",
      "to": "proxy",
      "label": "调用接口方法"
    },
    {
      "from": "config",
      "to": "proxy",
      "label": "注册 Mapper"
    },
    {
      "from": "proxy",
      "to": "executor",
      "label": "MapperMethod"
    },
    {
      "from": "config",
      "to": "executor",
      "label": "默认执行器"
    },
    {
      "from": "executor",
      "to": "statement",
      "label": "prepare"
    },
    {
      "from": "statement",
      "to": "result",
      "label": "ResultSet"
    },
    {
      "from": "executor",
      "to": "base",
      "label": "用到时才取"
    }
  ],
  "steps": [
    {
      "title": "Routing：按 statementType 造不同的 handler",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 39,
      "code": "  public RoutingStatementHandler(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds,\n      ResultHandler resultHandler, BoundSql boundSql) {\n\n    switch (ms.getStatementType()) {\n      case STATEMENT:\n        delegate = new SimpleStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);\n        break;\n      case PREPARED:\n        delegate = new PreparedStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);\n        break;\n      case CALLABLE:\n        delegate = new CallableStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);\n        break;\n      default:\n        throw new ExecutorException(\"Unknown statement type: \" + ms.getStatementType());\n    }\n\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17,
        18
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "`switch (ms.getStatementType())`：STATEMENT → `SimpleStatementHandler`，PREPARED → `PreparedStatementHandler`，CALLABLE → `CallableStatementHandler`。构造完就把一切委派给它。",
      "log": [
        {
          "k": "delegate",
          "v": "PreparedStatementHandler"
        }
      ]
    },
    {
      "title": "Base：四个 handler 共享的初始化",
      "file": "org/apache/ibatis/executor/statement/BaseStatementHandler.java",
      "line": 53,
      "code": "  protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject,\n      RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {\n    this.configuration = mappedStatement.getConfiguration();\n    this.executor = executor;\n    this.mappedStatement = mappedStatement;\n    this.rowBounds = rowBounds;\n\n    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();\n    this.objectFactory = configuration.getObjectFactory();\n\n    if (boundSql == null) { // issue #435, get the key before calculating the statement\n      generateKeys(parameterObject);\n      boundSql = mappedStatement.getBoundSql(parameterObject);\n    }\n\n    this.boundSql = boundSql;\n\n    this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);\n    this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler,\n        resultHandler, boundSql);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17,
        18,
        19,
        20
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "存 executor / ms / rowBounds，拿 TypeHandlerRegistry 与 ObjectFactory；`boundSql == null` 时先生成主键再取 BoundSql；最后建 `ParameterHandler` 与 `ResultSetHandler`。",
      "log": [
        {
          "k": "parameterHandler",
          "v": "DefaultParameterHandler"
        },
        {
          "k": "resultSetHandler",
          "v": "DefaultResultSetHandler"
        }
      ]
    },
    {
      "title": "PREPARED：带 ? 的语句",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 46,
      "code": "      case PREPARED:\n        delegate = new PreparedStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);\n        break;",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "默认就是它。SQL 里已经是 `?`，参数由 `ParameterHandler` 按位置绑。",
      "log": [
        {
          "k": "handler",
          "v": "PreparedStatementHandler"
        }
      ],
      "predict": {
        "question": "默认 statementType 是？",
        "options": [
          "STATEMENT",
          "PREPARED",
          "CALLABLE"
        ],
        "answer": 1,
        "reveal": "默认 PREPARED，所以 #{} 才会变成 ?。"
      }
    },
    {
      "title": "STATEMENT：没有占位符，SQL 必须已经拼好",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 42,
      "code": "    switch (ms.getStatementType()) {\n      case STATEMENT:\n        delegate = new SimpleStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);\n        break;\n      case PREPARED:",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "`SimpleStatementHandler` 直接把 `BoundSql.getSql()` 交给 `Statement.execute`。`#{ }` 在这种模式下会被当成普通文本——所以不能混用。",
      "log": [
        {
          "k": "handler",
          "v": "SimpleStatementHandler"
        },
        {
          "k": "参数",
          "v": "不绑定"
        }
      ]
    },
    {
      "title": "CALLABLE：存储过程",
      "file": "org/apache/ibatis/executor/statement/RoutingStatementHandler.java",
      "line": 49,
      "code": "      case CALLABLE:\n        delegate = new CallableStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);\n        break;",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "`CallableStatementHandler` 额外处理 OUT 参数，`{call proc(?, ?)}` 这种写法就是它。",
      "log": [
        {
          "k": "handler",
          "v": "CallableStatementHandler"
        },
        {
          "k": "OUT 参数",
          "v": "额外处理"
        }
      ]
    },
    {
      "title": "造 PreparedStatement：预编译与主键",
      "file": "org/apache/ibatis/executor/statement/PreparedStatementHandler.java",
      "line": 77,
      "code": "  protected Statement instantiateStatement(Connection connection) throws SQLException {\n    String sql = boundSql.getSql();\n    if (mappedStatement.getKeyGenerator() instanceof Jdbc3KeyGenerator) {\n      String[] keyColumnNames = mappedStatement.getKeyColumns();\n      if (keyColumnNames == null) {\n        return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);\n      } else {\n        return connection.prepareStatement(sql, keyColumnNames);\n      }\n    }\n    if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {\n      return connection.prepareStatement(sql);\n    } else {\n      return connection.prepareStatement(sql, mappedStatement.getResultSetType().getValue(),\n          ResultSet.CONCUR_READ_ONLY);\n    }\n  }\n\n  @Override\n  public void parameterize(Statement statement) throws SQLException {",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17,
        18,
        19,
        20
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "按 `resultSetType` / `resultSetConcurrency` 调 `connection.prepareStatement(...)`；配了 `keyProperty` 的 INSERT 还要带 `RETURN_GENERATED_KEYS`。语句超时也在这里设。",
      "state": {
        "vars": [
          {
            "name": "sql",
            "value": "select ... where id = ?"
          },
          {
            "name": "generatedKeys",
            "value": "true（INSERT）"
          }
        ]
      }
    },
    {
      "title": "绑参数只占一行",
      "file": "org/apache/ibatis/executor/statement/PreparedStatementHandler.java",
      "line": 96,
      "code": "  public void parameterize(Statement statement) throws SQLException {\n    parameterHandler.setParameters((PreparedStatement) statement);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "`parameterize` 把活全交给 `parameterHandler.setParameters(ps)`。职责切得很干净：StatementHandler 管 JDBC 语句，ParameterHandler 管值。",
      "state": {
        "stack": [
          "PreparedStatementHandler.parameterize",
          "DefaultParameterHandler.setParameters"
        ]
      },
      "predict": {
        "question": "parameterize 实际做了什么？",
        "options": [
          "自己遍历参数",
          "转调 ParameterHandler",
          "重新拼 SQL"
        ],
        "answer": 1,
        "reveal": "只有一行：`parameterHandler.setParameters(ps)`。"
      }
    },
    {
      "title": "执行只有两行",
      "file": "org/apache/ibatis/executor/statement/PreparedStatementHandler.java",
      "line": 63,
      "code": "  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {\n    PreparedStatement ps = (PreparedStatement) statement;\n    ps.execute();\n    return resultSetHandler.handleResultSets(ps);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "`ps.execute()` 然后 `resultSetHandler.handleResultSets(ps)`。到这里 JDBC 的部分结束，后面全是映射。",
      "state": {
        "stack": [
          "ps.execute()",
          "ResultSetHandler.handleResultSets"
        ]
      }
    },
    {
      "title": "绑参数：按 ParameterMapping 逐个取",
      "file": "org/apache/ibatis/scripting/defaults/DefaultParameterHandler.java",
      "line": 62,
      "code": "  public void setParameters(PreparedStatement ps) {\n    ErrorContext.instance().activity(\"setting parameters\").object(mappedStatement.getParameterMap().getId());\n    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();\n    if (parameterMappings != null) {\n      MetaObject metaObject = null;\n      for (int i = 0; i < parameterMappings.size(); i++) {\n        ParameterMapping parameterMapping = parameterMappings.get(i);\n        if (parameterMapping.getMode() != ParameterMode.OUT) {\n          Object value;\n          String propertyName = parameterMapping.getProperty();\n          if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params\n            value = boundSql.getAdditionalParameter(propertyName);\n          } else if (parameterObject == null) {\n            value = null;\n          } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {\n            value = parameterObject;\n          } else {\n            if (metaObject == null) {\n              metaObject = configuration.newMetaObject(parameterObject);\n            }\n            value = metaObject.getValue(propertyName);\n          }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17,
        18,
        19,
        20
      ],
      "nodes": [
        "statement"
      ],
      "edges": [],
      "explain": "遍历 `parameterMappings`：先从 additionalParameters 找（`__frch_*`），再判断参数本身、最后用 `MetaObject` 反射。取到值再交给 `typeHandler.setParameter`。",
      "log": [
        {
          "k": "#{id}",
          "v": "LongTypeHandler"
        },
        {
          "k": "#{user.name}",
          "v": "StringTypeHandler"
        }
      ]
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

```code-challenge
{
  "id": "ex-statementhandler",
  "kind": "order",
  "title": "排出 JDBC 边界的四步",
  "lines": [
    "PreparedStatementHandler.parameterize 绑参数",
    "RoutingStatementHandler 按 statementType 建 delegate",
    "PreparedStatementHandler.query 执行并取结果集",
    "BaseStatementHandler 构造 ParameterHandler 与 ResultSetHandler"
  ],
  "answer": [
    1,
    3,
    0,
    2
  ],
  "explain": "先选 handler，再由基类建两个子处理器，然后绑参、执行。"
}
```

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
