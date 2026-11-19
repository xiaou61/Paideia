---
title: 35 · record
order: 35
chapter: 现代 Java
level: intermediate
prerequisite: 34 · instanceof 模式匹配
category: java-from-zero
minutes: 20
status: published
---

# 35 · record

## 两个 `{1,2}`，`equals` 却是 `false`

下面这段直接跑，`ArrayBox` 只有一个组件 `int[] values`。

<!-- run -->
```java
import java.util.Arrays;

public class Main {
    record ArrayBox(int[] values) {
    }

    public static void main(String[] args) {
        ArrayBox a = new ArrayBox(new int[]{1, 2});
        ArrayBox b = new ArrayBox(new int[]{1, 2});

        System.out.println("a.equals(b)=" + a.equals(b));
        System.out.println("Arrays.equals=" + Arrays.equals(a.values(), b.values()));
        System.out.println("toString starts with ArrayBox[values=[I@: " + a.toString().startsWith("ArrayBox[values=[I@"));
    }
}
```
```text
a.equals(b)=false
Arrays.equals=true
toString starts with ArrayBox[values=[I@: true
```

我原来以为 record 会把数组内容逐项比一遍，实际 `a.equals(b)` 是 `false`；`Arrays.equals` 对同一对数组返回 `true`，说明内容一样，差在 record 生成的 `equals`。

生成的 `equals` 逐组件比较：引用组件走 `Objects.equals`，基本类型走 `==`。数组是引用类型，`Objects.equals` 落到 `Object.equals`，比的是不是同一个数组。`hashCode` 也一样：引用组件走 `Objects.hashCode`，数组的 `hashCode` 是 identity hash。`toString` 用 `String.valueOf`，数组打出来就是 `[I@...`。同一段代码里加一行 `System.out.println(a)`，我这次得到的是 `ArrayBox[values=[I@7852e922]`，再跑一次地址就变。

所以数组组件会破坏 record 的值语义。record 只保证组件本身不能再赋值（引用类型就是引用不变），不保证组件指向的内容不变。这句话是这篇的主线，后面的 List 和 map key 都是它的推论。

## `record Point(int x, int y) {}` 生成了什么

这是一个完整的顶层声明，文件名 `Point.java`；放进 `Main` 里也一样。

```java
record Point(int x, int y) {
}
```

编译器生成：

- 规范构造器 `Point(int x, int y)`；
- 访问器 `x()`、`y()`，不是 `getX()`；
- `equals`、`hashCode`、`toString`；
- 类是隐式 `final`，组件是 `private final` 字段；
- 不能新增实例字段，但可以加实例方法、静态方法、静态字段。

`equals` 逐组件比较，`hashCode` 逐组件取值，`toString` 的格式是 `Point[x=1, y=2]`。这三件事对「组件引用指向什么」不做深挖：组件是 `String`、`Integer` 这类不可变对象时没问题；组件是数组、集合、可变对象时，值语义只到引用这一层。准确说法是**组件引用不可变（浅不可变）**：组件的引用不能换，引用指向的内容仍可能变。想让内容也锁死，得在构造器里做防御性拷贝。

## 数组组件：要么别放，要么补三个方法

最省事的修法是不在 record 里放数组。坐标用两个 `int`，序列用 `List<Integer>`，都比 `int[]` 安全。确实需要数组时，自己覆盖 `equals`、`hashCode`、`toString`，并在紧凑构造器和访问器里做拷贝：

<!-- run -->
```java
import java.util.Arrays;

public class Main {
    record ArrayBox(int[] values) {
        ArrayBox {
            values = values.clone();
        }

        @Override
        public int[] values() {
            return values.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ArrayBox box && Arrays.equals(values, box.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "ArrayBox[values=" + Arrays.toString(values) + "]";
        }
    }

    public static void main(String[] args) {
        int[] raw = {1, 2};
        ArrayBox a = new ArrayBox(raw);
        ArrayBox b = new ArrayBox(new int[]{1, 2});
        raw[0] = 9;

        System.out.println("equals=" + a.equals(b));
        System.out.println("hashEqual=" + (a.hashCode() == b.hashCode()));
        System.out.println(a);

        a.values()[0] = 7;
        System.out.println("afterAccessorWrite=" + a);
    }
}
```
```text
equals=true
hashEqual=true
ArrayBox[values=[1, 2]]
afterAccessorWrite=ArrayBox[values=[1, 2]]
```

