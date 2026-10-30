---
title: 23 · JVM 内存与垃圾回收入门
order: 23
chapter: 并发与运行时
level: advanced
prerequisite: 22 · 并发入门：线程、线程池与共享状态
category: java-from-zero
minutes: 30
status: published
---

# 23 · JVM 内存与垃圾回收入门

## 这一课你要会

- 说清 Java 内存的几块区域，以及对象到底分配在哪；
- 认出三种 `OutOfMemoryError` 各自说明什么问题；
- 解释为什么"Java 有 GC"但仍会**内存泄漏**；
- 用 `-Xmx` 与堆转储排查"越跑越慢"。

## 为什么

Java 不用手动 `free`，但"不用管内存"是误解。生产上最常见的事故之一是：**服务跑两天内存涨到上限，然后 OOM 重启**。

这一类问题看代码看不出来——它需要知道"什么对象会被一直引用着"。这一课给的是排查思路，不是 GC 算法细节。

## 讲解

### JVM 内存的几块区域

| 区域 | 放什么 | 是否共享 | 会 OOM 吗 |
| --- | --- | --- | --- |
| **堆（Heap）** | 所有 `new` 出来的对象、数组 | 所有线程共享 | 会（最常见） |
| **虚拟机栈** | 每个方法的局部变量、调用帧 | 每线程私有 | 会（栈溢出） |
| **方法区 / 元空间** | 类的元数据、静态变量、常量池 | 共享 | 会（类太多） |
| **程序计数器** | 当前执行到哪条指令 | 私有 | 不会 |
| **本地方法栈** | native 方法调用 | 私有 | 会 |

```sketch
{
  "title": "JVM 内存分几块，日常只关心堆",
  "rows": [
    [
      { "id": "heap", "title": "堆", "sub": "new 出来的对象，共享", "tone": "green" },
      { "id": "stack", "title": "虚拟机栈", "sub": "局部变量，每线程私有", "tone": "blue" },
      { "id": "meta", "title": "元空间", "sub": "类的元数据，共享", "tone": "violet" },
      { "id": "pc", "title": "程序计数器", "sub": "执行到哪了，私有", "tone": "gray" }
    ]
  ],
  "arrows": []
}
```

日常最关心**堆**：`new` 出来的东西都在这里，GC 也只回收这里。

### 对象一定在堆上吗

不一定。JIT 编译器的**逃逸分析**能发现"这个对象没逃出方法"，于是把它分配在栈上（栈上分配）或干脆拆成几个局部变量（标量替换）。

这是个优化，不影响语义。**你在代码层面的判断标准是：`new` 的对象逻辑上在堆**。

### 引用：什么决定对象能回收

GC 判断一个对象能不能回收，看的是**还有没有可达的引用**（从 GC Roots 出发能走到）。

```java
User user = new User("alice");   // user 是强引用
user = null;                      // 没有引用了，这个对象可以被回收
```

四种引用强度：

| 类型 | 什么时候回收 | 典型用途 |
| --- | --- | --- |
| 强引用（`new`） | 只要还有引用就不回收 | 绝大多数场景 |
| 软引用 `SoftReference` | 内存不足时回收 | 缓存（内存紧张才丢） |
| 弱引用 `WeakReference` | 下次 GC 就回收 | `ThreadLocalMap`、`WeakHashMap` |
| 虚引用 `PhantomReference` | 回收时收到通知 | 资源清理（很少直接用） |

**`ThreadLocal` 会用弱引用**，这也是它必须 `remove()` 的原因——后面会讲。

### 三种 `OutOfMemoryError`，含义完全不同

**一、`java.lang.OutOfMemoryError: Java heap space`**

堆装满了。原因通常两类：

- **真的需要更多内存**：数据量大、缓存大 → 调大 `-Xmx`，或改成流式处理；
- **泄漏**：对象本该被回收却一直被引用 → 找谁在引用。

**二、`java.lang.OutOfMemoryError: Metaspace`**

