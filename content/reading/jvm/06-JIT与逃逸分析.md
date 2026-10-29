---
title: JIT、逃逸分析与那些"优化了个寂寞"
category: jvm
order: 6
minutes: 24
level: advanced
status: published
---

## 先把结论摆出来

**JIT 不是"把 Java 编译成机器码"这么简单，它是一套基于运行时profile 的投机优化系统。它的假设成立时，代码会快得超出你的直觉；假设被推翻时，它会去优化、重新编译，表现为延迟尖刺。**

三句话：

1. 代码先解释执行，跑热了才编译；热点方法走 C1 再到 C2，C2 会做内联、逃逸分析、锁消除、标量替换；
2. 逃逸分析能消掉**不逃出方法/线程**的对象分配和锁，但它是优化，不是语言保证，不能拿它当正确性依据；
3. 微基准测试必须预热，否则你测的是解释器和 C1，不是线上跑的那个 C2 版本。手写"优化"之前先测，很多时候 JIT 已经做了，或者你的写法反而挡住了它。

## 解释器、C1、C2：三层执行方式

HotSpot 里有三种执行代码的方式，默认同时存在：

| 执行方式 | 启动速度 | 峰值性能 | 编译成本 | 典型用途 |
| --- | --- | --- | --- | --- |
| 解释器 | 立即 | 很低 | 无 | 冷代码、启动阶段 |
| C1（Client） | 快 | 中等 | 低 | 短生命周期方法、带 profiling 的过渡层 |
| C2（Server） | 慢 | 很高 | 高 | 长期运行的热点方法 |

解释器不只是"慢速执行器"，它还负责**收集 profile**：方法被调用了多少次、某个分支走了哪边、某个接口调用点实际出现了哪些类型。这些信息是 C2 做投机优化的依据。

C1 编译快，优化保守，适合快速把代码从解释器里救出来。C2 编译慢，但会做激进优化：内联、常量传播、逃逸分析、锁消除、循环优化。C2 的优化很多是**带假设的**，比如"这个 `List` 调用点过去一万次都是 `ArrayList`，那我就按 `ArrayList` 内联"。

分层编译（Tiered Compilation）把两者串起来，默认开启：

```text
Level 0：解释器
Level 1：C1，无 profiling
Level 2：C1，轻量 profiling
Level 3：C1，完整 profiling
Level 4：C2
```

常见路径是 `0 → 3 → 4`：先解释执行并收集 profile，再用 C1 编译一版带着 profile 的代码，等热度够了再交给 C2。方法特别简单时可能直接到 Level 1；C2 排队忙时，C1 版本会先顶着。

和这套机制相关的常用参数：

```text
-XX:+TieredCompilation          # 默认开启
-XX:-TieredCompilation          # 只用 C2，预热更慢，峰值可能略高
-Xint                           # 只解释执行，排查 JIT 相关问题时用
-Xcomp                          # 只编译执行，启动极慢，几乎只用于诊断
-XX:+PrintCompilation           # 打印编译事件
```

`-Xint` 和 `-Xcomp` 不是性能选项，是诊断选项。线上不要用它们"提升稳定性"。

## 热点探测与 OSR

JVM 怎么知道哪些代码"热"？两个计数器：

- **方法调用计数器**：方法被调用了多少次；
- **回边计数器（backedge）**：循环体往回跳了多少次。

达到阈值后触发编译。方法调用和循环是分开计的，因为一个只调用一次但内部循环一百万次的方法，也需要被编译。

这就引出了 **OSR（On-Stack Replacement，栈上替换）**：一个方法正在解释器里跑一个长循环，JVM 不能等它退出再编译，否则这个循环永远等不到优化。OSR 会在循环回边处直接把当前栈帧替换成编译后的版本，让循环的后半段跑机器码。

`-XX:+PrintCompilation` 里带 `%` 的行就是 OSR 编译：

```text
     123   45       3       com.example.OrderService::sum (42 bytes)
     124   46 %     4       com.example.OrderService::sum @ 12 (42 bytes)
```

第一行是普通方法编译，第二行的 `%` 表示 OSR。看到大量 `%` 编译，通常说明有长循环或批处理任务。

热点阈值不是一个固定数字，和分层、编译器队列、方法大小都有关。默认量级是几百到几千次调用/回边。你不需要背具体值，但要知道：**"跑几万次才编译"是正常的，所以微基准测试不预热就是在测解释器。**

