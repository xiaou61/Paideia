---
title: 11 · Spring AOP
order: 11
chapter: 横切
level: intermediate
category: spring-from-zero
prerequisite: 10 · 手写动态代理
minutes: 20
status: published
---

# 11 · Spring AOP

## 一条日志有、一条日志没有

给业务类加了个记日志的切面，`outer()` 上有，`inner()` 上没有——可 `inner()` 上也标了同一个注解：

```text
ASPECT SelfService.outer()
CALL self.outer=inner
```

第一行是切面打的，第二行是 `outer()` 里调 `inner()` 时打的。`inner()` 那一次，切面完全没进来。

它既不是 `private`，也不是 `final`，更不是 `static`。它只是被**同一个类里的另一个方法**调用了：

```java
@Service
public class SelfService {

    @Loggable
    public String outer() {
        return "self.outer=" + inner();   // 第 10 课那个 this 的坑，原样搬到了 AOP
    }

    @Loggable
    public String inner() {
        return "inner";
    }
}
```

第 10 课你已经见过这个：`this.inner()` 里的 `this` 是原始对象，不是代理对象，所以调用根本没经过代理——也就谈不上被切面拦下来。

**这一课的主线就是这个：先判断「这次调用有没有经过代理」，再谈「切点写对没写对」。**

## 反射只能回答一半问题

网上常见的一个排查办法是：用反射看目标方法能不能被覆盖。

<!-- run -->
```java
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class Main {

    static class Service {
        public String fast() {
            return "fast";
        }

        public final String pinned() {
            return "pinned";
        }

        private String hidden() {
            return "hidden";
        }

        public static String util() {
            return "util";
        }
    }

    static final class Frozen {
    }

    static boolean overridable(Method method) {
        int modifiers = method.getModifiers();
        return !Modifier.isFinal(modifiers) && !Modifier.isPrivate(modifiers)
                && !Modifier.isStatic(modifiers);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("fast 能被覆盖: " + overridable(Service.class.getDeclaredMethod("fast")));
        System.out.println("pinned 能被覆盖: " + overridable(Service.class.getDeclaredMethod("pinned")));
        System.out.println("hidden 能被覆盖: " + overridable(Service.class.getDeclaredMethod("hidden")));
        System.out.println("util 能被覆盖: " + overridable(Service.class.getDeclaredMethod("util")));
        System.out.println("Service 能被子类化: " + !Modifier.isFinal(Service.class.getModifiers()));
        System.out.println("Frozen 能被子类化: " + !Modifier.isFinal(Frozen.class.getModifiers()));
    }
}
```
```text
fast 能被覆盖: true
pinned 能被覆盖: false
hidden 能被覆盖: false
util 能被覆盖: false
Service 能被子类化: true
Frozen 能被子类化: false
```

这段代码有用，但它回答的是**「CGLIB 能不能覆盖这个方法」**，不是「这次调用会不会被 AOP 拦下来」。差在哪？

同一个 `final` 方法，换一种代理方式就被拦了。我在一个临时 Boot 4.1.1 工程里，把 `spring.aop.proxy-target-class` 改掉后跑到的日志：

```text
默认 CGLIB：
CALL finalMethod=final-method              <- 没有 ASPECT

--spring.aop.proxy-target-class=false（走 JDK 接口代理）：
ASPECT Greeter.greet()
CALL greeter=interface-final               <- 被拦了
```

`final` 只挡住「继承出一个子类来覆盖它」这一条路。如果 Bean 是靠实现接口生成代理的，调用入口本来就是接口上的方法，`final` 就管不着了。

所以判断顺序应该是这三问，反射只覆盖第二问：

1. **这次调用经过了代理对象吗**（自调用、`this`、内部 `new` 出来的对象 → 没经过，后面都不用看了）；
2. **代理能覆盖这个方法吗**（`final` 类 / `final` 方法 / `private` / `static` / 包级可见且在别的包）；
3. **切点匹配上了吗**（注解在不在、包路径对不对、`@within` 是否覆盖到这个类）。

只查第二问，就是只看了三分之一。

## 五种通知，边界差别比名字重要

