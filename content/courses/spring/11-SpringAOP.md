---
title: 11 · Spring AOP
order: 11
chapter: 横切
level: intermediate
category: spring-from-zero
prerequisite: 10 · 手写动态代理
minutes: 30
status: published
---

# 11 · Spring AOP

## 这一课你要会

- 写一个能生效的 `@Aspect`，并知道 `@Around` / `@Before` / `@AfterReturning` / `@AfterThrowing` 的边界；
- 读懂最常用的三种切点表达式；
- 用反射判断"一个方法能不能被代理"，从而在动手之前就知道会不会失效；
- 决定切在哪一层：为什么切 Controller 通常不是好主意。

## 为什么

有了第 10 课的代理，AOP 剩下的只是"怎么描述**要切哪些方法**"。

但真实项目里的问题往往不是"会不会写切面"，而是"为什么这个切面只对一半方法生效"。答案基本都在同一个地方：**目标方法能不能被覆盖**。这一课给你一个两分钟就能查清的办法。

## 讲解

### 切点表达式：三种够用了

```java
// 1. 按包 + 任意方法
@Around("execution(* com.example.order.service..*.*(..))")

// 2. 按注解
@Around("@annotation(com.example.common.Loggable)")

// 3. 按类上的注解（整个类的所有方法）
@Around("@within(org.springframework.stereotype.Service)")
```

`execution` 的结构是 `返回类型 包.类.方法(参数)`：

| 写法 | 含义 |
| --- | --- |
| `*` | 任意一个 |
| `..` | 任意多个层级 / 任意多个参数 |
| `(..)` | 任意参数 |
| `(Long, ..)` | 第一个参数是 Long，后面任意 |

`..*.*(..)` 拆开读是：`..` 包下任意层级 → `*` 任意类 → `.` → `*` 任意方法 → `(..)` 任意参数。

**优先用第 2、3 种**。`execution` 里的包路径是字符串，重构时不会跟着改；按注解切，加一个注解就生效，删掉就失效，行为跟着代码走。

### 五种通知的边界

| 注解 | 什么时候执行 | 拿得到返回值吗 | 能改变结果吗 |
| --- | --- | --- | --- |
| `@Before` | 方法之前 | 不能 | 不能（可以抛异常中断） |
| `@AfterReturning` | 正常返回后 | 能 | 不能改返回对象，但能改它的字段 |
| `@AfterThrowing` | 抛异常后 | 拿到异常 | 不能吞掉（要吞用 `@Around`） |
| `@After` | 无论正常还是异常 | 不能 | 不能 |
| `@Around` | 全包 | 能 | 能（改返回值、吞异常、重试、改参数） |

`@Around` 能力最强，也最容易写错：**必须调用 `proceed()`，否则目标方法根本不会执行**，你会得到一个"接口不报错、什么都没干"的诡异现象。

```java
@Around("@annotation(com.example.common.Loggable)")
public Object log(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.nanoTime();
    try {
        return joinPoint.proceed();                 // 少了这一行，目标方法不执行
    } finally {
        long cost = (System.nanoTime() - start) / 1_000_000;
        System.out.println(joinPoint.getSignature().toShortString() + " " + cost + "ms");
    }
}
```

### 先用反射判断会不会失效

