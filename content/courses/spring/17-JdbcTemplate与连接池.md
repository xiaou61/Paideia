---
title: 17 · JdbcTemplate 与连接池
order: 17
chapter: 数据
level: intermediate
category: spring-from-zero
prerequisite: 16 · 配置从哪来
minutes: 24
status: published
---

# 17 · JdbcTemplate 与连接池

## 第 11 个请求卡在 30 秒

```text
2026-11-19T14:38:58.922+08:00  INFO 66816 --- [           main] lab.Main : Started Main in 1.952 seconds (process running for 2.583)
2026-11-19T14:38:58.936+08:00  INFO 66816 --- [          req-4] com.zaxxer.hikari.HikariDataSource : HikariPool-1 - Starting...
request-02 got connection after 482 ms
request-08 failed after 30473 ms: org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
  cause = java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30006ms (total=10, active=10, idle=0, waiting=0)
```

这不是某家公司的线上日志，是我在 `%TEMP%` 的临时工程里复现的故障形态：Temurin 25.0.4、Spring Boot 4.1.1、HikariCP 7.0.2、H2 2.4.240。11 个线程同时调用同一个 `JdbcTemplate` 查询方法，前 10 个各拿一条连接后 `sleep(35_000)`，剩下一个等连接。数据库是 H2 内存库，不是 MySQL；连接池的排队行为一样，但 MySQL 的 `wait_timeout`、最大连接数没有在这里复现。

启动日志里没有报错，`Started Main` 之后，Hikari 的 `Starting...` 出现在第一个请求线程 `req-4` 上。池是懒启动的，应用启动成功不代表连接池容量够。

我原来以为 `maximum-pool-size: 10` 是「同时处理 10 个请求」，实际是「池里最多 10 条连接」。一次查询或一个事务占着一条，第 11 个只能等 `connection-timeout`。这两个参数本来就是这个默认值：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:lab
    hikari:
      maximum-pool-size: 10   # 默认值，写出来只为让故障可复现
      connection-timeout: 30000
```

故障不是配错，而是把默认值当成了并发上限。`connection-timeout` 默认 30000ms，所以它卡了 30 秒才抛，不是 1 秒。

## 一次查询的五道门

```text
JdbcTemplate.query(...)
  → DataSourceUtils.getConnection(dataSource)
      → 有事务：从 TransactionSynchronizationManager 取 ConnectionHolder
      → 没事务：dataSource.getConnection()
  → HikariPool 借一条 Connection
  → PreparedStatement 执行 SQL
  → ResultSet 逐行交给 RowMapper
  → finally 归还；事务里 release，不 close
```

下面按这条线走，每道门只留一个会出问题的点。

## 第一道门：JdbcTemplate 的承诺与例外

`JdbcTemplate` 替你做的事：取连接、还连接、绑定 `?` 参数、把 `SQLException` 翻译成 `DataAccessException`、逐行回调。最容易记错的是「查不到」和「查多了」。

```text
queryForObject empty -> org.springframework.dao.EmptyResultDataAccessException
  message = Incorrect result size: expected 1, actual 0
queryForObject two rows -> org.springframework.dao.IncorrectResultSizeDataAccessException
  message = Incorrect result size: expected 1, actual 2
query empty list size = 0
update matched rows = 1
update matched no rows = 0
```

这是 H2 2.4.240、Boot 4.1.1 的同一个临时工程。`queryForObject` 不是「取第一行」：0 行抛 `EmptyResultDataAccessException`，2 行抛 `IncorrectResultSizeDataAccessException`。`query` 返回空 `List`，不抛。`update` 返回影响行数；影响 0 行不是异常。

`EmptyResultDataAccessException` 的继承链，用 `javap` 看的：

```text
EmptyResultDataAccessException
  extends IncorrectResultSizeDataAccessException
    extends DataRetrievalFailureException
      extends DataAccessException
        extends NestedRuntimeException
          extends RuntimeException
```

所以它是非受检异常。业务层可以 catch `EmptyResultDataAccessException` 做「没找到」，也可以让它冒泡，由全局异常处理决定返回 404 还是 500。别 catch `Exception` 吞掉。

异常翻译也验证了：

```text
bad SQL -> org.springframework.jdbc.BadSqlGrammarException
  is DataAccessException = true
