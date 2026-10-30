---
title: 接口怎么变成对象：Registry、Factory 与代理
category: mybatis-source
order: 8
minutes: 25
level: intermediate
status: published
---

## 这一关要读什么

你写了一个接口，`sqlSession.getMapper(UserMapper.class)` 之后就能调。中间没有任何实现类。

这一关只看**对象是怎么被造出来的**：注册表存工厂，工厂造代理。

```source-walk
{
  "id": "mapper-registry-proxy",
  "title": "从 getMapper 到动态代理",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/binding/MapperRegistry.java", "label": "MapperRegistry" },
    { "path": "org/apache/ibatis/binding/MapperProxyFactory.java", "label": "MapperProxyFactory" },
    { "path": "org/apache/ibatis/binding/MapperProxy.java", "label": "MapperProxy" }
  ],
  "controls": [
    {
      "id": "target",
      "type": "select",
      "label": "被调用的方法属于谁",
      "default": "mapper",
      "options": [
        { "value": "mapper", "label": "你自己的 Mapper 方法" },
        { "value": "object", "label": "toString / equals / hashCode" }
      ]
    }
  ],
  "steps": [
    {
      "title": "getMapper：查表，没有就报错",
      "file": "org/apache/ibatis/binding/MapperRegistry.java",
      "line": 44,
      "lines": 11,
      "explain": "`knownMappers.get(type)` 拿到的是 `MapperProxyFactory`，不是对象本身。没注册过就抛 `BindingException`——「Type xxx is not known to the MapperRegistry」这句就是这儿来的。",
      "state": {
        "tables": [["knownMappers", "UserMapper → MapperProxyFactory"], ["返回", "代理对象"]]
      }
    },
    {
      "title": "Factory：每次 getMapper 造一个新的代理",
      "file": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "line": 51,
      "lines": 4,
      "explain": "`new MapperProxy<>(sqlSession, mapperInterface, methodCache)` 把**当前 SqlSession** 塞进 handler。所以同一个 Mapper 在不同 session 里是两个不同的代理对象——这也是为什么 Mapper 不能跨 session 复用。",
      "state": {
        "vars": [["sqlSession", "DefaultSqlSession@1"], ["mapperInterface", "UserMapper"]]
      }
    },
    {
      "title": "Proxy.newProxyInstance：真正的造对象",
      "file": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "line": 46,
      "lines": 3,
      "explain": "JDK 动态代理只要求一个接口 + 一个 `InvocationHandler`。`MapperProxy` 就是那个 handler。",
      "predict": {
        "question": "为什么不用 CGLIB 生成子类？",
        "options": ["因为 Mapper 是接口，JDK 代理够用", "因为 CGLIB 性能差", "因为 CGLIB 不支持 Java 25"],
        "answer": 0,
        "reveal": "Mapper 本来就是接口，JDK 代理零依赖、无需字节码库。MyBatis 只在延迟加载那种**类**的场景才用 Javassist/CGLIB。"
      },
      "state": {
        "vars": [["interfaces", "[UserMapper]"], ["handler", "MapperProxy"]]
      }
    },
    {
      "title": "invoke 的第一道判断：Object 的方法",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 81,
      "lines": 10,
      "explain": "`toString` / `equals` / `hashCode` 的声明类是 `Object`，不能当成 SQL 去执行——直接 `method.invoke(this, args)`，作用在 handler 自己身上。**没有这段，打印一个 Mapper 代理就会去查库**。",
      "state": {
        "stack": ["MapperProxy.invoke", "Object.equals"],
        "vars": [["method", "toString()"]]
      }
    },
    {
      "title": "Mapper 方法走 cachedInvoker",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 86,
      "lines": 1,
      "explain": "其余方法先按 `Method` 查缓存，拿到一个 invoker 再执行。缓存的是「方法 → 怎么执行」，不是返回值。",
      "when": { "target": "mapper" },
      "state": {
        "tables": [["methodCache", "selectById → PlainMethodInvoker"]]
      }
    },
    {
      "title": "Object 方法不查表",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 83,
      "lines": 3,
      "explain": "这条分支在缓存之前就返回了，所以 `methodCache` 里永远不会有 `toString`。",
      "when": { "target": "object" },
      "state": {
        "tables": [["methodCache", "不记录 Object 方法"]]
      }
    },
    {
      "title": "PlainMethodInvoker：最后还是 execute",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 140,
      "lines": 4,
      "explain": "抽象方法的 invoker 只有一行：`mapperMethod.execute(sqlSession, args)`。到这儿，代理的活干完了，剩下的交给 `MapperMethod`。",
      "state": {
        "stack": ["MapperProxy.invoke", "PlainMethodInvoker.invoke", "MapperMethod.execute"]
      }
    }
  ]
}
```

## 为什么每次 getMapper 都 new 一个代理

代理持有 `sqlSession`。`SqlSession` 不是线程安全的，生命周期通常是一次请求；把代理缓存起来跨 session 用，
就会拿旧 session 去执行新请求的 SQL。**代理本身很轻**，建一个的成本远小于一个连接。

```quiz
{
  "id": "registry-quiz",
  "title": "小测：代理对象",
  "questions": [
    {
      "question": "knownMappers 里存的是什么？",
      "options": ["Mapper 实例", "MapperProxyFactory", "MappedStatement"],
      "answer": 1,
      "explain": "注册表存工厂，工厂再按 SqlSession 造代理。"
    },
    {
      "question": "为什么 toString 不会触发 SQL？",
      "options": ["因为 Object 方法被特殊处理，直接 method.invoke(this)", "因为 toString 不在接口里", "因为缓存"],
      "answer": 0,
      "explain": "`invoke` 第一段就判断声明类是不是 Object。"
    },
    {
      "question": "同一个 Mapper 接口，在两个 SqlSession 里 getMapper 得到的对象？",
      "options": ["同一个", "两个不同的代理，各自持有自己的 session", "一个代理共享 session"],
      "answer": 1,
      "explain": "每次 newInstance 都新建 MapperProxy，并把传入的 session 塞进去。"
    }
  ]
}
```

下一关：一个代理对象被调一万次，`methodCache` 到底缓存了什么，default 方法又怎么处理。
