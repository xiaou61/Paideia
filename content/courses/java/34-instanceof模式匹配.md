---
title: 34 · instanceof 模式匹配
order: 34
chapter: 现代 Java
level: intermediate
prerequisite: 33 · switch 表达式与箭头语法
category: java-from-zero
minutes: 19
status: published
---

# 34 · instanceof 模式匹配

## 复制来的第二个分支，运行时才炸

`label(Object value)` 里的 `value` 来自我练手项目里手写的配置读取器——`Map<String,Object>` 取出来的值一律是 `Object`，我要按类型给它贴个标签。例子是玩具，不是线上代码。

老写法，判断和强转分两处写：

<!-- run -->
```java
public class Main {
    static String label(Object value) {
        if (value instanceof String) {
            return "text:" + ((String) value).length();
        }
        if (value instanceof Integer) {
            String text = (String) value;
            return "int:" + text.length();
        }
        return "other";
    }

    public static void main(String[] args) {
        System.out.println(label("abc"));
        try {
            System.out.println(label(42));
        } catch (ClassCastException e) {
            System.out.println(e);
        }
    }
}
```
```text
text:3
java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.String (java.lang.Integer and java.lang.String are in module java.base of loader 'bootstrap')
```

第二个分支是从第一个分支复制过来改的：条件改成了 `Integer`，里面的 `(String)` 忘了改。

我当时的反应是「`instanceof` 刚才不是已经判断过类型了吗」。错就错在这里：`instanceof` 判断的类型和 `(String)` 强转的类型，是两处互不核对的信息。`value` 的静态类型是 `Object`，而 `Object` 转 `String` 在编译期完全合法，真正的检查被推迟到执行那一行——所以错误炸在第二个分支上，而且只是运行时异常。

为了让这段能放进可运行示例（退出码必须是 0），我把异常 catch 了打出来。去掉 `try`/`catch`，输出第一行是 `Exception in thread "main"` 加同一句消息，退出码 1。

## 同一个错，换成模式变量就在编译期红

现在把「判断 + 强转」压成一步，故意犯同一个错：

```java
public class Main {
    static String label(Object value) {
        if (value instanceof Integer number) {
            String text = (String) number;
            return "int:" + text.length();
        }
        return "other";
    }
}
```
```text
Main.java:4: error: incompatible types: Integer cannot be converted to String
            String text = (String) number;
                                   ^
1 error
```

值钱的不是「编译不过」，是**为什么到了这里才拦得住**。旧写法里编译器只知道操作数是 `Object`，`Object` 转 `String` 合法，它没有任何依据拒绝；模式变量把类型绑在了 `number` 上，`Integer` 转 `String` 是编译期就能判定不可能的转换，于是同一处手滑变成了红线。

改对之后：

<!-- run -->
```java
public class Main {
    static String label(Object value) {
        if (value instanceof String text) {
            return "text:" + text.length();
        }
        if (value instanceof Integer number) {
            return "int:" + number;
        }
        return "other";
    }

    public static void main(String[] args) {
        System.out.println(label("abc"));
        System.out.println(label(42));
    }
}
```
```text
text:3
int:42
```

代码上唯一的变化是：条件里多写一个名字，强转那半行删掉。`text`、`number` 叫**模式变量**，匹配成功时它已经是那个类型。

## 作用域按流程算，不按大括号算

把同样的写法用到 `||` 右边，编译器立刻翻脸：

```java
public class Main {
    static String label(Object value) {
        if (value instanceof String text || text.isEmpty()) {
            return "text:" + text.length();
        }
        return "other";
    }

    public static void main(String[] args) {
        System.out.println(label("abc"));
    }
}
```
```text
Main.java:3: error: cannot find symbol
        if (value instanceof String text || text.isEmpty()) {
                                            ^
  symbol:   variable text
  location: class Main
Main.java:4: error: cannot find symbol
            return "text:" + text.length();
                             ^
  symbol:   variable text
  location: class Main
2 errors
```

`||` 的右边只有在左边为 `false` 时才求值，而那时候类型根本没匹配上，`text` 不存在。这不是编译器小气：JLS 第 6.3 节把模式变量的作用域定为「匹配成功之后**可能执行到**的那部分程序」，6.3.1 与 6.3.2 再把 `&&`、`||`、`!`、`if`、`while` 这些结构逐个摊开——不是「这段大括号里」。

