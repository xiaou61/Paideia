---
title: AOP 与代理
category: spring
order: 4
minutes: 20
level: intermediate
status: published
---

## 切面进来了，事务没开

下面这个类我在临时工程里跑过：`saveOrder()` 上标着 `@Transactional`，一个 `@Around` 把每次拦到的方法签名打出来，事务管理器是手写的桩，只在 `doBegin`/`doCommit` 里计数。配置走 `@EnableAspectJAutoProxy(proxyTargetClass = true)` 加 `@EnableTransactionManagement(proxyTargetClass = true)`。

```java
interface OrderApi { void createOrder(); void saveOrder(); void finalSave(); }

class OrderService implements OrderApi {
    @PostConstruct
    void init() {
        System.out.println("[init]      @PostConstruct 里 this.getClass() = " + this.getClass().getName());
    }

    @Override
    public void createOrder() {
        System.out.println("[createOrder] this.getClass() = " + this.getClass().getName());
        this.saveOrder();
    }

    @Override
    @Transactional
    public void saveOrder() {
        System.out.println("[saveOrder]  isActualTransactionActive="
                + TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Override
    @Transactional
    public final void finalSave() {
        System.out.println("[finalSave]  isActualTransactionActive="
                + TransactionSynchronizationManager.isActualTransactionActive());
    }
}
```

跑出来的（JDK 25.0.4 / Spring Framework 6.2.7，单文件 `java -cp` 启动，没有 Boot 应用；JUL 那一行本地化时间戳我删了，其余逐字照抄）：

```text
[init]      @PostConstruct 里 this.getClass() = OrderProbe$OrderService
[bean]      class = OrderProbe$OrderService$$SpringCGLIB$$0
[bean]      isAopProxy=true isCglibProxy=true isJdkProxy=false
-- 1) svc.createOrder()，内部 this.saveOrder()
[aspect]     OrderService.createOrder()
[createOrder] this.getClass() = OrderProbe$OrderService
[saveOrder]  isActualTransactionActive=false
-- 2) svc.saveOrder() 直接调
[aspect]     OrderService.saveOrder()
[saveOrder]  isActualTransactionActive=true
-- 3) svc.finalSave() 直接调
[finalSave]  isActualTransactionActive=false
-- 4) writer.saveOrder()：另一个 bean 调
[aspect]     OrderWriter.saveOrder()
[writer]     isActualTransactionActive=false
[aspect]     OrderService.saveOrder()
[saveOrder]  isActualTransactionActive=true
[tx]        begin=2 commit=2
```

（启动时还有一条 WARN，`CglibAopProxy.doValidateClass` 说 `finalSave` 是 final 方法代理不了，症状表那一节再贴。）

第 1 段和第 2 段调的是同一个方法、同一条注解，一个 false 一个 true。我第一反应是「注解读取失败」——不成立，否则第 2 段也不会开。差别只在这次调用是从哪儿进来的。

第 3 段更别扭：`finalSave` 连切面日志都没有，事务也没开。第 4 段里 `writer` 自己那行是 false，紧接着 `saveOrder` 是 true——因为 `writer` 自己没标注解，它调出去的那次经了代理。

## 容器里那个 bean 不是 OrderService

输出第 2 行：容器交出来的实例是 `OrderProbe$OrderService$$SpringCGLIB$$0`，一个运行时生成的子类。第 1 行的 `@PostConstruct` 里，`this.getClass()` 还是原始类——代理是在 bean 初始化完成之后的后处理阶段换上去的，初始化回调跑在它前面。

所以每次 `svc.xxx()` 实际走的是：代理子类 → 拦截链（切面 + 事务）→ 目标对象的方法。而在目标对象里写 `this.saveOrder()`，这个 `this` 就是目标对象自己，跟代理没有任何关系。

| 调用 | 切面拦到 | 事务 | 为什么 |
|---|---|---|---|
| `svc.createOrder()`，内部 `this.saveOrder()` | 只有 `createOrder` | false | 内层 `this` 是目标对象 |
| `svc.saveOrder()` | `saveOrder` | true | 从代理入口进 |
| `svc.finalSave()`（CGLIB） | 没有 | false | 子类覆写不了 final 方法 |
| `writer.saveOrder()` 里 `api.saveOrder()` | 两次都拦到 | writer 自己 false，`saveOrder` true | 每次跨过代理入口都拦一次 |

判断一次调用会不会进切面，就一句：它有没有从代理的入口进来。跟方法上写没写注解放没关系——注解是给拦截链读的，拦截链被跳过，注解就没人看。

## 判断手里的 bean 是不是代理，别靠类名

`AopUtils.isAopProxy` / `isCglibProxy` / `isJdkDynamicProxy`（输出第 3 行那三个值）是稳定的问法。类名不是：同一个 JDK 25、同一次运行，我只把接口的可见性换了一下——

```text
[public 接口 Runnable]   jdk.proxy1.$Proxy26 / module=jdk.proxy1
[包级接口 OrderApi]      $Proxy27 / module=null
```

