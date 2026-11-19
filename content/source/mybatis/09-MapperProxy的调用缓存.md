---
title: MapperProxy：方法缓存与 default 方法
category: mybatis-source
order: 9
minutes: 22
level: intermediate
status: published
---

## 两次 rand() 返回同一个数，methodCache 却只有一条

```java
@Select("select rand()") double rand();

double first = counter.rand();
double second = counter.rand();
System.out.println("rand #1 = " + first);
System.out.println("rand #2 = " + second);
System.out.println("same result? " + (first == second));
System.out.println("cache after rand x2 = " + factory.getMethodCache().size());
```

默认配置（`localCacheScope=SESSION`）的一次运行：

```text
rand #1 = 0.0080858336864702
rand #2 = 0.0080858336864702
same result? true
cache after rand x2 = 1
```

复现第一段时，把完整探针里的 `cfg.setLocalCacheScope(LocalCacheScope.STATEMENT)` 去掉；第二段保留它。

这段来自完整探针，`counter` / `factory` 的初始化在「跑一遍」。我当时的判断：`methodCache` 把结果缓存了。把 `localCacheScope` 改成 `STATEMENT` 再跑：

```text
rand #1 = 0.4504378421378973
rand #2 = 0.39501626764562814
same result? false
cache after rand x2 = 1
```

