---
title: CAS 与原子类：无锁的代价
category: concurrency
order: 3
minutes: 22
level: intermediate
status: published
---

## 先把结论摆出来

CAS 是"**比较并交换**"：只有当变量还是我读到的那个旧值时，才把它改成新值；否则失败重来。

```code-flow
{
  "id": "concurrency-cas-retry",
  "title": "CAS：失败就重试的无锁循环",
  "nodes": [
    { "id": "read", "label": "读到旧值 old", "sub": "value.get()", "layer": 0, "order": 0 },
    { "id": "cas", "label": "CAS(old → next)", "sub": "比较并交换", "layer": 1, "order": 0 },
    { "id": "ok", "label": "成功退出", "sub": "旧值仍是 old", "layer": 2, "order": 0 },
    { "id": "fail", "label": "失败重试", "sub": "value 已被改", "layer": 2, "order": 1 },
    { "id": "spin", "label": "自旋烧 CPU", "sub": "竞争激烈时都在重试", "layer": 3, "order": 0 }
  ],
  "edges": [
    { "from": "read", "to": "cas", "label": "拿旧值去换" },
    { "from": "cas", "to": "ok", "label": "true" },
    { "from": "cas", "to": "fail", "label": "false" },
    { "from": "fail", "to": "read", "label": "重读再试" },
    { "from": "fail", "to": "spin", "label": "竞争时" }
  ],
  "steps": [
    {
      "title": "先读一次旧值",
      "desc": "记住当前值，算出新值",
      "file": "AtomicCounter.java",
      "code": "int old = value.get();     // 读到旧值\nint next = old + 1;        // 算出新值",
      "focus": [1, 2],
      "nodes": ["read"],
      "edges": [],
      "log": [{ "k": "前提", "v": "读和写之间不能有人插队" }],
      "explain": "CAS 的写法永远是“读一次、算好、再尝试写”，而不是先写后判断。"
    },
    {
      "title": "CAS 比较并交换",
      "desc": "旧值没变才写，变了就失败",
      "file": "AtomicCounter.java",
      "code": "boolean ok = value.compareAndSet(old, next);\n// 旧值仍然是 old 才写成功，否则返回 false，不阻塞",
      "focus": [1, 2],
      "nodes": ["read", "cas", "ok", "fail"],
      "edges": ["read>cas", "cas>ok", "cas>fail"],
      "log": [{ "k": "机制", "v": "一条 CPU 原子指令完成判断 + 写入" }],
      "explain": "“判断旧值 + 写入新值”合成一个硬件指令，中间没有窗口。失败不阻塞，只是返回 false。"
    },
    {
      "title": "失败就循环重试",
      "desc": "无锁的代价在这里",
      "file": "AtomicCounter.java",
      "code": "do {\n    int old = value.get();\n    int next = old + 1;\n} while (!value.compareAndSet(old, next));",
      "focus": [1, 2, 3, 4],
      "nodes": ["fail", "read"],
      "edges": ["fail>read"],
      "log": [{ "k": "模式", "v": "读 → CAS → 失败再读，直到成功" }],
      "explain": "AtomicInteger.incrementAndGet() 内部就是这条循环。低竞争时比加锁快，因为没有线程挂起/唤醒。"
    },
    {
      "title": "高竞争时全在自旋",
      "desc": "CPU 白烧，吞吐反而下降",
      "file": "AtomicCounter.java",
      "code": "// 同一时刻只有一个线程 CAS 成功\n// 其余全部失败重试，重试次数随线程数上升 → CPU 白烧\n// 高并发纯计数改用 LongAdder，把写分散到多个格子",
      "focus": [1, 2, 3],
      "nodes": ["spin"],
      "edges": ["fail>spin"],
      "log": [{ "k": "结论", "v": "写密集计数用 LongAdder，别死磕 AtomicLong" }],
      "explain": "无锁把“等待”换成了“重试”。竞争激烈时失败重试的线程全部在空转，吞吐可能比加锁还低。"
    }
  ]
}
```

`AtomicInteger.incrementAndGet()` 就是"读一次、CAS 一次、失败就再读再试"。它**不用锁**，但**不是没有代价**：

- 竞争激烈时大量线程在自旋，CPU 白烧；
- 只能保护**一个变量**的原子更新；
- 有 ABA 问题（值从 A 变 B 又变回 A，CAS 察觉不到）。

