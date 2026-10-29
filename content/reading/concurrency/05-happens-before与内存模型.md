---
title: happens-before：为什么加了 volatile 还是错
category: concurrency
order: 5
minutes: 26
level: advanced
status: published
---

## 先把结论摆出来

**`volatile` 只建立一条很窄的可见性通道，不负责让一段代码变成原子操作。**

它保证两件事：对这个变量的写，对之后读到它的线程可见；这个写之前的普通写，也会跟着一起可见。它**不保证**读-改-写是原子的，不保证多个变量作为一个整体一起变，也不保证你写的每一行都按源码顺序发生。

真正决定"一个线程的写，另一个线程能不能看见"的，是 Java 内存模型（JMM）里的 **happens-before（先行发生）** 关系。两个人之间没有这条边，哪怕代码在你这台机器上跑一万次都对，也只是"碰巧"，不是"正确"。

判断一段并发代码是否可靠，只看一句话：**所有跨线程的读写，是否都被某条 happens-before 边连起来了。** 连上了，才有可见性与顺序保证；没连上，就是数据竞争。

## 先把三个词分清：可见性、有序性、原子性

这三个词经常被混着说，但它们解决的是不同问题。混着说，就会得出"加了 volatile 就该对"这种结论。

| 性质 | 回答的问题 | 典型手段 | 不加会怎样 |
| --- | --- | --- | --- |
| 可见性 | 一个线程写了，另一个线程什么时候能看见 | `volatile`、锁、`final`、并发容器 | 另一个线程可能一直读旧值 |
| 有序性 | 指令会不会被编译器/CPU 调换顺序 | `volatile`、锁、`final`、`VarHandle` | 另一个线程可能看到"半成品" |
| 原子性 | 一组操作会不会被别的线程插进来 | 锁、CAS、原子类、事务性容器方法 | 丢更新、超卖、重复创建 |

`volatile` 同时提供了**可见性**和**有限的有序性**，但完全没有提供**原子性**。所以下面这段代码无论加多少 `volatile` 都不会对：

```java
private volatile int count;

void inc() {
    count++;   // 读 count、加一、写回，三步
}
```

十个线程各加一万次，结果通常小于十万，而且没有任何保证。原因是两个线程可能同时读到 `count == 100`，各自算出 101，再先后写回 101，其中一次更新被覆盖。`volatile` 保证的是"你写进去的 101 别人能看见"，不是"读和写之间没人插队"。

原子性要靠锁、`AtomicInteger`，或者 `LongAdder` 这类专门为计数设计的类。可见性、有序性、原子性三件事，必须分开检查。

## JMM 到底在定义什么

很多人把 JMM 理解成"CPU 缓存 + 主存"的示意图，然后背"工作内存"四个字。这个图有助于理解，但它不是规范本身。

JMM 是一份**允许哪些执行结果出现的规则**。它不规定 JVM 必须怎么实现，只规定：一个正确同步的程序，必须表现出顺序一致的行为；一个存在数据竞争的程序，允许出现各种"反直觉"的结果。

规范里有两个关键概念：

- **数据竞争（data race）**：两个线程访问同一个变量，至少有一个是写，而且这两次访问之间没有 happens-before 关系。
- **正确同步（correctly synchronized）**：程序里不存在数据竞争。只有正确同步的程序，才保证所有执行结果都等价于某种顺序执行。

换句话说，**没有 happens-before 保护的读写，JMM 不承诺任何事**。不是"一定会错"，而是"错了也不算 JVM 的错"。线上偶发、压测复现不了、换台机器就好了，通常都落在这个区间里。

还要注意：JMM 允许的重排，是站在**单线程语义不变**的前提下做的。编译器可以把你写的两条独立语句换顺序，只要单线程跑起来结果一样。这就是 `as-if-serial`：单线程看你还是那个顺序，多线程看就未必了。

## 指令重排从哪来

重排不是某一个环节的毛病，而是三层机制共同作用的结果：

| 来源 | 做什么 | 例子 |
| --- | --- | --- |
| 编译器 | 调整字节码/机器码顺序，消除冗余 | 把循环外的计算提到循环外 |
| CPU | 乱序执行、指令流水线 | 后面的加法先于前面的读内存完成 |
| 内存系统 | 写缓冲、缓存一致性延迟 | 写先进入 store buffer，别的核暂时看不到 |

