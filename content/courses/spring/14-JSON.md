---
title: 14 · JSON
order: 14
chapter: Web
level: intermediate
category: spring-from-zero
prerequisite: 13 · 全局异常与参数校验
minutes: 30
status: published
---

# 14 · JSON

## 这一课你要会

- 说清"对象变成 JSON"这一步发生在请求链的哪个位置；
- 手写一个反射序列化器，知道 Jackson 底下的动作就这些；
- 避开四个高频坑：字段名不一致、null 处理、日期格式、双向关联；
- 知道 `@JsonProperty` / `@JsonInclude` / `@JsonFormat` 各自解决哪一个坑。

## 为什么

JSON 是前后端之间唯一的契约。契约上任何一处不对，表现都是"接口 200，但前端拿不到数据"——比 500 更难查，因为没有错误信息。

四个坑的共同点是：**代码看起来都对**。字段名 camelCase 与 snake_case 不一致时，反序列化给的是 null 而不是报错；null 字段被输出成 `"key": null` 还是被省略，会影响前端 `hasOwnProperty` 的判断；日期序列化成一个时间戳数字时，前端的日期库会把它当毫秒。

## 讲解

### 这一步在请求链的哪一段

第 1 课说过"返回值类型决定用哪个转换器"。具体到 JSON：

```text
方法返回 Book 对象
   → returnValueHandler 发现返回值不是视图名
   → 选 HttpMessageConverter（这里选 Jackson 的 MappingJackson2HttpMessageConverter）
   → 用 ObjectMapper 把 Book 写成字节
   → 写进响应体，同时设置 Content-Type: application/json
```

所以"我的接口返回的不是 JSON"通常是三种原因之一：类上用了 `@Controller` 而不是 `@RestController`、返回值是 `String` 且被当成视图名、或者请求头 `Accept` 里没有 JSON。

### 手写一遍序列化

反射读字段、按类型决定怎么写、还有 null 要不要输出：

<!-- run -->
```java
import java.lang.reflect.Field;

public class Main {

    static class Book {
        String title;
        Integer pages;
        String isbn;

        Book(String title, Integer pages, String isbn) {
            this.title = title;
            this.pages = pages;
            this.isbn = isbn;
        }
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Field field : value.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object fieldValue;
            try {
                fieldValue = field.get(value);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
            if (fieldValue == null) {
                continue;
            }
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("\"").append(field.getName()).append("\":").append(toJson(fieldValue));
        }
        return json.append("}").toString();
    }

    public static void main(String[] args) {
        System.out.println(toJson(new Book("深入理解 Java 虚拟机", 480, null)));
        System.out.println(toJson(new Book("无 ISBN 的书", 120, "9787111544937")));
    }
}
```
```text
{"title":"深入理解 Java 虚拟机","pages":480}
{"title":"无 ISBN 的书","pages":120,"isbn":"9787111544937"}
```

第一行没有 `"isbn":null`——这是手写版的默认行为（跳过 null）。Jackson 的默认恰好相反：**它会把 null 也输出**。你看到的差异，就是你项目里需要一个 `@JsonInclude` 的原因。

### 坑一：字段名不一致

```java
public class Book {
    private String bookName;      // Java 侧
}
```

```json
{"book_name": "深入理解 Java 虚拟机"}     // 前端约定的
```

不处理的话，反序列化时 `bookName` 拿到 `null`，而且**不报错**。两种修法：

```java
// 逐字段改
@JsonProperty("book_name")
private String bookName;
```

```yaml
# 全局改成 snake_case（对前后端契约统一的项目更省事）
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

选一个，不要混着用——混用之后你会在两个文件里各找一遍字段名。

### 坑二：null 与空集合

```java
@JsonInclude(JsonInclude.Include.NON_NULL)   // 整个类生效
```

| 策略 | 效果 |
| --- | --- |
| `ALWAYS`（默认） | 输出 `"key": null` |
| `NON_NULL` | 省略 null 字段 |
| `NON_EMPTY` | 省略 null 与空集合、空字符串 |

为什么要统一：前端写 `if (data.list)` 时，`list: null` 与"没有 list 这个字段"在 JavaScript 里都是 falsy，看起来一样；但用 `Object.keys(data).length` 或者 TypeScript 的可选字段判断时，两者差很多。

**本项目的约定是 `NON_NULL`**：null 不输出，集合永远输出空数组而不是 null。这样前端只需要处理"数组可能是空的"，不需要处理"字段可能不存在"。

### 坑三：日期

```json
{"createdAt": 1735689600000}                    // 时间戳数字
{"createdAt": "2025-01-01T00:00:00Z"}           // ISO-8601 字符串
```

默认情况下 Jackson 把 `java.util.Date` 写成时间戳数字，把 `LocalDateTime` 写成数组 `[2025,1,1,0,0]`——后者是历史遗留行为，很多前端库完全不认识。

```java
@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
private OffsetDateTime publishedAt;
```

更好的做法是**全局关掉时间戳、统一用 ISO-8601**：

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
```

为什么 ISO-8601 更好：它自带时区信息（`Z` 或 `+08:00`），可读，能被所有语言直接解析。时间戳数字丢了时区，跨时区时必然出一次错。本项目所有的时刻都按 UTC 存、按 ISO-8601 输出。

### 坑四：双向关联

```java
public class Book {
    private Category category;
}

public class Category {
    private List<Book> books;      // 反向引用
}
```

