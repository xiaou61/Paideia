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

```source-walk
{
  "id": "mapper-xml-to-statement",
  "title": "从 <select> 到 MappedStatement",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java", "label": "XMLMapperBuilder" },
    { "path": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java", "label": "XMLStatementBuilder" },
    { "path": "org/apache/ibatis/builder/MapperBuilderAssistant.java", "label": "MapperBuilderAssistant" }
  ],
  "controls": [
    { "id": "generatedKeys", "type": "toggle", "label": "useGeneratedKeys", "default": true }
  ],
  "steps": [
    {
      "title": "一个 Mapper 文件只解析一次",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 96,
      "lines": 10,
      "explain": "`isResourceLoaded` 防重复；解析完记一笔。后面三行处理「解析时引用了还没解析到的东西」——ResultMap、cache-ref、语句都允许延后重试，这就是 `IncompleteElementException` 的用途。",
      "state": {
        "vars": [["resource", "mapper/UserMapper.xml"], ["namespace", "com.example.UserMapper"]]
      }
    },
    {
      "title": "顺序：cache → resultMap → sql 片段 → 语句",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 111,
      "lines": 17,
      "explain": "`namespace` 先写进 `builderAssistant`，之后所有 id 都会自动加上前缀。接着按依赖顺序解析：缓存、参数映射、结果映射、`<sql>` 片段，最后才是语句——因为语句会引用前四者。",
      "state": {
        "tables": [["currentNamespace", "com.example.UserMapper"], ["sqlFragments", "Base_Column_List"]]
      }
    },
    {
      "title": "每条语句交给 XMLStatementBuilder",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 136,
      "lines": 13,
      "explain": "循环里对每个 `<select|insert|update|delete>` 建一个 `XMLStatementBuilder`。注意 catch 的是 `IncompleteElementException`——解析失败不一定是错，可能是引用了后面才定义的东西，先记进 `incompleteStatements` 等重试。",
      "state": {
        "stack": ["XMLMapperBuilder.parse()", "buildStatementFromContext(list)", "XMLStatementBuilder.parseStatementNode()"],
        "vars": [["statements", "4 条"]]
      }
    },
    {
      "title": "先按 databaseId 过滤",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 58,
      "lines": 7,
      "explain": "`databaseIdMatchesCurrent` 不匹配就直接 return——同一个 id 可以按数据库写多份，只有匹配当前 `databaseId` 的那份会被装配。",
      "state": {
        "vars": [["id", "selectById"], ["databaseId", "null（没配）"]]
      }
    },
    {
      "title": "标签名决定 SqlCommandType",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 66,
      "lines": 6,
      "explain": "`<select>` → SELECT，`<insert>` → INSERT。接着三个默认值：`flushCache` 对写语句默认 true、`useCache` 对查询默认 true。**这两个默认值就是一级缓存行为的一半**。",
      "predict": {
        "question": "一条 <update> 默认会清缓存吗？",
        "options": ["会，flushCache 默认 true", "不会，要手动写 flushCache", "取决于数据库"],
        "answer": 0,
        "reveal": "写语句默认 `flushCache=true`，所以更新之后同一 SqlSession 的查询会重新走库。反过来，<select> 默认不清缓存。"
      },
      "state": {
        "vars": [["sqlCommandType", "SELECT"], ["flushCache", "false"], ["useCache", "true"]]
      }
    },
    {
      "title": "include 必须在解析 SQL 之前展开",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 74,
      "lines": 4,
      "explain": "`<include refid=\"...\">` 先把 `<sql>` 片段拼进当前节点，后面 `createSqlSource` 看到的才是完整的 SQL。反过来做的话，动态 SQL 里会残留一个 `<include>` 节点。",
      "state": {
        "tables": [["sqlFragments", "Base_Column_List → id, name, age"]]
      }
    },
    {
      "title": "主键策略：useGeneratedKeys 开着就走 Jdbc3KeyGenerator",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 87,
      "lines": 10,
      "explain": "先看有没有 `<selectKey>`（那会生成一个独立的 SelectKeyGenerator）；否则看 `useGeneratedKeys`：开着且是 INSERT，就用 `Jdbc3KeyGenerator` 把自增主键回填到对象上。",
      "when": { "generatedKeys": true },
      "state": {
        "tables": [["keyGenerator", "Jdbc3KeyGenerator"], ["回填字段", "id"]]
      }
    },
    {
      "title": "关掉之后：NoKeyGenerator",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 87,
      "lines": 10,
      "explain": "同一个分支，`useGeneratedKeys=false` 时拿到 `NoKeyGenerator`：插入照常，但对象上的 id 还是 null。**很多人以为「插完就有 id」是框架默认，其实要靠这个开关**。",
      "when": { "generatedKeys": false },
      "state": {
        "tables": [["keyGenerator", "NoKeyGenerator"], ["回填字段", "无"]]
      }
    },
    {
      "title": "生成 SqlSource 与结果映射的引用",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 97,
      "lines": 12,
      "explain": "`langDriver.createSqlSource` 决定了「这张 SQL 是静态的还是带 `<if>` 的动态 SQL」；`resultType` / `resultMap` 二选一，两个都没写时才去猜方法返回类型。",
      "state": {
        "vars": [["sqlSource", "DynamicSqlSource"], ["statementType", "PREPARED"], ["resultType", "com.example.User"]]
      }
    },
    {
      "title": "最后一步：Builder 组装并入库",
      "file": "org/apache/ibatis/builder/MapperBuilderAssistant.java",
      "line": 209,
      "lines": 17,
      "explain": "`applyCurrentNamespace(id, false)` 补上 `com.example.UserMapper.` 前缀，然后用 Builder 把十几个字段一次设完，最后 `configuration.addMappedStatement(statement)`。到这里，一条 XML 语句才真正可执行。",
      "state": {
        "stack": ["MapperBuilderAssistant.addMappedStatement(...)"],
        "tables": [["mappedStatements", "com.example.UserMapper.selectById"], ["statement.parameterMap", "自动生成"]]
      }
    }
  ]
}
```

## 这条线为什么值得读

- 你终于知道 `namespace` 与 `id` 是怎么拼成 `statement id` 的——`IllegalArgumentException: Mapped Statements collection does not contain value for ...` 十有八九是这里拼错。
- `flushCache` / `useCache` 的默认值是**按语句类型**给的，不是全局一个值。
- 主键回填不是自动的：`useGeneratedKeys` 或 `<selectKey>` 二选一。

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
