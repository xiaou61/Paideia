---
title: 18 · 手写 MyBatis 映射
order: 18
chapter: 数据
level: intermediate
category: spring-from-zero
prerequisite: 17 · JdbcTemplate 与连接池
minutes: 21
status: published
---

# 18 · 手写 MyBatis 映射

## 查出来 4 行，两个字段停在默认值

我在自己练手的小书单服务里有一张 `book` 表（玩具级别，就四行）：

| id | title | created_at | t_count |
| --- | --- | --- | --- |
| 1 | 深入理解 Java 虚拟机 | 2024-03-01 09:00 | 1 |
| 2 | Spring 实战 | 2024-05-20 09:00 | 0 |
| 3 | Java 并发编程实战 | 2024-07-11 09:00 | 1 |
| 4 | Spring 源码深度解析 | 2024-01-05 09:00 | 0 |

实体上对应的是 `Long id`、`String title`、`LocalDateTime createdAt`、`boolean tCount`。mapper 只有接口和 SQL：

```java
@Mapper
public interface BookMapper {
    @Select("SELECT id, title, created_at FROM book")
    List<Book> findAll();

    @Select("SELECT id, title, t_count FROM book")
    List<Book> tiny();
}
```

启动之后把两件事打出来：MyBatis 当前的下划线映射配置，和两个方法的返回值。

```text
mapUnderscoreToCamelCase | false
findAll | [1:深入理解 Java 虚拟机:null, 2:Spring 实战:null, 3:Java 并发编程实战:null, 4:Spring 源码深度解析:null]
tiny(TINYINT -> boolean) | [1:深入理解 Java 虚拟机:tCount=false, 2:Spring 实战:tCount=false, 3:Java 并发编程实战:tCount=false, 4:Spring 源码深度解析:tCount=false]
```

四行都在，`id` 和 `title` 全对。第一个猜的是「SQL 少查列」——回去看 mapper，`created_at` 和 `t_count` 明明都写在 `SELECT` 里。第二个猜的是「TINYINT 转 boolean 坏了」——这个猜错了，下面会看到：开关一开，`t_count` 的 1/0 自己就变成 true/false，类型转换一点毛病没有。

打开开关，Java 代码和 SQL 一个字不改：

```text
java -jar app.jar --mybatis.configuration.map-underscore-to-camel-case=true
```

```text
mapUnderscoreToCamelCase | true
findAll | [1:深入理解 Java 虚拟机:2024-03-01T09:00, 2:Spring 实战:2024-05-20T09:00, 3:Java 并发编程实战:2024-07-11T09:00, 4:Spring 源码深度解析:2024-01-05T09:00]
tiny(TINYINT -> boolean) | [1:深入理解 Java 虚拟机:tCount=true, 2:Spring 实战:tCount=false, 3:Java 并发编程实战:tCount=true, 4:Spring 源码深度解析:tCount=false]
```

两个字段都回来了，boolean 也对。出问题的是「列名 → 字段名」这一步：`created_at` 和 `createdAt` 是两个不同的字符串，没开转驼峰时 MyBatis 找不到叫 `createdAt` 的属性，**不报错，直接跳过**，字段停在 Java 默认值上——`createdAt` 是 null，`tCount` 是 false。于是「真的是 0」和「根本没赋值」在对象上长得一模一样。

这类症状的排查顺序：

| 症状 | 先看哪 |
| --- | --- |
| 只有带下划线的字段是 null / false，别的字段正常，不报错 | 下划线转驼峰开关（`mapUnderscoreToCamelCase`，默认 false），以及列名和字段名是否真对得上 |
| 抛 `NumberFormatException: For input string: "created_at DESC"` | SQL 里是不是写了 `ORDER BY #{...}`，占位符被当成排序序号了 |
| SQL 日志里的引号、`OR 1=1` 是你传进去的原文 | SQL 里用了 `${...}`，值参与了语法解析 |

上面这三段输出和下面全部输出都来自同一台机器：Temurin 25.0.4、Spring Boot 4.1.1、mybatis-spring-boot-starter 4.1.0、H2 2.4.240，数据就是那四行。

## mapper 接口没有实现类，注入进来的是一个代理

上面那个 `BookMapper` 没有实现类，却能注入进 `@Component` 直接调。把注入进来的对象拆开看：

```text
mapper.getClass().getName() | jdk.proxy2.$Proxy51
Proxy.isProxyClass | true
InvocationHandler | org.apache.ibatis.binding.MapperProxy
```

