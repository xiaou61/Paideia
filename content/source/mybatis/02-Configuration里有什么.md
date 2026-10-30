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

```source-walk
{
  "id": "configuration-fields",
  "title": "Configuration 里真正重要的三类字段",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/session/Configuration.java", "label": "Configuration" }
  ],
  "controls": [
    {
      "id": "executor",
      "type": "select",
      "label": "默认执行器",
      "default": "SIMPLE",
      "options": [
        { "value": "SIMPLE", "label": "SIMPLE" },
        { "value": "REUSE", "label": "REUSE" },
        { "value": "BATCH", "label": "BATCH" }
      ]
    }
  ],
  "steps": [
    {
      "title": "第一组：开关",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 105,
      "lines": 7,
      "explain": "`environment` 之外是一排布尔开关。它们不是「全局配置文档」里的摆设——每一个都会被某个执行路径读到，比如 `mapUnderscoreToCamelCase` 直接决定结果集映射时列名 `user_name` 能不能对上属性 `userName`。",
      "state": {
        "vars": [["mapUnderscoreToCamelCase", "false（默认）"], ["cacheEnabled", "true"], ["useActualParamName", "true"]]
      }
    },
    {
      "title": "第二组：默认值决定「不写会怎样」",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 125,
      "lines": 9,
      "explain": "`localCacheScope=SESSION`、`defaultExecutorType=SIMPLE`、`autoMappingBehavior=PARTIAL`——这三个默认值几乎解释了初学者遇到的一半「为什么和我以为的不一样」。",
      "state": {
        "vars": [["localCacheScope", "SESSION"], ["defaultExecutorType", "SIMPLE"], ["autoMappingBehavior", "PARTIAL"]]
      }
    },
    {
      "title": "执行器换一种，openSession 之后的对象就不同",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 132,
      "lines": 1,
      "explain": "`defaultExecutorType` 只是被 `DefaultSqlSessionFactory.openSession` 读一次。选 SIMPLE 就是每次新建一个 `SimpleExecutor`，没有花活。",
      "when": { "executor": "SIMPLE" },
      "state": {
        "tables": [["openSession → executor", "SimpleExecutor"]]
      }
    },
    {
      "title": "REUSE：复用 Statement",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 132,
      "lines": 1,
      "explain": "同一个 SqlSession 里，相同 SQL 的 `PreparedStatement` 会被复用。省的是 prepare 的开销，代价是 Statement 一直占着连接资源。",
      "when": { "executor": "REUSE" },
      "state": {
        "tables": [["openSession → executor", "ReuseExecutor"], ["statement 缓存", "按 SQL 文本复用"]]
      }
    },
    {
      "title": "BATCH：攒够了一次发",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 132,
      "lines": 1,
      "explain": "`BatchExecutor` 把 update 攒起来，直到 `flushStatements()` 或提交。批量插入用它，但**查不到未提交的数据**，写一读一的时候别开。",
      "when": { "executor": "BATCH" },
      "state": {
        "tables": [["openSession → executor", "BatchExecutor"], ["待执行", "攒到 flush 才发"]]
      }
    },
    {
      "title": "第三组：四个注册表",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 152,
      "lines": 3,
      "explain": "`mapperRegistry` 记「哪个接口有哪些方法」；`interceptorChain` 是插件链；`typeHandlerRegistry` 负责 Java 类型 ↔ JDBC 类型。它们都是**启动期填满、运行期只读**。",
      "state": {
        "tables": [["mapperRegistry", "com.example.UserMapper"], ["interceptorChain", "0 个（没配插件）"], ["typeHandlerRegistry", "内置 30+ 个"]]
      }
    },
    {
      "title": "映射表：MappedStatement 的 StrictMap",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 158,
      "lines": 4,
      "explain": "所有解析出来的语句都进 `mappedStatements`，键是 `namespace.id`。它用 `StrictMap`：重复键不是覆盖，而是抛异常并把两个来源文件都打出来——所以「两个 Mapper 写了同一条 id」会在启动时报错，不会静默生效。",
      "predict": {
        "question": "两个 XML 里写了同一个 namespace + id，会发生什么？",
        "options": ["后一个覆盖前一个", "启动时报重复 id 异常", "随机用一个"],
        "answer": 1,
        "reveal": "`StrictMap` 的冲突消息会指出两个资源文件。这也解释了为什么 Mapper 的 namespace 必须写全限定接口名。"
      },
      "state": {
        "tables": [["mappedStatements", "com.example.UserMapper.selectById"], ["resultMaps", "com.example.UserMapper.userMap"]]
      }
    },
    {
      "title": "addMapper 只是转发",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 942,
      "lines": 3,
      "explain": "Configuration 自己不维护接口表，直接转给 `MapperRegistry`。下一关会看 Registry 里到底存了什么。",
      "state": {
        "stack": ["Configuration.addMapper(type)", "MapperRegistry.addMapper(type)"]
      }
    }
  ]
}
```

## 为什么这些默认值是「坑」

- `localCacheScope=SESSION`：同一个 SqlSession 里两次相同查询第二次不走库。跨请求没影响，但在**长事务**里会读到旧值。
- `autoMappingBehavior=PARTIAL`：没有嵌套结果映射时才自动映射。写了 `<association>` 之后，不显式配 `resultMap` 的列会悄悄不映射。
- `cacheEnabled=true`：一级缓存默认就开，关它要显式 `localCacheScope=STATEMENT` 或 `flushCache`。

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
