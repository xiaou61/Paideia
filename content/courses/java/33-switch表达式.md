---
title: 33 · switch 表达式与箭头语法
order: 33
chapter: 现代 Java
level: intermediate
prerequisite: 32 · 文本块
category: java-from-zero
minutes: 20
status: published
---

# 33 · switch 表达式与箭头语法

## 本该一行，却打印了两行

<!-- run -->
```java
public class Main {
    public static void main(String[] args) {
        int day = 3;
        switch (day) {
            case 1:
                System.out.println("mon");
            case 2:
                System.out.println("tue");
            case 3:
                System.out.println("wed");
            default:
                System.out.println("other");
        }
    }
}
```
```text
wed
other
```

我原来以为 `case 3` 匹配上就只执行那一支。实际是：冒号写法把 `case 3` 当成「从这里开始」的入口，执行完 `wed` 后继续往下，直到 `default` 打完 `other` 才停。`day=1` 时更夸张，会连续打印 `mon`、`tue`、`wed`、`other` 四行。

这不是 Java 的 bug，是 `switch` 语句本来的语义。箭头写法改的就是这一点。

## 箭头只走自己

```java
switch (day) {
    case 1 -> System.out.println("mon");
    case 2 -> System.out.println("tue");
    default -> System.out.println("other");
}
```

每个箭头分支执行完就结束，不会落到下一支，所以不需要 `break`。这不是「把冒号换成箭头」的缩写——它同时改掉了穿透语义。

```sketch
{
  "title": "冒号会往下漏，箭头只走自己",
  "rows": [
    [
      { "id": "colon", "title": "case 1:", "sub": "忘了写 break", "tone": "amber" },
      { "id": "fall", "title": "继续执行 case 2", "sub": "一直漏到 break 为止", "tone": "amber" }
    ],
    [
      { "id": "arrow", "title": "case 1 ->", "sub": "执行完就结束", "tone": "green" },
      { "id": "stop", "title": "不会碰到下一支", "sub": "不用再想 break", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "colon", "to": "fall" },
    { "from": "arrow", "to": "stop" }
  ]
}
```

## 它还能直接算出值

`switch` 作为表达式时，每个分支交一个值，整个 `switch` 可以直接赋给变量。

<!-- run -->
```java
public class Main {
    enum Level { LOW, HIGH }

    static String size(int code) {
        return switch (code) {
            case 1, 2 -> "小";
            case 3 -> {
                String name = "大";
                yield name + "号";
            }
            default -> "未知";
        };
    }

    public static void main(String[] args) {
        for (Level level : Level.values()) {
            String label = switch (level) {
                case LOW -> "入门";
                case HIGH -> "进阶";
            };
            System.out.println(level + "=" + label);
        }
        System.out.println(size(2));
        System.out.println(size(3));
    }
}
```
```text
LOW=入门
HIGH=进阶
小
大号
```

输出里有两条要分开看：

- `Level` 只有 `LOW`、`HIGH` 两个常量，两个都写了，所以表达式不需要 `default`。但「不需要」只针对枚举常量，`null` 是另一回事，下一节说。
- `size(2)` 走 `case 1, 2 ->`，输出 `小`；`size(3)` 走进大括号分支，真的执行到 `yield name + "号"`，输出 `大号`。如果只拿 `size(2)` 做例子，`yield` 那一支永远不会执行；你看到 `小`，会以为 `yield` 只是「换一种 return」，其实没跑过。

`yield` 不是 `return` 的别名。它只结束当前分支并交出一个值；方法本身的退出仍然靠 `return`。上面 `size` 方法里，`return switch (...)` 退出方法，`yield name + "号"` 只把值交给 `switch`。

## 表达式分支里不能 return / break / continue

我在一个类里把四种跳法都写了一遍，`javac --release 21` 一次报了四条（本机 Temurin 25.0.4，英文 locale）：

