---
title: 34 · instanceof 模式匹配
order: 34
chapter: 现代 Java
level: intermediate
prerequisite: 33 · switch 表达式与箭头语法
category: java-from-zero
minutes: 24
status: published
---

# 34 · instanceof 模式匹配

## 这一课你要会

- 用 `if (x instanceof String s)` 一次完成判断与赋值；
- 说清模式变量在什么范围内可见；
- 用记录模式（record pattern）一次拆开对象的字段；
- 知道 `null` 永远不会匹配 `instanceof`。

## 为什么

以前的写法是先判断、再强转：

```text
if (value instanceof String) {
    String s = (String) value;
    System.out.println(s.length());
}
```

判断一次、强转一次，两处都要写类型。中间插一行代码、或者改类型时漏改一处，就可能留下一个 `ClassCastException`。

模式匹配把这两步合成一步：**判断成功的同时，变量就已经是那个类型**，不需要强转。

## 讲解

### 基本写法

```text
if (value instanceof String text) {
    System.out.println(text.length());   // text 已经是 String
}
```

`text` 叫**模式变量**。它只在"匹配成功"的分支里存在：

- 在 `if` 的大括号内可见；
- 在 `if` 条件为真之后的代码里可见（`if (!(x instanceof String s)) return;` 之后）；
- 在 `&&` 的右边可见：`if (x instanceof String s && s.length() > 3)`。

在条件不成立的分支里引用它，编译直接报错——这比运行时才发现安全得多。

### 记录模式：一次拆开字段

如果类型是 `record`，`instanceof` 还能顺便把字段解构出来：

<!-- run -->
```java
public class Main {
    record Point(int x, int y) {
    }

    public static void main(String[] args) {
        Object[] values = {"hello", 42, new Point(3, 4)};

        for (Object value : values) {
            if (value instanceof String text) {
                System.out.println("string:" + text.length());
            } else if (value instanceof Integer number && number > 40) {
                System.out.println("big int:" + number);
            } else if (value instanceof Point(int x, int y)) {
                System.out.println("point:" + (x + y));
            }
        }
    }
}
```
```text
string:5
big int:42
point:7
```

`instanceof Point(int x, int y)` 的意思是：先确认它是 `Point`，再把 `x`、`y` 两个组件取出来。嵌套的 record 还能继续拆，比如 `Point(Point(int x, int y), int z)`。

```sketch
{
  "title": "匹配成功就直接拿到变量，不用再强转",
  "rows": [
    [
      { "id": "old", "title": "老写法", "sub": "instanceof 再手动强转", "tone": "amber" },
      { "id": "pat", "title": "模式匹配", "sub": "instanceof String s", "tone": "green" },
      { "id": "rec", "title": "记录模式", "sub": "Point(int x, int y) 直接拆", "tone": "blue" }
    ]
  ],
  "arrows": [
    { "from": "old", "to": "pat" },
    { "from": "pat", "to": "rec" }
  ]
}
```

### `instanceof` 与 `null`

```text
Object value = null;
System.out.println(value instanceof String);   // false
```

`instanceof` 对 `null` 永远返回 `false`，**不会抛空指针**。所以"判断类型"这条路上不需要先判空。但也别因此把 `null` 当成"不是这个类型"来处理——业务上 `null` 通常该单独处理。

### 什么时候用，什么时候别用

**适合**：处理"一个父类型下有几种具体实现"的场景，比如解析 JSON 后的 `Object`、事件对象、AST 节点。

**不适合**：如果你能提前用多态（父类定义方法、子类各自实现），优先多态。模式匹配不是用来替代继承的；它适合"类型是外部给的、你改不了"的场合。

## 动手

下面这段代码编译不过。找出有问题的第一行。

```code-challenge
{
  "id": "java-32-pattern-scope",
  "kind": "bug",
  "title": "模式变量出了作用域",
  "language": "java",
  "code": "public class Main {\n    public static void main(String[] args) {\n        Object value = \"abc\";\n        if (value instanceof String text) {\n            System.out.println(text.length());\n        }\n        System.out.println(text.toUpperCase());\n    }\n}",
  "bugLine": 7,
  "explain": "模式变量 `text` 只在\"匹配成功\"的范围内存在：`if` 的大括号里，以及条件为真之后的代码。出了这个 `if` 就不存在了。想在后面用，要么把逻辑放进 `if` 里，要么先判空/判类型再单独声明。编译器在这里拦住你，正是为了避免\"没匹配上却用了它\"。"
}
```

## 检查一下

```quiz
{
  "id": "java-32-quiz",
  "title": "模式匹配三问",
  "questions": [
    {
      "question": "null instanceof String 的结果是？",
      "options": ["true", "false", "抛 NullPointerException"],
      "answer": 1,
      "explain": "instanceof 对 null 一律返回 false，不抛异常。"
    },
    {
      "question": "模式变量 text 在哪个范围内可用？",
      "options": ["整个方法", "匹配成功的分支及其后的确定路径", "任何地方"],
      "answer": 1,
      "explain": "编译器按流程分析作用域：匹配成功的分支内、以及条件成立后必然执行的代码里。"
    },
    {
      "question": "instanceof Point(int x, int y) 做了什么？",
      "options": ["只判断类型", "判断类型并取出两个组件", "创建一个新的 Point"],
      "answer": 1,
      "explain": "这叫记录模式：判断类型的同时解构 record 的组件，x 与 y 直接可用。"
    }
  ]
}
```

## 常见误区

### 误区：模式匹配只是省了一次强转

**你以为**：本质还是 `if + cast`，只是写法短。
**其实**：强转失败是运行时异常；模式匹配的变量只在匹配成功的路径上存在，用错在编译期就被挡住。安全性不一样。

### 误区：匹配失败后模式变量是 `null`

**你以为**：没匹配上时变量还是被声明了，只是值为空。
**其实**：它根本不在那个作用域里。编译器不让你引用，也就不存在"空指针"这一说。

### 误区：有了模式匹配就该到处替代多态

**你以为**：`instanceof` 链比设计接口简单。
**其实**：每加一种类型就要改所有 `instanceof` 链，违反开闭原则。只有在类型来自外部、你无法改它的继承结构时，模式匹配才是合适工具。

## 小结

`instanceof` 模式匹配把"判断 + 强转"合成一步，模式变量只在匹配成功的路径上可见。record 支持记录模式，能一次解构字段。`null` 永远不匹配。能用多态时优先多态。

下一课：`record`——用一行声明一个不可变数据类。
