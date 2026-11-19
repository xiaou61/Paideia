---
title: 31 · var 与类型推断
order: 31
chapter: 现代 Java
level: beginner
prerequisite: 30 · 日志：程序出事了怎么查
category: java-from-zero
minutes: 16
status: published
---

# 31 · var 与类型推断

## 一个数组初始化把我卡了十分钟

想用 `var` 简化一段代码：

```java
var arr = {1, 2, 3};
```

javac 25 的原文：

```text
error: cannot infer type for local variable arr
  (array initializer needs an explicit target-type)
```

数组初始化 `{1,2,3}` 本身没有类型——它得靠左边的目标类型才能变成 `int[]`。而 `var` 的意思是"我不知道类型，你自己看右边"，两边互相等，编译器就没法开工。

这件事说明 `var` 不是"省事的语法糖"，它有一个明确的前提：**右边必须自己带着类型信息**。这篇把这个前提、以及三个推断不出来时会给出的真实报错讲清楚。

## var 是"让编译器替你写左边"

<!-- run -->
```java
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        var name = "小林";
        var scores = new ArrayList<Integer>(List.of(90, 80));
        var total = 0;

        for (var score : scores) {
            total += score;
        }

        System.out.println(name.getClass().getSimpleName());
        System.out.println(scores.getClass().getSimpleName());
        System.out.println(total);
    }
}
```
```text
String
ArrayList
170
```

`name` 的类型就是 `String`。下面这行**编译不过**：

```text
var name = "小林";
name = 42;          // error: incompatible types: int cannot be converted to String
```

类型在声明那一刻由右边推断出来并固定，和写 `String name = "小林"` 完全等价。`var` 只省了左边的字，不改变 Java 是静态类型语言这件事。

## 能用的地方很窄

| 位置 | 能用 `var` 吗 |
| --- | --- |
| 方法里的局部变量 | 能 |
| `for` / `for-each` 的循环变量 | 能 |
| `try-with-resources` 的资源变量 | 能 |
| lambda 参数（Java 11+） | 能，但必须全部用 `var` 或全部写类型 |
| `catch` 的参数 | 不能 |
| 方法参数 | 不能 |
| 成员变量（字段） | 不能 |
| 方法返回值 | 不能 |

原因：局部变量的类型在声明处一眼可见，写两遍是浪费；字段和参数是**对外契约**，必须写清楚，否则调用方要读实现才知道类型。

lambda 那条限制有讲究：`(var p, var q) -> p + q` 合法，但 `(var p, int q) -> p + q` 不行：

```text
error: invalid lambda parameter declaration
  (cannot mix 'var' and explicitly-typed parameters)
```

## 推断不出来的三种情况

都是真实报错，原文贴在这里。

**一、右边是 `null`：**

```text
error: cannot infer type for local variable x
  (variable initializer is 'null')
```

`null` 有自己特殊的 null 类型，能赋给任何**引用**类型（`String`、`List`、你自己写的类），但不能赋给 `int`、`double` 这些基本类型——`int x = null;` 一样编译不过。

正因为「能赋给任何引用类型」，它没有唯一答案，`var` 拒绝猜。写 `String x = null;` 或 `List<String> y = null;`。

**二、右边是数组初始化：**

```text
error: cannot infer type for local variable arr
  (array initializer needs an explicit target-type)
```

改成 `var arr = new int[]{1, 2, 3};`——右边带了 `int[]`，就能推断。

**三、右边是 lambda：**

```text
error: cannot infer type for local variable f
  (lambda expression needs an explicit target-type)
```

lambda 没有独立类型，它必须"目标化"成某个函数式接口。写 `var f = (Supplier<Integer>) () -> 1;` 或者直接 `Supplier<Integer> f = () -> 1;`。

顺带一条：`var x;`（不初始化）也编译不过，报的是 `cannot use 'var' on variable without initializer`。这是第四种，不在上面三类里——它不是「推断不出来」，而是**根本没有可推断的表达式**。

四条的共同点就是那句老话：**var 需要右边先有类型**。

## 一个安静得多、更容易漏的坑

`var` 会把三元表达式的**数值提升结果**原样接住：

```java
var x = true ? 1 : 2.5;      // 你想的是 int，得到的是 double
int r = x;                    // error: possible lossy conversion from double to int
```

`1` 与 `2.5` 在同一个三元表达式里，`1` 被提升成 `1.0`，整体类型是**基本类型 `double`**（不是 `Double`）。

所以：`System.out.println(x)` 打印 `1.0`；`x.getClass()` 会报 `error: double cannot be dereferenced`——基本类型没有方法可调。写显式类型 `int x = true ? 1 : 2.5;` 时编译器当场就报错，用了 `var` 它反而放行到下一句才炸——**报错位置离原因更远**。

