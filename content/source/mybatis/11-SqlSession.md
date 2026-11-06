---
title: SqlSession：一次请求的边界
category: mybatis-source
order: 11
minutes: 20
level: beginner
status: published
---

## 这一关要读什么

`SqlSession` 是 MyBatis 给你用的门面：拿 Mapper、执行语句、提交/回滚、关连接。

这一关只看三件事：**它怎么被创建**、**查询怎么落到 Executor**、**为什么它不能跨线程共享**。

```code-flow
{
  "id": "sql-session",
  "title": "openSession 到 executor.query",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
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
      "title": "openSession：默认执行器 + 不自动提交",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 46,
      "code": "  public SqlSession openSession() {\n    return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, false);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "api"
      ],
      "edges": [],
      "explain": "无参 `openSession()` 用的是 `configuration.getDefaultExecutorType()` 与 `autoCommit=false`。**默认不自动提交**——这是很多人第一次用 MyBatis 插入不生效的原因。",
      "state": {
        "vars": [
          {
            "name": "execType",
            "value": "SIMPLE"
          },
          {
            "name": "autoCommit",
            "value": "false"
          }
        ]
      }
    },
    {
      "title": "一句 openSession 里发生了四件事",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 94,
      "code": "  private SqlSession openSessionFromDataSource(ExecutorType execType, TransactionIsolationLevel level,\n      boolean autoCommit) {\n    Transaction tx = null;\n    try {\n      final Environment environment = configuration.getEnvironment();\n      final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);\n      tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);\n      final Executor executor = configuration.newExecutor(tx, execType);\n      return createSqlSession(configuration, executor, autoCommit);\n    } catch (Exception e) {\n      closeTransaction(tx); // may have fetched a connection so lets call close()\n      throw ExceptionFactory.wrapException(\"Error opening session.  Cause: \" + e, e);\n    } finally {\n      ErrorContext.instance().reset();\n    }\n  }",
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
        "api"
      ],
      "edges": [],
      "explain": "取 Environment → 建 Transaction（这一步可能拿连接）→ `configuration.newExecutor(tx, execType)`（按需套缓存/插件）→ 包成 `DefaultSqlSession`。失败时关闭事务，避免连接泄漏。",
      "log": [
        {
          "k": "transaction",
          "v": "JdbcTransaction"
        },
        {
          "k": "executor",
          "v": "SimpleExecutor"
        }
      ],
      "state": {
        "stack": [
          "openSessionFromDataSource",
          "transactionFactory.newTransaction",
          "configuration.newExecutor"
        ]
      }
    },
    {
      "title": "autoCommit=true：事务照样建，只是自动提交",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 100,
      "code": "      tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "api"
      ],
      "edges": [],
      "explain": "`autoCommit` 一路传给 `newTransaction`。注意它**不改变执行器**，只改变连接是否自动提交；一级缓存照样在。",
      "log": [
        {
          "k": "connection.autoCommit",
          "v": "true"
        },
        {
          "k": "一级缓存",
          "v": "仍然生效"
        }
      ]
    },
    {
      "title": "autoCommit=false：忘了 commit 就白写",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 100,
      "code": "      tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);",
      "language": "java",
      "focus": [
        1
      ],
      "nodes": [
        "api"
      ],
      "edges": [],
      "explain": "连接不自动提交，`session.close()` 之前没有 `commit()`，事务会回滚。**同一个 session 里能查到刚插入的数据**（没走库或走了同事务），换一个 session 就没了。",
      "log": [
        {
          "k": "connection.autoCommit",
          "v": "false"
        },
        {
          "k": "未 commit 就 close",
          "v": "回滚"
        }
      ]
    },
    {
      "title": "selectList：三行就到 Executor",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "line": 146,
      "code": "  public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {\n    return selectList(statement, parameter, rowBounds, Executor.NO_RESULT_HANDLER);\n  }\n\n  private <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {\n    try {\n      MappedStatement ms = configuration.getMappedStatement(statement);\n      dirty |= ms.isDirtySelect();\n      return executor.query(ms, wrapCollection(parameter), rowBounds, handler);\n    } catch (Exception e) {\n      throw ExceptionFactory.wrapException(\"Error querying database.  Cause: \" + e, e);\n    } finally {",
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
        "api"
      ],
      "edges": [],
      "explain": "取 `MappedStatement` → `wrapCollection(parameter)`（集合参数包成 map）→ `executor.query(...)`。SqlSession 自己**不做查询**，它只是把参数摆好。",
      "state": {
        "vars": [
          {
            "name": "statement",
            "value": "com.example.UserMapper.selectById"
          }
        ],
        "stack": [
          "DefaultSqlSession.selectList",
          "Executor.query"
        ]
      },
      "predict": {
        "question": "selectList 里真正查库的是谁？",
        "options": [
          "Executor",
          "SqlSession 自己",
          "StatementHandler"
        ],
        "answer": 0,
        "reveal": "SqlSession 只把参数摆好，`executor.query(...)` 才查库。"
      }
    },
    {
      "title": "selectOne：查完再判断数量",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "line": 73,
      "code": "  public <T> T selectOne(String statement, Object parameter) {\n    // Popular vote was to return null on 0 results and throw exception on too many.\n    List<T> list = this.selectList(statement, parameter);\n    if (list.size() == 1) {\n      return list.get(0);\n    }\n    if (list.size() > 1) {\n      throw new TooManyResultsException(\n          \"Expected one result (or null) to be returned by selectOne(), but found: \" + list.size());\n    } else {\n      return null;\n    }\n  }",
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
        "api"
      ],
      "edges": [],
      "explain": "`selectOne` 不是单独的查询路径，它调 `selectList` 然后判断：1 条返回、0 条 null、多条抛 `TooManyResultsException`。",
      "state": {
        "vars": [
          {
            "name": "list.size()",
            "value": "3"
          },
          {
            "name": "结果",
            "value": "TooManyResultsException"
          }
        ]
      },
      "predict": {
        "question": "selectOne 查到 3 条会怎样？",
        "options": [
          "返回第一条",
          "返回 null",
          "抛 TooManyResultsException"
        ],
        "answer": 2,
        "reveal": "源码里明确 `list.size() > 1` 就抛异常。想只要一条就写 `limit 1` 或用 selectList 自己取。"
      }
    },
    {
      "title": "getMapper：session 把 configuration 借出去",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "line": 290,
      "code": "  public <T> T getMapper(Class<T> type) {\n    return configuration.getMapper(type, this);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3
      ],
      "nodes": [
        "api"
      ],
      "edges": [],
      "explain": "`configuration.getMapper(type, this)`——注意第二个参数是 `this`：代理拿到的就是当前 session。这就是「Mapper 不能跨 session 复用」的根源。",
      "log": [
        {
          "k": "MapperProxy.sqlSession",
          "v": "当前 DefaultSqlSession"
        }
      ]
    }
  ]
}
```