三层都有自己的"本地优化"，合起来就产生了跨线程可见的顺序问题。JMM 不要求你理解每一层的细节，它用 happens-before 把"哪些顺序必须保留"抽象出来。

重排有一条底线：**有数据依赖的语句不能换。** `a = 1; b = a;` 里，`b = a` 依赖 `a` 的写，换不了。但 `a = 1; flag = true;` 之间没有数据依赖，只要单线程看不出差别，就可能被换。控制依赖（`if` 分支里的写）在有些情况下也会被预测执行绕过，所以"有 `if` 就不会重排"也不成立。

这就是为什么并发 bug 常常表现为"标志位先到了，数据没到"：标志位和数据之间没有依赖，CPU 或编译器没有理由等数据写完再写标志位。

## happens-before 的八条规则

JMM 定义的 happens-before 是一个偏序关系。它不要求所有操作都能比出先后，只要求存在某些必须成立的边。官方列出的八条如下。

| 规则 | 内容 | 典型代码 |
| --- | --- | --- |
| 程序次序 | 同一个线程里，前面的操作先行发生于后面的操作 | `a = 1; b = 2;` |
| 监视器锁 | 对同一个锁的解锁，先行发生于之后对它的加锁 | `synchronized` 退出 → 下一个进入 |
| volatile | 对 volatile 变量的写，先行发生于之后对它的读 | `flag = true` → `if (flag)` |
| 线程启动 | `Thread.start()` 先行发生于新线程里的每一个操作 | `start()` 之前设置的参数 |
| 线程终止 | 线程里的所有操作，先行发生于别的线程发现它结束 | `join()` 返回、`isAlive()` 为 false |
| 线程中断 | `interrupt()` 先行发生于被中断线程检测到中断 | `catch (InterruptedException)` |
| 对象终结 | 构造函数结束先行发生于 `finalize()` 开始 | 一般不用关心 |
| 传递性 | A 先行发生于 B，B 先行发生于 C，则 A 先行发生于 C | 把上面几条串起来 |

前六条是日常真正会用到的。第七条的 `finalize` 已经废弃，知道有这条即可。第八条最重要：**单看一条边往往不够，真正让代码成立的是把边串起来。**

一个例子：

```java
// 线程 A
data = 42;          // 普通写
ready = true;       // volatile 写

// 线程 B
if (ready) {        // volatile 读
    System.out.println(data);
}
```

线程 B 看到 `ready == true` 时，一定能看到 `data == 42`。推理链是：

1. `data = 42` 先行发生于 `ready = true`（程序次序）；
2. `ready = true` 先行发生于线程 B 的 `if (ready)`（volatile 规则）；
3. 由传递性，`data = 42` 先行发生于线程 B 的读。

把这两行换个顺序，结论就没了：

```java
ready = true;       // volatile 写
data = 42;          // 普通写，在 volatile 写之后
```

线程 B 可能看到 `ready == true`，但 `data` 还是 0。因为 `data = 42` 在 volatile 写之后，volatile 规则只保证"写之前的操作"跟着可见，不保证"写之后的操作"也被推过去。**volatile 是一条单向的 release 边，不是一堵把整段代码包起来的墙。**

还要强调：happens-before 不是"墙上时钟谁先谁后"。它是"如果 B 看到了 A 的某个同步动作，那么 A 在它之前的写对 B 可见"。两个线程完全可能在不同核上同时跑，JMM 只关心可见性和顺序保证，不关心纳秒级的时间。

## volatile 的语义与边界

把 `volatile` 的读写拆开看，它分别是一条 acquire 和一条 release：

| 操作 | 语义 | 效果 |
| --- | --- | --- |
| volatile 写 | release | 写之前的普通写，不能跑到它后面；写本身对后续读可见 |
| volatile 读 | acquire | 读之后的普通读写，不能跑到它前面；能看到写线程在写之前的所有写 |

这两条合起来，构成了"安全发布"的最短路径：**把对象构造好，再把引用写进 volatile 字段。** 读线程只要读到非 null 的引用，就能看到构造期间写入的所有字段。

但边界也很清楚：