```java
public class Bad {
    static String f(int x) {
        return switch (x) {
            case 1 -> { return "one"; }
            default -> "other";
        };
    }

    static String g(int x) {
        return switch (x) {
            case 1 -> { break; }
            default -> "other";
        };
    }

    static String h(int x) {
        return switch (x) {
            case 1 -> { continue; }
            default -> "other";
        };
    }

    static int i() {
        yield 1;
    }
}
```
```text
Bad.java:4: error: attempt to return out of a switch expression
            case 1 -> { return "one"; }
                        ^
Bad.java:11: error: attempt to break out of a switch expression
            case 1 -> { break; }
                        ^
Bad.java:18: error: attempt to continue out of a switch expression
            case 1 -> { continue; }
                        ^
Bad.java:24: error: yield outside of switch expression
        yield 1;
        ^
4 errors
```

这四条不是「风格不好」，是编译器直接拒绝。`switch` 表达式本身要产出一个值，分支只能用 `yield` 交值；`return`、`break`、`continue` 都会试图跳出这个表达式，编译器不允许。

要退出方法，把 `return` 放在 `switch` 外面：

```java
static String f(int x) {
    return switch (x) {
        case 1 -> "one";
        default -> "other";
    };
}
```

`return` 本身不是禁区。下面这个 `switch` **语句**里，`case 1: return "one";` 编译并正常返回——区别在 `switch` 是语句还是表达式：

```java
static String g(int x) {
    switch (x) {
        case 1: return "one";
        default: return "other";
    }
}
```

## default 不等于接住 null

`Level` 全覆盖的表达式，传入 `null` 会怎样？我加了一个 `try`：

<!-- run -->
```java
enum Level { LOW, HIGH }

sealed interface Shape permits Circle, Square {}

final class Circle implements Shape {
    final double radius;
    Circle(double radius) { this.radius = radius; }
    double radius() { return radius; }
}

final class Square implements Shape {
    final double side;
    Square(double side) { this.side = side; }
    double side() { return side; }
}

public class Main {
    static String label(Level level) {
        return switch (level) {
            case LOW -> "入门";
            case HIGH -> "进阶";
        };
    }

    static String withDefault(Level level) {
        return switch (level) {
            case LOW -> "入门";
            default -> "兜底";
        };
    }

    static String withNull(Level level) {
        return switch (level) {
            case null -> "空";
            case LOW -> "入门";
            case HIGH -> "进阶";
        };
    }

    static String withNullDefault(Level level) {
        return switch (level) {
            case null, default -> "兜底";
        };
    }

    static double area(Shape shape) {
        return switch (shape) {
            case Circle c -> c.radius() * c.radius();
            case Square s -> s.side() * s.side();
        };
    }

    public static void main(String[] args) {
        System.out.println("null -> " + withNull(null));
        System.out.println("null, default -> " + withNullDefault(null));
        try {
            label(null);
        } catch (NullPointerException e) {
            System.out.println("enum 全覆盖: " + e.getClass().getSimpleName());
        }
        try {
            withDefault(null);
        } catch (NullPointerException e) {
            System.out.println("enum 带 default: " + e.getClass().getSimpleName());
        }
        try {
            area(null);
        } catch (NullPointerException e) {
            System.out.println("sealed: " + e.getClass().getSimpleName());
        }
    }
}
```
```text
null -> 空
null, default -> 兜底
enum 全覆盖: NullPointerException
enum 带 default: NullPointerException
sealed: NullPointerException
```

`enum 全覆盖` 和 `enum 带 default` 两行都是 `NullPointerException`——`default` 不接 `null`。枚举的穷尽性只保证「所有常量」被覆盖，`null` 不在这张表里。

具体消息（`javac -g` 编译，保留变量名）是：

```text
Cannot invoke "Level.ordinal()" because "level" is null
```

`area(null)` 也一样。它的 `getMessage()` 是 `null`，栈顶落在 `java.util.Objects.requireNonNull`。

