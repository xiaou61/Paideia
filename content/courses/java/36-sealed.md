---
title: 36 · sealed 与封闭类型
order: 36
chapter: 现代 Java
level: advanced
prerequisite: 35 · record
category: java-from-zero
minutes: 20
status: published
---

# 36 · sealed 与封闭类型

## 加一种支付结果，程序没崩，只是少了一行

我写过一个把支付结果转成人话的方法，四个结果各自一个 `record`，`switch` 末尾留着 `default -> "unknown"`。上线两周后加了「退款」：

```java
interface PaymentResult {
}

record Success(String orderId) implements PaymentResult {
}

record Failure(String reason) implements PaymentResult {
}

record Pending() implements PaymentResult {
}

record Refunded(String orderId) implements PaymentResult {
}

static String describe(PaymentResult r) {
    return switch (r) {
        case Success s -> "paid " + s.orderId();
        case Failure f -> "failed " + f.reason();
        case Pending p -> "pending";
        default -> "unknown";
    };
}
```

跑两次：

```text
paid A1
unknown
```

编译通过、测试没红、日志里就一行 `unknown`。**`default` 把「多了一种结果没人处理」变成了运行期的一句话**，这是我加 `default` 那天亲手埋的。

## 把「允许哪些实现」写进类型

`sealed` 就是把名单写进声明，别人再想实现它，编译器当场拦住。下面这段把 `Refunded` 加进代码、但**没有**写进 `permits`：

```java
sealed interface PaymentResult permits Success, Failure, Pending {
}
```

真跑 `javac --release 21`：

```text
P2.java:6: error: class is not allowed to extend sealed class: PaymentResult (as it is not listed in its 'permits' clause)
    record Refunded(String orderId) implements PaymentResult {}
    ^
1 error
```

把它加进 `permits`、`switch` 却不补分支：

```text
P3.java:9: error: the switch expression does not cover all possible input values
        return switch (r) {
               ^
1 error
```

这一步才是 `sealed` 真正的价值：**改类型的人当场知道自己漏了哪里**，而不是等退款单子在页面上显示成 `unknown`。

补全之后就能跑了，也仍然不需要 `default`：

<!-- run -->
```java
public class Main {
    sealed interface PaymentResult permits Success, Failure, Pending, Refunded {
    }

    record Success(String orderId) implements PaymentResult {
    }

    record Failure(String reason) implements PaymentResult {
    }

    record Pending(String orderId) implements PaymentResult {
    }

    record Refunded(String orderId) implements PaymentResult {
    }

    static String describe(PaymentResult result) {
        return switch (result) {
            case Success s -> "paid " + s.orderId();
            case Failure f -> "failed " + f.reason();
            case Pending p -> "pending " + p.orderId();
            case Refunded r -> "refunded " + r.orderId();
        };
    }

    public static void main(String[] args) {
        System.out.println(describe(new Success("A1")));
        System.out.println(describe(new Failure("timeout")));
        System.out.println(describe(new Pending("A2")));
        System.out.println(describe(new Refunded("A1")));
    }
}
```
```text
paid A1
failed timeout
pending A2
refunded A1
```

## `permits` 不是必写项

我一开始以为 `sealed interface Shape {}` 一定编译不过。真试了才知道分两种情况，**取决于子类型在不在同一个编译单元**。

同一个文件里全部写齐，`permits` 可以省，编译器自己从同一编译单元的直接子类型推：

```java
public class A {
    sealed interface Shape {
    }

    record Circle(double r) implements Shape {
    }

    record Square(double s) implements Shape {
    }

    public static void main(String[] args) {
        System.out.println("A ok: " + new Circle(1).getClass().getSimpleName());
    }
}
```

```text
A ok: Circle
```

一旦子类型挪到另一个文件，省掉 `permits` 就一次报两条：

```text
Circle.java:1: error: class is not allowed to extend sealed class: Shape (as it is not listed in its 'permits' clause)
public record Circle() implements Shape {}
       ^
Shape.java:1: error: sealed class must have subclasses
public sealed interface Shape {}
              ^
2 errors
```

第二条是我原来不知道的：**封闭类型必须至少有一个直接子类型**。光写一个 `sealed interface Empty {}` 也是同一个错：