## 为什么不能共享 SqlSession

- 它持有 `Executor`，`Executor` 持有事务与一级缓存——都是**每请求**的状态。
- `DefaultSqlSession` 不是线程安全的；多线程共用一个 session，一级缓存与事务边界都会串。
- 正确做法：一次请求（或一个事务）一个 session，用完关闭。Spring 集成用 `SqlSessionTemplate` 做了这层绑定，但那是另一个仓库。

```code-challenge
{
  "id": "ex-sqlsession",
  "kind": "fill",
  "title": "补 openSession 的默认值",
  "template": "无参 openSession() 的 autoCommit 是 {{0}}，执行器类型取 configuration 的 {{1}}。",
  "options": [
    [
      "false",
      "true"
    ],
    [
      "defaultExecutorType",
      "executorType"
    ]
  ],
  "answers": [
    0,
    0
  ],
  "explain": "默认不自动提交，执行器类型来自全局配置。"
}
```

```quiz
{
  "id": "sqlsession-quiz",
  "title": "小测：SqlSession",
  "questions": [
    {
      "question": "openSession() 默认自动提交吗？",
      "options": ["是", "不是，要手动 commit", "取决于数据库"],
      "answer": 1,
      "explain": "无参 openSession 传的是 autoCommit=false。"
    },
    {
      "question": "selectOne 查到多条会怎样？",
      "options": ["返回第一条", "返回 null", "抛异常"],
      "answer": 2,
      "explain": "`TooManyResultsException`，并把实际条数写进消息。"
    },
    {
      "question": "为什么 Mapper 代理不能跨 SqlSession 复用？",
      "options": ["代理里存了 session", "代理有缓存", "接口不能复用"],
      "answer": 0,
      "explain": "`getMapper` 把当前 session 塞进 MapperProxy；换 session 就该重新 getMapper。"
    }
  ]
}
```

篇三结束。下一篇进入执行层：`Executor` 家族与一级缓存。