active connections after bad SQL = 0
```

最后一行说明 `JdbcTemplate` 在 SQL 报错时也把连接还了。这也是为什么不该在它外面再手动 `getConnection()`。

## 第二道门：DataSource 与事务绑定

`JdbcTemplate` 不直接 `new` 连接；它每次执行都问 `DataSourceUtils` 要连接。`DataSourceUtils` 先看当前线程有没有事务绑定的 `ConnectionHolder`，有就用，没有才向 `DataSource` 借。`@Transactional` 的 `DataSourceTransactionManager` 在方法进入前借一条连接、绑定到线程，方法结束后提交或回滚并归还。

在同一个 H2 临时工程里，我给 `DataSource` 套了一个计数代理，数 `getConnection()` 被调用几次：

```text
non-tx getConnection delta = 2
non-tx active after = false
tx active inside = true
tx getConnection delta inside = 0
tx getConnection delta outside = 1
tx active after = false
```

两次非事务查询，`getConnection` 两次；`@Transactional` 方法里两次查询，方法内 0 次，外层事务总共 1 次。池可能把同一条物理连接借给你两次，但取和还的动作仍是两次；事务的价值是这两次取还变成一次，而且这条连接在提交或回滚前不会回到池里被别的请求借走。

这里和第 12 课连着：`this.method()` 不经过代理，`@Transactional` 不会生效；`REQUIRES_NEW` 要第二条连接，外层事务没结束时它只能再借一条。第 12 课用 `maximumPoolSize=1` 复现过这个等待。

## 第三道门：池的 10 个名额

Hikari 的默认值，来自 `HikariConfig` 的 getter：

```text
maximumPoolSize = 10
minimumIdle = -1
connectionTimeout = 30000
idleTimeout = 600000
maxLifetime = 1800000
leakDetectionThreshold = 0
```

`minimumIdle = -1` 是 Hikari 的 sentinel，不是「池里 -1 条」。它表示没有单独设；池启动后按 `maximumPoolSize` 补。把池跑起来后：

```text
borrowed one connection
total = 10, idle = 10, active = 0
```

H2 内存库，池启动后 10 条空闲。HikariCP 6.3.3（Boot 3.5.6 的依赖）的 `HikariConfig` 用 getter 对过，也是 10 / -1 / 30000 / 600000 / 1800000 / 0；完整 Boot 3.5.6 应用没有重跑 11 并发。

Spring Boot 的绑定方式：

- `spring.datasource.url`、`username`、`password`、`driver-class-name` 绑到 `DataSourceProperties`，它带 `@ConfigurationProperties("spring.datasource")`。
- `spring.datasource.hikari.*` 绑到 `HikariDataSource`。`HikariDataSource` 本身就是 `HikariConfig`；Boot 的 `DataSourceConfiguration.Hikari` 方法上写着 `@ConfigurationProperties("spring.datasource.hikari")`。kebab-case 和 camelCase 都认。

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 3
      minimum-idle: 1
      connection-timeout: 1500
      pool-name: LabPool
```

```text
poolName = LabPool
maximumPoolSize = 3
minimumIdle = 1
connectionTimeout = 1500
```

在 Boot 4.1.1 上，两处都写 URL 时，`spring.datasource.hikari.jdbc-url` 会覆盖 `spring.datasource.url`。我把两个都设上，最后一行为：

```text
jdbcUrl = jdbc:h2:mem:fromHikari
```

选一处写，不要两边都配。

池大小不是越大越好：`maximumPoolSize` 只是上限，不会让数据库多出并发处理能力。数据库的 CPU、IO、锁能同时处理的查询数有上限；连接超过这个数，多出来的连接在数据库侧等 CPU 或锁，各占一份会话内存，应用侧只是把排队从池外挪到池内。11 并发、池 10 的实验就是最小证据：第 11 个没有变快，它在等 `connectionTimeout`。我没有做吞吐压测，不写「10 比 50 快多少」的数字。要验证自己的库，就在固定 SQL 上逐步加并发，记录数据库活跃会话、CPU 和 P99；池加大后 P99 不降，说明瓶颈不在池。

