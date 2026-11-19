---
title: 18 · Optional：该用在哪、不该用在哪
order: 18
chapter: 泛型与函数式
level: intermediate
prerequisite: 17 · 泛型：类型参数与通配符
category: java-from-zero
minutes: 22
status: published
---

# 18 · Optional：该用在哪、不该用在哪

## 这条链在第 31 行编译不过

一个订单可能没有客户，客户可能没有地址，最后要城市。我先写了这条链（`Customer::getAddress` 是方法引用，`c -> ...` 是 lambda；第 21 课会专门讲，这里先照抄。第 35 课的 `record` 这篇不用，类都写成普通类）：

```java
import java.util.Optional;

class Address {
    private final String city;
    Address(String city) { this.city = city; }
    String getCity() { return city; }
}

class Customer {
    private final String name;
    private final Address address;
    Customer(String name, Address address) {
        this.name = name;
        this.address = address;
    }
    String getName() { return name; }
    Optional<Address> getAddress() { return Optional.ofNullable(address); }
}

class Order {
    private final Customer customer;
    Order(Customer customer) { this.customer = customer; }
    Optional<Customer> getCustomer() { return Optional.ofNullable(customer); }
}

public class Main {
    public static void main(String[] args) {
        Order order = new Order(new Customer("alice", new Address("杭州")));
        String city = order.getCustomer()
                .map(Customer::getAddress)
                .map(Address::getCity)
                .orElse("未知");
        System.out.println(city);
    }
}
```

我在 PowerShell 里先设了 `$env:JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US'`，让报错是英文；`javac --release 21 Main.java` 的输出是：

```text
Main.java:31: error: incompatible types: invalid method reference
                .map(Address::getCity)
                     ^
    method getCity in class Address cannot be applied to given types
      required: no arguments
      found:    Optional<Address>
      reason: actual and formal argument lists differ in length
Note: Some messages have been simplified; recompile with -Xdiags:verbose to get full output
1 error
```

我原来以为 `map(Customer::getAddress)` 会直接把地址拿出来，实际它只把 `getAddress()` 的返回值套进一层新的 `Optional`。`getAddress()` 返回 `Optional<Address>`，所以这一行得到 `Optional<Optional<Address>>`；下一行 `map(Address::getCity)` 要的是 `Address`，拿到的是 `Optional<Address>`，方法引用对不上，编译就停在 `.map(Address::getCity)` 这一行（第 31 行）。

把这一行的 `map` 换成 `flatMap`：

```java
String city = order.getCustomer()
        .flatMap(Customer::getAddress)
        .map(Address::getCity)
        .orElse("未知");
System.out.println(city);
```

输出：

```text
杭州
```

`flatMap` 的约定是「你给我的函数返回 `Optional<U>`，我直接把 `U` 接在链上」，所以它会把外面那层和里面那层合成一层。`map` 的约定是「你给我的函数返回什么，我就用 `Optional` 包什么」——返回 `Optional` 就包成两层。

| 这一行 | 结果类型 |
| --- | --- |
| `order.getCustomer()` | `Optional<Customer>` |
| `.map(Customer::getAddress)` | `Optional<Optional<Address>>` |
| `.flatMap(Customer::getAddress)` | `Optional<Address>` |
| `.map(Address::getCity)` | `Optional<String>` |

自己验：把 `flatMap` 改回 `map`，再编译一次，报错会回到上面那条。我的环境是 Temurin 25.0.4 加 `javac --release 21`；你的 javac 版本不同，行号或 `Note` 可能有差异，`found: Optional<Address>` 是判据。

## `map` 返回 `null` 会变成空，不报错

`map` 还有一条规则：函数返回 `null` 时，它把结果变成 `Optional.empty()`，不会抛 NPE。比如地址对象在、但 `city` 字段是 `null`：

```java
Optional<String> city = Optional.of(new Address(null)).map(Address::getCity);
System.out.println(city.isEmpty());   // true
```

（`isEmpty()` 是 Java 11 加的；课程基线 21，直接用。）

