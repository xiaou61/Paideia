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

```source-walk
{
  "id": "executor-family",
  "title": "Executor 的四种形态",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/executor/BaseExecutor.java",
      "label": "BaseExecutor"
    },
    {
      "path": "org/apache/ibatis/executor/SimpleExecutor.java",
      "label": "SimpleExecutor"
    },
    {
      "path": "org/apache/ibatis/executor/BatchExecutor.java",
      "label": "BatchExecutor"
    },
    {
      "path": "org/apache/ibatis/executor/CachingExecutor.java",
      "label": "CachingExecutor"
    }
  ],
  "controls": [
    {
      "id": "kind",
      "type": "select",
      "label": "用哪个执行器",
      "default": "simple",
      "options": [
        {
          "value": "simple",
          "label": "SimpleExecutor"
        },
        {
          "value": "batch",
          "label": "BatchExecutor"
        },
        {
          "value": "caching",
          "label": "CachingExecutor（二级缓存）"
        }
      ]
    }
  ],
  "steps": [
    {
      "title": "BaseExecutor：所有实现的公共前半段",
      "file": "org/apache/ibatis/executor/BaseExecutor.java",
      "line": 142,
      "lines": 32,
      "explain": "查一级缓存、维护 `queryStack`、处理延迟加载、按 `localCacheScope` 清理——这些与「用哪种执行器」无关，所以放在抽象基类。子类只实现 `doQuery` / `doUpdate`。",
      "state": {
        "stack": [
          "BaseExecutor.query",
          "localCache.getObject"
        ],
        "vars": [
          [
            "queryStack",
            "1"
          ]
        ]
      }
    },
    {
      "title": "真正查库的那一步",
      "file": "org/apache/ibatis/executor/BaseExecutor.java",
      "line": 331,
      "lines": 15,
      "explain": "`queryFromDatabase` 先放一个占位对象（防止嵌套查询重复查），再 `doQuery`，最后把结果写进一级缓存。**占位是为了递归查询**：同一条语句在结果映射里再次触发时不会死循环。",
      "state": {
        "tables": [
          [
            "localCache",
            "key → EXECUTION_PLACEHOLDER → 结果"
          ]
        ]
      }
    },
    {
      "title": "Simple：每次新建 Statement，用完就关",
      "file": "org/apache/ibatis/executor/SimpleExecutor.java",
      "line": 57,
      "lines": 13,
      "explain": "拿 `StatementHandler` → `prepareStatement` → `handler.query` → finally 关闭。没有复用、没有攒批，最好理解。",
      "when": {
        "kind": "simple"
      },
      "state": {
        "tables": [
          [
            "每次查询",
            "新建并关闭 Statement"
          ]
        ]
      }
    },
    {
      "title": "Batch：查询前先把攒的写发掉",
      "file": "org/apache/ibatis/executor/BatchExecutor.java",
      "line": 83,
      "lines": 15,
      "explain": "`flushStatements()` 在查询之前——因为同一个连接上，未执行的批处理会影响后续读。然后照常 prepare/query。",
      "when": {
        "kind": "batch"
      },
      "state": {
        "tables": [
          [
            "待执行批",
            "先 flush"
          ],
          [
            "查询",
            "照常走"
          ]
        ]
      }
    },
    {
      "title": "Batch 的 flush：逐条 executeBatch",
      "file": "org/apache/ibatis/executor/BatchExecutor.java",
      "line": 115,
      "lines": 21,
      "explain": "回滚时直接返回空；否则对每个攒下的 `Statement` 调 `executeBatch()`，再把影响行数写回 `BatchResult`，主键回填也在这之后。",
      "when": {
        "kind": "batch"
      },
      "state": {
        "tables": [
          [
            "executeBatch",
            "返回 int[]"
          ],
          [
            "主键回填",
            "在 flush 之后"
          ]
        ]
      },
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
      "lines": 18,
      "explain": "先算 `CacheKey`，再看 `ms.getCache()`（二级缓存）。命中就直接返回；没命中才委派给真正的执行器，并把结果放进缓存。**它不改变 SQL 执行，只加一层缓存**。",
      "when": {
        "kind": "caching"
      },
      "state": {
        "tables": [
          [
            "cache",
            "namespace 级二级缓存"
          ],
          [
            "命中",
            "不进 delegate"
          ]
        ]
      },
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