一个落在 `jdk.proxy1` 模块里、一个没有模块，序号（26、27）只是这一次运行的结果，下次跑会变。CGLIB 那边同理：我量到的是 `$$SpringCGLIB$$0`，老文章里写成 `$$EnhancerBySpringCGLIB$$1a2b3c` 的是 Spring 5 及更早的命名。拿类名做判断条件，换个版本就断。

## 换成 JDK 接口代理，两个结论反过来

`@EnableAspectJAutoProxy` 不写参数时，Spring Framework 的默认值是 `proxyTargetClass = false`，有接口就走 JDK 接口代理。同一份 `OrderService` 换到这个默认值下再跑：

```text
[bean]      class = $Proxy25
[bean]      isAopProxy=true isJdkProxy=true
-- 按实现类类型取 bean
[getBean]   org.springframework.beans.factory.NoSuchBeanDefinitionException
[getBean]   No qualifying bean of type 'OrderProbe$OrderService' available
-- 3) api.finalSave() 直接调
[aspect]     OrderApi.finalSave()
[finalSave]  isActualTransactionActive=true
[tx]        begin=2 commit=2
```

（`[init]` 行和第 1 节重复，省掉；`createOrder` 那段和第 1 节一致，内层 `this.saveOrder()` 照样 false，这里跳过。）

两处反直觉：

- 按实现类类型取 bean，直接抛 `NoSuchBeanDefinitionException`——容器里只有 `OrderApi` 形状的对象。注入点声明成实现类，启动就失败。这点我猜错过：我以为会报类型不匹配，实际是找不到 bean。
- `finalSave` 这次反被拦住了。接口代理实现的是接口方法，不覆写实现类，`final` 不构成障碍。`begin=2` 也对得上：`saveOrder` 一次、`finalSave` 一次。

所以「final 方法拦不住」的限定条件是 CGLIB 子类代理，不是 Spring AOP 的通性。Boot 的默认值正是 CGLIB：`spring-boot-autoconfigure` 3.5.6 里，`spring.aop.proxy-target-class` 是 `matchIfMissing = true`，对应的配置类上写着 `@EnableAspectJAutoProxy(proxyTargetClass = true)`。我只读了 jar 里的字节码，没起 Boot 应用验证。

## 只有 public 才生效？这条现在不成立

一直有「`@Transactional` 只能写在 public 方法上」的说法。在 6.2.7 + CGLIB 下，我把三种可见性都试了一遍，调用方和被调方在同一个包：

```text
[bean] class = OrderProbe$VisibilityService$$SpringCGLIB$$0
-- public 方法
    [publicSave] isActualTransactionActive=true
-- protected 方法
    [protectedSave] isActualTransactionActive=true
-- 包级可见方法
    [packageSave] isActualTransactionActive=true
[tx]        begin=3 commit=3
```

private 方法从外面根本调不到，所以「没生效」这件事连测试都写不成——没有报错、没有日志，只是那段代码永远进不来。

版本得交代清楚：我手上只有 6.2.7。如果你维护 5.x 项目，看到 protected 方法不开事务，别拿这段去改代码——那是另一个版本的行为，我没跑过，见文末。

## 内层调用怎么也能经过代理

两条路，都跑过。

拆到另一个 bean：`writer` 拿到的是注入进去的代理，它调 `api.saveOrder()` 时从代理入口进，就是第 1 节输出里第 4 条那两行 `[aspect]` 加一个 true。这条路只依赖一件事——注入给你的是代理，不依赖容器怎么解析自引用。

注入自己：字段 `@Autowired SelfService self`，没加 `@Lazy`。

```text
===== 5. 字段注入自己（无 @Lazy）=====
[bean]      class = OrderProbe$SelfService$$SpringCGLIB$$0
[aspect]     SelfService.viaSelf()
[viaSelf]    self.getClass() = OrderProbe$SelfService$$SpringCGLIB$$0
[aspect]     SelfService.saveOrder()
[saveOrder]  isActualTransactionActive=true
[tx]        begin=1 commit=1
```

`self` 拿到的是代理，切面和事务都进来了。`@Lazy` 常被解释成「避开构造期循环依赖」的手段。这个场景里没复现出来：不加 `@Lazy` 也拿到了代理。构造器注入自引用是另一条路径，它会不会撞上构造期依赖，我没测。

还有一条容易被当成「注解没生效」：切面里不调 `proceed()`，目标方法压根不执行，调用方收到的是切面自己的返回值。

```text
===== 6. @Around 不调 proceed() =====
    [aspect]  这次不调 proceed()
[caller]    收到: 被切面换掉了
```

目标方法里本来会打一行 `[target] hello() 真的执行了`；它没出现，就是目标方法没跑。

## 症状不同，先看的地方也不同

