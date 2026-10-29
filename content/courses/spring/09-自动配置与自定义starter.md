---
title: 09 · 自动配置与自定义 starter
order: 9
chapter: 容器
level: intermediate
category: spring-from-zero
prerequisite: 08 · 作用域与生命周期
minutes: 34
status: published
---

# 09 · 自动配置与自定义 starter

## 这一课你要会

- 说清自动配置的本质：一份候选清单 + 一堆条件判断；
- 用 `@ConditionalOnMissingBean` 解释"为什么我加个依赖它就自动配好了"；
- 知道候选清单现在放在哪（`AutoConfiguration.imports`，不是老的 `spring.factories`）；
- 用 `--debug` 打印条件判断报告，看清哪些生效、哪些被跳过、为什么跳过；
- 知道一个最小的自定义 starter 需要哪几个文件。

## 为什么

"加个 `starter-web` 就自带 Tomcat""加个 `starter-jdbc` 就自带连接池"——这类体验很容易让人以为框架在"猜"。

它不猜。它做的是：**准备一份候选配置类清单，启动时逐个检查每个类上的条件，条件全成立的才应用**。条件不成立的那一条，连类都不会被实例化。

理解这一点之后，两个困扰会消失：为什么我定义了自己的 `DataSource` 之后默认的那个不见了（因为 `@ConditionalOnMissingBean` 让位了），以及为什么我明明加了依赖却没生效（某个 `@ConditionalOnClass` 或 `@ConditionalOnProperty` 没满足）。

## 讲解

### 自动配置的全部逻辑

去掉所有名词，就三句话：

1. 有一份清单：**"可能用得上的配置类"**；
2. 每个配置类上写着**"什么情况下我才生效"**；
3. 生效的配置类会向容器里登记对象；跳过的不登记。

### 手写一遍内核

下面这段代码就是那个内核。注意 `applyAutoConfiguration` 里只有一句判断：

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

        // 全部逻辑：用户已经有了，就不动；没有，才造
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

### 常用的四类条件

| 注解 | 成立条件 | 典型用途 |
| --- | --- | --- |
| `@ConditionalOnClass(X.class)` | 类路径上有 X | "只有引了驱动才配数据源" |
| `@ConditionalOnMissingBean` | 容器里还没有这个类型的对象 | 让用户定义优先 |
| `@ConditionalOnProperty(name="x", havingValue="true")` | 配置项满足 | 开关型功能 |
| `@ConditionalOnMissingClass` / `@ConditionalOnBean` | 相反方向 | 互斥的两种实现 |

第一条要特别注意：`@ConditionalOnClass` 判断的是**类存不存在**，而写 `X.class` 时如果 X 本身不在类路径上，这个类会加载失败。所以真实用法里写成字符串：

```java
@ConditionalOnClass(name = "com.mysql.cj.jdbc.Driver")
```

这是自动配置代码里常见的"明明只是判断，为什么要写字符串"的原因。

### 候选清单放在哪

老的写法是 `META-INF/spring.factories`，里面一个键对应一串类名。**Boot 2.7 之后引入 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`**，一行一个类名，`spring.factories` 里的自动配置键在 Boot 3 已被移除。

所以如果你在找一个"为什么我的自动配置没被加载"，先看清单文件对不对：

```text
src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

内容是纯文本，一行一个全限定类名，没有 `key=`。

### 怎么看清哪些生效了

```bash
java -jar app.jar --debug
```

控制台会打出一张 `ConditionEvaluationReport`：

```text
============================
CONDITIONS EVALUATION REPORT
============================

Positive matches:
-----------------
   DataSourceAutoConfiguration matched:
      - @ConditionalOnClass found required classes 'javax.sql.DataSource'
      - @ConditionalOnMissingBean did not find any beans (OnBeanCondition)

Negative matches:
-----------------
   RedisAutoConfiguration:
      Did not match:
         - @ConditionalOnClass did not find required class 'redis.clients.jedis.Jedis'
```

**这张表是排查"为什么没生效"的第一站**。它逐条列出"哪些条件成立了、哪些没成立、没成立是因为缺哪个类"。不看这张表去猜，是最浪费时间的一种排查。

### 一个最小 starter 需要什么

`starter` 这个词在 Boot 里其实是两件事，很多人混着说：

| 名字 | 是什么 | 内容 |
| --- | --- | --- |
| `xxx-spring-boot-autoconfigure` | 自动配置的实现 | 配置类、条件注解、`AutoConfiguration.imports` |
| `xxx-spring-boot-starter` | 一个空的聚合包 | 只有一个 `pom`，把上面的实现与依赖都拉进来 |

