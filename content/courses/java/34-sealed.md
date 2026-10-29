---
title: 34 · sealed 与封闭类型
order: 34
chapter: 现代 Java
level: advanced
prerequisite: 33 · record
category: java-from-zero
minutes: 28
status: published
---

# 34 · sealed 与封闭类型

## 这一课你要会

- 用 `sealed interface` 声明"只允许这几种实现"；
- 配合 `record` 写出可穷尽匹配的模型；
- 知道 `sealed` 与 `final`、普通接口的区别；
- 知道新增一个实现时，编译器会帮你找出所有漏处理的地方。

## 为什么

想象一个"支付结果"：成功、失败、待确认。用普通接口，谁都可以再写一个实现；你的 `switch` 只能靠 `default` 兜底，而 `default` 会把"新增了一种结果但没人处理"悄悄吞掉。

`sealed` 把实现列表写进类型声明：**只有我允许的这几个类能实现我**。于是编译器知道全部可能，`switch` 不写 `default` 也能通过穷尽性检查；将来有人加了一种，所有没处理的地方立刻编译失败。

## 讲解

### 声明一个封闭家族

```text
sealed interface Shape permits Circle, Square {}

record Circle(double radius) implements Shape {}
record Square(double side) implements Shape {}
```

三条规则：

1. `permits` 列出**直接**实现/继承者；
2. 每个被允许的子类型必须是 `final`、`sealed` 或 `non-sealed` 之一（`record` 隐式 final）；
3. 子类型必须与父类型在同一个模块/包里（同一编译单元也行）。

`sealed` 不等于 `final`：父类型本身还能被使用，只是"谁能继承我"被限制住了。

### 穷尽匹配：新增类型时编译报错

<!-- run -->
```java
public class Main {
    sealed interface Shape permits Circle, Square {
    }

    record Circle(double radius) implements Shape {
    }

    record Square(double side) implements Shape {
    }

    static double area(Shape shape) {
        return switch (shape) {
            case Circle(double radius) -> Math.PI * radius * radius;
            case Square(double side) -> side * side;
        };
    }

    public static void main(String[] args) {
        System.out.println(Math.round(area(new Circle(1))));
        System.out.println(area(new Square(3)));
    }
}
```
```text
3
9.0
```

注意 `switch` **没有 `default`**。这不是省事，而是把"是否覆盖全部情况"交给编译器判断：

- 现在只有 `Circle`、`Square`，全覆盖，编译通过；
- 以后加一个 `Triangle` 并写进 `permits`，这个 `switch` 立刻编译失败，告诉你"漏了 Triangle"。

如果写了 `default`，新增类型会被悄悄归到默认分支，运行时才可能发现行为错了。**这正是 sealed 最大的价值**。

### sealed 的三种子类型

| 子类型修饰 | 含义 |
| --- | --- |
| `final` | 到此为止，不能再有子类 |
| `sealed` | 继续限制下一层 |
| `non-sealed` | 开放给任何人继承 |

`non-sealed` 会把封闭性"重新打开"，一般只在你确实需要扩展时用；用了它就失去了穷尽检查的好处。

### 和枚举怎么选

- 所有情况都是**单例、没有字段** → 枚举；
- 每种情况**带不同数据**（圆有半径、方形有边长）→ `sealed` + `record`；
- 需要将来允许第三方扩展 → 普通接口。

## 动手

下面这段代码编译不过：`switch` 漏了一种形状。找出有问题的第一行。

```code-challenge
{
  "id": "java-34-sealed-exhaustive",
  "kind": "bug",
  "title": "sealed 家族漏了一个分支",
  "language": "java",
  "code": "public class Main {\n    sealed interface Shape permits Circle, Square {\n    }\n\n    record Circle(double radius) implements Shape {\n    }\n\n    record Square(double side) implements Shape {\n    }\n\n    static double area(Shape shape) {\n        return switch (shape) {\n            case Circle(double radius) -> Math.PI * radius * radius;\n        };\n    }\n\n    public static void main(String[] args) {\n        System.out.println(area(new Square(2)));\n    }\n}",
  "bugLine": 12,
  "explain": "`Shape` 是 sealed 且 permits 了 Circle 与 Square，编译器知道全部可能。`switch` 只写了 Circle，没有覆盖 Square，于是直接编译失败，而不是等到运行时。补上 `case Square(double side) -> side * side;` 即可。注意这里**不该**用 `default` 兜底——那样新增形状就不会报错了。"
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
      "explain": "permits 列的是被允许的直接子类型，其他类不能实现这个接口。"
    },
    {
      "question": "sealed 的 switch 为什么可以不写 default？",
      "options": ["编译器自动补一个", "所有实现已知，能检查是否穷尽", "运行时再判断"],
      "answer": 1,
      "explain": "实现集合是封闭的，编译器能确认每个可能类型都有分支，所以不要求 default。"
    },
    {
      "question": "什么时候用 sealed + record 而不是枚举？",
      "options": ["每种情况都带不同的数据", "只有几个固定常量", "需要第三方扩展"],
      "answer": 0,
      "explain": "枚举常量是单例、不能各带不同字段；带不同数据的封闭家族用 sealed + record。"
    }
  ]
}
```

## 常见误区

### 误区：`sealed` 就是 `final`

**你以为**：都不能被继承。
**其实**：`sealed` 允许被**指定**的子类型继承，`final` 是一个都不允许。sealed 是"名单制"，final 是"禁止制"。

### 误区：加了 `default` 更安全

**你以为**：多一个兜底，总不会漏。
**其实**：在 sealed 的 `switch` 里，`default` 会让"新增实现"不再报错，把编译期能发现的问题推到运行期。只有当你确实想统一处理未知类型时才写。

### 误区：sealed 子类型可以随便放

**你以为**：哪个包都能写实现。
**其实**：被允许的子类型必须在同一个模块里（同一包或同一编译单元）。跨模块就必须用 `non-sealed` 或改成普通接口。

## 小结

`sealed` 把"只有这几种实现"写进类型，配合 `record` 表达带数据的封闭家族。`switch` 因此可以做穷尽检查、省略 `default`，新增实现时自动暴露漏处理的位置。它和枚举的分工是：枚举适合纯常量，sealed 适合每种情况带不同数据。

下一课：虚拟线程——让"一个请求一个线程"重新变得可行。
