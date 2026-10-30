---
title: 35 · record
order: 35
chapter: 现代 Java
level: intermediate
prerequisite: 34 · instanceof 模式匹配
category: java-from-zero
minutes: 28
status: published
---

# 35 · record

## 这一课你要会

- 用 `record` 一行声明一个不可变数据类；
- 知道它自动生成了什么（构造器、访问器、`equals`/`hashCode`/`toString`）；
- 用紧凑构造器做参数校验；
- 知道 record 的"浅不可变"与不能做的事。

## 为什么

写一个"只有几个字段的数据类"，传统写法要几十行：

```text
final class Point {
    private final int x;
    private final int y;

    Point(int x, int y) { this.x = x; this.y = y; }
    int x() { return x; }
    int y() { return y; }
    // equals、hashCode、toString……
}
```

这些代码没有业务含义，却必须写对——尤其 `equals`/`hashCode` 写错，放进 `HashMap` 就会找不到。`record` 把这一整类样板代码交给编译器，你只声明"有哪些字段"。

## 讲解

### 一行声明

```text
record Point(int x, int y) {}
```

编译器自动生成：

- **规范构造器**：`Point(int x, int y)`；
- **访问器**：`x()`、`y()`（注意不是 `getX()`）；
- **`equals` / `hashCode`**：按字段值比较；
- **`toString`**：`Point[x=1, y=2]` 这种格式。

`record` 是 `final` 的：不能被继承。字段也是 `final` 的：创建后不能改。所以它天生适合做"值对象"——坐标、金额、区间、查询条件。

### 紧凑构造器：校验写在入口

<!-- run -->
```java
public class Main {
    record Range(int from, int to) {
        Range {
            if (from > to) {
                throw new IllegalArgumentException("from > to");
            }
        }

        int length() {
            return to - from;
        }
    }

    public static void main(String[] args) {
        Range a = new Range(1, 5);
        Range b = new Range(1, 5);

        System.out.println(a);
        System.out.println(a.equals(b));
        System.out.println(a.from() + "/" + a.to() + "/" + a.length());

        try {
            new Range(5, 1);
        } catch (IllegalArgumentException error) {
            System.out.println("rejected:" + error.getMessage());
        }
    }
}
```
```text
Range[from=1, to=5]
true
1/5/4
rejected:from > to
```

紧凑构造器不写参数列表：参数就是 record 的组件。它会在字段赋值**之前**执行，适合做校验。**不要在紧凑构造器里写 `this.from = from`**——字段赋值由编译器自动完成，手写反而编译不过。

### 可以加方法，不能加实例字段

```text
record Range(int from, int to) {
    int length() { return to - from; }              // 可以：实例方法
    static Range ofOne(int n) { return new Range(n, n); }  // 可以：静态方法
    static final int MAX = 100;                     // 可以：静态字段
    // int extra;                                   // 不可以：额外的实例字段
}
```

record 的字段集合就是它的"形状"，由声明决定。想加派生值（比如 `length()`），用方法算，不要存第二份——两份数据就可能不一致。

### 浅不可变：字段是 final，内容不一定

```text
record Order(String id, List<String> items) {}
```

`items` 这个**引用**不能换，但 `items.add(...)` 仍然能改列表内容。如果需要真正不可变，在构造器里拷一份：

```sketch
{
  "title": "final 锁住的是引用，不是里面的内容",
  "rows": [
    [
      { "id": "field", "title": "record 的字段", "sub": "final，引用换不了", "tone": "green" },
      { "id": "list", "title": "指向一个 List", "sub": "这个 List 本身没锁", "tone": "amber" },
      { "id": "mutate", "title": "items.add(...)", "sub": "内容照样能改", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "field", "to": "list" },
    { "from": "list", "to": "mutate" }
  ]
}
```

```text
record Order(String id, List<String> items) {
    Order {
        items = List.copyOf(items);
    }
}
```

（在紧凑构造器里重新给参数赋值，就会写入拷贝后的值。）这是 record 最容易被忽略的一点。

### record 与普通类的取舍

| 你的类型 | 建议 |
| --- | --- |
| 主要用来装数据、比较相等、当 map 的 key | `record` |
| 有可变状态、需要继承、需要懒加载 | 普通类 |
| 需要"只有几种实现"的封闭集合 | `sealed` + `record`（下一课） |

## 动手

下面这个 record 编译不过。找出有问题的第一行。

```code-challenge
{
  "id": "java-33-record-compact",
  "kind": "bug",
  "title": "紧凑构造器里多写了一行赋值",
  "language": "java",
  "code": "public class Main {\n    record Range(int from, int to) {\n        Range {\n            this.from = from;\n            this.to = to;\n        }\n    }\n\n    public static void main(String[] args) {\n        System.out.println(new Range(1, 5));\n    }\n}",
  "bugLine": 4,
  "explain": "紧凑构造器的参数会在末尾自动赋给字段，字段本身是 final，所以手工 `this.from = from` 是\"给 final 变量再赋一次值\"，编译错误。只有想改写入库值（比如拷贝列表）时才给**参数**重新赋值：`items = List.copyOf(items);`，不是给 `this.items` 赋值。"
}
```

## 检查一下

```quiz
{
  "id": "java-33-quiz",
  "title": "record 三问",
  "questions": [
    {
      "question": "record Point(int x, int y) 自动生成的访问器叫什么？",
      "options": ["getX()", "x()", "getx()"],
      "answer": 1,
      "explain": "record 的访问器与字段同名：x()、y()。它没有 JavaBean 风格的 getX()。"
    },
    {
      "question": "record 里能不能再加一个实例字段？",
      "options": ["能，和普通类一样", "不能，字段集合由声明决定", "能，但必须是 private"],
      "answer": 1,
      "explain": "record 的字段就是组件列表，不能再加实例字段；需要派生值就写成方法。"
    },
    {
      "question": "record Order(List<String> items) 真的不可变吗？",
      "options": ["是，完全不可变", "不是，列表内容仍可改", "取决于 JDK 版本"],
      "answer": 1,
      "explain": "字段引用是 final，但 List 本身可变。需要深不可变就在构造器里 List.copyOf。"
    }
  ]
}
```

## 常见误区

### 误区：record 就是"自动生成 getter 的类"

**你以为**：和 Lombok 的 `@Data` 差不多。
**其实**：record 字段不可变、类是 final、自动生成的是同名访问器不是 `getX()`，而且 `equals`/`hashCode`/`toString` 按组件生成。它表达的是"值"，不是"可变的 JavaBean"。

### 误区：record 就一定完全不可变

**你以为**：都是 final 字段，肯定改不了。
**其实**：如果组件是数组、集合、可变对象，内容仍能改。要真不可变，构造时拷贝。

### 误区：record 可以继承

**你以为**：能像普通类一样 `extends`。
**其实**：record 隐式 final，不能继承别的类，也不能被继承；但可以实现接口。

## 小结

`record` 一行声明不可变数据类，自动生成构造器、同名访问器、`equals`/`hashCode`/`toString`。紧凑构造器用于校验与拷贝，不能手工给 `this` 字段赋值。注意浅不可变。适合值对象、map 的 key、消息与查询条件。

下一课：`sealed`——把"只有这几种"写进类型里。
