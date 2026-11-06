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

```code-flow
{
  "id": "mapper-method",
  "title": "SqlCommand + MethodSignature + execute",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/binding/MapperMethod.java",
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
      "title": "构造时就把两件事定下来",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 52,
      "code": "  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {\n    this.command = new SqlCommand(config, mapperInterface, method);\n    this.method = new MethodSignature(config, mapperInterface, method);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4
      ],
      "nodes": [
        "proxy"
      ],
      "edges": [],
      "explain": "`SqlCommand` 决定「执行哪条语句、什么类型」；`MethodSignature` 决定「返回值长什么样、参数怎么转」。两个都在构造期算完，执行期只做 switch。",
      "log": [
        {
          "k": "command.name",
          "v": "com.example.UserMapper.selectById"
        },
        {
          "k": "signature.returnsMany",
          "v": "false"
        }
      ]
    },
    {
      "title": "SqlCommand：接口名 + 方法名 = 语句 id",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 222,
      "code": "    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {\n      final String methodName = method.getName();\n      final Class<?> declaringClass = method.getDeclaringClass();\n      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass, configuration);\n      if (ms == null) {\n        if (method.getAnnotation(Flush.class) == null) {\n          throw new BindingException(\n              \"Invalid bound statement (not found): \" + mapperInterface.getName() + \".\" + methodName);\n        }\n        name = null;\n        type = SqlCommandType.FLUSH;\n      } else {\n        name = ms.getId();\n        type = ms.getSqlCommandType();\n        if (type == SqlCommandType.UNKNOWN) {\n          throw new BindingException(\"Unknown execution method for: \" + name);\n        }",
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
        "proxy"
      ],
      "edges": [],
      "explain": "`mapperInterface.getName() + \".\" + methodName` 就是要去 `Configuration` 里查的 id。查不到且没有 `@Flush`，直接抛 `Invalid bound statement (not found)`——这是 MyBatis 最常见的一条报错。",
      "state": {
        "vars": [
          {
            "name": "statementId",
            "value": "com.example.UserMapper.selectById"
          },
          {
            "name": "type",
            "value": "SELECT"
          }
        ]
      }
    },
    {
      "title": "父接口的方法也能找到",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 250,
      "code": "    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName, Class<?> declaringClass,\n        Configuration configuration) {\n      String statementId = mapperInterface.getName() + \".\" + methodName;\n      if (configuration.hasStatement(statementId)) {\n        return configuration.getMappedStatement(statementId);\n      }\n      if (mapperInterface.equals(declaringClass)) {\n        return null;\n      }\n      for (Class<?> superInterface : mapperInterface.getInterfaces()) {\n        if (declaringClass.isAssignableFrom(superInterface)) {\n          MappedStatement ms = resolveMappedStatement(superInterface, methodName, declaringClass, configuration);\n          if (ms != null) {\n            return ms;\n          }\n        }\n      }",
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
        "proxy"
      ],
      "edges": [],
      "explain": "如果当前接口里没有这条语句，会沿父接口递归找。所以把 Mapper 拆成 `BaseMapper<T>` + 子接口时，父接口的方法也能绑上。",
      "log": [
        {
          "k": "查找顺序",
          "v": "子接口 → 父接口递归"
        }
      ],
      "predict": {
        "question": "方法定义在父接口 BaseMapper 上，MappedStatement 的 id 应该怎么写？",
        "options": [
          "父接口全名 + 方法名",
          "子接口全名 + 方法名",
          "都可以"
        ],
        "answer": 0,
        "reveal": "`SqlCommand` 先用子接口名查，查不到再递归父接口，用父接口全名作为 id。XML 的 namespace 要与声明方法的接口一致。"
      }
    },
    {
      "title": "MethodSignature：返回类型决定执行方式",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 284,
      "code": "    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {\n      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);\n      if (resolvedReturnType instanceof Class<?>) {\n        this.returnType = (Class<?>) resolvedReturnType;\n      } else if (resolvedReturnType instanceof ParameterizedType) {\n        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();\n      } else {\n        this.returnType = method.getReturnType();\n      }\n      this.returnsVoid = void.class.equals(this.returnType);\n      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();\n      this.returnsCursor = Cursor.class.equals(this.returnType);\n      this.returnsOptional = Optional.class.equals(this.returnType);\n      this.mapKey = getMapKey(method);\n      this.returnsMap = this.mapKey != null;\n      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);\n      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);\n      this.paramNameResolver = new ParamNameResolver(configuration, method);\n    }",
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
        19
      ],
      "nodes": [
        "proxy"
      ],
      "edges": [],
      "explain": "`returnsMany`（集合/数组）、`returnsMap`、`returnsCursor`、`returnsOptional`、`returnsVoid` 都在这里算。还会找 `RowBounds` 与 `ResultHandler` 参数的位置，以及创建 `ParamNameResolver`。",
      "log": [
        {
          "k": "returnsMany",
          "v": "false"
        },
        {
          "k": "returnsMap",
          "v": "false"
        },
        {
          "k": "rowBoundsIndex",
          "v": "-1"
        }
      ]
    },
    {
      "title": "execute：一个 switch 分派到底",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 57,
      "code": "  public Object execute(SqlSession sqlSession, Object[] args) {\n    Object result;\n    switch (command.getType()) {\n      case INSERT: {\n        Object param = method.convertArgsToSqlCommandParam(args);\n        result = rowCountResult(sqlSession.insert(command.getName(), param));\n        break;\n      }\n      case UPDATE: {\n        Object param = method.convertArgsToSqlCommandParam(args);\n        result = rowCountResult(sqlSession.update(command.getName(), param));\n        break;\n      }\n      case DELETE: {\n        Object param = method.convertArgsToSqlCommandParam(args);\n        result = rowCountResult(sqlSession.delete(command.getName(), param));\n        break;\n      }\n      case SELECT:\n        if (method.returnsVoid() && method.hasResultHandler()) {\n          executeWithResultHandler(sqlSession, args);\n          result = null;\n        } else if (method.returnsMany()) {\n          result = executeForMany(sqlSession, args);\n        } else if (method.returnsMap()) {\n          result = executeForMap(sqlSession, args);\n        } else if (method.returnsCursor()) {\n          result = executeForCursor(sqlSession, args);\n        } else {\n          Object param = method.convertArgsToSqlCommandParam(args);\n          result = sqlSession.selectOne(command.getName(), param);\n          if (method.returnsOptional() && (result == null || !method.getReturnType().equals(result.getClass()))) {\n            result = Optional.ofNullable(result);\n          }",
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
      "explain": "`INSERT/UPDATE/DELETE` 都走 `rowCountResult`；`SELECT` 再按返回类型细分。**没有魔法**：编译期不知道的东西，运行期用一个枚举 switch 补上。",
      "state": {
        "stack": [
          "MapperMethod.execute",
          "switch(command.getType())"
        ]
      }
    },
    {
      "title": "单个对象：selectOne + Optional 包装",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 85,
      "code": "        } else {\n          Object param = method.convertArgsToSqlCommandParam(args);\n          result = sqlSession.selectOne(command.getName(), param);\n          if (method.returnsOptional() && (result == null || !method.getReturnType().equals(result.getClass()))) {\n            result = Optional.ofNullable(result);\n          }",
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
        "proxy"
      ],
      "edges": [],
      "explain": "返回单个对象时调 `selectOne`；返回 `Optional` 时把 null 包成 `Optional.empty()`。",
      "log": [
        {
          "k": "调用",
          "v": "sqlSession.selectOne"
        },
        {
          "k": "Optional",
          "v": "null → Optional.empty"
        }
      ],
      "predict": {
        "question": "selectOne 查到 0 条返回什么？",
        "options": [
          "null",
          "空列表",
          "异常"
        ],
        "answer": 0,
        "reveal": "0 条返回 null，多条才抛 TooManyResultsException。"
      }
    },
    {
      "title": "List：executeForMany",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 140,
      "code": "  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {\n    List<E> result;\n    Object param = method.convertArgsToSqlCommandParam(args);\n    if (method.hasRowBounds()) {\n      RowBounds rowBounds = method.extractRowBounds(args);\n      result = sqlSession.selectList(command.getName(), param, rowBounds);\n    } else {\n      result = sqlSession.selectList(command.getName(), param);\n    }\n    // issue #510 Collections & arrays support\n    if (!method.getReturnType().isAssignableFrom(result.getClass())) {\n      if (method.getReturnType().isArray()) {\n        return convertToArray(result);\n      }\n      return convertToDeclaredCollection(sqlSession.getConfiguration(), result);\n    }",
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
        16
      ],
      "nodes": [
        "proxy"
      ],
      "edges": [],
      "explain": "`executeForMany` 调 `selectList`，再按返回类型做集合/数组转换（`issue #510` 就是返回数组时的兼容）。",
      "log": [
        {
          "k": "调用",
          "v": "sqlSession.selectList"
        },
        {
          "k": "返回",
          "v": "List / 数组"
        }
      ]
    },
    {
      "title": "void / int：rowCountResult",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 106,
      "code": "  private Object rowCountResult(int rowCount) {\n    final Object result;\n    if (method.returnsVoid()) {\n      result = null;\n    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {\n      result = rowCount;\n    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {\n      result = (long) rowCount;\n    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {\n      result = rowCount > 0;\n    } else {\n      throw new BindingException(\n          \"Mapper method '\" + command.getName() + \"' has an unsupported return type: \" + method.getReturnType());\n    }\n    return result;\n  }",
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
        16
      ],
      "nodes": [
        "proxy"
      ],
      "edges": [],
      "explain": "写语句的影响行数会按方法返回类型转：`void → null`、`int → rowCount`、`long → (long)`、`boolean → rowCount > 0`。返回别的类型直接抛「unsupported return type」。",
      "log": [
        {
          "k": "rowCount",
          "v": "3"
        },
        {
          "k": "boolean 返回",
          "v": "true"
        },
        {
          "k": "void 返回",
          "v": "null"
        }
      ]
    }
  ]
}
```

## 顺序很重要

`MapperMethod` 的构造发生在**第一次调用**（`cachedInvoker` 的 `computeIfAbsent`），不是在 `getMapper` 时。
所以「Invalid bound statement」这类错误，第一次调到才会报——启动时 `@Mapper` 扫描不会替你验一遍。
（Spring 集成里有 `MapperScannerConfigurer`，但那是另一个仓库的事。）

```code-challenge
{
  "id": "ex-mappermethod",
  "kind": "order",
  "title": "排出 MapperMethod 的解析与分派",
  "lines": [
    "sqlSession.selectOne(command.getName(), param)",
    "new SqlCommand(...) 解析语句 id 与类型",
    "new MethodSignature(...) 解析返回类型",
    "MapperMethod.execute 按 command 类型 switch"
  ],
  "answer": [
    1,
    2,
    3,
    0
  ],
  "explain": "构造期把命令与签名算好，执行期只做 switch。"
}
```

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