反过来，只要失败路径必然退出，`if` 之后照样能用：

<!-- run -->
```java
public class Main {
    static String label(Object value) {
        if (!(value instanceof String text)) {
            return "not text";
        }
        return "text:" + text.length();
    }

    public static void main(String[] args) {
        System.out.println(label("abc"));
        System.out.println(label(42));
    }
}
```
```text
text:3
not text
```

同理，`&&` 的右边可见（左边为 `true` 才求值右边，那时已匹配成功），`else` 分支不可见（那正是没匹配上的路径）。

另一处容易踩的是：`boolean b = value instanceof String s;` 在 javac 25.0.4 上编得过去，`s` 却从下一行起就不存在——没有「必然成功」的路径可挂，声明出来也没处用。编译通过不等于拿得到变量。

## null 连 Object 都匹配不上

<!-- run -->
```java
public class Main {
    static String label(Object value) {
        if (value instanceof String text) {
            return "text:" + text.length();
        }
        return "other";
    }

    public static void main(String[] args) {
        Object value = null;
        System.out.println("String: " + (value instanceof String));
        System.out.println("Object: " + (value instanceof Object));
        System.out.println("label(null): " + label(null));
    }
}
```
```text
String: false
Object: false
label(null): other
```

`instanceof` 对 `null` 一律返回 `false`，不抛 `NullPointerException`。别顺手套用成「`null` 是另一种类型」：`label(null)` 会一路走到最后那个 `return "other"`，和「我不认识的类型」共用同一个分支。要区分，就显式先判 `value == null`。

## 记录模式：判断的同时把组件拆出来

如果 `value` 是 record，`instanceof` 还能把组件一起拆出来。`record` 本身是第 35 课的内容，这里只需要看懂 `record Span(int from, int to) { }` 这一行：它声明了一个带两个组件的类型，别的先不用管。

记录模式是 Java 21 才正式支持的，`--release 17` 直接拒绝：

```java
public class Main {
    record Span(int from, int to) {
    }

    static String label(Object value) {
        if (value instanceof Span(int from, int to)) {
            return "span:" + from + "-" + to;
        }
        return "other";
    }
}
```
```text
Main.java:6: error: deconstruction patterns are not supported in -source 17
        if (value instanceof Span(int from, int to)) {
                                 ^
  (use -source 21 or higher to enable deconstruction patterns)
1 error
```

`--release 21` 能过，跑出来是这样：

<!-- run -->
```java
public class Main {
    record Span(int from, int to) {
    }

    static String label(Object value) {
        if (value instanceof Span(int from, int to)) {
            return "span:" + from + "-" + to;
        }
        return "other";
    }

    public static void main(String[] args) {
        System.out.println(label(new Span(3, 7)));
    }
}
```
```text
span:3-7
```

两个版本边界一起记：`instanceof String text` 这种基本模式从 Java 16 起（`--release 15` 报 `pattern matching in instanceof is not supported in -source 15`），记录模式要 21 起。

| `javac` 参数 | `instanceof String text` | `instanceof Span(int from, int to)` |
| --- | --- | --- |
| `--release 16` | 通过 | 报错：`deconstruction patterns are not supported in -source 16` |
| `--release 17` | 通过 | 报错：同样的 `-source 17` 文案 |
| `--release 21` | 通过 | 通过 |
| 不传（本机 25.0.4） | 通过 | 通过 |

记录模式只对 record 成立，`instanceof String(int c)` 会报 `deconstruction patterns can only be applied to records, String is not a record`——普通类只能拿到类型，没有可拆的组件。

## 报错对号入座

