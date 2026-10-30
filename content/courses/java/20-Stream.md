---
title: 20 · Stream：把循环压成一条链
order: 20
chapter: 泛型与函数式
level: intermediate
prerequisite: 19 · 日期时间：LocalDate、Instant 与跨时区
category: java-from-zero
minutes: 30
status: published
---

# 20 · Stream：把循环压成一条链

## 这一课你要会

- 把"遍历 → 判断 → 收集"三段循环改写成一条 Stream 链；
- 分清**中间操作**（`map` / `filter` / `sorted`）与**终结操作**（`collect` / `sum` / `forEach`）；
- 知道 `Collectors` 里最常用的几个人；
- 判断什么时候**不该**用 Stream。

## 为什么

处理集合时有大量"筛选 + 变换 + 汇总"的重复动作。用循环写要维护一个临时变量、一个 `if`、一个 `add`，代码量不小，而且意图藏在细节里。

Stream 把这些动作变成一串具名操作，读的时候像读句子：

```java
// 循环版：读完才知道在干什么
List<String> result = new ArrayList<>();
for (User user : users) {
    if (user.age() >= 18) {
        result.add(user.name().toUpperCase());
    }
}
Collections.sort(result);

// Stream 版：一眼看出"取成年人 → 转大写 → 排序"
List<String> result = users.stream()
        .filter(user -> user.age() >= 18)
        .map(user -> user.name().toUpperCase())
        .sorted()
        .toList();
```

两种写法性能接近，但第二种**意图更清楚**。

## 讲解

### 两类操作

**中间操作**（返回新 Stream，可以链下去）：`filter`、`map`、`flatMap`、`sorted`、`distinct`、`limit`、`skip`、`peek`。

**终结操作**（返回结果，链到此结束）：`collect`、`toList`、`count`、`sum`、`max`、`min`、`findFirst`、`anyMatch`、`allMatch`、`forEach`、`reduce`。

**关键点：没有终结操作，整条链一行都不会执行。** Stream 是惰性的。

```java
users.stream().filter(u -> u.age() >= 18);      // 什么都不会发生
users.stream().filter(u -> u.age() >= 18).count();  // 这时才真的遍历
```

```sketch
{
  "title": "数据从源头流过每一站，最后被收成结果",
  "rows": [
    [
      { "id": "src", "title": "List<User>", "sub": "数据源", "tone": "gray" },
      { "id": "filter", "title": "filter 年龄 ≥ 18", "sub": "留下满足条件的", "tone": "blue" },
      { "id": "map", "title": "map 取名字", "sub": "一个换一个", "tone": "blue" },
      { "id": "end", "title": "collect 收成 List", "sub": "终结操作，这时才执行", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "src", "to": "filter" },
    { "from": "filter", "to": "map" },
    { "from": "map", "to": "end" }
  ]
}
```

### `filter`：留下满足条件的

```java
List<User> adults = users.stream()
        .filter(user -> user.age() >= 18)
        .toList();
```

写成 lambda 的条件，等价于 `if` 里的判断。**返回 `boolean`**。

### `map`：一个换一个

```java
List<String> names = users.stream()
        .map(User::getName)       // 方法引用，等价于 user -> user.getName()
        .toList();
```

`map` 是"一对一"。**注意它和 `filter` 的区别**：`filter` 不改变元素数量（只减少），`map` 也不改变数量（只变形）。

### `flatMap`：一个换多个

```java
// 每个用户有多个标签，要拿到全部标签的扁平列表
List<String> allTags = users.stream()
        .flatMap(user -> user.getTags().stream())
        .distinct()
        .toList();
```

`flatMap` 用于"元素本身是集合"的场合。如果这里用 `map`，得到的是 `List<List<String>>`，还要再摊平一次。

### `sorted`：排序

```java
// 自然顺序（元素要 Comparable）
users.stream().sorted().toList();

// 自定义比较器
users.stream()
        .sorted(Comparator.comparing(User::getAge).reversed())
        .toList();

// 多级排序：先按年龄，年龄相同按名字
users.stream()
        .sorted(Comparator.comparing(User::getAge).thenComparing(User::getName))
        .toList();
```

`Comparator.comparing(...)` 是"取字段比较"的简写，比手写 `(a, b) -> a.getAge() - b.getAge()` 安全（后者在 `int` 溢出时会排错）。

### `toList()` 与 `collect(Collectors.toList())`

```java
List<String> a = stream.toList();                          // Java 16+
List<String> b = stream.collect(Collectors.toList());       // 传统写法
```

两者有个**重要区别**：

- `toList()` 返回**不可变**列表（往里 `add` 会抛 `UnsupportedOperationException`）；
- `Collectors.toList()` 返回通常可变的 `ArrayList`。

**新代码用 `toList()`**——不可变是更好的默认值。需要可变时显式写 `new ArrayList<>(stream.toList())`。