## 内联：JIT 最重要的一步优化

如果只能记住一个 JIT 优化，记内联。

方法调用有开销：压栈、跳转、返回、参数传递。更重要的是，**不内联，后面的优化都做不了**。只有把被调方法展开到调用点，C2 才能做常量传播、死代码消除、逃逸分析、锁消除。

```java
int add(int a, int b) {
    return a + b;
}

int calc() {
    return add(1, 2) * 10;
}
```

内联后，C2 看到的是 `(1 + 2) * 10`，直接折叠成 30。没有内联，它只能生成一次调用。

HotSpot 的内联策略大致是：

- 小方法优先：`-XX:MaxInlineSize` 默认约 35 字节，`-XX:FreqInlineSize` 默认约 325 字节（热方法放宽）；
- 调用点类型越单一越容易内联：单态（只有一种类型）最好，双态可以带类型检查内联，多态（megamorphic，超过两种）通常不内联；
- 递归有层数限制：`-XX:MaxRecursiveInlineLevel`；
- 太大、太复杂、带异常处理的方法不容易内联。

这解释了两个常见现象：

1. **把代码拆成很多小方法，不一定更慢。** 小方法更容易内联，内联后和写在一起差不多，可读性还更好。
2. **接口/抽象类调用不一定会慢。** 如果调用点实际只有一种实现，C2 会按这个实现内联，并加一个类型检查；只有真的出现多种实现（megamorphic）时才会退化成虚调用。

可以用 `-XX:+PrintInlining` 看内联决策，它需要 `-XX:+UnlockDiagnosticVMOptions`：

```text
-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining
```

输出里会写 `inline (hot)`、`too big`、`already compiled into a big method`、`virtual call` 等。看到 `too big` 就可以考虑拆方法；看到 `megamorphic` 就要检查调用点是不是被太多类型污染了。

## 逃逸分析：对象真的没分配吗

逃逸分析（Escape Analysis）回答一个问题：**这个对象会不会跑出当前方法或当前线程？**

对象的逃逸状态大致分三种：

| 状态 | 含义 | 能做什么 |
| --- | --- | --- |
| `NoEscape` | 只在方法内部使用，不外传 | 标量替换、锁消除 |
| `ArgEscape` | 作为参数传给别的方法，但没逃出线程 | 有限优化，通常仍分配 |
| `GlobalEscape` | 赋给静态字段、返回、被其他线程看到 | 不能消除分配 |

当对象 `NoEscape` 时，C2 可以做两件事：

- **标量替换（Scalar Replacement）**：把对象拆成几个局部变量，不再在堆上分配；
- **锁消除（Lock Elision）**：如果对象只被当前线程使用，对它加的 `synchronized` 可以去掉。

```java
int sum() {
    int total = 0;
    for (int i = 0; i < 100; i++) {
        Point p = new Point(i, i);   // 只在循环里用，没传出去
        total += p.x;
    }
    return total;
}
```

`Point` 没有返回、没有存进字段、没有传给未知方法，逃逸分析可以判定 `NoEscape`，把 `p.x` 和 `p.y` 直接变成局部变量。循环跑起来可能一次分配都没有。

相关参数：

```text
-XX:+DoEscapeAnalysis          # 默认开启
-XX:+EliminateAllocations      # 默认开启，标量替换
-XX:+EliminateLocks            # 默认开启，锁消除
-XX:+UnlockDiagnosticVMOptions -XX:+PrintEscapeAnalysis
-XX:+UnlockDiagnosticVMOptions -XX:+PrintEliminateAllocations
```

但逃逸分析有很多边界，下面这些都会让对象"逃逸"：

- 对象被 `return` 返回；
- 对象被赋给字段、静态变量、数组元素；
- 对象被传给一个 JIT 看不透的方法（native 方法、反射、未内联的大方法）；
- 对象被放进集合、队列、缓存；
- 对象被其他线程看到；
- 对象需要真实身份：`==` 比较、`System.identityHashCode`、`hashCode` 依赖地址、弱引用/软引用；
- 对象数组、变长数组、太大的对象；
- 异常路径把对象带出去。

所以"我 `new` 了一个对象，JIT 应该会优化掉"是一个不可靠的假设。逃逸分析是**机会性优化**，不是语言语义。你不能因为它可能消除分配，就写出大量浪费的代码；也不能因为依赖对象身份（比如用它做锁、做 `ConcurrentHashMap` 的 key），就指望它被消除。