序列化 `Book` 时进 `Category`，从 `Category` 又回到 `Book`……无限递归，栈溢出。三种修法：

```java
@JsonIgnore                              // 反向那一侧不序列化（最常用）
private List<Book> books;
```

```java
@JsonManagedReference / @JsonBackReference   // 成对标注，显式表达"父子"
```

```java
// 或者干脆不序列化实体，用一个专门的 DTO
```

**本项目推荐第三种**：接口返回的从来不是数据库实体，而是为这个接口定义的响应对象。实体是给持久层用的，它的字段会随库表变化；DTO 是契约，只随接口变化。用实体当响应体，加一个数据库字段就等于悄悄改了 API。

### 反序列化失败凭什么算 400

```json
{"pages": "很多"}
```

`pages` 是 `int`，收到字符串。Jackson 抛 `HttpMessageNotReadableException`，Spring 默认映射成 **400**。这条默认是对的——请求体不符合契约，是客户端的问题，不是服务端故障。

但如果你的全局异常处理器里加了一个 `@ExceptionHandler(Exception.class)` 兜底，它会不会抢走这条？不会——Spring 按"最具体"匹配，`HttpMessageNotReadableException` 有更近的父类 `ResponseStatusException` 一路继承到 `ResponseStatusException`/`ErrorResponse`，容器里有内部处理器优先匹配。**这就是"异常映射表要留兜底、但不该乱写宽泛分支"的原因。**

## 动手

下面这个接口的响应里，前端永远拿不到 `book_name`，因为它输出的是 `bookName` 且值是 null 之外的另一个问题。找出最该改的那一行：

```code-challenge
{
  "id": "spring-14-bug",
  "kind": "bug",
  "title": "前端拿不到 book_name",
  "code": "public class Book {\n\n    private String name;\n\n    private Integer pages;\n\n    public Book(String name, Integer pages) {\n        this.name = name;\n        this.pages = pages;\n    }\n\n    public String getName() {\n        return name;\n    }\n\n    public Integer getPages() {\n        return pages;\n    }\n}",
  "bugLine": 3,
  "explain": "第 3 行字段叫 `name`，序列化出来就是 `\"name\"`，而契约要的是 `book_name`。修法二选一：给字段加 `@JsonProperty(\"book_name\")`，或把全局策略改成 SNAKE_CASE。注意**不要**只改字段名——字段名一改，持久层与前端都要跟着动，而 `@JsonProperty` 只影响 JSON 契约这一层。"
}
```

**提示**：改的应该是"契约映射"，不是"数据存储"。

## 检查一下

```quiz
{
  "id": "spring-14-quiz",
  "title": "JSON 三问",
  "questions": [
    {
      "question": "Jackson 默认对 null 字段的处理是？",
      "options": ["省略", "输出 \"key\": null", "变成空字符串"],
      "answer": 1,
      "explain": "默认是 ALWAYS。要省略得加 @JsonInclude(NON_NULL) 或全局配置。"
    },
    {
      "question": "为什么推荐用 ISO-8601 字符串而不是时间戳数字表示时间？",
      "options": ["更长更好看", "自带时区信息，跨时区不会错", "JSON 不支持数字"],
      "answer": 1,
      "explain": "时间戳丢了时区。同一个数字在不同时区解释出不同的本地时间，跨时区必错一次。"
    },
    {
      "question": "为什么接口响应体不该直接返回数据库实体？",
      "options": ["性能差", "实体字段随库表变化，等于悄悄改 API", "实体不能序列化"],
      "answer": 1,
      "explain": "加一个库字段就可能泄露内部结构或破坏前端契约。响应应该用专门的 DTO。"
    }
  ]
}
```

## 常见误区

### 误区：字段名对不上就是前端写错了

**你以为**：后端输出 `bookName`，前端该适配。
**其实**：JSON 契约是双边的。真正的问题不是"谁改"，而是**没有一处写明契约**。修法要么用 `@JsonProperty` 显式声明，要么全局统一命名策略——两种都比"口头约定"可靠。
**怎么验证**：搜一下项目里有没有一处地方能列出某个接口的完整响应结构。没有，就说明契约只在两个人的脑子里。

### 误区：`@JsonIgnore` 是万能的防递归手段

**你以为**：双向关联加 `@JsonIgnore` 就好了。
**其实**：它把那一侧永久地从 JSON 里去掉了。当另一个接口恰恰需要那个方向的数据时，你只能再加一个 DTO 或者开 `@JsonView`——绕了一圈回到"用 DTO"。
**怎么验证**：给 `Category` 加一个"带书籍列表"的接口，看现有注解是否还需要改。

### 误区：序列化失败一定是数据有问题

**你以为**：拿到 `HttpMessageNotReadableException`，就是客户端发了坏数据。
**其实**：也可能是**你的 DTO 与契约不一致**：改了字段名没同步、日期格式不认识、枚举多了一个值。先看异常消息的 `through reference chain`，一行就能指出是哪个字段。
**怎么验证**：故意发一个少字段的请求体，看异常消息里点名的是不是你以为的那个字段。

## 小结

JSON 序列化发生在返回值处理阶段，由 `HttpMessageConverter` 完成。四个坑分别是字段名、null、日期、双向关联，各有一个注解/配置对应。响应体用 DTO 不用实体；时间是 ISO-8601 不是时间戳。

下一课：一次请求从 socket 走到你的方法，中间到底有几层——以及各类横切逻辑应该挂在哪一层。