`max-lifetime` 要短于数据库侧的空闲断开时间。MySQL 的 `wait_timeout` 是其中一个，默认值随版本和配置变；如果池里的连接活得比它久，连接可能已经被数据库断开，借出时才报 `Communications link failure`。Hikari 会校验和淘汰，但不要把它当保证，留出余量更省事。`idle-timeout` 只在 `minimumIdle < maximumPoolSize` 时才会收空闲连接；`minimumIdle` 等于最大值时，池不会因为空闲而缩。

驱动类名：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/paideia
    driver-class-name: com.mysql.cj.jdbc.Driver   # 可省略，Connector/J 8+ 走 SPI 自动注册
```

`com.mysql.jdbc.Driver` 是旧名字。Connector/J 9.7.0 里这个类还在，但加载它会打印：

```text
Loading class `com.mysql.jdbc.Driver'. This is deprecated. The new driver class is `com.mysql.cj.jdbc.Driver'. The driver is automatically registered via the SPI and manual loading of the driver class is generally unnecessary.
```

这是运行时打印，不是编译错误；新代码不要写它。

## 第四道门：连接什么时候还

泄漏的典型形态不是「忘了 new」，而是「手动拿了连接，没在 finally 还」：

```java
Connection connection = dataSource.getConnection();
try {
    // 用 connection
} finally {
    connection.close();
}
```

`JdbcTemplate` 内部已经这样做；你在它外面再 `getConnection()`，就多借了一条。如果 `close()` 还不在 finally 里，一抛异常就永久占着；池到上限后，所有请求都在 `connection-timeout` 上排队，就是开头那 30 秒。

`DataSourceUtils` 和 `TransactionAwareDataSourceProxy` 的区别，我用同一个 H2 临时工程、同一事务里的两条连接看了：

```text
raw == bound = false
raw transaction-bound = false
bound transaction-bound = true
raw session = 4, bound session = 3
proxy session = 3, bound session = 3
proxy logical closed after close = true
jdbc still works after proxy close = true
tx active after proxy close = true
```

- `dataSource.getConnection()` 在事务里仍是第二条物理连接（session 4），`DataSourceUtils.isConnectionTransactional` 对它是 false；它不会自动加入事务。
- `DataSourceUtils.getConnection(dataSource)` 返回事务绑定的那条（session 3），`isConnectionTransactional` 是 true；`DataSourceUtils.releaseConnection` 在事务里不会关闭它。
- `TransactionAwareDataSourceProxy` 是给「只会直接调 `getConnection()` 的老代码」用的包装：它返回的代理连接和事务绑定连接是同一个 session；在事务里 `close()` 只做逻辑关闭，`JdbcTemplate` 之后还能用同一事务连接（`jdbc still works = true`）。它不负责开事务，也不负责把泄漏的连接还回来。

应用代码优先用 `JdbcTemplate`；框架代码要用 `DataSourceUtils.getConnection`，就必须配 `DataSourceUtils.releaseConnection`。只有确实需要手写 JDBC 时才直接拿 `Connection`，并且放 `try-with-resources` 或 `finally`。

## 第五道门：一行怎么变成对象

`RowMapper` 是你写的映射函数；`BeanPropertyRowMapper` 是反射版。先看真实输出：

```text
BeanPropertyRowMapper bookTitle = 重构
BeanPropertyRowMapper mismatched column = null
checkFullyPopulated -> org.springframework.dao.InvalidDataAccessApiUsageException
  message = Given ResultSet does not contain all properties necessary to populate object of class lab.Main$BookBean: [id, bookTitle]