构造器里的 `values.clone()` 挡住外部数组后续修改；访问器再 `clone()` 一次，挡住调用方通过 `a.values()[0] = 7` 改内部状态。`equals` 用 `Arrays.equals`，`hashCode` 用 `Arrays.hashCode`，`toString` 用 `Arrays.toString`。少任何一个，值语义都漏一块。

如果只覆盖 `equals`/`hashCode` 而不 clone，两个对象能相等，但外部还能改内容，`hashCode` 又会变，等于把 map key 的坑留在原地。

## `List.copyOf` 只复制列表结构

把数组换成 `List` 也不自动安全。看两个 record：`BareBox` 直接存传入的列表，`CopiedBox` 在紧凑构造器里做 `List.copyOf`。

<!-- run -->
```java
import java.util.ArrayList;
import java.util.List;

public class Main {
    record BareBox(List<String> items) {
    }

    record CopiedBox(List<StringBuilder> items) {
        CopiedBox {
            items = List.copyOf(items);
        }
    }

    public static void main(String[] args) {
        List<String> mutable = new ArrayList<>(List.of("a"));
        BareBox bare = new BareBox(mutable);
        bare.items().add("b");
        System.out.println("bare=" + bare.items());

        List<StringBuilder> builders = new ArrayList<>();
        StringBuilder word = new StringBuilder("x");
        builders.add(word);
        CopiedBox copied = new CopiedBox(builders);
        word.append("y");
        System.out.println("copied=" + copied.items().get(0));

        try {
            copied.items().add(new StringBuilder("z"));
        } catch (UnsupportedOperationException error) {
            System.out.println("add: " + error.getClass().getSimpleName());
        }

        try {
            List.copyOf(null);
        } catch (NullPointerException error) {
            System.out.println("copyOf(null): " + error.getClass().getSimpleName());
        }

        List<String> withNull = new ArrayList<>();
        withNull.add("a");
        withNull.add(null);
        try {
            List.copyOf(withNull);
        } catch (NullPointerException error) {
            System.out.println("copyOf(with null): " + error.getClass().getSimpleName());
        }
    }
}
```
```text
bare=[a, b]
copied=xy
add: UnsupportedOperationException
copyOf(null): NullPointerException
copyOf(with null): NullPointerException
```

逐行看：

- `bare=[a, b]`：`BareBox` 存的是原列表引用，`items()` 返回同一个列表，`add` 直接改到它。
- `copied=xy`：`List.copyOf` 复制的是「列表里有哪几个元素」，元素还是同一个 `StringBuilder`；`word.append("y")` 之后，`copied.items().get(0)` 也变成 `xy`。
- `add: UnsupportedOperationException`：`List.copyOf` 返回不可改列表，`add` 被拒绝。所以「record 里放了 List 就真的不可变吗」的准确答案是：引用不可变，内容不一定——引用不可换、列表结构不能改，但元素内容可能变。
- `copyOf(null)` 和含 null 元素的列表都抛 `NullPointerException`：`List.copyOf` 不接受 null。`BareBox` 也一样：传 `List.of("a")` 进去，`items().add("b")` 抛 `UnsupportedOperationException`；传 `new ArrayList<>(List.of("a"))` 才能改。record 本身不决定内容能不能改，传入的列表类型也决定了一半。

要更深一层，得自己复制元素：`items = items.stream().map(StringBuilder::new).toList();`，或者干脆用不可变元素类型（`String`、`Integer`、`LocalDate`）。`List.copyOf` 是浅拷贝，不是深拷贝。

## 当 map 的 key：组件一改，桶就找不到

record 适合当 map 的 key，但前提是组件稳定。`Order` 里放了一个可变 `List`，构造完再改列表，`hashCode` 就变了：

<!-- run -->
```java
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {
    record Order(String id, List<String> items) {
    }

    public static void main(String[] args) {
        List<String> items = new ArrayList<>(List.of("a"));
        Order order = new Order("o1", items);
        Set<Order> orders = new HashSet<>();
        orders.add(order);

        System.out.println("before=" + orders.contains(order) + ", size=" + orders.size());
        items.add("b");
        System.out.println("after=" + orders.contains(order) + ", size=" + orders.size());
        System.out.println("equalsSelf=" + order.equals(order));

        orders.add(order);
        System.out.println("reAdd=" + orders.size());
    }
}
```
```text
before=true, size=1
after=false, size=1
equalsSelf=true
reAdd=2
```