这既是特性也是坑。特性是链不会因为中途某个 getter 返回 `null` 就炸，后面的 `orElse` / `ifPresent` 仍然能工作；坑是你分不清「没有地址」和「地址对象在、城市字段是 `null`」——两种都变成 empty。要区分，就得在 `Address` 的构造器或更早的入口保证 `city` 非空，别把校验全推给 `map`。

自己验：把 `new Address(null)` 改成 `new Address("杭州")`，`city.isEmpty()` 就从 `true` 变 `false`；如果只把 `getCity()` 改成返回 `""`，它不会变空，空字符串仍然是值。

`filter` 则是另一件事：它不改变类型，只决定「这个值留不留」。`order.getCustomer().filter(c -> "alice".equals(c.getName()))` 还是 `Optional<Customer>`；条件不满足就变空。它不负责拆 `Optional<Optional<...>>`，那是 `flatMap` 的事。

## 跑一遍

下面这段把同一个 `Order` 的四种状态、`orElse` / `orElseGet`、`of` / `ofNullable`、三个后加的方法一次跑完。`cityOf` 就是上面的 `flatMap` 链。`Main::expensiveDefault` 也是第 21 课的方法引用，现在读成「调用 `expensiveDefault`」即可。

<!-- run -->
```java
import java.util.NoSuchElementException;
import java.util.Optional;

class Address {
    private final String city;
    Address(String city) { this.city = city; }
    String getCity() { return city; }
}

class Customer {
    private final String name;
    private final Address address;
    Customer(String name, Address address) {
        this.name = name;
        this.address = address;
    }
    String getName() { return name; }
    Optional<Address> getAddress() { return Optional.ofNullable(address); }
}

class Order {
    private final Customer customer;
    Order(Customer customer) { this.customer = customer; }
    Optional<Customer> getCustomer() { return Optional.ofNullable(customer); }
}

public class Main {
    static String cityOf(Order order) {
        return order.getCustomer()
                .flatMap(Customer::getAddress)
                .map(Address::getCity)
                .orElse("未知");
    }

    static String expensiveDefault() {
        System.out.println("  （expensiveDefault 被调用）");
        return "未知";
    }

    public static void main(String[] args) {
        Order order = new Order(new Customer("alice", new Address("杭州")));
        System.out.println("有客户有地址：" + cityOf(order));

        order = new Order(new Customer("bob", null));
        System.out.println("有客户没地址：" + cityOf(order));

        order = new Order(null);
        System.out.println("没客户：" + cityOf(order));

        order = new Order(new Customer("carol", new Address(null)));
        System.out.println("地址在、城市是 null：" + cityOf(order));
        System.out.println("  map 之后 isEmpty()："
                + order.getCustomer()
                        .flatMap(Customer::getAddress)
                        .map(Address::getCity)
                        .isEmpty());

        order = new Order(new Customer("alice", new Address("杭州")));
        System.out.println("orElse 前：");
        String a = order.getCustomer().map(Customer::getName).orElse(expensiveDefault());
        System.out.println("orElse 得到：" + a);
        System.out.println("orElseGet 前：");
        String b = order.getCustomer().map(Customer::getName).orElseGet(Main::expensiveDefault);
        System.out.println("orElseGet 得到：" + b);

        try {
            Optional.of(null);
        } catch (NullPointerException failure) {
            System.out.println("Optional.of(null) -> " + failure.getClass().getSimpleName());
        }
        System.out.println("Optional.ofNullable(null).isEmpty() -> "
                + Optional.ofNullable(null).isEmpty());
        Object maybe = Optional.of(new Customer("alice", null));   // 先当成 Object，才能做 instanceof
        System.out.println("Optional 实现 Serializable？ -> "
                + (maybe instanceof java.io.Serializable));

        order = new Order(null);
        try {
            order.getCustomer().orElseThrow();
        } catch (NoSuchElementException failure) {
            System.out.println("orElseThrow() -> " + failure.getClass().getSimpleName()
                    + "，getMessage() = " + failure.getMessage());
        }

        order = new Order(new Customer("alice", new Address("杭州")));
        order.getCustomer()
                .flatMap(Customer::getAddress)
                .map(Address::getCity)
                .filter(city -> city.startsWith("杭"))
                .ifPresentOrElse(
                        city -> System.out.println("ifPresentOrElse 有值：" + city),
                        () -> System.out.println("ifPresentOrElse 没有值"));

        order = new Order(new Customer("bob", null));
        order.getCustomer()
                .flatMap(Customer::getAddress)
                .map(Address::getCity)
                .ifPresentOrElse(
                        city -> System.out.println("ifPresentOrElse 有值：" + city),
                        () -> System.out.println("ifPresentOrElse 没有值"));

        order = new Order(new Customer("alice", new Address("杭州")));
        System.out.println("stream().count() 有值：" + order.getCustomer().stream().count());
        order = new Order(null);
        System.out.println("stream().count() 空：" + order.getCustomer().stream().count());

        order = new Order(new Customer("alice", new Address("杭州")));
        System.out.println("get() 拿到：" + order.getCustomer().get().getName());
    }
}
```