| 症状 | 先看哪里 | 实测信号 |
|---|---|---|
| 启动直接失败 | 类是不是 final、是不是不可见 | `java.lang.IllegalArgumentException: Cannot subclass final class OrderProbe$FrozenService`；refresh 日志里还有一句 `Could not generate CGLIB subclass of class ... Common causes of this problem include using a final class or a non-visible class` |
| 启动打一条 WARN，应用照跑 | 那个方法是不是 final | `Unable to proxy interface-implementing method [public final void OrderProbe$OrderService.finalSave()] because it is marked as final, consider using interface-based JDK proxies instead.`（出自 `CglibAopProxy.doValidateClass`，不是异常） |
| 注解像是没生效，方法确实被调了 | 调用方是不是同一个类里的 `this` | 切面日志少一行，`isActualTransactionActive=false`，`begin` 计数不涨 |
| `getBean(实现类.class)` 报找不到 | 是不是走了 JDK 接口代理 | `NoSuchBeanDefinitionException: No qualifying bean of type 'OrderProbe$OrderService' available` |

前两行值得分清：final 标在类上是**启动失败**，标在方法上只是**那一个方法不拦**。

## 复现

依赖：JDK 25.0.4；Spring Framework 6.2.7（`spring-core`、`spring-beans`、`spring-context`、`spring-aop`、`spring-expression`、`spring-tx`、`spring-jcl`）；`aspectjweaver` 1.9.25.1；`jakarta.annotation-api` 2.1.1。没有 Boot。

```bash
# 把依赖 jar 放进 lib/，java 的目录通配会把它们全部装进 classpath
java -cp "lib/*" OrderProbe.java
```

单文件源码直接跑（JDK 11+ 的 source launcher）。要拼多个路径时，Windows 的分隔符是 `;`，Linux / macOS 是 `:`。上面没贴的三样：

```java
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableTransactionManagement(proxyTargetClass = true)
class CglibCfg { /* 注册 OrderService、OrderWriter、LogAspect、CountingTx */ }

@Aspect
class LogAspect {
    @Around("execution(* OrderProbe.OrderService.*(..))")
    Object around(ProceedingJoinPoint pjp) throws Throwable {
        System.out.println("[aspect]     " + pjp.getSignature().toShortString());
        return pjp.proceed();
    }
}

class CountingTx extends AbstractPlatformTransactionManager {
    protected void doBegin(Object tx, TransactionDefinition def) { begins++; }
    protected void doCommit(DefaultTransactionStatus st) { commits++; }
    // doGetTransaction / doRollback 留空
}
```

`CountingTx` 是故意不接真实数据源的：这样输出的 `begin`/`commit` 就是「事务开没开、开了几次」，不被别的东西干扰。回滚和隔离不在这次测量里，那需要真实连接，见文末「还差的」。

## 检查一下

```quiz
{
  "id": "spring-04-quiz",
  "title": "代理边界三问",
  "questions": [
    {
      "question": "一个类里 createOrder() 调 this.saveOrder()，saveOrder 上标了 @Transactional，事务没开。最直接的原因是？",
      "options": ["@Transactional 必须写在接口方法上", "saveOrder 的可见性不够", "这次调用没有经过代理，是从目标对象自己发起的"],
      "answer": 2,
      "explain": "拦截只发生在从代理入口进来的那一跳。this 是目标对象本身，拦截链被跳过，注解就没机会被读。"
    },
    {
      "question": "CGLIB 子类代理下，一个 final 方法上标了 @Transactional，会发生什么？",
      "options": ["启动时打一条 WARN；那个方法不开事务，应用照跑", "编译期就报错", "事务照开，只是切面不进"],
      "answer": 0,
      "explain": "代理是子类，覆写不了 final 方法，只能跳过它。日志里那条 Unable to proxy interface-implementing method 说的就是这件事，注意它是 WARN 不是异常。"
    },
    {
      "question": "想在代码里判断某个 bean 是不是 AOP 代理，可靠的做法是？",
      "options": ["看类名里有没有 $$SpringCGLIB$$ 或 $Proxy", "用 AopUtils.isAopProxy(bean)", "看 getClass().getSuperclass() 是不是 java.lang.reflect.Proxy"],
      "answer": 1,
      "explain": "AopUtils 是 Spring 自己用的判断入口。类名和父类都不稳定：接口可见性、JDK 版本、CGLIB 版本都会改变生成出来的类长什么样。"
    }
  ]
}
```

## 还差的

- **构造器注入自引用没测**。我只跑了字段注入，`@Lazy` 在构造期到底什么时候是必需的，这篇给不出结论。
- **5.x 与 6.0 之前的行为没验证**。protected、包级可见方法在那些版本上是否也开事务，我只有 6.2.7 的实测，没有跨版本对照。
- **Boot 只读了 jar**。`AopAutoConfiguration` 的 `matchIfMissing = true` 是从字节码里看到的，没起一个 Boot 应用实测（手上 Spring 7 那一系的 `spring-jcl` 缺失，离线起不来）。
- **AspectJ 编译期/加载期织入没跑**。那条路能拦 private 方法和 `this` 调用，本篇所有结论只对 Spring AOP 的代理模式成立。
- **开销一个数都没有**。多一层代理每次调用多几次方法转发、CGLIB 代理对象多占多少内存，我没量。

下一篇是事务：`@Transactional` 在异常、传播、只读这些地方各自怎么失效。
