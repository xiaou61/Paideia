---
title: 18 · Optional：该用在哪、不该用在哪
order: 18
chapter: 泛型与函数式
level: intermediate
prerequisite: 17 · 泛型：类型参数与通配符
category: java-from-zero
minutes: 26
status: published
---

# 18 · Optional：该用在哪、不该用在哪

## 这一课你要会

- 说清 `Optional` 解决的到底是什么问题；
- 用 `map` / `filter` / `orElse` / `orElseThrow` 写出不用判空的链路；
- 知道**为什么不该把它用在字段和参数上**；
- 认出 `Optional.get()` 与 `isPresent()` 的滥用。

## 为什么

`Optional<T>` 是 Java 8 引入的"一个值可能有、也可能没有"的容器。

它经常被误解成"更好的 null"，然后被塞进字段、塞进方法参数、塞进集合——这些都是官方明确不建议的用法。

这一课讲清它真正的用途：**给返回值一个"可能没有结果"的显式类型**，并让调用方无法忘记处理"没有"的情况。

## 讲解

### 它解决什么

没有 `Optional` 时，方法"没有结果"只能用 `null` 表示：

```java
User findById(long id) { ... }   // 找不到返回 null
```

问题：**调用方不知道这个方法可能返回 null**。签名上看不出来，忘判空就是 NPE。

`Optional` 把这个信息写进类型：

```java
Optional<User> findById(long id) { ... }
```

看到返回类型是 `Optional`，调用方就知道"可能没有"，而且**它必须显式处理**才能拿到值——`get()` 会抛异常，正常做法是用 `orElse` 系列。

### 四个核心方法

```java
Optional<String> found = Optional.of("x");
Optional<String> empty = Optional.empty();

// 1) 有就用、没有用默认值
String a = found.orElse("默认");        // "x"
String b = empty.orElse("默认");        // "默认"

// 2) 没有时现算一个默认值（注意：只有需要时才执行）
String c = empty.orElseGet(() -> "现算");   // "现算"

// 3) 没有就抛异常
String d = empty.orElseThrow(() -> new IllegalStateException("找不到"));   // 抛

// 4) 有就执行、没有就跳过
found.ifPresent(value -> System.out.println(value));   // 打印 x
```

`orElse` 与 `orElseGet` 的区别很实际：

```java
// orElse 的参数**总会先算出来**，哪怕 Optional 里有值
String x = found.orElse(expensiveDefault());    // expensiveDefault() 也会被执行

// orElseGet 的 lambda 只在真的没有值时才执行
String y = found.orElseGet(() -> expensiveDefault());   // 不执行
```

所以**默认值需要计算时一律用 `orElseGet`**。

### 链路：`map` 与 `filter`

`Optional` 最舒服的用法是链式，不用一层层判空：

```java
String city = findById(id)
        .map(User::address)          // Optional<Address>
        .map(Address::city)          // Optional<String>
        .orElse("未知");
```

`map` 的规则：有值就把函数作用上去（函数返回 null 会变成空 `Optional`）；没有值就继续空。

`filter` 用于"值存在但要满足条件才保留"：

```java
Optional<User> adult = findById(id).filter(user -> user.age() >= 18);
```

这套链路的价值：**每一段都可能"没有"，但代码里没有一个 `if`**。

### 三种构造方式，别用错

```java
Optional.of(value)        // value 为 null 时立刻抛 NPE
Optional.ofNullable(value) // value 为 null 时给空 Optional
Optional.empty()           // 明确的空
```

**易错点**：`Optional.of(null)` 抛 NPE。想"可能为 null"就必须用 `ofNullable`。

### 不该用的地方（重点）

**一、不要做字段类型。**

```java
class User {
    // private Optional<String> nickname;   // 不要
    private String nickname;                // 可以为 null
}
```

三个理由：`Optional` 本身不是 `Serializable`（影响序列化框架）；每个字段多一个对象；字段的"可空性"用注解或文档表达更轻。

**二、不要做方法参数。**

