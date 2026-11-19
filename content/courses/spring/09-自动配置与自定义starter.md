---
title: 09 · 自动配置与自定义 starter
order: 9
chapter: 容器
level: intermediate
category: spring-from-zero
prerequisite: 08 · 作用域与生命周期
minutes: 22
status: published
---

# 09 · 自动配置与自定义 starter

## 报告里根本没有我这一条

我给自己的库写了个自动配置，本地能跑，打成 jar 放进另一个项目里，`Greeter` 注入不进来。第一反应是条件没满足，于是加上 `--debug`，在输出里搜类名：

```text
$ java -jar app.jar --debug | grep -n Greeter
（没有输出）
```

一条都没有。不在 `Positive matches`，也不在 `Negative matches`——**它压根没进入候选名单**。

那一刻我才意识到自己少写了一整个文件：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。类编译好了、注解也对、条件全满足，只是没人告诉 Spring"有这么个类要考虑"。

这一课就把这件事推到根上：自动配置不是框架在猜，它是**一份清单 + 一堆条件判断**。清单漏了，条件写得再对也不会被看一眼。

## 自动配置的全部逻辑

去掉所有名词，就三句话：

1. 有一份清单：**"可能用得上的配置类"**；
2. 每个配置类上写着**"什么情况下我才生效"**；
3. 生效的配置类往容器里登记对象；跳过的不登记。

"加个 `starter-jdbc` 就自带连接池"就是这么来的。它没有猜，只是在清单里有一条"如果类路径上有连接池的类，就造一个"。

## 手写一遍内核

下面这段代码就是那个内核，`applyAutoConfiguration` 里只有一句判断：

<!-- run -->
```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class Main {

    interface DataSource {
        String name();
    }

    interface Cache {
        String name();
    }

    static class DefaultDataSource implements DataSource {
        public String name() {
            return "default-datasource";
        }
    }

    static class UserDataSource implements DataSource {
        public String name() {
            return "user-datasource";
        }
    }

    static class DefaultCache implements Cache {
        public String name() {
            return "default-cache";
        }
    }

    // 一条自动配置 = 它提供什么类型 + 怎么造
    static final class AutoConfig {
        final Class<?> type;
        final Supplier<Object> factory;

        AutoConfig(Class<?> type, Supplier<Object> factory) {
            this.type = type;
            this.factory = factory;
        }
    }

    static final class Context {
        private final Map<Class<?>, Object> beans = new LinkedHashMap<>();

        <T> void define(Class<T> type, T instance) {
            beans.put(type, instance);
        }

        Object get(Class<?> type) {
            return beans.get(type);
        }

        // 全部逻辑：用户已经有了就不动，没有才造
        void applyAutoConfiguration(AutoConfig... configs) {
            for (AutoConfig config : configs) {
                if (beans.containsKey(config.type)) {
                    continue;
                }
                beans.put(config.type, config.factory.get());
            }
        }
    }

    public static void main(String[] args) {
        AutoConfig[] configs = {
                new AutoConfig(DataSource.class, DefaultDataSource::new),
                new AutoConfig(Cache.class, DefaultCache::new),
        };

        Context plain = new Context();
        plain.applyAutoConfiguration(configs);
        System.out.println("什么都没定义: " + ((DataSource) plain.get(DataSource.class)).name()
                + " / " + ((Cache) plain.get(Cache.class)).name());

        Context customized = new Context();
        customized.define(DataSource.class, new UserDataSource());
        customized.applyAutoConfiguration(configs);
        System.out.println("用户定义了数据源: " + ((DataSource) customized.get(DataSource.class)).name()
                + " / " + ((Cache) customized.get(Cache.class)).name());
    }
}
```
```text
什么都没定义: default-datasource / default-cache
用户定义了数据源: user-datasource / default-cache
```

第二行是自动配置最核心的行为：**用户定义优先，自动配置让位**。这就是 `@ConditionalOnMissingBean` 的全部含义。

注意我这个内核只判一种条件（"有没有"）。真实框架的条件有几十种，但每一种都长这个形状——一个返回 boolean 的检查，加一句"不成立就跳过"。

## 常用的四类条件

| 注解 | 成立条件 | 典型用途 |
| --- | --- | --- |
| `@ConditionalOnClass` | 类路径上有某个类 | "只有引了驱动才配数据源" |
| `@ConditionalOnMissingBean` | 容器里还没有这个类型的对象 | 让用户定义优先 |
| `@ConditionalOnProperty` | 配置项满足（值或是否存在） | 开关型功能 |
| `@ConditionalOnBean` / `@ConditionalOnMissingClass` | 相反方向 | 互斥的两种实现 |

