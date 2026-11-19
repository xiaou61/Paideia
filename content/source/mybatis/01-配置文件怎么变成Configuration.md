---
title: 跑起来：配置文件怎么变成 Configuration
category: mybatis-source
order: 1
minutes: 24
level: beginner
status: published
---

版本：MyBatis 3.5.19，git revision ee0d4f4；行号核对日期 2026-11-19。下面所有 `文件:行号` 都指这个版本。

## 先看到的是 BuilderException，不是 TypeException

`mybatis-config.xml` 里照常写 `<typeAliases>`，再把 `XMLConfigBuilder.java:121` 的 `typeAliasesElement(...)` 与 `:131` 的 `mappersElement(...)` 两行对调，用 `SqlSessionFactoryBuilder.build(InputStream)` 启动。只留 message、去掉栈帧，控制台最后是：

```text
### Error building SqlSession.
### The error may exist in UserMapper.xml
### Cause: org.apache.ibatis.builder.BuilderException: Error parsing SQL Mapper Configuration. Cause: org.apache.ibatis.builder.BuilderException: Error parsing Mapper XML. The XML location is 'UserMapper.xml'. Cause: org.apache.ibatis.builder.BuilderException: Error resolving class. Cause: org.apache.ibatis.type.TypeException: Could not resolve type alias 'User'.  Cause: java.lang.ClassNotFoundException: Cannot find class: User
```

这段是我在临时目录里真跑出来的：同一份 XML，加一个同包名、只对调 121 与 131 的 `XMLConfigBuilder` 副本，JDK 25 + `mybatis-3.5.19.jar`。不是凭报错格式猜的。

第一次看到这条链，我把它读成「`TypeException` 是顶层异常」。实际从外到内是：

1. `SqlSessionFactoryBuilder.build(InputStream, String, Properties)`:77-93，catch 在 81-82，把异常包成 `PersistenceException: Error building SqlSession.`。日志第一行的 `### Error building SqlSession.` 就是它。
2. `XMLConfigBuilder.parseConfiguration`:114-135，catch 在 132-133，包成 `BuilderException: Error parsing SQL Mapper Configuration. Cause: ...`。
3. `XMLMapperBuilder.configurationElement`:111-126，catch 在 124-125，包成 `BuilderException: Error parsing Mapper XML. The XML location is ...`。
4. `BaseBuilder.resolveClass`:99-104，catch 在 102-103，包成 `BuilderException: Error resolving class. Cause: ...`。
5. `TypeAliasRegistry.resolveAlias`:113-129，catch 在 127-128，抛 `TypeException: Could not resolve type alias 'User'. ...`。
6. 最内层才是 `Resources.classForName` 抛出的 `ClassNotFoundException: Cannot find class: User`。

所以用户先看到的 `Caused by` 是 `BuilderException`，完整栈顶是 `PersistenceException`；`TypeException` 不在最内层，它下面还有 `ClassNotFoundException`，上面还有三层 `BuilderException` 包装。说「`resolveClass("User")` 抛 `TypeException`」会漏掉这些中间层。

## 顺序写在 Java 里，不在 XML 里

我一开始以为把 `<mappers>` 挪到 `<typeAliases>` 前面就能复现。不是。`parseConfiguration` 是按元素名逐个 `evalNode`，调用顺序在 114-135 行写死；XML 里标签谁先谁后不影响解析顺序。而且 `XMLConfigBuilder.java:92` 构造 `XPathParser` 时传了 `validation=true`，`XPathParser.java:234` 会 `factory.setValidating(validation)`；配置带 DTD 时，乱序还会先被 DTD 校验拦下。

这也是为什么「把 mappers 提前」只能在源码层面对调 121 与 131 两行；改 XML 标签顺序试不出来。

`parseConfiguration` 里真正的方法名与顺序是：

```text
117  propertiesElement
118  settingsAsProperties
119  loadCustomVfsImpl
120  loadCustomLogImpl
121  typeAliasesElement
122  pluginsElement
123  objectFactoryElement
124  objectWrapperFactoryElement
125  reflectorFactoryElement
126  settingsElement
128  environmentsElement
129  databaseIdProviderElement
130  typeHandlersElement
131  mappersElement
```

几处顺序的理由，源码里能直接读出来：

