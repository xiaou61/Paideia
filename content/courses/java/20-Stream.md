---
title: 20 · Stream：把循环压成一条链
order: 20
chapter: 泛型与函数式
level: intermediate
prerequisite: 19 · 日期时间：LocalDate、Instant 与跨时区
category: java-from-zero
minutes: 20
status: published
---

# 20 · Stream：把循环压成一条链

```java
import java.util.stream.Stream;

public class Main {
    public static void main(String[] args) {
        Stream<String> s = Stream.of("a", "b");
        s.toList();
        s.toList();
    }
}
```

```text
Exception in thread "main" java.lang.IllegalStateException: stream has already been operated upon or closed
```

我原来以为 `toList()` 只是把元素倒出来，倒两次最多得到两个一样的列表；实际第一次终结操作就把流标记成已消费，第二次直接抛错。本机 Temurin 25.0.4，`javac --release 21`，下面所有输出都是这台机器上跑出来的；报错块只贴第一行，栈从略，`run` 块的输出是完整 stdout。

代码里的 `x -> x.age() >= 18` 是 lambda，`User::age` 是方法引用（`x -> x.age()` 的简写）。这两样第 21 课细讲，现在先照抄。`record User(...)` 这里只当带 `name()`、`age()` 访问器的数据类用，第 35 课细讲。

## 一条流只能消费一次

`Stream` 不是集合，它更像一次性的管道。`s.toList()` 之后，`s` 已经结束；再调 `s.toList()` 就是上面那个异常。要对同一批数据做两次，重新开流：

```java
List<String> source = List.of("a", "b");
List<String> first = source.stream().toList();
List<String> second = source.stream().toList();
```

或者先把结果落成 `List`，再对列表操作：

```java
List<String> allNames = users.stream().map(User::name).toList();
long count = allNames.stream().filter(name -> name.startsWith("a")).count();
```

对同一个 `List` 每次调 `stream()` 都是一条新流。这里的 `users` 在下一节定义，完整版本在「动手」。

## 先把这组数据放好

```java
record User(long id, String name, int age, String city, List<String> tags) { }

List<User> users = List.of(
        new User(1, "alice", 30, "杭州", List.of("java", "db")),
        new User(2, "bob", 17, "北京", List.of("java")),
        new User(3, "carol", 25, "杭州", List.of("ops")),
        new User(4, "dave", 42, "北京", List.of("db", "ops")));
```

`record` 生成的是 `id()`、`name()`、`age()`、`city()`、`tags()` 这些访问器，不是 `getId()` 那一套。下面统一用 `User::age`、`user.name()` 这种写法；record 的构造器、`equals`、`toString` 第 35 课细讲。这段不是完整类，完整可运行版本在「动手」。

## 终结操作里没有 `sum`

先把最容易记错的一条拿出来：

```java
import java.util.stream.Stream;

public class Main {
    public static void main(String[] args) {
        Stream.of(1, 2, 3).sum();
    }
}
```

```text
Main.java:5: error: cannot find symbol
        Stream.of(1, 2, 3).sum();
                          ^
  symbol:   method sum()
  location: interface Stream<Integer>
1 error
```

`Stream<T>` 的终结操作包括 `collect`、`toList`、`count`、`forEach`、`reduce`、`findFirst`、`findAny`、`anyMatch`、`allMatch`、`noneMatch`，以及要传 `Comparator` 的 `max`/`min`。`sum` 和 `average` 只在 `IntStream`/`LongStream`/`DoubleStream` 上。要求年龄和，先把 `User` 映射成 `int`：

```java
int totalAge = users.stream().mapToInt(User::age).sum();
```

不想映射，也可以用 `reduce`：

```java
int totalAgeByReduce = users.stream().map(User::age).reduce(0, Integer::sum);
```

`Stream<T>.max()` 不带参数也编译不过，报 `required: Comparator<? super T>`；正确写法是 `users.stream().max(Comparator.comparing(User::age))`。

## 惰性：不遍历源，不等于一句都没执行

```java
users.stream()
        .filter(user -> {
            System.out.println("检查 " + user.name());
            return user.age() >= 18;
        });
System.out.println("链结束");
```

```text
链结束
```

`users.stream()` 这一行执行了，返回了一个 Stream 对象；但没有终结操作，所以没有遍历 `users`，`filter` 里的 `println` 一次也没跑。源如果在创建流时就有副作用（比如自己实现的 Spliterator），那部分照常发生；惰性省掉的是遍历源和执行中间操作的 lambda。

加上终结操作才会真的走：

