---
title: 09 · 自动配置与自定义 starter
order: 9
chapter: 容器
level: intermediate
category: spring-from-zero
prerequisite: 08 · 作用域与生命周期
minutes: 19
status: published
---

# 09 · 自动配置与自定义 starter

## 782 行报告里搜不到我的类

探针工程里只有两个类：`probe.lib.Greeter`，和一个本该把它装进容器的 `probe.lib.GreeterAutoConfiguration`。包名故意跟示例工程错开，省得撞名。清单文件这一轮故意不建，直接跑：

```bash
java -cp "out;res;lib/*" probe.app.MinimalApp --debug > report.txt 2>&1
```

- Windows 用分号分隔，Linux / macOS 换冒号；
- `lib/` 是 `mvn dependency:copy-dependencies` 拉下来的 jar；
- `res/` 是放清单文件的资源目录，这一轮是空的。

`report.txt` 782 行，从 `Positive matches` 翻到 `Unconditional classes`，`GreeterAutoConfiguration` 一次都没出现；启动最后一行是 `[app] Greeter 定义数 = 0`。

我的第一反应是条件写错了，来回改了两轮 `@ConditionalOnClass`，报告一个字没变。后来才发现方向从头就错：我在查一个从没进过判断的条件。

下面所有输出都来自同一套探针，每次只改清单文件或启动参数：JDK 25.0.4（Temurin）、Spring Boot 3.5.6、Spring Framework 6.2.11，`WebApplicationType.NONE`（不起 web）。`content/reading/spring/03-自动配置.md` 里用同一套探针跑出的是 783 行——那是另一次运行，多一行日志，零命中的结论一样。

## 清单是一行类名，漏了不报错

Boot 2.7 引入 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，同一版把 `spring.factories` 里的 `EnableAutoConfiguration` 键标成过期，Boot 3.0 起不再读它。3.5.6 的 `spring-boot-autoconfigure` jar 里，这个文件 156 行，全是全限定类名，没有 `key=`：

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

```text
probe.lib.GreeterAutoConfiguration
```

把这一行搬进去、别的什么都不改，同一个命令再跑，报告里立刻多出两条记录——一条类级、一条方法级：

```text
   GreeterAutoConfiguration matched:
      - @ConditionalOnClass found required class 'probe.extra.FancyClient' (OnClassCondition)

   GreeterAutoConfiguration#greeter matched:
      - @ConditionalOnMissingBean (types: probe.lib.Greeter; SearchStrategy: all) did not find any beans (OnBeanCondition)
```

为了确认不是路径碰运气，我又把同一行按 2.x 的写法塞进 `META-INF/spring.factories`：

```ini
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
probe.lib.GreeterAutoConfiguration
```

还是 782 行、零命中、定义数 0。文件在、格式对、不报错、不警告——3.5.6 上这个键等于没写。这就是这类问题最难查的地方：**漏清单和条件不满足，启动日志都不会为这个配置类发一条错误或警告**，只有注入的时候才炸。

## 报告里的一行，对应卡在哪一段

清单登记过、类级条件、方法级条件，三段里任何一段没过，报告都记，但记法不同。先认样子，再决定查哪：

| 报告里的样子 | 卡在哪一段 | 先去看什么 |
| --- | --- | --- |
| 两个 matches 段都搜不到类名 | 清单没读进来，后面三段都没发生 | 文件路径与文件名、是不是还写在 `spring.factories`、jar 里打没打包进去 |
| 类名下面跟着 `Did not match` | 类级条件 | 它逐条列出的那句原因：缺哪个类、缺哪个属性 |
| 类名 matched，`类名#方法名` 却挂在 `Negative matches` 下 | 方法级条件 | `found beans of type` 后面那个 bean 名是谁登记的 |
| 只有 `类名#方法名`，没有类名那一行 | 类级没有条件可判，或者被漏判 | 它是不是待在 `Unconditional classes` 段 |
| 落在 `Unconditional classes` 段 | 条件没参与判断 | 条件是不是写在你自己的组合注解里 |

第二行和第三行差一层，症状却都是「`Greeter` 注入不进来」：一种是类根本没生效，一种是类生效了 bean 没注册。报告分开记，注入报错分不开。

