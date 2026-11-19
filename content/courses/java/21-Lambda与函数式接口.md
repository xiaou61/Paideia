---
title: 21 · Lambda 与函数式接口
order: 21
chapter: 泛型与函数式
level: intermediate
prerequisite: 20 · Stream：把循环压成一条链
category: java-from-zero
minutes: 20
status: published
---

# 21 · Lambda 与函数式接口

我想把购物车里的单价加起来，照上一课的路子写成链，`forEach` 里顺手用了外面的 `total`：

```java
import java.util.List;

public class Main {
    static final class Item {
        final int cents;

        Item(int cents) { this.cents = cents; }

        int cents() { return cents; }
    }

    public static void main(String[] args) {
        int total = 0;
        List.of(new Item(4999), new Item(6200), new Item(1500))
                .forEach(item -> total += item.cents());
        System.out.println(total);
    }
}
```

```text
Main.java:15: error: local variables referenced from a lambda expression must be final or effectively final
                .forEach(item -> total += item.cents());
                                 ^
1 error
```

我当时第一反应是「`forEach` 不接返回值，所以里面不能写赋值语句」——猜错了。报错指的是 `main` 的局部变量 `total`，箭头后面的写法本身没毛病。下面所有输出都出自本机 Temurin 25.0.4、`javac --release 21`；为了读英文报错我设了 `JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US`，报错文本是原样复制。

## 它拷贝的是变量，不是共享那个变量

lambda 没有自己的栈帧。编译时，它把用到的外部局部变量当作构造参数接进去，存成自己对象上的字段——所以 lambda 体里读到的是一份**副本**。既然改副本外面看不见，「在 lambda 里累加」这个写法本身就没有意义，JLS 索性要求这类变量是 `final` 或 effectively final（没被重新赋值过），把「改不到外面」从运行期陷阱变成编译期错误。

卡住的不是「lambda 里不能有语句」。同一句话，把 `total` 从局部变量换成字段就能编过，因为没有「复制到 lambda 字段」这一步：

```java
import java.util.List;

public class Main {
    static int running = 0;

    public static void main(String[] args) {
        List.of(4999, 6200, 1500).forEach(cents -> running += cents);
        System.out.println(running);
    }
}
```

```text
12699
```

这段能跑，但仍然是坏习惯：`forEach` 里的副作用没法并行（第 22 课会碰到），而且它把「求和」写成了命令式。原来那句的正确改法是让 Stream 自己求和，一个变量都不用捕获（`Item` 用开场那个类）：

```java
int total = List.of(new Item(4999), new Item(6200), new Item(1500))
        .stream().mapToInt(Item::cents).sum();
```

同一批数据，输出 `12699`。

## 数组能过、换个指向不能过

局部变量不只有 `int`。同样是往「外面」累加，换成数组就编得过：

```java
import java.util.List;

public class Main {
    public static void main(String[] args) {
        int[] running = {0};
        List.of(4999, 6200, 1500).forEach(cents -> running[0] += cents);
        System.out.println(running[0]);
    }
}
```

```text
12699
```

因为捕获的是**引用的副本**：`main` 里的 `running` 和 lambda 字段里的 `running` 指向同一个数组对象，而 `running[0]` 是那个对象的元素，不是局部变量。真正被禁止的是给变量本身重新赋值：

```java
public class Main {
    static final class Counter {
        int value;
    }

    public static void main(String[] args) {
        Counter counter = new Counter();
        Runnable reset = () -> counter = new Counter();
        reset.run();
    }
}
```

```text
Main.java:8: error: local variables referenced from a lambda expression must be final or effectively final
        Runnable reset = () -> counter = new Counter();
                               ^
1 error
```

一句话判断：**动对象可以，动变量不行**。`counter.value++` 编过（改的是对象），`counter = new Counter()` 编不过（改的是变量）。

## 一句 body 落在哪个类型上，由赋值目标决定

lambda 自己没有类型，编译器拿目标类型来核对签名。所以同一段箭头函数可以落在两个接口上：

```java
@FunctionalInterface
interface Discount {
    int apply(int amount, int percentOff);
}

Discount thirty = (amount, percentOff) -> amount * (100 - percentOff) / 100;
```

这份 body 原样塞进 `BiFunction<Integer, Integer, Integer>` 也编得过，两边都是 `8889`。差别在装箱：`Discount.apply` 收 `int`，`BiFunction` 收 `Integer`，后者每次调用多两次拆装。