拆开的好处是：别人可以只用你的自动配置，不引你带的依赖。小项目直接合成一个包也行。

一个最小的自动配置类：

```java
@AutoConfiguration
@ConditionalOnClass(name = "com.example.client.Client")
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
    // getter / setter
}
```

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.greeter.GreeterAutoConfiguration
```

`@AutoConfiguration` 与 `@Configuration` 的差别是前者多了"必须在自动配置阶段处理"的语义，防止被组件扫描重复注册。**自己的业务配置类不要用它**，那是给 starter 用的。

## 动手

把"自动配置为什么不生效"的排查顺序排对：

```code-challenge
{
  "id": "spring-09-order",
  "kind": "order",
  "title": "自动配置没生效时看什么",
  "lines": [
    "启动时加 --debug，拿到 CONDITIONS EVALUATION REPORT",
    "在 Negative matches 里找自己那条配置",
    "读它下面列出的\"Did not match\"原因（缺类 / 缺属性 / 已有同类型 Bean）",
    "确认 AutoConfiguration.imports 里确实登记了这个类",
    "按原因修：补依赖、补配置项、或删掉自己的同名 Bean"
  ],
  "answer": [0, 1, 2, 3, 4],
  "explain": "第 3 步容易被跳过：如果清单里根本没登记这个类，报告里连它的条目都不会出现，你会误判成\"条件不成立\"。先确认清单，再看条件。"
}
```

**提示**：报告里**找不到**某条配置，和它出现在 Negative matches 里，是两个完全不同的原因。

## 检查一下

```quiz
{
  "id": "spring-09-quiz",
  "title": "自动配置三问",
  "questions": [
    {
      "question": "为什么 @ConditionalOnClass 常写成字符串 name = \"...\"？",
      "options": ["字符串比较更快", "直接写 X.class 会让这个配置类本身加载失败", "注解不支持 Class 参数"],
      "answer": 1,
      "explain": "注解里的 Class 引用会在加载注解时被解析，目标类不在类路径上就炸。字符串延迟到运行时才解析，安全。"
    },
    {
      "question": "自己定义了一个 DataSource 之后，默认的自动配置为什么不见了？",
      "options": ["Boot 随机选一个", "@ConditionalOnMissingBean 发现已有同类型 Bean，主动让位", "两个都在，随机注入"],
      "answer": 1,
      "explain": "这就是\"用户定义优先\"。两个都存在时会变成\"候选不唯一\"，所以框架宁可自己退出。"
    },
    {
      "question": "自动配置的候选清单现在在哪个文件？",
      "options": ["META-INF/spring.factories", "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports", "application.yml"],
      "answer": 1,
      "explain": "Boot 2.7 引入、Boot 3 完全切换到 .imports 文件，一行一个类名。"
    }
  ]
}
```

## 常见误区

### 误区：自动配置是"猜我需要什么"

**你以为**：框架在分析你的代码意图。
**其实**：它只检查四类机械条件：类在不在、Bean 有没有、配置项是什么值、属性能不能绑定。它不读你的意图，只看环境。
**怎么验证**：把 `--debug` 输出里 Positive matches 的每一条当成一句 if 语句读，你会发现全是"在不在、有没有"。

### 误区：starter 就是一个带依赖的 jar

**你以为**：把依赖写进 `pom` 就是 starter。
**其实**：真正干活的自动配置必须在清单文件里登记。漏了这一步，jar 在类路径上、类也编译好了、条件也全满足——**但它永远不会被加载**，而且不报错。这是自建 starter 最安静的一种失败。
**怎么验证**：打 `--debug`，看你的配置类名是否出现在报告里。没出现 = 清单没登记。

### 误区：`@Configuration` 和 `@AutoConfiguration` 可以混用

**你以为**：都是配置类，随便标。
**其实**：`@AutoConfiguration` 声明的是"我属于自动配置阶段"，它会被按清单加载、并且不会被组件扫描重复注册。给业务配置类标它，可能被加载两次；给自动配置类标 `@Configuration` 并放进清单，行为不确定。
**怎么验证**：看启动日志里同一个 Bean 定义是否出现两次（`Overriding bean definition` 警告）。

## 小结

自动配置 = 候选清单 + 条件判断。核心行为一条：用户定义优先，自动配置让位。清单在 `AutoConfiguration.imports`，排查先看 `--debug` 的 `CONDITIONS EVALUATION REPORT`。自建 starter 最少三个文件：配置类、属性类、清单文件。

容器这一章到此结束。下一章换一个角度：对象造好之后，怎么在不改它的前提下，给它的方法加一层行为——这就是代理，也是"注解为什么不生效"的总答案。