类元数据区满了。常见原因：**运行时不断生成新类**——大量使用动态代理、CGLIB、脚本引擎反复编译。

**三、`java.lang.OutOfMemoryError: unable to create native thread`**

创建不了新线程。不是内存不够，而是**线程数太多**——每条线程要栈空间，且系统有线程数上限。原因通常是"每次请求 new Thread"或者线程池无界。

还有一种看起来像 OOM 的：

**`java.lang.StackOverflowError`**（不是 OOM）

递归太深或者无限递归。**修它是修递归，不是调内存。**

### 为什么 Java 也会内存泄漏

GC 只回收"没人引用"的对象。如果对象被**不该引用的地方**一直引用着，GC 无能为力。

四个经典泄漏源：

**泄漏一：静态集合只增不减。**

```java
public class Cache {
    // 静态 map 跟着类活一辈子
    private static final Map<String, byte[]> STORE = new HashMap<>();

    public static void put(String key, byte[] value) {
        STORE.put(key, value);      // 只加不删，迟早 OOM
    }
}
```

**修法**：用有上限的缓存（`LinkedHashMap` 覆盖 `removeEldestEntry`），或者定期清理，或者用 `WeakHashMap`。

**泄漏二：`ThreadLocal` 用完不 `remove`。**

`ThreadLocal` 的值存在线程的 `ThreadLocalMap` 里，而线程来自线程池——**线程不会销毁，值就一直留着**。

```java
try {
    context.set(user);
    // 干活
} finally {
    context.remove();      // 必须！否则线程复用时带着上一个请求的数据
}
```

这个泄漏还会导致**串数据**（下一个请求读到上一个请求的用户信息），比 OOM 更危险。

**泄漏三：监听器/回调注册了没注销。**

```java
eventBus.register(listener);   // 注册后一直是引用
// 页面/对象销毁时忘了 unregister
```

**泄漏四：集合里放了对象，对象又引用回集合**（循环引用）。

实际上循环引用本身**不是**问题——GC 用可达性分析，能处理环。真正的泄漏是"有一个不该活的入口一直可达"。

### 排查：先确认，再定位

**第一步：确认是不是真的在泄漏。**

```bash
# 观察堆使用：如果 Full GC 之后仍然持续上升，是泄漏
jcmd <pid> GC.heap_info
```

做几次强制 GC，看堆占用是否回落到正常水平。**GC 后不回落 = 有泄漏**。

**第二步：拿堆转储。**

```bash
jcmd <pid> GC.heap_dump /tmp/heap.hprof
```

或者启动时加参数，OOM 时自动 dump：

```bash
java -Xmx512m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap.hprof -jar app.jar
```

**第三步：用工具看谁占着内存。**

堆转储用 VisualVM、Eclipse MAT 或 `jhat` 打开，看"占内存最多的对象类型"和"谁在引用它们"。

排查的关键问题是：**这条引用链为什么还活着？** 找出来的那个"不该存在的引用"就是答案。

### 常用启动参数

```bash
-Xms512m                          # 初始堆大小
-Xmx2g                            # 最大堆大小（关键参数）
-XX:+HeapDumpOnOutOfMemoryError   # OOM 时自动 dump（强烈建议）
-XX:HeapDumpPath=/tmp/heap.hprof
-XX:+PrintGCDetails               # 打印 GC 日志（老版本）
-Xlog:gc*:file=/tmp/gc.log        # JDK 9+ 的 GC 日志
```

**`-Xms` 与 `-Xmx` 设成相等**是常见做法：避免运行期反复扩缩堆。

### 一个反直觉的事实：不是所有"内存上涨"都是泄漏

JVM 启动后会**逐渐把堆用满再 GC**——这是设计，不是问题。判断泄漏看的是"**GC 后**是否回落"，不是"运行中涨到多少"。

