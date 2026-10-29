---
title: 06 · static 与值传递
order: 6
chapter: 类与对象
level: intermediate
prerequisite: 05 · 类与对象
category: java-from-zero
minutes: 26
status: published
---

# 06 · static 与值传递

## 这一课你要会

- 分清 `static` 成员与实例成员，知道为什么 `static` 方法里没有 `this`；
- 说清"Java 只有值传递"，以及为什么方法里改对象会影响外面；
- 用 `null` 之前先判断，不写靠运气的代码。

## 为什么

这两条是 Java 面试最常问、也最容易被含糊带过的：`static` 和"是不是引用传递"。

含糊的代价是实打实的：以为方法是引用传递，就会写出"我明明在方法里换了对象，外面却没变"的困惑；以为 `static` 只是省事，就会在静态方法里访问实例字段然后编译失败。

## 讲解

### `static` 属于类，不属于对象

```text
class Counter {
    static int created = 0;   // 所有实例共享
    int value = 0;            // 每个实例一份

    Counter() {
        created++;
    }

    static int createdCount() {
        return created;
    }

    int getValue() {
        return value;
    }
}
```

两条规则：

- **`static` 方法里不能直接用实例字段**，也没有 `this`——因为它不知道操作的是哪个对象；
- **实例方法可以直接访问 `static` 成员**，反过来不行。

`static` 适合工具函数（`Math.max`）和常量（`static final`）。**不该用来存"全局状态"**：多线程下会变成共享可变数据，第 22 课会看到代价。

### Java 只有值传递

参数传递时，传的是**值的副本**：

- 传基本类型，复制的是那个数字。方法里改，外面不变；
- 传对象，复制的是**引用的副本**。方法里改对象的**字段**，外面看得到（同一个对象）；方法里让参数指向新对象，外面**看不到**（只改了副本）。

```text
static void changeNumber(int x) { x = 99; }
static void changeField(Student s) { s.score = 99; }
static void reassign(Student s) { s = new Student("新", 0); }
```

三次调用后：`changeNumber` 无影响；`changeField` 改到了原对象；`reassign` 无影响。

**这就是"引用传递"这个说法的错处**：它让人以为 reassign 会生效。准确说法是"传引用的值"。

### `null`

引用类型默认值是 `null`。访问 `null` 的字段或方法抛 `NullPointerException`。

三种常见防护：

- 判空：`if (value != null)`；
- 常量放前面：`"abc".equals(value)`；
- 用 `Optional` 表达"可能没有"（第 18 课细讲，别到处套）。

**不要为了"防 NPE"到处塞 null 检查**：先想清楚"这里允许为 null 吗"。允许就在类型和文档上标明。

### 跑一遍

<!-- run -->
```java
public class Main {

    static class Student {
        String name;
        int score;

        Student(String name, int score) {
            this.name = name;
            this.score = score;
        }
    }

    static int counter = 0;

    static void changeNumber(int x) {
        x = 99;
    }

    static void changeField(Student s) {
        s.score = 99;
    }

    static void reassign(Student s) {
        s = new Student("新学生", 0);
    }

    public static void main(String[] args) {
        int number = 1;
        changeNumber(number);
        System.out.println(number);

        Student student = new Student("小林", 80);
        changeField(student);
        System.out.println(student.score);

        reassign(student);
        System.out.println(student.name + " " + student.score);

        counter++;
        counter++;
        System.out.println(counter);

        Student empty = null;
        System.out.println("abc".equals(empty));
        try {
            System.out.println(empty.name);
        } catch (NullPointerException error) {
            System.out.println("空引用不能取字段");
        }
    }
}
```
```text
1
99
小林 99
2
false
空引用不能取字段
```

`reassign` 那一段最关键：方法里确实造了新对象，但外面打印的还是原来的——参数只是引用的副本。

## 动手

把这段代码补完整，使 `static` 方法能访问实例字段（提示：先创建对象）。

```code-challenge
{
  "id": "java-06-fill",
  "kind": "fill",
  "title": "修正 static 里的访问",
  "language": "java",
  "template": "public class Main {\n    static class Counter {\n        int value = 3;\n    }\n\n    static int read() {\n        Counter counter = new Counter();\n        return {{0}};\n    }\n\n    public static void main(String[] args) {\n        System.out.println(read());\n    }\n}",
  "options": [["counter.value", "value", "this.value"]],
  "answers": [0],
  "explain": "static 方法里没有 this，也不能直接访问实例字段；必须先有对象再通过它取字段。"
}
```

## 检查一下

```quiz
{
  "id": "java-06-quiz",
  "title": "static 与传参三问",
  "questions": [
    {
      "question": "static 方法里为什么不能用 this？",
      "options": [
        "因为语法不允许写",
        "因为 static 属于类，没有「当前对象」这个东西",
        "因为 this 只能用在构造器里"
      ],
      "answer": 1,
      "explain": "static 成员在类加载时就存在，不依附任何实例，所以没有 this 可指。"
    },
    {
      "question": "方法里 s = new Student(...) 之后，调用方的变量会变吗？",
      "options": [
        "会，因为传的是引用",
        "不会，方法拿到的是引用的副本，重新赋值只改副本",
        "取决于对象大小"
      ],
      "answer": 1,
      "explain": "Java 只有值传递；对象传的是引用的值。改字段能传出去，换对象不能。"
    },
    {
      "question": "static 适合用来做什么？",
      "options": [
        "存全局可变状态",
        "工具方法与常量",
        "每个对象独有的数据"
      ],
      "answer": 1,
      "explain": "静态可变状态在多线程下是共享数据，容易出事；它适合无状态的工具与常量。"
    }
  ]
}
```

## 常见误区

### 误区：Java 是引用传递

**你以为**：方法里把参数换成新对象，外面会跟着变。
**其实**：传的是引用的副本。改字段能传出去，重新赋值不能。
**怎么验证**：写一个 reassign 方法，调用后打印外面那个变量。

### 误区：`static` 方法里可以直接用实例字段

**你以为**：同一个类里的东西都能随便用。
**其实**：静态方法没有 `this`，必须先有对象。编译期就报 `non-static variable cannot be referenced from a static context`。

### 误区：多判几次 `!= null` 就更安全

**你以为**：到处判空能防住所有空指针。
**其实**：判空只是把错误挪到别处。真正要做的是想清楚"这里能不能为空"，并在类型或方法名上表达出来。

## 小结

`static` 属于类，没有 `this`。Java 只有值传递：改对象字段能传出去，换对象不能。`null` 要先想清楚再判。

下一课：继承、多态与接口——三种"复用"各自解决什么问题。