| 你看到的那一行 | 先看哪 | 为什么 |
| --- | --- | --- |
| `error: cannot find symbol` + `symbol: variable text` | `else` 分支、`\|\|` 右边、`if` 之后 | 可见性按流程算，只有到此必然匹配成功的路径才有这个变量 |
| `error: incompatible types: Integer cannot be converted to String` | 是不是还留着旧写法的 `(String)` 强转 | 变量已经是 `Integer`，编译器不再有 `Object` 那么宽的转换余地 |
| `error: Object cannot be safely cast to List<String>` | `instanceof` 右边带了泛型参数，而左边静态类型是 `Object` | 参数类型运行时被擦除，没东西可查；换 `List<?>`。左边静态类型本来就是 `List<String>` 时，同样的写法能编过，因为那时不需要运行时检查 |
| `error: incompatible types: String cannot be converted to Integer` | 左边表达式的静态类型和右边类型根本不可能相交（比如 `String` 变量去 `instanceof Integer`） | 编译期就能确定不相交，直接拒，不拖到运行期 |
| `ClassCastException: class java.lang.Integer cannot be cast to class java.lang.String` | 是不是还在用「判类型 + 强转」两处写法 | 两处类型各写一遍，改一处漏一处；换模式变量让编译器来核对 |

## 动手

下面这段编译不过。找出有问题的第一行。

```code-challenge
{
  "id": "java-32-pattern-scope",
  "kind": "bug",
  "title": "模式变量出了作用域",
  "language": "java",
  "code": "public class Main {\n    public static void main(String[] args) {\n        Object value = \"abc\";\n        if (value instanceof String text) {\n            System.out.println(text.length());\n        }\n        System.out.println(text.toUpperCase());\n    }\n}",
  "bugLine": 7,
  "explain": "模式变量 `text` 只在「到此必然匹配成功」的路径上存在：`if` 的大括号里，以及条件为真之后必然执行的代码。`if` 结束就没了，所以第 7 行编译器报 `cannot find symbol`。想在后面用，要么把逻辑挪进 `if`，要么写成 `if (!(value instanceof String text)) return;` 让失败路径先退出。"
}
```

## 检查一下

```quiz
{
  "id": "java-32-quiz",
  "title": "模式匹配三问",
  "questions": [
    {
      "question": "要让模式变量 s 在 if 语句之后仍然可用，条件部分该怎么写？",
      "options": [
        "if (!(value instanceof String s)) return; 之后再写 s",
        "if (value instanceof String s) { ... } 结束后直接写 s",
        "if (value instanceof String s) { ... } else { 写 s }"
      ],
      "answer": 0,
      "explain": "编译器认的是「到这里必然匹配成功」的路径。先 return 掉不匹配的情况，if 之后 s 就必然存在；if 正常结束、或者进了 else，都不满足这个条件。"
    },
    {
      "question": "用 javac --release 17 编译 value instanceof Span(int from, int to) 会怎样？",
      "options": [
        "编译通过，运行时才报错",
        "只有 IDE 报错，javac 照样编",
        "编译报错：deconstruction patterns are not supported in -source 17"
      ],
      "answer": 2,
      "explain": "记录模式要 Java 21 才正式支持，17 上 javac 直接拒（16 也一样）。消息里的 deconstruction patterns 就是编译器对记录模式的叫法。"
    },
    {
      "question": "value 的静态类型是 Object，写 value instanceof List<String> list 会怎样？",
      "options": [
        "编译通过，运行时顺带检查元素类型",
        "编译报错：Object cannot be safely cast to List<String>",
        "编译通过，但只能拿到 Object 类型的元素"
      ],
      "answer": 1,
      "explain": "类型参数在运行时被擦除，没有任何东西可查，所以编译器不允许这个检查，让换 List<?>。反过来，左边静态类型本来就是 List<String> 时同样的写法能编过——那不需要运行时检查。"
    }
  ]
}
```

## 还差的

- 所有报错原文和版本矩阵只在 javac 25.0.4 上取过；`--release 16/17/21` 是同一个编译器模拟的，我没有真的换 JDK 16、17 去跑。课程沙箱基线是 Java 21。
- 报错文案本身是实现的措辞（`deconstruction patterns` 这个叫法，老版本 javac 不一样），会随版本变；被拒绝编译这件事才是语言规定的。表格里那些字符串不要当规范背。
- 没讲 `switch` 里的模式匹配（`case String s ->`）以及 `when` 守卫，那在第 36 课之后；本文所有例子都停在 `if` 链上。
- 记录模式只拆了一层。嵌套的 `Span(Span(int a, int b), int c)`、带泛型参数的 record 能不能拆、类型怎么推，都没验。
- `boolean b = value instanceof String s;` 我只在 25.0.4 上看过「能编过、s 用不了」这一种行为，没写成规范结论。
