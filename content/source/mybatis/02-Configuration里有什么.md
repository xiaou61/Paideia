---
title: Configuration：一个对象装下了整个 MyBatis
category: mybatis-source
order: 2
minutes: 25
level: beginner
status: published
---

## 这一关要读什么

`Configuration` 有 40 多个字段，刚打开会劝退。这一关只看三组：

- **开关**：`mapUnderscoreToCamelCase`、`useGeneratedKeys`、`cacheEnabled`……
- **注册表**：Mapper、TypeHandler、Interceptor、语言驱动。
- **映射表**：`MappedStatement`、`ResultMap`、`Cache`。

读完你会知道：为什么 MyBatis 的「默认行为」几乎全在一个对象上改。

```code-flow
{
  "id": "configuration-fields",
  "title": "Configuration 里真正重要的三类字段",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/session/Configuration.java",
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
      "title": "第一组：开关",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 105,
      "code": "  protected Environment environment;\n\n  protected boolean safeRowBoundsEnabled;\n  protected boolean safeResultHandlerEnabled = true;\n  protected boolean mapUnderscoreToCamelCase;\n  protected boolean aggressiveLazyLoading;\n  protected boolean useGeneratedKeys;",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`environment` 之外是一排布尔开关。它们不是「全局配置文档」里的摆设——每一个都会被某个执行路径读到，比如 `mapUnderscoreToCamelCase` 直接决定结果集映射时列名 `user_name` 能不能对上属性 `userName`。",
      "state": {
        "vars": [
          {
            "name": "mapUnderscoreToCamelCase",
            "value": "false（默认）"
          },
          {
            "name": "cacheEnabled",
            "value": "true"
          },
          {
            "name": "useActualParamName",
            "value": "true"
          }
        ]
      }
    },
    {
      "title": "第二组：默认值决定「不写会怎样」",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 125,
      "code": "  protected LocalCacheScope localCacheScope = LocalCacheScope.SESSION;\n  protected JdbcType jdbcTypeForNull = JdbcType.OTHER;\n  protected Set<String> lazyLoadTriggerMethods = new HashSet<>(\n      Arrays.asList(\"equals\", \"clone\", \"hashCode\", \"toString\"));\n  protected Integer defaultStatementTimeout;\n  protected Integer defaultFetchSize;\n  protected ResultSetType defaultResultSetType;\n  protected ExecutorType defaultExecutorType = ExecutorType.SIMPLE;\n  protected AutoMappingBehavior autoMappingBehavior = AutoMappingBehavior.PARTIAL;",
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
      "explain": "`localCacheScope=SESSION`、`defaultExecutorType=SIMPLE`、`autoMappingBehavior=PARTIAL`——这三个默认值几乎解释了初学者遇到的一半「为什么和我以为的不一样」。",
      "state": {
        "vars": [
          {
            "name": "localCacheScope",
            "value": "SESSION"
          },
          {
            "name": "defaultExecutorType",
            "value": "SIMPLE"
          },
          {
            "name": "autoMappingBehavior",
            "value": "PARTIAL"
          }
        ]
      },
      "predict": {
        "question": "不写 autoMappingBehavior 时默认是？",
        "options": [
          "NONE",
          "PARTIAL",
          "FULL"
        ],
        "answer": 1,
        "reveal": "PARTIAL：只有没有嵌套结果映射时才自动映射。"
      }
    },
    {
      "title": "执行器换一种，openSession 之后的对象就不同",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 132,
      "code": "  protected ExecutorType defaultExecutorType = ExecutorType.SIMPLE;",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`defaultExecutorType` 只是被 `DefaultSqlSessionFactory.openSession` 读一次。选 SIMPLE 就是每次新建一个 `SimpleExecutor`，没有花活。",
      "log": [
        {
          "k": "openSession → executor",
          "v": "SimpleExecutor"
        }
      ]
    },
    {
      "title": "REUSE：复用 Statement",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 132,
      "code": "  protected ExecutorType defaultExecutorType = ExecutorType.SIMPLE;",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "同一个 SqlSession 里，相同 SQL 的 `PreparedStatement` 会被复用。省的是 prepare 的开销，代价是 Statement 一直占着连接资源。",
      "log": [
        {
          "k": "openSession → executor",
          "v": "ReuseExecutor"
        },
        {
          "k": "statement 缓存",
          "v": "按 SQL 文本复用"
        }
      ]
    },
    {
      "title": "BATCH：攒够了一次发",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 132,
      "code": "  protected ExecutorType defaultExecutorType = ExecutorType.SIMPLE;",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`BatchExecutor` 把 update 攒起来，直到 `flushStatements()` 或提交。批量插入用它，但**查不到未提交的数据**，写一读一的时候别开。",
      "log": [
        {
          "k": "openSession → executor",
          "v": "BatchExecutor"
        },
        {
          "k": "待执行",
          "v": "攒到 flush 才发"
        }
      ]
    },
    {
      "title": "第三组：四个注册表",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 152,
      "code": "  protected final MapperRegistry mapperRegistry = new MapperRegistry(this);\n  protected final InterceptorChain interceptorChain = new InterceptorChain();\n  protected final TypeHandlerRegistry typeHandlerRegistry = new TypeHandlerRegistry(this);",
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
      "explain": "`mapperRegistry` 记「哪个接口有哪些方法」；`interceptorChain` 是插件链；`typeHandlerRegistry` 负责 Java 类型 ↔ JDBC 类型。它们都是**启动期填满、运行期只读**。",
      "log": [
        {
          "k": "mapperRegistry",
          "v": "com.example.UserMapper"
        },
        {
          "k": "interceptorChain",
          "v": "0 个（没配插件）"
        },
        {
          "k": "typeHandlerRegistry",
          "v": "内置 30+ 个"
        }
      ]
    },
    {
      "title": "映射表：MappedStatement 的 StrictMap",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 158,
      "code": "  protected final Map<String, MappedStatement> mappedStatements = new StrictMap<MappedStatement>(\n      \"Mapped Statements collection\")\n          .conflictMessageProducer((savedValue, targetValue) -> \". please check \" + savedValue.getResource() + \" and \"\n              + targetValue.getResource());",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "所有解析出来的语句都进 `mappedStatements`，键是 `namespace.id`。它用 `StrictMap`：重复键不是覆盖，而是抛异常并把两个来源文件都打出来——所以「两个 Mapper 写了同一条 id」会在启动时报错，不会静默生效。",
      "log": [
        {
          "k": "mappedStatements",
          "v": "com.example.UserMapper.selectById"
        },
        {
          "k": "resultMaps",
          "v": "com.example.UserMapper.userMap"
        }
      ],
      "predict": {
        "question": "两个 XML 里写了同一个 namespace + id，会发生什么？",
        "options": [
          "后一个覆盖前一个",
          "启动时报重复 id 异常",
          "随机用一个"
        ],
        "answer": 1,
        "reveal": "`StrictMap` 的冲突消息会指出两个资源文件。这也解释了为什么 Mapper 的 namespace 必须写全限定接口名。"
      }
    },
    {
      "title": "addMapper 只是转发",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 942,
      "code": "  public <T> void addMapper(Class<T> type) {\n    mapperRegistry.addMapper(type);\n  }",
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
      "explain": "Configuration 自己不维护接口表，直接转给 `MapperRegistry`。下一关会看 Registry 里到底存了什么。",
      "state": {
        "stack": [
          "Configuration.addMapper(type)",
          "MapperRegistry.addMapper(type)"
        ]
      }
    }
  ]
}
```

