---
title: SqlSource 与 BoundSql：#{} 在哪一步变成 ?
category: mybatis-source
order: 6
minutes: 25
level: intermediate
status: published
---

## 这一关要读什么

`SqlSource` 是「还没绑定参数的 SQL」，`BoundSql` 是「这一次查询真正要发的 SQL + 参数列表」。

这一关回答一个问题：**`#{id}` 是在什么时候变成 `?` 的？**

```code-flow
{
  "id": "sqlsource-boundSql",
  "title": "SqlSource → BoundSql",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/mapping/SqlSource.java",
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
      "title": "接口只有一个方法",
      "file": "org/apache/ibatis/mapping/SqlSource.java",
      "line": 26,
      "code": "  BoundSql getBoundSql(Object parameterObject);",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`getBoundSql(parameterObject)`：给一个参数对象，还一份可执行的 SQL。接口这么小，是因为所有变化都藏在实现里。",
      "log": [
        {
          "k": "实现类",
          "v": "DynamicSqlSource / RawSqlSource / StaticSqlSource"
        }
      ]
    },
    {
      "title": "动态 SQL：每次查询都重新拼一遍",
      "file": "org/apache/ibatis/scripting/xmltags/DynamicSqlSource.java",
      "line": 37,
      "code": "  public BoundSql getBoundSql(Object parameterObject) {\n    DynamicContext context = new DynamicContext(configuration, parameterObject);\n    rootSqlNode.apply(context);\n    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);\n    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();\n    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());\n    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);\n    context.getBindings().forEach(boundSql::setAdditionalParameter);\n    return boundSql;\n  }",
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
        10
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "每次 `getBoundSql` 都新建 `DynamicContext`、把树 apply 一遍，然后才交给 `SqlSourceBuilder` 把 `#{}` 换成 `?`。**这就是动态 SQL 的代价**：省不了这次拼接。",
      "state": {
        "vars": [
          {
            "name": "每次调用",
            "value": "重新拼"
          }
        ],
        "stack": [
          "DynamicSqlSource.getBoundSql",
          "rootSqlNode.apply",
          "SqlSourceBuilder.parse"
        ]
      },
      "predict": {
        "question": "动态 SQL 的 #{} 每次查询都重新解析吗？",
        "options": [
          "会，每次都拼",
          "不会，构建期一次"
        ],
        "answer": 0,
        "reveal": "DynamicSqlSource 每次新建 DynamicContext 再走 SqlSourceBuilder。"
      }
    },
    {
      "title": "静态 SQL：构建期就解析完了",
      "file": "org/apache/ibatis/scripting/defaults/RawSqlSource.java",
      "line": 39,
      "code": "  public RawSqlSource(Configuration configuration, SqlNode rootSqlNode, Class<?> parameterType) {\n    this(configuration, getSql(configuration, rootSqlNode), parameterType);\n  }\n\n  public RawSqlSource(Configuration configuration, String sql, Class<?> parameterType) {\n    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);\n    Class<?> clazz = parameterType == null ? Object.class : parameterType;\n    sqlSource = sqlSourceParser.parse(sql, clazz, new HashMap<>());\n  }",
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
        9
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "构造函数里 `getSql(rootSqlNode)` 先走一遍树（因为可能出现 `${}` 或直接文本），然后 `SqlSourceBuilder.parse` 一次，之后每次查询直接复用 `StaticSqlSource`。",
      "state": {
        "vars": [
          {
            "name": "解析次数",
            "value": "构建期 1 次"
          }
        ],
        "stack": [
          "RawSqlSource.<init>",
          "SqlSourceBuilder.parse"
        ]
      }
    },
    {
      "title": "#{} → ?：核心就一个 TokenHandler",
      "file": "org/apache/ibatis/builder/SqlSourceBuilder.java",
      "line": 44,
      "code": "  public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {\n    ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType,\n        additionalParameters);\n    GenericTokenParser parser = new GenericTokenParser(\"#{\", \"}\", handler);\n    String sql;\n    if (configuration.isShrinkWhitespacesInSql()) {\n      sql = parser.parse(removeExtraWhitespaces(originalSql));\n    } else {\n      sql = parser.parse(originalSql);\n    }\n    return new StaticSqlSource(configuration, sql, handler.getParameterMappings());\n  }",
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
        12
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`GenericTokenParser(\"#{\", \"}\", handler)` 扫全文，每遇到一个占位符就回调 `handleToken`：生成一个 `ParameterMapping`（属性名、javaType、jdbcType、typeHandler），同时把文本换成 `?`。",
      "log": [
        {
          "k": "parameterMappings",
          "v": "id → Long, name → String"
        }
      ],
      "state": {
        "vars": [
          {
            "name": "原 SQL",
            "value": "where id = #{id} and name = #{name}"
          },
          {
            "name": "解析后",
            "value": "where id = ? and name = ?"
          }
        ]
      },
      "predict": {
        "question": "一个 SQL 里有 3 个 #{...}，解析后 ParameterMapping 有几个？",
        "options": [
          "1 个",
          "3 个，顺序与出现顺序一致",
          "0 个，运行时才算"
        ],
        "answer": 1,
        "reveal": "每个占位符对应一个 ParameterMapping，顺序就是 `?` 的顺序——绑定参数时按下标取。"
      }
    },
    {
      "title": "BoundSql：一次查询的最终形态",
      "file": "org/apache/ibatis/mapping/BoundSql.java",
      "line": 35,
      "code": "public class BoundSql {\n\n  private final String sql;\n  private final List<ParameterMapping> parameterMappings;\n  private final Object parameterObject;\n  private final Map<String, Object> additionalParameters;",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "四个字段：`sql`（带 `?` 的文本）、`parameterMappings`（按下标绑定）、`parameterObject`（你传的入参）、`additionalParameters`（`<foreach>` 生成的 `__frch_*` 之类）。",
      "log": [
        {
          "k": "sql",
          "v": "select ... where id = ?"
        },
        {
          "k": "parameterMappings",
          "v": "1 个"
        },
        {
          "k": "additionalParameters",
          "v": "空"
        }
      ]
    },
    {
      "title": "注意 getSql 返回的是可执行文本",
      "file": "org/apache/ibatis/mapping/BoundSql.java",
      "line": 52,
      "code": "  public String getSql() {\n    return sql;\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "调试时打 `boundSql.getSql()` 能看到真实 SQL；但参数还是分离的，别以为日志里那条 SQL 能直接复制去跑。",
      "state": {
        "vars": [
          {
            "name": "getSql()",
            "value": "select ... where id = ?"
          }
        ]
      }
    }
  ]
}
```

## 三层的分工

| 层 | 什么时候生成 | 内容 |
| --- | --- | --- |
| `SqlSource` | 构建期 | SQL 模板 / SqlNode 树 |
| `BoundSql` | 每次查询 | 带 `?` 的 SQL + ParameterMapping 列表 |
| `PreparedStatement` | 执行期 | 真正绑定参数、发给数据库 |

```code-challenge
{
  "id": "ex-sqlsource",
  "kind": "fill",
  "title": "补 #{} 的归宿",
  "template": "`#{id}` 在 {{0}} 里被替换成 `{{1}}`，同时生成 ParameterMapping。",
  "options": [
    [
      "SqlSourceBuilder.parse",
      "ResultSetHandler"
    ],
    [
      "?",
      "#{id}"
    ]
  ],
  "answers": [
    0,
    0
  ],
  "explain": "解析期就把占位符换成 ?，参数值执行期才绑。"
}
```

```quiz
{
  "id": "sqlsource-quiz",
  "title": "小测：SQL 的三种形态",
  "questions": [
    {
      "question": "带 <if> 的 SQL，每次查询会重新解析 #{} 吗？",
      "options": ["会，DynamicSqlSource 每次都拼", "不会，构建期解析一次", "只解析第一次"],
      "answer": 0,
      "explain": "DynamicSqlSource.getBoundSql 每次新建 DynamicContext 并重新走 SqlSourceBuilder。"
    },
    {
      "question": "BoundSql 里的 sql 字段是什么样？",
      "options": ["原始 XML 文本", "带 ? 的可执行 SQL", "已经绑好值的 SQL"],
      "answer": 1,
      "explain": "参数是分离的：`?` 配合 parameterMappings，绑定发生在 ParameterHandler。"
    },
    {
      "question": "RawSqlSource 适合什么语句？",
      "options": ["有 <foreach> 的", "有 ${} 的", "没有任何动态标签的"],
      "answer": 2,
      "explain": "没有动态节点才会走 RawSqlSource；有 `${}` 的文本节点仍算动态。"
    }
  ]
}
```

下一关：`?` 有了，参数是怎么按名字找到值的？——`ParamNameResolver`。
