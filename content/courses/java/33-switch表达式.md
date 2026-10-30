---
title: 33 · switch 表达式与箭头语法
order: 33
chapter: 现代 Java
level: intermediate
prerequisite: 32 · 文本块
category: java-from-zero
minutes: 26
status: published
---

# 33 · switch 表达式与箭头语法

## 这一课你要会

- 用 `case X ->` 写不穿透的 `switch`；
- 把 `switch` 当**表达式**用，直接赋给变量；
- 知道 `yield` 与 `return` 的分工；
- 知道什么时候必须写 `default`。

## 为什么

老式 `switch` 有两个经典 bug：忘了 `break` 导致穿透，以及"一个分支只为了给变量赋值"要写一堆重复代码。

```text
String label;
switch (level) {
    case LOW:
        label = "入门";
        break;
    case HIGH:
        label = "进阶";
        break;
    default:
        label = "未知";
}
```

箭头语法把这两件事一起解决了：**箭头分支不会穿透**，而且 `switch` 可以直接产出值。

## 讲解

### 箭头不穿透

```text
switch (day) {
    case 1 -> System.out.println("mon");
    case 2 -> System.out.println("tue");
    default -> System.out.println("other");
}
```

每个箭头分支只执行自己，不需要 `break`。传统冒号写法会从匹配的那一行一直往下执行，直到遇到 `break`——这是 `switch` 最著名的坑。

```sketch
{
  "title": "冒号写法会往下漏，箭头写法只走自己",
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

### 当表达式用

`switch` 现在可以直接算出值：

<!-- run -->
```java
public class Main {
    enum Level {
        LOW, HIGH
    }

    public static void main(String[] args) {
        for (Level level : Level.values()) {
            String label = switch (level) {
                case LOW -> "入门";
                case HIGH -> "进阶";
            };
            System.out.println(level + "=" + label);
        }

        int code = 2;
        String name = switch (code) {
            case 1, 2 -> "小";
            case 3 -> {
                String size = "大";
                yield size + "号";
            }
            default -> "未知";
        };
        System.out.println(name);
    }
}
```
```text
LOW=入门
HIGH=进阶
小
```

三个要点：

1. **枚举覆盖全部常量时，`switch` 表达式可以不写 `default`**。编译器知道没有别的可能；
2. 一个 `case` 可以列多个值：`case 1, 2 ->`；
3. 分支里有多条语句时用大括号，**用 `yield` 交出值**，不是 `return`——`yield` 只结束这个分支，不会结束整个方法。

### `default` 什么时候必须写

| 被 switch 的类型 | 是否必须 `default` |
| --- | --- |
| 枚举，且覆盖了全部常量 | 不必须 |
| 枚举，只覆盖一部分 | 必须 |
| `int` / `String` 等开放类型 | 必须 |
| `sealed` 接口的全部实现（第 36 课） | 不必须 |

编译器会检查"表达式是否一定有一个值"。少一个分支，编译直接失败——这比运行时才发现没处理某个状态好得多。

### 表达式与语句的区别

- **语句**：执行动作，不产出值。`switch (...) { case ... -> doSomething(); }`
- **表达式**：产出一个值，可以赋给变量、作为方法参数。

同一个 `switch` 关键字，两种用法都支持。写表达式时，所有分支必须产出**兼容的类型**，否则编译不过。

## 动手

下面这段老式 `switch` 少写了 `break`，本该只打印 `wed`，实际打印了两行。修好它。

```code-challenge
{
  "id": "java-31-switch-fallthrough",
  "kind": "run",
  "title": "忘了 break，case 穿透了",
  "language": "java",
  "starter": "public class Main {\n    public static void main(String[] args) {\n        int day = 3;\n        switch (day) {\n            case 1:\n                System.out.println(\"mon\");\n            case 2:\n                System.out.println(\"tue\");\n            case 3:\n                System.out.println(\"wed\");\n            default:\n                System.out.println(\"other\");\n        }\n    }\n}",
  "cases": [
    {
      "name": "正确输出",
      "expected": "wed\n"
    }
  ],
  "explain": "老式 `case` 是\"入口标签\"不是\"独立分支\"：匹配到 3 之后会继续往下执行 default，所以打印两行。可以补 `break`，也可以直接改成箭头语法 `case 3 -> System.out.println(\"wed\");`——箭头分支天然不穿透。"
}
```

**提示**：两种改法都行，推荐箭头。

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
      "explain": "箭头分支只执行自己，不会落到下一个分支，所以不需要 break。"
    },
    {
      "question": "switch 表达式里，多语句分支怎么交回值？",
      "options": ["return", "yield", "break"],
      "answer": 1,
      "explain": "yield 结束当前分支并交出值；return 会直接结束整个方法。"
    },
    {
      "question": "对 String 做 switch 表达式，只写了几个 case，会怎样？",
      "options": ["没匹配到时返回 null", "编译错误，必须有 default", "运行时抛异常"],
      "answer": 1,
      "explain": "String 是开放类型，编译器无法确认覆盖所有情况，所以必须写 default。"
    }
  ]
}
```

## 常见误区

### 误区：箭头语法只是把冒号换成箭头

**你以为**：写法变短了而已。
**其实**：箭头分支不会穿透，还能作为表达式产出值。语义上更接近"多路选择"，不是老式 `switch` 的语法糖。

### 误区：分支里用 `return` 交值

**你以为**：`switch` 表达式里返回值和普通方法一样。
**其实**：`return` 会结束整个方法，只适用于你确实想退出方法。分支交值要用 `yield`。

### 误区：枚举的 switch 永远不用写 `default`

**你以为**：枚举只有几个值，编译器肯定知道。
**其实**：只有**覆盖了全部常量**才不用写。少一个就必须补 `default`，否则编译失败——这正好是它的价值：新增枚举常量时，漏处理的地方会变成编译错误。

## 小结

箭头 `switch` 不穿透、能当表达式；多语句分支用 `yield` 交值；枚举全覆盖可省略 `default`，开放类型必须写。它把"漏一个分支"从运行时问题提前成了编译期问题。

下一课：`instanceof` 模式匹配——少一次强制转换。
