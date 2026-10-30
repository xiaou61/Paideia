---
title: 21 · Lambda 与函数式接口
order: 21
chapter: 泛型与函数式
level: intermediate
prerequisite: 20 · Stream：把循环压成一条链
category: java-from-zero
minutes: 26
status: published
---

# 21 · Lambda 与函数式接口

## 这一课你要会

- 读懂 `(a, b) -> a + b` 这种写法，知道它的类型从哪来；
- 认出四个内置函数式接口（`Function` / `Consumer` / `Supplier` / `Predicate`）；
- 用方法引用 `String::length` 替代更啰嗦的 lambda；
- 说清 lambda 里的"变量必须 effectively final"是什么意思。

## 为什么

Lambda 是 Stream 的燃料：上一课所有 `filter(u -> ...)`、`map(User::getName)` 都是 lambda 或方法引用。

它本身没有新语法能力——所有 lambda 都能写成匿名类。价值在于**把"传一段行为"这件事写短**，让代码聚焦在"做什么"而不是"怎么包一层类"。

## 讲解

### Lambda 的形状

```java
(a, b) -> a + b
```

三部分：参数列表、箭头、函数体。

```java
// 单参数可省略括号
name -> name.toUpperCase()

// 无参数
() -> System.out.println("hi")

// 多行函数体要花括号 + return
(a, b) -> {
    int sum = a + b;
    return sum * 2;
}
```

### 类型从哪来：函数式接口

Lambda **本身没有类型**，它的类型由"赋值目标"决定。那个目标必须是一个**函数式接口**——只有一个抽象方法的接口。

```sketch
{
  "title": "lambda 自己没有类型，由赋值目标定",
  "rows": [
    [
      { "id": "fn", "title": "(a, b) -> a + b", "sub": "单看它说不上什么类型", "tone": "gray" },
      { "id": "iface", "title": "函数式接口", "sub": "只有一个抽象方法", "tone": "blue" },
      { "id": "type", "title": "推断成 Calculator", "sub": "参数和返回值对上了", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "fn", "to": "iface" },
    { "from": "iface", "to": "type" }
  ]
}
```

```java
@FunctionalInterface
interface Calculator {
    int apply(int a, int b);
}

Calculator add = (a, b) -> a + b;    // 类型推断为 Calculator
System.out.println(add.apply(2, 3)); // 5
```

`@FunctionalInterface` 不是必需的，但**强烈建议加**：它让编译器检查"这个接口确实只有一个抽象方法"，将来有人加第二个方法会立刻报错。

### 四个内置接口，覆盖九成场景

JDK 的 `java.util.function` 包里有几十个，常用的就四个：

| 接口 | 签名 | 用途 | 典型调用处 |
| --- | --- | --- | --- |
| `Function<T, R>` | `R apply(T)` | 输入一个，输出一个 | `stream.map(...)` |
| `Consumer<T>` | `void accept(T)` | 输入一个，不返回 | `stream.forEach(...)`  |
| `Supplier<T>` | `T get()` | 不输入，输出一个 | `Optional.orElseGet(...)` |
| `Predicate<T>` | `boolean test(T)` | 输入一个，判断 | `stream.filter(...)` |

```java
Function<String, Integer> length = String::length;
Consumer<String> printer = System.out::println;
Supplier<String> factory = () -> "new";
Predicate<String> notEmpty = text -> !text.isEmpty();
```

还有两个常用的变体：

- `BiFunction<T, U, R>`：两个输入一个输出（`Map.compute` 用）；
- `UnaryOperator<T>`：输入输出同类型（`list.replaceAll` 用）。

### 方法引用：三种写法

方法引用是 lambda 的简写，**只在"lambda 只是转手调用一个已有方法"时**才能用。

```java
// 1) 静态方法：String::valueOf  ↔  x -> String.valueOf(x)
Function<Integer, String> a = String::valueOf;

// 2) 实例方法（用调用者当参数）：String::length  ↔  s -> s.length()
Function<String, Integer> b = String::length;

// 3) 某个对象的实例方法：System.out::println  ↔  x -> System.out.println(x)
Consumer<String> c = System.out::println;

// 4) 构造器：ArrayList::new  ↔  () -> new ArrayList<>()
Supplier<List<String>> d = ArrayList::new;
```