前两条的命令不同：`@ConditionalOnBean` / `@ConditionalOnMissingBean` 是**顺序敏感**的，它只能看到"到目前为止"已经登记的 Bean。所以自动配置类在 `@AutoConfiguration` 里要写 `after`：

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
```

顺序错了，`@ConditionalOnMissingBean` 会在用户 Bean 还没登记时判断"没有"，然后抢着造一个。

### `@ConditionalOnClass` 为什么常写成字符串

```java
@ConditionalOnClass(name = "com.mysql.cj.jdbc.Driver")   // 常见写法
@ConditionalOnClass(DataSource.class)                    // 很多时候也没问题
```

网上流传的解释是"写 `X.class` 会让这个配置类加载失败，所以必须写字符串"。**这个说法只对一半。**

- 当注解**直接标在配置类上**时，Spring 用 ASM 读字节码里的注解元数据，不会真的去解析那个 `Class` 引用——所以 `X.class` 缺类也是安全的，这是 `@ConditionalOnClass` 文档里明确写了的。
- 只有当这个注解作为**元注解**出现在你自己写的组合注解里时，才必须用 `name`。因为那时 JVM 要加载你的组合注解，它的 `Class` 参数会被解析，目标类不在类路径上就抛 `ArrayStoreException` / `TypeNotPresentException`。

所以判断标准是：**直接标注 → `X.class` 可以；写自己的组合注解 → 用 `name`。**

## 候选清单放在哪

老的写法是 `META-INF/spring.factories`，里面一个键挂一串类名。Boot 2.7 引入新的清单文件，Boot 3 起自动配置键从 `spring.factories` 移除：

```text
src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

内容是纯文本，一行一个全限定类名，没有 `key=`：

```text
com.example.greeter.GreeterAutoConfiguration
```

这个文件是开头那个 bug 的答案。**漏了它，症状就是"报告里连 Negative matches 都没有"**——因为 Negative matches 记录的是"被考虑过但没通过"，而清单里没登记的类根本没被考虑。

## 怎么看清哪些生效了

```bash
java -jar app.jar --debug
```

控制台会打出一张条件评估报告：

```text
============================
CONDITIONS EVALUATION REPORT
============================

Positive matches:
-----------------
   DataSourceAutoConfiguration matched:
      - @ConditionalOnClass found required classes 'javax.sql.DataSource', 'org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType'
      - @ConditionalOnMissingBean did not find any beans (OnBeanCondition)

Negative matches:
-----------------
   RedisAutoConfiguration:
      Did not match:
         - @ConditionalOnClass did not find required class 'org.springframework.data.redis.core.RedisOperations'
```

这张表是排查"为什么没生效"的第一站，因为它逐条给出"哪些条件成立、哪些不成立、不成立是缺哪个类"。

顺便说一个容易看错的细节：`javax.sql.DataSource` 属于 JDK，不是漏改成 `jakarta` 的老代码。JDK 的 `java.sql` / `javax.sql` 包名从来没跟 Jakarta EE 一起改名，看到它不用去"修"。

## 一个最小 starter 需要什么

`starter` 这个词在 Boot 里其实是两件事，很多人混着说：

| 名字 | 是什么 | 内容 |
| --- | --- | --- |
| `xxx-spring-boot-autoconfigure` | 自动配置的实现 | 配置类、条件注解、`AutoConfiguration.imports` |
| `xxx-spring-boot-starter` | 空的聚合包 | 只有一个 `pom`，把实现与依赖都拉进来 |

拆开的好处是别人可以只用你的自动配置、不引你带的依赖。小项目直接合成一个包也行。

一个最小的自动配置，三个文件：

```java
@AutoConfiguration
@ConditionalOnClass(name = "com.example.greeter.client.Client")
@EnableConfigurationProperties(GreeterProperties.class)
public class GreeterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Greeter greeter(GreeterProperties properties) {
        return new Greeter(properties.getPrefix());
    }
}
```

```java
@ConfigurationProperties(prefix = "greeter")
public class GreeterProperties {

    private String prefix = "hello";

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }
}
```

属性类里那两个方法必须是真的方法。我第一次抄别人的模板，正文里写着 `// getter / setter` 当注释，然后 `properties.getPrefix()` 编译报错——模板里的注释不是代码，这段我删掉了好几次才长记性。用 `record` 也可以（`record GreeterProperties(String prefix) {}`），但从 Boot 3 起绑定不可变对象的写法要求在 `@ConfigurationProperties` 上配构造器绑定，初学时用 getter/setter 版本更少踩坑。

注意 `@ConditionalOnMissingBean` 在这个例子里标在**方法**上而不是类上：标在类上表示"这个类里所有 Bean 都要在缺失时才注册"，标在方法上只针对这一个。

```text
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.greeter.GreeterAutoConfiguration
```

### `@AutoConfiguration` 与 `@Configuration` 到底差什么

