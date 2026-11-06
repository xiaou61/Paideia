---
title: 一个 select 标签怎么变成 MappedStatement
category: mybatis-source
order: 3
minutes: 30
level: intermediate
status: published
---

## 这一关要读什么

Mapper XML 里写的是 `<select id="selectById" resultType="User">`，运行期拿到的是
一个 `MappedStatement` 对象：语句 id、SQL 来源、参数映射、结果映射、缓存策略、主键策略。

这一关跟完 `XMLMapperBuilder` → `XMLStatementBuilder` → `MapperBuilderAssistant` 的整条装配线。

```code-flow
{
  "id": "mapper-xml-to-statement",
  "title": "从 <select> 到 MappedStatement",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
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
      "title": "一个 Mapper 文件只解析一次",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 96,
      "code": "  public void parse() {\n    if (!configuration.isResourceLoaded(resource)) {\n      configurationElement(parser.evalNode(\"/mapper\"));\n      configuration.addLoadedResource(resource);\n      bindMapperForNamespace();\n    }\n    configuration.parsePendingResultMaps(false);\n    configuration.parsePendingCacheRefs(false);\n    configuration.parsePendingStatements(false);\n  }",
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
      "explain": "`isResourceLoaded` 防重复；解析完记一笔。后面三行处理「解析时引用了还没解析到的东西」——ResultMap、cache-ref、语句都允许延后重试，这就是 `IncompleteElementException` 的用途。",
      "state": {
        "vars": [
          {
            "name": "resource",
            "value": "mapper/UserMapper.xml"
          },
          {
            "name": "namespace",
            "value": "com.example.UserMapper"
          }
        ]
      }
    },
    {
      "title": "顺序：cache → resultMap → sql 片段 → 语句",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 111,
      "code": "  private void configurationElement(XNode context) {\n    try {\n      String namespace = context.getStringAttribute(\"namespace\");\n      if (namespace == null || namespace.isEmpty()) {\n        throw new BuilderException(\"Mapper's namespace cannot be empty\");\n      }\n      builderAssistant.setCurrentNamespace(namespace);\n      cacheRefElement(context.evalNode(\"cache-ref\"));\n      cacheElement(context.evalNode(\"cache\"));\n      parameterMapElement(context.evalNodes(\"/mapper/parameterMap\"));\n      resultMapElements(context.evalNodes(\"/mapper/resultMap\"));\n      sqlElement(context.evalNodes(\"/mapper/sql\"));\n      buildStatementFromContext(context.evalNodes(\"select|insert|update|delete\"));\n    } catch (Exception e) {\n      throw new BuilderException(\"Error parsing Mapper XML. The XML location is '\" + resource + \"'. Cause: \" + e, e);\n    }\n  }",
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
        17
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`namespace` 先写进 `builderAssistant`，之后所有 id 都会自动加上前缀。接着按依赖顺序解析：缓存、参数映射、结果映射、`<sql>` 片段，最后才是语句——因为语句会引用前四者。",
      "log": [
        {
          "k": "currentNamespace",
          "v": "com.example.UserMapper"
        },
        {
          "k": "sqlFragments",
          "v": "Base_Column_List"
        }
      ]
    },
    {
      "title": "每条语句交给 XMLStatementBuilder",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 136,
      "code": "  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {\n    for (XNode context : list) {\n      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context,\n          requiredDatabaseId);\n      try {\n        statementParser.parseStatementNode();\n      } catch (IncompleteElementException e) {\n        configuration.addIncompleteStatement(statementParser);\n      }\n    }\n  }\n\n  private void cacheRefElement(XNode context) {",
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
        13
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "循环里对每个 `<select|insert|update|delete>` 建一个 `XMLStatementBuilder`。注意 catch 的是 `IncompleteElementException`——解析失败不一定是错，可能是引用了后面才定义的东西，先记进 `incompleteStatements` 等重试。",
      "state": {
        "vars": [
          {
            "name": "statements",
            "value": "4 条"
          }
        ],
        "stack": [
          "XMLMapperBuilder.parse()",
          "buildStatementFromContext(list)",
          "XMLStatementBuilder.parseStatementNode()"
        ]
      }
    },
    {
      "title": "先按 databaseId 过滤",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 58,
      "code": "  public void parseStatementNode() {\n    String id = context.getStringAttribute(\"id\");\n    String databaseId = context.getStringAttribute(\"databaseId\");\n\n    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {\n      return;\n    }",
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
      "explain": "`databaseIdMatchesCurrent` 不匹配就直接 return——同一个 id 可以按数据库写多份，只有匹配当前 `databaseId` 的那份会被装配。",
      "state": {
        "vars": [
          {
            "name": "id",
            "value": "selectById"
          },
          {
            "name": "databaseId",
            "value": "null（没配）"
          }
        ]
      }
    },
    {
      "title": "标签名决定 SqlCommandType",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 66,
      "code": "    String nodeName = context.getNode().getNodeName();\n    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));\n    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;\n    boolean flushCache = context.getBooleanAttribute(\"flushCache\", !isSelect);\n    boolean useCache = context.getBooleanAttribute(\"useCache\", isSelect);\n    boolean resultOrdered = context.getBooleanAttribute(\"resultOrdered\", false);",
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
      "explain": "`<select>` → SELECT，`<insert>` → INSERT。接着三个默认值：`flushCache` 对写语句默认 true、`useCache` 对查询默认 true。**这两个默认值就是一级缓存行为的一半**。",
      "state": {
        "vars": [
          {
            "name": "sqlCommandType",
            "value": "SELECT"
          },
          {
            "name": "flushCache",
            "value": "false"
          },
          {
            "name": "useCache",
            "value": "true"
          }
        ]
      },
      "predict": {
        "question": "一条 <update> 默认会清缓存吗？",
        "options": [
          "会，flushCache 默认 true",
          "不会，要手动写 flushCache",
          "取决于数据库"
        ],
        "answer": 0,
        "reveal": "写语句默认 `flushCache=true`，所以更新之后同一 SqlSession 的查询会重新走库。反过来，<select> 默认不清缓存。"
      }
    },
    {
      "title": "include 必须在解析 SQL 之前展开",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 74,
      "code": "    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);\n    includeParser.applyIncludes(context.getNode());\n\n    String parameterType = context.getStringAttribute(\"parameterType\");",
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
      "explain": "`<include refid=\"...\">` 先把 `<sql>` 片段拼进当前节点，后面 `createSqlSource` 看到的才是完整的 SQL。反过来做的话，动态 SQL 里会残留一个 `<include>` 节点。",
      "log": [
        {
          "k": "sqlFragments",
          "v": "Base_Column_List → id, name, age"
        }
      ]
    },
    {
      "title": "主键策略：useGeneratedKeys 开着就走 Jdbc3KeyGenerator",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 87,
      "code": "    KeyGenerator keyGenerator;\n    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;\n    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);\n    if (configuration.hasKeyGenerator(keyStatementId)) {\n      keyGenerator = configuration.getKeyGenerator(keyStatementId);\n    } else {\n      keyGenerator = context.getBooleanAttribute(\"useGeneratedKeys\",\n          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))\n              ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;\n    }",
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
      "explain": "先看有没有 `<selectKey>`（那会生成一个独立的 SelectKeyGenerator）；否则看 `useGeneratedKeys`：开着且是 INSERT，就用 `Jdbc3KeyGenerator` 把自增主键回填到对象上。",
      "log": [
        {
          "k": "keyGenerator",
          "v": "Jdbc3KeyGenerator"
        },
        {
          "k": "回填字段",
          "v": "id"
        }
      ]
    },
    {
      "title": "关掉之后：NoKeyGenerator",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 87,
      "code": "    KeyGenerator keyGenerator;\n    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;\n    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);\n    if (configuration.hasKeyGenerator(keyStatementId)) {\n      keyGenerator = configuration.getKeyGenerator(keyStatementId);\n    } else {\n      keyGenerator = context.getBooleanAttribute(\"useGeneratedKeys\",\n          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))\n              ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;\n    }",
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
      "explain": "同一个分支，`useGeneratedKeys=false` 时拿到 `NoKeyGenerator`：插入照常，但对象上的 id 还是 null。**很多人以为「插完就有 id」是框架默认，其实要靠这个开关**。",
      "log": [
        {
          "k": "keyGenerator",
          "v": "NoKeyGenerator"
        },
        {
          "k": "回填字段",
          "v": "无"
        }
      ]
    },
    {
      "title": "生成 SqlSource 与结果映射的引用",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 97,
      "code": "    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);\n    StatementType statementType = StatementType\n        .valueOf(context.getStringAttribute(\"statementType\", StatementType.PREPARED.toString()));\n    Integer fetchSize = context.getIntAttribute(\"fetchSize\");\n    Integer timeout = context.getIntAttribute(\"timeout\");\n    String parameterMap = context.getStringAttribute(\"parameterMap\");\n    String resultType = context.getStringAttribute(\"resultType\");\n    Class<?> resultTypeClass = resolveClass(resultType);\n    String resultMap = context.getStringAttribute(\"resultMap\");\n    if (resultTypeClass == null && resultMap == null) {\n      resultTypeClass = MapperAnnotationBuilder.getMethodReturnType(builderAssistant.getCurrentNamespace(), id);",
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
        11
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`langDriver.createSqlSource` 决定了「这张 SQL 是静态的还是带 `<if>` 的动态 SQL」；`resultType` / `resultMap` 二选一，两个都没写时才去猜方法返回类型。",
      "state": {
        "vars": [
          {
            "name": "sqlSource",
            "value": "DynamicSqlSource"
          },
          {
            "name": "statementType",
            "value": "PREPARED"
          },
          {
            "name": "resultType",
            "value": "com.example.User"
          }
        ]
      }
    },
    {
      "title": "最后一步：Builder 组装并入库",
      "file": "org/apache/ibatis/builder/MapperBuilderAssistant.java",
      "line": 209,
      "code": "    id = applyCurrentNamespace(id, false);\n\n    MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)\n        .resource(resource).fetchSize(fetchSize).timeout(timeout).statementType(statementType)\n        .keyGenerator(keyGenerator).keyProperty(keyProperty).keyColumn(keyColumn).databaseId(databaseId).lang(lang)\n        .resultOrdered(resultOrdered).resultSets(resultSets)\n        .resultMaps(getStatementResultMaps(resultMap, resultType, id)).resultSetType(resultSetType)\n        .flushCacheRequired(flushCache).useCache(useCache).cache(currentCache).dirtySelect(dirtySelect);\n\n    ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);\n    if (statementParameterMap != null) {\n      statementBuilder.parameterMap(statementParameterMap);\n    }\n\n    MappedStatement statement = statementBuilder.build();\n    configuration.addMappedStatement(statement);\n    return statement;",
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
        17
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "`applyCurrentNamespace(id, false)` 补上 `com.example.UserMapper.` 前缀，然后用 Builder 把十几个字段一次设完，最后 `configuration.addMappedStatement(statement)`。到这里，一条 XML 语句才真正可执行。",
      "log": [
        {
          "k": "mappedStatements",
          "v": "com.example.UserMapper.selectById"
        },
        {
          "k": "statement.parameterMap",
          "v": "自动生成"
        }
      ],
      "state": {
        "stack": [
          "MapperBuilderAssistant.addMappedStatement(...)"
        ]
      },
      "predict": {
        "question": "addMappedStatement 会把 id 补成什么？",
        "options": [
          "selectById",
          "接口全限定名.方法名",
          "namespace:方法名"
        ],
        "answer": 1,
        "reveal": "`applyCurrentNamespace` 拼上 namespace，所以 XML 的 namespace 要写接口全名。"
      }
    }
  ]
}
```

## 这条线为什么值得读

- 你终于知道 `namespace` 与 `id` 是怎么拼成 `statement id` 的——`IllegalArgumentException: Mapped Statements collection does not contain value for ...` 十有八九是这里拼错。
- `flushCache` / `useCache` 的默认值是**按语句类型**给的，不是全局一个值。
- 主键回填不是自动的：`useGeneratedKeys` 或 `<selectKey>` 二选一。

```code-challenge
{
  "id": "ex-statement",
  "kind": "order",
  "title": "排出 <select> 的装配顺序",
  "lines": [
    "MapperBuilderAssistant.addMappedStatement(...)",
    "XMLMapperBuilder.parse()",
    "XMLStatementBuilder.parseStatementNode()",
    "configurationElement() 解析 cache/resultMap/sql",
    "buildStatementFromContext() 遍历语句"
  ],
  "answer": [
    1,
    3,
    4,
    2,
    0
  ],
  "explain": "先 namespace 下的公共部分，再逐条语句，最后统一由 Assistant 入库。"
}
```

```quiz
{
  "id": "statement-quiz",
  "title": "小测：装配线",
  "questions": [
    {
      "question": "MappedStatement 的 id 最终长什么样？",
      "options": ["selectById", "com.example.UserMapper.selectById", "UserMapper#selectById"],
      "answer": 1,
      "explain": "`applyCurrentNamespace` 把 namespace 拼在前面，所以 Mapper XML 的 namespace 必须写接口全限定名。"
    },
    {
      "question": "IncompleteElementException 在这里代表什么？",
      "options": ["配置写错了", "引用了还没解析到的元素，先记下稍后重试", "数据库连不上"],
      "answer": 1,
      "explain": "它被 catch 后存进 incompleteStatements，等所有资源解析完再重试。"
    },
    {
      "question": "useGeneratedKeys 关掉后，插入还能拿到自增 id 吗？",
      "options": ["能，框架自动补", "不能，要用 selectKey 或手动查", "能，但只限 MySQL"],
      "answer": 1,
      "explain": "关掉时是 NoKeyGenerator；要自增 id 就配 `<selectKey>` 或打开 useGeneratedKeys。"
    }
  ]
}
```

下一篇进入 `binding` 包：接口没有实现类，调用却真的到了 `MapperMethod`——中间是 JDK 动态代理。