```text
有客户有地址：杭州
有客户没地址：未知
没客户：未知
地址在、城市是 null：未知
  map 之后 isEmpty()：true
orElse 前：
  （expensiveDefault 被调用）
orElse 得到：alice
orElseGet 前：
orElseGet 得到：alice
Optional.of(null) -> NullPointerException
Optional.ofNullable(null).isEmpty() -> true
Optional 实现 Serializable？ -> false
orElseThrow() -> NoSuchElementException，getMessage() = No value present
ifPresentOrElse 有值：杭州
ifPresentOrElse 没有值
stream().count() 有值：1
stream().count() 空：0
get() 拿到：alice
```

从输出里能看到几件事：

- 前四行是同一个 `order` 变量的四种状态：有客户有地址、有客户没地址、没客户、地址对象在但城市为 `null`。四种都走 `cityOf`，后三种都落到 `orElse("未知")`。
- `map 之后 isEmpty()：true`：`Address.getCity()` 返回 `null`，`map` 把它变成了空 `Optional`。
- `orElse 前：` 后面有一行 `（expensiveDefault 被调用）`，尽管这时 `order` 有客户；`orElseGet 前：` 后面没有这行。`orElse` 的参数是普通方法参数，调用前先求值；`orElseGet` 收的是 `Supplier`，只在空的时候才调用。
- `Optional.of(null)` 抛 `NullPointerException`；`Optional.ofNullable(null).isEmpty()` 是 `true`。
- `Optional 实现 Serializable？ -> false`：JDK 的 `Optional` 没有实现 `Serializable`。把 `Optional` 当字段，序列化框架要么额外支持，要么写出来的不是你想要的字段。
- `orElseThrow()` 抛 `NoSuchElementException`，`getMessage()` 是 `No value present`。`get()` 抛的是同一个异常、同一条消息；区别在名字和用法，不在异常类型。
- `ifPresentOrElse` 走了两个分支：有地址时打印城市，没地址时打印「没有值」。
- `stream().count()` 在有值时是 `1`，空时是 `0`。它把「0 或 1 个值」变成一个 `Stream`，后面可以接第 20 课的流操作。
- 最后一行 `get() 拿到：alice` 说明 `get()` 仍然能用；它不是废弃 API，只是不建议用。

自己改一行：把 `startsWith("杭")` 改成 `startsWith("北")`，`ifPresentOrElse` 会走第二个分支；把 `new Address(null)` 改成 `new Address("杭州")`，`map 之后 isEmpty()` 会变 `false`。

## 三个后加的方法

`orElseThrow()` 的无参版是 Java 10 加的。它和 `get()` 抛同一个 `NoSuchElementException("No value present")`，但方法名把「没有就抛」写出来了：

```java
order.getCustomer().orElseThrow();
```

要自定义异常，还是用带 `Supplier` 的版本：

```java
order.getCustomer().orElseThrow(() -> new IllegalStateException("没有客户"));
```

`ifPresentOrElse(Consumer, Runnable)` 是 Java 9 加的。它把「有值做什么」和「没有值做什么」放在一次调用里，返回值是 `void`：

```java
order.getCustomer().ifPresentOrElse(
        customer -> System.out.println(customer.getName()),
        () -> System.out.println("没有客户"));
```

如果你需要「有值时得到一个结果」，不要用它，还是用 `map(...).orElse(...)`。