| 注解 | 什么时候执行 | 拿得到返回值吗 | 能改变结果吗 |
| --- | --- | --- | --- |
| `@Before` | 方法之前 | 不能 | 不能（可以抛异常中断） |
| `@AfterReturning` | 正常返回后 | 能，要写 `returning = "x"` | 不能换返回对象，但能改它的字段 |
| `@AfterThrowing` | 抛异常后 | 拿到异常，要写 `throwing = "e"` | 不能吞掉（要吞用 `@Around`） |
| `@After` | 无论正常还是异常，等价于 `finally` | 不能 | 不能 |
| `@Around` | 全包 | 能 | 能（改返回值、吞异常、重试、改参数） |

表格里那两个 `returning` / `throwing` 不是可选项，少了它们参数绑不上：

```java
@AfterReturning(pointcut = "@annotation(com.example.common.Loggable)", returning = "result")
public void afterReturn(Object result) {
    System.out.println("返回: " + result);
}

@AfterThrowing(pointcut = "@annotation(com.example.common.Loggable)", throwing = "ex")
public void afterThrow(RuntimeException ex) {
    System.out.println("异常: " + ex.getMessage());
}
```

`@Around` 能力最强，也最容易写出「不报错、什么都没干」的东西：

```java
@Around("@annotation(com.example.common.Loggable)")
public Object log(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.nanoTime();
    try {
        return joinPoint.proceed();          // 少了这一行，目标方法压根不执行
    } finally {
        long cost = (System.nanoTime() - start) / 1_000_000;
        System.out.println(joinPoint.getSignature().toShortString() + " " + cost + "ms");
    }
}
```

`getTarget()` 常被误用成 `proceed()`：它只是把目标对象取出来，不执行方法，返回类型还是那个业务对象——接口会拿到一个错的值，而且不报错。这就是下面动手题。

## 切点表达式：常用的三种

```java
@Around("execution(* com.example.order.service..*.*(..))")   // 按包
@Around("@annotation(com.example.common.Loggable)")          // 按方法注解
@Around("@within(org.springframework.stereotype.Service)")    // 按类注解
```

| 写法 | 含义 |
| --- | --- |
| `*` | 任意一个 |
| `..` | 任意多个层级 / 任意多个参数 |
| `(..)` | 任意参数 |
| `(Long, ..)` | 第一个参数是 Long，后面任意 |

`..*.*(..)` 拆开读是：`..` 包下任意层级 → `*` 任意类 → `.` → `*` 任意方法 → `(..)` 任意参数。

按注解切比按包路径切好在**行为跟着代码走**：加个注解就登进名单，删掉就退出，改包名不会静默失效。

但 `@within(@Service)` 要把代价说清楚：它会把**这个类里所有方法**都切中，包括你只是想内部用的辅助方法、包括 `equals`/`toString` 之外的 getter。日志切面通常无所谓，可一旦切面里有计数、有耗时统计、有鉴权判断，范围大一圈就全是噪声。它在 `com.example..` 这种包根下还会把配置类、转换器一起扫进来。

## 切在哪一层：三个更正

| 层 | 适合切什么 | 不适合切什么 |
| --- | --- | --- |
| Controller | 统一日志（入参、状态码、耗时） | 事务 |
| Service | 事务、缓存、重试 | 依赖具体响应格式的日志 |
| Repository | 慢 SQL 统计 | 业务规则 |

**更正一：事务「通常」在 Service，不是「必须」。** 第 2 课的原话是「事务边界通常就是一个 Service 方法」。`@Transactional` 标在 Repository 上也能工作，标在 Controller 上也能编译、也能回滚，只是事务范围会连参数解析和响应序列化一起包进去，通常不是你想要的。别把它记成框架限制，它是设计约定。

**更正二：权限不要只靠切面。** 「切在 Service 就安全了」不成立——Service 方法同样会被自调用绕过，定时任务、消息消费者、直接调 Repository 的路径也全部绕过。权限交给 Spring Security 的方法授权（`@PreAuthorize`），它同样走代理、也会被自调用绕过，所以真正的护栏是：**任何入口都要经过被代理的那一层，并且给每条绕过路径写测试**。手写切面适合做审计和补充校验，不适合当唯一边界。

**更正三：`@Around` 吞异常是否影响事务，取决于顺序。** 只有切面在事务拦截器**内侧**时，异常先到切面、被吞掉后事务才看不见。如果 `@Around` 在事务外层，事务拦截器已经看到异常并标记回滚，外层再 catch 也回不去。这正好接下一节。

