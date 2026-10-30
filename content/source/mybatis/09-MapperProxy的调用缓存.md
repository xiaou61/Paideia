---
title: MapperProxy：方法缓存与 default 方法
category: mybatis-source
order: 9
minutes: 20
level: intermediate
status: published
---

## 这一关要读什么

上一关看到代理把调用交给 `cachedInvoker(method)`。这一关把缓存拆开：

- 缓存的是 `Method → MapperMethodInvoker`，**同一个方法只建一次** `MapperMethod`；
- 接口里的 `default` 方法不能走 SQL，要用 `MethodHandle` 直接调；
- 抛出来的异常会被解包，不然你看到的是 `UndeclaredThrowableException`。

```source-walk
{
  "id": "mapper-proxy-cache",
  "title": "methodCache 与 default 方法",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/binding/MapperProxy.java",
      "label": "MapperProxy"
    }
  ],
  "controls": [
    {
      "id": "methodKind",
      "type": "select",
      "label": "方法是什么类型",
      "default": "abstract",
      "options": [
        {
          "value": "abstract",
          "label": "抽象方法（要执行 SQL）"
        },
        {
          "value": "default",
          "label": "default 方法（有实现）"
        }
      ]
    }
  ],
  "steps": [
    {
      "title": "computeIfAbsent：一个方法只装配一次",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 92,
      "lines": 21,
      "explain": "`methodCache` 是 `Map<Method, MapperMethodInvoker>`，用 `computeIfAbsent` 填。`MapperMethod` 的构造要做 SQL 命令解析与签名解析，**不该每次调用都做一遍**。",
      "state": {
        "tables": [
          [
            "methodCache",
            "selectById → PlainMethodInvoker"
          ],
          [
            "命中",
            "第二次起不再构造"
          ]
        ]
      }
    },
    {
      "title": "抽象方法：PlainMethodInvoker + MapperMethod",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 95,
      "lines": 3,
      "explain": "只要不是 default 方法，就 `new MapperMethod(mapperInterface, method, configuration)`，包成 `PlainMethodInvoker`。缓存里放的是这个 invoker。",
      "when": {
        "methodKind": "abstract"
      },
      "state": {
        "vars": [
          [
            "invoker",
            "PlainMethodInvoker"
          ],
          [
            "MapperMethod",
            "每个方法一个"
          ]
        ]
      },
      "predict": {
        "question": "MapperMethod 在什么时候构造？",
        "options": [
          "getMapper 时",
          "第一次调用这个方法时",
          "每次调用"
        ],
        "answer": 1,
        "reveal": "`cachedInvoker` 的 computeIfAbsent 在首次调用时 new。"
      }
    },
    {
      "title": "default 方法：用 MethodHandle 直接调",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 98,
      "lines": 9,
      "explain": "接口的 default 方法不该被翻译成 SQL。Java 8 用 `Lookup` 反射构造 `MethodHandle`，Java 9+ 用 `privateLookupIn`；包成 `DefaultMethodInvoker` 后调用时不经过 SQL 层。",
      "when": {
        "methodKind": "default"
      },
      "state": {
        "vars": [
          [
            "invoker",
            "DefaultMethodInvoker"
          ],
          [
            "底层",
            "MethodHandle.findSpecial"
          ]
        ]
      }
    },
    {
      "title": "Java 9+ 的获取方式",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 114,
      "lines": 7,
      "explain": "`privateLookupIn(declaringClass, MethodHandles.lookup())` 拿到私有查找权限，再 `findSpecial` 找到 default 实现。这段是为了兼容 JDK 版本差异，读的时候知道它在干什么就够。",
      "state": {
        "vars": [
          [
            "版本分支",
            "privateLookupInMethod != null → Java 9+"
          ]
        ]
      }
    },
    {
      "title": "异常解包：别让 UndeclaredThrowable 盖住真因",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 87,
      "lines": 3,
      "explain": "代理里抛的受检异常会被 JDK 包成 `UndeclaredThrowableException`。`ExceptionUtil.unwrapThrowable` 把它剥掉，你看到的才是 `PersistenceException` / `SQLException` 本身。",
      "predict": {
        "question": "Mapper 方法里抛出的异常，为什么在业务层看到的是原始类型？",
        "options": [
          "JDK 代理不包装",
          "MapperProxy 主动解包了",
          "Spring 帮忙处理的"
        ],
        "answer": 1,
        "reveal": "invoke 的 catch 里调用 `ExceptionUtil.unwrapThrowable(t)`，把动态代理的包装层剥掉。"
      },
      "state": {
        "stack": [
          "MapperProxy.invoke",
          "ExceptionUtil.unwrapThrowable"
        ]
      }
    }
  ]
}
```

## 一个容易忽略的点

`methodCache` 的生命周期跟着**工厂**走，不是跟着代理走：`MapperProxyFactory` 持有一个 `Map`，
每次 `newInstance(session)` 把同一个 map 传进去。所以代理换了很多个，方法装配只做一次。

```code-challenge
{
  "id": "ex-methodcache",
  "kind": "fill",
  "title": "补缓存里存的是什么",
  "template": "methodCache 缓存的是 `Method → {{0}}`，同一个方法只构造一次。",
  "options": [
    [
      "MapperMethodInvoker",
      "MappedStatement",
      "MapperProxy"
    ]
  ],
  "answers": [
    0
  ],
  "explain": "缓存的是「怎么执行这个方法」，不是执行结果。"
}
```

```quiz
{
  "id": "proxy-cache-quiz",
  "title": "小测：代理缓存",
  "questions": [
    {
      "question": "methodCache 缓存的是什么？",
      "options": ["方法的返回值", "Method → MapperMethodInvoker", "SQL 文本"],
      "answer": 1,
      "explain": "缓存的是「这个方法该怎么执行」，不是执行结果。"
    },
    {
      "question": "接口里的 default 方法会被当成 SQL 执行吗？",
      "options": ["会", "不会，用 MethodHandle 调实现", "取决于返回值"],
      "answer": 1,
      "explain": "`cachedInvoker` 里 `m.isDefault()` 单独一条分支。"
    },
    {
      "question": "MapperProxy 抛出的原始异常在哪一步被还原？",
      "options": ["MapperMethod.execute", "MapperProxy.invoke 的 catch", "SqlSession"],
      "answer": 1,
      "explain": "`ExceptionUtil.unwrapThrowable(t)` 在代理层就把包装剥了。"
    }
  ]
}
```

下一关：代理把活交给 `MapperMethod`，它怎么知道该调 `selectList` 还是 `insert`？