**判据**：如果 lambda 里只有"用参数调某个方法"，就用方法引用——它更短且不会写错参数顺序。如果 lambda 里还有别的计算，就保留 lambda。

### 变量捕获：effectively final

Lambda 可以读外部的局部变量，但那个变量必须是 **effectively final**（事实上没被重新赋值过）：

```java
String prefix = "user-";             // 没再赋值，effectively final
Function<String, String> f = name -> prefix + name;   // 可以

int count = 0;
count++;                             // 重新赋值过
// Consumer<String> g = x -> count++;   // 编译错误：count 不是 effectively final
```

**为什么不能改**：lambda 里的 `count++` 直觉上应该累加，但 lambda 可能被延迟执行、可能被多线程执行，"改外部变量"的语义会变得难以定义。Java 直接禁止。

**要在 lambda 里累加怎么办**：

```java
// 1) 用可变容器（能改容器内容，不能改引用）
int[] counter = {0};
Consumer<String> g = x -> counter[0]++;

// 2) 用 AtomicInteger（多线程安全）
AtomicInteger safe = new AtomicInteger();
Consumer<String> h = x -> safe.incrementAndGet();

// 3) 用 reduce / sum 代替手工累加（最推荐）
int total = list.stream().mapToInt(String::length).sum();
```

第 3 种最好：累加本来就是 Stream 的职责，不该在 lambda 里手写。

### Lambda 有没有性能问题

Lambda **不是**匿名类的慢速写法，也不是反射。JDK 用 `invokedynamic` 生成调用点，第一次调用时引导一次，之后就是直接调用。

**创建 lambda 实例几乎零成本**（不生成新类），而匿名类每次 `new` 都分配一个对象。所以在热路径上 lambda 反而更轻。

唯一要注意的是**捕获**：lambda 捕获了外部变量时，每次创建都会带上那些引用。

### 一个常见的设计判断：什么时候该自己定义函数式接口

**用内置的就够**：`Function` / `Consumer` / `Supplier` / `Predicate` 加它们的变体覆盖绝大多数场景。

**该自己定义**：当语义需要名字来表意时。

```java
@FunctionalInterface
interface DiscountRule {
    BigDecimal apply(BigDecimal price, Customer customer);
}
```

`BiFunction<BigDecimal, Customer, BigDecimal>` 功能一样，但读代码时不知道"这是在算折扣"。**名字本身就是文档**——这是自定义函数式接口的唯一正当理由。

## 动手

这段代码演示四种内置接口、方法引用，以及"不能改外部变量"的报错原因。

<!-- run -->
```java
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Main {
    /** 自定义函数式接口：名字说明了语义。 */
    @FunctionalInterface
    interface PriceRule {
        int apply(int base, int percentOff);
    }

    public static void main(String[] args) {
        // 1) Function：输入一个，输出一个
        Function<String, Integer> length = String::length;
        System.out.println("长度：" + length.apply("hello"));

        // 2) Consumer：输入一个，不返回
        Consumer<String> printer = text -> System.out.println("打印：" + text);
        printer.accept("hi");

        // 3) Supplier：不输入，输出一个
        Supplier<List<String>> factory = ArrayList::new;
        System.out.println("新建列表：" + factory.get());

        // 4) Predicate：输入一个，判断
        Predicate<String> startsWithA = text -> text.startsWith("a");
        System.out.println("alice 以 a 开头：" + startsWithA.test("alice"));

        // 5) 自定义语义
        PriceRule halfOff = (base, percentOff) -> base * (100 - percentOff) / 100;
        System.out.println("打七折：" + halfOff.apply(100, 30));

        // 6) 方法引用 vs lambda
        List<String> names = List.of("bob", "alice", "carol");
        String joined = names.stream()
                .map(String::toUpperCase)      // 方法引用
                .sorted()
                .collect(Collectors.joining(","));
        System.out.println("排序后：" + joined);

        // 7) 捕获外部变量：只能读，不能改
        int bonus = 10;                        // effectively final
        Function<Integer, Integer> addBonus = value -> value + bonus;
        System.out.println("加奖励：" + addBonus.apply(5));
    }
}
```

