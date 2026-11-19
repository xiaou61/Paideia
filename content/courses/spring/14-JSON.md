---
title: 14 · JSON
order: 14
chapter: Web
level: intermediate
category: spring-from-zero
prerequisite: 13 · 全局异常与参数校验
minutes: 20
status: published
---

# 14 · JSON

## 500 的那次，撞上的是嵌套上限

JDK 25.0.4 + Spring Boot 4.1.1（Spring Framework 7.0.9）的最小 demo，服务在 18080。`/books/1` 返回一本书，书属于一个分类，分类里又挂着自己的书：

```java
public class Book {
    private Category category;      // 书 → 分类
}

public class Category {
    private List<Book> books;       // 分类 → 书，反向
}
```

请求原样跑：

```text
$ curl -i localhost:18080/books/1
HTTP/1.1 500
Content-Type: application/json
Transfer-Encoding: chunked
Connection: close

{"timestamp":"2026-11-19T06:50:06.061Z","status":500,"error":"Internal Server Error","path":"/books/1"}
```

我第一反应是 Jackson 无限递归栈溢出。日志里不是那回事：

```text
WARN ... DefaultHandlerExceptionResolver : Resolved [org.springframework.http.converter.HttpMessageNotWritableException: Could not write JSON: Document nesting depth (501) exceeds the maximum allowed (500, from `StreamWriteConstraints.getMaxNestingDepth()`)]
```

写第 501 层时 Jackson 自己抛出异常，深度上限是 500。它没有把栈压爆——真栈溢出时日志里是 `StackOverflowError`，线程直接死，那条兜底的 `/error` 响应都不一定来得及写。这个区分有用：看见 500 且响应体是 `/error` 的默认结构（13 课拆过这个结构），先去日志里搜这行，别先怀疑内存。

## 把反向那一侧断开，还剩三个问题

修法放到「动手」里，先把 `Category.books` 用 `@JsonIgnore` 断开，再请求：

```text
$ curl -s localhost:18080/books/1/fixed
{"category":{"name":"JVM"},"id":1,"isbn":null,"pages":480,"publishedAt":"2025-01-15T09:30:00","title":"深入理解 Java 虚拟机"}
```

这一行里有三件常被算到前端头上的事：

- `isbn` 是 null，照样输出成 `"isbn":null`；
- `publishedAt` 是 `2025-01-15T09:30:00`，没有 `Z`，也没有偏移量；
- 字段顺序是 category、id、isbn、pages、publishedAt、title，字母序，跟类里声明的顺序无关。

前两件各占一节：null 和空集合怎么配、日期到底带不带时区。字段顺序不单开一节，只记一句——它是字母序不是声明顺序，别拿它做快照断言。

## 字段名对不上：200，值是 null

契约上这个字段叫 `book_name`，后端字段是 `bookName`，加了 `@JsonProperty("book_name")`。前端某天统一改成了驼峰，发过来：

```text
$ curl -s -X POST localhost:18080/snake -H 'Content-Type: application/json' -d '{"bookName":"深入理解 Java 虚拟机"}'
{"book_name":null,"pages":null}
```

200，没有警告，日志干净，值是 null。字段名对上就正常：

```text
$ curl -s -X POST localhost:18080/snake -H 'Content-Type: application/json' -d '{"book_name":"深入理解 Java 虚拟机","pages":480}'
{"book_name":"深入理解 Java 虚拟机","pages":480}
```

我原以为「字段不认识会报错」。默认不报：`FAIL_ON_UNKNOWN_PROPERTIES` 在 Spring Boot 的自动配置里是关掉的。要它报，得自己打开：

```yaml
spring:
  jackson:
    deserialization:
      fail-on-unknown-properties: true
```

同一个请求立刻变成 400：

```text
$ curl -i -X POST localhost:18080/snake -H 'Content-Type: application/json' -d '{"bookName":"深入理解 Java 虚拟机"}'
HTTP/1.1 400
...

WARN ... DefaultHandlerExceptionResolver : Resolved [org.springframework.http.converter.HttpMessageNotReadableException: JSON parse error: Unrecognized property "bookName" (class demo.ProbeApplication$SnakeBook), not marked as ignorable]
```

只在「改过配置」之后成立。没开的时候，同一条错误路径上什么都不会打印。

命名策略是另一个开关，而且**双向**都生效：

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

打开后 `/books/1/fixed` 输出里变成 `"published_at":"2025-01-15T09:30:00"`；请求这一侧也跟着变，同一个 POST，发 `publishedAt`（驼峰）绑不上、发 `published_at` 才绑得上：

