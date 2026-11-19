---
title: 06 · static 与值传递
order: 6
chapter: 类与对象
level: intermediate
prerequisite: 05 · 类与对象
category: java-from-zero
minutes: 18
status: published
---

# 06 · static 与值传递

## error: non-static variable bonus cannot be referenced from a static context

给我那个记分的小程序加一个「全班统一加分」的工具方法，第一版写成这样：

```java
public class Main {
    int bonus = 5;

    static int withBonus(int score) {
        return score + bonus;
    }

    public static void main(String[] args) {
        System.out.println(withBonus(80));
    }
}
```

```text
$ javac --release 21 Main.java
Main.java:5: error: non-static variable bonus cannot be referenced from a static context
        return score + bonus;
                       ^
1 error
```

我一开始的判断是「`static` 不过就是省掉 `new`」，所以往 `this` 上找问题，来回改了几次编译参数——都没用。原因不在语法糖上：`static` 成员在类加载时就已经存在，它不依附任何一个对象；而 `bonus` 是每个 `Main` 实例各一份的数据。方法体里没有「这是哪个实例」这个信息，编译器不知道该取谁的 5。

所以修的方向只有两条，选哪条取决于这份数据到底有几份：

- 全班共用同一个值 → 它本来就该是 `static` 字段；
- 每个对象各一份 → 把它当参数传进来，或者先有对象、再通过对象读。

第 05 课那个 `Student` 的分数属于后者，所以下面走第二条。

## 跑一遍

<!-- run -->
```java
public class Main {

    static class Student {
        static int created = 0;
        private final String name;
        private int score;

        Student(String name, int score) {
            this.name = name;
            setScore(score);
            created++;
        }

        void setScore(int score) {
            if (score < 0) {
                throw new IllegalArgumentException("分数不能为负：" + score);
            }
            this.score = score;
        }

        int getScore() {
            return score;
        }

        String getName() {
            return name;
        }
    }

    static void addFiveToScore(int score) {
        score = score + 5;
    }

    static void addFive(Student student) {
        student.setScore(student.getScore() + 5);
    }

    static void replace(Student student) {
        student = new Student(student.getName(), 100);
    }

    public static void main(String[] args) {
        Student lin = new Student("小林", 80);

        addFiveToScore(lin.getScore());
        System.out.println("1 int 参数：" + lin.getScore());

        addFive(lin);
        System.out.println("2 改字段：" + lin.getScore());

        replace(lin);
        System.out.println("3 换对象：" + lin.getName() + " " + lin.getScore());

        new Student("小周", 70);
        new Student("小何", 60);
        System.out.println("4 created=" + Student.created);

        int viaInstance = lin.created;
        System.out.println("5 实例读 static：" + viaInstance);

        Student absent = null;
        try {
            System.out.println(absent.getName());
        } catch (NullPointerException error) {
            System.out.println("6 " + error.getMessage());
        }
    }
}
```
```text
1 int 参数：80
2 改字段：85
3 换对象：小林 85
4 created=4
5 实例读 static：4
6 Cannot invoke "Main$Student.getName()" because "<local3>" is null
```

三个加分方法，一个都没生效、一个生效、一个「看起来该生效却没用」：

- 第 1 行：`addFiveToScore(lin.getScore())` 传进去的是 `80` 这个数字的副本。`score = score + 5` 改的是方法自己的局部变量，方法一返回就没了，所以还是 80。
- 第 2 行：`addFive(lin)` 传进去的副本是一个引用，这个副本和 `lin` 指着同一个对象。`student.setScore(...)` 改的是对象里的字段，所以外面看到 85。
- 第 3 行：`replace` 里 `student = new Student(...)` 只让**副本**改指新对象。`lin` 还指着原来那个，分数 85、名字小林，一动不动。

这三行合起来就是「Java 只有值传递」的全部内容。说「Java 是引用传递」错在哪：如果真是引用传递，第 3 行就该改到 `lin`。准确说法是**传引用的值**——值本身是一个引用，但仍然是复制的。

## 计数器报 4，我只叫得出 3 个学生

第 4 行那个 4 值得停一下。我 `new` 出来的学生只有三个：小林、小周、小何。第四个是 `replace` 里 `new` 的那个——它真的被造出来了，构造函数跑了、`created++` 也加了，只是方法返回后除了计数器再没人指着它。

也就是说，第 3 行不是「没执行」，是**执行了、结果留在了方法里**。`created` 报 4，是这个判断唯一能拿到的证据。

`created` 和 `score` 的区别就在这里：

- `static int created` 只有一份，挂在类上，所有实例读写的是同一个格子；
- `private int score` 每个对象一份，`lin.setScore(85)` 只改小林的。

第 5 行 `int viaInstance = lin.created;` 能编译、也能打印 4，但 `-Xlint:static` 会警告：

```text
$ javac --release 21 -Xlint:static Main.java
Main.java:58: warning: [static] static variable should be qualified by type name, Student, instead of by an expression
        int viaInstance = lin.created;
                             ^
1 warning
```

默认编译不出这个警告。所以「用实例访问 static 字段」语法上是通的，读到的还是类那一份——**通不等于对**，它容易让人以为这个字段跟着对象走。

反方向是合法的：实例上下文里读写 `static` 字段、调 `static` 方法都没问题——构造函数里的 `created++` 就是实例上下文改的类字段。这条规则只有一个方向：**static 方法看不见实例，实例方法看得见 static**。

## 没来报到的那个学生

第 6 行是 `absent` 为 `null` 时调方法的结果。这里有两个我原先搞混的点。

**「引用类型的默认值是 `null`」只对字段成立。** 局部变量没有默认值，不赋值就不能用：

```java
public class Main {
    static class Student {
        private final String name;

        Student(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }
    }

    public static void main(String[] args) {
        Student s;
        System.out.println(s.getName());
    }
}
```