Java 21 允许在 case 标签里写 `null`：`case null ->` 单独接住 null，`case null, default ->` 把 null 和其余所有值合并成一支。前者还要把枚举常量列全；后者一行就覆盖 null 和剩下的值。

## 什么时候必须写 default

先把两件事分开：`switch` **语句**和 `switch` **表达式**的检查规则不一样。

**switch 语句**

| 被 switch 的类型 | 少写分支会怎样 |
| --- | --- |
| 枚举，只覆盖一部分 | 编译通过；没匹配到就什么都不做 |
| `int` / `String` 等开放类型 | 编译通过；没匹配到就什么都不做 |
| sealed + 模式标签，没覆盖直接子类型 | 编译失败：`the switch statement does not cover all possible input values` |

我拿 `Level.HIGH` 跑了一个只写 `case LOW` 的语句：

```java
enum Level { LOW, HIGH }

public class Main {
    public static void main(String[] args) {
        Level level = Level.HIGH;
        switch (level) {
            case LOW -> System.out.println("low");
        }
        System.out.println("done");
    }
}
```
```text
done
```

编译通过，`HIGH` 没有匹配到任何分支，什么也没打印，最后只输出 `done`。这就是语句：它不要求有值。

**switch 表达式**

| 被 switch 的类型 | 少写分支会怎样 |
| --- | --- |
| 枚举，只覆盖一部分 | 编译失败：`the switch expression does not cover all possible input values` |
| `int` / `String` 等开放类型，没写 `default` | 编译失败：同上 |
| sealed + 模式标签，没覆盖直接子类型 | 编译失败：同上 |

同一个 `Level`，换成表达式就过不去：

```java
enum Level { LOW, HIGH }

public class EnumExpression {
    static String label(Level level) {
        return switch (level) {
            case LOW -> "low";
        };
    }
    public static void main(String[] args) {}
}
```
```text
EnumExpression.java:5: error: the switch expression does not cover all possible input values
        return switch (level) {
               ^
```

sealed 的**语句**又是另一条规则：它虽然不产出值，但用模式标签时必须穷尽。漏掉一个直接子类型，报的是 `the switch statement does not cover all possible input values`。

## 穷尽性检查的是直接子类型

第 36 课才讲 `sealed` / `permits`，这里先照抄，只记住一句：`sealed interface Node permits Leaf, Branch` 告诉编译器，`Node` 的**直接**子类型只有 `Leaf` 和 `Branch`。穷尽性检查的单位就是 `permits` 后面列出的这两个直接子类型，不是「所有实现类」。

```java
sealed interface Node permits Leaf, Branch {}
final class Leaf implements Node {}
non-sealed class Branch implements Node {}
class Twig extends Branch {}

public class NodeRun {
    static String name(Node node) {
        return switch (node) {
            case Leaf l -> "leaf";
            case Branch b -> "branch";
        };
    }

    public static void main(String[] args) {
        System.out.println(name(new Leaf()));
        System.out.println(name(new Branch()));
        System.out.println(name(new Twig()));
    }
}
```
```text
leaf
branch
branch
```

`Twig` 是 `Branch` 的子类，但它不是 `Node` 的直接子类型。表达式只覆盖 `Leaf` 和 `Branch`，编译通过；`new Twig()` 也会落到 `case Branch`。

这就是 `non-sealed` 的含义：`Branch` 这一支是开放的，后代可以继续出现；你不需要、也不可能把每个后代都列出来，`case Branch` 一次收住整支。

反过来，只写 `case Leaf` 就编译不过。我把它另存成 `NodeMissing.java`：

```text
NodeMissing.java:8: error: the switch expression does not cover all possible input values
        return switch (node) {
               ^
```

`Branch` 本身也是一个可能的值，`Twig` 只是它后面的一个后代。所以「sealed 接口的全部实现」这个说法会误导：检查的是 `permits` 列出的直接子类型，不是实现类的全集。

