---
title: 18 · 手写 MyBatis 映射
order: 18
chapter: 数据
level: intermediate
category: spring-from-zero
prerequisite: 17 · JdbcTemplate 与连接池
minutes: 30
status: published
---

# 18 · 手写 MyBatis 映射

## 这一课你要会

- 说清 MyBatis 的 mapper 接口没有实现类，运行时那个对象是什么；
- 说清 `#{}` 与 `${}` 的机制差别，以及后者为什么是注入漏洞；
- 手写一遍"接口 + SQL 字符串 + 反射填值"的映射器；
- 知道本项目为什么要求"SQL 只写在 mapper 里，且禁 MyBatis-Plus"。

## 为什么

上一课的 `RowMapper` 是手写的：每个查询都要写一遍"怎么把一行变成对象"。字段一多，这段代码就是重复劳动，而且改列名时容易漏。

MyBatis 把这一步自动做了：**你只写接口和 SQL，映射由反射完成**。但它的两套占位符（`#{}` 与 `${}`）在表面上只差一个字符，一个是安全绑定、一个是直接拼字符串——这是本项目最重要的一条安全纪律的由来。

## 讲解

### mapper 接口没有实现类

```sketch
{
  "title": "mapper 没有实现类，运行时给它造一个",
  "rows": [
    [
      { "id": "iface", "title": "mapper 接口", "sub": "只有方法签名", "tone": "gray" },
      { "id": "proxy", "title": "启动时生成代理", "sub": "按注解或 XML 找 SQL", "tone": "blue" },
      { "id": "run", "title": "调方法就执行 SQL", "sub": "结果反射填进对象", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "iface", "to": "proxy" },
    { "from": "proxy", "to": "run" }
  ]
}
```

```java
@Mapper
public interface BookMapper {
    @Select("SELECT id, title FROM book WHERE id = #{id}")
    Book findById(@Param("id") long id);
}
```

这个接口没有实现类，但你注入了就能用。原因是**第 10 课的动态代理**：

```text
Spring 启动时扫到 @Mapper
  → 为每个接口生成一个代理对象（JDK 动态代理，接口正好满足前提）
  → 代理的处理器：读方法上的 SQL、把参数塞进占位符、执行、把结果反射填进返回类型
  → 把这个代理注册成 Bean
```

所以"mapper 接口能被注入"和"`@Transactional` 能生效"是同一套机制的两个应用。你现在应该对"为什么没实现类也能调"毫无疑问了。

### `#{}` 与 `${}`

```sketch
{
  "title": "#{} 走预编译，${} 直接拼进 SQL",
  "rows": [
    [
      { "id": "hash", "title": "#{title}", "sub": "变成占位符 ?", "tone": "green" },
      { "id": "dollar", "title": "${title}", "sub": "原样拼进去", "tone": "amber" },
      { "id": "risk", "title": "拼接就会中注入", "sub": "只有表名列名才考虑", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "hash", "to": "dollar" },
    { "from": "dollar", "to": "risk" }
  ]
}
```

| 写法 | 底层 | 例子 |
| --- | --- | --- |
| `#{title}` | `PreparedStatement.setString(1, title)` | 值永远不参与 SQL 解析 |
| `${title}` | 字符串直接拼进 SQL | 值成为 SQL 的一部分 |

<!-- run -->
```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    static final class Table {
        final List<Map<String, Object>> rows = new ArrayList<>();

        Table add(long id, String title) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("title", title);
            rows.add(row);
            return this;
        }
    }

    // 等价于 #{}：值被当成数据，只做字符串比较
    static List<Book> boundQuery(Table table, String keyword) {
        List<Book> result = new ArrayList<>();
        for (Map<String, Object> row : table.rows) {
            String title = (String) row.get("title");
            if (title.contains(keyword)) {
                result.add(new Book(((Number) row.get("id")).longValue(), title));
            }
        }
        return result;
    }

    // 等价于 ${}：值被当成 SQL 语法的一部分拼进去
    static String splicedSql(String keyword) {
        return "SELECT id, title FROM book WHERE title LIKE '%" + keyword + "%'";
    }

    public static void main(String[] args) {
        Table table = new Table()
                .add(1, "深入理解 Java 虚拟机")
                .add(2, "Spring 实战");

        System.out.println("正常输入 " + boundQuery(table, "Java"));
        System.out.println("正常输入 " + boundQuery(table, "Spring"));

        String attack = "x' OR '1'='1";
        System.out.println("拼接后的 SQL: " + splicedSql(attack));
        System.out.println("同一个输入走 #{}: " + boundQuery(table, attack).size() + " 条");
    }
}
```
```text
正常输入 [1:深入理解 Java 虚拟机]
正常输入 [2:Spring 实战]
拼接后的 SQL: SELECT id, title FROM book WHERE title LIKE '%x' OR '1'='1%'
同一个输入走 #{}: 0 条
```

