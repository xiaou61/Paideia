---
title: ResultSetHandler：列怎么变成属性
category: mybatis-source
order: 16
minutes: 30
level: intermediate
status: published
---

## 这一关要读什么

SQL 回来了，`ResultSet` 里是一列一列的值，你要的是一个 `User` 对象。中间是
`DefaultResultSetHandler` 的活儿：先按 `resultMap` 显式映射，再做自动映射。

这一关看**自动映射到底怎么匹配**——`user_name` 为什么有时能对上 `userName`，有时不能。

```source-walk
{
  "id": "result-set-handler",
  "title": "handleResultSets 与自动映射",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java", "label": "DefaultResultSetHandler" }
  ],
  "controls": [
    { "id": "camel", "type": "toggle", "label": "mapUnderscoreToCamelCase", "default": false }
  ],
  "steps": [
    {
      "title": "入口：每个结果集交给一个 resultMap",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 189,
      "lines": 40,
      "explain": "`handleResultSets` 按语句顺序处理多个 ResultSet，每个都调 `handleResultSet` → `handleRowValues` → `getRowValue`。一个语句返回两个结果集（存储过程）时，这里会走多轮。",
      "state": {
        "vars": [["resultSets", "1"], ["resultMap", "com.example.UserMapper.userMap"]]
      }
    },
    {
      "title": "先建对象，再填属性",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 429,
      "lines": 52,
      "explain": "`getRowValue` 先 `createResultObject`（构造器/工厂），然后依次：`applyPropertyMappings`（显式 resultMap）→ `applyAutomaticMappings`（自动映射）→ 嵌套结果/关联对象 → 延迟加载登记。",
      "state": {
        "tables": [["显式映射", "resultMap 里写了的列"], ["自动映射", "没写但同名的列"]]
      }
    },
    {
      "title": "显式映射：resultMap 说了算",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 481,
      "lines": 52,
      "explain": "遍历 `resultMappings`，按 `column` 取列、按 `property` 写属性，类型转换交给 `typeHandler`。**显式映射优先**，同名属性不会被自动映射覆盖。",
      "state": {
        "tables": [["<id column=\"id\" property=\"id\">", "已处理"], ["<result column=\"user_name\" property=\"userName\">", "已处理"]]
      }
    },
    {
      "title": "自动映射：只处理「没配过」的列",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 534,
      "lines": 46,
      "explain": "`createAutomaticMappings` 把 ResultSet 里**没被 resultMap 用过**的列拿出来，按列名找属性。`autoMappingBehavior=PARTIAL`（默认）时，有嵌套结果映射就不做自动映射。",
      "state": {
        "tables": [["未映射列", "age, email"], ["候选属性", "age, email"]]
      }
    },
    {
      "title": "user_name 能不能对上 userName，全看这个开关",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 580,
      "lines": 45,
      "explain": "`applyAutomaticMappings` 里对列名做 `configuration.isMapUnderscoreToCamelCase()` 判断：开着就把 `user_name` 转成 `userName` 再找；关着就按原样找，找不到就跳过（`autoMappingUnknownColumnBehavior` 默认 NONE，不报错）。",
      "when": { "camel": true },
      "state": {
        "tables": [["列 user_name", "→ userName（命中）"], ["未映射列", "0"]]
      }
    },
    {
      "title": "关掉驼峰：user_name 就没人接",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 580,
      "lines": 45,
      "explain": "默认是关的。列 `user_name` 找不到属性 `user_name`，于是被静默跳过——对象上 `userName` 还是 null。**这就是「为什么我的字段是 null」最常见的原因**。",
      "when": { "camel": false },
      "state": {
        "tables": [["列 user_name", "找不到属性（跳过）"], ["userName", "null"]]
      }
    }
  ]
}
```

## 嵌套结果与延迟加载

- 显式 `<association>` / `<collection>` 的列会先被消费，自动映射不会再碰。
- `fetchType="lazy"` 时，此行不查关联对象，而是登记一个 `ResultLoader`，访问属性时才查。
- `autoMappingBehavior` 改成 `FULL` 时，即使有嵌套结果映射也做自动映射——方便，也更容易撞上意料之外的字段。

```quiz
{
  "id": "resultset-quiz",
  "title": "小测：结果映射",
  "questions": [
    {
      "question": "列 user_name 对属性 userName，默认能映射上吗？",
      "options": ["能", "不能，mapUnderscoreToCamelCase 默认关", "只有 resultMap 能"],
      "answer": 1,
      "explain": "默认 false；要么开这个开关，要么在 resultMap 里显式写。"
    },
    {
      "question": "显式 resultMap 与自动映射谁优先？",
      "options": ["自动映射", "显式映射", "后执行的覆盖"],
      "answer": 1,
      "explain": "先 applyPropertyMappings，自动映射只处理剩下的列。"
    },
    {
      "question": "autoMappingUnknownColumnBehavior 默认值是什么？",
      "options": ["NONE（不报错）", "WARNING", "FAILING"],
      "answer": 0,
      "explain": "默认 NONE：多出来的列静默忽略。"
    }
  ]
}
```

下一关：值到底怎么从 `String` 变成 `LocalDateTime`——`TypeHandler`。
