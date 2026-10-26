---
title: 可见性与有序性：volatile 解决什么
category: concurrency
order: 1
minutes: 22
level: intermediate
status: published
---

## 先把结论摆出来

`volatile` 只做两件事：

1. **可见性**：一个线程写了，别的线程立刻能看到最新值；
2. **禁止特定重排序**：挡住它前后那几条指令被编译器/CPU 调换顺序。

它**不保证原子性**。`volatile int count; count++` 依然是读、加、写三步，两个线程交错照样丢更新。

需要"一次做完"的地方用锁或原子类；只关心"别人能不能马上看见"的地方才用 `volatile`。

## 为什么会有"看不见"这回事

每个线程有自己的工作内存（可以理解成寄存器和 CPU 缓存的抽象）。线程改了变量，可能只写在自己的缓存里，还没同步回主存。

于是：

```text
线程 A: flag = true;      // 写在 A 的缓存里
线程 B: while (!flag) {}  // 一直读自己缓存里的旧值，永远退出不了
```

这不是"理论问题"——线上表现为**服务停了但线程不退出**、**配置改了但某个线程还用旧值**。

加 `volatile` 后，写操作会马上对其他线程可见，读也强制从主存取。

## 重排序是什么

编译器和 CPU 都会为了让流水线跑满而调换指令顺序，前提是"单线程看起来结果不变"。

```text
// 源码顺序
a = 1;
ready = true;

// 可能被排成
ready = true;
a = 1;
```

单线程无所谓。但如果有另一个线程看到 `ready == true` 就去读 `a`，它可能读到 0。

`volatile` 挡住了这种调换：对 `volatile` 变量的读写前后有内存屏障，屏障内的指令不越界。

## 一个具体例子：双重检查锁为什么要 volatile

```text
public class Config {
    private static volatile Config instance;   // 少了 volatile 就是错的
    public static Config getInstance() {
        if (instance == null) {
            synchronized (Config.class) {
                if (instance == null) {
                    instance = new Config();
                }
            }
        }
        return instance;
    }
}
```

`instance = new Config()` 不是一步，而是三步：分配内存、初始化对象、把引用赋给 `instance`。重排序可能把"赋引用"提到"初始化"之前——另一个线程在第一个 `if` 那里看到 `instance != null`，直接返回一个**还没初始化完的对象**。

`volatile` 禁止了这个重排序。这是这条规则最经典的出处。

## 什么时候够用，什么时候不够

| 场景 | volatile 够吗 |
| --- | --- |
| 一个线程写、多个线程读的开关/配置 | 够 |
| 计数器、累加 | **不够**，要原子类或锁 |
| 多个变量要一起变（写一半不能被看到） | **不够**，要锁 |
| 双重检查锁的单例引用 | 够（且必须加） |

判断标准：**这个操作是不是"一次完成"**。是"赋值一个值"，`volatile` 够；是"读-改-写"或多步联动，不够。

## 检查一下

```quiz
{"id":"concurrency-01-quiz","title":"可见性三问","questions":[
 {"question":"volatile int count; 十个线程各 count++ 一万次，最终结果？","options":["恰好 10 万","小于 10 万","大于 10 万","一定报错"],"answer":1,"explain":"count++ 是读-改-写三步，volatile 不保证原子性，会丢更新。"},
 {"question":"双重检查锁的单例，instance 为什么要加 volatile？","options":["为了性能","因为 new 对象的三步可能重排序，别的线程会拿到未初始化完的对象","为了防止空指针","因为静态变量必须有"],"answer":1,"explain":"分配、初始化、赋引用可能被调换顺序。"},
 {"question":"volatile 不保证下面哪一项？","options":["可见性","禁止重排序","原子性","读到的值是最新的"],"answer":2,"explain":"原子性要靠锁或原子类。"}
]}
```

## 常见误区

### 误区：volatile 是更快的锁

**你以为**：`volatile` 能替代 `synchronized`，只是更轻量。
**其实**：它只解决"看不见"，不解决"交错执行"。复合操作照样出错。
**怎么验证**：用 `volatile` 计数器跑并发累加，对比 `AtomicLong` 的结果。

### 误区：CPU 缓存会自己同步，不用管

**你以为**：硬件会自动保证一致，程序里不用写 `volatile`。
**其实**：硬件保证的是缓存一致性协议层面的最终同步，**不保证你读到的是"什么时候"的值**，也不限制编译器重排序。Java 的规则是"没有同步就没有跨线程可见性保证"。
**怎么验证**：一个线程写标志位，另一个线程忙等；不加 `volatile` 时偶发不退出。

### 误区：加了 volatile 就不用考虑顺序了

**你以为**：`volatile` 一加，整段代码都按源码顺序执行。
**其实**：它只挡住**与这个 volatile 变量相关**的重排序。两个普通变量之间的顺序仍可能被调换。
**怎么验证**：把双重检查锁的 `volatile` 去掉，看是否偶发拿到半初始化对象。