- **不保证复合操作原子**：`count++`、`check-then-act` 照样错。
- **不保证多个 volatile 变量作为一个整体一致**：读两个 volatile 字段之间，写线程可以插进来。
- **不保证引用指向的对象的内部状态**：`volatile List<Order> orders` 只保证 `orders` 这个引用可见；`orders.add(...)` 不是线程安全的，除非用并发容器。
- **不保证数组元素可见**：`volatile int[] arr` 只让 `arr` 这个引用有 volatile 语义，`arr[0]` 没有。
- **不提供互斥**：两个线程可以同时写同一个 volatile 变量，最后谁赢没有定义。

现代 Java 还提供了 `VarHandle`，把这些语义拆得更细：

```java
VarHandle H = MethodHandles.arrayElementVarHandle(int[].class);

H.setVolatile(arr, 0, 1);     // 完整 volatile 语义
H.setRelease(arr, 0, 1);      // 只保证之前的写先发布
int x = H.getAcquire(arr, 0); // 只保证之后的读不提前
H.compareAndSet(arr, 0, 1, 2);
```

`setRelease` / `getAcquire` 比完整 volatile 更轻，适合"我只需要单向发布"的场景。日常业务代码很少直接用到，但理解它们能帮你明白：volatile 不是魔法，它只是内存屏障的一种组合。

## 为什么"加了 volatile 还是错"

下面六种写法，是"我明明加了 volatile"却仍然出错的常见来源。

### 一、把 volatile 当成原子类

```java
private volatile int stock = 10;

boolean take() {
    if (stock > 0) {
        stock--;       // 读、判断、写，三步
        return true;
    }
    return false;
}
```

两个线程可能同时看到 `stock == 1`，都判断通过，都执行 `stock--`，最后卖出两件。`volatile` 让每次读写都看见最新值，但"看见"和"独占"是两件事。要么加锁，要么用 `AtomicInteger.decrementAndGet()` 并在结果为负时回滚，要么用 `synchronized` 把判断和扣减包在一起。

### 二、check-then-act 的窗口

```java
private volatile Map<String, User> cache = new HashMap<>();

User get(String id) {
    User user = cache.get(id);
    if (user == null) {
        user = load(id);
        cache.put(id, user);   // 两个线程可能都走到这里
    }
    return user;
}
```

即使把 `cache` 换成 `ConcurrentHashMap`，这段仍然是"先查再放"的复合操作。两个线程可能同时查不到，同时加载，同时放入。正确做法是用 `computeIfAbsent`，或者接受重复加载但保证结果一致。

### 三、多个变量要一起变

```java
private volatile int success;
private volatile int failure;

void record(boolean ok) {
    if (ok) success++;
    else failure++;
}
```

读线程先读 `success` 再读 `failure`，中间可能正好有一次写，于是读到的是"旧 success + 新 failure"这种从未同时存在过的组合。两个字段各自可见，不等于它们作为一个整体可见。

要一起变，就把它们装进一个不可变对象，再用一个 volatile 引用发布；或者用锁把读和写都保护起来。

### 四、volatile 引用不保护对象内部

```java
private volatile List<Order> orders = new ArrayList<>();

void add(Order order) {
    orders.add(order);   // 不是线程安全的
}
```

`orders` 的引用有 volatile 语义，但 `ArrayList.add` 内部的 `size`、数组、扩容都没有。多个线程同时 `add`，照样会丢元素、数组越界、读到不一致的中间状态。要么换成 `CopyOnWriteArrayList` / `ConcurrentLinkedQueue`，要么自己加锁。

### 五、方向搞反：volatile 写之后的写不会被发布

```java
private int data;
private volatile boolean ready;

void publish() {
    ready = true;   // 先写 volatile
    data = 42;      // 后写普通变量
}
```

读线程看到 `ready == true` 时，`data` 可能还是 0。因为 `data = 42` 在 volatile 写之后，没有 happens-before 边把它连到读线程。把两行换过来才是对的。

同理，volatile 读**之前**的读，也不保证能看到写线程的更新：

```java
int local = data;      // 先读普通变量
if (ready) { ... }     // 后读 volatile
```

这里 `local` 的读发生在 acquire 之前，编译器可以让它提前，读到的可能是旧值。要读最新值，先读 volatile，再读普通变量。

### 六、把 volatile 当锁用

```java
private volatile boolean locked = false;

void doWork() {
    while (locked) { }     // 自旋等
    locked = true;
    try {
        // 临界区
    } finally {
        locked = false;
    }
}
```

