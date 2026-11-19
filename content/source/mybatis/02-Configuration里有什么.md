---
title: Configuration：一个对象装下了整个 MyBatis
category: mybatis-source
order: 2
minutes: 24
level: beginner
status: published
---

版本：MyBatis 3.5.19，git revision ee0d4f4；行号核对日期 2026-11-19。下面所有 `文件:行号` 都指这个版本。

## openSession 之后不是 SimpleExecutor

只建 `Configuration`、`openSession`，不执行 SQL；用反射把 `DefaultSqlSession` 的 private `executor` 拿出来打印类名：

```java
import java.lang.reflect.Field;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

public class Probe {
  static Executor executorOf(SqlSession session) throws Exception {
    Field field = DefaultSqlSession.class.getDeclaredField("executor");
    field.setAccessible(true);
    return (Executor) field.get(session);
  }

  public static void main(String[] args) throws Exception {
    Configuration c = new Configuration();
    c.setEnvironment(new Environment("dev", new JdbcTransactionFactory(), new UnpooledDataSource()));
    DefaultSqlSessionFactory f = new DefaultSqlSessionFactory(c);

    try (SqlSession s = f.openSession()) {
      System.out.println("cacheEnabled=true  openSession()          -> " + executorOf(s).getClass().getName());
    }
    try (SqlSession s = f.openSession(ExecutorType.BATCH)) {
      System.out.println("cacheEnabled=true  openSession(BATCH)     -> " + executorOf(s).getClass().getName());
    }

    c.setCacheEnabled(false);
    try (SqlSession s = f.openSession()) {
      System.out.println("cacheEnabled=false openSession()          -> " + executorOf(s).getClass().getName());
    }
    try (SqlSession s = f.openSession(ExecutorType.BATCH)) {
      System.out.println("cacheEnabled=false openSession(BATCH)     -> " + executorOf(s).getClass().getName());
    }
  }
}
```

JDK 25 + `mybatis-3.5.19.jar` 的实测输出：

```text
cacheEnabled=true  openSession()          -> org.apache.ibatis.executor.CachingExecutor
cacheEnabled=true  openSession(BATCH)     -> org.apache.ibatis.executor.CachingExecutor
cacheEnabled=false openSession()          -> org.apache.ibatis.executor.SimpleExecutor
cacheEnabled=false openSession(BATCH)     -> org.apache.ibatis.executor.BatchExecutor
```

我原来以为 `defaultExecutorType=SIMPLE`（`Configuration.java:132`）就意味着对象是 `SimpleExecutor`。实际 `newExecutor`:735-749 先按类型造基础执行器，738-743 行选出 `SimpleExecutor`，745-747 行再在 `cacheEnabled=true`（113 行）时套一层 `CachingExecutor`。所以默认拿到的是 `CachingExecutor(SimpleExecutor)`，`cacheEnabled=false` 才是裸的。

## 三组字段：开关、注册表、映射表

`Configuration.java:105-183` 有 56 个字段声明。全看会劝退，按行为分三组就够了。

