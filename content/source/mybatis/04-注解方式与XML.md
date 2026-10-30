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

```source-walk
{
  "id": "annotation-mapper",
  "title": "注解 Mapper 的装配",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/binding/MapperRegistry.java", "label": "MapperRegistry" },
    { "path": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java", "label": "MapperAnnotationBuilder" }
  ],
  "controls": [
    {
      "id": "via",
      "type": "select",
      "label": "这条语句写在哪",
      "default": "annotation",
      "options": [
        { "value": "annotation", "label": "注解" },
        { "value": "xml", "label": "XML" }
      ]
    }
  ],
  "steps": [
    {
      "title": "注册先放工厂，再解析",
      "file": "org/apache/ibatis/binding/MapperRegistry.java",
      "line": 60,
      "lines": 20,
      "explain": "`knownMappers.put(type, new MapperProxyFactory<>(type))` 发生在解析**之前**。注释写得很清楚：先把类型登记上，解析器才不会又去 bind 一次。解析失败时 finally 把它移除，避免留下半个 Mapper。",
      "state": {
        "tables": [["knownMappers", "UserMapper → MapperProxyFactory"], ["解析状态", "进行中"]]
      }
    },
    {
      "title": "parse 先找同名 XML",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 114,
      "lines": 27,
      "explain": "`loadXmlResource()` 排在最前：`com.example.UserMapper` 会去找 `com/example/UserMapper.xml`。也就是说**注解与 XML 可以混用**，XML 先装配，注解后装配。",
      "state": {
        "vars": [["resource", "com/example/UserMapper"], ["xml", "UserMapper.xml（可选）"]]
      }
    },
    {
      "title": "同名 XML 是怎么被拉进来的",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 145,
      "lines": 21,
      "explain": "资源名是接口全限定名换斜杠加 `.xml`。找不到不算错（注解-only 的项目很常见）；找到就交给 `XMLMapperBuilder` 正常解析，走的还是上一关那条装配线。",
      "state": {
        "tables": [["classpath", "com/example/UserMapper.xml"], ["xmlParser", "XMLMapperBuilder"]]
      }
    },
    {
      "title": "每个方法一条语句",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 282,
      "lines": 10,
      "explain": "`mappedStatementId = type.getName() + \".\" + method.getName()\"`——和 XML 的 `namespace.id` 是同一个规则。bridge 方法与 default 方法直接跳过（`canHaveStatement`）。",
      "state": {
        "vars": [["mappedStatementId", "com.example.UserMapper.selectById"], ["sqlCommandType", "SELECT"]]
      }
    },
    {
      "title": "主键策略：@SelectKey > @Options > 全局",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 293,
      "lines": 25,
      "explain": "INSERT/UPDATE 才看主键：先找 `@SelectKey`，再找 `@Options`，都没有才用全局的 `useGeneratedKeys`。和 XML 那条分支（上一关的第 87 行）是同一套规则的两种写法。",
      "predict": {
        "question": "一个 @Insert 方法，全局 useGeneratedKeys=false，但方法上写了 @Options(useGeneratedKeys=true)，会回填 id 吗？",
        "options": ["会，方法级优先", "不会，全局说了算", "两者冲突直接报错"],
        "answer": 0,
        "reveal": "@Options 是方法级配置，优先于全局。这也是为什么同一个项目里可以有的表回填、有的不回填。"
      },
      "state": {
        "tables": [["keyGenerator", "Jdbc3KeyGenerator"], ["keyProperty", "id"]]
      }
    },
    {
      "title": "注解文本怎么变成 SqlSource",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 573,
      "lines": 14,
      "explain": "`@Select` / `@Insert` 的 `value()` 是 String[]，拼起来交给 `LanguageDriver.createSqlSource`。注意**这里也能用动态 SQL**：`<script>` 包一段 XML，注解里就能写 `<if>`。",
      "state": {
        "vars": [["annotation", "@Select"], ["sql", "select * from user where id = #{id}"]]
      }
    },
    {
      "title": "最后和 XML 走同一个入口",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 347,
      "lines": 8,
      "explain": "`assistant.addMappedStatement(...)` 与 XML 完全同一条路。所以注解与 XML 的差别只在**从哪读文本**，装配规则一份。",
      "state": {
        "tables": [["mappedStatements", "com.example.UserMapper.selectById"]]
      }
    },
    {
      "title": "注解写的那条：来自 parseStatement",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 282,
      "lines": 1,
      "explain": "这条走的是「遍历接口方法 → 读注解 → 建语句」。",
      "when": { "via": "annotation" },
      "state": {
        "tables": [["来源", "@Select 注解"]]
      }
    },
    {
      "title": "XML 写的那条：来自 XMLMapperBuilder",
      "file": "org/apache/ibatis/builder/annotation/MapperAnnotationBuilder.java",
      "line": 152,
      "lines": 1,
      "explain": "这条走的是「同名 XML → XMLMapperBuilder」。两种写法可以同时存在于一个 Mapper，但**同一个 id 只能有一个来源**：重复会在 StrictMap 处抛异常。",
      "when": { "via": "xml" },
      "state": {
        "tables": [["来源", "UserMapper.xml"], ["同 id 重复", "启动失败"]]
      }
    }
  ]
}
```

## 什么时候用哪个

- **注解**：语句短、无动态片段。优点是离方法近；缺点是复杂 SQL 会变成一坨字符串。
- **XML**：语句长、动态片段多、要复用 `<sql>`。缺点是文件和接口分离。
- **混用**：常见于老项目——主查询在 XML，简单的 count/delete 用注解。知道它们走同一条装配线，就不会被"哪个优先级高"绕住。

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