两次结果不同，`methodCache` 还是 1 条。它缓存的是「方法 → 调用器」，不是 SQL 结果；第一次两次相同，是 MyBatis 的 session 本地缓存（`Configuration.java:125` 默认 `LocalCacheScope.SESSION`）干的。把 scope 调成 `STATEMENT` 就排除了它。`methodCache` 的声明在 `MapperProxyFactory.java:32`：`Map<Method, MapperMethodInvoker>`，底层 `ConcurrentHashMap`。完整探针在「跑一遍」，这里先看关键调用和输出。

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
    },
    {
      "path": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "label": "MapperProxyFactory"
    },
    {
      "path": "org/apache/ibatis/binding/MapperMethod.java",
      "label": "MapperMethod"
    },
    {
      "path": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "label": "DefaultSqlSession"
    },
    {
      "path": "org/apache/ibatis/session/Configuration.java",
      "label": "Configuration"
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
      "title": "工厂字段：Method → 调用器",
      "file": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "line": 32,
      "lines": 1,
      "explain": "`methodCache` 是 `Map<Method, MapperMethodInvoker>`，底层 `ConcurrentHashMap`。key 是 `Method`，value 是调用器（`PlainMethodInvoker` 或 `DefaultMethodInvoker`），不是 SQL 结果。",
      "state": {
        "tables": [
          [
            "key",
            "Method"
          ],
          [
            "value",
            "MapperMethodInvoker"
          ]
        ]
      },
      "predict": {
        "question": "methodCache 缓存的是 SQL 结果吗？",
        "options": [
          "是，缓存返回值",
          "不是，缓存 Method 到调用器",
          "只缓存 SQL 文本"
        ],
        "answer": 1,
        "reveal": "字段类型是 Map<Method, MapperMethodInvoker>；结果缓存是另外的机制。"
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "每个代理共用工厂里的同一份 map",
      "file": "org/apache/ibatis/binding/MapperProxyFactory.java",
      "line": 51,
      "lines": 4,
      "explain": "`newInstance(SqlSession)` 每次都 `new MapperProxy<>(sqlSession, mapperInterface, methodCache)`，把工厂的同一个 map 传进去；代理换了，map 不换。`MapperProxy` 在 `:46` 持有它。",
      "state": {
        "vars": [
          [
            "sqlSession",
            "DefaultSqlSession@1"
          ],
          [
            "mapperInterface",
            "Counter"
          ],
          [
            "methodCache",
            "同一个 ConcurrentHashMap"
          ]
        ]
      },
      "predict": {
        "question": "换一个 SqlSession 再 getMapper，新代理的 methodCache 从哪来？",
        "options": [
          "新的一份空 map",
          "还是工厂里的同一份",
          "每次调用重建"
        ],
        "answer": 1,
        "reveal": "工厂把同一个 map 传给每个新 MapperProxy（MapperProxyFactory:52）。"
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "cachedInvoker：computeIfAbsent 填表",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 92,
      "lines": 21,
      "explain": "`cachedInvoker` 用 `MapUtil.computeIfAbsent(methodCache, method, m -> ...)`（`:94`）填。第一次调用执行 lambda：非 default → `new MapperMethod(...)`（`:96`），包成 `PlainMethodInvoker`；之后命中直接返回。`MapperMethod` 构造要做 `SqlCommand` + `MethodSignature` 解析（`MapperMethod:52-55`），所以只做一次有意义。",
      "state": {
        "tables": [
          [
            "第一次调用",
            "构造 MapperMethod + PlainMethodInvoker"
          ],
          [
            "之后调用",
            "直接返回缓存的 invoker"
          ]
        ]
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "抽象方法 vs default 方法",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 95,
      "lines": 13,
      "explain": "`!m.isDefault()` 是分叉点（`:95`）：抽象方法走 `PlainMethodInvoker`，default 方法走 `DefaultMethodInvoker`（`:98-106`）。default 方法不查 `MappedStatement`，也不执行 SQL。",
      "when": {
        "methodKind": "abstract"
      },
      "state": {
        "vars": [
          [
            "抽象方法",
            "PlainMethodInvoker"
          ],
          [
            "default 方法",
            "DefaultMethodInvoker"
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
        "reveal": "cachedInvoker 的 computeIfAbsent 在首次调用时 new。"
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "PlainMethodInvoker：每次调用都 execute",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 140,
      "lines": 3,
      "explain": "`PlainMethodInvoker.invoke` 只有一行 `mapperMethod.execute(sqlSession, args)`（`:141`）；每次调用都执行。缓存里没有返回值，所以 `rand()` 两次仍会各自走 SQL（前提是本地缓存不拦，见最后一步）。",
      "when": {
        "methodKind": "abstract"
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
    },
    {
      "title": "default 方法：MethodHandle 直接调",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 114,
      "lines": 13,
      "explain": "Java 9+ 用 `privateLookupIn(declaringClass, MethodHandles.lookup())` + `findSpecial`（`:114-120`）；Java 8 用 `Lookup(Class, int)` + `unreflectSpecial`（`:122-126`）。static 块在类加载时探测 `privateLookupIn` 是否存在（`:54-78`），决定走哪条。",
      "when": {
        "methodKind": "default"
      },
      "state": {
        "vars": [
          [
            "版本分支",
            "privateLookupInMethod != null → Java 9+"
          ],
          [
            "底层",
            "MethodHandle.findSpecial"
          ]
        ]
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "异常出口：catch 里剥一层",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 87,
      "lines": 3,
      "explain": "`invoke` 的 catch 在 `:87-89`：`Throwable` → `ExceptionUtil.unwrapThrowable(t)`。它剥的是 `InvocationTargetException` / `UndeclaredThrowableException` 外壳；不是「所有受检异常都变透明」。JDK 代理只看方法签名：声明了 `throws SQLException` 就透传，没声明才包 `UndeclaredThrowableException`。如果剥出来的 `SQLException` 仍未声明，出代理边界还会再包一层。",
      "state": {
        "stack": [
          "MapperProxy.invoke",
          "ExceptionUtil.unwrapThrowable"
        ]
      },
      "predict": {
        "question": "Mapper 方法声明了 throws SQLException，JDK 代理会怎么处理？",
        "options": [
          "照样包成 UndeclaredThrowableException",
          "已声明，直接透传",
          "转成 RuntimeException"
        ],
        "answer": 1,
        "reveal": "JDK 代理只看方法签名；声明了就不会包。"
      },
      "nodes": [
        "proxy"
      ]
    },
    {
      "title": "SQLException 在更外层已被包成 PersistenceException",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "line": 150,
      "lines": 7,
      "explain": "`DefaultSqlSession.selectList` 在 `:150-156` catch `Exception`，`ExceptionFactory.wrapException` 返回 `PersistenceException`。所以常规查询失败到代理层时是运行时异常，`SQLException` 在 cause 里，不是顶层类型。",
      "state": {
        "stack": [
          "DefaultSqlSession.selectList",
          "ExceptionFactory.wrapException",
          "PersistenceException"
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "先排除 session 本地缓存",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 125,
      "lines": 1,
      "explain": "`Configuration.localCacheScope` 默认 `LocalCacheScope.SESSION`（`Configuration.java:125`）。同一个 session、同一条 statement、同样参数，第二次查询可能直接命中本地缓存；这就是开头 `rand()` 两次相同的原因。设成 `STATEMENT` 后每次查询结束清掉，才看到两个不同的随机数。这个缓存和 `methodCache` 是两回事。",
      "state": {
        "tables": [
          [
            "SESSION",
            "同一 session 内可能复用查询结果"
          ],
          [
            "STATEMENT",
            "每次查询结束清理"
          ]
        ]
      },
      "nodes": [
        "config"
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

## 缓存的是调用器，不是结果

`MapperProxyFactory` 持有一个 `Map<Method, MapperMethodInvoker>`（`MapperProxyFactory.java:32`），构造器只保存接口类型（`:34-36`）。`newInstance(SqlSession)` 每次创建新的 `MapperProxy`，但把同一个 map 传进去（`:52`）；`MapperProxy` 的字段在 `MapperProxy.java:46`，构造器在 `:48-51`。所以代理的生命周期跟 session 走，缓存的寿命跟工厂走；`Configuration` 不重建，工厂和 map 就不重建。

`cachedInvoker` 的填充点是 `MapUtil.computeIfAbsent(methodCache, method, m -> ...)`（`MapperProxy.java:94`）。注意它不是直接调 `methodCache.computeIfAbsent`，而是经过 `MapUtil`；底层 map 是 `ConcurrentHashMap`（`MapperProxyFactory.java:32`）。lambda 只在 key 缺失时执行：抽象方法构造 `new MapperMethod(mapperInterface, method, sqlSession.getConfiguration())`（`:96`），包成 `PlainMethodInvoker`。第二次调用同一个方法，`computeIfAbsent` 直接返回已有的 invoker，不会再构造 `MapperMethod`。

`MapperMethod` 的构造器做两件事：`new SqlCommand(...)` 和 `new MethodSignature(...)`（`MapperMethod.java:52-55`），前者要解析 statement，后者要解析返回值、参数、`RowBounds` / `ResultHandler` 位置。这些结果对同一个 `Method` 不变，所以放在工厂级缓存里。

它不是结果缓存：`PlainMethodInvoker.invoke` 每次都调 `mapperMethod.execute(sqlSession, args)`（`MapperProxy.java:140-142`），没有把返回值写回 map。`getMethodCache()` 是 public 的（`MapperProxyFactory.java:42-44`），探针才能直接看 key/value；但 value 类型 `MapperMethodInvoker` 是包私有的（`MapperProxy.java:128`），包外只能写成 `Map<?, ?>`。

## default 方法不经过 SQL

分叉在 `!m.isDefault()`（`MapperProxy.java:95`）：抽象方法走 `PlainMethodInvoker`，default 方法走 `DefaultMethodInvoker`（`:98-106`）。default 方法在注解解析阶段也会被跳过：`MapperAnnotationBuilder.canHaveStatement` 返回 `!method.isBridge() && !method.isDefault()`（`MapperAnnotationBuilder.java:140-143`），所以它不会因为找不到 SQL 而在 `addMapper` 时失败。

Java 9+ 的取法是 `privateLookupIn(declaringClass, MethodHandles.lookup())` 再 `findSpecial`（`MapperProxy.java:114-120`）；Java 8 走 `Lookup(Class, int)` 再 `unreflectSpecial`（`:122-126`）。static 块在类加载时探测 `privateLookupIn` 是否存在（`:54-78`），据此选分支。`DefaultMethodInvoker.invoke` 最后是 `methodHandle.bindTo(proxy).invokeWithArguments(args)`（`:153-155`），不碰 `SqlSession`。

探针里 `tag()` 返回 `default-ok`，缓存里的 value 是 `MapperProxy$DefaultMethodInvoker`；同一个工厂里另一个 key `rand` 的 value 是 `MapperProxy$PlainMethodInvoker`。两种调用器在同一个 map 里，靠方法类型区分。

## 异常：声明与未声明不一样

我上一版写的是「代理里抛的受检异常都会被 JDK 包成 `UndeclaredThrowableException`，`ExceptionUtil.unwrapThrowable` 剥掉后你看到的就是 `SQLException` 本身」。这两句都不准确。

JDK 动态代理的规则是看接口方法的签名：方法声明了 `throws SQLException`，`SQLException` 就原样透传；没声明，才会在代理边界被包成 `UndeclaredThrowableException`；`RuntimeException` / `Error` 不受这个包装影响。

`MapperProxy.invoke` 的 catch 在 `MapperProxy.java:87-89`，`ExceptionUtil.unwrapThrowable(t)` 在 `:88`。它循环剥 `InvocationTargetException` 和 `UndeclaredThrowableException` 两种外壳（实现不在本快照，行为用 3.5.19 的 jar 核对过）。但剥完不等于结束：如果剥出来的是 `SQLException`，而接口方法仍没声明它，`invoke` 把异常抛出代理边界时，JDK 代理还会再包一层 `UndeclaredThrowableException`。所以「解包后就能看到 SQLException 本身」只在方法声明了它时成立。

常规查询路径更早一步：`DefaultSqlSession.selectList` 在 `:150-156` catch `Exception`，`ExceptionFactory.wrapException` 返回 `PersistenceException`。到 `MapperProxy` 时它是运行时异常，不会被 JDK 代理包；`SQLException` 在 cause 里。探针里那条坏 SQL 打出来是：

```text
bad query -> org.apache.ibatis.exceptions.PersistenceException
bad query cause -> org.h2.jdbc.JdbcSQLSyntaxErrorException
```

`JdbcSQLSyntaxErrorException` 是 H2 的 `SQLException` 子类。也就是说，业务层看到的顶层类型是 `PersistenceException`，不是 `SQLException` 本身。

声明与未声明的对照，探针输出是：

```text
declared checked -> java.io.IOException / declared-io
undeclared checked -> java.lang.reflect.UndeclaredThrowableException
undeclared cause -> java.io.IOException / undeclared-io
```

`declared()` 在接口里声明了 `throws IOException`，所以直接透传；`undeclared()` 用 sneaky throw 丢出 `IOException` 但签名没声明，`MapperProxy` 剥完一层后，出代理边界又被包成 `UndeclaredThrowableException`，cause 才是 `IOException`。

## 跑一遍

完整探针（JDK 25.0.4 / MyBatis 3.5.19 / H2 2.4.240，classpath 含 `mybatis-3.5.19.jar` 与 `h2-2.4.240.jar`）：

```java
package probe;

import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.binding.MapperProxyFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

public class Probe09 {
  interface Counter {
    @Select("select rand()") double rand();
    default String tag() { return "default-ok"; }
  }

  interface BadMapper {
    @Select("select * from no_such_table") int bad();
  }

  interface CheckedMapper {
    default String declared() throws java.io.IOException {
      throw new java.io.IOException("declared-io");
    }

    default String undeclared() {
      return sneaky(new java.io.IOException("undeclared-io"));
    }

    @SuppressWarnings("unchecked")
    static <E extends Throwable> String sneaky(Throwable t) throws E {
      throw (E) t;
    }
  }

  public static void main(String[] args) {
    Environment env = new Environment("probe", new JdbcTransactionFactory(),
        new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:p9;DB_CLOSE_DELAY=-1", "sa", ""));
    Configuration cfg = new Configuration(env);
    cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);
    cfg.addMapper(Counter.class);
    cfg.addMapper(BadMapper.class);

    try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession()) {
      MapperProxyFactory<Counter> factory = new MapperProxyFactory<>(Counter.class);
      Counter counter = factory.newInstance(session);
      System.out.println("cache before = " + factory.getMethodCache().size());
      double first = counter.rand();
      double second = counter.rand();
      System.out.println("rand #1 = " + first);
      System.out.println("rand #2 = " + second);
      System.out.println("same result? " + (first == second));
      System.out.println("cache after rand x2 = " + factory.getMethodCache().size());
      System.out.println("tag() = " + counter.tag());
      System.out.println("cache after tag = " + factory.getMethodCache().size());
      Map<?, ?> cache = factory.getMethodCache();
      cache.forEach((k, v) -> System.out.println("  " + ((java.lang.reflect.Method) k).getName()
          + " -> " + v.getClass().getName()));

      try {
        BadMapper bad = session.getMapper(BadMapper.class);
        bad.bad();
      } catch (Throwable t) {
        System.out.println("bad query -> " + t.getClass().getName());
        System.out.println("bad query cause -> " + t.getCause().getClass().getName());
      }

      // CheckedMapper 不注册：两个方法都是 default，不需要 MappedStatement
      MapperProxyFactory<CheckedMapper> checkedFactory = new MapperProxyFactory<>(CheckedMapper.class);
      CheckedMapper checked = checkedFactory.newInstance(session);
      try {
        checked.declared();
      } catch (Throwable t) {
        System.out.println("declared checked -> " + t.getClass().getName() + " / " + t.getMessage());
      }
      try {
        checked.undeclared();
      } catch (Throwable t) {
        System.out.println("undeclared checked -> " + t.getClass().getName());
        System.out.println("undeclared cause -> " + t.getCause().getClass().getName() + " / " + t.getCause().getMessage());
      }
    }
  }
}
```

一次运行的输出（随机数和 `@` 后的 hash 每次会变，缓存两行的顺序也不保证）：

```text
cache before = 0
rand #1 = 0.4504378421378973
rand #2 = 0.39501626764562814
same result? false
cache after rand x2 = 1
tag() = default-ok
cache after tag = 2
  tag -> org.apache.ibatis.binding.MapperProxy$DefaultMethodInvoker
  rand -> org.apache.ibatis.binding.MapperProxy$PlainMethodInvoker
bad query -> org.apache.ibatis.exceptions.PersistenceException
bad query cause -> org.h2.jdbc.JdbcSQLSyntaxErrorException
declared checked -> java.io.IOException / declared-io
undeclared checked -> java.lang.reflect.UndeclaredThrowableException
undeclared cause -> java.io.IOException / undeclared-io
```

## 动手

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
  "explain": "value 是 MapperMethodInvoker（PlainMethodInvoker 或 DefaultMethodInvoker），不是 SQL 结果。"
}
```

## 检查一下

```quiz
{
  "id": "proxy-cache-quiz",
  "title": "小测：代理缓存",
  "questions": [
    {
      "question": "methodCache 的 key 和 value 是什么？",
      "options": ["SQL 文本 → 结果", "Mapper 接口 → 代理", "Method → MapperMethodInvoker"],
      "answer": 2,
      "explain": "字段声明在 MapperProxyFactory:32；value 是调用器，不是返回值。"
    },
    {
      "question": "同一个 MapperProxyFactory 造出的两个代理，methodCache 怎么分？",
      "options": ["共用工厂里的同一份", "各自一份", "每次调用重建"],
      "answer": 0,
      "explain": "newInstance 把工厂的 methodCache 传给每个 MapperProxy（MapperProxyFactory:52）。"
    },
    {
      "question": "Mapper 方法抛受检异常时，哪种说法对？",
      "options": ["所有受检异常都会被包成 UndeclaredThrowableException", "已声明的透传，未声明的才可能被包；解包后出代理边界还可能再包", "MyBatis 把所有 SQLException 都转成 UndeclaredThrowableException"],
      "answer": 1,
      "explain": "JDK 代理看方法签名；MapperProxy:87-89 只剥一层，不改变声明。"
    }
  ]
}
```

## 还差的

- 没在 Java 8 上跑 `getMethodHandleJava8`；本机 JDK 25 只走了 `privateLookupIn` 分支。
- 没展开 session 本地缓存和二级缓存的完整行为；只用一个 `rand()` 探针把它和 `methodCache` 分开。
- 没讲 `MapUtil.computeIfAbsent` 的内部实现（是否处理递归更新、并发首次调用）；只确认调用点在 `MapperProxy:94`。
- 没覆盖 default 方法内部调用同接口抽象方法时的代理行为；那是另一条调用链。
- 没展开 `MapperMethod` 的 `SqlCommand` 和 `MethodSignature` 解析，下一篇再讲。
