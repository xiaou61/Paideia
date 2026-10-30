---
title: 17 · JdbcTemplate 与连接池
order: 17
chapter: 数据
level: intermediate
category: spring-from-zero
prerequisite: 16 · 配置从哪来
minutes: 32
status: published
---

# 17 · JdbcTemplate 与连接池

## 这一课你要会

- 说清连接池为什么存在，以及池小了/池大了各会怎样；
- 说清 `JdbcTemplate` 替你做的四件事；
- 手写"取连接 → 执行 → 逐行映射 → 还连接"，知道 `RowMapper` 是什么；
- 判断什么时候会踩到 N+1，以及为什么它比"慢一点"严重得多。

## 为什么

一次 JDBC 查询最贵的部分通常不是 SQL，而是**建立连接**：TCP 握手、数据库认证、会话初始化，一次 20～100 毫秒。如果每个请求都建一次连接，一个每秒 100 请求的服务光建连接就烧掉 2～10 秒的 CPU 时间，数据库连接数也会被打爆。

连接池就是把"建过的连接留着复用"。听起来很简单，但它的参数（核心大小、最大大小、超时）配错会造出两种极端故障：**池太小 → 排队，全站变慢；池太大 → 数据库连接数耗尽，所有应用一起挂**。

## 讲解

### 池的四个参数

```sketch
{
  "title": "连接池就三步：借、用、还",
  "rows": [
    [
      { "id": "borrow", "title": "从池里借", "sub": "借不到就按超时等", "tone": "blue" },
      { "id": "use", "title": "执行 SQL", "sub": "全程同一条连接", "tone": "gray" },
      { "id": "back", "title": "还回池里", "sub": "是放回，不是关掉", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "borrow", "to": "use" },
    { "from": "use", "to": "back" }
  ]
}
```

| 参数 | 含义 | 配错的后果 |
| --- | --- | --- |
| 最小空闲 | 池里常备几个 | 太小：突发流量时现建，前几个请求变慢 |
| 最大连接 | 上限 | 太小：高峰期排队；太大：把数据库连接数吃光 |
| 获取超时 | 等不到连接时等多久 | 太大：请求全部堆积不失败；太小：正常抖动也报错 |
| 空闲存活 | 空闲连接多久被回收 | 太大：数据库侧有超时（`wait_timeout`），连接被动断开后用时报错 |

经验值：**最大连接数 ≈ (CPU 核数 × 2) 到 (CPU 核数 × 4)**，不是越大越好。数据库的连接是稀缺资源，多个应用各配 200 个，加起来就超了数据库上限。

本项目用 HikariCP（Boot 默认），关键配置：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 3000      # 毫秒
      idle-timeout: 600000
      max-lifetime: 1800000         # 必须小于数据库的 wait_timeout