## when 守卫：false 就继续找下一支

`case Circle c when c.radius() > 0` 里的 `when` 是守卫。守卫为 false 时，这个 case 不算匹配，switch 继续尝试后面的 case；它不是「匹配上了但什么都不做」。

`Shape`、`Circle`、`Square` 沿用前面 `Shape permits Circle, Square` 那套定义。在前面的 `Main` 里加上：

```java
static String describe(Shape shape) {
    return switch (shape) {
        case Circle c when c.radius() > 0 -> "circle:" + c.radius();
        case Circle c -> "circle:not-positive";
        case Square s -> "square:" + s.side();
    };
}
```

把这三行放进 `main` 调用：

```java
System.out.println(describe(new Circle(2)));
System.out.println(describe(new Circle(0)));
System.out.println(describe(new Square(3)));
```

```text
circle:2.0
circle:not-positive
square:3.0
```

`Circle(2)` 走第一支；`Circle(0)` 的守卫为 false，继续落到第二支；`Square(3)` 走第三支。

守卫还有一个副作用：**带守卫的 case 不算穷尽覆盖**。因为守卫可能对每个值都为 false，编译器不把它当成「这个类型已经处理完了」。下面少了不带守卫的 `case Circle`，编译失败：

```text
GuardMissing.java:3: error: the switch expression does not cover all possible input values
        return switch (shape) {
               ^
```

必须有 `case Circle c -> ...` 或 `default` 兜底。顺序也要注意：把不带守卫的 `case Circle c` 放在前面，后面的守卫分支不会被静默跳过——javac 直接报 `this case label is dominated by a preceding case label`。

## 编译期检查只在重新编译时成立

先准备两个文件，`Level.java` 和 `Client.java`：

```java
public enum Level { LOW, HIGH }
```

```java
public class Client {
    static String label(Level level) {
        return switch (level) {
            case LOW -> "入门";
            case HIGH -> "进阶";
        };
    }

    public static void main(String[] args) {
        Level level = Level.valueOf(args[0]);
        System.out.println(label(level));
    }
}
```

一起编译，跑 `LOW`：

```text
javac --release 21 Level.java Client.java
java Client LOW
```
```text
入门
```

现在只改 `Level.java`，加一个 `THIRD`，**只重新编译枚举**，`Client.class` 不动：

```text
javac --release 21 Level.java
java Client THIRD
```
```text
Exception in thread "main" java.lang.MatchException
	at Client.label(Client.java:3)
	at Client.main(Client.java:11)
```

旧客户端不会在编译期发现新常量，运行时才炸。把 `Client.java` 也重新编译，才会出现熟悉的编译错误：

```text
Client.java:3: error: the switch expression does not cover all possible input values
        return switch (level) {
               ^
```

所以「把运行时问题提前成编译期问题」这句话要加一个前提：**调用方也重新编译**。分开编译、分开部署的模块，仍然可能在运行时遇到 `MatchException`。

## 能正常完成的分支才要兼容类型

`default -> throw ...` 是合法的，尽管它没有产出 `int`：

<!-- run -->
```java
public class Main {
    static int parse(String text) {
        return switch (text) {
            case "1" -> 1;
            case "2" -> 2;
            default -> throw new IllegalArgumentException("bad: " + text);
        };
    }

    public static void main(String[] args) {
        System.out.println(parse("1"));
        try {
            parse("x");
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
    }
}
```
```text
1
bad: x
```

`throw` 分支不会正常完成，所以它不需要可赋给 `int`。规则是：**能正常完成的分支**必须能赋给目标类型；抛异常、`return`、`break` 这些突然结束的路径不受这条约束。把 `default -> throw ...` 换成 `default -> "x"`，javac 报 `incompatible types: bad type in switch expression`，并补一句 `String cannot be converted to int`。