另一个：**堆外内存**不在这套统计里。`ByteBuffer.allocateDirect`、Netty 的池化缓冲、JNI 分配都占本机内存，`-Xmx` 管不到。堆看起来正常但进程被系统 OOM Killer 杀，就要查这些。

### 这一课不讲的

GC 算法（标记-清除、复制、分代、G1/ZGC 的工作方式）、GC 参数调优、JIT 编译优化——这些属于"性能工程"，需要真实压测数据才有讨论价值。本课只到"能看懂现象、能定位方向"。

## 动手

这段代码演示：对象可回收的条件、`WeakHashMap` 与 `HashMap` 的区别、以及用 `Runtime` 观察堆占用。

<!-- run -->
```java
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public class Main {
    static class Key {
        final String name;
        Key(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    public static void main(String[] args) throws InterruptedException {
        // 1) 强引用：外部还有变量引用着 key，条目就留着
        Map<Key, String> strong = new HashMap<>();
        Key strongKey = new Key("strong");
        strong.put(strongKey, "值");
        System.out.println("强引用 map 大小：" + strong.size());

        // 2) 弱引用：key 在外部没有强引用时，GC 后条目会消失
        Map<Key, String> weak = new WeakHashMap<>();
        weak.put(new Key("weak"), "值");       // 这个 key 外面没有变量引用它
        System.gc();
        Thread.sleep(100);
        System.out.println("弱引用 map 大小（GC 后）：" + weak.size());

        // 3) 弱引用 + 外部仍持有 key：条目不会消失
        Map<Key, String> weakKept = new WeakHashMap<>();
        Key kept = new Key("kept");
        weakKept.put(kept, "值");
        System.gc();
        Thread.sleep(100);
        System.out.println("弱引用但外面还引用着：" + weakKept.size());

        // 4) 只增不减的集合：条目数只涨
        Map<Integer, int[]> store = new HashMap<>();
        int lastSize = -1;
        for (int i = 0; i < 3; i++) {
            store.put(i, new int[16]);
            System.out.println("第 " + (i + 1) + " 次 put 后条目数：" + store.size());
            lastSize = store.size();
        }
        System.out.println("最终条目数：" + lastSize + "（真实代码里这种只增不减迟早 OOM）");

        // 5) 堆大小可以读出来，但数值随机器与参数变化，只用于观察
        long maxMb = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        System.out.println("本机最大堆已设置（MB 数随机器变化，不做断言）");
    }
}
```

```text
强引用 map 大小：1
弱引用 map 大小（GC 后）：0
弱引用但外面还引用着：1
第 1 次 put 后条目数：1
第 2 次 put 后条目数：2
第 3 次 put 后条目数：3
最终条目数：3（真实代码里这种只增不减迟早 OOM）
本机最大堆已设置（MB 数随机器变化，不做断言）
```

最重要的一组对比是第 2 行与第 3 行：

- 第 2 行：`new Key("weak")` 外面**没有变量引用**，`WeakHashMap` 只持弱引用，GC 一跑条目就没了 → **0**
- 第 3 行：`kept` 变量还引用着同一个 key，所以即使放在 `WeakHashMap` 里也不回收 → **1**

**这就是弱引用的规则**：只有当 key 在其他地方都没有强引用时才会被回收。所以"用了 `WeakHashMap` 就没有泄漏"是错的——外部一直引用着，它一样不释放。

（堆占用与最大堆的具体数字每次运行都不同，属于环境相关值，所以这里不打印数字，只演示结构。）

### 你的练习

```code-challenge
{
  "id": "java-19-bug",
  "kind": "bug",
  "title": "找出只会增不会减的静态缓存",
  "language": "java",
  "code": "import java.util.HashMap;\nimport java.util.Map;\n\npublic class Main {\n    static final Map<String, byte[]> CACHE = new HashMap<>();\n\n    static byte[] load(String key) {\n        return CACHE.computeIfAbsent(key, k -> new byte[1024 * 1024]);\n    }\n\n    public static void main(String[] args) {\n        for (int i = 0; i < 100000; i++) {\n            load(\"key-\" + i);\n        }\n        System.out.println(CACHE.size());\n    }\n}",
  "bugLine": 8,
  "explain": "CACHE 是静态 Map，computeIfAbsent 只加不删，每个 key 带 1MB 数组，跑一万次就 10GB。修法：用有上限的缓存（LinkedHashMap 覆盖 removeEldestEntry）、WeakHashMap、或定期清理。"
}
```