目标有两个、都装得下，编译器就没有依据了：

```java
public class Main {
    interface Parser {
        int parse(String sku);
    }

    interface Filter {
        int keep(String sku);
    }

    static void use(Parser rule) { System.out.println("Parser"); }

    static void use(Filter rule) { System.out.println("Filter"); }

    public static void main(String[] args) {
        use(sku -> sku.length());
    }
}
```

```text
Main.java:15: error: reference to use is ambiguous
        use(sku -> sku.length());
        ^
  both method use(Parser) in Main and method use(Filter) in Main match
1 error
```

这不是 lambda 的毛病，是「无类型 body + 重载」撞在一起。加个 `(Parser)` 强制转换就定了。

`@FunctionalInterface` 也是在这一层起作用——它让编译器替你检查接口里确实只剩一个抽象方法。把下面这个存成 `Rule.java` 再编：

```java
@FunctionalInterface
interface Rule {
    int apply(int amount);
    int applyTo(int amount);
}
```

```text
Rule.java:1: error: Unexpected @FunctionalInterface annotation
@FunctionalInterface
^
  Rule is not a functional interface
    multiple non-overriding abstract methods found in interface Rule
1 error
```

但「只有一个抽象方法」这个说法要补一句：真正不计入的是**被 `Object` 的方法覆盖掉的那些**（`equals`、`hashCode`），以及 `default` 和 `static` 方法。给上面那个 `Discount` 再加 `default int applyTo(int amount)` 和 `static Discount none()`，它照样是函数式接口——同一个 lambda 一路编过，`apply(12699, 30)` 打 `8889`、`applyTo(500)` 打 `500`、`Discount.none().apply(12699, 30)` 打 `12699`。所以别一看到接口里有第二个方法就以为不能用 lambda。

## 四个内置接口，先认方法名

`java.util.function` 里几十个接口，日常先记这四个加它们的方法名——看到方法名就知道该往哪儿塞：

| 你想干的事 | 接口 | 唯一的抽象方法 | 常见的调用点 |
| --- | --- | --- | --- |
| 一件东西换成另一件 | `Function<T, R>` | `R apply(T)` | `stream.map(...)` |
| 挨个喂进去，不要结果 | `Consumer<T>` | `void accept(T)` | `forEach(...)` |
| 要的时候现造一个 | `Supplier<T>` | `T get()` | `orElseGet(...)`，第 18 课的 `Optional` |
| 判真假 | `Predicate<T>` | `boolean test(T)` | `filter(...)`、`anyMatch(...)` |

这四个接口在下面的「动手」里各出现一次，是同一份能跑的程序：`Item::cents` 当 `Function`、`cart.forEach(addUp)` 当 `Consumer`、`cheap` 当 `Predicate`、`ArrayList::new` 当 `Supplier`。

`Function<Item, Integer>` 每次 `apply` 都要把 `int` 装箱成 `Integer`。数值流水线上想避开这一步，用 `ToIntFunction<Item>`，Stream 那边对应 `mapToInt`。

还有一个边界要提前知道：这四个接口的抽象方法签名里都**没有 `throws`**。所以 `Integer::parseInt` 能塞进 `Function<String, Integer>`（它抛的 `NumberFormatException` 是非受检异常），而 `Files.readString`（抛 `IOException`）塞不进任何一个内置接口——只能在 lambda 体里 try-catch 掉，或者自己写一个声明了 `throws` 的接口。

## 方法引用只在「转手」时成立

四种形式都在这张表里，例子全用购物车这个对象：

| 写法 | 等价于 | 成立的条件 |
| --- | --- | --- |
| `Integer::parseInt` | `sku -> Integer.parseInt(sku)` | 静态方法，参数直接转交 |
| `Item::cents` | `item -> item.cents()` | 实例方法，第一个参数当接收者 |
| `System.out::println` | `text -> System.out.println(text)` | 实例方法，接收者已经固定 |
| `ArrayList::new` | `() -> new ArrayList<>()` | 构造器 |

`Item::cents` 能当 `Function<Item, Integer>`，是因为 `cents()` 无参、返回值对得上。写成 `Item::getCents` 就直接编译失败——方法引用是编译期按名字查的，不是反射（行号按我那个测试文件，你写的时候会不一样）：

```text
Main.java:14: error: invalid method reference
        Function<Item, Integer> priceOf = Item::getCents;
                                          ^
  cannot find symbol
    symbol:   method getCents(Item)
    location: class Item
1 error
```

