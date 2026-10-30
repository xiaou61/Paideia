---
title: MapperMethod：方法签名怎么决定走哪条 SQL 通道
category: mybatis-source
order: 10
minutes: 25
level: intermediate
status: published
---

## 这一关要读什么

`userMapper.selectById(1)` 与 `userMapper.deleteById(1)` 在代理层看起来一样，但一个调 `selectOne`、
一个调 `delete`。决定这件事的是 `MapperMethod`：它在**构造时**就把方法翻译成「命令 + 签名」。

```source-walk
{
  "id": "mapper-method",
  "title": "SqlCommand + MethodSignature + execute",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/binding/MapperMethod.java", "label": "MapperMethod" }
  ],
  "controls": [
    {
      "id": "returnType",
      "type": "select",
      "label": "方法返回什么",
      "default": "one",
      "options": [
        { "value": "one", "label": "单个对象" },
        { "value": "list", "label": "List" },
        { "value": "void", "label": "void / int" }
      ]
    }
  ],
  "steps": [
    {
      "title": "构造时就把两件事定下来",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 52,
      "lines": 4,
      "explain": "`SqlCommand` 决定「执行哪条语句、什么类型」；`MethodSignature` 决定「返回值长什么样、参数怎么转」。两个都在构造期算完，执行期只做 switch。",
      "state": {
        "tables": [["command.name", "com.example.UserMapper.selectById"], ["signature.returnsMany", "false"]]
      }
    },
    {
      "title": "SqlCommand：接口名 + 方法名 = 语句 id",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 222,
      "lines": 17,
      "explain": "`mapperInterface.getName() + \".\" + methodName` 就是要去 `Configuration` 里查的 id。查不到且没有 `@Flush`，直接抛 `Invalid bound statement (not found)`——这是 MyBatis 最常见的一条报错。",
      "state": {
        "vars": [["statementId", "com.example.UserMapper.selectById"], ["type", "SELECT"]]
      }
    },
    {
      "title": "父接口的方法也能找到",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 250,
      "lines": 17,
      "explain": "如果当前接口里没有这条语句，会沿父接口递归找。所以把 Mapper 拆成 `BaseMapper<T>` + 子接口时，父接口的方法也能绑上。",
      "predict": {
        "question": "方法定义在父接口 BaseMapper 上，MappedStatement 的 id 应该怎么写？",
        "options": ["父接口全名 + 方法名", "子接口全名 + 方法名", "都可以"],
        "answer": 0,
        "reveal": "`SqlCommand` 先用子接口名查，查不到再递归父接口，用父接口全名作为 id。XML 的 namespace 要与声明方法的接口一致。"
      },
      "state": {
        "tables": [["查找顺序", "子接口 → 父接口递归"]]
      }
    },
    {
      "title": "MethodSignature：返回类型决定执行方式",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 284,
      "lines": 19,
      "explain": "`returnsMany`（集合/数组）、`returnsMap`、`returnsCursor`、`returnsOptional`、`returnsVoid` 都在这里算。还会找 `RowBounds` 与 `ResultHandler` 参数的位置，以及创建 `ParamNameResolver`。",
      "state": {
        "tables": [["returnsMany", "false"], ["returnsMap", "false"], ["rowBoundsIndex", "-1"]]
      }
    },
    {
      "title": "execute：一个 switch 分派到底",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 57,
      "lines": 34,
      "explain": "`INSERT/UPDATE/DELETE` 都走 `rowCountResult`；`SELECT` 再按返回类型细分。**没有魔法**：编译期不知道的东西，运行期用一个枚举 switch 补上。",
      "state": {
        "stack": ["MapperMethod.execute", "switch(command.getType())"]
      }
    },
    {
      "title": "单个对象：selectOne + Optional 包装",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 85,
      "lines": 6,
      "explain": "返回单个对象时调 `selectOne`；返回 `Optional` 时把 null 包成 `Optional.empty()`。",
      "when": { "returnType": "one" },
      "state": {
        "tables": [["调用", "sqlSession.selectOne"], ["Optional", "null → Optional.empty"]]
      }
    },
    {
      "title": "List：executeForMany",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 140,
      "lines": 16,
      "explain": "`executeForMany` 调 `selectList`，再按返回类型做集合/数组转换（`issue #510` 就是返回数组时的兼容）。",
      "when": { "returnType": "list" },
      "state": {
        "tables": [["调用", "sqlSession.selectList"], ["返回", "List / 数组"]]
      }
    },
    {
      "title": "void / int：rowCountResult",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 106,
      "lines": 16,
      "explain": "写语句的影响行数会按方法返回类型转：`void → null`、`int → rowCount`、`long → (long)`、`boolean → rowCount > 0`。返回别的类型直接抛「unsupported return type」。",
      "when": { "returnType": "void" },
      "state": {
        "tables": [["rowCount", "3"], ["boolean 返回", "true"], ["void 返回", "null"]]
      }
    }
  ]
}
```

## 顺序很重要

`MapperMethod` 的构造发生在**第一次调用**（`cachedInvoker` 的 `computeIfAbsent`），不是在 `getMapper` 时。
所以「Invalid bound statement」这类错误，第一次调到才会报——启动时 `@Mapper` 扫描不会替你验一遍。
（Spring 集成里有 `MapperScannerConfigurer`，但那是另一个仓库的事。）

```quiz
{
  "id": "mapper-method-quiz",
  "title": "小测：方法解析",
  "questions": [
    {
      "question": "MappedStatement 的 id 是怎么拼的？",
      "options": ["Mapper 接口全名 + 方法名", "XML 文件名 + 方法名", "方法名"],
      "answer": 0,
      "explain": "`SqlCommand.resolveMappedStatement` 用 `mapperInterface.getName() + \".\" + methodName`。"
    },
    {
      "question": "方法返回 boolean、执行的是 update，结果怎么来？",
      "options": ["true/false 由数据库返回", "rowCount > 0", "抛异常"],
      "answer": 1,
      "explain": "`rowCountResult` 把影响行数转成布尔：大于 0 即 true。"
    },
    {
      "question": "方法第一次被调用时才报 Invalid bound statement，为什么？",
      "options": ["MapperMethod 在第一次调用时构造", "因为数据库没连上", "因为缓存没命中"],
      "answer": 0,
      "explain": "`cachedInvoker` 的 computeIfAbsent 在首次调用时 new MapperMethod，此时才解析命令。"
    }
  ]
}
```

下一关：`MapperMethod` 最后都落到 `SqlSession`。它是什么、为什么不能共享。