```text
$ curl -s -X POST localhost:18080/books -H 'Content-Type: application/json' -d '{"title":"深入理解 Java 虚拟机","pages":480,"publishedAt":"2025-01-15T09:30:00"}'
{"category":null,"id":0,"isbn":null,"pages":480,"published_at":null,"title":"深入理解 Java 虚拟机"}

$ curl -s -X POST localhost:18080/books -H 'Content-Type: application/json' -d '{"title":"深入理解 Java 虚拟机","pages":480,"published_at":"2025-01-15T09:30:00"}'
{"category":null,"id":0,"isbn":null,"pages":480,"published_at":"2025-01-15T09:30:00","title":"深入理解 Java 虚拟机"}
```

两条都是 200，只有一条把时间绑上了。所以这个配置不能当「只改输出」用，一开就是前后端一起改；而 `@JsonProperty` 是逐字段的，优先级高于全局策略（上面那个 `book_name` 在 SNAKE_CASE 下照样绑得上）。

出问题时按现象查，比按注解名查快：

| 你看到的 | 先看哪里 |
| --- | --- |
| 200，字段是 null，日志没有 WARN | 请求体的 key 与 `@JsonProperty` / 命名策略是否一致（strict 关着，不会报） |
| 400，日志 `Unrecognized property "xxx"` | 开了 `fail-on-unknown-properties`，请求体多发了字段 |
| 400，日志 `Cannot deserialize value of type int from String "很多"` | 类型不对，`"pages":"很多"` 这种 |
| 400，日志 `could not be parsed at index 10` | 日期字符串少了个 `T`，见下面日期那节 |

## null、空集合、空对象：`NON_EMPTY` 会把数组一起删掉

`@JsonInclude` 三个档，实测同一个空分类的返回体：

| 类上标的 | `books` 为空时 | `isbn` 为 null 时 |
| --- | --- | --- |
| 不标（默认 `ALWAYS`） | `"books":[]` | `"isbn":null` |
| `@JsonInclude(NON_NULL)` | `"books":[]` | 字段消失 |
| `@JsonInclude(NON_EMPTY)` | 字段消失 | 字段消失 |

```text
不标：      {"books":[],"name":"JVM"}
NON_NULL：  {"books":[],"name":"JVM"}
NON_EMPTY： {"name":"JVM"}
```

`NON_EMPTY` 那行是坑：字段消失后，前端 `data.books.length` 直接抛 `TypeError: Cannot read properties of undefined`。`data.books` 是 null 时一样抛，所以「炸」这件事两者都躲不掉；差别在别的判断上——`if (data.books)` 两边都进不去，但「字段不存在」会让 `'books' in data` 为 false，而 null 时它是 true。契约里写的是「永远有 books 这个字段」，那就得是 `NON_NULL`。

所以要的是「集合永远是数组」，这个约束落在 null 上就够：用 `NON_NULL`，别用 `NON_EMPTY`。`NON_EMPTY` 的语义是「空值一律不输出」，它不会区分「没有这个列表」和「列表是空的」。

## 日期：默认已经是 ISO 字符串

「Jackson 默认把 `java.util.Date` 写成时间戳、把 `LocalDateTime` 写成数组」这句话流传很广，我照抄过。在 Boot 4.1.1 上它是错的。四个时间类型同一个接口打出来：

```text
$ curl -s localhost:18080/clock
{"instant":"2025-01-15T01:30:00Z","legacy":"2025-01-15T01:30:00.000Z","local":"2025-01-15T09:30:00","offset":"2025-01-15T09:30:00+08:00"}
```

`legacy` 是 `java.util.Date`，它输出的是 ISO 字符串 `2025-01-15T01:30:00.000Z`，不是数字。一行配置都没加。

那「ISO-8601 自带时区」这句呢？也不能笼统说。下面这段只用 JDK：

<!-- run -->
```java
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.TimeZone;

public class Main {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        Instant instant = Instant.parse("2025-01-15T01:30:00Z");
        LocalDateTime local = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
        OffsetDateTime offset = local.atOffset(ZoneOffset.ofHours(8));
        Date legacy = Date.from(instant);

        System.out.println("Instant        " + instant);
        System.out.println("LocalDateTime  " + local);
        System.out.println("OffsetDateTime " + offset);
        System.out.println("Date.toString  " + legacy);
        System.out.println("Date.getTime   " + legacy.getTime());
    }
}
```
```text
Instant        2025-01-15T01:30:00Z
LocalDateTime  2025-01-15T09:30
OffsetDateTime 2025-01-15T09:30+08:00
Date.toString  Wed Jan 15 09:30:00 CST 2025
Date.getTime   1736904600000
```

