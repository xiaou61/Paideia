---
title: IoC 与依赖注入
category: spring
order: 1
minutes: 20
level: beginner
status: published
---

## 先把结论摆出来

**IoC（控制反转）反转的是"谁来 new 这个对象"的决定权，从你的代码转移到容器。**

```sketch
{
  "title": "反转的是「谁决定 new」",
  "rows": [
    [
      { "id": "before", "title": "以前：自己 new", "sub": "依赖变了就得改调用方", "tone": "amber" },
      { "id": "after", "title": "现在：容器造好送进来", "sub": "调用方只说我要什么", "tone": "blue" },
      { "id": "why", "title": "代价是启动变慢", "sub": "换来的是换实现不动调用方", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "before", "to": "after" },
    { "from": "after", "to": "why" }
  ]
}
```

你写 `new UserService(new UserDao())`，创建顺序由你决定；交给 Spring 后，你只声明"我需要一个 UserDao"，容器负责找、创建、按顺序塞进来。这个"塞进来"的动作就叫依赖注入（DI）。

所以 IoC 是**目标**，DI 是**手段**。一句话记法：**你不要去拿依赖，依赖会被送到你手上。**

## 为什么不要自己 new

自己 new 的问题不是"写起来麻烦"，是三件具体的事：

**第一，对象可能不只一个。** 你在本地 new 一个真实的 `UserDao`，它就真的连数据库了，测试时想换成假的得改代码。容器里可以同时登记两个实现，按名字或 `@Primary` 挑。

**第二，生命周期没人管。** `new` 出来的对象不会执行 `@PostConstruct`，不会被 `@Transactional` 包一层代理，销毁时也不会调 `@PreDestroy`。你 new 出来的 `UserService` 里，那些注解全是装饰。

**第三，单例就没了。** 每次 `new` 都是一个新对象。配置对象、连接池、缓存，这些东西应该全应用共用一份。

反过来说：**如果这几条你都不需要，那就该 new。** 值对象、DTO、一次性的计算工具类都该老实 `new`，别塞进容器——容器里的东西越多，启动越慢，关系越乱。判断标准就一句：**要共享、要外部资源、要被切面增强 → 交给容器；纯数据、纯计算 → 自己 new。**

## 容器里到底装了什么

Spring 的容器（`ApplicationContext`）核心是一张表：**bean 名字 → bean 定义 → bean 实例**。

bean 定义（`BeanDefinition`）不是对象本身，而是一份"怎么造这个对象"的说明书：类名、构造器参数、作用域、是否懒加载、初始化方法叫什么。

启动时 Spring 做三件事：

1. **扫描**：读你的 `@Component`、`@Service`、`@Configuration`，把它们变成 bean 定义；
2. **实例化**：按依赖关系算出创建顺序，依次造对象；
3. **后处理**：给需要增强的 bean 套上代理（`BeanPostProcessor` 干这个）。

你拿到的 `userService` 如果被 `@Transactional` 标记过，第 3 步就已经把真对象包进代理里了。所以容器返回给你的不一定是"你的对象"。

## 三种注入方式，差别在哪

### 字段注入（不推荐，但到处都是）

```java
@Service
public class OrderService {
    @Autowired
    private UserDao userDao;   // 直接塞进字段
}
```

写法最短，教程里最常见。问题有三个：

- **不能声明成 final**，字段可被反射改写，对象造出来时是不完整状态；
- **离开 Spring 就没法用**：单元测试里 `new OrderService()` 之后 `userDao` 是 `null`，只能起整个容器；
- **依赖看不出来**：一个类有 10 个 `@Autowired` 字段，你从签名上看不出它需要什么。

Spring 官方从 4.3 起就明确推荐构造器注入，字段注入能用但不建议。

### 构造器注入（推荐）

```java
@Service
public class OrderService {
    private final UserDao userDao;

    public OrderService(UserDao userDao) {
        this.userDao = userDao;
    }
}
```

单构造器时可以省略 `@Autowired`，Spring 4.3 起自动识别。

好处直接对应上面三个问题：字段是 `final`，对象一造出来就是完整的；测试里直接 `new OrderService(fakeDao)`，不需要容器；依赖写在构造器签名上，一眼看全。

还有一个不太被提到的好处：**循环依赖会在启动时直接报错**。构造器注入要求对方先完整存在，造不出环。字段注入则能靠"先造空壳再填字段"蒙混过去——能启动，但那是拿一个不该存在的中间态换来的。

### setter 注入

```java
@Service
public class ReportService {
    private DataSource dataSource;

    @Autowired
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }
}
```

只在**可选依赖**或**需要重新设置**时才有意义。日常业务代码里基本用不到，见到它多半是老代码。