## 类级和方法级是两条记录

上面那段输出是同一个类的两次判断，只是因为都通过才连在一起。把两者拆开看更清楚。

**类级没过**：把编译好的 `out` 拷一份，删掉里面 `probe/extra/FancyClient.class` 一个文件，清单不变、代码不变：

```text
   GreeterAutoConfiguration:
      Did not match:
         - @ConditionalOnClass did not find required class 'probe.extra.FancyClient' (OnClassCondition)
```

这时的报告里只有这一条，`GreeterAutoConfiguration#greeter` 一处都没有——类没通过，它里面的 `@Bean` 方法根本不进判断。顺序是先类后方法，这条结论在这里能直接看出来。

**类级过了、方法级没过**：给启动类加一个 `@Import`，里面自己声明一个 `Greeter`：

```java
@Configuration
public class UserConfig {

    @Bean
    public Greeter greeter() {
        return new Greeter("用户定义");
    }
}
```

报告里类级仍是 matched，方法级变成否：

```text
   GreeterAutoConfiguration#greeter:
      Did not match:
         - @ConditionalOnMissingBean (types: probe.lib.Greeter; SearchStrategy: all) found beans of type 'probe.lib.Greeter' greeter (OnBeanCondition)
```

注入拿到的就是 `Greeter(prefix=用户定义)`。

顺带一个只看源码看不出来的细节：报告里写的是 `types: probe.lib.Greeter`，但 `GreeterAutoConfiguration` 里那个注解我根本没写 value，只有 `@ConditionalOnMissingBean`。类型是从 `@Bean` 方法的返回类型推出来的——把返回类型改成接口或父类，这一行跟着变。

## 用户定义优先，靠的是导入顺序

三个条件里没有一条写着「用户优先」，这件事是排队排出来的。`javap` 看 3.5.6 的那个选择器：

```text
public class org.springframework.boot.autoconfigure.AutoConfigurationImportSelector implements
    org.springframework.context.annotation.DeferredImportSelector, ...
```

`DeferredImportSelector` 的意思是：等普通 `@Configuration` 都处理完，它才把清单里的类导进来。所以用户那个 `greeter` 先登记完，自动配置的方法级条件才有东西可看，才会判否。

但这是**实现手段，不是框架承诺**。它成立的前提是你的 bean 在处理顺序上确实更早；如果你的 bean 来自另一个自动配置，或者它自己被扫描得更晚，「先看见」就不成立，得靠 `@AutoConfiguration(after = ...)` 排。这一层我没做对照实验，按官方文档写。

## 同一个类里两个 @Bean，谁赢

方法级条件看的是**判定那一刻已经登记的定义**，不是全部登记完再统一检查一遍。下面这段就是它的骨架——把 `OnBeanCondition` 换成一个按类型查表的 if，其余照旧：

<!-- run -->
```java
import java.util.LinkedHashMap;
import java.util.Map;

public class Main {

    record Greeter(String prefix) {
    }

    // 判定那一刻已经登记的定义：bean 名 -> 类型
    static final Map<String, Class<?>> defined = new LinkedHashMap<>();
    static Greeter kept;

    static void beanMethod(String beanName, Class<?> type, boolean conditionalOnMissingBean, Greeter bean) {
        if (conditionalOnMissingBean) {
            String existing = findRegistered(type);
            if (existing != null) {
                System.out.println(beanName + " -> Did not match: found beans of type " + existing);
                return;
            }
        }
        defined.put(beanName, type);
        kept = bean;
        System.out.println(beanName + " -> matched，登记定义");
    }

    static String findRegistered(Class<?> type) {
        for (Map.Entry<String, Class<?>> entry : defined.entrySet()) {
            if (entry.getValue() == type) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static void main(String[] args) {
        // 同一个配置类里两个 @Bean 返回同一类型，都带 @ConditionalOnMissingBean(Greeter.class)
        beanMethod("zFirst", Greeter.class, true, new Greeter("zFirst"));
        beanMethod("aSecond", Greeter.class, true, new Greeter("aSecond"));
        System.out.println("Greeter 定义数 = " + defined.size() + "，值是 " + kept.prefix());
    }
}
```
```text
zFirst -> matched，登记定义
aSecond -> Did not match: found beans of type zFirst
Greeter 定义数 = 1，值是 zFirst
```

