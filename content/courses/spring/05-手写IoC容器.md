---
title: 05 · 手写一个 40 行的 IoC 容器
order: 5
chapter: 容器
level: intermediate
category: spring-from-zero
prerequisite: 04 · 返回值与状态码
minutes: 20
status: published
---

# 05 · 手写一个 40 行的 IoC 容器

## 加了第二个构造器，启动没报错，第一次调用才炸

`TransferService` 原来只有一个构造器，跑得好好的。我想让它在单测里能直接 `new`，就加了一个无参的：

```java
public class TransferService {
    private AccountRepository repository;      // 这里没有 final，这一点很关键

    public TransferService() {
    }

    public TransferService(AccountRepository repository) {
        this.repository = repository;
    }

    public String ping() {
        return "hello, " + repository.name();
    }
}
```

`InMemoryAccounts` 也注册了。Spring Framework 6.2.11 + JDK 25，用 `AnnotationConfigApplicationContext` 显式注册这两个类（省掉扫描），`refresh()` 之后：

```text
context 起来了
Exception in thread "main" java.lang.NullPointerException: Cannot invoke "AccountRepository.name()" because "this.repository" is null
```

启动没报错，`refresh()` 正常返回，第一次 `ping()` 才炸。我第一反应是 `InMemoryAccounts` 没被注册——查了，注册了，`context.getBean(AccountRepository.class)` 也确实拿得到实例。真正的解释是：**Spring 用的是那个无参构造器**，带参数的那个从头到尾没被调用过。

我原来以为容器会挑「参数能填满的那个」。它不猜：一个类有多个构造器、又没有哪个标了 `@Autowired` 时，它只认无参构造器；连无参的都没有，就直接启动失败：

```text
Error creating bean with name 'transferService': Failed to instantiate [TransferService]: No default constructor found
```

而只要类里只剩一个构造器，不标 `@Autowired` 也能注入（Spring 4.3 起）——同样这两个类，只留带参数的那个构造器，输出是 `hello, in-memory`。

三件事连起来看：注不注入跟注解没关系，跟**这个类有几个构造器、参数类型是什么**有关系。容器决定一个对象怎么造，靠的是读构造器签名，再拿参数类型去登记表里找候选。下面那 40 行就是把这件事写出来。

| 你看到的 | 先看哪 |
| --- | --- |
| 启动成功，第一次调用 NPE，报「某个字段 is null」 | 类里有几个构造器；是不是有一个无参的在那里，把依赖绕过去了 |
| `Failed to instantiate [...] No default constructor found` | 所有构造器都带参数，而其中没有哪个标 `@Autowired` |
| `Unsatisfied dependency ... constructor parameter 0` | 这个参数类型在容器里没有对应 Bean，或者实现类没被扫到 |

## 容器要回答的只有一句：这个参数谁来填

`Container` 从 `static class Container {` 到它的闭合括号正好 40 行：`register` 边登记边造，`get` 按类型取，真正做事的是 `instantiate` 和它下面的两个小方法。没有注解、没有配置文件、没有 AOP。

<!-- run -->
```java
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

public class Main {

    interface AccountRepository {
        String name();
    }

    static class InMemoryAccounts implements AccountRepository {
        public String name() {
            return "in-memory";
        }
    }

    static class TransferService {
        private final AccountRepository repository;

        TransferService(AccountRepository repository) {
            this.repository = repository;
        }

        String ping() {
            return "hello, " + repository.name();
        }
    }

    static class Container {
        private final Map<Class<?>, Object> instances = new LinkedHashMap<>();

        void register(Class<?>... types) throws ReflectiveOperationException {
            for (Class<?> type : types) {
                instances.put(type, instantiate(type));
            }
        }

        <T> T get(Class<T> type) {
            return type.cast(instances.get(type));
        }

        private Object instantiate(Class<?> type) throws ReflectiveOperationException {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Object[] arguments = fill(constructor.getParameterTypes());
                if (arguments == null) {
                    continue;
                }
                return constructor.newInstance(arguments);
            }
            throw new IllegalStateException("没有能填满的构造器: " + type.getSimpleName());
        }

        private Object[] fill(Class<?>[] parameters) {
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = resolve(parameters[i]);
                if (arguments[i] == null) return null;
            }
            return arguments;
        }

        private Object resolve(Class<?> wanted) {
            for (Object bean : instances.values()) {
                if (wanted.isAssignableFrom(bean.getClass())) return bean;
            }
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Container container = new Container();
        container.register(InMemoryAccounts.class, TransferService.class);
        System.out.println(container.get(TransferService.class).ping());
        System.out.println(container.get(InMemoryAccounts.class) == container.get(InMemoryAccounts.class));
        System.out.println(container.get(AccountRepository.class) == null ? "get(AccountRepository.class) 拿不到" : "取到了");

        Container reversed = new Container();
        try {
            reversed.register(TransferService.class, InMemoryAccounts.class);
        } catch (IllegalStateException error) {
            System.out.println(error.getMessage());
        }
    }
}
```
```text
hello, in-memory
true
get(AccountRepository.class) 拿不到
没有能填满的构造器: TransferService
```