- **properties 最先**：117 行的 `propertiesElement` 把 `<properties>` 子元素、`resource`/`url` 指向的文件、构造器传入的 props 合并，最后在 257-258 行同时塞给 `parser` 和 `configuration`。后面的 `resultType`、`typeAlias`、`environment` 里的 `${...}` 都靠这份 variables。116 行只有一句注释 `issue #117 read properties first`；源码没写当年发生了什么，本文也不替它补历史。
- **settings 拆成两段**：118 行先把 `<settings>` 读成 Properties，119-120 行立刻应用 `vfsImpl` 和 `logImpl`；剩下的 `settingsElement` 放到 126 行。因为 126 行会调用 `resolveClass` 解析 `defaultEnumTypeHandler`、`defaultScriptingLanguage`、`configurationFactory` 等类名（283-293 行），这时 121 行的别名表必须已经建好。
- **别名与类型处理器在 mappers 之前**：121 行 `typeAliasesElement` 注册别名，130 行 `typeHandlersElement` 注册类型处理器。Mapper XML 里的 `resultType`、`parameterType`、`typeHandler` 都要用这两张表。
- **mappers 最后**：131 行 `mappersElement` 一执行就会读 mapper resource，构造 `XMLMapperBuilder` 并 `parse`（402-405 行）。别名没就位，Mapper 解析在 `XMLStatementBuilder.java:105` 的 `resolveClass(resultType)` 处就失败。

把 121 和 131 对调后，`typeAliasRegistry` 里只有内置别名（`string`、`int` 这些，`TypeAliasRegistry` 构造器 42-109 行注册），没有 `User`。`resolveAlias` 先查表，查不到再用 `Resources.classForName("User")`，抛 `ClassNotFoundException`，最后变成开头那条链。

## properties 和别名不是无关

我原来写的是「properties 与别名无关」，这句话过头了。`<typeAlias type="${user.class}">` 的 `type` 属性也会做 `${}` 替换（`XNode` 读属性时走 `PropertyParser.parse`），所以别名注册可以依赖 properties。

准确的说法是：把 mappers 提前不影响 properties 的读取，因为 `propertiesElement` 仍然在 117 行第一个跑；受影响的只有 `typeAliases` 以及依赖别名的后续步骤。

## 连库不一定等第一条 SQL

`environmentsElement` 在 128 行执行，309-312 行创建 `TransactionFactory`、`DataSource`、`Environment`。标准 DataSourceFactory 下到这里只是建对象，不打开连接——「连接要等第一次执行 SQL」只在这个范围成立。

但只要配了 `<databaseIdProvider>` 且 environment 不为空，129 行就会在启动阶段连库：`databaseIdProviderElement` 的 331-334 行拿到 environment 的 DataSource，调用 `databaseIdProvider.getDatabaseId(...)`；`VendorDatabaseIdProvider` 的 `getDatabaseId`:40-49 走到 `getDatabaseProductName`:65-69，第 66 行 `dataSource.getConnection()` 在 try-with-resources 里打开连接并读元数据。配了它，`build()` 阶段就会连库，连不上直接构建失败。

## parse() 的闸门

`parse()` 在 105-112 行先看 `parsed`。第二次调用在 106-108 行直接抛：

```java
throw new BuilderException("Each XMLConfigBuilder can only be used once.");
```

第二次调用在写任何 `MappedStatement` 之前就抛。不是「重新解析并合并」，也不是「第二次调用会把映射表写两遍」；没有这道闸门，才会轮到「写两遍」这个问题。（真去掉闸门后哪些注册会重复，取决于 `loadedResources` 等去重，本文没有逐项验证。）