第三行就是漏洞的样子：`'x' OR '1'='1'` 把 `LIKE` 条件变成了恒真，**所有行都会被返回**。如果这是一个登录查询，或者后面还跟着 `DELETE`，结果就完全不同了。

第四行是同一份输入走 `#{}` 的结果：`0 条`——它被当成一个普通的字符串去比较，没有变成语法。

### 那 `${}` 还有存在必要吗

有，但只在"值不是数据，而是标识符"的时候：

```sql
ORDER BY ${sortColumn}        -- 列名不能是占位符
${tableName}                  -- 表名不能是占位符
```

`PreparedStatement` 的占位符只能替换**值**，不能替换列名/表名。所以排序、动态表名这类需求只能用 `${}`。

代价是：**你必须自己保证这个值是白名单内的**。

```java
private static final Set<String> SORTABLE = Set.of("created_at", "title", "id");

if (!SORTABLE.contains(sortColumn)) {
    throw new IllegalArgumentException("不允许的排序列: " + sortColumn);
}
```

**没有这层白名单，就不要写 `${}`。** 本项目这条纪律是硬的：`rg '\$\{' ` 在 mapper 目录里出现的每一处都必须能指到一段白名单校验。

### 手写一遍映射（含反射填值）

MyBatis 干的第二件事是"把一行 Map 填进对象"。手写版只需要反射：

```text
读方法的返回类型 → 创建实例 → 对每一列，找同名（或转驼峰后同名）的 setter/字段 → 填值
```

这一段代码比较长，这里只列关键几步（真实实现还要处理泛型、嵌套、类型转换，那属于框架的活）：

```java
static Object fill(Class<?> type, Map<String, Object> row) throws ReflectiveOperationException {
    Object instance = type.getDeclaredConstructor().newInstance();
    for (Map.Entry<String, Object> entry : row.entrySet()) {
        String property = toCamel(entry.getKey());          // created_at → createdAt
        Field field = type.getDeclaredField(property);
        field.setAccessible(true);
        field.set(instance, convert(entry.getValue(), field.getType()));
    }
    return instance;
}
```

`toCamel` 与 `convert` 是这层真正的难点：

- **列名与字段名不一致**：`created_at` 与 `createdAt`。MyBatis 的开箱配置是"不自动转"，需要 `map-underscore-to-camel-case: true`。
- **类型不一致**：MySQL 的 `TINYINT(1)` 读出来是 `Byte`，字段是 `boolean`；`DATETIME` 读出来可能是 `LocalDateTime` 也可能是 `Timestamp`。这些转换散落在框架里，是很多"字段莫名是 0 或 false"的来源。

所以本项目**坚持手写 MyBatis、禁 MyBatis-Plus**，理由不是怀旧：

| | 手写 MyBatis | MyBatis-Plus |
| --- | --- | --- |
| SQL 在哪 | 全部在 mapper 里，可审查 | 大量由框架动态生成 |
| 慢查询定位 | 直接看 SQL | 要看生成的 SQL，还得拼参数 |
| 行为可预测 | 你写什么就是什么 | 内置方法与你的预期有偏差（如 `updateById` 忽略 null） |
| 依赖 | 一个 | 一个 + 更频繁的破坏性升级 |

**核心是"SQL 必须能被人读一遍"。** 动态生成的 SQL 让人无法审查注入风险与索引命中。

### 一个查询的完整链路

```text
Service 调 mapper.findById(42)
  → 代理：取方法上的 SQL
  → 把 #{id} 替换成 ?，参数列表 [42]
  → 从当前事务拿连接（没有事务就借一个，用完还）
  → prepareStatement → bind → executeQuery
  → 用返回类型 Book 反射建实例 → 按列名填值
  → 返回 Book
```