同类还有菱形退化：

```java
var list = new ArrayList<>();   // 推断成 ArrayList<Object>
list.add("a");
String s = list.get(0);         // error: incompatible types: Object cannot be converted to String
```

菱形需要目标类型，`var` 给不了，于是退化成 `Object`。

注意这里有个前提：**只有无参的裸菱形 `new ArrayList<>()` 才退化成 `Object`**。如果构造器参数本身带了类型，一样能推断：

```java
var list = new ArrayList<>(List.of("a"));   // 推断成 ArrayList<String>
String s = list.get(0);                     // "a"
```

要修上面那个坑，写 `new ArrayList<String>()`。别把 `List.<String>of()` 当等价替代——`List.of()` 返回的是**不可变**列表，`list.add(...)` 会抛 `UnsupportedOperationException`；想可变就 `new ArrayList<>(List.of("a"))`，只读才用 `List.of`。

## 什么时候用，什么时候别用

**适合**：右边已经把类型说清楚了，或者类型名字很长。

```java
var users = new ArrayList<User>();                // 右边写了 User
var entry = map.entrySet().iterator().next();
// 片段：Files.newBufferedReader 会抛 IOException，main 里要先 throws 或 try
var reader = Files.newBufferedReader(path);
```

**不适合**：只看这一行看不出类型。

```java
var result = service.handle(request);             // handle 返回什么？
var data = list.get(0);                           // User 还是 String？
```

判据就一句：**读者只看这一行，能不能知道它是什么类型**。能，用；不能，写出来。

这条判据背后是重构成本：`var result = ...` 在 IDE 里重命名、找引用时会因为推断链而更难判断影响面；写清类型时，改 `handle` 的返回类型编译器立刻在调用点报错。

## 动手

下面这段代码编译不过。找出有问题的第一行。

```code-challenge
{
  "id": "java-29-var-null",
  "kind": "bug",
  "title": "var 推断不出 null 的类型",
  "language": "java",
  "code": "public class Main {\n    public static void main(String[] args) {\n        var count = 0;\n        var label = null;\n        System.out.println(count + \" \" + label);\n    }\n}",
  "bugLine": 4,
  "explain": "`var` 靠右边的表达式推断类型，而 `null` 没有任何类型信息，所以编译不过。写成 `String label = null;` 就行。另一种常见错误是 `var x;`（没初始化），同样推断不出来。"
}
```

## 检查一下

```quiz
{
  "id": "java-29-quiz",
  "title": "var 三问",
  "questions": [
    {
      "question": "var x = 1; 之后执行 x = \"a\"; 会怎样？",
      "options": ["正常运行", "编译错误", "运行时抛异常"],
      "answer": 1,
      "explain": "x 的类型在声明时已推断为 int，后面不能再赋 String。var 不是动态类型。"
    },
    {
      "question": "下面哪个位置不能用 var？",
      "options": ["方法里的局部变量", "类的成员变量", "for-each 的循环变量"],
      "answer": 1,
      "explain": "字段是对外契约，必须显式写类型；局部变量和循环变量可以用 var。"
    },
    {
      "question": "var x = true ? 1 : 2.5; 里 x 是什么类型？",
      "options": ["int", "double", "编译错误"],
      "answer": 1,
      "explain": "三元表达式两边会做数值提升，1 被提升成 1.0，整体是 double。写 int x = ... 会当场报错，用 var 会把错误推到下一句。"
    }
  ]
}
```

## 三件我原来以为对的事

**一、`var` 是动态类型。** 它是编译期推断 + 固定。`var` 变量之后换类型照样编译失败，字节码里也是具体类型（局部变量表里可能带 `Ljava/lang/String;` 这种签名）。

**二、能省就省，代码短就是好。** 短只在"右边自带类型"时才划算。`var result = service.handle(req)` 省了 12 个字符，代价是每个读者都要跳一次源码。

**三、`var` 只是少写字，行为完全一样。** 大部分情况是。但三元表达式的数值提升、菱形退化这两处，`var` 会让错误从"声明那一行"漂到"使用那一行"——排查成本变高。

## 这一课之后

`var` 是局部变量的类型推断：必须初始化、右边不能是 `null`、不能是裸数组初始化或裸 lambda、不能用于字段和参数（lambda 参数是特例）。推断结果仍是静态类型。

判断标准是"只看这一行能不能知道类型"。三元表达式与裸菱形这两个地方，`var` 会把编译错误推到下一句才报——**混合类型的三元表达式、`int` 与 `double` 混算得出你不想要的结果时，写显式类型**。普通加减法（`var total = 0;`）不受影响，那是本课自己的示例。

下一课：文本块——把 JSON、SQL 原样写进代码。