## 检查一下

```quiz
{
  "id": "java-19-quiz",
  "title": "JVM 内存三问",
  "questions": [
    {
      "question": "java.lang.OutOfMemoryError: unable to create native thread 通常说明什么？",
      "options": [
        "堆内存不够",
        "线程数太多，创建不了新的操作系统线程",
        "磁盘满了"
      ],
      "answer": 1,
      "explain": "每条线程都要栈空间，系统还有线程数上限。常见原因是每请求 new Thread 或线程池无界。"
    },
    {
      "question": "ThreadLocal 用完不 remove 会怎样？",
      "options": [
        "没影响，线程结束就回收了",
        "线程池的线程不销毁，值一直留着，既泄漏又会串数据",
        "编译报错"
      ],
      "answer": 1,
      "explain": "线程池复用线程，ThreadLocalMap 挂在 Thread 上，不 remove 就一直带着，下一个请求可能读到上一个请求的数据。"
    },
    {
      "question": "怎么判断是不是真的内存泄漏？",
      "options": [
        "内存占用高就是泄漏",
        "做几次 Full GC，看堆是否回落；GC 后不回落才是泄漏",
        "看 CPU 占用"
      ],
      "answer": 1,
      "explain": "JVM 会逐渐把堆用满再 GC，运行中占用高是正常的。判断标准是 GC 之后是否回落到正常水平。"
    }
  ]
}
```

## 常见误区

### 误区：Java 有 GC，所以不用管内存

GC 只回收**不可达**的对象。被静态集合、缓存、`ThreadLocal`、监听器一直引用的对象，GC 不会动。**泄漏在 Java 里同样会发生**，而且比 C++ 更难看出来。

### 误区：`StackOverflowError` 是内存不够

不是。它是**栈深度超限**（递归太深），和堆内存无关。调大 `-Xmx` 没有用，要修递归。

### 误区：调大 `-Xmx` 能解决 OOM

能缓解"真的需要更多内存"，但如果是泄漏，调大只是把 OOM 推迟几小时。**先确认是"不够"还是"不释放"。**

### 误区：`-Xmx` 覆盖了所有内存

不。堆外内存（`DirectByteBuffer`、Netty 池化缓冲、JNI）不受 `-Xmx` 约束。进程被系统 OOM Killer 杀但堆看起来正常，就是这类。

### 误区：用了 `WeakHashMap` 就没有泄漏

弱引用只在 key **没有其他强引用**时才回收。如果 key 被别处强引用着，`WeakHashMap` 一样不会释放。

## 小结

- 对象主要在**堆**；GC 只回收堆里不可达的对象。
- 三种 OOM 含义不同：`Java heap space`（堆满/泄漏）、`Metaspace`（类太多）、`unable to create native thread`（线程太多）。
- `StackOverflowError` 是递归问题，不是内存问题。
- Java 也会泄漏：静态集合只增、`ThreadLocal` 不 `remove`、监听器不注销是最常见的三个。
- 判断泄漏的标准是**GC 后是否回落**，不是"占用高不高"。
- 排查靠堆转储（`-XX:+HeapDumpOnOutOfMemoryError`）+ 分析工具，核心问题是"这条引用链为什么还活着"。

到这里，Java 从零开始的主线已经走完：语法 → 面向对象 → 集合与字符串 → 泛型与函数式 → 并发 → 内存。接下来可以按自己的方向继续：Web 开发（Spring Boot）、数据库、或者更深的并发与性能。