第 4 步是关键：**mapper 拿到的连接来自当前事务**。这就是为什么 `@Transactional` 加在 Service 上，mapper 的多次调用才在同一个事务里。

## 动手

下面这条查询有注入风险。找出最该改的那一行：

```code-challenge
{
  "id": "spring-18-bug",
  "kind": "bug",
  "title": "排序参数直接拼进 SQL",
  "code": "@Mapper\npublic interface BookMapper {\n\n    @Select(\"SELECT id, title FROM book ORDER BY ${sortColumn} DESC\")\n    List<Book> list(@Param(\"sortColumn\") String sortColumn);\n}\n\nclass BookController {\n    private final BookMapper mapper;\n\n    @GetMapping(\"/books\")\n    List<Book> list(@RequestParam(\"sort\") String sort) {\n        return mapper.list(sort);\n    }\n}",
  "bugLine": 4,
  "explain": "第 4 行用了 `${sortColumn}`，而它的值直接来自请求参数——用户可以把 `sortColumn` 传成 `id; DROP TABLE book` 之类的内容。列名确实不能用 `#{}`，所以正确做法是**保留 `${}` 但加白名单校验**：在 Controller 或 Service 里先确认 `sort` 属于允许排序的列集合（如 `created_at` / `title` / `id`），不在就抛 400。不加白名单就不要用 `${}`。"
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
      "question": "mapper 接口没有实现类，注入进来的是什么？",
      "options": ["一个空对象", "运行时生成的动态代理", "编译期生成的子类"],
      "answer": 1,
      "explain": "和 AOP 同一套机制：JDK 动态代理，处理器读 SQL、绑参数、反射填值。"
    },
    {
      "question": "${} 在什么情况下必须用？",
      "options": ["所有字符串参数", "替换列名、表名这类标识符时", "任何情况都不该用"],
      "answer": 1,
      "explain": "PreparedStatement 占位符只能替换值。标识符只能用拼接，但必须配白名单。"
    },
    {
      "question": "本项目禁用 MyBatis-Plus 的核心理由是？",
      "options": ["性能差", "SQL 大量由框架生成，无法审查注入风险与索引命中", "不兼容 Spring"],
      "answer": 1,
      "explain": "手写 MyBatis 让每条 SQL 都能被人读一遍。这是可审查性，不是偏好。"
    }
  ]
}
```

## 常见误区

### 误区：`#{}` 和 `${}` 都是占位符，效果一样

**你以为**：随便用，反正都能跑。
**其实**：`#{}` 走 `PreparedStatement` 的参数绑定，值不参与语法；`${}` 是字符串拼接，值成为语法。一个注入漏洞就从这个字符的差别长出来。
**怎么验证**：把一个字符串参数写成 `${}`，传 `x' OR '1'='1`，看返回是不是全部数据。

### 误区：动态 SQL 更灵活，所以能少写很多接口

**你以为**：一个 mapper 方法配 `<if>` 就能覆盖所有查询场景。
**其实**：每加一个分支，SQL 的可审查性就下降一档——你无法从代码里直接看出最终会执行什么。真正需要动态条件时，宁可有三个明确的方法。
**怎么验证**：看一个带 5 个 `<if>` 的查询，试着回答"排序字段会不会走索引"。答不出来就是复杂度已经超了。

### 误区：字段名与列名不一致时，MyBatis 会自动处理

**你以为**：`created_at` 会自己映射到 `createdAt`。
**其实**：默认不会。要么开 `map-underscore-to-camel-case: true`，要么在 SQL 里写别名。默认不开的表现是"对象里字段全是 null，但 SQL 明明查出了数据"。
**怎么验证**：故意用一个下划线列名，不加配置，看结果对象是否为空。

## 小结

mapper 接口没有实现类，靠的是动态代理；代理读 SQL、绑参数、反射填值。`#{}` 是安全的参数绑定，`${}` 是直接拼接，只能用于标识符且必须配白名单。本项目坚持手写 MyBatis、禁 MyBatis-Plus，核心是"每条 SQL 都能被人读一遍"。

下一章只剩两课：测试怎么写得快，以及打出来的 jar 为什么起不来。