## 同一个类型有两个实现怎么办

```java
public interface PayChannel { void pay(BigDecimal amount); }

@Service("alipay")
public class AlipayChannel implements PayChannel { ... }

@Service("wechat")
public class WechatChannel implements PayChannel { ... }
```

注入 `PayChannel` 时容器会报 `NoUniqueBeanDefinitionException`。三种解法：

| 做法 | 写法 | 适合 |
| --- | --- | --- |
| 按名字 | 参数名写成 `alipay`，或用 `@Qualifier("alipay")` | 明确知道要哪个 |
| 按默认 | 其中一个实现加 `@Primary` | 有一个是"绝大多数情况用这个" |
| 全都要 | 注入 `List<PayChannel>` 或 `Map<String, PayChannel>` | 策略模式，按场景选 |

`Map<String, PayChannel>` 的 key 就是 bean 名字，写策略模式最省事——不用自己维护一张 `Map` 常量表。

## 一个具体例子

```java
@Service
public class PriceService {
    private final List<DiscountRule> rules;

    public PriceService(List<DiscountRule> rules) {
        this.rules = rules;
    }

    public BigDecimal apply(BigDecimal price) {
        BigDecimal result = price;
        for (DiscountRule rule : rules) {
            result = rule.apply(result);
        }
        return result;
    }
}
```

新增一条折扣规则时，只需加一个 `@Component` 实现类，`PriceService` 一行不改。这是依赖注入真正换来的东西：**调用方不需要知道有多少个实现**。

## 检查一下

```quiz
{"id":"spring-01-quiz","title":"IoC 与注入三问","questions":[
 {"question":"IoC 反转的到底是什么？","options":["反转了方法的调用顺序","反转了\"谁来创建并组装对象\"的决定权","反转了数据库的读写方向","反转了编译与运行的先后"],"answer":1,"explain":"对象的创建与依赖装配从你的代码移到容器，这就是控制反转。"},
 {"question":"构造器注入相比字段注入，最直接的好处是什么？","options":["启动更快","字段可以是 final，对象造出来就是完整的，测试时能直接 new","能自动处理循环依赖","支持更多注解"],"answer":1,"explain":"final 字段加完整构造，让对象不可变、可脱离容器测试；循环依赖反而会直接暴露成启动失败。"},
 {"question":"一个接口有两个实现类，注入时想默认用其中一个，加什么注解？","options":["@Qualifier","@Scope","@Primary","@Lazy"],"answer":2,"explain":"@Primary 标记默认候选；@Qualifier 是按名字精确指定，不是设默认。"}
]}
```

## 常见误区

### 误区：所有对象都该交给 Spring 管

**你以为**：既然容器这么好，那就把所有类都加 `@Component`。
**其实**：容器要维护 bean 定义、算依赖图、生成代理，这些都是启动成本。纯数据对象（DTO、值对象）和纯计算工具类放进容器没有收益，反而让依赖关系变模糊。
**怎么验证**：看一眼你的 `@Component` 列表，找出那些**没有任何注入、也没被切面增强**的类——它们其实可以直接 `new`，或者做成静态工具方法。

### 误区：`@Autowired` 按类型找不到就会按名字找

**你以为**：类型匹配不上时会自动退化成按字段名匹配。
**其实**：默认策略是**先按类型**。类型匹配到多个候选时，才会用字段名或参数名作为兜底去匹配 bean 名字。类型一个都没有时直接报 `NoSuchBeanDefinitionException`，不会去猜。
**怎么验证**：把字段名改成和 bean 名字不一致（比如 bean 叫 `alipayChannel`，字段叫 `channel`），同时存在两个 `PayChannel` 实现，启动会报 `NoUniqueBeanDefinitionException`。

### 误区：循环依赖是 Spring 会自动解决的正常情况

**你以为**：A 依赖 B、B 依赖 A，Spring 能处理，所以不用管。
**其实**：字段注入和 setter 注入能靠"提前暴露半成品对象"（三级缓存）绕过去，但那是**补救**，不是设计。构造器注入的循环依赖直接启动失败，被提前暴露的对象也可能拿到还没初始化完的依赖。
**怎么验证**：把两个互相依赖的类都改成构造器注入，启动会报 `BeanCurrentlyInCreationException`。这时该做的是拆类，不是换回字段注入。

## 小结

IoC 是把"谁来创建和组装对象"交给容器，DI 是它的实现方式。构造器注入让依赖显式、对象不可变、测试不需要容器；字段注入省事但把问题推到运行期。容器只该装需要共享、需要增强、需要外部资源的对象。

下一篇讲一个对象在容器里从生到死要经过什么，以及为什么默认是单例、什么时候会因此出事。