同一份配置在真容器里就是这个结果。报告里先声明的那条 matched、后声明的那条被判否：

```text
   OrderProbeConfig#zFirst matched:
      - @ConditionalOnMissingBean (types: probe.lib.Greeter; SearchStrategy: all) did not find any beans (OnBeanCondition)

   OrderProbeConfig#aSecond:
      Did not match:
         - @ConditionalOnMissingBean (types: probe.lib.Greeter; SearchStrategy: all) found beans of type 'probe.lib.Greeter' zFirst (OnBeanCondition)
```

启动收尾：`[ord] Greeter 定义数 = 1`、`[ord] zFirst = Greeter(prefix=zFirst)`。

注意赢的是 `zFirst` 而不是 `aSecond`——按 bean 名字母序 `aSecond` 在前。所以顺序不是名字排的，是**方法声明顺序**：先声明的先登记，后声明的判定时看见了它。跨配置类时，顺序由配置类的处理顺序决定，`@AutoConfiguration` 的 `after` / `before` 就是干这个的（文档结论，同上，没做对照实验）。

## 组合注解里写 X.class：我以为会抛异常

我读到的说法是：`@ConditionalOnClass(SomeClass.class)` 在**直接标注**时安全，但一旦包进自己写的组合注解，缺类就会抛 `ArrayStoreException` 或 `TypeNotPresentException`，所以组合注解必须写 `name`。

前半句对。后半句在 3.5.6 上不成立。组合注解长这样，条件原样照抄，只是搬进注解里：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnClass(probe.extra.FancyClient.class)
public @interface ComposedOnFancy {
}
```

把它标在一个输出 `Greeter` 的自动配置类上，运行时 `FancyClient` 不在类路径（还是删掉那个 class 文件）。我准备抓异常，结果**没有任何异常**：启动正常，`Greeter` 照样是 `Greeter(prefix=meta)`，报告把它归到这一段，第 775 行：

```text
Unconditional classes:
----------------------

    ...
    probe.lib.MetaAutoConfiguration
```

条件被整个漏掉了，连 `Did not match` 都没有。而它在类里的方法级条件照常工作，报告里仍有 `MetaAutoConfiguration#greeter matched:`。

把同一个组合注解改成 `@ConditionalOnClass(name = "probe.extra.FancyClient")` 再跑，才恢复正常：

```text
   MetaByNameAutoConfiguration:
      Did not match:
         - @ConditionalOnClass did not find required class 'probe.extra.FancyClient' (OnClassCondition)
```

四种组合的实测结果（前两条用 `DirectXAutoConfiguration`，后两条用 `MetaAutoConfiguration` 与 `MetaByNameAutoConfiguration`，清单里一次只放一个，其余代码完全相同）：

- 直接标在配置类上，`X.class`，类在 → matched；
- 直接标在配置类上，`X.class`，类不在 → `Did not match`，配置跳过；
- 组合注解里 `X.class`，类不在 → **条件消失**，进 `Unconditional classes`，配置照常生效；
- 组合注解里 `name = "…"`，类不在 → `Did not match`，配置跳过。

文档只承诺了第一种和第二种（直接标注时 `X.class` 缺类安全），对组合注解只要求写 `name`，没写不写会怎样。所以「必须写 name」这条规矩是**对的**，但它对的原因不是会抛异常，而是不写就会被静默漏判——这比抛异常难查得多，因为你会去等一个永远不会出现的报错。至于元注解解析在哪一步把条件丢掉了，我没挖到（见最后一节）。

## @AutoConfiguration 和 @Configuration 差什么

`javap` 看注解本身，`@AutoConfiguration` 就是 `@Configuration(proxyBeanMethods=false)` 加排序字段：

```text
    org.springframework.context.annotation.Configuration(
      proxyBeanMethods=false
    )
```

前半句是可验证的元数据；`proxyBeanMethods=false` 意味着配置类里 `@Bean` 方法之间的互相调用不再走 CGLIB 代理，每次都是真调用。所以别把 `@AutoConfiguration` 当「更高级的 `@Configuration`」往业务配置类上写：它多出来的那点东西（`before` / `after` 排序字段、清单语义）只在从清单加载时才起作用，落在扫描路径上就只剩下 `proxyBeanMethods=false` 这一个副作用。