```source-walk
{
  "id": "config-parse-order",
  "title": "XMLConfigBuilder 的解析顺序",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "label": "XMLConfigBuilder"
    },
    {
      "path": "org/apache/ibatis/session/SqlSessionFactoryBuilder.java",
      "label": "SqlSessionFactoryBuilder"
    }
  ],
  "controls": [
    {
      "id": "order",
      "type": "select",
      "label": "解析顺序",
      "default": "standard",
      "options": [
        {
          "value": "standard",
          "label": "标准顺序（先别名后 mappers）"
        },
        {
          "value": "mappersFirst",
          "label": "把 mappers 提到别名前"
        }
      ]
    }
  ],
  "steps": [
    {
      "title": "入口：build 创建 XMLConfigBuilder 并 parse",
      "file": "org/apache/ibatis/session/SqlSessionFactoryBuilder.java",
      "line": 77,
      "lines": 17,
      "explain": "`build(InputStream)` 在 65-67 行转到这个重载。79 行 `new XMLConfigBuilder(inputStream, environment, properties)`，80 行 `parser.parse()`；81-82 行 catch 后包成 `PersistenceException: Error building SqlSession.`，85-88 行关闭输入流。",
      "state": {
        "stack": [
          "SqlSessionFactoryBuilder.build(InputStream)",
          "new XMLConfigBuilder(...)",
          "parser.parse()"
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "parse 只允许跑一次",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 105,
      "lines": 8,
      "explain": "`parsed` 是一次性闸门：第二次调用在 106-108 行直接抛 `BuilderException`，精确消息是「Each XMLConfigBuilder can only be used once.」。第二次调用在 `parseConfiguration` 之前就抛，不会写任何新映射；没有这道闸门，才会轮到写两遍的问题。",
      "state": {
        "vars": [
          [
            "parsed",
            "false → true"
          ],
          [
            "root",
            "/configuration 节点"
          ]
        ]
      },
      "predict": {
        "question": "同一个 XMLConfigBuilder 第二次 parse() 会？",
        "options": [
          "重新解析并合并",
          "抛 BuilderException",
          "静默忽略"
        ],
        "answer": 1,
        "reveal": "`parsed` 闸门直接抛异常，消息是 Each XMLConfigBuilder can only be used once."
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "properties 先读，settings 先读后应用",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 114,
      "lines": 7,
      "explain": "116 行注释只写 `issue #117 read properties first`；117 行 `propertiesElement` 把变量同时交给 parser 与 configuration（257-258 行）。118 行 `settingsAsProperties` 先把 settings 读成 Properties，119-120 行立刻应用 vfsImpl 与 logImpl，其余的 settings 留到 126 行。",
      "state": {
        "stack": [
          "XMLConfigBuilder.parseConfiguration(root)"
        ],
        "tables": [
          [
            "variables",
            "properties 的键值 + 构造器传入的 props"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "别名、工厂、settings 集中在 121-126",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 121,
      "lines": 6,
      "explain": "121 行注册别名，122 行插件，123-125 行三个工厂，126 行才统一应用 settings。`settingsElement` 会 `resolveClass` 解析 `defaultEnumTypeHandler`、`defaultScriptingLanguage` 等类名（283-293 行），所以别名必须先就位；typeHandlers 则在 130 行。",
      "state": {
        "tables": [
          [
            "typeAliasRegistry",
            "内置别名 + user → com.example.User"
          ],
          [
            "settings",
            "vfsImpl / logImpl 已生效，其余在 126 行"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "环境：只建 DataSource，不连库",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 128,
      "lines": 2,
      "explain": "`environmentsElement` 选出默认环境，309-312 行创建 TransactionFactory、DataSource、Environment。标准 DataSourceFactory 下仅这一步不打开连接；连接要等执行 SQL 时才取。",
      "state": {
        "tables": [
          [
            "environment",
            "development"
          ],
          [
            "dataSource",
            "PooledDataSource（还没 getConnection）"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "databaseIdProvider 会在启动阶段连库",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 129,
      "lines": 1,
      "explain": "只要配了 `<databaseIdProvider>` 且 environment 不为空，331-334 行就会拿 DataSource 调 `getDatabaseId(...)`；`VendorDatabaseIdProvider` 在 66 行 `dataSource.getConnection()` 读数据库产品名。配了它，build 阶段就会连库。",
      "state": {
        "tables": [
          [
            "databaseIdProvider",
            "配了就在 build 阶段 getConnection()"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "typeHandlers 仍在 mappers 之前",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 130,
      "lines": 1,
      "explain": "`typeHandlersElement` 注册 Java 类型与 JDBC 类型之间的处理器。Mapper XML 里的 `typeHandler=...` 解析时要用这张表，所以它必须排在 131 行之前。",
      "state": {
        "tables": [
          [
            "typeHandlerRegistry",
            "LocalDateTime → LocalDateTimeTypeHandler"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "标准顺序：mappers 最后跑",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 131,
      "lines": 1,
      "explain": "`mappersElement` 是最后一行。它一执行就去读 mapper resource，Mapper 里的 `resultType=User` 依赖 121 行已经注册好的别名。此时 typeAliasRegistry 里同时有内置别名与自定义 User。",
      "when": {
        "order": "standard"
      },
      "predict": {
        "question": "把 mappers 提到 typeAliases 前面，最先坏的是哪一步？",
        "options": [
          "properties 读不到",
          "Mapper 里的别名解析失败",
          "数据库连不上"
        ],
        "answer": 1,
        "reveal": "`resolveClass` 找不到 User，日志里最外层是 BuilderException，TypeException 是 cause；properties 已经读过，数据库也不会因为这一步连接。"
      },
      "state": {
        "tables": [
          [
            "typeAliasRegistry",
            "内置别名 + user → com.example.User"
          ],
          [
            "mappers",
            "可以开始解析"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "mappersFirst 分支：别名表还是空的",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 131,
      "lines": 1,
      "explain": "把 121 与 131 对调后，这一行先执行。`typeAliasRegistry` 里只有内置别名，`resultType=User` 在 XMLStatementBuilder.java:105 解析失败。这条分支不是真实源码，是给你做对照的。",
      "when": {
        "order": "mappersFirst"
      },
      "state": {
        "tables": [
          [
            "typeAliasRegistry",
            "只有内置别名"
          ],
          [
            "mappers",
            "解析到一半失败"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    }
  ],
  "map": {
    "title": "MyBatis 主干",
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
        "sub": "反射/类型/缓存/插件",
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
    ]
  }
}
```

```code-challenge
{
  "id": "ex-config",
  "kind": "order",
  "title": "排出 XMLConfigBuilder 的解析顺序",
  "lines": [
    "mappersElement(root)",
    "propertiesElement(root)",
    "typeHandlersElement(root)",
    "settingsElement(settings)",
    "typeAliasesElement(root)"
  ],
  "answer": [
    1,
    4,
    3,
    2,
    0
  ],
  "explain": "properties 最先（占位符）；typeAliases 在 settingsElement 之前，因为 settingsElement 会用 resolveClass 解析类名；typeHandlers 在 mappers 之前，mappers 最后。"
}
```

```quiz
{
  "id": "config-quiz",
  "title": "小测：解析顺序",
  "questions": [
    {
      "question": "为什么 properties 必须最先解析？",
      "options": [
        "因为它最小",
        "因为后面的配置里可能用 ${} 引用它",
        "因为 MyBatis 的作者习惯"
      ],
      "answer": 1,
      "explain": "`${}` 占位符在 typeAliases、environments、mappers 等处都会被替换；propertiesElement 在 257-258 行把变量交给 parser 和 configuration。"
    },
    {
      "question": "一个 XMLConfigBuilder 能 parse() 两次吗？",
      "options": [
        "能，会合并",
        "不能，第二次抛 BuilderException",
        "能，但只生效第二次"
      ],
      "answer": 1,
      "explain": "`parsed` 闸门在 106-108 行抛异常，消息是 Each XMLConfigBuilder can only be used once.，此时还没有写任何映射。"
    },
    {
      "question": "mappers 放最后，直接原因是？",
      "options": [
        "需要别名与类型处理器先注册",
        "因为文件最大",
        "因为要等数据库连接"
      ],
      "answer": 0,
      "explain": "Mapper XML 里的 resultType、parameterType、typeHandler 都要用前面注册好的别名表与类型处理器表。"
    }
  ]
}
```

## 还差的

- 启动日志来自临时目录里的最小 harness：同一份 XML + 一个把 121/131 对调的 `XMLConfigBuilder` 副本，不是 Spring Boot 全量启动。顶部 `PersistenceException` 和 `### Error building SqlSession.` 是真的，但你的项目如果还有 Spring/连接池包装，外层可能更多。
- `BaseBuilder.java`、`TypeAliasRegistry.java`、`VendorDatabaseIdProvider.java` 不在仓库的精选源码快照里，这三处行号按同一 commit `ee0d4f4` 的上游原文核对；没有在快照里再验一遍。
- 没有用真实数据库验证 `<databaseIdProvider>` 的连接时机，只按 `VendorDatabaseIdProvider.java:66` 的源码判断。
- 没有逐项验证去掉 `parsed` 闸门后哪些注册会重复；`loadedResources` 会跳过已加载的 mapper resource，但插件、typeHandler 等是否重复没有跑。
- 没有验证 DTD 乱序时各解析器的具体报错文本；只确认 `XPathParser.java:234` 启用了 validation。