`HashSet` 先用 `hashCode` 找桶，再用 `equals` 确认。`items.add("b")` 之后，`order` 的 `hashCode` 变了，`contains` 按新哈希去找，找不到旧桶里的那个对象；但 `order.equals(order)` 仍是 `true`。同一个对象在集合里，`contains` 却返回 `false`。再 `add` 一次，这次它进了另一个桶，`size` 变成 2——一个 `Set` 里出现两个「相等」的元素。

record 只是把 `hashCode` 生成出来了，不会让组件变稳定。当 key 的 record，组件要么本身不可变（`String`、`int`、`LocalDate`），要么在构造时就拷贝成不可改的，并且元素也不可变；之后不再改。数组组件更不适合当 key：两个内容相同的数组本来就是两个不同的 key。

## 紧凑构造器：校验和改参数都在赋值前

`record Range(int low, int high)` 要求 `low <= high`。校验放进紧凑构造器：

<!-- run -->
```java
public class Main {
    record Range(int low, int high) {
        Range {
            if (low > high) {
                throw new IllegalArgumentException("low > high: " + low + " > " + high);
            }
        }

        int length() {
            return high - low;
        }
    }

    record Label(String text) {
        Label {
            text = text.strip();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("blank label");
            }
        }
    }

    public static void main(String[] args) {
        Range range = new Range(1, 5);
        System.out.println(range);
        System.out.println("length=" + range.length());

        try {
            new Range(5, 1);
        } catch (IllegalArgumentException error) {
            System.out.println("range: " + error.getMessage());
        }

        System.out.println("label=[" + new Label("  hi  ").text() + "]");
        try {
            new Label("   ");
        } catch (IllegalArgumentException error) {
            System.out.println("label: " + error.getMessage());
        }
    }
}
```
```text
Range[low=1, high=5]
length=4
range: low > high: 5 > 1
label=[hi]
label: blank label
```

紧凑构造器不写参数列表，`Range { ... }` 里的 `low`、`high` 就是组件参数。它在字段赋值之前执行：可以校验、可以抛异常，也可以给参数重新赋值；重新赋值后的参数才会写进字段。`Label` 里的 `text = text.strip()` 就是改参数。字段赋值由编译器在末尾自动完成，不要写 `this.low = low`——那是给 final 字段再赋一次值，编译报 `cannot assign a value to final variable low`。

如果不想用紧凑写法，也可以写完整规范构造器 `Range(int low, int high) { this.low = low; this.high = high; }`，但校验和拷贝都要自己重复一遍。

## static 字段：不用 final，也不参与比较

record 的实例字段必须来自组件，但 static 字段不受这个限制。`StaticBox` 加一个 `static int count`，不写 `final` 也能编译：

<!-- run -->
```java
public class Main {
    record StaticBox(int x) {
        static int count;
    }

    public static void main(String[] args) {
        StaticBox a = new StaticBox(1);
        StaticBox b = new StaticBox(1);
        StaticBox.count = 7;

        System.out.println("equals=" + a.equals(b));
        System.out.println("hash=" + (a.hashCode() == b.hashCode()));
        System.out.println("count=" + StaticBox.count);
        System.out.println(a);
    }
}
```
```text
equals=true
hash=true
count=7
StaticBox[x=1]
```

`count` 不是组件，所以不需要 `final`，也不进 `equals`/`hashCode`/`toString`。`a`、`b` 的 `x` 都是 1，它们相等；`count` 改成 7 不影响这个判断。反过来说，static 字段是所有实例共享的可变状态，别把它当成 record 的值的一部分；需要「每个对象一份」的数据，只能放进组件。

record 仍然不能加实例字段。写 `int extra;` 会编译报 `field declaration must be static`，编译器还会提示把它改成组件。

## 不能继承类，但能实现接口

record 是隐式 final 的。反射能看到它的直接父类是 `java.lang.Record`，不是 `Object`：