### 常用 `Collectors`

```java
// 1) 计数
long count = users.stream().filter(u -> u.getAge() >= 18).count();

// 2) 分组：按年龄分组 → Map<Integer, List<User>>
Map<Integer, List<User>> byAge = users.stream()
        .collect(Collectors.groupingBy(User::getAge));

// 3) 分组后计数
Map<String, Long> byCity = users.stream()
        .collect(Collectors.groupingBy(User::getCity, Collectors.counting()));

// 4) 拼字符串
String joined = users.stream()
        .map(User::getName)
        .collect(Collectors.joining(", ", "[", "]"));     // [a, b, c]

// 5) 转 Map（注意 key 重复会抛异常）
Map<Long, User> byId = users.stream()
        .collect(Collectors.toMap(User::getId, user -> user));

// 6) 求和
int totalAge = users.stream().mapToInt(User::getAge).sum();
```

`toMap` 那个"key 重复抛异常"值得记：两个用户 id 相同会让 `Collectors.toMap` 抛 `IllegalStateException`。要容忍重复，得给第三个参数：

```java
Map<Long, User> byId = users.stream()
        .collect(Collectors.toMap(User::getId, user -> user, (first, second) -> second));
```

### `mapToInt` / `mapToLong` / `mapToDouble`

这三个返回**基本类型流**（`IntStream` 等），好处是能直接 `sum()` / `average()`，且不用装箱：

```java
int total = users.stream().mapToInt(User::getAge).sum();
double average = users.stream().mapToInt(User::getAge).average().orElse(0);
OptionalInt max = users.stream().mapToInt(User::getAge).max();
```

注意 `average()` 返回 `OptionalDouble`（空集合没有平均值），`max()` 返回 `OptionalInt`——上一课讲过为什么用 `Optional` 表达"可能没有"。

### 短路操作

`anyMatch` / `allMatch` / `findFirst` 会**提前结束**，不需要遍历完：

```java
boolean hasAdult = users.stream().anyMatch(u -> u.getAge() >= 18);   // 找到一个就停
Optional<User> first = users.stream().filter(u -> u.getAge() >= 18).findFirst();
```

对大数据集，`anyMatch` 比 `filter(...).count() > 0` 快得多——后者要把全部元素过一遍。

### `reduce`：把一串值压成一个

```java
int sum = numbers.stream().reduce(0, Integer::sum);           // 有初始值
Optional<Integer> max = numbers.stream().reduce(Integer::max); // 没有初始值，可能为空
```

日常用 `mapToInt().sum()` 更直观；`reduce` 适合自定义的折叠逻辑。

### 什么时候不该用 Stream

**一、简单到一行能写完的循环。**

```java
// 为了 Stream 而 Stream
for (String name : names) System.out.println(name);

// 这个更啰嗦
names.stream().forEach(System.out::println);
```

**二、需要在循环里 `break` / `continue` / 修改外部变量。**

Stream 不支持 `break`，`forEach` 里的 `return` 只是跳过当前元素。这类逻辑用普通循环更直接。

**三、有受检异常要处理。**

lambda 里不能直接抛受检异常，要么包 try-catch（难看），要么写个包装方法。这种时候循环更好。

**四、热路径上极高频调用。**

Stream 每步都有函数调用开销，在每秒百万次的路径上，普通循环更快。**先测量再优化**——大部分业务代码用 Stream 完全没问题。

### 一个容易写错的点：`peek` 不是给业务用的

```java
users.stream()
        .peek(user -> System.out.println(user))   // 只该用于调试
        .filter(...)
        .toList();
```

`peek` 是给"看数据流经过"用的。**不要用它做有副作用的业务操作**——没有终结操作时它不会执行，这个"不执行"的行为会让排查很痛苦。

## 动手

这段代码把一段循环改写为 Stream，并演示分组、求和与短路。

<!-- run -->
```java
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Main {
    record User(String name, int age, String city) { }

    public static void main(String[] args) {
        List<User> users = List.of(
                new User("alice", 30, "杭州"),
                new User("bob", 17, "北京"),
                new User("carol", 25, "杭州"),
                new User("dave", 42, "北京"));

        // 1) 筛选 + 变形 + 排序：成年人名字大写后按名字排
        List<String> adults = users.stream()
                .filter(user -> user.age() >= 18)
                .map(user -> user.name().toUpperCase())
                .sorted()
                .toList();
        System.out.println("成年用户：" + adults);

        // 2) 分组：按城市
        Map<String, List<String>> byCity = users.stream()
                .collect(Collectors.groupingBy(User::city,
                        Collectors.mapping(User::name, Collectors.toList())));
        System.out.println("按城市：" + byCity);

        // 3) 分组后计数
        Map<String, Long> cityCount = users.stream()
                .collect(Collectors.groupingBy(User::city, Collectors.counting()));
        System.out.println("城市人数：" + cityCount);

        // 4) 基本类型流：求和与平均
        int totalAge = users.stream().mapToInt(User::age).sum();
        double averageAge = users.stream().mapToInt(User::age).average().orElse(0);
        System.out.println("总年龄：" + totalAge + "，平均：" + averageAge);

        // 5) 排序：年龄倒序，年龄相同按名字
        List<String> byAgeDesc = users.stream()
                .sorted(Comparator.comparing(User::age).reversed()
                        .thenComparing(User::name))
                .map(User::name)
                .toList();
        System.out.println("按年龄倒序：" + byAgeDesc);

        // 6) 短路：找到一个就停
        boolean hasAdult = users.stream().anyMatch(user -> user.age() >= 18);
        System.out.println("有成年人：" + hasAdult);
    }
}
```

