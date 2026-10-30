---
title: 11 · equals 与 hashCode 必须成对
order: 11
chapter: equals 与集合选型
level: intermediate
prerequisite: 10 · 包装类与自动装箱
category: java-from-zero
minutes: 28
status: published
---

# 11 · equals 与 hashCode 必须成对

## 这一课你要会

- 说清 `equals` 与 `hashCode` 各自的契约，以及为什么两者必须一起重写；
- 写出一份正确的 `equals`（含 `null`、类型检查、字段比较）；
- 让对象能在 `HashSet` / `HashMap` 里被正确找到；
- 认出"只重写 `equals` 忘了 `hashCode`"导致的诡异现象。

## 为什么

这是 Java 里最经典的一条规矩，也是最常被违反的一条。

违反它不会报错，只会让你的对象在 `HashSet` 里"放进去就找不到了"，在 `HashMap` 里"存的取不出来"。这种 bug 极难查——因为代码看起来完全正常。

## 讲解

### 两个方法各管什么

```java
boolean equals(Object other)   // 内容是否相同
int hashCode()                 // 内容对应的整数指纹
```

`equals` 回答"这两个对象算不算同一个东西"；`hashCode` 回答"把它放进哈希表时，该丢到哪个桶"。

### 契约：三条硬规矩

**规矩一：`equals` 相等 → `hashCode` 必须相等。**

反过来不要求：`hashCode` 相同，`equals` 可以不等（这叫"碰撞"，哈希表会再比一次 `equals` 区分）。

**规矩二：`equals` 要满足三个性质。**

| 性质 | 含义 |
| --- | --- |
| 自反 | `x.equals(x)` 为真 |
| 对称 | `x.equals(y)` 与 `y.equals(x)` 结果相同 |
| 传递 | `x.equals(y)` 且 `y.equals(z)` → `x.equals(z)` |

**规矩三：`hashCode` 在一次运行期间必须稳定。**

同一个对象，只要参与比较的字段没变，反复调 `hashCode()` 必须返回同一个值。所以**别用随机数、别用当前时间**参与 `hashCode`。

### 为什么两个必须一起改

`HashMap` / `HashSet` 的查找过程分两步：

1. 用 `hashCode()` 算出桶的位置；
2. 在那个桶里用 `equals()` 逐个比。

如果两个对象 `equals` 相等但 `hashCode` 不同，它们会被放进**不同的桶**——第二步永远不会发生，于是"明明放进去过，却找不到"。

```sketch
{
  "title": "先算 hashCode 找桶，再在桶里用 equals 比",
  "rows": [
    [
      { "id": "key", "title": "放进去的 key", "sub": "一个 Point(1, 2)", "tone": "gray" },
      { "id": "hash", "title": "hashCode()", "sub": "算出桶下标", "tone": "blue" },
      { "id": "bucket", "title": "落到那个桶", "sub": "桶里用 equals 逐个比", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "key", "to": "hash" },
    { "from": "hash", "to": "bucket" }
  ]
}
```

```java
class Point {
    final int x, y;
    Point(int x, int y) { this.x = x; this.y = y; }

    @Override public boolean equals(Object o) {
        return o instanceof Point p && p.x == x && p.y == y;
    }
    // 忘了写 hashCode！
}

Set<Point> set = new HashSet<>();
set.add(new Point(1, 2));
System.out.println(set.contains(new Point(1, 2)));   // false —— 明明 equals 相等
```

这就是那个"放进去找不到"的现象：两个 `Point(1,2)` 的 `equals` 为真，但默认 `hashCode`（对象地址派生）不同，进了不同的桶。

### 正确写法

现代 Java 有两种写法，都可以。

**写法一：`Objects.hash` + `Objects.equals`（兼容旧代码）**

```java
class Point {
    final int x, y;
    Point(int x, int y) { this.x = x; this.y = y; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;                     // 同一个引用，直接真（也省掉后面的拆箱）
        }
        if (!(other instanceof Point point)) {
            return false;                    // null 或类型不对
        }
        return x == point.x && y == point.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }
}
```

`instanceof Point point` 是 Java 16 起的模式匹配：先判断类型，同时把 `other` 转成 `point`。它**同时处理了 `null`**——`null instanceof Point` 为 `false`。

**写法二：`record`（Java 16+，推荐新代码）**

```java
record Point(int x, int y) { }
```

`record` 自动生成 `equals`、`hashCode`、`toString`，规则正是"所有组件都相等则相等"。**手写的那一堆就不需要了。**