> 注意：HotSpot 做的是标量替换，不是"在栈上分配对象"。很多资料说"栈上分配"，严格说只对了一部分：对象被拆成标量后，确实不在堆上，但并没有一个完整的对象放在栈上。

### 锁消除的边界

```java
void add() {
    synchronized (new Object()) {   // 锁对象只在这里出现，别的线程拿不到
        count++;
    }
}
```

这个锁没有意义，逃逸分析可以消掉。但下面这个不行：

```java
void add() {
    synchronized (this) {           // this 逃逸到外部，别的线程也能拿到
        count++;
    }
}
```

锁消除只对"不可能被其他线程获取"的锁生效。它不能成为你删掉共享状态同步的理由：共享对象的锁该加还得加。

## 去优化：优化被打脸的时候

C2 的优化建立在假设上：

- "这个调用点只会出现 `ArrayList`"；
- "这个 `if` 分支从来不走"；
- "这个类没有被继承"；
- "这个字段没有被修改"。

一旦假设被推翻，JVM 会**去优化（Deoptimization）**：丢掉编译后的机器码，回到解释器或较低层级，重新收集 profile，再决定怎么编译。

`-XX:+PrintCompilation` 里会看到：

```text
     456   78       4       com.example.Size::get (30 bytes)   made not entrant
     457   79       4       com.example.Size::get (30 bytes)   made zombie
```

`made not entrant` 表示这段编译代码不再被新调用进入；`made zombie` 表示已经没有栈帧在用，可以被回收。

常见的去优化触发：

| 触发 | 例子 |
| --- | --- |
| 类型假设失败 | 一直传 `ArrayList`，突然来了 `LinkedList` |
| 分支假设失败 | 一直走 `if`，突然走 `else` |
| 类层次变化 | 新类加载，推翻了"没有子类"的假设 |
| 空值/边界检查失败 | 投机执行的路径真的遇到 null 或越界 |
| 代码缓存满 | 编译代码放不下，编译被禁用 |
| `-XX:+PrintCompilation` 里的 uncommon trap | 投机分支真的被执行 |

偶发的去优化是正常的，影响通常是一次性延迟。**去优化风暴**才是问题：大量方法反复编译、去优化、再编译，CPU 花在编译上，延迟出现周期性尖刺。常见原因是：

- 调用点类型太多（megamorphic），每次流量结构变化都推翻上一次的 profile；
- 类加载/热部署频繁，类层次不断变化；
- 代码缓存太小，编译结果被挤掉；
- 大量反射、动态代理、脚本引擎生成的类。

排查工具：

- `-XX:+PrintCompilation` 看编译/去优化频率；
- `-XX:+PrintCodeCache` 或 `jcmd <pid> VM.info` 看代码缓存使用；
- JFR 的 `jdk.Deoptimization` 事件；
- `-XX:ReservedCodeCacheSize` 调整代码缓存（默认通常够用，除非大量动态生成代码）。

## JMH：为什么必须预热

微基准测试最大的陷阱不是"测不准"，而是"测的不是你以为的代码"。

一个朴素的手写 benchmark：

```java
long start = System.nanoTime();
for (int i = 0; i < 1_000_000; i++) {
    result += work(i);
}
long cost = System.nanoTime() - start;
```

这段代码有几个问题：

1. **前几万次跑的是解释器/C1**，不是 C2；
2. 循环可能被 JIT 优化掉（如果结果没被使用）；
3. 常量可能被折叠，`work(i)` 可能根本没执行；
4. 没有考虑 GC、JIT 编译线程、CPU 频率变化；
5. 单次运行，没有统计分布，噪声全算进结果。

JMH 把这些都处理了：

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Thread)
public class PointBench {

    @State(Scope.Thread)
    public static class Data {
        int seed = ThreadLocalRandom.current().nextInt();
    }

    private final List<Point> points = new ArrayList<>();