```

`max-lifetime` 那条特别值得记：**它必须比数据库侧的 `wait_timeout` 小**。否则连接会在池里"看起来活着"，实际已被数据库断开，下一次借出来用就报 `Communications link failure`——而且只在空闲一段时间后出现，测试时很难复现。

### 手写一遍：池 + 查询 + 映射 + 归还

<!-- run -->
```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    // 模拟 JDBC 返回的结果集：一组行，每行是 列名 → 值
    static final class ResultSet {
        final List<Map<String, Object>> rows = new ArrayList<>();

        ResultSet add(Object... pairs) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) {
                row.put((String) pairs[i], pairs[i + 1]);
            }
            rows.add(row);
            return this;
        }
    }

    static final class Book {
        final long id;
        final String title;

        Book(long id, String title) {
            this.id = id;
            this.title = title;
        }

        public String toString() {
            return id + ":" + title;
        }
    }

    // 连接池：没有空闲就新建，有就复用，用完还回来
    static final class Pool {
        private final List<String> idle = new ArrayList<>();
        private int created;
        private int borrowed;

        String acquire() {
            borrowed++;
            if (!idle.isEmpty()) {
                return idle.remove(idle.size() - 1);
            }
            created++;
            return "connection#" + created;
        }

        void release(String connection) {
            idle.add(connection);
        }

        String stats() {
            return "新建 " + created + " 个连接，借出 " + borrowed + " 次，当前空闲 " + idle.size() + " 个";
        }
    }

    // RowMapper：把"一行 Map"变成对象。—— 这一层就是 MyBatis 的映射
    static List<Book> mapRows(ResultSet resultSet) {
        List<Book> books = new ArrayList<>();
        for (Map<String, Object> row : resultSet.rows) {
            books.add(new Book(((Number) row.get("id")).longValue(), (String) row.get("title")));
        }
        return books;
    }

    static ResultSet resultSetFor(String sql) {
        if (sql.contains("id = 1")) {
            return new ResultSet().add("id", 1L, "title", "深入理解 Java 虚拟机");
        }
        return new ResultSet()
                .add("id", 1L, "title", "深入理解 Java 虚拟机")
                .add("id", 2L, "title", "Spring 实战");
    }

    static List<Book> query(Pool pool, String sql) {
        String connection = pool.acquire();
        try {
            return mapRows(resultSetFor(sql));
        } finally {
            pool.release(connection);          // 异常也要还，否则池会被漏干
        }
    }

    public static void main(String[] args) {
        Pool pool = new Pool();
        System.out.println(query(pool, "SELECT id, title FROM book"));
        System.out.println(query(pool, "SELECT id, title FROM book WHERE id = 1"));
        System.out.println(query(pool, "SELECT id, title FROM book"));
        System.out.println(pool.stats());
    }
}
```
```text
[1:深入理解 Java 虚拟机, 2:Spring 实战]
[1:深入理解 Java 虚拟机]
[1:深入理解 Java 虚拟机, 2:Spring 实战]
新建 1 个连接，借出 3 次，当前空闲 1 个
```

最后一行是池的全部价值：**借了三次，只建了一个连接**。

三个细节值得注意：

- **`finally` 里归还**。写在 `finally` 之外时，一抛异常连接就永久占着；池被"漏干"之后所有请求都卡在等待上，表现为"应用还活着但所有接口超时"。
- **`RowMapper` 是纯函数**：输入一行 Map，输出一个对象。它能不能被单独测试，决定了这层有没有价值。
- **结果集要逐行消费**。真实 JDBC 的 `ResultSet` 是流式的，读完关闭；一次性 `getObject` 全部读进内存会把大查询变成 OOM。

### `JdbcTemplate` 替你做的四件事

```java
List<Book> books = jdbcTemplate.query(
        "SELECT id, title FROM book WHERE title LIKE ?",
        (rs, rowNum) -> new Book(rs.getLong("id"), rs.getString("title")),
        "%Java%");