反过来不成立：`item -> item.cents() * 2` 没有现成方法可引用，只能先写一个 `static int doubled(Item item)`，再 `Main::doubled`——那时计算只是挪了个地方，一点没变短。判据就是「lambda 体里除了转手调用，还有没有别的动作」。

## 无捕获的 lambda 会复用实例，但这只是当前实现

我原来以为 lambda 是匿名类的语法糖、所以更慢。先看编译产物：同一个文件里放一个 lambda 和一个匿名类，javac 之后只有匿名类落地成文件：

```text
Main.class
Main$1.class
```

`javap -p -c Main` 里对应两行：

```text
    0: invokedynamic #7,  0              // InvokeDynamic #0:run:()Ljava/lang/Runnable;
    6: new           #11                 // class Main$1
```

所以「不生成新类」这个说法只对了一半：你的 classpath 上确实没有多出 `.class` 文件，但运行期是**真的生成了一个类**，只是它是隐藏类。实例的类名打出来是这样（地址每次运行都不同）：

```text
类名：Main$$Lambda/0x0000000025040210
```

想让那个隐藏类落成文件，加启动参数 `-Djdk.invoke.LambdaMetafactory.dumpProxyClassFiles=true`，执行完目录里多出：

```text
DUMP_LAMBDA_PROXY_CLASS_FILES\Main$$Lambda.0x000000001d040210.class
```

另一件能测的事：不捕获任何变量的 lambda，同一个调用点返回的是**同一个实例**；捕获了变量的，每次新建。

```text
无捕获 同一实例 true
有捕获 同一实例 false
```

这来自 HotSpot 当前实现（调用点把实例缓存住了），JLS 并不保证。所以别拿 `==` 比较两个 lambda，也别把 lambda 当 Map 的 key 去指望行为稳定。至于「lambda 比匿名类快多少」，这里没有数字——没跑 JMH 就不编，见文末。

## 动手

把上面几段拼成一个 `Main`：`Item` 是同一个对象，四个内置接口、方法引用、规则叠加、无捕获/有捕获都在这一份里。

<!-- run -->
```java
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class Main {
    static final class Item {
        final String sku;
        final int cents;

        Item(String sku, int cents) {
            this.sku = sku;
            this.cents = cents;
        }

        int cents() { return cents; }
        String sku() { return sku; }
    }

    @FunctionalInterface
    interface Discount {
        int apply(int amount, int percentOff);
    }

    static final List<Function<Integer, Integer>> RULES = List.of(
            amount -> amount >= 10000 ? amount - 1000 : amount,
            amount -> amount * 90 / 100);

    static int afterRules(int amount) {
        int result = amount;
        for (Function<Integer, Integer> rule : RULES) {
            result = rule.apply(result);
        }
        return result;
    }

    static Runnable plain() { return () -> { }; }

    static Runnable echo(int value) { return () -> System.out.print(value); }

    public static void main(String[] args) {
        List<Item> cart = List.of(new Item("A-1", 4999), new Item("B-2", 6200), new Item("C-3", 1500));

        Function<Item, Integer> priceOf = Item::cents;
        int total = 0;
        for (Item item : cart) total += priceOf.apply(item);
        System.out.println("合计 " + total + " 分");

        int[] running = {0};
        Consumer<Item> addUp = item -> running[0] += item.cents();
        cart.forEach(addUp);
        System.out.println("容器累加 " + running[0] + " 分");

        Predicate<Item> cheap = item -> item.cents() < 5000;
        System.out.println("低于 5000 分的件数 " + cart.stream().filter(cheap).count());

        Supplier<List<String>> newBag = ArrayList::new;
        List<String> bag = newBag.get();
        for (Item item : cart) bag.add(item.sku());
        System.out.println("SKU " + bag);

        System.out.println("折后 " + afterRules(total) + " 分");

        Discount thirty = (amount, percentOff) -> amount * (100 - percentOff) / 100;
        BiFunction<Integer, Integer, Integer> same =
                (amount, percentOff) -> amount * (100 - percentOff) / 100;
        System.out.println("Discount " + thirty.apply(12699, 30) + "，BiFunction " + same.apply(12699, 30));

        System.out.println("无捕获 同一实例 " + (plain() == plain()));
        System.out.println("有捕获 同一实例 " + (echo(1) == echo(1)));
    }
}
```