DataClassRowMapper record = BookRecord[id=1, bookTitle=重构]
```

H2 2.4.240、Spring JDBC 7.0.9。`book_title` 到 `bookTitle` 默认就映射上了；`BeanPropertyRowMapper` 会同时登记属性名的 lower-case 与 underscore 形式。这里纠正一个流传很广的说法：「`BeanPropertyRowMapper` 要求列名和属性名完全一致，snake_case 要靠 `DataClassRowMapper` 或配置」。在 Spring 6.2.11 和 7.0.9 上不是这样；我在两个版本的 `mappedNames` 字节码里都看到 `lowerCaseName` 和 `underscoreName`。

它的真实取舍：

- JavaBean：无参构造加 setter，适合可变对象；列名对不上不会报错，字段留 `null`，就是第二行输出。`checkFullyPopulated(true)` 能把「少列」变成异常，但仍是运行时。
- `DataClassRowMapper`：构造器绑定，适合 record 或不可变类；构造器参数名来自 `-parameters`。Boot 父工程默认加了，自己搭 Maven 要加；也可以用 `@ConstructorProperties`。它同样会尝试下划线形式。
- 显式 `RowMapper`：不用反射，字段和类型直接写在代码里；列名仍是字符串，写错照样运行时才炸。联表、别名、计算列、特殊转换都自己写；热路径或复杂查询用它。
- `queryForObject` 的「只有一行」约束在这里也适用：映射器只负责一行，行数由 `JdbcTemplate` 的查询方法决定。

`BeanPropertyRowMapper` 的 `checkFullyPopulated` 异常是 `InvalidDataAccessApiUsageException`，不是 `DataRetrievalFailureException`；这是我在 7.0.9 上看到的当前行为，不要拿它当稳定错误类型。

## 跑一遍

下面纯 Java 模拟「非事务两次查询各借一次 / 事务里两次查询只借一次」，不连数据库；真实 Spring 的输出在上面。`Pool` 是玩具，只用来数借还次数。

<!-- run -->
```java
import java.util.ArrayList;
import java.util.List;

public class Main {

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

    static final class Connection {
        final int id;

        Connection(int id) {
            this.id = id;
        }
    }

    static final class Pool {
        private final int max;
        private final List<Connection> idle = new ArrayList<>();
        private int created;
        private int borrowed;

        Pool(int max) {
            this.max = max;
        }

        Connection acquire() {
            borrowed++;
            if (!idle.isEmpty()) {
                return idle.remove(idle.size() - 1);
            }
            if (created == max) {
                throw new IllegalStateException("connection timeout");
            }
            created++;
            return new Connection(created);
        }

        void release(Connection connection) {
            idle.add(connection);
        }

        String stats() {
            return "新建 " + created + " 个连接，借出 " + borrowed + " 次，当前空闲 " + idle.size() + " 个";
        }
    }

    interface RowMapper<T> {
        T map(long id, String title);
    }

    static final class JdbcTemplate {
        private final Pool pool;
        private Connection txConnection;

        JdbcTemplate(Pool pool) {
            this.pool = pool;
        }

        <T> List<T> query(RowMapper<T> mapper) {
            Connection connection = txConnection != null ? txConnection : pool.acquire();
            try {
                List<T> rows = new ArrayList<>();
                rows.add(mapper.map(1L, "深入理解 Java 虚拟机"));
                rows.add(mapper.map(2L, "Spring 实战"));
                return rows;
            } finally {
                if (txConnection == null) {
                    pool.release(connection);
                }
            }
        }

        void inTransaction(Runnable body) {
            txConnection = pool.acquire();
            try {
                body.run();
            } finally {
                pool.release(txConnection);
                txConnection = null;
            }
        }
    }