```java
// void save(String name, Optional<String> nickname)   // 不要
void save(String name, String nickname)                // 可以传 null，也可以重载
```

参数用 `Optional` 会逼调用方每次写 `Optional.ofNullable(...)`，而且"传 `null` 还是传 `Optional.empty()`"变成两个都会出现的错误来源。

**三、不要放进集合。**

```java
// List<Optional<String>> list;   // 不要
List<String> list;                // 用"不放进空元素"来表达
```

`List<Optional<T>>` 让你每一格都要解包，而"这一格没有值"用"这个元素不存在"来表达更直接。

**四、不要用 `Optional` 表示"集合可能为空"。**

```java
// Optional<List<User>> findUsers();   // 不要
List<User> findUsers();                // 返回空列表，不要返回 null
```

集合的"没有"就是空集合。用 `Optional<List>` 会套两层，调用方要写两次解包。

### `get()` 与 `isPresent()` 为什么是坏味道

```java
Optional<User> user = findById(id);
if (user.isPresent()) {            // 这和 if (x != null) 有什么区别？
    System.out.println(user.get());
}
```

这样写等于把 null 检查换了个名字，`Optional` 的全部好处都没用上。更好的写法：

```java
findById(id).ifPresent(u -> System.out.println(u));
```

或者

```java
User user = findById(id).orElseThrow(() -> new NotFound("用户不存在"));
```

**`get()` 的危险**：空的时候抛 `NoSuchElementException`，而它是非受检的，容易漏。**只在已经确定有值的情况下用**，而那通常意味着你该用 `orElseThrow` 明确表达。

### 与 `null` 的关系

`Optional` 不是"消灭 null"，它是**让"可能没有"这件事出现在类型里**。

一条实用分工：

| 场景 | 用法 |
| --- | --- |
| 方法返回"可能有、可能没有的一个值" | `Optional<T>` |
| 方法返回一组值（可能 0 个） | `List<T>`（空列表，不返回 null） |
| 字段可以为空 | 普通引用 + 文档/注解 |
| 参数可选 | 重载，或明确写"为 null 时按默认" |

## 动手

这段代码演示链式取值、`orElse` 与 `orElseGet` 的求值时机差别，以及 `of` 与 `ofNullable` 的行为差异。

<!-- run -->
```java
import java.util.Optional;

public class Main {
    record Address(String city) { }
    record User(String name, Address address) { }

    static Optional<User> findUser(String name) {
        if ("alice".equals(name)) {
            return Optional.of(new User("alice", new Address("杭州")));
        }
        if ("bob".equals(name)) {
            return Optional.of(new User("bob", null));
        }
        return Optional.empty();
    }

    static String expensiveDefault() {
        System.out.println("  （expensiveDefault 被调用）");
        return "未知";
    }

    public static void main(String[] args) {
        // 1) 链式取值：不用一个 if
        String city = findUser("alice")
                .map(User::address)
                .map(Address::city)
                .orElse("未知");
        System.out.println("alice 的城市：" + city);

        // 2) 中途为空：address 是 null，map 会把它变成空 Optional
        String bobCity = findUser("bob")
                .map(User::address)
                .map(Address::city)
                .orElse("未知");
        System.out.println("bob 的城市：" + bobCity);

        // 3) orElse 的参数总会求值；orElseGet 只在需要时求值
        System.out.println("orElse：");
        findUser("alice").map(User::name).orElse(expensiveDefault());
        System.out.println("orElseGet：");
        findUser("alice").map(User::name).orElseGet(Main::expensiveDefault);

        // 4) of(null) 立刻抛；ofNullable(null) 给空
        try {
            Optional.of(null);
        } catch (NullPointerException failure) {
            System.out.println("Optional.of(null) 抛了 NPE");
        }
        System.out.println("ofNullable(null) 是空：" + Optional.ofNullable(null).isEmpty());

        // 5) filter：值存在但要满足条件
        System.out.println("alice 名字长度大于 3：" + findUser("alice")
                .map(User::name)
                .filter(name -> name.length() > 3)
                .isPresent());
    }
}
```