```java
long adults = users.stream()
        .filter(user -> user.age() >= 18)
        .count();
```

这时 `filter` 里的 `println` 会按元素执行，`count` 返回 `long`。

## `filter` 与 `map`

`filter` 只做减法：留下或丢掉，不新增元素；`map` 是一对一变形，数量不变。

```java
List<String> adultNames = users.stream()
        .filter(user -> user.age() >= 18)
        .map(user -> user.name().toUpperCase())
        .toList();
```

`filter` 的 lambda 必须返回 `boolean`。`map` 的 lambda 返回什么，流里就变成什么；上面这一步把 `User` 变成了 `String`。

方法引用是同样的意思：

```java
List<String> names = users.stream()
        .map(User::name)
        .toList();
```

`User::name` 等价于 `user -> user.name()`。record 没有 `getName()`，写 `User::getName` 会直接编译失败。

`flatMap` 把每个元素换成一个流再摊平，可以是零个、一个或多个；适合元素本身带集合的情况：

```java
List<String> allTags = users.stream()
        .flatMap(user -> user.tags().stream())
        .distinct()
        .sorted()
        .toList();
```

这段的结果是 `[db, java, ops]`。如果这里用 `map`，得到的是 `List<List<String>>`，还要再摊平一次。

## `sorted` 要给它比较规则

`record` 不会自动实现 `Comparable`。直接自然排序：

```java
users.stream().sorted().toList();
```

运行期会抛：

```text
Exception in thread "main" java.lang.ClassCastException: class User cannot be cast to class java.lang.Comparable (User is in unnamed module of loader 'app'; java.lang.Comparable is in module java.base of loader 'bootstrap')
```

按年龄排要显式给比较器：

```java
List<User> byAge = users.stream()
        .sorted(Comparator.comparing(User::age))
        .toList();
```

多级排序可以接着写：

```java
List<String> byAgeDesc = users.stream()
        .sorted(Comparator.comparing(User::age).reversed()
                .thenComparing(User::name))
        .map(User::name)
        .toList();
```

结果是 `[dave, alice, carol, bob]`。`Comparator.comparing(...)` 比手写 `(a, b) -> a.age() - b.age()` 安全，后者在 `int` 溢出时会排错。

## `toList()` 与 `collect(Collectors.toList())`

```java
List<String> a = users.stream().map(User::name).collect(Collectors.toList());
List<String> b = users.stream().map(User::name).toList();
System.out.println(a.getClass().getName());
System.out.println(b.getClass().getName());
b.add("eve");
```

```text
java.util.ArrayList
java.util.ImmutableCollections$ListN
Exception in thread "main" java.lang.UnsupportedOperationException
```

`Collectors.toList()` 在 JDK 21 的当前实现是 `ArrayList`，但规范不保证返回类型、可变性、序列化或线程安全；不要把它当成契约。`Stream.toList()` 返回不可修改列表。要可变副本，显式写：

```java
List<String> mutable = new ArrayList<>(users.stream().map(User::name).toList());
mutable.add("eve");
```

## `Collectors` 常用几个

分组用 `TreeMap::new` 固定顺序：

```java
Map<String, List<String>> byCity = users.stream()
        .collect(Collectors.groupingBy(User::city,
                TreeMap::new,
                Collectors.mapping(User::name, Collectors.toList())));
System.out.println(byCity);

Map<String, Long> cityCount = users.stream()
        .collect(Collectors.groupingBy(User::city,
                TreeMap::new,
                Collectors.counting()));
System.out.println(cityCount);
```

```text
{北京=[bob, dave], 杭州=[alice, carol]}
{北京=2, 杭州=2}
```

不传 `TreeMap::new` 时，JDK 21 的当前实现用 `HashMap`；规范不保证 Map 类型，也不保证迭代顺序，所以不要写死打印结果。`TreeMap` 按 key 的自然顺序排，输出才稳定。

拼字符串和转 Map 也常用：

```java
String joined = users.stream()
        .map(User::name)
        .collect(Collectors.joining(", ", "[", "]"));
System.out.println(joined);

Map<Long, String> nameById = users.stream()
        .collect(Collectors.toMap(User::id, User::name));
```

`joined` 是 `[alice, bob, carol, dave]`。上面四个 id 唯一，所以 `nameById` 能正常建出来；把 `carol` 的 id 也改成 1，再跑同一句才会抛（只留第一行）：

```text
Exception in thread "main" java.lang.IllegalStateException: Duplicate key 1 (attempted merging values alice and carol)
```

要保留其中一个，给第三个参数：

```java
Map<Long, User> userById = users.stream()
        .collect(Collectors.toMap(User::id, user -> user, (first, second) -> second));
```

