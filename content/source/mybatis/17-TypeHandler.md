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

```source-walk
{
  "id": "type-handler",
  "title": "TypeHandlerRegistry 与 BaseTypeHandler",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/type/TypeHandlerRegistry.java", "label": "TypeHandlerRegistry" },
    { "path": "org/apache/ibatis/type/BaseTypeHandler.java", "label": "BaseTypeHandler" }
  ],
  "controls": [
    {
      "id": "value",
      "type": "select",
      "label": "参数值",
      "default": "normal",
      "options": [
        { "value": "normal", "label": "有值" },
        { "value": "null", "label": "null" }
      ]
    }
  ],
  "steps": [
    {
      "title": "按 Java 类型找 handler",
      "file": "org/apache/ibatis/type/TypeHandlerRegistry.java",
      "line": 219,
      "lines": 4,
      "explain": "`getTypeHandler(Class)` 查一张 `Map<Class, TypeHandler>`。内置的 `String`、`Long`、`LocalDateTime` 都在启动时注册好了；找不到就返回 null，调用方再决定用什么兜底。",
      "state": {
        "tables": [["Long", "LongTypeHandler"], ["String", "StringTypeHandler"]]
      }
    },
    {
      "title": "带上 jdbcType 时走二维表",
      "file": "org/apache/ibatis/type/TypeHandlerRegistry.java",
      "line": 231,
      "lines": 4,
      "explain": "`getTypeHandler(type, jdbcType)` 先按精确的 (Java 类型, JDBC 类型) 找，找不到再退回只按 Java 类型。所以 `#{createdAt,jdbcType=TIMESTAMP}` 能选到更合适的 handler。",
      "state": {
        "tables": [["(LocalDateTime, TIMESTAMP)", "LocalDateTimeTypeHandler"], ["回退", "只按 Java 类型"]]
      }
    },
    {
      "title": "注册：内置的都是这么来的",
      "file": "org/apache/ibatis/type/TypeHandlerRegistry.java",
      "line": 417,
      "lines": 20,
      "explain": "`register(Class<T> javaType, TypeHandler<? extends T> handler)` 把 handler 放进两张表：按 Java 类型、按 (Java 类型, jdbcType)。自定义 handler 也是同一个入口。",
      "state": {
        "tables": [["注册表", "按 Java 类型 + 按 JDBC 类型"], ["自定义", "同一入口"]]
      }
    },
    {
      "title": "有值：交给子类的 setNonNullParameter",
      "file": "org/apache/ibatis/type/BaseTypeHandler.java",
      "line": 70,
      "lines": 12,
      "explain": "`setParameter` 判断 null 之后，非 null 调抽象方法 `setNonNullParameter`——每个子类只实现这一句 JDBC 调用。",
      "when": { "value": "normal" },
      "state": {
        "stack": ["BaseTypeHandler.setParameter", "LongTypeHandler.setNonNullParameter", "ps.setLong"]
      }
    },
    {
      "title": "null：没有 jdbcType 直接抛异常",
      "file": "org/apache/ibatis/type/BaseTypeHandler.java",
      "line": 59,
      "lines": 12,
      "explain": "`parameter == null && jdbcType == null` 时抛 `TypeException`：驱动不知道 null 该按哪种 JDBC 类型发。所以可空参数建议写成 `#{name,jdbcType=VARCHAR}`，或配全局 `jdbcTypeForNull`。",
      "when": { "value": "null" },
      "predict": {
        "question": "为什么 null 需要 jdbcType，而有值的时候不需要？",
        "options": ["驱动无法从 null 推断类型", "MyBatis 偷懒", "数据库要求"],
        "answer": 0,
        "reveal": "有值时 Java 类型能推断 JDBC 类型；null 没有类型信息，只能显式指定或走全局默认（OTHER）。"
      },
      "state": {
        "tables": [["ps.setNull", "需要 TypeCode"], ["全局默认", "jdbcTypeForNull = OTHER"]]
      }
    },
    {
      "title": "取值：getResult 只包一层异常",
      "file": "org/apache/ibatis/type/BaseTypeHandler.java",
      "line": 83,
      "lines": 8,
      "explain": "`getResult(rs, columnName)` 调 `getNullableResult`，把 SQL 异常包成 `ResultMapException` 并带上列名——排查「哪一列转换失败」时这条信息很关键。",
      "state": {
        "stack": ["BaseTypeHandler.getResult", "getNullableResult", "rs.getTimestamp"]
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