然后是那句流传很广的提醒：自动配置类不要放在组件扫描范围内，否则会被注册两次。我把 `probe.dup.DupConfig` 同时登记进清单、又留在扫描范围里（启动类 `scanBasePackages` 同时扫 `probe.iso2` 和 `probe.dup`），实测：

```text
[dup] new DupConfig #1 id=80026551
[dup] DupConfig 实例数 = 1，构造函数执行次数 = 1
[dup] DupConfig 的 bean 名字 = probe.dup.DupConfig
```

一个实例、构造一次、bean 名是全限定名（清单加载的命名方式，不是 `dupConfig`），日志里也没有 `Overriding bean definition` 之类的字样。也就是说这个组合下「扫描 + 清单」两条路只走了一条。

原因能指到具体类：`javap -v` 看 `@SpringBootApplication` 的注解体，它内嵌的 `@ComponentScan` 带了两个排除过滤器：

```text
    org.springframework.context.annotation.ComponentScan(
      excludeFilters=[@org.springframework.context.annotation.ComponentScan$Filter(
        type=Lorg/springframework/context/annotation/FilterType;.CUSTOM
        classes=[class Lorg/springframework/boot/context/TypeExcludeFilter;]
      ),@org.springframework.context.annotation.ComponentScan$Filter(
        type=Lorg/springframework/context/annotation/FilterType;.CUSTOM
        classes=[class Lorg/springframework/boot/autoconfigure/AutoConfigurationExcludeFilter;]
      )]
    )
```

`AutoConfigurationExcludeFilter` 把「既在 `.imports` 清单里、又标了 `@Configuration` 的类」从扫描结果里排掉，剩下的从清单走。所以包放哪儿不是坑，注册两次也不是必然结果——不重复注册的原因是这两个过滤器，不是我放对了包。这是我测到的那一种组合，别的组合没验。

## 最少要放两个文件

一个能用的自动配置，两个文件：配置类，加上一行清单。配置类长这样（`Greeter` 从 `probe.lib` 来，包名换掉时整篇一起换）：

```java
@AutoConfiguration
@ConditionalOnClass(name = "probe.extra.FancyClient")
public class GreeterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Greeter greeter() {
        return new Greeter("hello");
    }
}
```

`@ConditionalOnMissingBean` 标在方法上只守这一个 bean，标在类上等于给类里所有 `@Bean` 方法都加一遍。上面这个例子放方法上是故意的：将来加进来的第二个 bean 未必也该让位。

想让前缀来自配置项，得用 `@ConfigurationProperties` 绑定——那是第 16 课的内容，这里先写常量，不提前牵进来。

发布时拆成两个 artifact 是惯例：`greeter-spring-boot-autoconfigure` 放配置类和清单，`greeter-spring-boot-starter` 里只有一个 pom，把实现和依赖一起拉进来。拆开的好处是别人能只引自动配置、不引你带的实现依赖；小项目合成一个包也能跑。

三个环节出问题，症状和报告里的痕迹都不一样：

| 哪个环节出问题 | 启动与注入的症状 | 报告里有痕迹吗 |
| --- | --- | --- |
| 清单那一行 | 注入时报找不到 `Greeter`，启动本身不报错 | 没有，两个 matches 段都搜不到 |
| 类级条件不成立（依赖没引、类名写错） | 同上，注入报找不到 | 有，`Did not match` 并给出缺哪个类 |
| 方法级条件判否（别人已经定义了 `Greeter`） | 注入拿到别人的那个，取不到自动配置的默认值 | 有，`类名#方法名` 落在 `Negative matches`，`found beans of type` 指向谁 |

## 动手

自动配置没生效时，排查顺序被打乱了。排成正确的顺序：