这不是锁，是两个线程同时把 `locked` 设成 true 的竞态。volatile 没有"检查并设置"的原子性。真要做自旋锁，得用 `AtomicBoolean.compareAndSet(false, true)`，而且还要考虑公平性、重入、异常释放等问题——这也是为什么应该直接用 `ReentrantLock`。

把六种情况收成一张表：

| 写法 | 缺的是什么 | 正确做法 |
| --- | --- | --- |
| `volatile` 计数器自增 | 原子性 | `AtomicInteger` / `LongAdder` / 锁 |
| 先查后放 | 复合操作原子性 | `computeIfAbsent` / 锁 |
| 多个字段一起变 | 整体一致性 | 不可变快照 + volatile 引用 / 锁 |
| volatile 容器引用 | 容器内部线程安全 | 并发容器 / 锁 |
| volatile 写之后再写数据 | 发布方向反了 | 先写数据，再写 volatile |
| volatile 当锁 | 互斥 | `ReentrantLock` / `synchronized` |

## final 与安全发布

`final` 字段有一条特殊保证：**如果一个对象在构造完成后才被其他线程看到，那么所有 final 字段的值一定是构造函数里写入的值，哪怕发布过程没有同步。**

这条规则叫初始化安全（initialization safety）。它让"不可变对象 + final 字段"成为最便宜的安全发布方式之一：不需要 volatile，也不需要锁，只要保证对象在构造期间没有逃逸。

```java
final class Config {
    private final String endpoint;
    private final int timeoutMs;

    Config(String endpoint, int timeoutMs) {
        this.endpoint = endpoint;
        this.timeoutMs = timeoutMs;
    }
}
```

只要构造完成后才把引用交给别的线程，读线程看到的 `endpoint` 和 `timeoutMs` 就不会是 null 或 0。

但 `final` 的保证有一个前提：**`this` 不能在构造期间逃逸。** 下面这些写法都会破坏它：

```java
class BadConfig {
    static final List<BadConfig> ALL = new ArrayList<>();
    private final int timeout;

    BadConfig(int timeout) {
        this.timeout = timeout;
        ALL.add(this);   // this 逃逸了，别的线程可能在这行之后、构造完成之前看到它
    }
}
```

`ALL.add(this)` 把半成品交给了静态列表，另一个线程完全可能在这个时刻读到 `timeout == 0`。`final` 的冻结发生在构造函数结束时，对象还没冻结就被发布，保证就不成立。

常见的逃逸方式还有：

- 在构造函数里启动线程，线程里访问 `this`；
- 在构造函数里注册监听器/回调，回调会立刻或并发地被调用；
- 在构造函数里把 `this` 放进静态字段、缓存、事件总线；
- 在构造函数里调用可被子类重写的方法（子类字段还没初始化）。

安全发布的几种常见手段：

| 方式 | 原理 | 适用 |
| --- | --- | --- |
| 静态初始化器 | 类初始化锁保证可见 | 单例、常量表 |
| `final` 字段 | 初始化安全 | 不可变对象 |
| `volatile` 引用 | release/acquire | 可变配置、缓存快照 |
| 锁 | 解锁/加锁建立 HB | 需要互斥时 |
| 并发容器 | 内部有 HB 保证 | 共享集合、队列 |
| `AtomicReference` | 内部 volatile/CAS | 需要原子替换引用 |

## 双重检查锁：volatile 到底挡了什么

双重检查锁（DCL）是 happens-before 最经典的应用，也是最容易被背错的地方。

```java
final class Singleton {
    private static volatile Singleton instance;

    static Singleton get() {
        Singleton local = instance;
        if (local == null) {
            synchronized (Singleton.class) {
                local = instance;
                if (local == null) {
                    local = new Singleton();
                    instance = local;
                }
            }
        }
        return local;
    }
}
```

为什么 `instance` 必须是 volatile？因为 `new Singleton()` 不是一步：

```text
1. 分配内存
2. 调用构造函数，写入字段
3. 把引用赋给 instance
```

没有 volatile 时，编译器或 CPU 可能把第 3 步提到第 2 步之前。线程 B 在第一个 `if` 处看到 `instance != null`，直接返回，拿到的是一个字段还是默认值的对象。

加了 volatile 之后，第 3 步是 volatile 写，第 1、2 步不能跑到它后面；线程 B 的 `instance` 读是 volatile 读。线程 A 的构造写 → volatile 写 → 线程 B 的 volatile 读，由传递性建立 HB，构造写对 B 可见。这里挡住的不是"所有重排"，而是**赋值不能越过构造**这一条关键顺序。