四行输出对应四件事：

- **`hello, in-memory`**：`TransferService` 的构造器要一个 `AccountRepository`，容器从表里找到 `InMemoryAccounts`。判据是 `wanted.isAssignableFrom(bean.getClass())`——「这个 bean 能不能赋给 wanted」，不是「两个类相不相等」。
- **`true`**：两次 `get` 是同一个对象。`instances` 就是缓存表，`register` 时造一次，之后只查表。
- **`get(AccountRepository.class) 拿不到`**：取用时按精确相等查键，表里的键是 `InMemoryAccounts.class`。注入走得通、取用走不通，因为这是两套查找。
- **`没有能填满的构造器: TransferService`**：我把登记顺序反过来（先 `TransferService`，后 `InMemoryAccounts`），造 `TransferService` 时表里还没有能赋给 `AccountRepository` 的对象。`instances.put(type, instantiate(type))` 是边登记边造，被依赖的类必须先进表。

最后一行不是一个错误文案的巧合，是这行登记代码的语义决定的。Spring 不是这么做的：它先把所有 Bean 的定义登记完，再按依赖顺序实例化，所以你不必操心 `@Service` 的书写顺序。这两层为什么分开，第 6 课讲。

## `get` 和 `resolve` 是两套查找：一个按精确相等，一个按能赋值

上面第 3、4 行的失败是两个不同的坑，值得拆开：

- `get` 是**你按类型要**：键必须精确相等，`get(AccountRepository.class)` 和 `get(InMemoryAccounts.class)` 是两次不同的查询。
- `resolve` 是**容器给参数填**：只要候选能赋值给参数类型就行，所以接口参数能拿到实现类。

同一张表、两个方向、两套规则。我的实现只让「宽松」活在注入侧，取用侧还是严格的——所以才出现「注入成功、`get` 拿不到」。

真实的 Spring 两个方向都能走。同样两个类，单构造器的 `TransferService`，只打印两行：

```text
in-memory
true
```

`getBean(AccountRepository.class)` 直接拿到 `InMemoryAccounts` 的实例，而且和 `getBean(InMemoryAccounts.class)` 是同一个对象。

要注意它**怎么**做到的：Spring 在按类型取 Bean 时，是对每个候选 Bean 的实际类型做一次「能不能赋值给目标类型」的判断，不是维护一张「接口 → 实例」的键表。我在玩具里用的是键表（把接口也塞成键），因为它好写、也够说明效果——但机制不同，别把玩具的结构当成 Spring 的结构。

按键表补上这个效果，`Container` 从 40 行长到 49 行，改动只有两处：登记时把接口也写成键，以及多一个 `keys` 方法。

<!-- run -->
```java
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    interface AccountRepository {
        String name();
    }

    static class InMemoryAccounts implements AccountRepository {
        public String name() {
            return "in-memory";
        }
    }

    static class TransferService {
        private final AccountRepository repository;

        TransferService(AccountRepository repository) {
            this.repository = repository;
        }

        String ping() {
            return "hello, " + repository.name();
        }
    }

    static class Container {
        private final Map<Class<?>, Object> instances = new LinkedHashMap<>();

        void register(Class<?>... types) throws ReflectiveOperationException {
            for (Class<?> type : types) {
                Object instance = instantiate(type);
                for (Class<?> key : keys(type)) {
                    instances.put(key, instance);
                }
            }
        }

        <T> T get(Class<T> type) {
            return type.cast(instances.get(type));
        }

        private Object instantiate(Class<?> type) throws ReflectiveOperationException {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Object[] arguments = fill(constructor.getParameterTypes());
                if (arguments == null) {
                    continue;
                }
                return constructor.newInstance(arguments);
            }
            throw new IllegalStateException("没有能填满的构造器: " + type.getSimpleName());
        }

        private Object[] fill(Class<?>[] parameters) {
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = resolve(parameters[i]);
                if (arguments[i] == null) return null;
            }
            return arguments;
        }

        private Object resolve(Class<?> wanted) {
            for (Object bean : instances.values()) {
                if (wanted.isAssignableFrom(bean.getClass())) return bean;
            }
            return null;
        }

        private List<Class<?>> keys(Class<?> type) {
            List<Class<?>> keys = new ArrayList<>(List.of(type));
            keys.addAll(List.of(type.getInterfaces()));
            return keys;
        }
    }

    public static void main(String[] args) throws Exception {
        Container container = new Container();
        container.register(InMemoryAccounts.class, TransferService.class);
        System.out.println(container.get(AccountRepository.class).name());
        System.out.println(container.get(AccountRepository.class) == container.get(InMemoryAccounts.class));
    }
}
```
```text
in-memory
true
```