    @Benchmark
    public int scalar(Data data) {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            Point p = new Point(data.seed + i, data.seed - i);
            sum += p.x;
        }
        return sum;
    }
}
```

几个注解的作用：

| 注解 | 作用 |
| --- | --- |
| `@Warmup` | 预热轮数/时长，让 JIT 编译、类加载、profile 稳定 |
| `@Measurement` | 正式测量轮数/时长 |
| `@Fork` | 每个 fork 一个独立 JVM，避免上一次的 profile 污染下一次 |
| `@State` | 定义基准状态的生命周期（`Thread` / `Benchmark`） |
| `@BenchmarkMode` | 测平均时间、吞吐、采样等 |
| `@OutputTimeUnit` | 输出单位 |

预热到底在等什么：

- 类加载、链接、初始化；
- 解释器收集 profile；
- C1、C2 编译热点方法；
- 内联、逃逸分析等优化生效；
- 分支预测、CPU 缓存、TLAB 等硬件/运行时状态稳定；
- 代码缓存布局稳定。

只预热 CPU 缓存是不够的。**JIT 没编译到 C2，测出来的数字和线上没有关系。**

用 `-prof gc` 可以看每次操作的分配量（下面是示意输出，具体分数取决于机器）：

```text
Benchmark                 Mode  Cnt    Units
PointBench.scalar        avgt   15    ns/op
PointBench.scalar:·gc.alloc.rate.norm   avgt   15   ≈ 10⁻⁹   B/op
```

`alloc.rate.norm` 接近 0，说明逃逸分析把 `Point` 的分配消掉了。把 `p` 加进一个 `List` 再测：

```java
@Benchmark
public int escaped() {
    int sum = 0;
    for (int i = 0; i < 100; i++) {
        Point p = new Point(i, i);
        points.add(p);          // 逃逸到集合
        sum += p.x;
    }
    return sum;
}
```

`alloc.rate.norm` 会变成每操作上千字节，性能也可能下降一个数量级。**同一段代码，只因为对象逃逸了，优化结果完全不同。** 这就是为什么"我在本地测过，很快"经常不可复现：本地的小循环可能被逃逸分析优化了，线上对象被存进缓存/队列，分配又回来了。

JMH 也有坑：

- 返回值要用 `Blackhole` 消费，或者作为 `@Benchmark` 返回值，否则可能被死代码消除；
- 不要在基准方法里写 `System.out.println`，IO 会淹没测量；
- `@State` 的共享/线程作用域要选对，否则会有伪共享或竞争；
- 常量参数可能被常量折叠，用 `@Param` 或 `Blackhole` 避免；
- 单个 fork 可能被 JIT/GC 噪声影响，至少 3 个 fork；
- 测分配用 `-prof gc`，测 CPU 热点用 `-prof perfasm`（Linux）或 JFR。

## 那些"优化了个寂寞"

下面这些手写优化，在 JIT 面前经常没有收益，甚至会变慢。

| 手写优化 | 实际情况 |
| --- | --- |
| 手动展开循环 | C2 会做循环展开；手写展开可读性差，还可能阻止其他优化 |
| 把 `list.size()` 缓存到局部变量 | C2 通常能提升/消除重复调用；手写缓存有时反而增加寄存器压力 |
| 用 `StringBuilder` 拼简单字符串 | `javac` 用 `invokedynamic` + `StringConcatFactory`，JIT 也会优化；简单拼接直接 `+` 更清楚 |
| 避免 `try/catch` | 没有异常抛出时，`try/catch` 基本零成本；为了"性能"删掉异常处理是净亏 |
| 对象池 | 现代分配在 TLAB 上极快；池会引入竞争、生命周期管理和内存泄漏，通常更慢 |
| 给方法/类加 `final` 提升性能 | 影响很小；`final` 应用来表达语义，不是性能开关 |
| `String.intern()` 省内存 | 常量池有竞争和元空间成本；滥用会让 CPU 和 Metaspace 一起涨 |
| 用 `int` 代替 `long` | 64 位 JVM 上差别很小；溢出风险更大 |
| 手动 `System.arraycopy` 替代循环 | `arraycopy` 是 intrinsic，通常更快；但普通循环也可能被向量化，先测再说 |
| 关闭逃逸分析/分层编译 | 只会让预热更慢、峰值更低，不是"稳定" |
| 用 `==` 比较字符串 | 语义错误，不是优化 |
| 自己实现无锁队列 | 正确性极难保证；`ArrayBlockingQueue` / `LinkedTransferQueue` 已经调好 |

这些"优化"的共同问题是：**它们都在猜 JIT 没做什么，而不是先测量瓶颈在哪里。**

真正该做的顺序：

1. **先用 JFR / async-profiler 找热点**：CPU 花在哪个方法、锁竞争在哪里、对象分配在哪里；
2. **看分配速率和 GC**：如果每秒分配几百 MB，先减少对象创建，而不是微调循环；
3. **看锁和 IO**：很多"CPU 高"其实是锁竞争或下游超时导致的重试；
4. **只优化热点路径**：冷代码优化没有收益，还会增加维护成本；
5. **用 JMH 验证**：改前改后各跑一次，看置信区间是否真的分开了。

一个常见的反例：有人把 `for` 循环里的 `list.size()` 提到循环外，结果因为 `list` 在循环里被修改，逻辑错了；性能没提升多少，bug 倒是引入了。JIT 本来就能处理这种重复调用，不需要你替它操心。

另一个反例：为了避免"对象分配"，把可读的 `new OrderSummary(...)` 改成复用可变对象。结果对象被多个线程共享，字段互相覆盖，最后要靠加锁修正，性能反而更差。**不可变对象的分配很便宜，共享可变对象的同步很贵。**

## 一个具体例子

某接口在本地 JMH 里测出来 200 ns/op，上线后 P99 到了 20 ms。代码简化如下：

```java
public List<Item> recommend(long userId) {
    List<Item> result = new ArrayList<>();
    for (long id : candidateIds(userId)) {
        Item item = new Item(id, score(id));
        if (item.score > threshold) {
            result.add(item);
        }
    }
    return result;
}
```

本地测试只传了 10 个候选，`result` 很小，`Item` 在循环里创建后马上被读取，逃逸分析可能把一部分分配消掉。线上候选有几千个，`result` 被返回、被序列化、被放进缓存，`Item` 全部逃逸，每请求分配几千个对象。再加上序列化和缓存拷贝，P99 自然上去了。

这说明两件事：

- **微基准的输入规模要和线上一致**，否则逃逸分析和缓存行为完全不同；
- **对象分配不是免费的**，即使 JIT 很强，分配速率高到一定程度也会把 GC 拖下水。

正确的优化不是"手动复用 Item 对象"，而是：

1. 用 JFR 看分配热点，确认 `Item` / `ArrayList` / 序列化是不是主要来源；
2. 减少不必要的中间对象（比如直接流式返回，不构造完整列表）；
3. 限制候选数量，别把几千个候选全算一遍；
4. 用 JMH 在**相同规模**下对比改前改后，而不是在 10 个元素上自欺欺人。

如果最终发现瓶颈是下游 RPC 而不是 CPU，那 JIT 层面的优化全是白费。**先定位瓶颈类型，再谈优化手段。**

## 检查一下

```quiz
{"id":"jvm-06-quiz","title":"JIT 三问","questions":[
 {"question":"JMH 基准测试为什么必须预热？","options":["只是为了暖 CPU 缓存","要让类加载、profile 收集和 C1/C2 编译稳定，否则测的是解释器或低层编译版本","预热能避免 GC","不预热 JMH 会报错"],"answer":1,"explain":"JIT 是分层、基于 profile 的；不预热测不到线上 C2 版本的行为。"},
 {"question":"逃逸分析对不逃出方法的对象通常能做什么？","options":["保证对象一定在栈上分配","做标量替换消除分配，并可能消除该对象的锁","把对象变成 static","自动加 volatile"],"answer":1,"explain":"NoEscape 对象可被拆成标量，锁也可消除；但这是优化，不是语言保证。"},
 {"question":"-XX:+PrintCompilation 里的 made not entrant 表示什么？","options":["方法第一次被编译","编译代码不再被新调用进入，通常是去优化/重新编译","GC 完成了","代码缓存已满"],"answer":1,"explain":"它表示这段编译代码退役，常见于类型假设失败等去优化场景。"}
]}
```

## 常见误区

### 误区：JIT 会把代码优化到最优，所以不用管性能

**你以为**：只要跑得够久，JVM 就会把一切优化好。
**其实**：JIT 只优化热点，冷代码仍然解释执行；它的优化基于 profile，流量结构变化会触发去优化；代码缓存有限，大量动态生成类会把缓存挤满；它也不会替你做算法和 IO 层面的优化。JIT 是"把已经合理的代码跑快"，不是"把糟糕的设计救回来"。
**怎么验证**：用 `-XX:+PrintCompilation` 看哪些方法真的被编译；用 JFR 看热点是否在你以为的方法上。冷门路径上的"优化"通常没有任何编译事件。

### 误区：逃逸分析保证 `new` 不会分配

**你以为**：只要对象在方法里创建，JVM 就会把它优化掉。
**其实**：只有 `NoEscape` 对象才可能被标量替换。对象一旦被返回、存进字段/集合、传给看不透的方法、被其他线程看到，就会正常分配。逃逸分析还是机会性的，不能作为正确性保证（比如依赖对象身份、弱引用、锁行为）。
**怎么验证**：用 JMH `-prof gc` 看 `alloc.rate.norm`；把对象加进 `List` 再测，分配量会立刻出现。也可以用 `-XX:+PrintEscapeAnalysis` 看判定结果。

### 误区：微基准测试不用 JMH，手写 `nanoTime` 就行

**你以为**：循环里跑一百万次，除以时间就是单次耗时。
**其实**：手写循环很难处理死代码消除、常量折叠、JIT 预热、GC 噪声、CPU 频率变化。你测到的可能是"编译器把整个循环删掉"的时间。JMH 的 `Blackhole`、`@Fork`、`@Warmup` 就是为这些坑设计的。
**怎么验证**：写一个返回常量、结果不被使用的方法，用手写循环测——可能接近 0 ns；换成 JMH + `Blackhole.consume`，才是真实成本。

### 误区：关掉分层编译/逃逸分析会更稳定

**你以为**：优化越少，行为越可预测。
**其实**：关掉分层编译会让预热变长、峰值性能下降；关掉逃逸分析会增加分配和 GC 压力；关掉 C2 更是直接放弃峰值性能。这些开关是诊断工具，不是生产调优手段。真正的稳定来自可观测性（JFR、GC 日志、指标），不是关掉优化。
**怎么验证**：同一份 JMH 基准，分别用默认、`-XX:-TieredCompilation`、`-XX:-DoEscapeAnalysis` 跑，比较预热时间和稳定后的分数。

### 误区：手写对象池一定比 `new` 快

**你以为**：复用对象能减少 GC，所以更快。
**其实**：现代 JVM 的 TLAB 分配非常快，短命对象在年轻代回收也便宜；对象池会引入借还协议、线程竞争、状态清理和泄漏风险。只有当对象构造极贵（大数组、连接、native 资源）时才值得池化。
**怎么验证**：用 JMH 对比 `new` 和池化版本，同时在多线程下测；很多池化版本在低竞争时略快，高竞争时明显更慢。

## 小结

JIT 的核心不是"编译"，而是"基于运行时信息的投机优化"：

- 解释器收集 profile，C1 快速编译，C2 做内联、逃逸分析、锁消除、标量替换；
- 热点由调用计数和回边计数决定，长循环靠 OSR 栈上替换；
- 内联是最重要的优化，小方法、单态调用点更容易被内联；
- 逃逸分析只对不逃出方法/线程的对象生效，是优化不是保证；
- 假设被推翻时会去优化，偶发正常，风暴要查类型污染、类加载和代码缓存；
- 微基准必须预热，JMH 不是可选项；
- 手写优化之前先测量，大多数"优化"JIT 已经做了，或者根本不在瓶颈上。

最后一句：**让代码保持简单、热点集中、调用点单态、对象尽量不可变，比任何手写 micro-optimization 都更有效。** JIT 喜欢简单代码，读代码的人也喜欢。

## 附录：常用诊断参数速查

```text
# 看编译事件（含 OSR、去优化）
-XX:+PrintCompilation

# 看内联决策（需要 diagnostic）
-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining

# 看逃逸分析与分配消除（需要 diagnostic）
-XX:+UnlockDiagnosticVMOptions -XX:+PrintEscapeAnalysis
-XX:+UnlockDiagnosticVMOptions -XX:+PrintEliminateAllocations

# 看代码缓存
-XX:+PrintCodeCache
jcmd <pid> VM.info | grep -i codecache

# 只解释 / 只编译（仅诊断）
-Xint
-Xcomp

# JMH 常用
-prof gc
-prof perfasm
-prof stack
```

这些参数不是让你天天开着，而是排查"为什么这段代码没有按预期优化"时用的。生产环境优先用 JFR 和 async-profiler，它们对运行时的影响更可控。