三条信息：类名是 `jdk.proxy2.$Proxy51`（编号每次启动都不一样，但形态是 `jdk.proxy2.$Proxy` 加数字，磁盘上没有这个 `.java`）；`Proxy.isProxyClass` 为 true，说明它是 `Proxy.newProxyInstance` 造出来的；真正接住调用的是 `org.apache.ibatis.binding.MapperProxy`。

这是第 10 课那个动态代理的一个用户，但它跟 `@Transactional` 的代理不是同一套东西。`@Transactional` 的代理由 Spring 的 `AbstractAutoProxyCreator` 在 bean 初始化后织入；mapper 的代理来自 MyBatis：`mybatis-spring-boot-starter` 扫到 `@Mapper` 接口（或者你用 `@MapperScan` 指定包），每个接口注册成一个 `MapperFactoryBean`，注入时 `getObject()` 把代理交出来。两者唯一的共同点是「都用了 JDK 动态代理」。所以 `new BookMapper()` 编译不过，你也拿不到一个别的实现。

代理本身干的事很薄——按方法找到启动时就解析好的 `MappedStatement`，然后交给 `SqlSession`。参数绑定、执行、结果映射都在后面：

```text
service 调 mapper.findAll()
  → 代理按方法找到 MappedStatement（注解里的 SQL 在启动时已经成了固定文本，参数位置也定了）
  → SqlSessionTemplate → SqlSession → Executor
  → 从当前事务拿连接；没有事务就借一个，用完还（第 17 课）
  → prepareStatement、按顺序 setXxx、executeQuery
  → ResultSetHandler 按列名反射填进 Book
```

倒数第二步那个连接是「当前事务的连接」，所以 `@Transactional` 加在 service 上时，里面对 mapper 的三次调用仍在同一个事务里（第 12 课）。

## 跑一遍：手写「接口 + SQL + 反射填值」

去掉 MyBatis 和数据库，只留接口、SQL、反射三样。一行的数据就是一个 `Map`，形状跟从 `ResultSet` 里读出来的差不多：

<!-- run -->
```java
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    public static class Book {
        private Long id;
        private String title;
        private LocalDateTime createdAt;

        public void setId(Long id) { this.id = id; }
        public void setTitle(String title) { this.title = title; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public String toString() {
            return id + "/" + title + "/" + createdAt;
        }
    }

    interface BookMapper {
        List<Book> findByTitle(String keyword);
    }

    static Map<String, Object> row(long id, String title, String createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", title);
        row.put("created_at", createdAt);
        return row;
    }

    static String toCamel(String column) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    // 一行的 Map -> 对象：列名当字段名，类型按字段类型转
    static Book fill(Map<String, Object> row, boolean camel) throws ReflectiveOperationException {
        Book book = new Book();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String property = camel ? toCamel(entry.getKey()) : entry.getKey();
            Field field = Book.class.getDeclaredField(property);
            field.setAccessible(true);
            field.set(book, convert(entry.getValue(), field.getType()));
        }
        return book;
    }

    static Object convert(Object value, Class<?> type) {
        if (value instanceof String text && type == LocalDateTime.class) {
            return LocalDateTime.parse(text);
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(1, "深入理解 Java 虚拟机", "2024-03-01T09:00"));
        rows.add(row(2, "Spring 实战", "2024-05-20T09:00"));

        try {
            fill(rows.get(0), false);
        } catch (NoSuchFieldException e) {
            System.out.println("直接拿列名找字段：" + e);
        }
        for (Map<String, Object> row : rows) {
            System.out.println("转驼峰后：" + fill(row, true));
        }

        InvocationHandler handler = (proxy, method, params) -> {
            System.out.println("代理收到的 SQL：SELECT id, title, created_at FROM book WHERE title LIKE ?");
            String keyword = (String) params[0];
            List<Book> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (String.valueOf(row.get("title")).contains(keyword)) {
                    result.add(fill(row, true));
                }
            }
            return result;
        };
        BookMapper mapper = (BookMapper) Proxy.newProxyInstance(
                Main.class.getClassLoader(), new Class<?>[] {BookMapper.class}, handler);
        System.out.println("是代理对象：" + Proxy.isProxyClass(mapper.getClass()));
        System.out.println("接口：" + mapper.getClass().getInterfaces()[0].getSimpleName());
        System.out.println("查出：" + mapper.findByTitle("Java"));
    }
}
```
```text
直接拿列名找字段：java.lang.NoSuchFieldException: created_at
转驼峰后：1/深入理解 Java 虚拟机/2024-03-01T09:00
转驼峰后：2/Spring 实战/2024-05-20T09:00
是代理对象：true
接口：BookMapper
代理收到的 SQL：SELECT id, title, created_at FROM book WHERE title LIKE ?
查出：[1/深入理解 Java 虚拟机/2024-03-01T09:00]
```

