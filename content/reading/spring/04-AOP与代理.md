---
title: AOP 与代理
category: spring
order: 4
minutes: 24
level: intermediate
status: published
---

## 先把结论摆出来

**Spring 的 AOP 不修改你的字节码，它在运行时给你造一个"替身"，让调用先经过替身。**

```sketch
{
  "title": "不改字节码，只造替身",
  "rows": [
    [
      { "id": "caller", "title": "调用方", "sub": "以为自己调的是原对象", "tone": "gray" },
      { "id": "proxy", "title": "运行时代理", "sub": "子类或接口实现", "tone": "blue" },
      { "id": "target", "title": "原对象", "sub": "字节码一个字节没改", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "caller", "to": "proxy" },
    { "from": "proxy", "to": "target" }
  ]
}
```

这个替身叫代理（proxy）。代理和你的类实现同一个接口（或继承你的类），外部拿到的其实是代理。代理在真正方法的前后插一段逻辑——这就是切面。

由此可以推出本篇最重要的一条：

> **只有"从外部经过代理"的调用才会被增强。方法内部 `this.xxx()` 这种调用绕过了代理，所以切面失效。**

这不是 bug，是代理机制的直接后果。`@Transactional`、`@Async`、`@Cacheable` 全都一样。

## 切面是怎么织进去的

织入（weaving）指"把切面逻辑插进目标方法的调用链"。时机有三种：编译期、类加载期、运行期。

Spring AOP 用**运行期织入**，位置是 `BeanPostProcessor.postProcessAfterInitialization`——bean 初始化完之后，检查有没有需要增强的方法，有就把返回值替换成代理。所以容器里登记的是"代理"，不是原始对象。这也解释了第二篇那句话：`@PostConstruct` 执行时代理还不存在。

概念对应：

| 概念 | 是什么 | 代码里长什么样 |
| --- | --- | --- |
| 切面（Aspect） | 一组增强逻辑 | `@Aspect` 标注的类 |
| 连接点（Join point） | 可以插逻辑的位置 | 方法执行（Spring 只支持这一种） |
| 切点（Pointcut） | 挑出哪些连接点 | `@Around("execution(* com.x..*Service.*(..))")` |
| 通知（Advice） | 插什么、插在哪 | `@Before` / `@After` / `@Around` |
| 目标对象（Target） | 被增强的原对象 | 你的 `OrderService` |

一个完整例子：

```java
@Aspect
@Component
public class TimingAspect {

    @Around("execution(* com.example.service..*.*(..))")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            return pjp.proceed();          // 调真正的目标方法
        } finally {
            long cost = (System.nanoTime() - start) / 1_000_000;
            System.out.println(pjp.getSignature() + " 用了 " + cost + "ms");
        }
    }
}
```

`pjp.proceed()` 就是"放行"。`@Around` 不调它，目标方法根本不会执行。

## JDK 动态代理 vs CGLIB

Spring 需要"造一个替身"，有两条路：

| | JDK 动态代理 | CGLIB |
| --- | --- | --- |
| 前提 | 目标类**实现了接口** | 目标类**没有接口**也行 |
| 原理 | 生成一个实现同接口的类，持有目标对象，转发调用 | 生成目标类的**子类**，覆写方法 |
| 限制 | 只能代理接口里声明的方法 | 不能代理 `final` 类、`final` 方法、`private` 方法 |
| 依赖 | JDK 自带 | 需要额外库（Spring Boot 已内置） |

Spring Boot 2.x 起**默认全部用 CGLIB**（`spring.aop.proxy-target-class=true`），即使有接口也走 CGLIB。
原因是 JDK 代理只能注入接口类型。一个类实现了接口但你在别处按类类型注入，JDK 代理会报类型不匹配。统一用 CGLIB 省掉这类问题。代价是：**`final` 和 `private` 方法代理不了**——CGLIB 靠继承覆写，`final` 不能覆写，`private` 子类看不见。

这就是 `@Transactional` 写在 `private` 方法上完全无效的原因：代理连覆写的机会都没有。

怎么确认用的是哪种：

```java
@PostConstruct
public void probe() {
    System.out.println(orderService.getClass());
    // class com.example.OrderService$$EnhancerBySpringCGLIB$$1a2b3c
    // 或 class jdk.proxy2.$Proxy42
}
```

类名带 `$$EnhancerBySpringCGLIB$$` 是 CGLIB，带 `$Proxy` 是 JDK 代理。

## 为什么同类内部调用切面失效

这是本篇的核心，也是最高频的面试题。

看这段代码：

```java
@Service
public class OrderService {

    public void createOrder(Order order) {
        validate(order);
        saveOrder(order);       // 直接调本类方法
    }

    @Transactional
    public void saveOrder(Order order) {
        orderRepository.save(order);
    }
}
```

调用 `createOrder()` 时发生了什么：