`@AutoConfiguration` 做了两件事：一是标记"我属于自动配置阶段"（框架会给它 `proxyBeanMethods=false`，并允许写 `after`/`before` 排序）；二是它被设计成**只由清单加载**。

但它**不会阻止组件扫描**。官方文档专门提了一条：自动配置类要放在**不被组件扫描覆盖的包**里，否则一旦你的启动类扫到它，它就会被当成普通配置类注册一次，加上清单里的那一次——**同一个配置类被处理两次**。

我踩过的表现是启动日志里的 `Overriding bean definition for bean 'greeter'`，以及某些 Bean 初始化了两遍。修法不是删注解，而是移动包：`com.example.greeter.autoconfigure` 这类子包要避开使用方的扫描根（通常是 `com.example.demo`）。用官方脚手架（`spring-boot-starter-xxx` 模板）能避免这个坑，因为它默认就这么放。

顺带一句：**自己的业务配置类别用 `@AutoConfiguration`**。它没有登记在清单里，标了也不会被特殊处理，只会在某个时刻给你一个"顺序按 after 排"的错觉。

## 动手

自动配置没生效时，排查顺序被打乱了。排成正确的顺序：

```code-challenge
{
  "id": "spring-09-order",
  "kind": "order",
  "title": "自动配置没生效时看什么",
  "lines": [
    "在 Negative matches 里找自己那条配置",
    "确认 AutoConfiguration.imports 里确实登记了这个类",
    "启动时加 --debug，拿到 CONDITIONS EVALUATION REPORT",
    "按原因修：补依赖、补配置项、或删掉自己的同名 Bean",
    "读它下面列出的 Did not match 原因（缺类 / 缺属性 / 已有同类型 Bean）"
  ],
  "answer": [2, 1, 0, 4, 3],
  "explain": "先确认清单（类到底进没进候选名单），再看报告，再找原因，最后修。最容易跳过的是第二步：如果清单里没登记，报告里连这条都不会出现，你会把它误判成「条件不成立」，然后去查一堆根本没参与判断的条件。"
}
```

**提示**：报告里**找不到**某条配置，和它出现在 `Negative matches` 里，是两个完全不同的原因。

## 检查一下

```quiz
{
  "id": "spring-09-quiz",
  "title": "自动配置三问",
  "questions": [
    {
      "question": "什么时候 @ConditionalOnClass 必须写成 name 字符串而不是 X.class？",
      "options": ["任何时候都必须写字符串", "只有当它作为元注解出现在自己写的组合注解里时", "只有目标类在同一个 jar 里时"],
      "answer": 1,
      "explain": "直接标在配置类上时，Spring 用 ASM 读字节码元数据，不会真的解析 Class 引用，所以 X.class 缺类也安全。写组合注解时才必须用 name。"
    },
    {
      "question": "自己定义了一个 DataSource 之后，默认的自动配置为什么不见了？",
      "options": ["Boot 随机选一个", "@ConditionalOnMissingBean 发现已有同类型 Bean，主动让位", "两个都在，注入时随机挑"],
      "answer": 1,
      "explain": "这就是「用户定义优先」。如果两个都注册，注入时会变成候选不唯一，所以框架宁可自己退出。"
    },
    {
      "question": "自动配置的候选清单现在在哪个文件？",
      "options": ["META-INF/spring.factories", "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports", "application.yml"],
      "answer": 1,
      "explain": "Boot 2.7 引入、Boot 3 起自动配置键从 spring.factories 移除，改成一行一个类名的 .imports 文件。"
    }
  ]
}
```

## 两件我原以为是坑、其实不是

**一、`@ConditionalOnClass(X.class)` 会把配置类炸掉。** 不会，前提是直接标在配置类上。真正会炸的是把它包进自己的组合注解。

**二、`@AutoConfiguration` 让类不会被扫描到。** 不会。它不改变扫描行为，官方只要求你**把类放在扫描不到的包**里。重复注册的原因是"扫描 + 清单"两条路都走到了。

反过来，真正安静地失败的是这两种：

- **清单漏登记**：`--debug` 报告里连条目都没有，症状最像"条件不满足"，最容易查错方向；
- **`@ConditionalOnMissingBean` 顺序错**：用户 Bean 还没登记它就判断"没有"，于是抢着造一个，然后要么冲突、要么注入到错的那个。

## 这一课之后

自动配置 = 候选清单 + 条件判断。核心行为一条：用户定义优先，自动配置让位。清单在 `AutoConfiguration.imports`，排查先看 `--debug` 的条件评估报告。自建 starter 最少三个文件：配置类、属性类、清单文件——外加一个"别被扫描到"的包位置。

容器这一章到此结束。下一章换角度：对象造好之后，怎么在不改它源码的前提下给它的方法加一层行为——代理，也是"注解为什么不生效"的总答案。
