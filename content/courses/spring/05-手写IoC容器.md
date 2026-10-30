---
title: 05 · 手写一个 40 行的 IoC 容器
order: 5
chapter: 容器
level: intermediate
category: spring-from-zero
prerequisite: 04 · 返回值与状态码
minutes: 34
status: published
---

# 05 · 手写一个 40 行的 IoC 容器

## 这一课你要会

- 说清 IoC（控制反转）反转的到底是"谁决定 new 哪个实现"；
- 用反射 + 构造器参数写一个能按类型取出实例的容器；
- 解释"按接口注入"与"按具体类注入"的差别，以及 Spring 为什么两个都支持；
- 知道容器启动慢在哪、以及为什么它只在启动时慢。

## 为什么

`@Autowired` 背后只有一个问题：**这个对象里的依赖，是谁塞进去的？**

不用容器时，是代码自己 `new`：

```java
TransferService service = new TransferService(new AccountRepo());
```

用容器时，是"别人"在主流程之外把它造好塞进来。反转的就是这件事的决定权——从"用到的时候自己造"变成"启动时统一造好、按需要给你"。

明白这一点之后，`@Autowired` 就只是一个标记，告诉容器"这个位置需要你填"。它没有创造任何能力。

## 讲解

### 一个容器最少要有两个能力

1. **登记**：知道有哪些类型需要被管理；
2. **取出**：按类型给出一个实例，并且同一个类型给同一个（单例）。

要"按类型给实例"，就必须能自己造对象——而你不知道这个类有哪些构造器、需要哪些参数。这就是为什么容器一定要用**反射**：它在运行时才能读到别人的类。

```sketch
{
  "title": "容器就两件事：登记，然后按类型取",
  "rows": [
    [
      { "id": "reg", "title": "登记", "sub": "知道要管哪些类", "tone": "gray" },
      { "id": "new", "title": "反射造对象", "sub": "构造器、参数都得运行时读", "tone": "blue" },
      { "id": "get", "title": "按类型取到同一个", "sub": "单例，重复取还是它", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "reg", "to": "new" },
    { "from": "new", "to": "get" }
  ]
}
```

### 手写版

下面这 40 行就是容器的全部核心。注意它没有注解、没有 AOP、没有配置文件：

<!-- run -->
```java
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

public class Main {

    interface Repo {
        String name();
    }

    static class RepoImpl implements Repo {
        public String name() {
            return "in-memory";
        }
    }

    static class Greeter {
        private final Repo repo;

        Greeter(Repo repo) {
            this.repo = repo;
        }

        String hello() {
            return "hello, " + repo.name();
        }
    }

    static class Container {
        private final Map<Class<?>, Object> instances = new HashMap<>();

        void register(Class<?>... types) {
            for (Class<?> type : types) {
                instances.put(type, instantiate(type));
            }
        }

        // 注入时按"能不能赋值"找：要 Repo，手里有 RepoImpl，就给它
        private Object resolve(Class<?> wanted) {
            Object direct = instances.get(wanted);
            if (direct != null) {
                return direct;
            }
            for (Object candidate : instances.values()) {
                if (wanted.isAssignableFrom(candidate.getClass())) {
                    return candidate;
                }
            }
            return null;
        }

        private Object instantiate(Class<?> type) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                Object[] arguments = new Object[parameters.length];
                boolean satisfiable = true;
                for (int i = 0; i < parameters.length; i++) {
                    Object dependency = resolve(parameters[i]);
                    if (dependency == null) {
                        satisfiable = false;
                        break;
                    }
                    arguments[i] = dependency;
                }
                if (!satisfiable) {
                    continue;
                }
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(arguments);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("实例化失败: " + type.getSimpleName(), error);
                }
            }
            throw new IllegalStateException("找不到可满足的构造器: " + type.getSimpleName());
        }

        @SuppressWarnings("unchecked")
        <T> T get(Class<T> type) {
            return (T) instances.get(type);
        }
    }

    public static void main(String[] args) {
        Container container = new Container();
        container.register(RepoImpl.class, Greeter.class);
        System.out.println(container.get(Greeter.class).hello());
        System.out.println(container.get(RepoImpl.class) == container.get(RepoImpl.class));
        System.out.println(container.get(Repo.class) == null ? "按接口取不到" : "按接口取到了");
    }
}
```
```text
hello, in-memory
true
按接口取不到
```

三行输出对应三个关键事实：

- **构造器注入是自动的**：`Greeter` 的构造器要一个 `Repo`（接口），容器从已登记的对象里找到了 `RepoImpl`——靠的是 `repo.isAssignableFrom(RepoImpl.class)` 为真，不是"类型相等"。
- **单例是默认行为**：同一个类型两次 `get` 返回同一引用，因为 `instances` 就是一张缓存表。
- **按接口取不到**：注意 `resolve` 与 `get` 是**两套查找方式**——注入时按"能不能赋值"宽松地找，取用时按"键相等"严格地查。所以 `get(Repo.class)` 返回了 `null`，因为登记表里的键是 `RepoImpl.class`。

### Spring 补上的那一步：接口也算键

真实容器会把"这个对象实现了哪些接口"一并记成可查询的键。于是 `getBean(Repo.class)` 也能拿到那个 `RepoImpl`。加上这一条之后，"面向接口注入"才成立：

```sketch
{
  "title": "接口也算键，面向接口注入才成立",
  "rows": [
    [
      { "id": "impl", "title": "RepoImpl 实例", "sub": "真正造出来的对象", "tone": "blue" },
      { "id": "keys", "title": "登记两个键", "sub": "RepoImpl 和 Repo 都指向它", "tone": "violet" },
      { "id": "inject", "title": "按接口注入", "sub": "调用方只写 Repo", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "impl", "to": "keys" },
    { "from": "keys", "to": "inject" }
  ]
}
```

