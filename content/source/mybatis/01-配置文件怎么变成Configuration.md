---
title: 跑起来：配置文件怎么变成 Configuration
category: mybatis-source
order: 1
minutes: 25
level: beginner
status: published
---

## 这一关要读什么

`mybatis-config.xml` 里有 properties、settings、typeAliases、plugins、environments、mappers。
`XMLConfigBuilder` 把它们读进一个 `Configuration` 对象。

这一关只看两件事：**入口怎么防止重复解析**，以及**那一长串解析为什么必须是这个顺序**。

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
    { "path": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java", "label": "XMLConfigBuilder" },
    { "path": "org/apache/ibatis/session/SqlSessionFactoryBuilder.java", "label": "SqlSessionFactoryBuilder" }
  ],
  "controls": [
    {
      "id": "order",
      "type": "select",
      "label": "解析顺序",
      "default": "standard",
      "options": [
        { "value": "standard", "label": "标准顺序（先别名后 mappers）" },
        { "value": "mappersFirst", "label": "先把 mappers 提到最前" }
      ]
    }
  ],
  "steps": [
    {
      "title": "parse 只允许跑一次",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 105,
      "lines": 8,
      "explain": "`parsed` 是个一次性闸门：第二次调用直接抛 `BuilderException`。Configuration 一构建完就当只读用，重复解析会让映射表被写两遍。",
      "state": {
        "vars": [["parsed", "false → true"], ["root", "/configuration 节点"]]
      }
    },
    {
      "title": "properties 必须先读",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 114,
      "lines": 5,
      "explain": "`propertiesElement` 排在最前，因为后面所有 `${...}` 占位符都要靠它。源码里那句 `issue #117` 就是当年顺序写反留下的注释。",
      "state": {
        "stack": ["XMLConfigBuilder.parseConfiguration(root)"],
        "tables": [["variables", "从 <properties> 读入的键值"]]
      }
    },
    {
      "title": "settings 先解析成 Properties，再统一生效",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 118,
      "lines": 3,
      "explain": "先 `settingsAsProperties` 把 `<settings>` 读成键值，再 `loadCustomVfsImpl` / `loadCustomLogImpl`——因为 vfs 与日志实现要先于其它对象创建。真正写进 Configuration 是在第 126 行的 `settingsElement`。",
      "state": {
        "vars": [["settings", "mapUnderscoreToCamelCase → true 之类"]]
      }
    },
    {
      "title": "别名与类型处理器：mappers 之前必须就位",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 121,
      "lines": 10,
      "explain": "typeAliases、plugins、objectFactory、reflectorFactory、typeHandlers 全在这段。它们决定「一个类名怎么变成 Class」「一个 Java 类型怎么变成 JDBC 参数」，而 mappers 解析时就要用。",
      "state": {
        "tables": [["typeAliasRegistry", "user → com.example.User"], ["typeHandlerRegistry", "LocalDateTime → LocalDateTimeTypeHandler"]]
      }
    },
    {
      "title": "mappers 永远最后",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 131,
      "lines": 1,
      "explain": "`mappersElement` 是最后一行。它一执行就会去解析每个 Mapper XML，而 Mapper 里 `resultType=\"User\"` 这种写法依赖别名表已经建好。",
      "predict": {
        "question": "把 mappers 提到 typeAliases 前面，最先坏的是哪一步？",
        "options": ["properties 读不到", "Mapper 里的别名解析失败", "数据库连不上"],
        "answer": 1,
        "reveal": "`resolveClass(\"User\")` 找不到别名会抛 `TypeException`，构建直接失败。properties 与别名无关，数据库要等 openSession 才连。"
      }
    },
    {
      "title": "如果先解析 mappers：别名表还是空的",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 121,
      "lines": 1,
      "explain": "这条分支在真实代码里不存在，是给你做对照的：把 mappers 提到这里，`typeAliasRegistry` 里只有内置别名（`string`、`int` 这些），自定义的 `User` 找不到，Mapper 解析在 `resolveClass` 处失败。",
      "when": { "order": "mappersFirst" },
      "state": {
        "tables": [["typeAliasRegistry", "只有内置别名"], ["mappers", "解析到一半失败"]]
      }
    },
    {
      "title": "标准顺序下，同一处已经有全部别名",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 121,
      "lines": 1,
      "explain": "同样一行，在标准顺序下执行到这里时，`typeAliasesElement` 已经跑过，`User` 能解析成 Class。**行号没变，状态变了**——这就是顺序的意义。",
      "when": { "order": "standard" },
      "state": {
        "tables": [["typeAliasRegistry", "内置别名 + user → com.example.User"], ["mappers", "可以开始解析"]]
      }
    },
    {
      "title": "环境与数据源：真正连库的配置",
      "file": "org/apache/ibatis/builder/xml/XMLConfigBuilder.java",
      "line": 128,
      "lines": 1,
      "explain": "`environmentsElement` 选出默认环境，把 `TransactionFactory` 与 `DataSource` 装进 `Environment`。注意：这一步只是**建数据源对象**，还没连库——连接要等第一次执行 SQL。",
      "state": {
        "tables": [["environment", "development"], ["transactionFactory", "JdbcTransactionFactory"], ["dataSource", "PooledDataSource（未连）"]]
      }
    }
  ]
}
```

## 两个容易记错的点

1. **Configuration 是「构建期对象」**：`parse()` 完成后它就被当成只读。运行期再往里加 Mapper 不是常规用法。
2. **报错信息会包一层**：`parseConfiguration` 的 catch 把任何异常包成 `BuilderException("Error parsing SQL Mapper Configuration...")`，
   所以你看到的顶层错误往往是这句话，真正原因在 `cause` 里。

```quiz
{
  "id": "config-quiz",
  "title": "小测：解析顺序",
  "questions": [
    {
      "question": "为什么 properties 必须最先解析？",
      "options": ["因为它最小", "因为后面的配置里可能用 ${} 引用它", "因为 MyBatis 的作者习惯"],
      "answer": 1,
      "explain": "`${}` 占位符在 typeAliases、environments 等处都会被替换，properties 没就位时替换会失败。"
    },
    {
      "question": "一个 XMLConfigBuilder 能 parse() 两次吗？",
      "options": ["能，会合并", "不能，第二次抛异常", "能，但只生效第二次"],
      "answer": 1,
      "explain": "`parsed` 闸门在第二次调用时抛 `BuilderException`。"
    },
    {
      "question": "mappers 放最后，直接原因是？",
      "options": ["需要别名与类型处理器先注册", "因为文件最大", "因为要等数据库连接"],
      "answer": 0,
      "explain": "Mapper XML 里的 `resultType` / `parameterType` / `typeHandler` 都要用前面注册好的东西。"
    }
  ]
}
```

下一关不急着读 Mapper，先打开 `Configuration`：那 40 多个字段里，真正会改变一次查询行为的只有几个。