注意几个细节：

- 第一个 `if` 的读也是 volatile 读，所以快速路径也有 acquire 语义；
- 把 `instance` 先读进局部变量 `local`，是为了避免在同步块里反复读 volatile（性能与清晰度都更好）；
- `synchronized` 块里再检查一次，是因为可能有多个线程同时通过了第一个 `if`；
- 更简单的替代方案是**静态内部类**（类初始化由 JVM 保证线程安全）或**枚举单例**，它们不需要你手写 DCL。

```java
final class HolderSingleton {
    private HolderSingleton() { }

    private static final class Holder {
        static final HolderSingleton INSTANCE = new HolderSingleton();
    }

    static HolderSingleton get() {
        return Holder.INSTANCE;   // 类初始化锁保证只创建一次，且安全发布
    }
}
```

如果不需要延迟初始化，直接 `static final` 字段就够了。DCL 只在"确实要延迟到第一次使用时才创建"的场景才有意义，而大多数单例并没有这个需求。

## 一个具体例子

假设有一个配置中心，运行时可以推送新配置。最初版本是这样：

```java
class ConfigHolder {
    private volatile String endpoint;
    private volatile int timeoutMs;
    private volatile int retries;

    void update(String endpoint, int timeoutMs, int retries) {
        this.endpoint = endpoint;
        this.timeoutMs = timeoutMs;
        this.retries = retries;
    }
}
```

推送线程依次写三个 volatile 字段，业务线程读的时候可能读到"新 endpoint + 旧 timeout + 旧 retries"。每个字段都是最新的，但组合起来是错的。更糟的是，如果业务代码先读 `endpoint` 判断环境，再读 `timeoutMs`，它可能用新环境的地址配旧环境的超时。

改成不可变快照 + 单个 volatile 引用：

```java
final class Config {
    final String endpoint;
    final int timeoutMs;
    final int retries;

    Config(String endpoint, int timeoutMs, int retries) {
        this.endpoint = endpoint;
        this.timeoutMs = timeoutMs;
        this.retries = retries;
    }
}

class ConfigHolder {
    private volatile Config current = new Config("https://api.example.com", 3000, 2);

    void update(String endpoint, int timeoutMs, int retries) {
        current = new Config(endpoint, timeoutMs, retries);   // 一次引用写，整体替换
    }

    Config get() {
        return current;   // 一次引用读，拿到的一定是某一个完整版本
    }
}
```

这里有三层保证：

1. `Config` 的字段都是 `final`，构造完成后不会被改；
2. `current` 是 volatile，引用写有 release 语义，构造期间的写对读到它的线程可见；
3. 业务线程只读一次 `current`，拿到的对象要么是旧版本，要么是新版本，不会是新旧混合。

注意第 3 点：如果在业务方法里连续写两次 `current.getEndpoint()` 和 `current.getTimeout()`，而 `current` 在这两次之间被替换，你仍然可能读到两个版本。正确做法是先把 `Config c = holder.get();` 存到局部变量，后面都用 `c`。**"读一次快照"是使用不可变对象的基本纪律。**

如果更新操作可能并发（多个线程同时推送），`current = new Config(...)` 这种"读旧值、算新值、写回"的更新会丢更新。这时要用 `AtomicReference.updateAndGet` 或 `synchronized`：

```java
private final AtomicReference<Config> current = new AtomicReference<>(defaultConfig);

void updateTimeout(int timeoutMs) {
    current.updateAndGet(old -> new Config(old.endpoint, timeoutMs, old.retries));
}
```

`updateAndGet` 内部是 CAS 循环，保证每次更新都基于某个完整旧版本，不会把别人的修改覆盖掉。

## 检查一下