```text
合计 12699 分
容器累加 12699 分
低于 5000 分的件数 2
SKU [A-1, B-2, C-3]
折后 10529 分
Discount 8889，BiFunction 8889
无捕获 同一实例 true
有捕获 同一实例 false
```

「折后 10529」是两条规则按顺序叠出来的：`12699` 先满 10000 减 1000 得 `11699`，再乘 90 除 100，整数除法截掉 `0.1` 分。规则写在 `List<Function<Integer, Integer>>` 里，加一条就是往列表加一行——这是函数式接口最实用的地方，行为能当数据存。

## 挑错

下面这段只读不改，先找出该怎么修：

```code-challenge
{
  "id": "java-16-bug",
  "kind": "bug",
  "title": "找出让 lambda 编不过的那一行",
  "language": "java",
  "code": "import java.util.List;\n\npublic class Main {\n    public static void main(String[] args) {\n        int total = 0;\n        List<Integer> numbers = List.of(1, 2, 3);\n        Runnable sum = () -> System.out.println(total);\n        total = 10;\n        sum.run();\n    }\n}",
  "bugLine": 8,
  "explain": "报错指在第 7 行那个 lambda 上，但改第 7 行没用——问题在第 8 行又给 total 赋了一次值，total 从此不是 effectively final。删掉第 8 行能编过并打印 0（捕获发生在创建 lambda 那一刻）；真要打印 10，就先把值落到一个新变量再捕获（int later = total;），或者改用字段——捕获一个之后还要重新赋值的局部变量，编译器永远不给过。"
}
```

## 检查一下

```quiz
{
  "id": "java-16-quiz",
  "title": "Lambda 三问",
  "questions": [
    {
      "question": "同一段箭头函数 body，为什么能分别赋给 Discount 和 BiFunction 两个接口？",
      "options": [
        "因为 lambda 会被编译成匿名类，匿名类可以随便转型",
        "只有签名完全一致的两个接口才行，别的情况都编不过",
        "lambda 本身没有类型，编译器拿赋值目标的抽象方法去核对参数与返回值，装得下就成立"
      ],
      "answer": 2,
      "explain": "目标类型决定 lambda 的类型。Discount.apply 收 int，BiFunction 收 Integer，两者都装得下那段 body，所以都编得过，代价是后者多了装箱。"
    },
    {
      "question": "lambda 里改 main 的局部 int 会编译失败，改 static 字段却能过，原因是哪一个？",
      "options": [
        "局部变量被拷进 lambda 的字段，改副本外面看不见，所以要求 final 或 effectively final；字段是直接访问对象，不拷贝",
        "因为 int 是基本类型，基本类型的局部变量在 lambda 里不能改",
        "因为 static 字段的可见性更高，lambda 内部默认能拿到"
      ],
      "answer": 0,
      "explain": "捕获的是值。引用类型的局部变量也一样受限：int[] running 能过是因为改的是数组元素，running = new int[1] 这种重新赋值照样报 effectively final。"
    },
    {
      "question": "两次调用同一个工厂方法返回的无捕获 lambda，用 == 比较得到 true。怎么理解这件事？",
      "options": [
        "JLS 规定相同的 lambda 表达式只能有一个实例，可以放心依赖",
        "这是 HotSpot 当前实现把调用点缓存住的结果，规范不保证，别拿 lambda 当 Map key 或据此写逻辑",
        "说明 lambda 是编译期常量，和字符串字面量同一个机制"
      ],
      "answer": 1,
      "explain": "无捕获的 lambda 在实现上复用了同一个实例，捕获了的每次新建，这两条都是实测的当前行为，不是语言契约。"
    }
  ]
}
```

## 还差的

- 没跑 JMH。lambda 与匿名类的对比只到「是否落地成 .class」「实例复用还是新建」，没有吞吐和分配率数字，所以本文不给「快多少」的结论。
- `java.util.function` 里的变体只点了 `ToIntFunction`、`BiFunction` 两个名，`IntFunction`、`BinaryOperator`、`BiConsumer` 以及各自的拆装箱规则没展开。
- 受检异常只说清了「塞不进内置接口」，没给出可复用的包装：`ThrowingFunction` 加 `sneakyThrow` 那条路子一个字没写。
- lambda 和泛型方法放一起的写法（`<T> List<T> pick(List<T> list, Predicate<T> keep)`）没展开，那是第 17 课泛型的活；本文的类型参数只到单个具体类型为止。
- 并发下的捕获共享只是名字：`AtomicInteger`、并行流里共享一个数组会怎样，留给第 22 课。