手写版把同一个问题摔成了异常：`fill(row, false)` 直接拿列名当字段名，`getDeclaredField("created_at")` 抛 `NoSuchFieldException`；`fill(row, true)` 先做 `created_at` → `createdAt` 再找，两行都填上了，连 `2024-03-01T09:00` 到 `LocalDateTime` 的转换也没问题。

MyBatis 遇到同一件事不会抛，而是把一个 null 交给你：自动映射发现「这一列找不到对应属性」就直接跳过（`autoMappingUnknownColumnBehavior` 默认是 `NONE`，我这台机器启动日志里没有任何提示）。所以「字段莫名是 null / false」的第一嫌疑是名字没对上，不是类型转换。

手写这版也就到此为止：`toCamel` 和 `convert` 是这层的活，一个管名字一个管类型，而我这里只写了「String → LocalDateTime」这一种转换。真实框架还要处理 NULL、包装类、嵌套对象、泛型返回类型，那是框架长肉的地方。

本项目是开开关解决的：`backend/paideia-persistence/src/main/java/io/github/xiaou61/persistence/MyBatisCustomization.java` 第 17 行是 `configuration.setMapUnderscoreToCamelCase(true)`，全局生效，mapper 里不用写别名。

## `#{}` 与 `${}`：一个字符，两种命运

同一条查询，两种写法：

```java
@Select("SELECT id, title, created_at FROM book WHERE title LIKE CONCAT('%', #{kw}, '%')")
List<Book> searchBound(@Param("kw") String kw);

@Select("SELECT id, title, created_at FROM book WHERE title LIKE '%${kw}%'")
List<Book> searchSpliced(@Param("kw") String kw);
```

| mapper 里写 | 执行时发生什么 | 传 `x%' OR 1=1 --` 的结果 |
| --- | --- | --- |
| `#{kw}` | 变成 `?`，再 `setString(1, kw)` | 0 行 |
| `${kw}` | 值原样拼进 SQL 文本，再去解析 | 4 行，全表 |

先传一个教科书 payload `x' OR '1'='1`：

```text
spliced SQL a1 | SELECT id, title, created_at FROM book WHERE title LIKE '%x' OR '1'='1%'
bound a1 rows | 0
spliced a1 rows | 0
```

两个都是 0 行。这里我猜错了——我以为它会把四行全拉出来。实际拼出来的 SQL 是 `LIKE '%x' OR '1'='1%'`：后半段 `'1'='1%'` 是字符串比较，假；前半段 `title LIKE '%x'` 也假。payload 和骨架不匹配，就注不进去。

把注释符带上就进去了：

```text
spliced SQL a2 | SELECT id, title, created_at FROM book WHERE title LIKE '%x%' OR 1=1 --%'
bound a2 rows | 0
spliced a2 rows | 4
```

`--` 把后面多出来的 `%'` 注释掉，剩下 `OR 1=1` 恒真，四行全出。同一个输入走 `#{}` 还是 0 行，因为它从头到尾只是一个要比较的字符串。

这也顺带否掉了我原来那条自查法——「传 `x' OR '1'='1`，看返回是不是全表」。在 `LIKE '%…%'` 这种骨架上它给的是 0 行，你会以为没事。

## `${}` 只能用在这些地方，而且必须配白名单

`PreparedStatement` 的 `?` 只能落在「值」的位置，落不到列名、表名、排序键上。这不是 MyBatis 的规矩，是语句执行本身的形状：语句结构在解析时就定了，占位符替换的是数据。

所以 `ORDER BY` 想换列名只剩拼接。把 `#{}` 硬塞进去，传 `created_at DESC`：

```text
ORDER BY #{} | DataIntegrityViolationException <- NumberFormatException: For input string: "created_at DESC"
```

H2 把 `?` 当成了排序项的位置序号，于是去解析 `"created_at DESC"` 里的数字，抛 `NumberFormatException`，外层被 Spring 包成 `DataIntegrityViolationException`。换成 `${}`：

```text
ORDER BY ${} created_at ASC | [4:Spring 源码深度解析:2024-01-05T09:00, 1:深入理解 Java 虚拟机:2024-03-01T09:00, 2:Spring 实战:2024-05-20T09:00, 3:Java 并发编程实战:2024-07-11T09:00]
ORDER BY ${} id DESC | [4:Spring 源码深度解析:2024-01-05T09:00, 3:Java 并发编程实战:2024-07-11T09:00, 2:Spring 实战:2024-05-20T09:00, 1:深入理解 Java 虚拟机:2024-03-01T09:00]
```

两次真的换了顺序。代价是排序键成了 SQL 文本的一部分——调用方传什么就拼什么。

