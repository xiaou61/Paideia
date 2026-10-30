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

```source-walk
{
  "id": "sql-session",
  "title": "openSession 到 executor.query",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "label": "DefaultSqlSessionFactory"
    },
    {
      "path": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "label": "DefaultSqlSession"
    }
  ],
  "controls": [
    {
      "id": "autoCommit",
      "type": "select",
      "label": "openSession(autoCommit)",
      "default": "false",
      "options": [
        {
          "value": "false",
          "label": "false（默认，要手动 commit）"
        },
        {
          "value": "true",
          "label": "true（自动提交）"
        }
      ]
    }
  ],
  "steps": [
    {
      "title": "openSession：默认执行器 + 不自动提交",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 46,
      "lines": 3,
      "explain": "无参 `openSession()` 用的是 `configuration.getDefaultExecutorType()` 与 `autoCommit=false`。**默认不自动提交**——这是很多人第一次用 MyBatis 插入不生效的原因。",
      "state": {
        "vars": [
          [
            "execType",
            "SIMPLE"
          ],
          [
            "autoCommit",
            "false"
          ]
        ]
      }
    },
    {
      "title": "一句 openSession 里发生了四件事",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 94,
      "lines": 16,
      "explain": "取 Environment → 建 Transaction（这一步可能拿连接）→ `configuration.newExecutor(tx, execType)`（按需套缓存/插件）→ 包成 `DefaultSqlSession`。失败时关闭事务，避免连接泄漏。",
      "state": {
        "stack": [
          "openSessionFromDataSource",
          "transactionFactory.newTransaction",
          "configuration.newExecutor"
        ],
        "tables": [
          [
            "transaction",
            "JdbcTransaction"
          ],
          [
            "executor",
            "SimpleExecutor"
          ]
        ]
      }
    },
    {
      "title": "autoCommit=true：事务照样建，只是自动提交",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 100,
      "lines": 1,
      "explain": "`autoCommit` 一路传给 `newTransaction`。注意它**不改变执行器**，只改变连接是否自动提交；一级缓存照样在。",
      "when": {
        "autoCommit": "true"
      },
      "state": {
        "tables": [
          [
            "connection.autoCommit",
            "true"
          ],
          [
            "一级缓存",
            "仍然生效"
          ]
        ]
      }
    },
    {
      "title": "autoCommit=false：忘了 commit 就白写",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSessionFactory.java",
      "line": 100,
      "lines": 1,
      "explain": "连接不自动提交，`session.close()` 之前没有 `commit()`，事务会回滚。**同一个 session 里能查到刚插入的数据**（没走库或走了同事务），换一个 session 就没了。",
      "when": {
        "autoCommit": "false"
      },
      "state": {
        "tables": [
          [
            "connection.autoCommit",
            "false"
          ],
          [
            "未 commit 就 close",
            "回滚"
          ]
        ]
      }
    },
    {
      "title": "selectList：三行就到 Executor",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "line": 146,
      "lines": 12,
      "explain": "取 `MappedStatement` → `wrapCollection(parameter)`（集合参数包成 map）→ `executor.query(...)`。SqlSession 自己**不做查询**，它只是把参数摆好。",
      "state": {
        "stack": [
          "DefaultSqlSession.selectList",
          "Executor.query"
        ],
        "vars": [
          [
            "statement",
            "com.example.UserMapper.selectById"
          ]
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
      "lines": 13,
      "explain": "`selectOne` 不是单独的查询路径，它调 `selectList` 然后判断：1 条返回、0 条 null、多条抛 `TooManyResultsException`。",
      "predict": {
        "question": "selectOne 查到 3 条会怎样？",
        "options": [
          "返回第一条",
          "返回 null",
          "抛 TooManyResultsException"
        ],
        "answer": 2,
        "reveal": "源码里明确 `list.size() > 1` 就抛异常。想只要一条就写 `limit 1` 或用 selectList 自己取。"
      },
      "state": {
        "vars": [
          [
            "list.size()",
            "3"
          ],
          [
            "结果",
            "TooManyResultsException"
          ]
        ]
      }
    },
    {
      "title": "getMapper：session 把 configuration 借出去",
      "file": "org/apache/ibatis/session/defaults/DefaultSqlSession.java",
      "line": 290,
      "lines": 3,
      "explain": "`configuration.getMapper(type, this)`——注意第二个参数是 `this`：代理拿到的就是当前 session。这就是「Mapper 不能跨 session 复用」的根源。",
      "state": {
        "tables": [
          [
            "MapperProxy.sqlSession",
            "当前 DefaultSqlSession"
          ]
        ]
      }
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
