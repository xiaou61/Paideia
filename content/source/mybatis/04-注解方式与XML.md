---
title: 注解方式：@Select 怎么和 XML 共存
category: mybatis-source
order: 4
minutes: 25
level: intermediate
status: published
---

## 这一关要读什么

`@Select("select * from user where id = #{id}")` 写在一个没有实现类的接口上，运行期却能执行。
这一关看两件事：**注解是在哪一步被翻译成 MappedStatement 的**，以及**同一个方法同时有注解和 XML 时会怎样**。

```code-flow
{
  "id": "annotation-mapper",
  "title": "注解 Mapper 的装配",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/binding/MapperRegistry.java",
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
      "title": "注册先放工厂，再解析",
      "file": "org/apache/ibatis/binding/MapperRegistry.java",
      "line": 60,
      "code": "  public <T> void addMapper(Class<T> type) {\n    if (type.isInterface()) {\n      if (hasMapper(type)) {\n        throw new BindingException(\"Type \" + type + \" is already known to the MapperRegistry.\");\n      }\n      boolean loadCompleted = false;\n      try {\n        knownMappers.put(type, new MapperProxyFactory<>(type));\n        // It's important that the type is added before the parser is run\n        // otherwise the binding may automatically be attempted by the\n        // mapper parser. If the type is already known, it won't try.\n        MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);\n        parser.parse();\n        loadCompleted = true;\n      } finally {\n        if (!loadCompleted) {\n          knownMappers.remove(type);\n        }\n      }\n    }",
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
        "proxy"
      ],
      "edges": [],
      "explain": "`knownMappers.put(type, new MapperProxyFactory<>(type))` 发生在解析**之前**。注释写得很清楚：先把类型登记上，解析器才不会又去 bind 一次。解析失败时 finally 把它移除，避免留下半个 Mapper。",
      "log": [
        {
          "k": "knownMappers",
          "v": "UserMapper → MapperProxyFactory"
        },
        {
          "k": "解析状态",
          "v": "进行中"
        }
      ]
    },
    {
      "title": "parse 先找同名 XML",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 114,
      "code": "  public void parse() {\n    String resource = type.toString();\n    if (!configuration.isResourceLoaded(resource)) {\n      loadXmlResource();\n      configuration.addLoadedResource(resource);\n      assistant.setCurrentNamespace(type.getName());\n      parseCache();\n      parseCacheRef();\n      for (Method method : type.getMethods()) {\n        if (!canHaveStatement(method)) {\n          continue;\n        }\n        if (getAnnotationWrapper(method, false, Select.class, SelectProvider.class).isPresent()\n            && method.getAnnotation(ResultMap.class) == null) {\n          parseResultMap(method);\n        }\n        try {\n          parseStatement(method);\n        } catch (IncompleteElementException e) {\n          configuration.addIncompleteMethod(new MethodResolver(this, method));\n        }\n      }\n    }\n    configuration.parsePendingMethods(false);\n  }\n\n  private static boolean canHaveStatement(Method method) {",
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
        "base"
      ],
      "edges": [],
      "explain": "`loadXmlResource()` 排在最前：`com.example.UserMapper` 会去找 `com/example/UserMapper.xml`。也就是说**注解与 XML 可以混用**，XML 先装配，注解后装配。",
      "state": {
        "vars": [
          {
            "name": "resource",
            "value": "com/example/UserMapper"
          },
          {
            "name": "xml",
            "value": "UserMapper.xml（可选）"
          }
        ]
      },
      "predict": {
        "question": "接口旁边没有同名 XML 会怎样？",
        "options": [
          "启动失败",
          "忽略，继续用注解",
          "抛异常"
        ],
        "answer": 1,
        "reveal": "`inputStream == null` 时静默跳过，注解-only 项目很常见。"
      }
    },
    {
      "title": "同名 XML 是怎么被拉进来的",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 145,
      "code": "  private void loadXmlResource() {\n    // Spring may not know the real resource name so we check a flag\n    // to prevent loading again a resource twice\n    // this flag is set at XMLMapperBuilder#bindMapperForNamespace\n    if (!configuration.isResourceLoaded(\"namespace:\" + type.getName())) {\n      String xmlResource = type.getName().replace('.', '/') + \".xml\";\n      // #1347\n      InputStream inputStream = type.getResourceAsStream(\"/\" + xmlResource);\n      if (inputStream == null) {\n        // Search XML mapper that is not in the module but in the classpath.\n        try {\n          inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);\n        } catch (IOException e2) {\n          // ignore, resource is not required\n        }\n      }\n      if (inputStream != null) {\n        XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource,\n            configuration.getSqlFragments(), type.getName());\n        xmlParser.parse();\n      }",
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
        "base"
      ],
      "edges": [],
      "explain": "资源名是接口全限定名换斜杠加 `.xml`。找不到不算错（注解-only 的项目很常见）；找到就交给 `XMLMapperBuilder` 正常解析，走的还是上一关那条装配线。",
      "log": [
        {
          "k": "classpath",
          "v": "com/example/UserMapper.xml"
        },
        {
          "k": "xmlParser",
          "v": "XMLMapperBuilder"
        }
      ]
    },
    {
      "title": "每个方法一条语句",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 282,
      "code": "  void parseStatement(Method method) {\n    final Class<?> parameterTypeClass = getParameterType(method);\n    final LanguageDriver languageDriver = getLanguageDriver(method);\n\n    getAnnotationWrapper(method, true, statementAnnotationTypes).ifPresent(statementAnnotation -> {\n      final SqlSource sqlSource = buildSqlSource(statementAnnotation.getAnnotation(), parameterTypeClass,\n          languageDriver, method);\n      final SqlCommandType sqlCommandType = statementAnnotation.getSqlCommandType();\n      final Options options = getAnnotationWrapper(method, false, Options.class).map(x -> (Options) x.getAnnotation())\n          .orElse(null);",
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
        "base"
      ],
      "edges": [],
      "explain": "`mappedStatementId = type.getName() + \".\" + method.getName()\"`——和 XML 的 `namespace.id` 是同一个规则。bridge 方法与 default 方法直接跳过（`canHaveStatement`）。",
      "state": {
        "vars": [
          {
            "name": "mappedStatementId",
            "value": "com.example.UserMapper.selectById"
          },
          {
            "name": "sqlCommandType",
            "value": "SELECT"
          }
        ]
      }
    },
    {
      "title": "主键策略：@SelectKey > @Options > 全局",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 293,
      "code": "      final KeyGenerator keyGenerator;\n      String keyProperty = null;\n      String keyColumn = null;\n      if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {\n        // first check for SelectKey annotation - that overrides everything else\n        SelectKey selectKey = getAnnotationWrapper(method, false, SelectKey.class)\n            .map(x -> (SelectKey) x.getAnnotation()).orElse(null);\n        if (selectKey != null) {\n          keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method),\n              languageDriver);\n          keyProperty = selectKey.keyProperty();\n        } else if (options == null) {\n          keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;\n        } else {\n          keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;\n          keyProperty = options.keyProperty();\n          keyColumn = options.keyColumn();\n        }\n      } else {\n        keyGenerator = NoKeyGenerator.INSTANCE;\n      }\n\n      Integer fetchSize = null;\n      Integer timeout = null;",
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
        "base"
      ],
      "edges": [],
      "explain": "INSERT/UPDATE 才看主键：先找 `@SelectKey`，再找 `@Options`，都没有才用全局的 `useGeneratedKeys`。和 XML 那条分支（上一关的第 87 行）是同一套规则的两种写法。",
      "log": [
        {
          "k": "keyGenerator",
          "v": "Jdbc3KeyGenerator"
        },
        {
          "k": "keyProperty",
          "v": "id"
        }
      ],
      "predict": {
        "question": "一个 @Insert 方法，全局 useGeneratedKeys=false，但方法上写了 @Options(useGeneratedKeys=true)，会回填 id 吗？",
        "options": [
          "会，方法级优先",
          "不会，全局说了算",
          "两者冲突直接报错"
        ],
        "answer": 0,
        "reveal": "@Options 是方法级配置，优先于全局。这也是为什么同一个项目里可以有的表回填、有的不回填。"
      }
    },
    {
      "title": "注解文本怎么变成 SqlSource",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 573,
      "code": "  private SqlSource buildSqlSource(Annotation annotation, Class<?> parameterType, LanguageDriver languageDriver,\n      Method method) {\n    if (annotation instanceof Select) {\n      return buildSqlSourceFromStrings(((Select) annotation).value(), parameterType, languageDriver);\n    }\n    if (annotation instanceof Update) {\n      return buildSqlSourceFromStrings(((Update) annotation).value(), parameterType, languageDriver);\n    } else if (annotation instanceof Insert) {\n      return buildSqlSourceFromStrings(((Insert) annotation).value(), parameterType, languageDriver);\n    } else if (annotation instanceof Delete) {\n      return buildSqlSourceFromStrings(((Delete) annotation).value(), parameterType, languageDriver);\n    } else if (annotation instanceof SelectKey) {\n      return buildSqlSourceFromStrings(((SelectKey) annotation).statement(), parameterType, languageDriver);\n    }",
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
        14
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "`@Select` / `@Insert` 的 `value()` 是 String[]，拼起来交给 `LanguageDriver.createSqlSource`。注意**这里也能用动态 SQL**：`<script>` 包一段 XML，注解里就能写 `<if>`。",
      "state": {
        "vars": [
          {
            "name": "annotation",
            "value": "@Select"
          },
          {
            "name": "sql",
            "value": "select * from user where id = #{id}"
          }
        ]
      }
    },
    {
      "title": "最后和 XML 走同一个入口",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 347,
      "code": "      }\n\n      assistant.addMappedStatement(mappedStatementId, sqlSource, statementType, sqlCommandType, fetchSize, timeout,\n          // ParameterMapID\n          null, parameterTypeClass, resultMapId, getReturnType(method, type), resultSetType, flushCache, useCache,\n          // TODO gcode issue #577\n          false, keyGenerator, keyProperty, keyColumn, statementAnnotation.getDatabaseId(), languageDriver,\n          // ResultSets",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "`assistant.addMappedStatement(...)` 与 XML 完全同一条路。所以注解与 XML 的差别只在**从哪读文本**，装配规则一份。",
      "log": [
        {
          "k": "mappedStatements",
          "v": "com.example.UserMapper.selectById"
        }
      ]
    },
    {
      "title": "注解写的那条：来自 parseStatement",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 282,
      "code": "  void parseStatement(Method method) {",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "这条走的是「遍历接口方法 → 读注解 → 建语句」。",
      "log": [
        {
          "k": "来源",
          "v": "@Select 注解"
        }
      ]
    },
    {
      "title": "XML 写的那条：来自 XMLMapperBuilder",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 152,
      "code": "      InputStream inputStream = type.getResourceAsStream(\"/\" + xmlResource);",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "这条走的是「同名 XML → XMLMapperBuilder」。两种写法可以同时存在于一个 Mapper，但**同一个 id 只能有一个来源**：重复会在 StrictMap 处抛异常。",
      "log": [
        {
          "k": "来源",
          "v": "UserMapper.xml"
        },
        {
          "k": "同 id 重复",
          "v": "启动失败"
        }
      ]
    }
  ]
}
```

## 什么时候用哪个

- **注解**：语句短、无动态片段。优点是离方法近；缺点是复杂 SQL 会变成一坨字符串。
- **XML**：语句长、动态片段多、要复用 `<sql>`。缺点是文件和接口分离。
- **混用**：常见于老项目——主查询在 XML，简单的 count/delete 用注解。知道它们走同一条装配线，就不会被"哪个优先级高"绕住。

```code-challenge
{
  "id": "ex-annotation",
  "kind": "fill",
  "title": "补语句 id 的组成",
  "template": "注解 Mapper 的语句 id 由 {{0}} + \".\" + {{1}} 组成。",
  "options": [
    [
      "接口全限定名",
      "XML 文件名"
    ],
    [
      "方法名",
      "参数名"
    ]
  ],
  "answers": [
    0,
    0
  ],
  "explain": "与 XML 的 namespace.id 是同一个规则。"
}
```

```quiz
{
  "id": "annotation-quiz",
  "title": "小测：注解 Mapper",
  "questions": [
    {
      "question": "同一个 Mapper 接口，注解和 XML 都定义了 selectById，会怎样？",
      "options": ["注解覆盖 XML", "XML 覆盖注解", "启动时报重复 id"],
      "answer": 2,
      "explain": "两者最终都进 `mappedStatements` 的 StrictMap，重复键直接抛异常，并打印两个来源。"
    },
    {
      "question": "MapperRegistry 为什么先把 MapperProxyFactory 放进 knownMappers，再解析？",
      "options": ["为了快", "避免解析过程中又被自动 bind 一次", "为了支持热加载"],
      "answer": 1,
      "explain": "源码注释写明了：类型已登记，解析器就不会再尝试绑定。失败时 finally 里移除。"
    },
    {
      "question": "注解里能写动态 SQL 吗？",
      "options": ["不能", "能，用 <script> 包起来", "只能写 <if> 不能写 <foreach>"],
      "answer": 1,
      "explain": "`createSqlSource` 走的是同一个 XML 语言驱动，`<script>` 里可以写全部动态标签。"
    }
  ]
}
```

下一关：动态 SQL 不是字符串拼接，是一棵 `SqlNode` 树。