```java
@Service
public class TransferService {
    private final AccountRepository repository;   // 接口

    public TransferService(AccountRepository repository) {
        this.repository = repository;
    }
}
```

`AccountRepository` 是一个接口，`@Repository` 标在 MyBatis 的 mapper 接口上——运行时真正被注入的是一个**代理对象**（第 10 课会看到它是怎么来的）。但注入这一步只关心"有没有一个对象能赋给这个接口"，不关心它是怎么造的。

### 为什么"启动慢、运行快"

容器在启动时做完两件重活：

1. **扫描**：读所有类上的注解，判断哪些要被管理；
2. **实例化与注入**：把整张依赖图从叶子往根造出来。

这两件事的代价是固定的，与请求量无关。所以容器"启动要几秒、之后每个请求只花微秒"。反过来说，如果启动时间随请求量增长，说明把重活放到了请求路径上——那是设计错误，不是容器的锅。

容器还有一件事值得知道：它默认**在启动时就把所有单例造完**（`spring.main.lazy-initialization=false`）。所以"某个 Bean 配置错了"会在启动时报出来，而不是等到第一次用到。这是好事——把错误提前到部署时，而不是用户点上来的那一刻。

## 动手

把容器构造对象的步骤排成正确顺序：

```code-challenge
{
  "id": "spring-05-order",
  "kind": "order",
  "title": "容器造一个对象的六步",
  "lines": [
    "拿到目标类型的 Class 对象",
    "遍历它的构造器，挑一个参数都能被满足的",
    "对每个参数类型，去已登记的实例表里找对象",
    "参数全都找到，反射调用 newInstance 造出实例",
    "把实例放进表里（单例只放一次）",
    "按类型把实例交还给调用方"
  ],
  "answer": [0, 1, 2, 3, 4, 5],
  "explain": "第 2 步的顺序很容易反：必须先挑\"参数能满足的构造器\"，再去找参数。反过来写就会遇到\"找到参数了但构造器有五个、不知道该用哪个\"。Spring 的规则更复杂（多个候选时按 @Primary、@Qualifier、参数名匹配），但起点是这一步。"
}
```

**提示**：三个以上构造器的类，容器必须有一个确定的挑选规则。

## 检查一下

```quiz
{
  "id": "spring-05-quiz",
  "title": "IoC 三问",
  "questions": [
    {
      "question": "IoC 反转的是哪件事的决定权？",
      "options": ["对象的方法由谁调用", "对象由谁创建、创建时依赖从哪来", "对象的方法在哪个线程执行"],
      "answer": 1,
      "explain": "\"控制\"指的是\"创建与装配\"。谁 new、依赖从哪来，这两件事从业务代码挪到了容器。"
    },
    {
      "question": "手写容器里 get(Repo.class) 返回 null，最直接的原因？",
      "options": ["Repo 是接口不能实例化", "登记表的键是具体类 RepoImpl，没有接口这个键", "缺少 @Autowired"],
      "answer": 1,
      "explain": "接口不能实例化只是它不会被当作目标类型去 new；取不到的直接原因是表里查不到这个键。真实容器会把实现的接口也记成可查询的键。"
    },
    {
      "question": "容器启动要几秒，处理请求却很快，原因是？",
      "options": ["它用了缓存数据库", "扫描与装配的重活在启动时一次做完，之后只是查表", "它把请求放到了另一个线程"],
      "answer": 1,
      "explain": "启动时做扫描 + 建依赖图 + 造单例；请求路径上只剩\"按类型查表\"这一步。"
    }
  ]
}
```

## 常见误区

### 误区：IoC 就是"用了 @Autowired"

**你以为**：没有注解就没有 IoC。
**其实**：上面那个手写容器没有任何注解，它照样在反转控制。注解只是告诉容器"这里要填"，是**配置手段**，不是机制本身。真实项目里可以完全用 Java 配置类（`@Bean` 方法）声明所有对象，一个 `@Autowired` 都不写。
**怎么验证**：把某个类上的 `@Autowired` 删掉（构造器只有一个时它本来就可省），项目照常启动。

### 误区：单例就是"全局唯一"

**你以为**：单例 = 整个 JVM 只有一个。
**其实**：Spring 的单例是**每个容器一个**。一个 JVM 里可以有两个 `ApplicationContext`（测试里很常见），它们各有一份单例。这也解释了为什么单例里放可变状态那么危险——它在整个应用生命周期内一直被复用，所有请求共享同一份。
**怎么验证**：在单例里加一个 `int counter`，两个请求打同一个接口，看计数是不是从 1 变成 2。

### 误区：构造器注入和字段注入差不多

**你以为**：`@Autowired` 放字段上更省代码。
**其实**：构造器注入让"依赖缺失"在**编译期就暴露**（构造器少一个参数就编译不过），也让对象在构造完成后一定是完整的。字段注入允许你造出一个"半成品对象"，只有在容器跑起来之后才知道缺东西。本项目一律用构造器注入。
**怎么验证**：把某个 `@Service` 的字段注入改成构造器注入，编译一次——如果编译不过，说明有个依赖你其实没提供，而它现在之所以能跑，只是容器帮你填空了。

## 小结

IoC 反转的是"谁决定创建与装配"。容器的最小能力只有两个：登记、按类型取出；实现靠反射读构造器、按参数类型去表里找依赖。Spring 多做了两件事：把实现的接口也记成键（所以能按接口注入），以及在启动时就把整张依赖图造完。

下一课：容器里装的东西其实有两层——描述（怎么造）与实例（造出来的），分开之后才解释得清"延迟初始化"。