<!-- run -->
```java
import java.lang.reflect.Modifier;

public class Main {
    record Point(int x, int y) implements Comparable<Point> {
        @Override
        public int compareTo(Point other) {
            return Integer.compare(x, other.x);
        }
    }

    public static void main(String[] args) {
        Point a = new Point(2, 0);
        Point b = new Point(1, 0);

        System.out.println("a.compareTo(b)=" + a.compareTo(b));
        System.out.println("final=" + Modifier.isFinal(Point.class.getModifiers()));
        System.out.println("superclass=" + Point.class.getSuperclass().getName());
        System.out.println("interfaces=" + Point.class.getInterfaces()[0].getName());
    }
}
```
```text
a.compareTo(b)=1
final=true
superclass=java.lang.Record
interfaces=java.lang.Comparable
```

`Point` 实现了 `Comparable<Point>`，所以能直接排序。record 可以实现任意接口，但只有一条继承线：直接父类固定是 `java.lang.Record`，不能再 `extends` 别的类。

试一下 `record Bad(int x) extends Object {}`，编译器的第一句是：

```text
Main.java:2: error: '{' expected
    record Bad(int x) extends Object {
                     ^
1 error
```

record 的语法里根本没有 `extends` 位置，所以它在这行就停了。

反过来，继承一个 record 也不行：

```text
Main.java:5: error: cannot inherit from final Point
    static class Sub extends Point {
                             ^
```

同一份输出里还有一条 `constructor Point in record Point cannot be applied to given types`，因为子类没调用 `Point(int,int)`；但第一条已经说明了根因：`Point` 是 final。

第 36 课的 `sealed` 会把 record 当封闭家族的成员：`sealed interface Shape permits Circle, Square`，`Circle`、`Square` 是实现了 `Shape` 的 record，`switch` 就能穷尽。这里先知道 record 能实现接口、不能被继承就够了。

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
  "explain": "紧凑构造器的参数会在末尾自动赋给字段，字段是 final，所以手写 `this.from = from` 等于给 final 变量再赋一次值，编译报 `cannot assign a value to final variable from`。要改变写进字段的值，只能给参数重新赋值，比如 `items = List.copyOf(items);`，不能给 `this.items` 赋值。"
}
```

## 检查一下

```quiz
{
  "id": "java-33-quiz",
  "title": "record 三问",
  "questions": [
    {
      "question": "record ArrayBox(int[] values) 的两个实例 a、b，values 内容都是 {1,2} 但数组不是同一个，a.equals(b) 的结果是？",
      "options": ["true，record 会按内容比较数组", "false，数组组件走引用比较", "编译错误，record 不能有数组组件"],
      "answer": 1,
      "explain": "生成的 equals 对每个组件调用 Objects.equals；数组没有重写 equals，比较的是不是同一个数组对象。要按内容比，自己用 Arrays.equals 覆盖。"
    },
    {
      "question": "record Order(List<String> items) 的 items 引用不能换，内容一定能改吗？",
      "options": ["一定能，List 都是可变的", "不一定：传入 List.of 生成的不可变列表时 add 抛 UnsupportedOperationException，传入 ArrayList 时能改；元素本身可变时，改元素也会透过去", "不能，record 会深拷贝 List"],
      "answer": 1,
      "explain": "record 只锁组件引用。List.copyOf 也只复制列表结构，元素对象不复制；要深不可变，得自己复制元素或只用不可变元素类型。"
    },
    {
      "question": "record StaticBox(int x) { static int count; } 里的 count 哪句对？",
      "options": ["必须写成 static final", "可以不加 final，且不参与 equals/hashCode/toString", "会成为一个 record 组件"],
      "answer": 1,
      "explain": "static 字段不是组件，不需要 final，也不进生成的 equals/hashCode/toString；它是所有实例共享的可变状态。"
    }
  ]
}
```

## 还差的

这篇没讲 record 的序列化：实现 `Serializable` 时组件怎么读写、反序列化是否经过紧凑构造器，规则和普通类不一样。也没讲 record 与 Jackson/JPA 的配合——Jackson 2.12 起能直接读写 record，JPA 实体则要求无参构造器和可变字段，record 当不了实体。还有「改一个组件、返回新 record」的写法：record 没有 `with` 方法，得自己写一个 `withX`。这些等用到再补。