本项目干脆不让调用方传列名：`backend/paideia-account/src/main/java/io/github/xiaou61/account/internal/user/UserMapper.java` 里分页查询的排序写死成 `ORDER BY created_at DESC, id DESC`，注释是「排序不接受调用方传来的列名」；同一个文件里的模糊搜索用 `LIKE CONCAT('%', #{query}, '%')`，百分号在 SQL 里加、不在 Java 里拼，这样用户输入里的 `%` 和 `_` 不会变成通配符。

真需要动态排序列时，白名单是唯一那道门：

```java
private static final Set<String> SORTABLE = Set.of("created_at", "title", "id");

String column = SORTABLE.contains(sort) ? sort : "id";
```

mapper 里还是 `${column} DESC`，但进到 SQL 里的只可能是 `SORTABLE` 里的三个字符串之一。排序方向同理，别把请求里的 `DESC` / `ASC` 原样拼进去。

## 动手

下面这条查询有注入风险。找出最该改的那一行：

```code-challenge
{
  "id": "spring-18-bug",
  "kind": "bug",
  "title": "排序参数直接拼进 SQL",
  "code": "@Mapper\npublic interface BookMapper {\n\n    @Select(\"SELECT id, title FROM book ORDER BY ${sortColumn} DESC\")\n    List<Book> list(@Param(\"sortColumn\") String sortColumn);\n}\n\nclass BookController {\n    private final BookMapper mapper;\n\n    @GetMapping(\"/books\")\n    List<Book> list(@RequestParam(\"sort\") String sort) {\n        return mapper.list(sort);\n    }\n}",
  "bugLine": 4,
  "explain": "第 4 行把 `${sortColumn}` 接在 `ORDER BY` 上，而它的值直接从 `@RequestParam` 进来，等于把请求参数接到了 SQL 文本里。列名不能用 `#{}`——上面实测会抛 `NumberFormatException`——所以正确做法是保留 `${}` 但加白名单：先确认 `sort` 在允许排序的列集合里，不在就用默认列或抛 400；排序方向也别从请求里原样拼。"
}
```

**提示**：`#{}` 只能替换值，不能替换列名——所以这里不能简单地把 `${}` 改成 `#{}`。

## 检查一下

```quiz
{
  "id": "spring-18-quiz",
  "title": "映射三问",
  "questions": [
    {
      "question": "什么情况下 `#{}` 用不了？",
      "options": ["任何情况下都能用", "参数是字符串的时候", "要替换列名、表名或排序键的时候"],
      "answer": 2,
      "explain": "占位符只能落在「值」的位置。标识符必须拼进 SQL 文本，所以只能用 `${}`，代价是自己加白名单。"
    },
    {
      "question": "`findAll()` 拿回来的对象里只有 `createdAt` 是 null，SQL 日志里 `created_at` 明明查出来了。先查什么？",
      "options": ["下划线转驼峰开关，以及列名和字段名对不对得上", "TINYINT 转 boolean 坏了", "把 SQL 改成 SELECT *"],
      "answer": 0,
      "explain": "自动映射对不上的列是静默跳过的，字段停在 Java 默认值。类型转换本身没问题：开关打开后 `t_count` 的 1/0 正确变成了 true/false。"
    },
    {
      "question": "mapper 接口没有实现类，注入进来的那个代理是谁造的？",
      "options": ["Spring 的 AOP 自动代理", "MyBatis 的 MapperFactoryBean 在注册 bean 时造的 JDK 动态代理", "编译期的注解处理器"],
      "answer": 1,
      "explain": "处理器是 `org.apache.ibatis.binding.MapperProxy`。它和 `@Transactional` 的代理只是都用了 JDK 动态代理，来路不一样。"
    }
  ]
}
```

## 还差的

- 只在 H2 2.4.240 上验过。`ORDER BY #{}` 在 MySQL 上是抛异常还是静默当常量排序（等于不生效），我没验证；能确定的只是两种结果都不会按你传的列名排。
- `t_count` 的 TINYINT → boolean 是 H2 的转换行为。MySQL 的 `TINYINT(1)` 通过 JDBC 拿到什么类型、能不能直接填 `boolean`，我这里没有实例可测，不写结论。
- 注入只试了两个 payload。带 `;` 的多语句在默认连接参数下能不能执行，取决于驱动和数据库，我没验，正文也就没说。
- 手写那版只覆盖一层的简单字段。嵌套对象、`association` / `collection`、泛型返回类型全跳过了，那是框架真正长肉的地方。
- `autoMappingUnknownColumnBehavior` 我只观察到「静默」，没把它改成 `WARNING` / `FAILING` 确认会不会报，也没确认 Boot 默认日志级别下打不打得出来。