```text
$ javac --release 21 Main.java
Main.java:16: error: variable s might not have been initialized
        System.out.println(s.getName());
                           ^
1 error
```

字段不一样：`private Student s;` 这种声明不赋值也合法，默认值才是 `null`（JLS 4.12.5 给字段和数组元素定默认值，局部变量只要求「用之前必须确定已赋值」）。

**NPE 的文案会指出是谁为 null，但名字不一定在。** 从 JDK 14 起（JEP 358，默认开启），空指针消息会补上「因为哪个引用是 null」——第 6 行那句的后半段就是。可它出现的是槽位号 `<local3>` 而不是变量名 `absent`，因为 `javac` 默认不写局部变量表。我用 `javap -v` 看过默认构建的 class 文件：只有 `LineNumberTable`，没有 `LocalVariableTable`。加 `-g:vars` 重编，同一行就变成：

```text
6 Cannot invoke "Main$Student.getName()" because "absent" is null
```

槽位号也不是定值。把 `main` 里那行 `int viaInstance = lin.created;` 删掉重编，同一个异常就变成 `<local2>`——槽位是按 `main` 里局部变量的声明顺序数下来的。

把这条帮助信息关掉（`-XX:-ShowCodeDetailsInExceptionMessages`），`getMessage()` 返回的就是 `null` 这两个字。所以「新 JDK 会告诉你在哪空指针」要加条件：**想定位到变量名，编译时得带局部变量表。**

至于防 NPE，比到处塞 `!= null` 更省事的是在入口处失败：`Objects.requireNonNull(student, "学生不能为 null")`，抛出来的消息就是你写的那句。不带第二个参数时 `getMessage()` 是字符串 `null`，等于什么也没说。

把上面这些症状收成一张对照表：

| 看到的症状 | 先看哪一行 | 结论 |
| --- | --- | --- |
| `non-static variable X cannot be referenced from a static context` | 这行是不是在 static 方法里直接读了实例字段 | 把字段传进来，或者它本来就该是 static |
| 方法里改了参数，调用方没变 | 这行是不是给参数本身赋了值（`x = ...`、`s = new ...`） | 改的是副本，出不去 |
| 方法里改了对象，调用方也变了 | 这行改的是字段、或者调了会改字段的方法 | 同一个对象，正常 |
| `Cannot invoke ... because "<local3>" is null` | 哪个引用从没被赋过值 | 编译没带 `-g:vars`，槽位号代替了变量名 |

## 动手

下面这个 `doubled` 是 static 方法，要读某个学生的分数。三个候选写法里只有一个能编译，选它。

```code-challenge
{
  "id": "java-06-fill",
  "kind": "fill",
  "title": "static 方法里怎么拿到实例数据",
  "language": "java",
  "template": "public class Main {\n    static class Student {\n        private int score;\n\n        Student(int score) {\n            this.score = score;\n        }\n\n        int getScore() {\n            return score;\n        }\n    }\n\n    static int doubled(Student student) {\n        return {{0}} * 2;\n    }\n\n    public static void main(String[] args) {\n        System.out.println(doubled(new Student(21)));\n    }\n}",
  "options": [["student.getScore()", "getScore()", "score"]],
  "answers": [0],
  "explain": "static 方法里没有 this：只写 score 是直接读实例字段，只写 getScore() 也没有对象可调用。必须经由传进来的 student 走一步。"
}
```

## 检查一下

```quiz
{
  "id": "java-06-quiz",
  "title": "static 与传参三问",
  "questions": [
    {
      "question": "static 方法里为什么不能直接读实例字段 bonus？",
      "options": [
        "因为 static 方法里没有 this，编译器不知道要取哪个对象的 bonus",
        "因为 bonus 没有标 final",
        "因为 bonus 是 private 的，跨方法读不到"
      ],
      "answer": 0,
      "explain": "private 只限制从哪个类能访问，与 static 无关；而 static 方法不依附实例，方法体里没有「当前对象」可指。"
    },
    {
      "question": "方法里执行 student = new Student(...) 之后，调用方那个变量会变吗？",
      "options": [
        "会变，因为对象是按引用传的",
        "不会变，参数拿到的是引用的副本，重新赋值只改副本",
        "取决于新对象占多大内存"
      ],
      "answer": 1,
      "explain": "Java 只有值传递。改字段能传出去，让参数改指新对象传不出去。"
    },
    {
      "question": "static int created 这个字段存在几份？",
      "options": [
        "每个 new 出来的对象各一份",
        "每个线程各一份",
        "整个类一份，所有实例共用"
      ],
      "answer": 2,
      "explain": "static 字段挂在类上，类加载时初始化一次；所以 replace 里那个没人再引用的对象也照样把它加到了 4。"
    }
  ]
}
```

## 还差的

- **并发没验证。** `created++` 是读-改-写三步，我这里只有单线程的实测（就是那个 4）。两个线程同时 `new` 会不会少算，我没测，不敢替它下结论；并发本身在第 22 课。
- **`-Werror` 没试。** 第 5 行只产生 warning，我没验证加 `-Werror` 后是否直接编译失败，所以不敢写「公司构建里照样能过」。
- **文案只在一台机器上核过。** JDK 25.0.4 加 `--release 21` 编译；课程沙箱基线是 Java 21，`<local3>` 里的槽位号取决于 `main` 里局部变量的声明顺序——这篇里我为了 `-Xlint` 演示加了一个 `int viaInstance`，槽位就从 2 挪到了 3。数组元素为 null、字段为 null 时的文案我没实测。
- **`requireNonNull` 不带消息时的 `getMessage()`** 返回字符串 `null`，我只测到这一个版本的行为，没去查它算规范还是实现细节。