```

四件事：

1. **取连接、归还连接**（含异常路径）；
2. **参数绑定**（`?` 占位符，值永远不参与 SQL 语法解析——这是防注入的关键）；
3. **把 `SQLException` 翻译成 `DataAccessException`**（受检变非受检，业务层不用逐个 catch）；
4. **逐行回调**，把 `ResultSet` 的关闭交给你写不到的地方。

`RowMapper` 是第 3 步之外的你的部分。MyBatis 做的事本质上就是把这一步自动化了——下一课看它怎么做的。

### 为什么必须在 Service 层包事务

第 12 课说过，事务是"同一个连接上的一组语句"。用 `JdbcTemplate` 时这一点特别直观：两次 `query` 调用默认**各拿一个连接**，它们不在一个事务里。

要让它们在一个事务里，必须由外层（`@Transactional` 的 Service 方法）在进入时拿一个连接并绑定到当前线程，之后所有 `JdbcTemplate` 调用都复用那一个。这就是"事务边界必须在 Service 层"的机制解释。

### N+1：最隐蔽的性能问题

```sketch
{
  "title": "N+1：查一次列表，再为每条查一次",
  "rows": [
    [
      { "id": "one", "title": "1 次查列表", "sub": "拿到 20 本书", "tone": "gray" },
      { "id": "loop", "title": "循环里查分类", "sub": "20 次往返", "tone": "amber" },
      { "id": "fix", "title": "改成一次 JOIN", "sub": "或批量查完再拼", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "one", "to": "loop" },
    { "from": "loop", "to": "fix" }
  ]
}
```

```java
List<Book> books = bookMapper.findAll();          // 1 次
for (Book book : books) {
    book.setCategory(categoryMapper.findById(book.categoryId()));   // N 次
}
```

100 本书就是 101 次数据库往返。单次 2 毫秒看起来没事，101 次就是 200 毫秒——而这些查询每个都很简单，`EXPLAIN` 也不会报慢。

它比"慢一点"严重的地方在于**放大效应**：页面分页 20 条、每页 3 个关联字段，就是 61 次查询；并发一上来，数据库连接池被这 61 次连续借还占满，全站开始排队。

三种修法：一次 JOIN 拿全（最常用）、批量 `IN` 查（`WHERE id IN (...)` 一次拿完）、或者明确接受它（列表页只查主表，详情页再查关联）。

## 动手

下面这个方法在高并发下会导致"所有接口超时"。找出最该改的那一行：

```code-challenge
{
  "id": "spring-17-bug",
  "kind": "bug",
  "title": "连接池被漏干",
  "code": "class BookService {\n\n    private final JdbcTemplate jdbcTemplate;\n\n    List<Book> list() {\n        Connection connection = dataSource.getConnection();\n        List<Book> books = jdbcTemplate.query(\"SELECT id, title FROM book\",\n                (rs, rowNum) -> new Book(rs.getLong(\"id\"), rs.getString(\"title\")));\n        connection.close();\n        return books;\n    }\n}",
  "bugLine": 6,
  "explain": "第 6 行手动 `getConnection()` 是多余的——`JdbcTemplate.query` 自己会取连接再归还。这里多要了一个连接，如果第 9 行 `close()` 之前抛异常（比如查询报错），这个连接就永远不还了；借到池的上限之后，所有请求都卡在 `connection-timeout` 上。修法：删掉第 6 行与第 9 行，让 `JdbcTemplate` 自己管连接。"
}
```

**提示**：`JdbcTemplate` 的名字里，"Template" 就是指"帮你把样板代码做掉"。

## 检查一下

```quiz
{
  "id": "spring-17-quiz",
  "title": "连接池三问",
  "questions": [
    {
      "question": "max-lifetime 为什么要小于数据库的 wait_timeout？",
      "options": ["为了省内存", "否则池里的连接会被数据库断开，池还以为它活着", "协议要求"],
      "answer": 1,
      "explain": "数据库会主动断开闲置太久的连接。池不知道，借出来用就报 Communications link failure，而且只在空闲后出现。"
    },
    {
      "question": "最大连接数越大越好吗？",
      "options": ["是，越多越快", "不是，数据库连接是稀缺资源，多应用叠加会超上限", "取决于 JDK"],
      "answer": 1,
      "explain": "池大只是把排队从应用挪到了数据库。经验值是 CPU 核数的 2～4 倍。"
    },
    {
      "question": "两次 JdbcTemplate.query 默认在同一个事务里吗？",
      "options": ["在，同一个数据源就自动同事务", "不在，除非外层 @Transactional 把连接绑定到线程", "取决于 SQL"],
      "answer": 1,
      "explain": "事务=同一个连接。没有外层事务时每次调用各拿一个连接。"
    }
  ]
}
```

## 常见误区

### 误区：连接池越大，吞吐越高

**你以为**：加连接就能扛更多并发。
**其实**：数据库侧的并发处理能力有上限，连接多了只是把等待从应用内存挪到数据库里，还会因为上下文切换变慢。真正的吞吐拐点通常远早于"连接数上限"。
**怎么验证**：把池从 10 调到 50，压测对比 P99；通常会看到 P99 变差而不是变好。

### 误区：用完连接 GC 会帮我关

**你以为**：忘了 `close` 也没事，连接对象被回收时能自动归还。
**其实**：连接是被池持有的，GC 不会归还；HikariCP 会打印 `Connection leak detection` 警告（如果开了），但泄漏已经发生。唯一可靠的做法是 `try-with-resources` 或 `finally`。
**怎么验证**：在池里制造一次泄漏，看多久之后全部接口开始超时。

### 误区：N+1 只是"慢一点"

**你以为**：多几条查询，能忍。
**其实**：它的放大效应在并发下会吃掉连接池——每个请求连续借还几十次连接，池被高频占用，其他请求排队。表现出来是"数据库 CPU 不高，但接口全超时"。
**怎么验证**：打开 SQL 日志，看一次列表请求实际发了几条 SQL。数字通常远大于你的预期。

## 小结

连接池解决"建连接贵"，核心参数是最大连接与 `max-lifetime`。`JdbcTemplate` 替你取连接、绑参数、翻译异常、逐行回调，`RowMapper` 是你写的映射函数。事务=同一个连接，所以它必须在 Service 层开。N+1 的危害在于并发下吃光连接池。

下一课：`RowMapper` 这一步能不能自动做？这就是 MyBatis 干的事——以及 `#{}` 与 `${}` 为什么是安全问题。