什么时候不能用 `record`：需要可变字段、需要继承别的类、需要自定义构造逻辑。那才退回写法一。

### 一个必须知道的陷阱：`HashSet` 里的对象被改了

```java
Set<Point> set = new HashSet<>();
Point p = new Point(1, 2);
set.add(p);
// 如果 Point 是可变的，且这里改了 p 参与 hashCode 的字段
p.x = 99;
System.out.println(set.contains(p));   // false —— 桶变了，找不到了
```

**结论**：放进哈希表的对象，**参与 `hashCode` 的字段不要改**。这也是 `record` 与 `final` 字段被推荐的原因——它们从源头上避免了这个问题。

如果确实需要可变对象做 key，用**不可变的标识**（比如 id）参与 `hashCode`，别用会变的内容。

### `equals` 的常见错误写法

```java
// 错误一：参数类型写成自己（没有 @Override，实际是重载不是覆盖）
public boolean equals(Point other) { ... }
```

`Object` 的 `equals` 参数是 `Object`。写成 `Point` 是**新增了一个重载方法**，`HashMap` 调的还是 `Object` 那个（地址比较）。**永远加 `@Override`**——写成错的签名时编译器会报错，这是最省事的防线。

```java
// 错误二：用 getClass() 而不是 instanceof
if (other == null || getClass() != other.getClass()) return false;
```

这会让子类与父类永不相等。`instanceof` 允许子类相等（更符合直觉，也是 `record` 的做法）。两者都能"正确工作"，但语义不同——**同一个字段结构的父子类，用 `instanceof`；要求严格同类，用 `getClass`**。选一个并保持全项目一致。

```java
// 错误三：equals 里比较了浮点数
return Math.abs(this.value - other.value) < 0.0001;
```

浮点比较要么用 `Double.compare`（精确到 bit），要么明确"容差相等"。而且**容差相等不满足传递性**（a≈b、b≈c 但 a 可能不等于 c），会破坏 `equals` 契约。

### `equals` 与 `compareTo` 要一致

如果你让类实现 `Comparable`，`compareTo` 返回 0 的条件应当与 `equals` 为真一致。否则放进 `TreeSet` / `TreeMap` 会出现"重复元素"或"找不到"。

### 继承带来的对称性问题

```java
class ColorPoint extends Point {
    final String color;
    // 如果 equals 只判 instanceof Point，那 ColorPoint(1,2,"red").equals(Point(1,2)) 为真
    // 但反过来 Point(1,2).equals(ColorPoint(1,2,"red")) 也为真 —— 看起来对称
    // 可一旦双方都只比 instanceof 而字段不同，就会出现"两个不同颜色的点相等"
}
```

**实用建议**：需要自定义相等语义的类，**优先用 `record` 或组合而不是继承**。继承 + 自定义 `equals` 是一对很难同时满足对称与传递的组合。

## 动手

这段代码演示"只写 equals 忘了 hashCode"的后果，以及补上之后的差别。

<!-- run -->
```java
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class Main {
    /** 只重写 equals，故意不写 hashCode。 */
    static class BrokenPoint {
        final int x, y;
        BrokenPoint(int x, int y) { this.x = x; this.y = y; }

        @Override
        public boolean equals(Object other) {
            return other instanceof BrokenPoint point && point.x == x && point.y == y;
        }
    }

    /** 两个都重写。 */
    static class FixedPoint {
        final int x, y;
        FixedPoint(int x, int y) { this.x = x; this.y = y; }

        @Override
        public boolean equals(Object other) {
            return other instanceof FixedPoint point && point.x == x && point.y == y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }

    /** record 自动生成两者。 */
    record RecordPoint(int x, int y) { }

    public static void main(String[] args) {
        Set<BrokenPoint> broken = new HashSet<>();
        broken.add(new BrokenPoint(1, 2));
        System.out.println("Broken equals : " + new BrokenPoint(1, 2).equals(new BrokenPoint(1, 2)));
        System.out.println("Broken contains: " + broken.contains(new BrokenPoint(1, 2)));

        Set<FixedPoint> fixed = new HashSet<>();
        fixed.add(new FixedPoint(1, 2));
        System.out.println("Fixed contains : " + fixed.contains(new FixedPoint(1, 2)));

        Set<RecordPoint> records = new HashSet<>();
        records.add(new RecordPoint(1, 2));
        System.out.println("Record contains: " + records.contains(new RecordPoint(1, 2)));
    }
}
```