`stream()` 也是 Java 9 加的。它把 `Optional<T>` 变成 0 或 1 个元素的 `Stream<T>`：

```java
long count = order.getCustomer().stream().count();   // 0 或 1
```

这三种方法在上面 `Main` 里都有对应版本；完整输出见上一节。把 `order` 换成 `new Order(null)`，`orElseThrow()` 会抛，`ifPresentOrElse` 会走第二个分支，`stream().count()` 会变 0。

最后把 `get()` 说清楚：它在 JDK 25 上没有 `@Deprecated`，不要把它说成废弃 API。不建议用它的原因是语义弱：名字没告诉你空的时候会抛；和 `isPresent()` 搭配时，代码又变回 `if (x != null)` 的形状。空的时候它抛 `NoSuchElementException("No value present")`；无参 `orElseThrow()` 抛的是同一个异常、同一条消息，但名字把意图写出来了。

自己验：`javac --release 21 -Xlint:deprecation Main.java` 编译上面调用 `get()` 的 `Main`，0 条警告；`javap -v java.util.Optional` 里 `get()` 的 flags 只有 `ACC_PUBLIC`，没有 `Deprecated` 标记。

## Optional 是返回值类型，不是万能容器

`Optional` 是 Java 8 引入的。它想解决的是「方法返回一个值，但这个值可能没有」：没有 `Optional` 时只能返回 `null`，签名上看不出来；有了 `Optional`，「可能没有」就写进了返回类型。它不消灭 `null`，也不强制你处理——`get()` 仍然能绕过去，`Optional` 变量本身也可以是 `null`。

但它不是「更安全的 null」，也不是所有可能为空的地方都该换成 `Optional`。判据只有一句：**这个位置是不是一个「可能没有的单个返回值」？** 是，返回 `Optional<T>`；不是，就别用。

| 位置 | 不要写 | 改成 | 原因 |
| --- | --- | --- | --- |
| 字段 | `Optional<String> nickname` | `String nickname`，在注释里说明可空 | 非空时多一个 `Optional` 对象；JDK 的 `Optional` 不实现 `Serializable`；字段可空是对象状态，不是返回契约 |
| 参数 | `save(String name, Optional<String> nickname)` | `save(String name)` / `save(String name, String nickname)` 重载 | 传可能为 `null` 的值时要先包 `Optional.ofNullable`；直接传 `null` 又多一个失败点 |
| 集合元素 | `List<Optional<String>>` | `List<String>` | 「这一格没有」用「元素不存在」表达；每格都解包很啰嗦 |
| 集合返回值 | `Optional<List<Customer>>` | `List<Customer>`，没有就返回空列表 | 集合的「没有」就是空集合，不要再套一层 |

字段这一条可以自己验：`Object o = Optional.of(new Customer("alice", null)); System.out.println(o instanceof java.io.Serializable);` 输出 `false`。直接写 `Optional.of(new Customer("alice", null)) instanceof java.io.Serializable` 反而编译不过，报 `incompatible types: Optional<Customer> cannot be converted to Serializable`——编译器知道 `Optional` 是 final 且没实现 `Serializable`，这个判断永远不成立。

`Optional` 也不保证字段、参数、集合里的 `null` 消失；它只负责返回值这个位置上的「可能没有」。别把它当成类型系统里的强制检查。

## `isPresent()` 加 `get()`：三种写法，三种改法

下面三段都假设 `order` 是前面那个 `Order` 变量；每段先给坏写法，再给改法。

### 1. 有就打印

```java
if (order.getCustomer().isPresent()) {
    System.out.println(order.getCustomer().get().getName());
}
```

改成：

```java
order.getCustomer()
        .map(Customer::getName)
        .ifPresent(name -> System.out.println(name));
```

`map` 负责把 `Customer` 变成 `String`，`ifPresent` 负责「有才执行」；没有值就什么都不做，不需要 `get()`。

### 2. 没有就抛

```java
if (!order.getCustomer().isPresent()) {
    throw new IllegalStateException("没有客户");
}
Customer customer = order.getCustomer().get();
```

改成：

```java
Customer customer = order.getCustomer()
        .orElseThrow(() -> new IllegalStateException("没有客户"));
```