## `mapToInt` 才有 `sum` 和 `average`

```java
int sumOfAges = users.stream().mapToInt(User::age).sum();
double averageAge = users.stream().mapToInt(User::age).average().orElse(0);
System.out.println("总年龄：" + sumOfAges + "，平均：" + averageAge);
```

```text
总年龄：114，平均：28.5
```

`average()` 返回 `OptionalDouble`，空流时没有平均值，`orElse(0)` 给默认值；Optional 在第 18 课讲过。`IntStream.max()` 返回 `OptionalInt`，不是 `int`。`sum`、`average` 以及不带参数的 `max`/`min` 在基本类型流上；`Stream<T>` 的 `max`/`min` 仍要传 `Comparator`。要拿数值结果，先用 `mapToInt`/`mapToLong`/`mapToDouble` 转过去。

## 短路：`findFirst` 与 `findAny`

```java
boolean hasAdult = users.stream().anyMatch(user -> user.age() >= 18);
String firstAdult = users.stream()
        .filter(user -> user.age() >= 18)
        .findFirst()
        .map(User::name)
        .orElse("无");
```

`anyMatch` 找到一个满足的就停；`filter(...).count() > 0` 不是短路，要数完才知道总数大不大于 0。`findFirst` 在有序流上尊重 encounter order，返回遇到顺序里的第一个；`findAny` 允许返回任意一个元素，并行流里少了保序协调，所以更快（具体差多少看数据量和线程数）。

`findAny` 在顺序流里也可能返回第一个，但规范没有这条保证。要稳定的第一个就用 `findFirst`，只关心有没有、不在乎哪个才用 `findAny`。

## `reduce`：把一串值压成一个

```java
int sum = users.stream().map(User::age).reduce(0, Integer::sum);
Optional<Integer> maxAge = users.stream().map(User::age).reduce(Integer::max);
```

有初始值时返回 `int`；没有初始值时返回 `Optional<Integer>`，因为空流没有结果。日常求和用 `mapToInt().sum()` 更直白，`reduce` 留给自定义的折叠逻辑。

## 什么时候别用 Stream

这四种情况，普通循环更直接：

1. 一行循环能写完的。`for (User user : users) System.out.println(user.name());` 比 `users.stream().forEach(user -> System.out.println(user.name()));` 更短，也更直白。
2. 要在循环里 `break`、`continue`，或者修改外部变量。Stream 没有 `break`；`forEach` 里的 `return` 只是结束当前 lambda，不是跳出循环。
3. lambda 里要抛受检异常。`Function`、`Predicate` 的抽象方法没有声明 `throws`，受检异常塞不进去；包 try-catch 或写包装方法都比 Stream 链更啰嗦。
4. 调用频率高的热路径。本例数据量太小，差异可忽略；选型看可读性，热路径要基准测试。不要凭感觉给 Stream 判死刑，也不要凭感觉说它更快。

## `peek` 不是给业务用的

```java
users.stream()
        .peek(user -> System.out.println(user.name()))
        .filter(user -> user.age() >= 18)
        .toList();
```

`peek` 是中间操作，只适合观察数据流经过；没有终结操作时它不会执行。不要把业务副作用放在 `peek` 里，调试完就删。

## 动手

下面这段把筛选、扁平化、分组、求和、排序、短路和 `reduce` 放在一个 `main` 里。`record` 先照抄，第 35 课细讲。

<!-- run -->
```java
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    record User(long id, String name, int age, String city, List<String> tags) { }

    public static void main(String[] args) {
        List<User> users = List.of(
                new User(1, "alice", 30, "杭州", List.of("java", "db")),
                new User(2, "bob", 17, "北京", List.of("java")),
                new User(3, "carol", 25, "杭州", List.of("ops")),
                new User(4, "dave", 42, "北京", List.of("db", "ops")));

        List<String> adults = users.stream()
                .filter(user -> user.age() >= 18)
                .map(user -> user.name().toUpperCase())
                .sorted()
                .toList();
        System.out.println("成年用户：" + adults);

        List<String> allTags = users.stream()
                .flatMap(user -> user.tags().stream())
                .distinct()
                .sorted()
                .toList();
        System.out.println("全部标签：" + allTags);

        Map<String, List<String>> byCity = users.stream()
                .collect(Collectors.groupingBy(User::city,
                        TreeMap::new,
                        Collectors.mapping(User::name, Collectors.toList())));
        System.out.println("按城市：" + byCity);

        int totalAge = users.stream().mapToInt(User::age).sum();
        double averageAge = users.stream().mapToInt(User::age).average().orElse(0);
        System.out.println("总年龄：" + totalAge + "，平均：" + averageAge);

        List<String> byAgeDesc = users.stream()
                .sorted(Comparator.comparing(User::age).reversed()
                        .thenComparing(User::name))
                .map(User::name)
                .toList();
        System.out.println("按年龄倒序：" + byAgeDesc);

        String firstAdult = users.stream()
                .filter(user -> user.age() >= 18)
                .findFirst()
                .map(User::name)
                .orElse("无");
        System.out.println("第一个成年人：" + firstAdult);

        int sumByReduce = users.stream().map(User::age).reduce(0, Integer::sum);
        System.out.println("reduce 求和：" + sumByReduce);

        List<String> mutable = new ArrayList<>(users.stream().map(User::name).toList());
        mutable.add("eve");
        System.out.println("可修改副本：" + mutable);

        Stream<String> once = Stream.of("a", "b");
        once.toList();
        try {
            once.toList();
        } catch (IllegalStateException e) {
            System.out.println("第二次消费：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
```

