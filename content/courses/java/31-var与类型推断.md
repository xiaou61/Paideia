---
title: 31 · var 与类型推断
order: 31
chapter: 现代 Java
level: beginner
prerequisite: 30 · 日志：程序出事了怎么查
category: java-from-zero
minutes: 22
status: published
---

# 29 · var 与类型推断

## 这一课你要会

- 用 `var` 声明局部变量，知道它仍然是**静态类型**；
- 说清 `var` 能用在哪儿、不能用在哪儿；
- 判断什么时候用 `var` 更清楚、什么时候反而更难读。

## 为什么

Java 以前的写法很啰嗦：

```text
Map<String, List<Integer>> groups = new HashMap<String, List<Integer>>();
```

右边已经把类型写全了，左边还要再抄一遍。`var` 让编译器从右边推断，少写一次重复信息。但它**不是** JavaScript 的 `let`——类型在编译期就定死了，写错一样编译不过。

## 讲解

### `var` 是"让编译器替你写类型"

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

`name` 的类型就是 `String`，不是"什么都能装"。下面这行**编译不过**：

```text
var name = "小林";
name = 42;          // 错误：int 不能赋给 String
```

类型在声明那一刻就固定了，和写 `String name = "小林"` 完全等价。`var` 只是省了左边的重复，不改变 Java 是静态类型语言这件事。

### 能用的地方很窄

| 位置 | 能用 `var` 吗 |
| --- | --- |
| 方法里的局部变量 | 能 |
| `for` / `for-each` 的循环变量 | 能 |
| `try-with-resources` 的资源变量 | 能 |
| 方法参数 | 不能 |
| 成员变量（字段） | 不能 |
| 方法返回值 | 不能 |

原因：局部变量的类型在声明处一眼可见；字段和参数是"对外契约"，必须写清楚，否则调用者得读实现才知道类型。

### 两个硬性要求

1. **必须初始化**：`var x;` 编译不过——没有右边，推断不出类型；
2. **右边不能是 `null`**：`var x = null;` 也编译不过——`null` 没有类型。

### 什么时候用，什么时候别用

**适合**：右边类型一目了然，或者类型名字很长。

```text
var users = new ArrayList<User>();          // 右边说了是 ArrayList<User>
var entry = map.entrySet().iterator().next();
var reader = Files.newBufferedReader(path);
```

**不适合**：右边看不出类型，或者用 `var` 会丢掉关键信息。

```text
var result = service.handle(request);       // handle 返回什么？读代码的人得跳过去看
var data = list.get(0);                     // data 是 User 还是 String？
```

判断标准很简单：**读者能不能只看这一行就知道它是什么**。能，就用；不能，就写清楚。

### `var` 与泛型一起用时的小坑

```text
var list = new ArrayList<>();   // 推断成 ArrayList<Object>
list.add("a");
String s = list.get(0);         // 编译错误：Object 不能赋给 String
```

菱形 `<>` 在没有左边目标类型时会退化成 `Object`。想要 `ArrayList<String>`，就写 `new ArrayList<String>()` 或 `List.<String>of()`。

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
      "question": "什么时候不适合用 var？",
      "options": ["右边是 new ArrayList<User>()", "右边是 service.handle(request)，返回类型不明显", "右边是 Files.newBufferedReader(path)"],
      "answer": 1,
      "explain": "读者只看这一行不知道类型时，应该写清楚。var 的目的是减少重复，不是隐藏信息。"
    }
  ]
}
```

## 常见误区

### 误区：`var` 是动态类型

**你以为**：像 JavaScript 一样，变量可以随时换类型。
**其实**：类型在编译期由右边推断并固定，之后赋值类型不符照样编译失败。`var` 只省了左边的字。

### 误区：`var` 可以用在字段和参数上

**你以为**：哪里都能省。
**其实**：只能用于局部变量、循环变量和 try-with-resources。字段、方法参数、返回值都必须显式写类型。

### 误区：`var` 一定让代码更短就更好

**你以为**：能省就省。
**其实**：`var result = service.handle(request);` 让读者必须跳进 `handle` 才知道类型，重构时也更难看出影响。可读性优先于少打几个字。

## 小结

`var` 是局部变量的类型推断，推断出来仍是静态类型。必须初始化、右边不能是 `null`，不能用于字段和参数。判断标准是"只看这一行能不能知道类型"。

下一课：文本块——把 JSON、SQL 原样写进代码。
