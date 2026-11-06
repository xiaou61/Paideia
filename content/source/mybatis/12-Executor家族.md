---
title: Executor 家族：查询到底交给谁
category: mybatis-source
order: 12
minutes: 25
level: intermediate
status: published
---

## 这一关要读什么

`SqlSession` 把查询交给 `Executor`，但 Executor 不是一个人：`Simple`、`Reuse`、`Batch` 三种实现，
外面还可能套一层 `CachingExecutor`（二级缓存）。

这一关只看清楚**这几种执行器各自在哪一行分叉**。

```code-flow
{
  "id": "executor-family",
  "title": "Executor 的四种形态",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/executor/BaseExecutor.java",
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
      "title": "BaseExecutor：所有实现的公共前半段",
      "file": "org/apache/ibatis/executor/BaseExecutor.java",
      "line": 142,
      "code": "  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,\n      CacheKey key, BoundSql boundSql) throws SQLException {\n    ErrorContext.instance().resource(ms.getResource()).activity(\"executing a query\").object(ms.getId());\n    if (closed) {\n      throw new ExecutorException(\"Executor was closed.\");\n    }\n    if (queryStack == 0 && ms.isFlushCacheRequired()) {\n      clearLocalCache();\n    }\n    List<E> list;\n    try {\n      queryStack++;\n      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;\n      if (list != null) {\n        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);\n      } else {\n        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);\n      }\n    } finally {\n      queryStack--;\n    }\n    if (queryStack == 0) {\n      for (DeferredLoad deferredLoad : deferredLoads) {\n        deferredLoad.load();\n      }\n      // issue #601\n      deferredLoads.clear();\n      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {\n        // issue #482\n        clearLocalCache();\n      }\n    }",
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
        "executor"
      ],
      "edges": [],
      "explain": "查一级缓存、维护 `queryStack`、处理延迟加载、按 `localCacheScope` 清理——这些与「用哪种执行器」无关，所以放在抽象基类。子类只实现 `doQuery` / `doUpdate`。",
      "state": {
        "vars": [
          {
            "name": "queryStack",
            "value": "1"
          }
        ],
        "stack": [
          "BaseExecutor.query",
          "localCache.getObject"
        ]
      }
    },
    {
      "title": "真正查库的那一步",
      "file": "org/apache/ibatis/executor/BaseExecutor.java",
      "line": 331,
      "code": "  private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds,\n      ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {\n    List<E> list;\n    localCache.putObject(key, EXECUTION_PLACEHOLDER);\n    try {\n      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);\n    } finally {\n      localCache.removeObject(key);\n    }\n    localCache.putObject(key, list);\n    if (ms.getStatementType() == StatementType.CALLABLE) {\n      localOutputParameterCache.putObject(key, parameter);\n    }\n    return list;\n  }",
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
        15
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "`queryFromDatabase` 先放一个占位对象（防止嵌套查询重复查），再 `doQuery`，最后把结果写进一级缓存。**占位是为了递归查询**：同一条语句在结果映射里再次触发时不会死循环。",
      "log": [
        {
          "k": "localCache",
          "v": "key → EXECUTION_PLACEHOLDER → 结果"
        }
      ]
    },
    {
      "title": "Simple：每次新建 Statement，用完就关",
      "file": "org/apache/ibatis/executor/SimpleExecutor.java",
      "line": 57,
      "code": "  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,\n      BoundSql boundSql) throws SQLException {\n    Statement stmt = null;\n    try {\n      Configuration configuration = ms.getConfiguration();\n      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler,\n          boundSql);\n      stmt = prepareStatement(handler, ms.getStatementLog());\n      return handler.query(stmt, resultHandler);\n    } finally {\n      closeStatement(stmt);\n    }\n  }",
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
        13
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "拿 `StatementHandler` → `prepareStatement` → `handler.query` → finally 关闭。没有复用、没有攒批，最好理解。",
      "log": [
        {
          "k": "每次查询",
          "v": "新建并关闭 Statement"
        }
      ]
    },
    {
      "title": "Batch：查询前先把攒的写发掉",
      "file": "org/apache/ibatis/executor/BatchExecutor.java",
      "line": 83,
      "code": "  public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds,\n      ResultHandler resultHandler, BoundSql boundSql) throws SQLException {\n    Statement stmt = null;\n    try {\n      flushStatements();\n      Configuration configuration = ms.getConfiguration();\n      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds,\n          resultHandler, boundSql);\n      Connection connection = getConnection(ms.getStatementLog());\n      stmt = handler.prepare(connection, transaction.getTimeout());\n      handler.parameterize(stmt);\n      return handler.query(stmt, resultHandler);\n    } finally {\n      closeStatement(stmt);\n    }",
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
        15
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "`flushStatements()` 在查询之前——因为同一个连接上，未执行的批处理会影响后续读。然后照常 prepare/query。",
      "log": [
        {
          "k": "待执行批",
          "v": "先 flush"
        },
        {
          "k": "查询",
          "v": "照常走"
        }
      ]
    },
    {
      "title": "Batch 的 flush：逐条 executeBatch",
      "file": "org/apache/ibatis/executor/BatchExecutor.java",
      "line": 115,
      "code": "  public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {\n    try {\n      List<BatchResult> results = new ArrayList<>();\n      if (isRollback) {\n        return Collections.emptyList();\n      }\n      for (int i = 0, n = statementList.size(); i < n; i++) {\n        Statement stmt = statementList.get(i);\n        applyTransactionTimeout(stmt);\n        BatchResult batchResult = batchResultList.get(i);\n        try {\n          batchResult.setUpdateCounts(stmt.executeBatch());\n          MappedStatement ms = batchResult.getMappedStatement();\n          List<Object> parameterObjects = batchResult.getParameterObjects();\n          KeyGenerator keyGenerator = ms.getKeyGenerator();\n          if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {\n            Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;\n            jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);\n          } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { // issue #141\n            for (Object parameter : parameterObjects) {\n              keyGenerator.processAfter(this, ms, stmt, parameter);",
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
        "executor"
      ],
      "edges": [],
      "explain": "回滚时直接返回空；否则对每个攒下的 `Statement` 调 `executeBatch()`，再把影响行数写回 `BatchResult`，主键回填也在这之后。",
      "log": [
        {
          "k": "executeBatch",
          "v": "返回 int[]"
        },
        {
          "k": "主键回填",
          "v": "在 flush 之后"
        }
      ],
      "predict": {
        "question": "executeBatch() 返回什么？",
        "options": [
          "int[] 影响行数",
          "List<ResultSet>",
          "boolean"
        ],
        "answer": 0,
        "reveal": "按结果写回 BatchResult，之后才回填主键。"
      }
    },
    {
      "title": "CachingExecutor：二级缓存套在最外面",
      "file": "org/apache/ibatis/executor/CachingExecutor.java",
      "line": 94,
      "code": "  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler,\n      CacheKey key, BoundSql boundSql) throws SQLException {\n    Cache cache = ms.getCache();\n    if (cache != null) {\n      flushCacheIfRequired(ms);\n      if (ms.isUseCache() && resultHandler == null) {\n        ensureNoOutParams(ms, boundSql);\n        @SuppressWarnings(\"unchecked\")\n        List<E> list = (List<E>) tcm.getObject(cache, key);\n        if (list == null) {\n          list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);\n          tcm.putObject(cache, key, list); // issue #578 and #116\n        }\n        return list;\n      }\n    }\n    return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);\n  }",
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
        18
      ],
      "nodes": [
        "executor"
      ],
      "edges": [],
      "explain": "先算 `CacheKey`，再看 `ms.getCache()`（二级缓存）。命中就直接返回；没命中才委派给真正的执行器，并把结果放进缓存。**它不改变 SQL 执行，只加一层缓存**。",
      "log": [
        {
          "k": "cache",
          "v": "namespace 级二级缓存"
        },
        {
          "k": "命中",
          "v": "不进 delegate"
        }
      ],
      "predict": {
        "question": "CachingExecutor 是四选一的执行器吗？",
        "options": [
          "是",
          "不是，是包装层"
        ],
        "answer": 1,
        "reveal": "只要 namespace 配了 <cache> 且 cacheEnabled，它就在外面。"
      }
    }
  ]
}
```