```code-challenge
{
  "id": "spring-09-order",
  "kind": "order",
  "title": "自动配置没生效时看什么",
  "lines": [
    "启动加 --debug，把整份输出重定向到文件（七百多行，控制台里翻不完）",
    "按报告给的原因修：补依赖、补配置项，或者删掉自己同名的 Bean",
    "先在清单文件的路径与文件名上确认：这个类的全限定名是不是真被登记了",
    "读它下面逐条列出的原因，不要先动条件注解",
    "在 Positive matches / Negative matches / Unconditional classes 三段里找自己那条"
  ],
  "answer": [2, 0, 4, 3, 1],
  "explain": "先确认清单（类到底进没进候选名单），再拿报告，再分三段找自己那条，然后读原因，最后才动手改。最容易跳过的是清单那一步：没登记时报告里连条目都没有，你会把它误判成条件不成立，然后去改一堆根本没参与判断的条件。"
}
```

**提示**：报告里**找不到**某条配置，和它出现在 `Negative matches` 里，是两个不同的原因——把这两种当成一种，是本课开头那个下午的全部代价。

## 检查一下

```quiz
{
  "id": "spring-09-quiz",
  "title": "自动配置三问",
  "questions": [
    {
      "question": "加 --debug 后，Positive matches 和 Negative matches 里都搜不到你的自动配置类名。最可能卡在哪？",
      "options": [
        "条件注解写错了，被判成不成立",
        "类的构造器是私有的，Spring 造不出来",
        "依赖没引，@ConditionalOnClass 没通过",
        "清单没被读到：路径或文件名不对，或者还写在 spring.factories 里"
      ],
      "answer": 3,
      "explain": "Negative matches 记的是「被考虑过但没通过」，两个段都没有说明它压根没进候选名单。3.5.6 实测：同一个类写在 spring.factories 里，782 行报告零命中。"
    },
    {
      "question": "@ConditionalOnClass(Foo.class) 写在你自己的组合注解上，运行时 Foo 不在类路径，3.5.6 实测会怎样？",
      "options": [
        "没有异常：条件被整个漏掉，类进 Unconditional classes，配置照常生效",
        "启动抛 ArrayStoreException / TypeNotPresentException",
        "条件判为不成立，配置跳过，报告给出 did not find required class",
        "编译期就报错"
      ],
      "answer": 0,
      "explain": "实测没有任何异常，报告把它放在 Unconditional classes 段。同一个组合注解改成 name 字符串后，才正常跳过并给出 did not find required class。"
    },
    {
      "question": "一个配置类里两个 @Bean 方法都带 @ConditionalOnMissingBean(Greeter.class)，先声明的返回 zFirst，后声明的返回 aSecond。容器里是哪个？",
      "options": [
        "两个都注册，注入时报候选不唯一",
        "按 bean 名字母序判断，aSecond 胜出",
        "只有 zFirst：后声明的方法判定时看见它，记 Did not match",
        "同一配置类内的 @Bean 不参与这个条件，两个都注册"
      ],
      "answer": 2,
      "explain": "方法级条件看的是判定那一刻已登记的定义，同一个类内按方法声明顺序走。实测 OrderProbeConfig#aSecond 的记录是 found beans of type 'probe.lib.Greeter' zFirst。"
    }
  ]
}
```

## 还差的

- **Boot 4.1.1 只解包过 jar，没实跑。** 4.x 把自动配置按模块拆开了，清单不再只有一份、类名和排序都变，本课这些结论只在 3.5.6 上验过，换版本要重新量。
- **组合注解漏判的成因没定位。** 我只测到结果（类进 `Unconditional classes`、配置照常生效），没查到元注解解析在哪一步丢掉了条件；Boot 2.x 上是否真的抛 `ArrayStoreException`，我也没跑旧版本。
- **`after` / `before` 只按文档写。** 没做「两个自动配置互相依赖、顺序写反导致抢注」的对照实验，所以那一节的顺序敏感性是文档结论，不是我的测量。
- **双重注册只测了一种组合。** 类标 `@Configuration`、落在默认扫描范围内、清单也登记了——这个组合注册一次。类加载器隔离、类没标 `@Configuration`、清单文本被改坏这几种情况下排除过滤器还奏不奏效，没验。
- **只用过 `--debug`。** `/actuator/conditions` 那份报告与它字段是否逐条对应，我没验——探针是 `WebApplicationType.NONE`，Actuator 没起，不敢说等价。
