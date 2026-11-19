---
title: 11 · equals 与 hashCode 必须成对
order: 11
chapter: equals 与集合选型
level: intermediate
prerequisite: 10 · 包装类与自动装箱
category: java-from-zero
minutes: 22
status: published
---

# 11 · equals 与 hashCode 必须成对

## 放进去，却找不到了

这段代码是我第一次见到"哈希"翻车的地方：

```java
class Point {
    final int x, y;
    Point(int x, int y) { this.x = x; this.y = y; }

    @Override
    public boolean equals(Object other) {
        return other instanceof Point point && point.x == x && point.y == y;
    }
}

Set<Point> set = new HashSet<>();
set.add(new Point(1, 2));
System.out.println(new Point(1, 2).equals(new Point(1, 2)));   // true
System.out.println(set.contains(new Point(1, 2)));             // false
```

同一个"点"，`equals` 说相等，集合说没有。代码看起来一点毛病都没有——这正是它难查的原因：没有异常、没有警告，只有一个 `false`。

原因只有一句：**你重写了 `equals`，但没重写 `hashCode`**。

## 查找分两步，你只重写了第二步

`HashSet` / `HashMap` 找东西是这样的：

1. 调 `hashCode()`，算出这个 key 该落在哪个桶；
2. 走到那个桶，再用 `equals()` 逐个比。

两个 `Point(1,2)` 的 `equals` 确实为真，但它们用的是**默认 `hashCode`**（由对象地址派生），两个新对象地址不同 → 桶不同 → 第 2 步根本不会发生。

所以：

- `equals` 回答"算不算同一个东西"，是**内容**问题；
- `hashCode` 回答"该丢到哪个桶"，是**定位**问题。

定位错了，内容比得再准也没机会用上。

## 五条契约

写这两个方法时，规则一共有五条（多数教程只讲前三条，后面两条同样会踩）：

| # | 契约 | 违反的后果 |
| --- | --- | --- |
| 1 | `equals` 相等 → `hashCode` 必须相等 | 放进哈希表后找不到（本课的 bug） |
| 2 | `x.equals(x)` 为真（自反） | 集合里 `contains(自己)` 为 false |
| 3 | 对称：`x.equals(y)` 与 `y.equals(x)` 一致 | 父类子类混用时会分裂成两套相等 |
| 4 | 传递：x=y、y=z 则 x=z | 用浮点容差比较时最容易破坏 |
| 5 | `x.equals(null)` 必须是 `false`（不能抛异常） | 集合遍历时抛 NPE |

另外 `hashCode` 还有一条要求：**同一次运行期间，只要参与比较的字段没变，反复调用必须返回同一个值**。所以别把随机数、当前时间塞进 `hashCode`。

反向不要求：`hashCode` 相同不代表 `equals` 相等——那是碰撞，哈希表会用 `equals` 再区分一次。

## 正确写法

### 手写

```java
class Point {
    final int x, y;
    Point(int x, int y) { this.x = x; this.y = y; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;                    // 同一个引用，直接真
        }
        if (!(other instanceof Point point)) {
            return false;                   // null 或类型不对，都在这一句里挡掉
        }
        return x == point.x && y == point.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }
}
```

`instanceof Point point` 是 Java 16 起的模式匹配：判断类型的同时完成转换。它顺带解决了 `null`——`null instanceof Point` 就是 `false`，不会抛 NPE。

### record（新代码首选）

```java
record Point(int x, int y) { }
```

一行拿到构造器、`equals`、`hashCode`、`toString`，相等规则是"所有组件都相等"。上面那一整段手写全部不需要。

`record` 也有自定义空间，比如要校验参数，用紧凑构造器：

```java
record Range(int low, int high) {
    Range {
        if (low > high) {
            throw new IllegalArgumentException("low 不能大于 high");
        }
    }
}
```

所以"需要校验就退回手写"是不对的——紧凑构造器就是为这个准备的。真正需要手写的情况只有：**字段要可变**，或者**要继承别的类**（`record` 不能 extends）。

## record 不是"完全不可变"

`record` 的字段是 `final`，也没有 setter，所以**引用不可变**。但如果组件本身是可变对象，内容照样能改：

```java
record Tags(List<String> values) { }

Tags tags = new Tags(new ArrayList<>());
tags.values().add("java");      // 编译通过，内容变了
```

`record` 帮你锁住的是"这个字段不会指向另一个 List"，和 05 课讲的 `final` 是同一条规则。真要深不可变，得在紧凑构造器里做防御性拷贝（`values = List.copyOf(values)`），或者干脆只用 `List.of(...)` 这类不可变实现。

## 放进哈希表之后，别改参与 hashCode 的字段

这一条必须单独说，因为它的现象和上面那个一模一样，原因却不同：

```java
class MutablePoint {          // 注意：字段不是 final
    int x, y;
    MutablePoint(int x, int y) { this.x = x; this.y = y; }

    @Override
    public boolean equals(Object other) {
        return other instanceof MutablePoint p && p.x == x && p.y == y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }
}

Set<MutablePoint> set = new HashSet<>();
MutablePoint p = new MutablePoint(1, 2);
set.add(p);              // 按 (1,2) 算出桶 A
p.x = 99;                // 现在 hashCode 变了，桶变成 B
set.contains(p);         // false —— 它在 A 桶里，却在 B 桶找
```

对象已经"泄漏"在集合里了：`contains` 找不到、`remove` 删不掉，集合还一直持有它。做 key 的对象要么不可变，要么只让**不变的标识**（比如 id）参与 `hashCode`。