两件事：

- `LocalDateTime` 的字符串里没有偏移量。`2025-01-15T09:30:00` 单独拿出来只是一个墙上时间，还原不了「这一刻」；带偏移量的是 `Instant`（`Z`）和 `OffsetDateTime`（`+08:00`）。
- 顺带一个细节：同一个值，`toString()` 打印 `09:30`（秒为 0 就省了），Jackson 写出来是 `09:30:00`。ISO 允许前一种，手写解析器不一定都认。

所以要跨时区不丢信息，字段类型得是 `Instant` 或 `OffsetDateTime`；用 `LocalDateTime` 就是在契约里约定「这个字段一律按某个时区解释」。

### 想让日期回退成时间戳，那行老配置会拦住启动

Boot 2/3 时代到处贴的是这段：

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
```

在 4.1.1 上它不是「不生效」，是应用直接起不来：

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Failed to bind properties under 'spring.jackson.serialization' to java.util.Map<tools.jackson.databind.SerializationFeature, java.lang.Boolean>:

    Reason: failed to convert java.lang.String to tools.jackson.databind.SerializationFeature (caused by java.lang.IllegalArgumentException: No enum constant tools.jackson.databind.SerializationFeature.write-dates-as-timestamps)
```

原因在下一段：Boot 4 用的是 Jackson 3（实测依赖 `jackson-databind 3.1.5`、`jackson-annotations 2.21`）。Jackson 3 把日期开关从 `SerializationFeature` 挪到了 `DateTimeFeature`，而 `spring.jackson.serialization.*` 只认 `SerializationFeature` 的枚举名，绑不上就启动失败。注解还在 `com.fasterxml.jackson.annotation` 包里，换包的是 `tools.jackson.databind`。

真要在 Boot 4 上开时间戳，我没找到能用的配置键：`JacksonProperties` 里只有 `serialization` / `deserialization` / `mapper` 这几张按枚举名映射的表，没有放 `DateTimeFeature` 的位置。实际能走通的是代码：

```java
import tools.jackson.databind.cfg.DateTimeFeature;

@Bean
JsonMapperBuilderCustomizer datesAsTimestamps() {
    return builder -> builder.enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
}
```

打开之后同一个 `/clock`：

```text
{"instant":1736904600.000000000,"legacy":1736904600000,"local":[2025,1,15,9,30],"offset":1736904600.000000000}
```

`LocalDateTime` 变成数组 `[2025,1,15,9,30]` 的现场在这里——它不是默认行为，是这个开关打开之后的行为。三种类型三种形态：`Date` 是整数毫秒，`Instant` / `OffsetDateTime` 是「秒.纳秒」。把「默认值」和「开关之后的值」混着讲，就成了半句谣言。

（`spring.jackson.use-jackson2-defaults` 这个属性我没试。`JacksonProperties` 里确实有它，但打开之后会不会把日期行为一起恢复成 Jackson 2 的样子，没验证。）

还有一个我只验到一半的开关：`spring.jackson.date-format`。它只作用于 `java.util.Date`，不碰 `java.time`：

| 配置 | `java.util.Date` | `Instant` | `LocalDateTime` |
| --- | --- | --- | --- |
| 默认 | `"2025-01-15T01:30:00.000Z"` | `"2025-01-15T01:30:00Z"` | `"2025-01-15T09:30:00"` |
| `--spring.jackson.date-format=yyyy/MM/dd'T'HH:mm:ss` | `"2025/01/15T01:30:00"` | 不变 | 不变 |

副作用比想象的大：它连框架自己的 `/error` 响应体一起改。同一个 `"pages":"很多"` 的 400，body 里的 `timestamp` 从 `2026-11-19T06:56:52.xxxZ` 变成 `2026/11/19T06:56:52`。所以这不是「只影响我的 DTO」的开关。

## 类型写错时，日志比响应体有用

400 的响应体只有 `{"timestamp":...,"status":400,"error":"Bad Request","path":"/books"}`，看不出哪个字段。日志里有：

```text
WARN ... : Resolved [org.springframework.http.converter.HttpMessageNotReadableException: JSON parse error: Cannot deserialize value of type `int` from String "很多": not a valid `int` value]
```

日期少了 `T`（写成 `2025-01-15 09:30:00`）也走同一条路，尾巴上是：

```text
WARN ... JSON parse error: Cannot deserialize value of type `java.time.LocalDateTime` from String "2025-01-15 09:30:00": ... (java.time.format.DateTimeParseException) Text '2025-01-15 09:30:00' could not be parsed at index 10]
```