第 10 课说了三条限制：`final` 类、`final` 方法、`private` 方法。这三条在**动手写代码之前**就能查——而且用的是纯 Java，不需要 Spring：

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
    }

    static final class Frozen {
    }

    static boolean overridable(Method method) {
        int modifiers = method.getModifiers();
        return !Modifier.isFinal(modifiers) && !Modifier.isPrivate(modifiers)
                && !Modifier.isStatic(modifiers);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("fast 能被代理: " + overridable(Service.class.getDeclaredMethod("fast")));
        System.out.println("pinned 能被代理: " + overridable(Service.class.getDeclaredMethod("pinned")));
        System.out.println("hidden 能被代理: " + overridable(Service.class.getDeclaredMethod("hidden")));
        System.out.println("Service 能被子类化: " + !Modifier.isFinal(Service.class.getModifiers()));
        System.out.println("Frozen 能被子类化: " + !Modifier.isFinal(Frozen.class.getModifiers()));
    }
}
```
```text
fast 能被代理: true
pinned 能被代理: false
hidden 能被代理: false
Service 能被子类化: true
Frozen 能被子类化: false
```

这段代码可以直接拿去排查你自己的类：把类名与方法名换掉，跑一次就知道 AOP 会不会生效。**比启动起来看日志快得多。**

注意 `static` 也在排除项里：静态方法属于类不属于实例，代理是实例层面的东西，覆盖不到——所以给 `static` 方法加 `@Transactional` 一定不生效。

### 切在哪一层

| 层 | 适合切什么 | 不适合切什么 |
| --- | --- | --- |
| Controller | 统一日志（入参、状态码、耗时） | 事务、权限（太外层，误伤） |
| Service | **事务**、缓存、重试、权限 | 依赖具体响应格式的日志 |
| Repository | 慢 SQL 统计 | 业务规则 |

事务必须在 Service：第 2 课说过，范围要覆盖"要么都成、要么都不成"的整体。

权限也一样——切在 Controller 上时，一个内部调用的批处理可能根本不经过 Controller，权限就被绕过了。**安全相关的横切，切在业务入口（Service）比切在协议入口（Controller）更可靠。**

### 顺序与副作用

一个方法被多个切面切中时，顺序由 `@Order` 决定（数字小的在外层）。这条在有"权限 + 事务 + 日志"三个切面时很关键：

```text
权限（Order 1，最外层）→ 事务（Order 2）→ 日志（Order 3）→ 目标方法
```

权限在最外层，意味着没权限的请求连事务都不开——这是对的。

`@Around` 里如果有副作用（打日志、发指标），要注意它会对**每一次**调用发生，包括被外层事务回滚的那些。别在切面里直接写库——那会进入同一个事务，跟着一起回滚或者一起提交，很难推理。

## 动手

下面这个切面写好了，但日志一条都不打。找出最该改的那一行：

```code-challenge
{
  "id": "spring-11-bug",
  "kind": "bug",
  "title": "切面一条日志都不打",
  "code": "@Aspect\n@Component\npublic class TimingAspect {\n\n    @Around(\"@annotation(com.example.common.Loggable)\")\n    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {\n        long start = System.nanoTime();\n        Object result = joinPoint.getTarget();\n        System.out.println(joinPoint.getSignature().toShortString()\n                + \" \" + (System.nanoTime() - start) / 1_000_000 + \"ms\");\n        return result;\n    }\n}",
  "bugLine": 8,
  "explain": "第 8 行调的是 `getTarget()`，它只是把目标对象取出来，不会执行目标方法——而且返回值类型还是个业务对象，接口会拿到一个错误的值。必须改成 `Object result = joinPoint.proceed();`。`proceed()` 是 \"让目标方法跑起来\" 的唯一入口，漏了它，切面就变成了拦截器最坏的那种写法：不报错、什么都不干。"
}
```

**提示**：`@Around` 与别的通知不同，它必须自己决定"目标方法要不要执行"。

## 检查一下

```quiz
{
  "id": "spring-11-quiz",
  "title": "AOP 三问",
  "questions": [
    {
      "question": "@Around 里忘了调 proceed()，会发生什么？",
      "options": ["启动报错", "目标方法不执行，接口返回 null 或你给的返回值", "切面不生效，目标照常执行"],
      "answer": 1,
      "explain": "proceed() 才是执行目标的唯一入口。不调它就是只包不执行，而且没有任何报错。"
    },
    {
      "question": "为什么推荐用 @annotation 而不是 execution 里的包路径？",
      "options": ["性能更好", "包路径是字符串，重构不跟着改；注解跟着代码走", "execution 不支持多参数"],
      "answer": 1,
      "explain": "包名或方法名一改，字符串切点就静默失效。按注解切，加/删注解就是开关。"
    },
    {
      "question": "权限校验切在 Controller 上，最大的风险是？",
      "options": ["性能差", "批处理、消息消费等入口不经过 Controller，权限被绕过", "日志太多"],
      "answer": 1,
      "explain": "横切要切在\"所有调用都会经过\"的地方。Controller 只是入口之一。"
    }
  ]
}
```

## 常见误区

### 误区：`@After` 就是"方法正常结束后"

**你以为**：`@After` 在正常返回之后执行。
**其实**：它等价于 `finally`——正常与异常都会执行。想只在正常返回后做事，用 `@AfterReturning`；只想在异常时做事，用 `@AfterThrowing`。
**怎么验证**：在 `@After` 与 `@AfterReturning` 里各打一条日志，让目标方法抛异常，看哪一条出现了。

### 误区：切面可以随便加，反正不影响业务

**你以为**：多一个切面只是多一层日志。
**其实**：每个切面都让调用多一次代理转发，更重要的是**它改变了调用链的语义**：`@Around` 能吞异常、能改返回值、能重试。一个写得随意的切面可以让"抛异常就回滚"变成"从不回滚"（`@Around` 里 catch 了异常没往外抛）。
**怎么验证**：让 `@Around` 里写 `try { return proceed(); } catch (Exception e) { return null; }`，再看事务还会不会回滚。

### 误区：切在 Controller 上就能覆盖所有业务

**你以为**：所有请求都经过 Controller，所以切这里最全。
**其实**：定时任务、消息消费者、内部管理工具、`CommandLineRunner` 都不经过 Controller。本项目里，SQL 沙箱、速递抓取、记忆提取这些任务全都是"没有 HTTP 入口"的路径。
**怎么验证**：`rg "@Scheduled|ApplicationRunner|CommandLineRunner"`，看你的切点覆盖了它们没有。

## 小结

AOP 剩下的事就是"描述切哪些方法"。优先按注解切，`@Around` 必须调 `proceed()`。`final` / `private` / `static` 三条限制可以用一段反射代码提前查清。横切要切在所有入口都会经过的那一层——事务和权限在 Service，统一日志可以在 Controller。

下一课：把这一层直接套在"事务"上，然后逐个复现四种失效。