```source-walk
{
  "id": "configuration-fields",
  "title": "Configuration 里真正重要的三类字段",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/session/Configuration.java",
      "label": "Configuration"
    },
    {
      "path": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "label": "DefaultSqlSessionFactory"
    },
    {
      "path": "org/apache/ibatis/executor/CachingExecutor.java",
      "label": "CachingExecutor"
    },
    {
      "path": "org/apache/ibatis/executor/BaseExecutor.java",
      "label": "BaseExecutor"
    },
    {
      "path": "org/apache/ibatis/executor/BatchExecutor.java",
      "label": "BatchExecutor"
    },
    {
      "path": "org/apache/ibatis/binding/MapperRegistry.java",
      "label": "MapperRegistry"
    },
    {
      "path": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "label": "XMLMapperBuilder"
    },
    {
      "path": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "label": "DefaultResultSetHandler"
    }
  ],
  "controls": [
    {
      "id": "executor",
      "type": "select",
      "label": "默认执行器",
      "default": "SIMPLE",
      "options": [
        {
          "value": "SIMPLE",
          "label": "SIMPLE"
        },
        {
          "value": "REUSE",
          "label": "REUSE"
        },
        {
          "value": "BATCH",
          "label": "BATCH"
        }
      ]
    }
  ],
  "steps": [
    {
      "title": "openSession 从 newExecutor 拿执行器",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 94,
      "lines": 16,
      "explain": "94-109 行的 openSessionFromDataSource：100 行建 Transaction，101 行 `configuration.newExecutor(tx, execType)`，102 行塞进 DefaultSqlSession。这里拿到的就是前面探针打印的 CachingExecutor(SimpleExecutor)。",
      "state": {
        "stack": [
          "DefaultSqlSessionFactory.openSessionFromDataSource",
          "transactionFactory.newTransaction(...)",
          "configuration.newExecutor(tx, execType)",
          "new DefaultSqlSession(configuration, executor, autoCommit)"
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "开关：cacheEnabled 不是一级缓存开关",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 107,
      "lines": 13,
      "explain": "107-119 行是一排布尔值。`cacheEnabled=true`（113 行）只在 `newExecutor`:745-747 决定是否套 `CachingExecutor`；一级缓存的 `localCache` 在 `BaseExecutor.java:68` 无条件创建，跟这个开关无关。",
      "state": {
        "vars": [
          [
            "cacheEnabled",
            "true（113 行）"
          ],
          [
            "useActualParamName",
            "true（115 行）"
          ],
          [
            "mapUnderscoreToCamelCase",
            "false（109 行）"
          ]
        ]
      },
      "predict": {
        "question": "把 cacheEnabled 改成 false，同一个 SqlSession 里重复查同一条数据，第二次还命中一级缓存吗？",
        "options": [
          "命中，一级缓存不归它管",
          "不命中，cacheEnabled 就是一级缓存开关",
          "看有没有 cache 标签"
        ],
        "answer": 0,
        "reveal": "`cacheEnabled` 只在 newExecutor:745-747 决定是否套 CachingExecutor；BaseExecutor.localCache 在 68 行无条件创建。"
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "默认值：SESSION、SIMPLE、PARTIAL",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 125,
      "lines": 9,
      "explain": "125 行 `localCacheScope=SESSION`，132 行 `defaultExecutorType=SIMPLE`，133 行 `autoMappingBehavior=PARTIAL`。这三个默认值分别管一级缓存寿命、openSession 的基础执行器、自动映射的边界。",
      "state": {
        "vars": [
          [
            "localCacheScope",
            "SESSION（125 行）"
          ],
          [
            "defaultExecutorType",
            "SIMPLE（132 行）"
          ],
          [
            "autoMappingBehavior",
            "PARTIAL（133 行）"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "SIMPLE：默认是 CachingExecutor(SimpleExecutor)",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 735,
      "lines": 15,
      "explain": "735-749 行先按 ExecutorType 选基础执行器：738-739 行 BATCH→BatchExecutor，740-741 行 REUSE→ReuseExecutor，742-743 行 else→SimpleExecutor；745-747 行再按 `cacheEnabled` 套 CachingExecutor。默认 true，所以 SIMPLE 的实际对象是 CachingExecutor(SimpleExecutor)。",
      "when": {
        "executor": "SIMPLE"
      },
      "state": {
        "tables": [
          [
            "openSession() → executor",
            "CachingExecutor(SimpleExecutor)"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "REUSE：复用 Statement，不是复用连接",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 735,
      "lines": 15,
      "explain": "ReuseExecutor 自己维护 `Map<String, Statement>`（ReuseExecutor.java:41），按 SQL 文本复用 PreparedStatement；连接仍由 Transaction 管。外面同样会套 CachingExecutor，除非 cacheEnabled=false。",
      "when": {
        "executor": "REUSE"
      },
      "state": {
        "tables": [
          [
            "openSession() → executor",
            "CachingExecutor(ReuseExecutor)"
          ],
          [
            "statement 缓存",
            "按 SQL 文本复用"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "BATCH：CachingExecutor(BatchExecutor)",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 735,
      "lines": 15,
      "explain": "BatchExecutor 把 update 攒在 statementList 里；`doUpdate` 在 55-80 行返回 43 行的常量 BATCH_UPDATE_RETURN_VALUE（79 行），不是真实影响行数。默认 cacheEnabled=true 时它也被 CachingExecutor 包着。",
      "when": {
        "executor": "BATCH"
      },
      "state": {
        "tables": [
          [
            "openSession(BATCH) → executor",
            "CachingExecutor(BatchExecutor)"
          ],
          [
            "doUpdate 返回值",
            "BATCH_UPDATE_RETURN_VALUE"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "CachingExecutor 只在有二级缓存时接管查询",
      "file": "org/apache/ibatis/executor/CachingExecutor.java",
      "line": 94,
      "lines": 17,
      "explain": "94-110 行的 `query` 先看 `ms.getCache()`。没有 `<cache>` 时直接 `delegate.query(...)`；有 cache、`useCache` 为 true 且 `resultHandler` 为 null 才走 `tcm.getObject`。`cacheEnabled=false` 时这层根本不存在，所以二级缓存也不会被查；一级缓存仍在 delegate 的 BaseExecutor 里。",
      "state": {
        "tables": [
          [
            "ms.getCache() == null",
            "直接 delegate"
          ],
          [
            "有 cache、useCache 且 resultHandler 为 null",
            "先查 TransactionalCacheManager"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "一级缓存：localCache 无条件创建",
      "file": "org/apache/ibatis/executor/BaseExecutor.java",
      "line": 65,
      "lines": 9,
      "explain": "BaseExecutor 构造器 65-73 行无条件 `new PerpetualCache` 创建 localCache（68 行），不读 `cacheEnabled`。它跟着 Executor，也就是跟着一个 SqlSession。",
      "state": {
        "tables": [
          [
            "localCache",
            "CacheKey → 查询结果"
          ],
          [
            "localOutputParameterCache",
            "存储过程 OUT 参数"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "一级缓存什么时候清",
      "file": "org/apache/ibatis/executor/BaseExecutor.java",
      "line": 148,
      "lines": 25,
      "explain": "148-149 行：queryStack==0 且语句要求 flushCache 时清；169-171 行：localCacheScope==STATEMENT 时每次顶层查询结束清；SESSION 则保留到 update/commit/rollback（116、247、258 行）。所以 STATEMENT 是「每条语句后清」，不是「关闭缓存」。",
      "state": {
        "tables": [
          [
            "update",
            "116 行 clearLocalCache()"
          ],
          [
            "commit / rollback",
            "247 / 258 行清"
          ],
          [
            "STATEMENT",
            "169-171 行每条语句后清"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "BATCH 查询先 flush：读得到自己刚写的数据",
      "file": "org/apache/ibatis/executor/BatchExecutor.java",
      "line": 83,
      "lines": 16,
      "explain": "doQuery 第一件事是 `flushStatements()`（87 行），把攒着的 batch 先执行，再 prepare/query。同一个 SqlSession 里写完立刻查，查得到自己刚写的数据；代价是每次走到 doQuery 都把 batch 刷掉，批量收益没了。",
      "state": {
        "tables": [
          [
            "query 之前",
            "flushStatements()"
          ],
          [
            "flush 之后",
            "执行查询"
          ]
        ]
      },
      "predict": {
        "question": "BATCH 执行器里，同一个 SqlSession 先 update 再 query，query 能看到刚写的数据吗？",
        "options": [
          "看不到，batch 还没提交",
          "看得到，doQuery 会先 flushStatements",
          "只有 commit 之后才看得到"
        ],
        "answer": 1,
        "reveal": "BatchExecutor.doQuery 在 87 行先 flushStatements()，再执行查询；代价是 batch 被这次查询刷掉。"
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "注册表：五个字段，不是一个 Map",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 152,
      "lines": 5,
      "explain": "152-156 行是五个字段：mapperRegistry、interceptorChain、typeHandlerRegistry、typeAliasRegistry、languageRegistry。mapperRegistry 是 `Map<Class<?>, MapperProxyFactory<?>>`（MapperRegistry.java:37），记的是接口→代理工厂，不是「哪个接口有哪些方法」；方法级缓存是在 MapperProxyFactory.java:32 的 methodCache 里。",
      "state": {
        "tables": [
          [
            "mapperRegistry",
            "接口 → MapperProxyFactory"
          ],
          [
            "interceptorChain",
            "0 个（没配插件）"
          ],
          [
            "typeHandlerRegistry",
            "内置类型处理器"
          ],
          [
            "typeAliasRegistry",
            "内置别名 + 自定义别名"
          ],
          [
            "languageRegistry",
            "XML / RAW"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "映射表：StrictMap 与重复 id",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 158,
      "lines": 8,
      "explain": "158-165 行：mappedStatements、caches、resultMaps、parameterMaps、keyGenerators 都是 StrictMap。mappedStatements 的 conflictMessageProducer（160-161 行）在重复 key 时把两个资源文件都打出来；StrictMap.put:1155-1158 抛 IllegalArgumentException。",
      "state": {
        "tables": [
          [
            "mappedStatements",
            "com.example.UserMapper.selectById"
          ],
          [
            "resultMaps",
            "com.example.UserMapper.userMap"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "PARTIAL：顶层仍自动映射，嵌套才收紧",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 466,
      "lines": 9,
      "explain": "466-474 行：resultMap 自己配了 autoMapping 就以它为准；isNested=true 时只有 FULL 才自动映射；顶层映射只要不是 NONE 就自动映射。所以 PARTIAL 下顶层列仍然自动映射，收紧的是 association/collection 里的嵌套列。",
      "state": {
        "tables": [
          [
            "PARTIAL + 顶层列",
            "自动映射"
          ],
          [
            "PARTIAL + 嵌套列",
            "不自动映射"
          ],
          [
            "FULL + 嵌套列",
            "自动映射"
          ]
        ]
      },
      "nodes": [
        "result"
      ]
    },
    {
      "title": "namespace：只有 getMapper 才要求它是接口全限定名",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 383,
      "lines": 10,
      "explain": "bindMapperForNamespace 383-392 行把 namespace 当类名加载，ClassNotFoundException 在 389-391 行被 ignore。纯 XML 的 mapper 可以用任意 namespace；想用 `configuration.getMapper(Class)` 才需要接口存在并绑定。",
      "state": {
        "tables": [
          [
            "namespace 不是类名",
            "ClassNotFoundException 被忽略"
          ],
          [
            "getMapper",
            "需要接口已注册"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "addMapper 只是转发",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 942,
      "lines": 3,
      "explain": "942-944 行把接口转给 MapperRegistry.addMapper。Configuration 自己不维护接口表；下一篇 03 看 XMLMapperBuilder 怎么把 select 变成 MappedStatement，MapperRegistry 在 08 篇。",
      "state": {
        "stack": [
          "Configuration.addMapper(type)",
          "MapperRegistry.addMapper(type)"
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

## cacheEnabled 到底关了什么

`Configuration.newExecutor`:735-749 主要做三件事：选基础执行器，按 `cacheEnabled` 决定要不要套 `CachingExecutor`，最后 748 行过一遍插件链。`CachingExecutor.query`:94-110 也只在 `ms.getCache()` 非空、`useCache` 为 true 且 `resultHandler` 为 null 时查 `TransactionalCacheManager`；否则直接转给 delegate。

一级缓存不在这条路径上。`BaseExecutor` 构造器 65-73 行无条件创建 `localCache`（68 行），查询时先看它（142-174 行）。所以：

- `cacheEnabled=false`：去掉 `CachingExecutor`，`<cache>` 的二级缓存不再被查；`localCache` 仍然在，重复查询仍然可能命中。
- `localCacheScope=STATEMENT`：169-171 行在每次顶层查询结束时清 `localCache`。它是「每条语句后清」，不是「关闭」；一条语句内部的嵌套查询仍可能用到它。
- 语句上 `flushCache=true`：148-149 行在顶层查询前清一次；`update` 则在 116 行无条件清。

## BATCH 的真实代价

`BatchExecutor.doQuery`:83-98 在查询前先 `flushStatements()`（87 行）。所以「BATCH 查不到自己刚写的数据」是反的：同一个 SqlSession、同一个事务连接里，写完立刻查，batch 会先被执行，查得到。

真正的问题是另外两个：

1. **每次真正走到 `BatchExecutor.doQuery` 的查询都会把 batch 刷掉**。写一条、查一次、再写一条，batch 永远攒不起来，批量收益消失。
2. **`doUpdate` 不返回真实影响行数**。55-80 行最后返回 `BATCH_UPDATE_RETURN_VALUE`（43 行常量，79 行返回），值不是 0/1/2 这种行数。要拿真实计数，得看 `flushStatements()` 返回的 `BatchResult`，`executeBatch()` 在 126 行填 `updateCounts`。

默认 `cacheEnabled=true` 时 BATCH 外面还有一层 `CachingExecutor`，但它只是委托，不会改变上面的 flush 行为。

## PARTIAL 只对嵌套映射收紧

`DefaultResultSetHandler.shouldApplyAutomaticMappings`:466-474 的规则是：

- `resultMap` 自己配了 `autoMapping`，以它为准（467-469 行）。
- `isNested=true`：只有 `AutoMappingBehavior.FULL` 才自动映射（470-471 行）。
- 顶层映射：只要不是 `NONE` 就自动映射（472-473 行）。

所以 `PARTIAL` 不是「整条查询都不自动映射」。顶层列仍然自动映射；只有 `<association>` / `<collection>` 里的嵌套列，在 PARTIAL 下不自动映射。要么显式写 `resultMap`，要么把 `autoMappingBehavior` 改成 `FULL`，要么在那个嵌套 resultMap 上单独开 `autoMapping=true`。

## namespace 不是必须写全限定接口名

`XMLMapperBuilder.bindMapperForNamespace`:383-392 会拿 namespace 去 `Resources.classForName`，但 `ClassNotFoundException` 在 389-391 行被 ignore。纯 XML 的 mapper 用 `namespace="userMapper"` 也能解析，`session.selectOne("userMapper.findById")` 照样能调；只有想用 `configuration.getMapper(UserMapper.class)` 时，才需要 namespace 对应到已注册的接口。

`StrictMap` 还会给带点的 key 存一个短名：`StrictMap.put`:1160-1166 在短名不冲突时存 `findById`，冲突时存 `Ambiguity`；`get`:1186-1188 遇到 `Ambiguity` 会抛异常，提示用全名。短名能用，但别把它当契约。

## 运行期不建议再改

「启动期填满、运行期只读」这句话也不准确。`Configuration.addMapper`:942-944、`addInterceptor`:930-932 都是 public，`TypeHandlerRegistry.register` 也能调。准确说法是：**构建期填满，运行期不建议再改**。

这些注册表内部有些用 `ConcurrentHashMap`，但这不等于整张配置表可以随时改。解析期还有 `incompleteStatements`、`incompleteResultMaps` 等队列（169-172 行），运行期再加 Mapper 没有一致性保证；设计用法仍然是 `build()` 完成后就当只读。

```code-challenge
{
  "id": "ex-configuration",
  "kind": "fill",
  "title": "补两个默认值",
  "template": "localCacheScope 默认是 {{0}}；defaultExecutorType 默认是 {{1}}。",
  "options": [
    [
      "SESSION",
      "STATEMENT"
    ],
    [
      "SIMPLE",
      "REUSE",
      "BATCH"
    ]
  ],
  "answers": [
    0,
    0
  ],
  "explain": "localCacheScope 默认 SESSION（Configuration.java:125）；defaultExecutorType 默认 SIMPLE（132）。"
}
```

```quiz
{
  "id": "configuration-quiz",
  "title": "小测：默认值与执行器",
  "questions": [
    {
      "question": "cacheEnabled=false 之后，一级缓存会怎样？",
      "options": [
        "一级缓存关闭",
        "只去掉 CachingExecutor，一级缓存还在",
        "一级缓存和二级缓存都关闭"
      ],
      "answer": 1,
      "explain": "`cacheEnabled` 只在 newExecutor:745-747 决定是否套 CachingExecutor；BaseExecutor.localCache 在 68 行无条件创建。"
    },
    {
      "question": "BATCH 执行器里，同一个 SqlSession 先 update 再 query，query 能看到刚写的数据吗？",
      "options": [
        "看不到，batch 还没提交",
        "看得到，doQuery 会先 flushStatements",
        "只有 commit 之后才看得到"
      ],
      "answer": 1,
      "explain": "BatchExecutor.doQuery 在 87 行先 flushStatements()，再执行查询；代价是 batch 被这次查询刷掉。"
    },
    {
      "question": "autoMappingBehavior=PARTIAL，查询里写了 association，顶层列还会自动映射吗？",
      "options": [
        "顶层列和嵌套列都不自动映射",
        "顶层列仍自动映射，嵌套映射不自动映射",
        "全部自动映射"
      ],
      "answer": 1,
      "explain": "DefaultResultSetHandler:466-474 只对 isNested=true 的分支要求 FULL；顶层映射只要不是 NONE 就自动映射。"
    }
  ]
}
```

## 还差的

- 执行器类名探针是真跑的（JDK 25 + `mybatis-3.5.19.jar`），但没有连真实数据库；BATCH 读到自己写的结论来自 `flushStatements` 的源码路径，没有在 MySQL 上跑一条 update+select。
- 没有测 `cacheEnabled=false` + `<cache>` 的实际命中差异；只按 `CachingExecutor.query`:94-110 判断。
- 没有测 `autoMappingBehavior=FULL` 下嵌套 association 的列映射；只按 `DefaultResultSetHandler`:466-474 判断。
- 没有验证运行期 `addMapper` / `addInterceptor` 在并发 session 下的实际后果；只能说 API 是 public，设计用法仍是构建期填满。
- 没有覆盖 Spring 事务、多 SqlSession 下 BATCH 的行为。