```text
成年用户：[ALICE, CAROL, DAVE]
全部标签：[db, java, ops]
按城市：{北京=[bob, dave], 杭州=[alice, carol]}
总年龄：114，平均：28.5
按年龄倒序：[dave, alice, carol, bob]
第一个成年人：alice
reduce 求和：114
可修改副本：[alice, bob, carol, dave, eve]
第二次消费：IllegalStateException: stream has already been operated upon or closed
```

## 练习：这条链的最后少了什么

```code-challenge
{
  "id": "java-15-bug",
  "kind": "bug",
  "title": "这条链的最后少了什么",
  "language": "java",
  "code": "import java.util.List;\n\npublic class Main {\n    public static void main(String[] args) {\n        List<Integer> numbers = List.of(1, 2, 3, 4, 5);\n        numbers.stream()\n                .filter(n -> n % 2 == 0)\n                .map(n -> n * 10);\n        System.out.println(\"done\");\n    }\n}",
  "bugLine": 8,
  "explain": "第 8 行是链尾：filter 和 map 都返回 Stream，但没有 toList/count/forEach 之类的终结操作消费它。运行时不会遍历 numbers，也不会执行这两个 lambda；程序只会打印 done。补一个终结操作，例如 .toList() 或 .count()。"
}
```

## 检查一下

```quiz
{
  "id": "java-15-quiz",
  "title": "Stream 三问",
  "questions": [
    {
      "question": "只写 users.stream().filter(u -> u.age() >= 18); 而不接终结操作，会发生什么？",
      "options": [
        "users 被遍历一遍，filter 的 lambda 也执行了",
        "users 不被遍历，filter 的 lambda 也不执行",
        "编译错误"
      ],
      "answer": 1,
      "explain": "中间操作只搭管道。没有终结操作时不会遍历源，也不会执行 filter 里的 lambda；但 users.stream() 这一句本身已经执行。"
    },
    {
      "question": "在 Stream<User> 上求年龄总和，哪个写法能通过编译？",
      "options": [
        "users.stream().sum()",
        "users.stream().mapToInt(User::age).sum()",
        "users.stream().max()"
      ],
      "answer": 1,
      "explain": "Stream<T> 没有 sum()；sum 和 average 在 IntStream/LongStream/DoubleStream 上。Stream<T> 的 max/min 还要传 Comparator。"
    },
    {
      "question": "关于 toList() 与 collect(Collectors.toList())，哪句准确？",
      "options": [
        "两者都保证返回可修改的 ArrayList",
        "Collectors.toList() 在 JDK 21 当前实现是 ArrayList，但规范不保证；Stream.toList() 返回不可修改列表",
        "Stream.toList() 返回 LinkedList"
      ],
      "answer": 1,
      "explain": "规范对 Collectors.toList() 返回的 List 只承诺是 List，类型和可变性都不保证；JDK 21 当前实现是 ArrayList。要可变就显式 new ArrayList<>(...)。"
    }
  ]
}
```

## 还差的

并行流没讲：`parallelStream()` 用哪个线程池、`findAny` 与 `findFirst` 在并行下到底差多少、什么情况下并行反而更慢，这一课只留了 `findAny` 的一句。`Collectors` 也只到 `groupingBy`、`mapping`、`counting`、`joining`、`toMap`；`partitioningBy`、`teeing` 和自定义 `Collector` 都没展开。受检异常怎么包进 lambda，这里只说了「不顺手」，没给可复用的包装方法。性能没有 JMH 数据，只有选型原则。