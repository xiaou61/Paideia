---
title: synchronized：它到底锁住了什么
category: concurrency
order: 2
minutes: 24
level: intermediate
status: published
---

## 先把结论摆出来

`synchronized` 锁的是**一个对象**，不是一段代码：

- 加在实例方法上 → 锁 `this`；
- 加在静态方法上 → 锁 `Class` 对象；
- `synchronized (obj)` → 锁 `obj`。

**只有锁同一个对象的线程才互斥。** 两个方法各自锁各自的实例，等于没锁——这是线上最高频的并发 bug。

## 锁升级在升什么

JDK 6 之后 `synchronized` 不再是"一定走系统调用"，而是按竞争程度逐级加码：

| 状态 | 什么情况 | 代价 |
| --- | --- | --- |
| 无锁 | 没人争 | 几乎为零 |
| 偏向锁 | 只有同一个线程反复进 | 一次 CAS，之后免同步 |
| 轻量级锁 | 偶尔有第二个线程争 | 自旋 CAS |
| 重量级锁 | 争用明显 | 挂起线程，进内核 |

**这不是"可以手动选"的优化**——JVM 自己判断。知道它的意义是：**没有竞争时 `synchronized` 很便宜**，不需要为了性能换成 CAS。

（偏向锁在 JDK 15 起默认关闭、后续版本移除，因为它的撤销成本在真实服务里常常得不偿失。）

## 锁对象选错的三种典型

### 一、两个实例各锁各的

```text
class Counter {
    private int value;
    synchronized void inc() { value++; }   // 锁的是 this
}

// 两个 Counter 实例各自调用 inc()：完全并行
```

如果这个计数本该全局唯一，锁就形同虚设。要么改成静态方法（锁 Class），要么让所有调用方共享同一个实例。

### 二、锁了不同对象

```text
synchronized (this) { ... }       // A 方法
synchronized (Counter.class) { ... }  // B 方法
```

两把不同的锁，A 和 B 可以同时进。

### 三、锁的是会被重新赋值的引用

```text
private Object lock = new Object();
void reset() { lock = new Object(); }   // 换锁了，之前持有旧锁的线程和的新的不再互斥
```

锁对象必须是 `final`。

## 什么时候该锁，锁多久

判据只有一条：**这段代码访问的共享可变状态，是不是在别处也被访问**。

```text
// 需要锁：读-改-写 + 多线程可达
synchronized void inc() { value++; }

// 需要锁：先查再改（check-then-act）
synchronized void ensureCapacity(int n) {
    if (array.length < n) { array = Arrays.copyOf(array, n); }
}
```

锁的粒度越小越好，但**不要把同一个业务操作拆成两段各锁一次**——中间那点空隙恰好是别人插进来的地方（这就是"锁没锁住"的另一种表现）。

## 一个具体例子

```text
class Inventory {
    private int stock = 10;

    synchronized boolean take() {
        if (stock <= 0) return false;
        stock--;
        return true;
    }
}
```

`if (stock <= 0) return false; stock--;` 是典型的 check-then-act：查和改之间如果别的线程插进来，就会超卖。整段放进同一个 `synchronized` 才安全。

而如果拆成 `synchronized boolean has() { ... }` + `synchronized void dec() { ... }` 两个方法，**看起来都加锁了，合起来却不安全**。

## 检查一下

```quiz
{"id":"concurrency-02-quiz","title":"synchronized 三问","questions":[
 {"question":"两个不同实例的 synchronized 实例方法能同时执行吗？","options":["不能，synchronized 是全局互斥","能，它们锁的是各自的 this","取决于 JVM 版本","取决于方法是否 static"],"answer":1,"explain":"实例方法锁 this，不同实例是不同的锁。"},
 {"question":"把 check 和 act 拆成两个各自加锁的方法，会怎样？","options":["仍然安全","中间存在空隙，别的线程可以插进来","会死锁","会编译失败"],"answer":1,"explain":"两把锁分别保护两段，合起来不构成原子操作。"},
 {"question":"JDK 6 之后 synchronized 的实际情况是？","options":["每次都要系统调用","按竞争程度逐级升级，无竞争时很便宜","只能用一次","必须配合 volatile"],"answer":1,"explain":"偏向锁/轻量级锁让无竞争路径几乎无开销。"}
]}
```

## 常见误区

### 误区：方法上加了 synchronized 就线程安全

**你以为**：给方法加锁就够了。
**其实**：只有当"所有修改这个状态的路径都锁同一个对象"时才安全。少一处没加，或者别的类直接改了这个字段，就破了。
**怎么验证**：搜这个字段的所有写入点，确认每一处都在同一把锁里。

### 误区：锁越细性能越好

**你以为**：锁范围缩到最小就是好代码。
**其实**：把一次复合操作拆成两段各锁一次，会引入中间状态被看到的窗口。正确性优先于细粒度。
**怎么验证**：并发调用那个操作，看最终状态是否自洽（比如库存不会变成负数）。

### 误区：用了 ConcurrentHashMap 就整个类安全了

**你以为**：容器是线程安全的，我围绕它写的逻辑也安全。
**其实**：容器只保证**单个操作**原子。`if (!map.containsKey(k)) map.put(k, v);` 依然是 check-then-act，要用 `putIfAbsent`。
**怎么验证**：并发执行上面这段，统计是否出现覆盖。