异常和「没有值」这个分支绑在同一个表达式里，不会出现「检查过了，下一行又 `get()`」的窗口。

### 3. 有就取，没有给默认

```java
Optional<Address> address = order.getCustomer().flatMap(Customer::getAddress);
String city;
if (address.isPresent()) {
    city = address.get().getCity();
} else {
    city = "未知";
}
```

改成：

```java
String city = order.getCustomer()
        .flatMap(Customer::getAddress)
        .map(Address::getCity)
        .orElse("未知");
```

这就是本文开头那条链。三种写法的共同点：`isPresent()` 只告诉你「有没有」，`get()` 只负责「拿出来」；真正的业务分支还留在 `if` 里。`map` / `orElse` / `ifPresent` / `orElseThrow` 把这些分支收进链里，读的时候按「有值会怎样、没有会怎样」看，不用在脑子里模拟两层 `if`。

自己验：把这三段「改成」的代码替换进上面的 `Main`，输出不变；把 `order` 换成 `new Order(null)`，第 1 段不打印，第 2 段抛 `IllegalStateException`，第 3 段打印「未知」。

## 动手

```code-challenge
{
  "id": "java-18-bug",
  "kind": "bug",
  "title": "找出让 Optional 套两层的那一行",
  "language": "java",
  "code": "import java.util.Optional;\n\npublic class Main {\n    static class Address {\n        String getCity() { return \"杭州\"; }\n    }\n\n    static class Customer {\n        Optional<Address> getAddress() { return Optional.of(new Address()); }\n    }\n\n    public static void main(String[] args) {\n        String city = Optional.of(new Customer())\n                .map(Customer::getAddress)\n                .map(Address::getCity)\n                .orElse(\"未知\");\n        System.out.println(city);\n    }\n}",
  "bugLine": 14,
  "explain": "第 14 行的 map 把 Optional<Address> 又套了一层，得到 Optional<Optional<Address>>；第 15 行的 map(Address::getCity) 收到的是 Optional<Address>，编译报 invalid method reference。把第 14 行改成 flatMap 即可。"
}
```

## 检查一下

```quiz
{
  "id": "java-18-quiz",
  "title": "Optional 三问",
  "questions": [
    {
      "question": "orElse 与 orElseGet 的区别是？",
      "options": [
        "orElse 的参数在 Optional 有值时也会先求值；orElseGet 的 Supplier 只在空时调用",
        "两者完全一样，只是名字不同",
        "orElseGet 在 Optional 有值时也会调用 Supplier"
      ],
      "answer": 0,
      "explain": "把 expensiveDefault() 放进 orElse(...)，即使订单有客户，输出里也会先出现「expensiveDefault 被调用」；换成 orElseGet(Main::expensiveDefault) 就不会。"
    },
    {
      "question": "Optional<Customer> 上调用 map(Customer::getAddress)，而 getAddress() 返回 Optional<Address>，结果类型是什么？",
      "options": [
        "Optional<Address>",
        "Address",
        "Optional<Optional<Address>>"
      ],
      "answer": 2,
      "explain": "map 只把返回值套一层；返回值本身是 Optional 就套成两层。要继续摊平，用 flatMap。"
    },
    {
      "question": "下面哪个位置适合用 Optional<T>？",
      "options": [
        "类的字段",
        "可能没有单个值的方法返回值",
        "List 的元素"
      ],
      "answer": 1,
      "explain": "Optional 的主要用途是返回值；字段、参数、集合元素用普通引用、重载或空集合更直接。"
    }
  ]
}
```

## 还差的

- `Optional` 和 `Stream` 的完整衔接只演示了 `stream().count()`；`flatMap` / `filter` / `reduce` 怎么接，第 20 课讲。
- `OptionalInt` / `OptionalLong` / `OptionalDouble` 三个原始类型特化版本没讲；它们避免装箱，但 API 和 `Optional<T>` 不完全一样。
- `Optional` 在 Jackson、MyBatis 等框架里的序列化/映射行为没展开；不同框架支持不一样，这一课只讲 JDK 类型本身。
- 性能只说了「多一个对象」，没有做分配和 GC 测量；要在热路径上优化，先用 JMH 测过再改。