```quiz
{"id":"concurrency-05-quiz","title":"happens-before 三问","questions":[
 {"question":"线程 A 先执行 data = 42，再执行 volatile boolean ready = true；线程 B 读到 ready == true 后读 data，能保证看到 42 吗？","options":["能，volatile 写之前的普通写对读到它的线程可见","不能，普通变量永远不可见","只有当 data 也是 volatile 时才能","取决于 CPU 核数"],"answer":0,"explain":"程序次序 + volatile 规则 + 传递性，建立了 data 写与 B 的读之间的 happens-before。"},
 {"question":"volatile int count; 多线程执行 count++ 仍然可能丢更新，根本原因是？","options":["volatile 不保证可见性","count++ 是读-改-写三步，volatile 不保证复合操作原子","volatile 只对引用有效","JVM 会忽略 volatile"],"answer":1,"explain":"volatile 提供可见性与有限有序性，不提供原子性。"},
 {"question":"构造函数里把 this 放进一个静态列表，之后别的线程从列表里拿到它，final 字段的初始化安全还成立吗？","options":["成立，final 字段永远安全","不成立，this 在构造完成前逃逸，别的线程可能看到未冻结的对象","只对 static final 字段不成立","取决于字段是不是基本类型"],"answer":1,"explain":"final 的冻结发生在构造结束时；this 提前逃逸就可能在冻结前被看到。"}
]}
```

## 常见误区

### 误区：happens-before 就是"时间上先发生"

**你以为**：A 的代码先执行，就自然 happens-before B。
**其实**：happens-before 是**可见性与顺序保证**，不是墙上时钟。两个线程各跑各的，没有同步动作把它们连起来，就算 A 的写实际更早，B 也可能读不到。反过来，只要建立了 HB 边，即使两个线程在并行执行，B 也保证能看到 A 在边之前的写。
**怎么验证**：写一个没有 volatile/锁的标志位程序，让写线程 `sleep` 一秒再写，读线程忙等；在多数机器上读线程仍然可能永远看不到（或被 JIT 优化成死循环）。实际验证应使用 jcstress 这类工具，而不是靠"跑几次看看"。

### 误区：volatile 变量所在的对象，所有字段都跟着可见

**你以为**：`volatile Config config` 一加，`config` 里面的字段也自动线程安全。
**其实**：volatile 只作用于这个引用本身。构造完成后再发布，构造期间的字段写会跟着可见；但发布之后其他线程对字段的修改，仍然需要同步。如果对象是可变的，`config.timeout = 5000` 和读 `config.timeout` 之间没有 HB 边，照样是数据竞争。
**怎么验证**：让一个线程在对象发布后不停改字段，另一个线程读；去掉字段上的同步，观察是否读到旧值或撕裂值。更可靠的是用 jcstress 跑。

### 误区：加了 volatile 就不需要锁了

**你以为**：volatile 比锁轻，能替代锁。
**其实**：volatile 不提供互斥，也不提供复合操作原子性。计数器、先查后改、多个字段一起变、需要保护临界区的地方，都必须用锁或原子类。volatile 只能解决"一个线程写、其他线程读"的发布问题。
**怎么验证**：把一段 `synchronized` 保护的 `count++` 改成 `volatile`，用多线程压测，看结果是否小于预期。

### 误区：final 字段在任何情况下都安全

**你以为**：字段加了 `final`，怎么发布都不会看到默认值。
**其实**：初始化安全的前提是对象在构造完成后才被其他线程看到。构造函数里 `this` 逃逸（注册监听器、启动线程、放进静态集合、调用可重写方法）都会破坏这个前提。另外，通过反射或 `Unsafe` 修改 final 字段也不在保证范围内。
**怎么验证**：在构造函数里把 `this` 放进一个静态列表，另一个线程并发从列表读取并打印字段；在高并发下可以观察到默认值。这类问题同样建议用 jcstress 验证，不要靠肉眼。

## 小结

`volatile` 不是"轻量锁"，它只是 JMM 里的一条 release/acquire 边。它保证一个变量的写对后续读可见，并让写之前的普通写跟着可见；它不保证原子性，不保证多个变量一起变，也不保证写之后的普通写被发布。

真正要检查的是 happens-before：程序次序、锁、volatile、线程启动/终止/中断、final，再加上传递性。跨线程的每一次读写，要么被这些边连起来，要么就是数据竞争。数据竞争的程序，JMM 不承诺任何结果。

最后记住三句话：

1. **可见性用 volatile，原子性用锁或原子类，整体一致性用不可变快照。**
2. **先写数据，再写 volatile；先读 volatile，再读数据。**
3. **`final` 很便宜，但前提是构造期间别让 `this` 跑出去。**

下一篇讲并发容器与阻塞队列：`ConcurrentHashMap` 的 `size()` 为什么只是估计值，`CopyOnWriteArrayList` 为什么只适合读多写少，以及线程池的队列为什么会把内存吃光。