```text
C.java:1: error: sealed class must have subclasses
public class C { sealed interface Empty {} public static void main(String[] args) {} }
                        ^
1 error
```

位置规则同样比我以为的更细。未命名模块（也就是我们平时写的一堆 .java 直接 `javac`）里，子类型必须和父类型**同包**：

```text
p\Shape.java:2: error: class Shape in unnamed module cannot extend a sealed class in a different package
public sealed interface Shape permits q.Circle {}
                                       ^
1 error
```

而放进具名模块（有 `module-info.java`）后，同模块不同包是允许的。这一条我只看过文档和 `javac` 的报错文案，没有在带模块的真实工程里跑过。

## 三种子类型：`non-sealed` 到底重新打开了什么

| 子类型修饰 | 含义 |
| --- | --- |
| `final` | 到此为止，不能再有子类 |
| `sealed` | 继续对下一层列名单 |
| `non-sealed` | 这一支重新对所有人开放 |

我原来以为「用了 `non-sealed` 就没有穷尽检查了」，实测不是这么粗糙。给 `Shape` 加一个 `non-sealed interface Polygon`：

```java
sealed interface Shape permits Circle, Polygon {
}

record Circle(double radius) implements Shape {
}

non-sealed interface Polygon extends Shape {
}

record Triangle(double a, double b) implements Polygon {
}

static double area(Shape shape) {
    return switch (shape) {
        case Circle(double radius) -> Math.PI * radius * radius;
        case Polygon p -> -1;
    };
}
```

这个 `switch` 照样穷尽、照样能编译能跑：

```text
E area(circle) = 3
E area(triangle via Polygon) = -1.0
```

但**针对 `Polygon` 自己的 `switch`** 就不能再穷尽了：

```text
E2.java:8: error: the switch expression does not cover all possible input values
        return switch (p) {
               ^
1 error
```

准确说法是：`non-sealed` 只把**这一支**重新开放；父类型的 `switch` 仍然可以靠「覆盖 `Polygon` 这个类型本身」保持穷尽，代价是这一支的后代全落到同一个分支里；而对 `Polygon` 写的 `switch` 从此没有穷尽性保证。所以 `non-sealed` 不是免费的开关，它是「这一部分我不打算穷尽处理了」的声明。

## 穷尽不等于覆盖 `null`

`switch` 覆盖了全部 sealed 子类型，`shape` 是 `null` 时照样炸。真跑：

```text
G NPE: java.lang.NullPointerException
```

`default` 也不接 `null`——它接的是「其他类型」，不是「没有对象」。要处理得显式写 `case null ->`，Java 21 也支持 `case null, default ->` 一起写。我第一版就是这么漏的：以为穷尽性检查连 `null` 都算进去了。

## `when` 守卫：不加 fallback 就不算穷尽

Java 21 的 `case` 后面可以跟守卫：

```java
static String describe(Shape shape) {
    return switch (shape) {
        case Circle c when c.radius() > 0 -> "positive circle";
        case Circle c -> "degenerate circle";
        case Square s -> "square";
    };
}
```

守卫为 false 时会继续匹配后面的 `case`，所以半径 -1 的圆落到第二条：

```text
J degenerate circle
J positive circle
```

但**带守卫的 `case` 不算穷尽覆盖**。删掉那条不带守卫的 `case Circle c`：

```text
K.java:7: error: the switch expression does not cover all possible input values
        return switch (shape) {
               ^
1 error
```

道理不难想：运行时才知道守卫真假，编译器不能拿一个可能为 false 的条件当「这一支处理过了」。所以只要有 `when`，同一类型通常得留一条不带守卫的分支兜住。

## 编译期检查有时效性

`sealed` 省掉 `default` 之后，加一种实现会让**重新编译的调用方**编译失败。已经编译好的旧 jar 不在这个保护里：先编译 `enum Level { LOW, HIGH }` 和用到它的 `Client`，只把枚举改成 `{ LOW, HIGH, URGENT }` 重编、`Client` 不重编，运行：

```text
H start
Exception in thread "main" java.lang.MatchException
	at Client.label(Client.java:3)
	at Client.main(Client.java:10)
```

`MatchException` 是 Java 21 为此专门加的。所以「加了新类型就会编译失败」要加上一句：**前提是把调用方一起重新编译**。跨仓库、跨服务的模型改动仍然是发版顺序问题，`sealed` 管不到。