打个比方：CAS 像去柜台换零钱——先看柜台上的零钱还是你刚才看到的那张，是才换、不是就重新排队再看。没人排队时飞快，人一多就全在反复看。真实机制是：它用一条 CPU 的"比较并交换"原子指令完成"判断旧值 + 写入新值"，失败返回 false，由调用方循环重试。

## 为什么无锁反而可能更慢

```text
do {
    old = value.get();
    next = old + 1;
} while (!value.compareAndSet(old, next));
```

低竞争时这比加锁快得多（没有线程挂起/唤醒）。但**竞争激烈时，每次 CAS 只有一个线程成功**，其余的都要重试。重试次数随线程数上升，CPU 全花在这上面。

所以 JDK 8 加了 `LongAdder`：把计数分散到多个格子，最后求和。写散开了，冲突就小了。**读多写少的计数器用 `AtomicLong`，写多的高并发计数用 `LongAdder`。**

## ABA 问题长什么样

```text
线程 1: 读到 value = 100
线程 2: 把 value 改成 200，又改回 100
线程 1: CAS(100 → 101) 成功
```

线程 1 以为"没人动过"，其实中间被改过两次。对**数字计数**通常无害。对**链表/栈的节点引用**就是灾难——节点可能已经被弹出并回收，引用却还是那个地址。

解法是 `AtomicStampedReference`：给值加一个版本号，一起比。

## 什么时候该用原子类，什么时候不该

| 场景 | 用什么 |
| --- | --- |
| 单个计数器/标志位 | `AtomicInteger` / `AtomicBoolean` |
| 高并发纯计数（允许最终一致） | `LongAdder` |
| 多个变量要一起变 | **锁**（原子类做不到跨变量原子） |
| 先判断再修改（依赖别的状态） | **锁**（或者 `computeIfAbsent` 这类容器提供的方法） |

判断标准还是那一句：**这个更新能不能表达成"一个变量的比较并交换"**。能，CAS 够；不能，用锁。

## 一个具体例子

```text
// 错：两个线程同时进来，都读到 1，都写 2
if (a.get() == 1) {
    a.set(2);
}

// 对：只有读到 1 的那个线程能写成功
a.compareAndSet(1, 2);
```

第一版把"判断"和"写入"分成了两步，中间就是窗口。CAS 把这两步合成一个硬件指令。

## 检查一下

```quiz
{"id":"concurrency-03-quiz","title":"CAS 三问","questions":[
 {"question":"CAS 在竞争激烈时可能比锁慢，原因是？","options":["CAS 要进内核","失败要重试，线程在自旋里空烧 CPU","CAS 不支持多核","CAS 会阻塞"],"answer":1,"explain":"同一时刻只有一个线程能成功，其余重试。"},
 {"question":"LongAdder 比 AtomicLong 更适合什么？","options":["读多写少的计数器","写非常多的高并发计数","需要精确读取中间值","需要跨变量原子"],"answer":1,"explain":"LongAdder 把写分散到多个格子，降低冲突。"},
 {"question":"AtomicInteger 能保证两个字段一起原子更新吗？","options":["能","不能，只能保证单个变量的原子性","取决于 CPU","加 volatile 就能"],"answer":1,"explain":"跨变量的原子性仍然要靠锁。"}
]}
```

## 常见误区

### 误区：无锁一定比有锁快

**你以为**：不用锁就没有开销。
**其实**：无锁把"等待"换成了"重试"。高竞争下大量 CPU 花在失败重试上，吞吐反而更低。
**怎么验证**：100 个线程对同一个 `AtomicLong` 各加 100 万次，对比每线程一个计数器的版本。

### 误区：用了原子类就不用管复合逻辑

**你以为**：变量是原子的，围绕它的代码就安全。
**其实**：`if (a.get() == 1) a.set(2);` 依然有窗口——两个操作之间别的线程可以插进来。
**怎么验证**：并发跑这段，统计是否出现"本该只发生一次"的变化发生两次。

### 误区：计数场景一律 AtomicLong

**你以为**：原子类都差不多。
**其实**：写极密集的计数用 `LongAdder` 明显更快；但它读取时是求和，**拿不到某一刻的精确快照**。
**怎么验证**：压测两种实现，同时观察读取到的值是否满足业务对精度的要求。