```text
长度：5
打印：hi
新建列表：[]
alice 以 a 开头：true
打七折：70
排序后：ALICE,BOB,CAROL
加奖励：15
```

第十行「新建列表：[]」说明 `Supplier` 真的新建了一个空列表——它的价值在于"什么时候需要、什么时候才创建"，这对有代价的对象很重要。

### 你的练习

```code-challenge
{
  "id": "java-16-bug",
  "kind": "bug",
  "title": "找出改外部局部变量的 lambda",
  "language": "java",
  "code": "import java.util.List;\n\npublic class Main {\n    public static void main(String[] args) {\n        int total = 0;\n        List<Integer> numbers = List.of(1, 2, 3);\n        numbers.forEach(n -> total += n);\n        System.out.println(total);\n    }\n}",
  "bugLine": 7,
  "explain": "total 在 lambda 里被赋值，它就不是 effectively final 了，编译报错。累加应当用 numbers.stream().mapToInt(Integer::intValue).sum()，或用一个 int[] 容器。"
}
```

## 检查一下

```quiz
{
  "id": "java-16-quiz",
  "title": "Lambda 三问",
  "questions": [
    {
      "question": "lambda 的类型由什么决定？",
      "options": [
        "箭头后表达式的类型",
        "赋值目标必须是函数式接口，类型由它决定",
        "必须是 Runnable"
      ],
      "answer": 1,
      "explain": "lambda 本身无类型，编译器按目标类型推断。所以同一个 lambda 可以赋给不同函数式接口（只要签名匹配）。"
    },
    {
      "question": "为什么 lambda 里不能修改外部局部变量？",
      "options": [
        "性能问题",
        "lambda 可能被延迟或多线程执行，改外部变量的语义不明确，Java 直接禁止",
        "语法没实现"
      ],
      "answer": 1,
      "explain": "用 AtomicInteger 或 int[] 容器可以绕过，但更好的做法是把累加交给 reduce / sum。"
    },
    {
      "question": "以下哪个不是内置函数式接口？",
      "options": ["Function", "Consumer", "Transformer"],
      "answer": 2,
      "explain": "java.util.function 里没有 Transformer。类似语义的是 Function 或 UnaryOperator。"
    }
  ]
}
```

## 常见误区

### 误区：lambda 就是匿名类的简写，所以更慢

编译产物完全不同：lambda 走 `invokedynamic`，创建实例几乎不分配对象；匿名类每次 `new` 都分配。**lambda 通常更快**。

### 误区：方法引用永远比 lambda 好

只在"lambda 只是转手调一个方法"时成立。`x -> x * 2` 写成方法引用反而不可能（除非先定义方法），写成 lambda 更清楚。

### 误区：函数式接口都必须加 `@FunctionalInterface`

不是必需，但建议加。它把"只有一个抽象方法"从约定变成编译期检查，防止后来的人往里加方法把已有 lambda 全搞坏。

### 误区：lambda 里不能有 try-catch

可以，只是代码会变长：

```java
Function<String, Integer> parse = text -> {
    try {
        return Integer.parseInt(text);
    } catch (NumberFormatException failure) {
        return null;
    }
};
```

麻烦的是**受检异常**：lambda 体里不能直接 `throw` 受检异常（函数式接口的方法签名没声明它）。这种情况要么包成非受检异常，要么写一个自定义函数式接口声明 `throws`。

## 小结

- Lambda 的形状是 `(参数) -> 表达式`，类型由赋值目标决定，目标必须是**函数式接口**。
- 四个内置接口：`Function`（变换）、`Consumer`（消费）、`Supplier`（供给）、`Predicate`（判断）。
- 方法引用用于"lambda 只是转手调用一个方法"的场合。
- 捕获的外部局部变量必须 **effectively final**；要累加用 `sum` / `reduce`。
- 自定义函数式接口的唯一理由是**名字表意**。

下一课讲**并发**：`Thread`、`Runnable`、线程池，以及为什么不要自己 `new Thread()`。