## 反射能不能硬塞一个实现

这大概是最多人问的。实测两条路都堵死：对 sealed 接口用动态代理直接抛 `IllegalArgumentException`，用 `MethodHandles.lookup().defineClass` 塞一个不在名单里的类抛

```text
java.lang.IncompatibleClassChangeError: Failed listed permitted subclass check
```

但别把结论放大成「安全边界」：`Unsafe`、`-javaagent` 改写字节码这类非正常手段我没测，`sealed` 是**类型系统里的约束**，不是沙箱。

## 枚举还是 sealed

我原来信了一句「枚举不能带不同数据」，写这课时翻了第 26 课才发现它是错的——枚举本来就能带字段、构造器和方法：

```java
enum Payment {
    SUCCESS("ok", 200),
    FAILURE("bad", 500);

    private final String message;
    private final int code;

    Payment(String message, int code) {
        this.message = message;
        this.code = code;
    }

    String message() {
        return message;
    }

    int code() {
        return code;
    }
}
```

```text
SUCCESS ok 200
FAILURE bad 500
```

所以判据不是「有没有数据」，而是**结构一不一样**：

- 每种情况都只有固定的几个常量、字段形状相同 → 枚举；
- 每种情况字段集合不同（`Success` 带单号、`Failure` 带原因、`Refunded` 带单号和金额），要按类型或记录模式解构 → `sealed` + `record`；
- 明确要允许第三方扩展 → 普通接口。

## 动手

下面这段编译不过：`switch` 漏了一种形状。找出有问题的第一行。

```code-challenge
{
  "id": "java-34-sealed-exhaustive",
  "kind": "bug",
  "title": "sealed 家族漏了一个分支",
  "language": "java",
  "code": "public class Main {\n    sealed interface Shape permits Circle, Square {\n    }\n\n    record Circle(double radius) implements Shape {\n    }\n\n    record Square(double side) implements Shape {\n    }\n\n    static double area(Shape shape) {\n        return switch (shape) {\n            case Circle(double radius) -> Math.PI * radius * radius;\n        };\n    }\n\n    public static void main(String[] args) {\n        System.out.println(area(new Square(2)));\n    }\n}",
  "bugLine": 12,
  "explain": "`Shape` 是 sealed 且 permits 了 Circle 与 Square，编译器知道全部可能，`return switch` 这一行就报 the switch expression does not cover all possible input values。补上 `case Square(double side) -> side * side;` 即可。注意别用 `default` 兜底——那样以后再新增形状就不报了，正是本课开头那个 unknown。"
}
```

## 检查一下

```quiz
{
  "id": "java-34-quiz",
  "title": "sealed 三问",
  "questions": [
    {
      "question": "sealed interface 的 permits 子句表达什么？",
      "options": ["哪些类可以使用它", "哪些类可以实现/继承它", "它继承谁"],
      "answer": 1,
      "explain": "permits 列的是被允许的直接子类型，名单外的类实现它会编译失败。"
    },
    {
      "question": "sealed 的 switch 不写 default 依然能通过穷尽检查，但哪种值照样会让运行时炸？",
      "options": ["null", "子类型的新实例", "枚举常量"],
      "answer": 0,
      "explain": "穷尽性覆盖的是 sealed 的直接子类型，不含 null；要处理得显式写 case null。"
    },
    {
      "question": "什么时候用 sealed + record 而不是枚举？",
      "options": ["每种情况的字段结构与数量不同，需要按类型解构", "只有几个固定常量", "需要第三方扩展"],
      "answer": 0,
      "explain": "枚举也能带字段（第 26 课），区别在结构：各种情况字段集合不同、需要按类型或记录模式解包时用 sealed + record。"
    }
  ]
}
```

## 还差的

- 具名模块下「同模块不同包」这条我只核对了文档与 `javac` 报错，没在真实模块化工程里跑；`module-info.java` 与 `permits` 的交互还有细节。
- `MatchException` 的捕获方式我只看到它抛出来，没写真实的重试/降级策略。
- `Unsafe`、`-javaagent`、native agent 能不能绕过 permitted 检查，我没测，所以「反射堵死」这个结论只覆盖常规反射。
- 记录模式（`case Circle(double radius)`）在嵌套泛型上的行为没验，本课只用了最浅的一层。