`keys` 只认直接实现的接口。父类上的接口、以及 `Object` 这种「所有候选都匹配」的情况，这里一概没处理——真实容器在按类型取时要求候选唯一，两个实现同时匹配就会报不唯一，第 6 课给了完整的说法。

## 动手

六行打乱了。排对之后，它描述的就是上面 `register` + `instantiate` 干的事：

```code-challenge
{
  "id": "spring-05-order",
  "kind": "order",
  "title": "容器造一个对象的六步",
  "lines": [
    "把实例放进登记表（同一个类型只放一次）",
    "拿到目标类型的 Class 对象",
    "参数全都找到，反射调用 newInstance 造出实例",
    "遍历它的构造器，挑一个参数都能被满足的",
    "按构造器参数的个数准备好实参数组",
    "对每个参数类型，去已登记的实例表里找对象"
  ],
  "answer": [1, 3, 4, 5, 2, 0],
  "explain": "顺序最容易反的是第 2 步和第 3 步：必须先挑构造器，再按它的参数列表去找对象。反过来做，你会先找到一堆对象，却不知道要往哪个构造器里塞。造完才入表也是硬约束——依赖它的类只能看见已经在表里的东西。注意「挑一个参数都能被满足的」是我这个玩具的贪心规则；Spring 对多构造器的处理不是这样，见正文第一节。"
}
```

**提示**：造完再入表，不是入表再补造。

## 检查一下

```quiz
{
  "id": "spring-05-quiz",
  "title": "IoC 三问",
  "questions": [
    {
      "question": "手写容器里 get(AccountRepository.class) 返回 null，最直接的原因是什么？",
      "options": [
        "接口不能被实例化，所以永远取不到",
        "因为没有在参数上写 @Autowired",
        "取用时按精确相等查键，登记表里没有接口这个键"
      ],
      "answer": 2,
      "explain": "接口不能实例化只说明它不会被当作目标类型去造；取不到的直接原因是键不存在。注入时走的是 resolve（按能赋值查），所以同一个容器里注入成功、取用失败可以同时发生。"
    },
    {
      "question": "一个类有两个构造器，一个无参、一个带参数，都没标 @Autowired。Spring 会怎么造它？",
      "options": [
        "挑参数能全部填满的那个，所以带参数的会被用",
        "用无参构造器造出来，带参数的那个不参与，依赖可能停在 null",
        "两个构造器都不满足，启动直接失败"
      ],
      "answer": 1,
      "explain": "没有 @Autowired 时 Spring 只信无参构造器，它不会替你在多个构造器里挑一个填得满的。所以启动是成功的，字段却还是 null，要等第一次调用才炸。"
    },
    {
      "question": "容器先 register(TransferService.class)、后 register(InMemoryAccounts.class)，会发生什么？",
      "options": [
        "造 TransferService 时表里还没有实现类，报没有能填满的构造器",
        "两次都成功，后登记的实现类会自动补给前面那个对象",
        "先返回一个依赖为 null 的 TransferService，下次 get 时再补上"
      ],
      "answer": 0,
      "explain": "register 是边登记边造：实例化发生在 put 之前，被依赖的类必须已经在表里。真实 Spring 先把定义全部登记、再按依赖顺序实例化，所以书写顺序不影响结果——这是两者最直接的差别。"
    }
  ]
}
```

## 还差的

- 只在 JDK 25 + Spring Framework 6.2.11 上跑过，用 `AnnotationConfigApplicationContext` 显式注册，没走 Boot 的自动配置与扫描。Boot 的失败文案（第 2 课那种 `Parameter 0 ...`）这里没重跑，正文引用的是 Framework 层的异常原文。
- 多候选没有规则。两个实现都实现 `AccountRepository` 时，我的 `resolve` 返回 `LinkedHashMap` 里第一个能赋值的那个；Spring 会抛 `NoUniqueBeanDefinitionException`。`@Primary`、`@Qualifier`、参数名匹配一个都没实现。
- `getDeclaredConstructors()` 的返回顺序没有规范保证，所以「两个构造器都能填满」时我的容器挑谁是不确定的。我只跑了「恰好一个能填满」和「一个都填不满」两种情况，没跑前者；这也是我没让它去学 Spring 那套构造器规则的原因之一。
- `keys` 只收直接实现的接口，父类上的接口没管，也没处理「按 `Object` 取」。代理对象（MyBatis mapper、AOP 代理）登记成什么类型没测，那要等第 10 课手写动态代理才有工具验证。
- 单例是「一张表一份」：两个 `Container` 实例就是两套对象。多容器、父子容器的场景没测，作用域与生命周期在第 8 课。
