---
title: 23 · JVM 内存与垃圾回收入门
order: 23
chapter: 并发与运行时
level: advanced
prerequisite: 22 · 并发入门：线程、线程池与共享状态
category: java-from-zero
minutes: 24
status: published
---

# 23 · JVM 内存与垃圾回收入门

## 四张 OOM 账单

我把「内存不够」拆成四张账单。每条命令只留第一行异常，先看清它到底在说哪块内存。

**一、堆：`-Xmx16m`**

```bash
java -Xmx16m HeapOom
```

```text
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

`HeapOom` 就是一个 `while (true)`，每次往 `List<byte[]>` 里放一个 1MB 数组：

```java
List<byte[]> keep = new ArrayList<>();
while (true) {
    keep.add(new byte[1024 * 1024]);
}
```

**二、元空间：`-XX:MaxMetaspaceSize=16m`**

```bash
java -Xmx64m -XX:MaxMetaspaceSize=16m MetaOom
```

```text
java.lang.OutOfMemoryError: Metaspace
```

`MetaOom` 用 `MethodHandles.Lookup.defineHiddenClass` 反复定义隐藏类，并把 `Class` 引用留在 `List` 里。这台机器用这条命令定义到 7996 个类时失败。关键不是「类太多」这四个字，而是**类元数据**也会占 native 内存，且默认没有上限。

**三、直接内存：`-XX:MaxDirectMemorySize=16m`**

```bash
java -Xmx64m -XX:MaxDirectMemorySize=16m DirectOom
```

```text
Exception in thread "main" java.lang.OutOfMemoryError: Cannot reserve 1048576 bytes of direct buffer memory (allocated: 16777216, limit: 16777216)
```

这是 `ByteBuffer.allocateDirect` 分配 1MB 直接缓冲、直接内存到 16MB 上限时的原文。**我手头的 JDK 21 和 25 都会把 allocated/limit 带出来**；旧资料里的 `Direct buffer memory` 是同一个错误的短写法。不要只 grep 冒号后面那四个字。

**四、线程：`ulimit -u 100`**

```bash
runuser -u nobody -- bash -c 'ulimit -u 100; java -Xmx64m -Xss256k ThreadOom'
```

```text
java.lang.OutOfMemoryError: unable to create native thread: possibly out of memory or process/resource limits reached
```

这条不是在 Windows/JDK 25 上拿到的：本机用 256KB 栈连开 30000 条线程仍然成功，Windows 的进程/线程上限不是固定数字。上面的输出来自 Linux + OpenJDK 17，`ulimit -u 100` 把 root 之外的测试用户限制在 100 个进程/线程，创建 81 条后，下一条（`Thread-81`）启动时 `pthread_create` 返回 `EAGAIN`。JDK 17 的输出是 `unable to create native thread`；有些版本和资料写成 `unable to create new native thread`，指的是同一条路径：**线程栈/native 内存或 OS 线程上限不够，不是堆满**。

四张账单对到参数上：

| 报错原文（detail） | 哪块内存 | 先看/调什么 |
| --- | --- | --- |
| `Java heap space` | 堆里的对象、数组 | `-Xmx`；先确认是「需要更多」还是「不释放」，不要只调大 |
| `Metaspace` / `Compressed class space` | 类元数据、方法字节码、运行时常量池 | `-XX:MaxMetaspaceSize`；查动态生成类的框架 |
| `... direct buffer memory (allocated: ..., limit: ...)` | 堆外的 NIO 直接缓冲 | `-XX:MaxDirectMemorySize`；默认等于 `-Xmx` |
| `unable to create native thread` | 线程栈/native 内存、OS 线程数 | `-Xss`、线程数、`ulimit -u`/cgroup；`-Xmx` 管不到 |

`StackOverflowError` 不在这张表里。它也是 `VirtualMachineError`，但不是 `OutOfMemoryError`：

```text
java.lang.StackOverflowError
is OutOfMemoryError: false
is VirtualMachineError: true
```

这段输出来自 `-Xss256k` 的深递归程序。**深递归得到 SOE；创建线程时 native 内存或 OS 限制不够，才是 native thread OOM。** 调 `-Xmx` 对两者都没有用。

## 内存结构：每格都要能说出「超了报什么」

| 区域 | 放什么 | 共享/私有 | 超了报什么 |
| --- | --- | --- | --- |
| 堆 | `new` 出来的对象、数组、`Class` 对象、静态字段、字符串常量池 | 所有线程共享 | `OutOfMemoryError: Java heap space` |
| 虚拟机栈 | 每个方法的调用帧、局部变量、操作数栈 | 每线程私有 | 深递归 `StackOverflowError`（不是 OOM）；创建线程时 native 不够才是 native thread OOM |
| 方法区 / 元空间 | 类元数据、方法字节码、运行时常量池 | 共享 | `OutOfMemoryError: Metaspace`；开启压缩类指针时也可能先看到 `Compressed class space` |
| 直接内存 | `ByteBuffer.allocateDirect`、Netty 池化缓冲等堆外内存 | 进程内共享 | `OutOfMemoryError`，detail 里含 `direct buffer memory` |
| 程序计数器 | 当前执行到哪条字节码指令 | 每线程私有 | 不会 OOM |
| 本地方法栈 | native 方法调用帧；HotSpot 常与线程栈一起管理 | 每线程私有 | 栈耗尽 `StackOverflowError`；线程创建失败是 native thread OOM |

这张表最容易记错的两格：

- **元空间不放静态字段，也不放字符串常量池。** JDK 8+ 静态字段的存储跟着堆里的 `Class` 对象；`String.intern()` 的字符串表也在堆（JDK 7 起就移过去了）。元空间放的是类元数据、方法字节码、**运行时常量池**。运行时常量池是 class 文件常量池的运行时表示，不是字符串常量池。
- **元空间默认没有实际上限。** 在这台 JDK 25 上跑：

```bash
java -XX:+PrintFlagsFinal -version | grep MaxMetaspaceSize
```

PowerShell 里把 `grep` 换成 `Select-String`，下面同。

```text
size_t MaxMetaspaceSize = 18446744073709551615 {product} {default}
```

`18446744073709551615` 是 64 位无符号最大值，实际等于「不设上限」，只受机器 native 内存限制。要限制它，写 `-XX:MaxMetaspaceSize=256m`，不要以为默认已经封顶。

验证静态字段在堆里：给一个类加 `static byte[] DATA = new byte[20 * 1024 * 1024]`，启动后 `jcmd <pid> GC.class_histogram` 会看到 `[B` 约 20MB；`jcmd <pid> VM.metaspace` 不会把这 20MB 算进类元数据。

直接内存也单独算一笔。`-XX:MaxDirectMemorySize` 不写时，`PrintFlagsFinal` 显示 `0`，但这个 `0` 不是「容量为零」，而是「不单独限制，按堆上限来」：

```text
# -Xmx64m，不写 MaxDirectMemorySize
... direct buffer memory (allocated: 67108864, limit: 67108864)
```

本机 `-Xmx64m` 时，直接内存也在 64MB 处失败。所以一个进程的总 native 占用至少要把堆、元空间、直接内存、线程栈和 JVM 自身一起算，不能只看 `-Xmx`。

## 对象不在栈上，只有「被优化掉」

HotSpot 没有真正的栈上分配。HotSpot 的 C2 会做逃逸分析，但落地方式是：

- **标量替换**：发现对象没逃出方法，就把对象拆成几个局部变量；
- **锁消除**：发现锁对象只被当前线程使用，就把同步去掉；
- 没有被优化掉的对象，仍然在堆上。

所以「对象分配在栈上」这句话是错的。对象要么在堆，要么根本没有以对象形式存在；不会以一个完整对象住在栈帧里。标量替换也不保证发生，不能写依赖它发生的代码。

验证这组优化开关：

```bash
java -XX:+PrintFlagsFinal -version | grep -E 'DoEscapeAnalysis|EliminateAllocations|EliminateLocks'
```

```text
bool DoEscapeAnalysis = true {C2 product} {default}
bool EliminateAllocations = true {C2 product} {default}
bool EliminateLocks = true {C2 product} {default}
```

这三个开关只能证明 C2 有这些优化，不能证明「栈上分配」存在。HotSpot 官方文档在 Stack Allocation 一节明确写的是 **not implemented**。真正要验证某个方法有没有标量替换，得看 JIT 编译日志；但日志依赖方法被 C2 编译，不能拿一次微基准当普遍结论。

## 什么决定对象能回收

GC 判断的是**从 GC Roots 出发还能不能走到**。常见 GC Roots 包括活动线程栈里的引用、静态字段、JNI 引用等。循环引用本身不是问题：A 引用 B、B 引用 A，只要从 Roots 都到不了，两个都能回收。Java 不是靠引用计数。

四种引用强度：

| 类型 | 什么时候回收 | 典型用途 | 注意 |
| --- | --- | --- | --- |
| 强引用 | 只要从 GC Roots 可达就不回收 | 普通对象、集合、静态字段 | 静态集合只增不减就是靠它把对象留住 |
| 软引用 `SoftReference` | 内存压力下由 GC 决定清除 | 可重建的缓存 | 不保证在 OOM 前一定清完，不能当容量上限 |
| 弱引用 `WeakReference` | 下一次 GC 时，如果只剩弱引用就清除 | `WeakHashMap` 的 key、`ThreadLocalMap` 的 key | 外部还有强引用就不会清 |
| 虚引用 `PhantomReference` | 对象变成 phantom reachable 后进入 `ReferenceQueue` | 清理通知、`Cleaner` | `get()` 永远返回 `null`，不能借它访问对象 |

`ThreadLocal` 的 key 是弱引用，但 value 是强引用。线程池的线程不销毁，value 就一直挂着——这既是泄漏，也是串数据。后面单独讲。

## GC：只记三件事

第一，**分代**。新对象先在年轻代（Eden/Survivor）分配，活过几轮进入老年代；年轻代回收叫 Young GC，老年代参与的叫 Mixed/Full GC。具体算法不是这一课的重点。

第二，**G1 是 Java 9 起的服务端默认收集器**（JEP 248）。在这台 JDK 25 上：

```text
[0.048s][info][gc] Using G1
```

也可以查 `UseG1GC = true`。不要在新代码里默认加 `-XX:+UseG1GC`，先看版本和运行环境；更不要拿 JDK 8 的参数表套 JDK 25。

第三，**`-XX:MaxGCPauseMillis` 是目标，不是保证**。本机默认值是 200ms，但把它设成 1ms，实际 Young GC 仍然会超过：

```text
[0.124s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 6M->6M(128M) 2.565ms
[0.126s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 11M->11M(128M) 1.726ms
[0.129s][info][gc] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 16M->16M(128M) 1.762ms
```

目标 1ms，前三轮是 2.565ms、1.726ms、1.762ms。G1 会尽量调整，但对象图、分配速度、堆大小都不听参数指挥。停顿目标只能用来表达偏好，不能写成 SLA。

想把 GC 日志打开：

```bash
java -Xlog:gc*:file=gc.log:time,uptime,level,tags -jar app.jar
```

## Java 也会泄漏：先记住三个入口

### 泄漏一：静态集合只增不减

```java
static final Map<String, Session> SESSIONS = new HashMap<>();

static void remember(String id, Session session) {
    SESSIONS.put(id, session);   // 只加不删
}
```

`SESSIONS` 是静态字段，跟着 `Class` 对象一直可达；它引用的每个 `Session` 和 `Session` 里的 `byte[]` 都不会被回收。数据量一大，`-Xmx` 只是把 OOM 推迟。

修法不是「换个 Map 实现」：要有上限和淘汰策略。`LinkedHashMap` 覆盖 `removeEldestEntry` 可以做 LRU；`WeakHashMap` 只有在 key 没有别处强引用时才有用；`SoftReference` 缓存也不能保证在 OOM 前清完。

### 泄漏二：资源没关

```java
class Session implements AutoCloseable {
    private final InputStream in;

    Session(Path path) throws IOException {
        this.in = Files.newInputStream(path);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}

try (Session session = new Session(path)) {
    session.handle();
}
```

文件、socket、JDBC `Connection`/`Statement`/`ResultSet` 都是这类。对象还被引用时，资源完全不会释放；对象即使最终不可达，也要等 GC/Cleaner 才可能关，时间不由你控制。用 try-with-resources，不要靠 `finalize()`。

### 泄漏三：`ThreadLocal` 用完不 `remove`

```java
static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

try {
    CURRENT.set(session);
    session.handle();
} finally {
    CURRENT.remove();
}
```

`ThreadLocalMap` 的 key 是弱引用，value 是强引用。线程来自线程池时，线程本身不会结束，value 就一直留在 `Thread` 上；下一个请求复用这条线程时，还可能读到上一个请求的 `Session`。**这比单纯 OOM 更危险：它先串数据，再泄漏。**

集合里互相引用不是泄漏，环会被可达性分析处理掉。真正的泄漏总是同一个问题：**有一条不该活的引用链，从 GC Root 一直可达**。

## 排查：`used`、RSS、retained 不是一回事

### 先看 `jcmd GC.heap_info`

```bash
jcmd <pid> GC.heap_info
```

一个真实进程的输出：

```text
112964:
garbage-first heap   total reserved 131072K, committed 131072K, used 84149K [0x00000000f8000000, 0x0000000100000000)
 region size 1024K, 2 young (2048K), 1 survivors (1024K)
```

`used` 是当前存活对象加已分配但未回收的空间；`committed` 是 JVM 向 OS 要来的堆；`reserved` 是预留的地址范围。三个数不是一回事，RSS 又是另一个数。

### 再看直方图，但它只给 shallow size

```bash
jcmd <pid> GC.class_histogram
```

```text
112964:
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:          8958       42392760  [B (java.base@25.0.4)
   2:          1740         230048  java.lang.Class (java.base@25.0.4)
   3:          8838         212112  java.lang.String (java.base@25.0.4)
```

（这里只截了前三行。）

`#bytes` 是 **shallow size**：对象自身的大小，不包含它引用的对象。`42,392,760` 字节的 `[B` 不等于「这些 byte 数组的持有者 retained 了 42MB」；反过来，一个 16 字节的 `Root` 对象也可能 retained 40MB。要看 retained size，必须看引用图，不能只看直方图。

### 然后 dump，在 MAT 里看 retained size

```bash
jcmd <pid> GC.heap_dump C:\Users\Lenovo\AppData\Local\Temp\paideia-jvm-23\jcmd-demo.hprof
```

```text
Dumping heap to C:\Users\Lenovo\AppData\Local\Temp\paideia-jvm-23\jcmd-demo.hprof ...
Heap dump file created [46254568 bytes in 0.061 secs]
```

路径按你的临时目录换。

用 Eclipse MAT 打开 dump，看 **Dominator Tree** 或 Leak Suspects 里的 `Retained Heap`。retained size 的定义是：**这个对象被回收后，能跟着一起回收的对象总大小**。判断泄漏的正确路径是：

1. 在稳定负载下取一次基线，`jcmd GC.heap_info` 记录 `used`/`committed`；
2. 在测试环境用 `jcmd <pid> GC.run` 触发一次完整回收（线上不要随手触发 Full GC），再 dump；
3. 在 MAT 里看谁 retained 最多、引用链从哪个 GC Root 来；
4. 跑同一负载一段时间后再 dump，比较同一组对象的 retained size 是否持续增长。

一次「GC 后 used 没降」不够。比如我故意让 `JcmdDemo.SESSIONS` 持有 40MB 数组，`GC.run` 后 used 从 `84149K` 只降到 `83433K`，直方图里 `[B` 仍有 42MB——这不是泄漏，是程序明确要留着的缓存。**如果这个缓存没有上限，才是泄漏。** 关键是「这个引用该不该活」，不是「数字有没有归零」。

RSS 更不能单独当证据。RSS 包含堆的 committed、元空间、代码缓存、线程栈、直接内存，以及 native 分配器自己的 arena。分配器 free 之后不一定立刻把页还给 OS（例如 jemalloc 的 purging 策略），RSS 就可能不降；这不能反推 Java 对象泄漏。`used` 高也只能说明当前存活对象多，不能说明它们不该活。

### `System.gc()` 只是建议

先看一个 `WeakHashMap` 实验：key 只在 map 里，没有外部强引用。

```java
Map<Key, String> weak = new WeakHashMap<>();
weak.put(new Key("only-weak"), "value");
System.gc();
Thread.sleep(300);
System.out.println("after System.gc: " + weak.size());
```

```text
# 不加参数
after System.gc: 0

# 加 -XX:+DisableExplicitGC
after System.gc: 1
```

不加参数时，`System.gc()` 让 G1 做了一次 Full GC：

```text
[0.144s][info][gc] GC(0) Pause Full (System.gc()) 2M->1M(10M) 4.716ms
```

加 `-XX:+DisableExplicitGC` 后，连 GC 日志都没有，弱引用条目也还在。**`System.gc()` 不是强制 GC，只是一个请求；HotSpot 可以执行、可以忽略，`-XX:+DisableExplicitGC` 下完全无效。**

线上禁止调它的原因不是「它会立刻把内存清干净」，而是：

- 它可能触发一次 Full GC/STW，停顿随堆和对象图增长，调用方控制不了；
- 它把一次不可控的停顿塞进业务线程，延迟毛刺很难解释；
- 它经常被用来掩盖泄漏：GC 后看起来降了，过一会又涨回来；
- 依赖库也可能替你调，最好用 `-XX:+DisableExplicitGC` 把这条路封掉。

排查时在测试环境用 `jcmd <pid> GC.run` 就够了；生产环境不要用 `System.gc()` 做「清理」。

### `jhat` 已经不在 JDK 里

JDK 9 起 `jhat` 被移除。JDK 25 的 `bin` 里能找到 `jmap` 和 `jcmd`，找不到 `jhat`。替代路径：

- `jcmd <pid> GC.heap_info`：先看堆概况；
- `jcmd <pid> GC.class_histogram`：看实例数和 shallow bytes；
- `jcmd <pid> GC.heap_dump <file>`：拿 dump；
- MAT 或 VisualVM：看引用链、Dominator Tree、retained size；
- `jmap -histo:live <pid>` / `jmap -dump:live,format=b,file=heap.hprof <pid>`：老工具仍在，但优先用 `jcmd`。

### OOM 自动 dump 默认是关的

```bash
java -Xmx512m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=C:\Users\Lenovo\AppData\Local\Temp\paideia-jvm-23\oom-heap.hprof -jar app.jar
```

`-XX:+HeapDumpOnOutOfMemoryError` 的默认值是 `false`；不写 `-XX:HeapDumpPath`，dump 会落在进程工作目录（文件名类似 `java_pid<pid>.hprof`），容器里可能正好是不可写或随容器销毁的路径。真实 OOM 时：

```text
java.lang.OutOfMemoryError: Java heap space
Dumping heap to C:\Users\Lenovo\AppData\Local\Temp\paideia-jvm-23\oom-heap.hprof ...
Heap dump file created [11430699 bytes in 0.020 secs]
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

注意：堆 dump 只包含堆对象。直接内存 OOM 时，dump 里最多看到 `DirectByteBuffer` 对象，看不到它引用的 native 字节；元空间 OOM 也不是靠堆 dump 解释。那两类要看 `jcmd VM.native_memory summary`（需启动时加 `-XX:NativeMemoryTracking=summary`）和 `jcmd VM.metaspace`。

## 常用参数：一对一对地看

```bash
-Xms512m                          # 初始堆
-Xmx2g                            # 最大堆；和 -Xms 一起决定堆的伸缩
-Xss512k                          # 每线程栈；越小，能开的线程越多，但更容易 SOE
-XX:MaxMetaspaceSize=256m         # 元空间上限；默认等于不设上限
-XX:MaxDirectMemorySize=256m      # 直接内存上限；不写时按堆上限
-XX:+HeapDumpOnOutOfMemoryError   # OOM 时 dump；默认关
-XX:HeapDumpPath=/tmp/heap.hprof  # dump 路径；要可写、有空间
-XX:MaxGCPauseMillis=200          # 停顿目标，不是保证
-Xlog:gc*:file=/tmp/gc.log        # JDK 9+ 的 GC 日志
```

`-Xms` 与 `-Xmx` 设成相等是常见做法：启动时就把堆定下来，避免运行期反复扩缩。验证：

```text
java -Xms64m -Xmx64m -XX:+PrintFlagsFinal -version
InitialHeapSize = 67108864
MaxHeapSize = 67108864
```

但设成相等不是语言要求。容器里要把元空间、直接内存、线程栈和 JVM 自身也算进去；`-Xmx` 只是堆的上限，不是整个进程的内存上限。

## 跑一遍

这段代码只做两件事：看弱引用在 GC 前后的变化，以及确认深递归得到的是 `StackOverflowError` 而不是 OOM。

<!-- run -->
```java
import java.util.Map;
import java.util.WeakHashMap;

public class Main {
    static class Key {
        final String name;
        Key(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    static int depth(int n) {
        return depth(n + 1);
    }

    public static void main(String[] args) throws Exception {
        Map<Key, String> weak = new WeakHashMap<>();
        Key kept = new Key("kept");
        weak.put(kept, "value");

        System.gc();
        Thread.sleep(100);
        System.out.println("kept after GC: " + weak.size());

        kept = null;
        System.gc();
        Thread.sleep(100);
        System.out.println("dropped after GC: " + weak.size());

        try {
            depth(0);
        } catch (Throwable error) {
            System.out.println(error.getClass().getSimpleName());
            System.out.println("is OutOfMemoryError: " + (error instanceof OutOfMemoryError));
            System.out.println("is VirtualMachineError: " + (error instanceof VirtualMachineError));
        }
    }
}
```

```text
kept after GC: 1
dropped after GC: 0
StackOverflowError
is OutOfMemoryError: false
is VirtualMachineError: true
```

前两行是弱引用的规则：`kept` 变量还强引用着 key，所以 GC 后条目留下；把 `kept` 置空后，下一次 GC 把只剩弱引用的条目清掉。后三行是本文最该记住的分类：`StackOverflowError` 属于 `VirtualMachineError`，但不属于 `OutOfMemoryError`，它不是堆内存不够。

示例里用 `System.gc()` 只是为了让弱引用实验在一次运行里完成；线上不要靠它。你可以在运行时加 `-XX:+DisableExplicitGC`，会看到 `dropped after GC` 不再是 0——这正好证明它只是建议。

### 你的练习

```code-challenge
{
  "id": "java-19-bug",
  "kind": "bug",
  "title": "找出只会增不会减的静态缓存",
  "language": "java",
  "code": "import java.util.HashMap;\nimport java.util.Map;\n\npublic class Main {\n    static final Map<String, byte[]> CACHE = new HashMap<>();\n\n    static byte[] load(String key) {\n        return CACHE.computeIfAbsent(key, k -> new byte[1024 * 1024]);\n    }\n\n    public static void main(String[] args) {\n        for (int i = 0; i < 10000; i++) {\n            load(Integer.toString(i));\n        }\n        System.out.println(CACHE.size());\n    }\n}",
  "bugLine": 8,
  "explain": "CACHE 是静态 Map，computeIfAbsent 只加不删；一万个 key 各带 1MB 数组，满载约 10GB。修法：用有上限的缓存（LinkedHashMap 覆盖 removeEldestEntry）、WeakHashMap（仅当 key 没有外部强引用时）或定期清理。"
}
```

## 检查一下

```quiz
{
  "id": "java-19-quiz",
  "title": "JVM 内存三问",
  "questions": [
    {
      "question": "看到 java.lang.OutOfMemoryError: unable to create native thread，先想到哪一块？",
      "options": [
        "堆被对象占满",
        "线程栈/native 内存或进程线程数限制",
        "磁盘空间不足"
      ],
      "answer": 1,
      "explain": "这不是 Java heap space。每条线程都要栈和 native 结构；线程数过多、栈太大或 OS 的进程/线程上限不够，都会让 pthread_create 失败。"
    },
    {
      "question": "JDK 8+ 的静态字段实际跟着谁待在堆里？",
      "options": [
        "Class 对象",
        "Metaspace 里的类元数据",
        "当前线程的虚拟机栈"
      ],
      "answer": 0,
      "explain": "JEP 122 把永久代移出后，静态字段跟着堆里的 Class 对象；Metaspace 放类元数据、方法字节码和运行时常量池，不放静态字段和字符串常量池。"
    },
    {
      "question": "判断一次内存上涨是不是泄漏，最靠谱的下一步是什么？",
      "options": [
        "看 RSS 有没有降",
        "调用 System.gc 后看 used 是否立刻降",
        "在稳定负载下 dump，比较 retained size 和引用链"
      ],
      "answer": 2,
      "explain": "RSS 包含堆、元空间、线程栈、直接内存和分配器 arena；used 只说明当前存活对象。retained size 和 GC Roots 引用链才能回答「谁把它留住了」。"
    }
  ]
}
```

## 还差的

- 没有展开 GC 算法：三色标记、SATB、G1 region 细节、ZGC/Shenandoah 都留给进阶；本文只到分代、G1 默认和停顿目标不是保证。
- 本机没有装 Eclipse MAT，heap dump 只验证了 `jcmd` 的生成和 shallow histogram；MAT 里的 Dominator Tree/retained size 需要你按上面的路径自己点开。
- native thread OOM 不是在同一台 Windows/JDK 25 上复现的：Windows 本机 3 万条线程仍成功，标准异常来自 Linux/OpenJDK 17 + `ulimit -u 100`；容器 cgroup 的 pids/memory 限制没有验证。
- 没有验证 `-XX:HeapDumpPath` 在磁盘满、不可写或容器销毁时的行为，也没有在生产服务上触发 OOM dump。
- 没有在 Java 21 沙箱镜像里跑这些示例；本机是 JDK 25，示例按 Java 21 语法写，`run` 段已在 JDK 25 上执行。