```text
成年用户：[ALICE, CAROL, DAVE]
按城市：{杭州=[alice, carol], 北京=[bob, dave]}
城市人数：{杭州=2, 北京=2}
总年龄：114，平均：28.5
按年龄倒序：[dave, alice, carol, bob]
有成年人：true
```

注意第 4 行「平均 28.5」：`mapToInt().average()` 返回的是 `OptionalDouble`，用 `orElse(0)` 处理空集合。这正是上一课 `Optional` 的用法。

### 你的练习

```code-challenge
{
  "id": "java-15-bug",
  "kind": "bug",
  "title": "找出没有终结操作的那条链",
  "language": "java",
  "code": "import java.util.List;\n\npublic class Main {\n    public static void main(String[] args) {\n        List<Integer> numbers = List.of(1, 2, 3, 4, 5);\n        numbers.stream()\n                .filter(n -> n % 2 == 0)\n                .map(n -> n * 10);\n        System.out.println(\"没有任何输出\");\n    }\n}",
  "bugLine": 8,
  "explain": "filter 和 map 都是中间操作，返回新的 Stream，但没有终结操作（如 toList/count/forEach），整条链一行都不会执行。要拿到结果必须接一个终结操作。"
}
```

## 检查一下

```quiz
{
  "id": "java-15-quiz",
  "title": "Stream 三问",
  "questions": [
    {
      "question": "stream().filter(...) 之后不接终结操作会发生什么？",
      "options": ["正常执行筛选", "什么都不执行", "编译错误"],
      "answer": 1,
      "explain": "Stream 是惰性的：中间操作只是记下要做什么，只有终结操作触发时才会真正遍历。"
    },
    {
      "question": "toList() 与 collect(Collectors.toList()) 的区别是？",
      "options": [
        "完全一样",
        "toList() 返回不可变列表，往里面 add 会抛异常",
        "toList() 更慢"
      ],
      "answer": 1,
      "explain": "toList()（Java 16+）返回不可变列表，是更安全的默认值。需要可变时用 new ArrayList<>(stream.toList())。"
    },
    {
      "question": "想判断「是否有成年人」，哪种写法在大数据集合上更快？",
      "options": [
        "filter(u -> u.age() >= 18).count() > 0",
        "anyMatch(u -> u.age() >= 18)",
        "两种一样快"
      ],
      "answer": 1,
      "explain": "anyMatch 是短路操作，找到一个就停止；count() 要把全部元素都过一遍。"
    }
  ]
}
```

## 常见误区

### 误区：Stream 比循环快

通常**不会**。Stream 有函数调用与装箱开销，简单场景普通循环往往更快。用 Stream 的理由是**可读性**，不是性能。

### 误区：一条链越长越"函数式"

三步以内通常更好读；五六步串起来后，调试困难（不好打断点看中间结果）。这时候拆成几个具名方法，或者改回局部变量。

### 误区：`forEach` 里能用 `return` 跳过元素

`return` 只是从当前 lambda 返回，不是从循环跳出。要"跳过某些元素"用 `filter`，要"提前结束"用 `findFirst` / `anyMatch`。

### 误区：Stream 可以重复用

**一个 Stream 只能消费一次**，第二次调终结操作会抛 `IllegalStateException`。需要多次处理要么重新 `stream()`，要么先 `toList()` 落成列表。

## 小结

- 中间操作（`filter` / `map` / `sorted`）可链式，终结操作（`toList` / `count` / `sum`）触发执行。
- **没有终结操作，整条链一行都不跑**。
- `map` 一对一、`flatMap` 一对多；`mapToInt` 返回基本类型流，避免装箱。
- `toList()` 返回不可变列表，是新代码的默认选择。
- `anyMatch` / `findFirst` 会短路；`Collectors.toMap` 遇重复 key 会抛异常。
- 简单循环、需要 `break`、有受检异常、极热路径——这四种情况用普通循环更好。

下一课讲 **Lambda 与函数式接口**：`->` 到底编译成了什么，以及为什么方法引用比 lambda 更清楚。