    public static void main(String[] args) {
        Pool nonTxPool = new Pool(10);
        JdbcTemplate nonTx = new JdbcTemplate(nonTxPool);
        System.out.println(nonTx.query((id, title) -> new Book(id, title)));
        System.out.println(nonTx.query((id, title) -> new Book(id, title)));
        System.out.println("non-tx: " + nonTxPool.stats());

        Pool txPool = new Pool(10);
        JdbcTemplate tx = new JdbcTemplate(txPool);
        tx.inTransaction(() -> {
            System.out.println(tx.query((id, title) -> new Book(id, title)));
            System.out.println(tx.query((id, title) -> new Book(id, title)));
        });
        System.out.println("tx: " + txPool.stats());
    }
}
```
```text
[1:深入理解 Java 虚拟机, 2:Spring 实战]
[1:深入理解 Java 虚拟机, 2:Spring 实战]
non-tx: 新建 1 个连接，借出 2 次，当前空闲 1 个
[1:深入理解 Java 虚拟机, 2:Spring 实战]
[1:深入理解 Java 虚拟机, 2:Spring 实战]
tx: 新建 1 个连接，借出 1 次，当前空闲 1 个
```

玩具池按顺序复用同一条连接，所以两次都是「新建 1 个连接」；非事务借出 2 次，事务借出 1 次。这不是 Hikari 的并发行为，只验证「事务边界决定借几次」。

## 动手

下面这个方法在高并发下会导致「所有接口超时」。找出最该改的那一行：

```code-challenge
{
  "id": "spring-17-bug",
  "kind": "bug",
  "title": "连接池被漏干",
  "code": "class BookService {\n    private final JdbcTemplate jdbcTemplate;\n    private final DataSource dataSource;\n\n    List<Book> list() {\n        Connection connection = dataSource.getConnection();\n        List<Book> books = jdbcTemplate.query(\"SELECT id, title FROM book\",\n                (rs, rowNum) -> new Book(rs.getLong(\"id\"), rs.getString(\"title\")));\n        connection.close();\n        return books;\n    }\n}",
  "bugLine": 6,
  "explain": "第 6 行手动 `getConnection()` 是多余的：`JdbcTemplate.query` 自己会取连接，并在 finally 里归还。这里多借了一条；第 9 行的 `close()` 又不在 finally 里，查询抛异常时这条连接就不会还。池到上限后，所有请求都卡在 `connection-timeout` 上。修法：删掉第 6 行和第 9 行，让 `JdbcTemplate` 管连接。"
}
```

**提示**：`JdbcTemplate` 的 `query` 自己会取连接；你手动多拿的那条，如果不在 `finally` 里关，就会在异常路径上漏掉。

## 检查一下

```quiz
{
  "id": "spring-17-quiz",
  "title": "连接池三问",
  "questions": [
    {
      "question": "HikariCP 的默认 maximumPoolSize 与 connectionTimeout 是多少？",
      "options": ["10 与 1 秒", "10 与 30 秒", "20 与 30 秒"],
      "answer": 1,
      "explain": "HikariCP 6.3.3 和 7.0.2 的 HikariConfig 默认值都是 10 与 30000ms；minimumIdle 的原始值是 -1，池按 maximumPoolSize 处理。"
    },
    {
      "question": "`queryForObject` 查不到行时返回什么？",
      "options": ["返回 null", "抛 SQLException", "抛 EmptyResultDataAccessException"],
      "answer": 2,
      "explain": "它要求恰好一行；0 行抛 EmptyResultDataAccessException，2 行抛 IncorrectResultSizeDataAccessException。`query` 才是返回空 list。"
    },
    {
      "question": "Spring 6.2/7 的 `BeanPropertyRowMapper` 遇到 `book_title` 列、`bookTitle` 属性，默认会怎样？",
      "options": ["能映射，默认会尝试下划线转驼峰", "映射不上，必须改列别名", "直接编译失败"],
      "answer": 0,
      "explain": "我在这两个版本上实测都能映射；它同时登记 `bookTitle` 与 `book_title`。真正会静默出问题的是列名与属性名都对不上：字段留 null，不报错。"
    }
  ]
}
```

## 还差的

- 只在 H2 2.4.240 上跑；MySQL 的 `wait_timeout`、`max_connections`、驱动层超时没有实测，MySQL 上的 11 并发也没复现。
- 11 并发是 11 个 Java 线程直接调 `JdbcTemplate`，不是 Tomcat 线程池加 HTTP 压测；真实请求还受 Tomcat 最大线程数、网络和事务长度影响。
- Boot 3.5.6 只对 HikariCP 6.3.3 的配置默认值做了 getter 对照，没有起完整应用重跑 11 并发；Boot 4.1.1 是完整跑过的。
- `TransactionAwareDataSourceProxy` 只验证了单 H2 数据源；多数据源、JTA、连接池外的 XA 没覆盖。
- 没有做吞吐和延迟压测，所以本文不给「池 10 比池 50 快或慢多少」的数字；只验证了上限和排队。