```text
alice 的城市：杭州
bob 的城市：未知
orElse：
  （expensiveDefault 被调用）
orElseGet：
Optional.of(null) 抛了 NPE
ofNullable(null) 是空：true
alice 名字长度大于 3：true
```

注意 `orElse` 那一段：**明明 alice 有值，`expensiveDefault` 还是被调用了**。`orElseGet` 那一段则没有这行输出——这就是两者的差别。

### 你的练习

```code-challenge
{
  "id": "java-13-bug",
  "kind": "bug",
  "title": "找出会用错 Optional 的那一行",
  "language": "java",
  "code": "import java.util.Optional;\n\npublic class Main {\n    static Optional<String> find(String key) {\n        return \"a\".equals(key) ? Optional.empty() : Optional.of(key);\n    }\n\n    public static void main(String[] args) {\n        System.out.println(find(\"a\").get());\n    }\n}",
  "bugLine": 9,
  "explain": "find(\"a\") 返回空 Optional，get() 抛 NoSuchElementException。应当用 orElse/orElseThrow 明确处理空的情况。"
}
```

## 检查一下

```quiz
{
  "id": "java-13-quiz",
  "title": "Optional 三问",
  "questions": [
    {
      "question": "为什么不该把 Optional 用作类的字段？",
      "options": [
        "语法不允许",
        "它不可序列化、每字段多一个对象，而且字段可空用文档表达更轻",
        "字段里放 Optional 会编译报错"
      ],
      "answer": 1,
      "explain": "Optional 不是 Serializable，会影响序列化框架；且它主要价值在返回值上。"
    },
    {
      "question": "orElse 与 orElseGet 的区别是？",
      "options": [
        "没有区别",
        "orElse 的参数总会求值，orElseGet 的 lambda 只在没有值时执行",
        "orElseGet 只能返回 null"
      ],
      "answer": 1,
      "explain": "orElse 接收一个值（先算出来再传），orElseGet 接收 Supplier（惰性求值）。默认值有代价时用 orElseGet。"
    },
    {
      "question": "Optional.of(null) 会怎样？",
      "options": ["返回空 Optional", "抛 NullPointerException", "编译错误"],
      "answer": 1,
      "explain": "of 要求非 null，立刻抛 NPE。可能为 null 的值要用 ofNullable。"
    }
  ]
}
```

## 常见误区

### 误区：`Optional` 是 null 的替代品，到处都该用

它只替代"方法返回值可能没有"这一种情况。字段、参数、集合元素都不该用。用多了会让代码更啰嗦，而不是更安全。

### 误区：`if (opt.isPresent()) opt.get()` 是好写法

这等价于 `if (x != null) use(x)`，只是换了个名字。真正的收益来自 `map` / `orElse` / `ifPresent` 这些组合子。

### 误区：返回 `Optional<List<User>>` 更严谨

不。集合的"没有"就是空集合。返回 `List` 空列表比返回 `Optional<List>` 简单得多——后者逼调用方解两次包。

### 误区：`Optional` 有性能问题，所以不能用

每次调用会多一个小对象（`Optional` 是 `final` 的单字段容器）。对绝大多数代码没有可测的影响。**真正的性能问题是装箱与在热路径里创建大量临时对象**，不是 `Optional` 本身。

## 小结

- `Optional<T>` 把"可能没有值"写进类型，主要用于**方法返回值**。
- `map` / `filter` / `orElse` / `orElseThrow` 让你不写 `if` 也能处理空。
- 默认值有计算代价时用 `orElseGet`（`orElse` 的参数总会求值）。
- `of(null)` 抛 NPE，可能为 null 用 `ofNullable`。
- **不要**用在字段、参数、集合元素上；不要用 `isPresent() + get()` 模拟 null 检查。

下一课讲**日期时间 API**：`LocalDate` / `Instant` / `ZonedDateTime` 各自代表什么，以及跨时区最容易踩的坑。