```text
Broken equals : true
Broken contains: false
Fixed contains : true
Record contains: true
```

第二行就是那个 bug：`equals` 说相等，`contains` 说找不到。补上 `hashCode`（或改用 `record`）之后两者一致。

### 你的练习

```code-challenge
{
  "id": "java-11-bug",
  "kind": "bug",
  "title": "找出让 HashSet 失效的那处缺失",
  "language": "java",
  "code": "import java.util.HashSet;\nimport java.util.Set;\n\npublic class Main {\n    static class User {\n        final String name;\n        User(String name) { this.name = name; }\n\n        @Override\n        public boolean equals(Object other) {\n            return other instanceof User user && name.equals(user.name);\n        }\n    }\n\n    public static void main(String[] args) {\n        Set<User> users = new HashSet<>();\n        users.add(new User(\"alice\"));\n        System.out.println(users.contains(new User(\"alice\")));\n    }\n}",
  "bugLine": 11,
  "explain": "User 重写了 equals 但没重写 hashCode。默认 hashCode 由对象地址派生，两个 new User(\"alice\") 会进不同的桶，contains 找不到。应当在 equals 之后补 hashCode（或用 Objects.hash(name)，或改成 record）。"
}
```

## 检查一下

```quiz
{
  "id": "java-11-quiz",
  "title": "equals 与 hashCode 三问",
  "questions": [
    {
      "question": "两个对象 equals 相等但 hashCode 不同，放进 HashSet 会怎样？",
      "options": [
        "正常去重",
        "都能放进去，且 contains 找不到应有的那个",
        "抛异常"
      ],
      "answer": 1,
      "explain": "哈希表先按 hashCode 定位桶，桶都不在一处就不会做 equals 比较，于是两个都进得去、也都找不到对方。"
    },
    {
      "question": "hashCode 相同的两个对象，equals 一定相等吗？",
      "options": ["一定", "不一定，这叫哈希碰撞", "取决于 JVM"],
      "answer": 1,
      "explain": "契约只要求 equals 相等时 hashCode 必须相等，反向不成立。碰撞时哈希表会用 equals 再区分一次。"
    },
    {
      "question": "为什么 equals 方法必须写 @Override？",
      "options": [
        "为了性能",
        "不加会把参数写成自己的类型，变成重载而不是覆盖，HashMap 调不到",
        "没有原因，可写可不写"
      ],
      "answer": 1,
      "explain": "写成 equals(Point) 是新增重载，Object.equals(Object) 仍是地址比较。@Override 会在签名不符时直接编译报错。"
    }
  ]
}
```

## 常见误区

### 误区：`equals` 写了就够，`hashCode` 是给哈希表用的可选优化

不是可选。只要对象可能进 `HashSet`、`HashMap`、或者被任何基于哈希的框架使用，`hashCode` 就必须成对重写。而"可能"这个词在实践中等于"几乎一定"。

### 误区：`Objects.hash` 一定比手写好

它更短、更不容易漏字段，但**每次调用都会装箱并创建一个数组**。在超高频率调用（比如几百万次）的 `hashCode` 里，手写的 `31 * x + y` 更快。

日常代码用 `Objects.hash`；性能敏感的路径（哈希表 key 的 `hashCode` 被反复调用）才考虑手写。

### 误区：可变对象也能安全当 `HashSet` 的元素

不能。放进哈希表后，只要参与 `hashCode` 的字段变了，它就在错误的桶里——`contains`、`remove` 全都会失效，而且这一行对象已经"泄漏"在集合里删不掉。

### 误区：`record` 太新，项目里不能用

项目用 JDK 25，`record`（Java 16+）完全可用。它同时解决了两个问题：**自动成对生成 `equals` / `hashCode`**，以及**字段默认 final**（天然不可变，适合做 key）。

## 小结

- `equals` 判内容、`hashCode` 算桶位，**必须成对重写**。
- 契约：`equals` 相等 → `hashCode` 必相等；反向不要求。
- `equals` 要自反、对称、传递；`hashCode` 在一次运行内必须稳定。
- 写 `equals` 时永远加 `@Override`，参数用 `Object` + `instanceof` 模式匹配。
- 新代码优先用 `record`，它把这一整类问题消掉了。

下一课讲**异常**：受检与非受检的区别、什么时候该包一层、以及"吞掉异常"为什么是最坏的做法。