## 怎么选

- 默认 `SIMPLE`。绝大多数业务用它。
- `REUSE`：同一 session 里重复执行相同 SQL 时省 prepare，但 Statement 会被长期持有。
- `BATCH`：批量写；**读不到未提交的数据**。
- `CachingExecutor` 不是四选一，它是包装层：只要 `cacheEnabled` 且语句的 namespace 配了 `<cache>`，它就在外面。

```code-challenge
{
  "id": "ex-executor",
  "kind": "order",
  "title": "排出一次查询穿过执行器的顺序",
  "lines": [
    "SimpleExecutor.doQuery 建 StatementHandler",
    "CachingExecutor.query 查二级缓存",
    "BaseExecutor.query 查一级缓存",
    "BaseExecutor.queryFromDatabase 调 doQuery"
  ],
  "answer": [
    1,
    2,
    3,
    0
  ],
  "explain": "二级缓存最外层，一级缓存在 BaseExecutor，最后才到具体执行器。"
}
```

```quiz
{
  "id": "executor-quiz",
  "title": "小测：执行器",
  "questions": [
    {
      "question": "一级缓存在哪个类里？",
      "options": ["SimpleExecutor", "BaseExecutor", "CachingExecutor"],
      "answer": 1,
      "explain": "`localCache` 是 BaseExecutor 的字段，三种执行器共享这段逻辑。"
    },
    {
      "question": "CachingExecutor 与二级缓存的关系？",
      "options": ["它就是二级缓存本身", "它是包装层，按 namespace 缓存", "与缓存无关"],
      "answer": 1,
      "explain": "`ms.getCache()` 取的是 namespace 级缓存，命中就不进 delegate。"
    },
    {
      "question": "BatchExecutor 执行查询前为什么要 flush？",
      "options": ["为了省内存", "因为未执行的批处理会影响同一连接的读", "为了回滚"],
      "answer": 1,
      "explain": "`doQuery` 第一行就是 `flushStatements()`。"
    }
  ]
}
```

下一关单独拆一级缓存：它什么时候命中、什么时候被清掉。