`index 10` 就是空格那个位置——`2025-01-15` 是 10 个字符。这条消息比「400 Bad Request」有用得多。为什么客户端发错数据是 400 而不是 500，13 课已经拆过，这里只记一句：先看 WARN，不要盯响应体。

## 动手

下面这个详情接口返回 500，响应体是 `/error` 的默认结构，日志里是 `Document nesting depth (501)`。找出最该改的那一行：

```code-challenge
{
  "id": "spring-14-bug",
  "kind": "bug",
  "title": "书详情接口 500：两个方向互相指",
  "code": "public class Book {\n\n    private Long id;\n    private String title;\n    private Category category;\n\n    public Category getCategory() {\n        return category;\n    }\n}\n\nclass Category {\n\n    private String name;\n    private List<Book> books = new ArrayList<>();\n\n    public String getName() {\n        return name;\n    }\n\n    public List<Book> getBooks() {\n        return books;\n    }\n}",
  "bugLine": 15,
  "explain": "第 15 行的反向引用进了序列化：Book → category → books → 每个 Book 的 category → ……，写第 501 层时 Jackson 抛出 nesting depth，接口 500。修法一：在这一行加 `@JsonIgnore`（实测有效），让这个方向不出现；修法二：这个方向的 getter 根本不暴露；更彻底是给详情接口写自己的响应对象，把 JSON 契约和领域对象分开——那样加字段也不会悄悄改 API。别指望「给字段换个名字」能解决，名字和环没关系。"
}
```

**提示**：`@JsonIgnore` 只是让那一侧不输出，不是「删掉数据」。另一个接口要这个方向的数据时，还得另开一个响应对象。

## 检查一下

```quiz
{
  "id": "spring-14-quiz",
  "title": "JSON 三问",
  "questions": [
    {
      "question": "没加任何配置，Boot 4.1.1 把 `LocalDateTime` 字段写成什么？",
      "options": ["数组 [2025,1,15,9,30]", "毫秒时间戳数字", "ISO 字符串 2025-01-15T09:30:00，不带偏移量"],
      "answer": 2,
      "explain": "实测默认是 ISO 字符串。数组形态只在打开 `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` 之后才出现，而且那时 Instant 是「秒.纳秒」、Date 是整数毫秒，三种类型的形态还不一样。"
    },
    {
      "question": "后端字段是 `@JsonProperty` 标注的 book_name，前端发的是 bookName，没人开 strict，会怎样？",
      "options": ["400，字段不认识", "200，字段是 null，日志无警告", "200，字段正常绑上"],
      "answer": 1,
      "explain": "`FAIL_ON_UNKNOWN_PROPERTIES` 默认关着，认不出的属性被静默跳过，结果是 200 加一个 null。要报错得显式开 `spring.jackson.deserialization.fail-on-unknown-properties`。"
    },
    {
      "question": "类上标了 `@JsonInclude(NON_EMPTY)`，`books` 是空列表，前端收到什么？",
      "options": ["`books` 字段整个消失，`data.books.length` 抛 TypeError", "`books: []`", "`books: null`"],
      "answer": 0,
      "explain": "实测返回体里只剩 name，books 字段没有了。空集合也算「空」，所以被一起删掉。要保证集合永远是数组，用 NON_NULL 而不是 NON_EMPTY。"
    }
  ]
}
```

## 还差的

- 没验证 3.5.x：实测是 Temurin 25.0.4 + Spring Boot 4.1.1 / Spring Framework 7.0.9 / jackson-databind 3.1.5。Boot 3.5.x 用 Jackson 2，`spring.jackson.serialization.write-dates-as-timestamps` 大概是能用的；照抄前先对齐版本，别把 4.x 的结论套到 3.x。
- `@JsonManagedReference` / `@JsonBackReference` 和 `@JsonView` 都没跑：正文只给了 `@JsonIgnore` 和「单独写响应对象」这两条实测过的路，其余两条是听说。
- 嵌套上限 500 只验到「第 501 层报错」：没试怎么调 `StreamWriteConstraints` 的默认值，也没测一个正常的超大对象（树形结构几百层）会不会被误伤。
- 前端那半只是按规则推断：`data.books.length` 抛 TypeError、`new Date("2025-01-15 09:30:00")` 在浏览器里可能被宽容解析（Chrome 与 Safari 不一样），我没在真浏览器里跑这两句。
- 没接持久层：demo 全是内存对象，双向关联是手写的。JPA/Hibernate 的懒加载代理过一遍 Jackson 会触发什么，我在 17、18 课之前给不出实测。