## 动手

下面这段老式 `switch` 少写了 `break`。它从标准输入读一个 `day`，要求：

- `day=1` 只输出 `mon`
- `day=2` 只输出 `tue`
- `day=3` 只输出 `wed`
- `day=9` 只输出 `other`

```code-challenge
{
  "id": "java-31-switch-fallthrough",
  "kind": "run",
  "title": "忘了 break，case 穿透了",
  "language": "java",
  "starter": "import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner scanner = new Scanner(System.in);\n        int day = scanner.nextInt();\n        switch (day) {\n            case 1:\n                System.out.println(\"mon\");\n            case 2:\n                System.out.println(\"tue\");\n            case 3:\n                System.out.println(\"wed\");\n            default:\n                System.out.println(\"other\");\n        }\n    }\n}",
  "cases": [
    {
      "name": "day=1",
      "stdin": "1\n",
      "expected": "mon\n"
    },
    {
      "name": "day=2",
      "stdin": "2\n",
      "expected": "tue\n"
    },
    {
      "name": "day=3",
      "stdin": "3\n",
      "expected": "wed\n"
    },
    {
      "name": "day=9",
      "stdin": "9\n",
      "expected": "other\n"
    }
  ],
  "explain": "老式 `case` 是「入口标签」：匹配到 1 会一直往下执行，直到遇到 `break`。只在 `case 3` 后面补一个 `break` 不够，`day=1`、`day=2` 仍会穿透。可以每个分支补 `break`，也可以整段改成箭头语法——箭头分支不穿透。"
}
```

**提示**：只在 `case 3` 后面补一个 `break` 不够——`day=1` 和 `day=2` 仍会往下漏。两种改法都行，推荐箭头。

## 检查一下

```quiz
{
  "id": "java-31-quiz",
  "title": "switch 三问",
  "questions": [
    {
      "question": "箭头分支需要写 break 吗？",
      "options": ["需要", "不需要，不会穿透", "只有最后一个需要"],
      "answer": 1,
      "explain": "箭头分支执行完就结束，不会落到下一支；`break` 是语句里的跳转，写在 `switch` 表达式里会报 `attempt to break out of a switch expression`。"
    },
    {
      "question": "switch 表达式里，多语句分支怎么交回值？",
      "options": ["return", "yield", "break"],
      "answer": 1,
      "explain": "`yield` 结束当前分支并交出值。`return`、`break`、`continue` 都不能从 `switch` 表达式里跳出去；要退出方法，写成 `return switch (...) { ... };`。"
    },
    {
      "question": "枚举覆盖全部常量的 switch 表达式，传入 null 会怎样？",
      "options": ["走 default", "返回 null", "抛 NullPointerException"],
      "answer": 2,
      "explain": "穷尽性只覆盖枚举常量，不覆盖 `null`。selector 为 `null` 时，`switch` 表达式先抛 `NullPointerException`；要接住得写 `case null` 或 `case null, default`。"
    }
  ]
}
```

## 还差的

- **只在 JDK 25 上跑过，编译目标是 21**。`--release 21` 能保证语法和 API 对齐 Java 21，但我没有在真正的 Java 21 运行时上比对 NPE 消息和 `MatchException` 的栈帧；消息文本可能随 JDK 版本变。
- **没测守卫表达式的副作用与求值顺序**。`when` 里如果调用有状态的方法，匹配失败时它执行几次、和后续 case 的顺序怎么交错，我只验证了纯函数 `radius() > 0`。
- **没测跨模块/独立类加载器的 `MatchException`**。上面的实验是同一目录、同一 classpath；模块化或热部署场景下旧客户端和新枚举的边界更复杂。
- **没测 `non-sealed` 层级里的抽象中间类、泛型 sealed 接口**。`Node`/`Leaf`/`Branch`/`Twig` 是最小例子，真实层级可能还有抽象类和更多分支。