```text
外部调用者
   ↓
[代理] createOrder 有没有切面 → 没有 → 直接转发
   ↓
[真对象] createOrder() 执行
   ↓
this.saveOrder()   ← this 是"真对象"，不是代理
   ↓
[真对象] saveOrder() 执行，没有事务
```

关键在 `this`：真对象内部的 `this` 指向真对象自己，不是代理。所以 `this.saveOrder()` 只是一次普通 Java 方法调用，代理完全不知道。

**一句话：切面挂在代理上，`this` 绕过了代理。** 三种解法：

**解法一，把方法拆到另一个 bean 里。** 这是最干净的。

```java
@Service
public class OrderService {
    private final OrderWriter writer;

    public void createOrder(Order order) {
        validate(order);
        writer.saveOrder(order);   // 跨 bean 调用，经过 writer 的代理
    }
}

@Service
public class OrderWriter {
    @Transactional
    public void saveOrder(Order order) { ... }
}
```

**解法二，注入自己。**

```java
@Service
public class OrderService {
    @Autowired
    @Lazy
    private OrderService self;      // 注入的是代理

    public void createOrder(Order order) {
        validate(order);
        self.saveOrder(order);      // 经过代理
    }
}
```

`@Lazy` 是为了避开构造期的循环依赖。能用，但读代码的人会愣一下，记得写注释。
**解法三，用 `AopContext.currentProxy()`**：需开 `@EnableAspectJAutoProxy(exposeProxy = true)`，然后 `((OrderService) AopContext.currentProxy()).saveOrder(order)`。耦合框架 API，不推荐。

优先选解法一，它同时改善了设计——"写订单"本来就是独立职责。

## 一个自检的方法

怀疑某个方法没走切面时，在方法体第一行打 `TransactionSynchronizationManager.isActualTransactionActive()`。返回 `false` 就说明没有事务上下文，问题在代理，不在 SQL——比翻日志快得多。

## 检查一下

```quiz
{"id":"spring-04-quiz","title":"AOP 与代理三问","questions":[
 {"question":"同类内部直接调用带 @Transactional 的方法，为什么事务不生效？","options":["因为方法不是 public","因为 this 指向真对象，绕过了代理","因为事务注解需要继承","因为 Spring 不支持嵌套调用"],"answer":1,"explain":"切面挂在代理上；真对象内部的 this 调用不经过代理，拦截器没有机会介入。"},
 {"question":"Spring Boot 2.x 起默认用哪种代理？","options":["JDK 动态代理","CGLIB","Javassist","ByteBuddy"],"answer":1,"explain":"默认 proxy-target-class=true，即使有接口也用 CGLIB 生成子类。"},
 {"question":"@Transactional 写在 private 方法上为什么无效？","options":["private 方法执行更快","CGLIB 靠继承覆写，无法覆写 private 方法","Spring 会忽略所有注解","private 方法不能被调用"],"answer":1,"explain":"CGLIB 生成子类覆写方法，private 方法子类不可见，代理无从介入。"}
]}
```

## 常见误区

### 误区：`@Transactional` 加在接口方法上就一定能生效

**你以为**：写在接口上，实现类自然继承这个能力。
**其实**：Spring 官方明确建议把 `@Transactional` 写在**实现类**上。接口上的注解在某些代理方式下不会被读取，行为依赖配置，属于"有时行有时不行"。
**怎么验证**：把注解从实现类挪到接口，用 `isActualTransactionActive()` 检查，再换回来对比。

### 误区：切面是按"调用栈"生效的，所以嵌套调用也会被拦

**你以为**：只要方法被调用，不管从哪调，切面都能拦到。
**其实**：切面只认"调用是否经过代理对象"。同一对象内部的调用是普通方法调用，不经过代理；跨 bean 调用才经过。
**怎么验证**：在 `createOrder` 里调 `saveOrder`，两处各打一行 `getClass()`。外层是代理类，内层是真类名。

### 误区：CGLIB 代理能增强所有方法

**你以为**：既然生成了子类，那所有方法都被覆写了。
**其实**：`final` 方法不能被覆写，`private` 方法子类看不见，`static` 方法属于类不属于实例——这三类都代理不了，加注解也白加。
**怎么验证**：把 `@Transactional` 方法标成 `final`，启动不报错但事务不生效。这类"静默失效"最难查，所以别给业务方法加 `final`。

## 小结

Spring AOP 是运行期生成代理，在 `BeanPostProcessor` 阶段替换掉原对象。JDK 代理靠接口，CGLIB 靠继承，Spring Boot 2.x 起默认 CGLIB。切面只对"经过代理的调用"生效，所以同类内部 `this.xxx()` 会失效——解决办法是把方法拆到另一个 bean。

下一篇讲 `@Transactional`，它就是一个切面，失效原因几乎都能归到本篇两条：代理没被经过，或异常没抛出去。
