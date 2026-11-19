---
title: 接口怎么变成对象：Registry、Factory 与代理
category: mybatis-source
order: 8
minutes: 21
level: intermediate
status: published
---

## 打印出来的是 MapperProxy，不是 UserMapper

```java
UserMapper a = session.getMapper(UserMapper.class);
UserMapper b = session.getMapper(UserMapper.class);
System.out.println(a.getClass().getName());
System.out.println(a == b);
System.out.println(a);
```

JDK 25.0.4 / MyBatis 3.5.19 / H2 2.4.240，接口放在 `probe` 包，输出：

```text
probe.$Proxy5
false
org.apache.ibatis.binding.MapperProxy@21bcffb5
```

这段输出和上面的调用来自完整探针，`session` 的初始化在「跑一遍」。`a` 不是 `UserMapper` 的实现类，是 JDK `Proxy` 生成的代理；`a == b` 是 false；`println` 打出来的是 `MapperProxy` 的 `toString`。我原来以为注册表里存的是代理对象，`getMapper` 只是把它取出来；实际存的是工厂，工厂每次按当前 `SqlSession` 造一个新代理。`@` 后面的 hash 每次运行会变，类名里的 `$ProxyN` 也会随项目变，`MapperProxy` 这一段不变。

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
    {
      "path": "org/apache/ibatis/binding/MapperRegistry.java",
      "label": "MapperRegistry"
    },
    {
      "path": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "label": "MapperProxyFactory"
    },
    {
      "path": "org/apache/ibatis/binding/MapperProxy.java",
      "label": "MapperProxy"
    },
    {
      "path": "org/apache/ibatis/binding/MapperMethod.java",
      "label": "MapperMethod"
    }
  ],
  "controls": [
    {
      "id": "target",
      "type": "select",
      "label": "被调用的方法属于谁",
      "default": "mapper",
      "options": [
        {
          "value": "mapper",
          "label": "你自己的 Mapper 方法"
        },
        {
          "value": "object",
          "label": "toString / equals / hashCode"
        }
      ]
    }
  ],
  "steps": [
    {
      "title": "getMapper：查表拿工厂",
      "file": "org/apache/ibatis/binding/MapperRegistry.java",
      "line": 44,
      "lines": 11,
      "explain": "`knownMappers.get(type)` 拿到的是 `MapperProxyFactory`，不是对象本身。没注册过就抛 `BindingException`，消息是「Type ... is not known to the MapperRegistry」；拿到了就 `newInstance(sqlSession)`。",
      "state": {
        "tables": [
          [
            "knownMappers",
            "UserMapper → MapperProxyFactory"
          ],
          [
            "返回",
            "新的代理对象"
          ]
        ]
      },
      "predict": {
        "question": "同一个接口两次 getMapper 得到？",
        "options": [
          "同一个代理",
          "两个新代理对象"
        ],
        "answer": 1,
        "reveal": "每次 newInstance 都新建 MapperProxy，并塞入当前 session。"
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "addMapper：重复注册直接抛",
      "file": "org/apache/ibatis/binding/MapperRegistry.java",
      "line": 60,
      "lines": 8,
      "explain": "`addMapper` 只处理接口（`:61`）。接口已经注册过，`hasMapper` 为 true，直接抛 `BindingException`，消息是「Type ... is already known to the MapperRegistry」（`:62-63`）；不是覆盖旧工厂。",
      "state": {
        "tables": [
          [
            "第一次 addMapper",
            "knownMappers.put(UserMapper, factory)"
          ],
          [
            "第二次 addMapper",
            "BindingException: already known"
          ]
        ]
      },
      "predict": {
        "question": "同一个接口第二次 addMapper 会怎样？",
        "options": [
          "覆盖旧工厂",
          "抛 BindingException",
          "静默忽略"
        ],
        "answer": 1,
        "reveal": "hasMapper 在 put 之前检查，重复注册直接失败。"
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "addMapper：parse 失败就移除",
      "file": "org/apache/ibatis/binding/MapperRegistry.java",
      "line": 73,
      "lines": 6,
      "explain": "先把工厂 `put` 进表（`:67`），再跑 `MapperAnnotationBuilder.parse()`（`:71-72`）。注释在 `:68-70` 说明顺序：parser 可能递归问注册表，类型已在表里就不会再尝试绑定。parse 失败时 `loadCompleted` 还是 false，`finally` 移除条目（`:74-77`），不留下半个工厂。",
      "state": {
        "tables": [
          [
            "parse 成功",
            "loadCompleted = true，保留工厂"
          ],
          [
            "parse 抛异常",
            "finally 移除 knownMappers 里的条目"
          ]
        ]
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "Factory：每次 getMapper 造新代理",
      "file": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "line": 51,
      "lines": 4,
      "explain": "`new MapperProxy<>(sqlSession, mapperInterface, methodCache)` 把当前 session 塞进 handler；然后 `newInstance(mapperProxy)` 调 `Proxy.newProxyInstance`。同一个接口在不同 session 里是两个代理，各自持有自己的 session。",
      "state": {
        "vars": [
          [
            "sqlSession",
            "DefaultSqlSession@1"
          ],
          [
            "mapperInterface",
            "UserMapper"
          ],
          [
            "methodCache",
            "工厂里的同一份 map"
          ]
        ]
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "Proxy.newProxyInstance：真正的造对象",
      "file": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "line": 47,
      "lines": 2,
      "explain": "JDK 动态代理只要求一个接口 + 一个 `InvocationHandler`。`MapperProxy` 就是 handler；`mapperInterface.getClassLoader()` 和 `new Class[] { mapperInterface }` 决定代理实现哪个接口。",
      "predict": {
        "question": "Mapper 为什么能用 JDK 代理？",
        "options": [
          "Mapper 是接口，JDK 代理够用",
          "JDK 代理性能更好",
          "接口不能生成子类"
        ],
        "answer": 0,
        "reveal": "JDK 代理要求接口 + InvocationHandler；Mapper 正好是接口。"
      },
      "state": {
        "vars": [
          [
            "interfaces",
            "[UserMapper]"
          ],
          [
            "handler",
            "MapperProxy"
          ]
        ]
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "invoke 的第一道判断：Object 的方法",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 81,
      "lines": 10,
      "explain": "`toString` / `equals` / `hashCode` 的声明类是 `Object`，不能当 SQL 执行——直接 `method.invoke(this, args)`，作用在 handler 自己身上。所以 `println(mapper)` 打的是 `MapperProxy@...`。没有这段也不会去查库：`toString` 会被当成普通 Mapper 方法，`MapperMethod` 构造时找不到语句，抛 `Invalid bound statement`（下一步）。",
      "when": {
        "target": "object"
      },
      "state": {
        "stack": [
          "MapperProxy.invoke",
          "Object.toString"
        ],
        "vars": [
          [
            "method",
            "toString()"
          ]
        ]
      },
      "predict": {
        "question": "println(mapper) 打印什么？",
        "options": [
          "MapperProxy@...",
          "UserMapper@...",
          "SQL 查询结果"
        ],
        "answer": 0,
        "reveal": "Object 分支直接 method.invoke(this, args)，打的是 handler 的 toString。"
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "MapperMethod：找不到语句就抛",
      "file": "org/apache/ibatis/binding/MapperMethod.java",
      "line": 225,
      "lines": 5,
      "explain": "`SqlCommand` 用 `mapperInterface.getName() + '.' + methodName` 拼 statement id。`UserMapper.toString` 没有对应 `MappedStatement`，`ms == null`，且方法上没有 `@Flush`，直接抛 `BindingException: Invalid bound statement (not found)`。这发生在 `MapperMethod` 构造阶段（`MapperMethod:52-55`），`execute` 还没开始，所以不查库。",
      "when": {
        "target": "object"
      },
      "state": {
        "tables": [
          [
            "查找",
            "UserMapper.toString"
          ],
          [
            "结果",
            "BindingException，不执行 SQL"
          ]
        ]
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "PlainMethodInvoker：最后还是 execute",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 140,
      "lines": 4,
      "explain": "抽象方法的 invoker 只有一行：`mapperMethod.execute(sqlSession, args)`。到这儿代理的活干完了，剩下的交给 `MapperMethod`。",
      "when": {
        "target": "mapper"
      },
      "state": {
        "stack": [
          "MapperProxy.invoke",
          "PlainMethodInvoker.invoke",
          "MapperMethod.execute"
        ]
      },
      "nodes": [
        "proxy"
      ]
    }
  ],
  "map": {
    "title": "MyBatis 主干",
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
        "sub": "反射/类型/缓存/插件",
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
    ]
  }
}
```

## 注册表里没有代理

`MapperRegistry` 的字段是 `Map<Class<?>, MapperProxyFactory<?>>`（`MapperRegistry.java:37`），不是 `Map<Class<?>, Object>`。`getMapper` 先从 `knownMappers` 取工厂（`:45`），取不到就抛 `BindingException`（`:47`），取到就 `mapperProxyFactory.newInstance(sqlSession)`（`:50`）。

工厂的 `newInstance(SqlSession)` 只做两件事：`new MapperProxy<>(sqlSession, mapperInterface, methodCache)`（`MapperProxyFactory.java:52`），再 `Proxy.newProxyInstance(...)`（`:48`）。`MapperProxy` 把 `sqlSession` 存成字段（`MapperProxy.java:44`）。所以代理和 session 是一对一绑定；同一接口在两个 session 里 `getMapper`，得到两个代理，各自拿着自己的 session。`DefaultSqlSession.getMapper` 传的就是 `this`（`DefaultSqlSession.java:290-291`），`Configuration.getMapper` 只是转发（`Configuration.java:946-947`）。

`getMapper` 外面还有一层 try/catch（`MapperRegistry.java:49-53`）：工厂造代理时抛的任何 `Exception` 都会被包成 `BindingException`，消息是 `Error getting mapper instance. Cause: ...`。

为什么每次都 new？`DefaultSqlSession` 持有 `executor`、`dirty`、`cursorList` 这些状态（`DefaultSqlSession.java:50-54`），其中 `dirty` / `cursorList` 会随调用变化，不适合跨线程或跨请求共享。代理把 session 存成字段，缓存代理就等于缓存 session。新建代理不建立连接：它先 new 一个 handler，再让 JDK 生成或复用代理类；连接在 executor / transaction 那层。

## addMapper 不是幂等的

`Configuration.addMapper` 直接转给注册表（`Configuration.java:942-943`）。注册表先看 `type.isInterface()`（`MapperRegistry.java:61`）；传 class 进来不会报错，也不会注册，后面 `getMapper` 才报 not known。

接口已经注册过，`hasMapper` 为 true，直接抛 `BindingException: Type ... is already known to the MapperRegistry`（`:62-63`）。所以重复扫描同一批 Mapper（比如两个配置都调 `addMapper`）不是覆盖，是启动失败。

第一次注册时，它先把 `new MapperProxyFactory<>(type)` 放进 `knownMappers`（`:67`），再跑 `MapperAnnotationBuilder.parse()`（`:71-72`）。顺序是故意的：注释在 `:68-70` 说明，parser 可能会递归地问注册表；类型已经在表里，它就不会再尝试绑定。

如果 parse 抛出未被内部捕获的异常，`loadCompleted` 还是 false（`:65`），`finally` 把它从 `knownMappers` 移除（`:74-77`）。这样不会留下一个「注册表里有、但语句没解析完」的工厂。要注意它只清理注册表：`MapperAnnotationBuilder.parse()` 是边解析边把 `MappedStatement` 写进 `Configuration`（`MapperAnnotationBuilder.java:122-137`、`:349-355`），中途失败时已经写进去的语句不在这个 finally 的清理范围。

## Object 方法：不查库，但也不是业务方法

`MapperProxy.invoke` 第一段判断 `Object.class.equals(method.getDeclaringClass())`（`MapperProxy.java:83`），是就 `method.invoke(this, args)`（`:84`），作用在 handler 自己身上。所以 `println(mapper)` 打出来是 `org.apache.ibatis.binding.MapperProxy@...`，不是 SQL 结果。

我上一版写的是「没有这段，打印代理就会去查库」，这是错的。没有这段，`toString` 会被当成普通 Mapper 方法交给 `cachedInvoker`（`:86`），构造 `MapperMethod` 时 `SqlCommand` 会去 `Configuration` 找 `UserMapper.toString`；找不到就抛 `BindingException: Invalid bound statement (not found)`（`MapperMethod.java:225-229`）。位置在 `MapperMethod` 构造阶段，`MapperMethod.execute`（`MapperMethod.java:57`）还没开始，所以不会查库。

探针里一个没有注解的 `int unbound()` 走的是同一条路径：`Invalid bound statement (not found): probe.Probe08$UserMapper.unbound`。`toString` 只是方法名换成 `toString`，失败点一样。

顺带一个副作用：`equals` / `hashCode` 也走 `method.invoke(this, args)`，所以 `proxy.equals(proxy)` 实际比较的是 handler 和 proxy，输出是 false；把 Mapper 代理当 `HashSet` 元素或 `HashMap` key 会得到反直觉的结果。要比较代理，用 `==`。

## 跑一遍

完整探针（JDK 25.0.4 / MyBatis 3.5.19 / H2 2.4.240，classpath 含 `mybatis-3.5.19.jar` 与 `h2-2.4.240.jar`）：

```java
package probe;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

public class Probe08 {
  interface UserMapper {
    @Select("select 1") int one();
    int unbound();
  }

  public static void main(String[] args) {
    Configuration bare = new Configuration();
    bare.addMapper(UserMapper.class);
    try {
      bare.addMapper(UserMapper.class);
    } catch (BindingException e) {
      System.out.println("addMapper twice -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    Environment env = new Environment("probe", new JdbcTransactionFactory(),
        new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:p8b;DB_CLOSE_DELAY=-1", "sa", ""));
    Configuration cfg = new Configuration(env);
    cfg.addMapper(UserMapper.class);

    try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession()) {
      UserMapper a = session.getMapper(UserMapper.class);
      UserMapper b = session.getMapper(UserMapper.class);
      System.out.println("proxy class = " + a.getClass().getName());
      System.out.println("a == b -> " + (a == b));
      System.out.println("println(proxy) -> " + a);
      System.out.println("a.equals(a) -> " + a.equals(a));
      System.out.println("one() -> " + a.one());
      try {
        a.unbound();
      } catch (Throwable t) {
        System.out.println("unbound method -> " + t.getClass().getName() + ": " + t.getMessage());
      }
    }
  }
}
```

一次运行的输出：

```text
addMapper twice -> BindingException: Type interface probe.Probe08$UserMapper is already known to the MapperRegistry.
proxy class = probe.$Proxy5
a == b -> false
println(proxy) -> org.apache.ibatis.binding.MapperProxy@21bcffb5
a.equals(a) -> false
one() -> 1
unbound method -> org.apache.ibatis.binding.BindingException: Invalid bound statement (not found): probe.Probe08$UserMapper.unbound
```

## 动手

```code-challenge
{
  "id": "ex-proxy",
  "kind": "order",
  "title": "排出 getMapper 的四步",
  "lines": [
    "调用 mapper 方法时，MapperProxy.invoke 拦截",
    "MapperRegistry.getMapper(type, session)",
    "Proxy.newProxyInstance 生成代理",
    "MapperProxyFactory.newInstance(session)"
  ],
  "answer": [
    1,
    3,
    2,
    0
  ],
  "explain": "先查注册表拿工厂，工厂 new 出 handler，再 newProxyInstance；调用方法时才轮到 invoke。"
}
```

## 检查一下

```quiz
{
  "id": "registry-quiz",
  "title": "小测：代理对象",
  "questions": [
    {
      "question": "knownMappers 里存的是什么？",
      "options": ["代理对象", "MapperMethod", "MapperProxyFactory"],
      "answer": 2,
      "explain": "字段类型是 Map<Class<?>, MapperProxyFactory<?>>（MapperRegistry:37）。"
    },
    {
      "question": "同一个接口第二次 addMapper 会怎样？",
      "options": ["抛 BindingException（already known）", "覆盖旧的工厂", "静默返回"],
      "answer": 0,
      "explain": "hasMapper 为 true 时直接抛（MapperRegistry:62-63）。"
    },
    {
      "question": "如果没有 Object 分支，打印代理会怎样？",
      "options": ["查库并返回结果", "在 MapperMethod 构造时抛 Invalid bound statement，不查库", "返回 null"],
      "answer": 1,
      "explain": "找不到 UserMapper.toString 对应的语句，MapperMethod:225-229 直接抛。"
    }
  ]
}
```

## 还差的

- 没讲 XML `<mapper>`、`@MapperScan` 或 Spring 是怎么调到 `addMapper` 的；这篇从 `Configuration.addMapper` 开始。
- 没测并发 `addMapper` 的竞态：`hasMapper`（`MapperRegistry.java:62`）和 `put`（`:67`）是两步，不是原子操作。
- 没验证 parse 中途失败时 `Configuration` 里已写入的 `MappedStatement` 如何清理；`MapperRegistry` 的 `finally` 只移除工厂。
- 没量 `getMapper` / JDK 代理的分配成本；这篇只说每次新建代理，没有数据。