## 为什么这些默认值是「坑」

- `localCacheScope=SESSION`：同一个 SqlSession 里两次相同查询第二次不走库。跨请求没影响，但在**长事务**里会读到旧值。
- `autoMappingBehavior=PARTIAL`：没有嵌套结果映射时才自动映射。写了 `<association>` 之后，不显式配 `resultMap` 的列会悄悄不映射。
- `cacheEnabled=true`：一级缓存默认就开，关它要显式 `localCacheScope=STATEMENT` 或 `flushCache`。

```code-challenge
{
  "id": "ex-configuration",
  "kind": "fill",
  "title": "补两个默认值",
  "template": "localCacheScope 默认是 {{0}}；defaultExecutorType 默认是 {{1}}。",
  "options": [
    [
      "SESSION",
      "STATEMENT"
    ],
    [
      "SIMPLE",
      "REUSE",
      "BATCH"
    ]
  ],
  "answers": [
    0,
    0
  ],
  "explain": "这两个默认值决定了「重复查询走不走库」与「openSession 用哪种执行器」。"
}
```

```quiz
{
  "id": "configuration-quiz",
  "title": "小测：默认值",
  "questions": [
    {
      "question": "同一个 SqlSession 里重复查同一条数据，第二次还走数据库吗？",
      "options": ["走，默认不缓存", "不走，一级缓存默认开启", "看数据库"],
      "answer": 1,
      "explain": "`localCacheScope` 默认 SESSION，`cacheEnabled` 默认 true。要禁用就把 localCacheScope 改成 STATEMENT 或在语句上 flushCache。"
    },
    {
      "question": "mappedStatements 遇到重复 id 的行为是？",
      "options": ["覆盖", "抛异常并打印两个来源", "忽略后一个"],
      "answer": 1,
      "explain": "`StrictMap` 的 conflictMessageProducer 会把两个资源文件都列出来。"
    },
    {
      "question": "BATCH 执行器最适合下面哪种场景？",
      "options": ["边写边读", "批量插入", "单条查询"],
      "answer": 1,
      "explain": "BATCH 攒到 flush 才发，读不到未提交的数据；批量写才是它的主场。"
    }
  ]
}
```

下一关：`XMLMapperBuilder` 怎么把一个 `<select>` 变成 `MappedStatement`。