## 顺序：`@Order` 加在切面类上

一个方法被多个切面切中时，顺序由 `@Order` 决定（数字小的在外层）：

```java
@Aspect
@Component
@Order(1)                     // 注意：加在切面类上，不是加在 @Around 方法上
public class AuthAspect { }

@Aspect
@Component
@Order(2)
public class TxAspect { }
```

```text
权限（Order 1，最外层）→ 事务（Order 2）→ 日志（Order 3）→ 目标方法
```

权限在最外层，意味着没权限的请求连事务都不开——这是对的，也是这件事值得单独讲的原因。

另外两个常被忽略的点：

- `@Around` 里有副作用（打日志、发指标）时，它会对**每一次**调用发生，包括后面被回滚的那些。数字会偏，自己心里要有数。
- 别在切面里直接写库。它会跟着同一事务提交或回滚，而且传播行为、只读标记、异常吞没都会掺进来，很难推理。

## 动手

下面这个切面写好了，但接口拿到的返回值不对，日志也一条不打。找出最该改的那一行：

```code-challenge
{
  "id": "spring-11-bug",
  "kind": "bug",
  "title": "切面一条日志都不打",
  "code": "@Aspect\n@Component\npublic class TimingAspect {\n\n    @Around(\"@annotation(com.example.common.Loggable)\")\n    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {\n        long start = System.nanoTime();\n        Object result = joinPoint.getTarget();\n        System.out.println(joinPoint.getSignature().toShortString()\n                + \" \" + (System.nanoTime() - start) / 1_000_000 + \"ms\");\n        return result;\n    }\n}",
  "bugLine": 8,
  "explain": "第 8 行调的是 `getTarget()`，它只是把目标对象取出来，不会执行目标方法——而且返回值类型还是个业务对象，接口会拿到一个错误的值。必须改成 `Object result = joinPoint.proceed();`。`proceed()` 是唯一能让目标方法跑起来的入口，漏了它，切面就变成最坏的那种拦截器：不报错、什么都不干。"
}
```

**提示**：`@Around` 与别的通知不同，它得自己决定「目标方法要不要执行」。

## 检查一下

```quiz
{
  "id": "spring-11-quiz",
  "title": "AOP 三问",
  "questions": [
    {
      "question": "一个标了 @Loggable 的 public 方法，切面在同类另一个方法里被 this 调用，为什么没日志？",
      "options": ["调用没经过代理对象，切面根本没被触发", "方法不是 final，所以不能被代理", "切点表达式写错了"],
      "answer": 0,
      "explain": "this 指向原始对象，绕过了代理。反射查修饰符查不出这个问题——这是「这次调用有没有经过代理」那一问。"
    },
    {
      "question": "反射查出来 pinned 能被覆盖 = false，能不能由此断定这个 final 方法一定不会被 AOP 拦到？",
      "options": ["能，final 就是不能覆盖", "不能，走 JDK 接口代理时仍然可能被拦到", "不能，要看 JVM 版本"],
      "answer": 1,
      "explain": "final 只挡住「子类覆盖」。用 spring.aop.proxy-target-class=false 走接口代理时，调用入口是接口方法，final 方法照样被拦。"
    },
    {
      "question": "为什么推荐用 @annotation 而不是 execution 里的包路径？",
      "options": ["性能更好", "execution 不支持多参数", "包路径是字符串，重构不跟着改；注解跟着代码走"],
      "answer": 2,
      "explain": "包名或方法名一改，字符串切点就静默失效。按注解切，加/删注解就是开关。"
    }
  ]
}
```

## 还差的

- **AspectJ 编译期织入没试过。** 它能绕过自调用（因为不是代理，是真的改了字节码），代价是构建配置和 IDE 支持。我只有机制上的理解，没在项目里跑过。
- **`@Order` 与 `@Transactional` 传播行为的组合没实测。** 三层切面顺序不同时，回滚边界到底落在哪，我只按文档推导。
- **`@within` 的扫描范围副作用没量化。** 我只知道它会多切，具体多切几个方法、日志涨多少没测。
- **权限绕过路径没有穷举。** 我列了自调用、定时任务、消息消费、直调 Repository 四条，实际项目里还有内部管理工具、测试夹具。

下一课：把这一层直接套在「事务」上，然后逐个复现四种失效。