## 几个容易写错的地方

**参数类型写成自己。**

```java
public boolean equals(Point other) { ... }    // 这是重载，不是覆盖
```

`Object.equals` 的参数是 `Object`。写成 `Point` 等于新增了一个方法，`HashMap` 调的还是 `Object` 那个（地址比较）。**永远加 `@Override`**——签名写错时编译器会直接报错，这是最省事的防线。

**`getClass()` 与 `instanceof` 不是对错之分，是语义之分。**

```java
if (other == null || getClass() != other.getClass()) return false;
```

- 用 `instanceof`：子类与父类可能相等（只要字段比得上就相等）；
- 用 `getClass()`：要求严格同类，父类子类的实例永远不相等。

两者都能正常工作，选哪个取决于你的类型设计。麻烦在于：如果父类用 `instanceof`、子类又加了字段，对称性很容易破——`ColorPoint(1,2,"red")` 和 `Point(1,2)` 可能互相都"相等"，而它们的字段其实不同。**所以规则是：一旦用了继承，两个类必须用同一套判断方式，且子类不要再加参与比较的字段**；做不到就别用继承，改用组合。

**`equals` 里别做容差比较。**

```java
return Math.abs(this.value - other.value) < 0.0001;   // 破坏传递性
```

容差不满足传递性（a≈b、b≈c 但 a 可能不等于 c），契约第 4 条就塌了。要精确比较用 `Double.compare`，要容差比较就别叫 `equals`（另写一个 `isCloseTo`）。

**实现了 `Comparable` 就要和 `equals` 保持一致。** `compareTo` 返回 0 的条件必须与 `equals` 为真一致，否则 `TreeSet` / `TreeMap` 会把"相等"和"排序相同"混为一谈，出现重复元素或找不到。

## 动手

下面这段代码演示了两种写法的差别，以及 `record` 自动生成的效果。

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
    record RecordPoint(int x, int y) {
        // 紧凑构造器：一样可以加校验
        RecordPoint {
            if (x < 0 || y < 0) {
                throw new IllegalArgumentException("坐标不能为负");
            }
        }
    }

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

        try {
            new RecordPoint(-1, 2);
        } catch (IllegalArgumentException error) {
            System.out.println("紧凑构造器拦住：" + error.getMessage());
        }
    }
}
```
```text
Broken equals : true
Broken contains: false
Fixed contains : true
Record contains: true
紧凑构造器拦住：坐标不能为负
```

第二行就是那个 bug：`equals` 说true，`contains` 说 false。补上 `hashCode`（或改用 `record`）之后两者一致。

## 你的练习

```code-challenge
{
  "id": "java-11-bug",
  "kind": "bug",
  "title": "找出让 HashSet 失效的那处缺失",
  "language": "java",
  "code": "import java.util.HashSet;\nimport java.util.Set;\n\npublic class Main {\n    static class User {\n        final String name;\n        User(String name) { this.name = name; }\n\n        @Override\n        public boolean equals(Object other) {\n            return other instanceof User user && name.equals(user.name);\n        }\n    }\n\n    public static void main(String[] args) {\n        Set<User> users = new HashSet<>();\n        users.add(new User(\"alice\"));\n        System.out.println(users.contains(new User(\"alice\")));\n    }\n}",
  "bugLine": 11,
  "explain": "User 重写了 equals，但字段下面没有 hashCode。默认 hashCode 由对象地址派生，两个 new User(\"alice\") 落进不同的桶，contains 永远返回 false。改法二选一：补一个 Objects.hash(name)，或者把整个类改成 record User(String name) —— 后者连 equals 都不用写。"
}
```

## 自测

```quiz
{
  "id": "java-11-quiz",
  "title": "equals 与 hashCode 三问",
  "questions": [
    {
      "question": "两个对象 equals 相等但 hashCode 不同，放进 HashSet 会怎样？",
      "options": [
        "正常去重，只会留一个",
        "两个都会被保存，而且 contains 找不到应有的那个",
        "抛异常"
      ],
      "answer": 1,
      "explain": "哈希表先按 hashCode 定位桶，落在不同桶就不会再做 equals 比较，于是两个都进得去、也都找不到对方。"
    },
    {
      "question": "hashCode 相同的两个对象，equals 一定相等吗？",
      "options": ["一定", "不一定，这叫哈希碰撞", "取决于 JVM 实现"],
      "answer": 1,
      "explain": "契约只要求 equals 相等时 hashCode 必须相等，反向不成立。碰撞时哈希表会用 equals 再区分一次。"
    },
    {
      "question": "record Range(int low, int high) 想校验 low <= high，该怎么办？",
      "options": [
        "写一个紧凑构造器 Range { if (low > high) throw ... }",
        "没法做，record 不能自定义逻辑",
        "必须退回到普通类手写 equals/hashCode"
      ],
      "answer": 0,
      "explain": "紧凑构造器（compact constructor）就是给 record 做校验用的，不用写参数列表、不用自己赋值，只写检查逻辑。"
    }
  ]
}
```

## 带走三句话

- `equals` 判内容、`hashCode` 算桶位，**必须成对重写**；只重写一个的现象是"放进去了、找不到"。
- 手写时永远加 `@Override`，用 `instanceof` 匹配（顺带挡掉 `null`）；新代码优先 `record`。
- `record` 只锁引用不锁内容；做成键的对象，参与 `hashCode` 的字段之后不要再改。

下一课讲异常：受检与非受检的区别、什么时候该包一层，以及"吞掉异常"为什么是最坏的做法。
