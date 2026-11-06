---
title: TypeHandler：Java 类型与 JDBC 类型之间的翻译官
category: mybatis-source
order: 17
minutes: 20
level: intermediate
status: published
---

## 这一关要读什么

`ps.setLong(1, 1L)` 与 `rs.getTimestamp("created_at")` 都是 JDBC 的事。MyBatis 要在
「你的 Java 类型」与「JDBC 类型」之间做一次翻译，这就是 `TypeHandler`。

这一关看两件事：**注册表怎么找一个 handler**，以及**null 为什么会逼你写 jdbcType**。

```code-flow
{
  "id": "type-handler",
  "title": "TypeHandlerRegistry 与 BaseTypeHandler",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/type/TypeHandlerRegistry.java",
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
      "title": "按 Java 类型找 handler",
      "file": "org/apache/ibatis/type/TypeHandlerRegistry.java",
      "line": 219,
      "code": "  public <T> TypeHandler<T> getTypeHandler(Class<T> type) {\n    return getTypeHandler((Type) type, null);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "`getTypeHandler(Class)` 查一张 `Map<Class, TypeHandler>`。内置的 `String`、`Long`、`LocalDateTime` 都在启动时注册好了；找不到就返回 null，调用方再决定用什么兜底。",
      "log": [
        {
          "k": "Long",
          "v": "LongTypeHandler"
        },
        {
          "k": "String",
          "v": "StringTypeHandler"
        }
      ]
    },
    {
      "title": "带上 jdbcType 时走二维表",
      "file": "org/apache/ibatis/type/TypeHandlerRegistry.java",
      "line": 231,
      "code": "  public <T> TypeHandler<T> getTypeHandler(Class<T> type, JdbcType jdbcType) {\n    return getTypeHandler((Type) type, jdbcType);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "`getTypeHandler(type, jdbcType)` 先按精确的 (Java 类型, JDBC 类型) 找，找不到再退回只按 Java 类型。所以 `#{createdAt,jdbcType=TIMESTAMP}` 能选到更合适的 handler。",
      "log": [
        {
          "k": "(LocalDateTime, TIMESTAM",
          "v": "LocalDateTimeTypeHandler"
        },
        {
          "k": "回退",
          "v": "只按 Java 类型"
        }
      ],
      "predict": {
        "question": "精确匹配不到 (Java 类型, jdbcType) 时？",
        "options": [
          "抛异常",
          "回退按 Java 类型找",
          "返回 null"
        ],
        "answer": 1,
        "reveal": "先精确、后回退。"
      }
    },
    {
      "title": "注册：内置的都是这么来的",
      "file": "org/apache/ibatis/type/TypeHandlerRegistry.java",
      "line": 417,
      "code": "  public void register(Class<?> typeHandlerClass) {\n    boolean mappedTypeFound = false;\n    MappedTypes mappedTypes = typeHandlerClass.getAnnotation(MappedTypes.class);\n    if (mappedTypes != null) {\n      for (Class<?> javaTypeClass : mappedTypes.value()) {\n        register(javaTypeClass, typeHandlerClass);\n        mappedTypeFound = true;\n      }\n    }\n    if (!mappedTypeFound) {\n      register(getInstance(null, typeHandlerClass));\n    }\n  }\n\n  // java type + handler type\n\n  public void register(String javaTypeClassName, String typeHandlerClassName) throws ClassNotFoundException {\n    register(Resources.classForName(javaTypeClassName), Resources.classForName(typeHandlerClassName));\n  }",
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
        "base"
      ],
      "edges": [],
      "explain": "`register(Class<T> javaType, TypeHandler<? extends T> handler)` 把 handler 放进两张表：按 Java 类型、按 (Java 类型, jdbcType)。自定义 handler 也是同一个入口。",
      "log": [
        {
          "k": "注册表",
          "v": "按 Java 类型 + 按 JDBC 类型"
        },
        {
          "k": "自定义",
          "v": "同一入口"
        }
      ]
    },
    {
      "title": "有值：交给子类的 setNonNullParameter",
      "file": "org/apache/ibatis/type/BaseTypeHandler.java",
      "line": 70,
      "code": "      }\n    } else {\n      try {\n        setNonNullParameter(ps, i, parameter, jdbcType);\n      } catch (Exception e) {\n        throw new TypeException(\"Error setting non null for parameter #\" + i + \" with JdbcType \" + jdbcType + \" . \"\n            + \"Try setting a different JdbcType for this parameter or a different configuration property. \" + \"Cause: \"\n            + e, e);\n      }\n    }\n  }",
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
        "base"
      ],
      "edges": [],
      "explain": "`setParameter` 判断 null 之后，非 null 调抽象方法 `setNonNullParameter`——每个子类只实现这一句 JDBC 调用。",
      "state": {
        "stack": [
          "BaseTypeHandler.setParameter",
          "LongTypeHandler.setNonNullParameter",
          "ps.setLong"
        ]
      }
    },
    {
      "title": "null：没有 jdbcType 直接抛异常",
      "file": "org/apache/ibatis/type/BaseTypeHandler.java",
      "line": 59,
      "code": "  public void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {\n    if (parameter == null) {\n      if (jdbcType == null) {\n        throw new TypeException(\"JDBC requires that the JdbcType must be specified for all nullable parameters.\");\n      }\n      try {\n        ps.setNull(i, jdbcType.TYPE_CODE);\n      } catch (SQLException e) {\n        throw new TypeException(\"Error setting null for parameter #\" + i + \" with JdbcType \" + jdbcType + \" . \"\n            + \"Try setting a different JdbcType for this parameter or a different jdbcTypeForNull configuration property. \"\n            + \"Cause: \" + e, e);\n      }",
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
        12
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "`parameter == null && jdbcType == null` 时抛 `TypeException`：驱动不知道 null 该按哪种 JDBC 类型发。所以可空参数建议写成 `#{name,jdbcType=VARCHAR}`，或配全局 `jdbcTypeForNull`。",
      "log": [
        {
          "k": "ps.setNull",
          "v": "需要 TypeCode"
        },
        {
          "k": "全局默认",
          "v": "jdbcTypeForNull = OTHER"
        }
      ],
      "predict": {
        "question": "为什么 null 需要 jdbcType，而有值的时候不需要？",
        "options": [
          "驱动无法从 null 推断类型",
          "MyBatis 偷懒",
          "数据库要求"
        ],
        "answer": 0,
        "reveal": "有值时 Java 类型能推断 JDBC 类型；null 没有类型信息，只能显式指定或走全局默认（OTHER）。"
      }
    },
    {
      "title": "取值：getResult 只包一层异常",
      "file": "org/apache/ibatis/type/BaseTypeHandler.java",
      "line": 83,
      "code": "  public T getResult(ResultSet rs, String columnName) throws SQLException {\n    try {\n      return getNullableResult(rs, columnName);\n    } catch (Exception e) {\n      throw new ResultMapException(\"Error attempting to get column '\" + columnName + \"' from result set.  Cause: \" + e,\n          e);\n    }\n  }",
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
      "explain": "`getResult(rs, columnName)` 调 `getNullableResult`，把 SQL 异常包成 `ResultMapException` 并带上列名——排查「哪一列转换失败」时这条信息很关键。",
      "state": {
        "stack": [
          "BaseTypeHandler.getResult",
          "getNullableResult",
          "rs.getTimestamp"
        ]
      }
    }
  ]
}
```

## 什么时候需要自定义 TypeHandler

- 数据库存的是 JSON 字符串，Java 侧想要对象；
- 枚举想存 code 而不是 name；
- 加密字段（存密文、读解密）。

注册方式：`<typeHandlers><typeHandler handler="..."/></typeHandlers>`，或在 `Configuration` 里
`typeHandlerRegistry.register(...)`。**注意它只影响绑定/取值，不影响 SQL 生成**。

```code-challenge
{
  "id": "ex-typehandler",
  "kind": "fill",
  "title": "补 null 的类型信息",
  "template": "参数为 null 且没有指定 `{{0}}` 时，BaseTypeHandler 会抛 TypeException。",
  "options": [
    [
      "jdbcType",
      "javaType",
      "typeHandler"
    ]
  ],
  "answers": [
    0
  ],
  "explain": "null 没有 Java 类型可供推断，必须显式给 jdbcType 或走全局默认。"
}
```

```quiz
{
  "id": "typehandler-quiz",
  "title": "小测：类型处理器",
  "questions": [
    {
      "question": "参数为 null 且没写 jdbcType，会发生什么？",
      "options": ["按 OTHER 发", "抛 TypeException", "驱动自己猜"],
      "answer": 1,
      "explain": "BaseTypeHandler.setParameter 里 `jdbcType == null` 直接抛。"
    },
    {
      "question": "getTypeHandler(type, jdbcType) 找不到精确匹配时？",
      "options": ["返回 null", "回退到只按 Java 类型找", "抛异常"],
      "answer": 1,
      "explain": "先精确、后回退。"
    },
    {
      "question": "自定义 TypeHandler 会影响 SQL 文本吗？",
      "options": ["会", "不会，只影响绑定与取值", "看数据库"],
      "answer": 1,
      "explain": "SQL 文本在 SqlSource 阶段就定了；TypeHandler 只做值转换。"
    }
  ]
}
```

下一关：关联对象为什么可以「用到才查」——延迟加载。
