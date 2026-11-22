---
artifact: design
work_id: WORK-054
work: Spring 与 Spring Boot 课程线及手写实战
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 14:51:02 +0800
approver_role: CTO
---

# WORK-054 设计

## 系统上下文

本项只改内容与一份前端常量，不触及后端运行时：

```
content/courses/spring/            ← 新课程线（21 个 md，由 verify_course.py 校验、import-course.mjs 导入）
content/courses/spring-handmade/   ← 新实战线（6 个 md，其中 5 课各带一个 TestMain.java 支撑文件）
content/reading/spring/00-总览.md  ← 改篇数与前提
frontend/packages/knowledge/src/role-map.ts ← 两处数组条目
```

判题链路不变：学习者端 `RunBody` → `sandbox.run({language:'java', code, files})` → `paideia-sandbox` 的 `DockerCodeRunner`（`eclipse-temurin:21-jdk-alpine`，`--network=none`）→ `javac -d out $(find . -name '*.java') && java -cp out TestMain`。

## 组件与职责

| 组件 | 职责 |
| --- | --- |
| `content/courses/spring/*.md` | 20 课正文 + 总览；每课七小节、一个 `<!-- run -->`、一个 `code-challenge`、一个 3 题 `quiz` |
| `content/courses/spring-handmade/*.md` | 总览 + 5 关；每关的 `code-challenge`（`kind: run`）带 `files` 里的固定 `TestMain.java` |
| `scripts/verify_course.py` | 不改。承担 AC-001 / AC-003 的全部证据 |
| `role-map.ts` | 只改 `TIERS.backend` 的 `core` 与 `advanced` 两个数组 |

## 请求或事件流程

课程正文不涉及流程。实战判题流程与 `java-project` 完全一致：

1. 前端把 `files` 里除 `Main.java` 以外的文件（即 `TestMain.java`）原样打包，再把编辑器里的内容作为 `Main.java`；
2. 沙箱把每个文件分帧写入 `/tmp/work`，`javac` 编译目录下全部 `.java`；
3. 运行固定入口 `TestMain`，stdout 与用例的 `expected` 逐字比对。

因此**每个 `TestMain` 都必须能在没有任何参数、没有任何外部依赖的情况下跑完并打印确定结果**。

## 接口与数据

### 课程 frontmatter（与既有课程同构）

```yaml
title: 05 · 手写一个 40 行的 IoC 容器
order: 5
chapter: 容器
level: intermediate
prerequisite: 04 · 返回值与状态码
category: spring-from-zero
minutes: 32
status: published
```

### 总览 frontmatter（`order: 0` 才需要）

```yaml
line_name: Spring 从零开始
line_kind: course
line_sort: 24
line_summary: 从 Maven 与第一个 200 走到打包部署：20 课，每课的机制先手写一遍再看 Spring 怎么写。
```

`line_sort: 24` 落在 `java-project`（22）与 `java-debug`（25）之间，改成整数即可；真正的岗位排序由 `role-map.ts` 决定。

### 实战线总览 frontmatter

```yaml
line_name: 手写一个迷你 Spring
line_kind: reading
line_sort: 26
line_mode: practice
line_theory: spring-from-zero
verify: project
```

与 `content/reading/java-project/00-总览.md` 的 `line_kind: reading` + `line_mode: practice` + `verify: project` 完全同构。放在 `content/courses/` 下是为了让它和课程一起被 `verify_course.py` 校验（`java-project` 放在 `content/reading/` 因此从未被结构校验覆盖）。

### 实战 5 关的固定契约

`Main.java` 由学习者编辑，`TestMain.java` 只读。同一个默认包，`TestMain` 可以引用 `Main.java` 里的**包级私有顶层类**。以下名字写死，正文逐条列出，不能改：

**L1 容器**

```java
// Main.java 必须声明
class Container {
    void register(Class<?>... types);
    <T> T get(Class<T> type);          // 单例：同一类型两次 get 返回同一实例
}
```

`TestMain` 传入 `Repo` 与 `Greeter(Repo)` 两个类型，断言 `get(Greeter.class).hello()` 为 `hello, in-memory`，且 `get(Repo.class) == get(Repo.class)`。
失败点：不做构造器注入 → `Greeter` 的 `repo` 为 null → NPE；每次 new → 单例断言红。

**L2 作用域与生命周期**

```java
interface InitializingBean { void afterCreate(); }
interface DisposableBean { void destroy(); }

class Container {
    void singleton(Class<?>... types);
    void prototype(Class<?>... types);
    <T> T get(Class<T> type);
    void close();                       // 对所有单例按创建逆序调 destroy()
}
```

`TestMain` 断言：同一单例两次 `get` 是同一引用；原型两次 `get` 不同引用；`close()` 后的事件日志为 `[create-a, create-b, destroy-b, destroy-a]`。

**L3 路由**

```java
interface Handler { String handle(String pathVariable); }

class Router {
    void register(String httpMethod, String pattern, Handler handler);  // pattern 形如 "/users/{id}"
    String dispatch(String httpMethod, String path);
    // 无匹配 → "404"；路径匹配但方法不匹配 → "405"
}
```

`TestMain` 断言：`GET /users/42` → 处理器收到 `"42"`；`POST /users/42` → `405`；`GET /nope` → `404`；`GET /users/42/`（尾斜杠）→ `404`（不自动容错，先严格）。

**L4 代理**

```java
interface Greeting {                        // 两个方法都必须在接口上，否则代理暴露不出来
    String hello(String name);
    String twice(String name);
}

class GreetingImpl implements Greeting {
    public String hello(String name);          // 返回 "hello, " + name
    public String twice(String name);          // 返回 hello(name) + hello(name)
}

class ProxyFactory {
    static Greeting timed(Greeting target, List<String> log);
    // 每次经过代理调用：先 log.add("before:" + 方法名)，调用后 log.add("after:" + 方法名)
}
```

`TestMain` 断言：`proxy.hello("a")` 为 `hello, a`，`log` 为 `[before:hello, after:hello]`；清空后 `proxy.twice("x")` 返回 `hello, xhello, x`，`log` 为 `[before:twice, after:twice]`——**内层两次 `hello` 没有产生日志**，这就是自调用绕过代理。

**L5 事务**

```java
class Store {                       // 用 List<String> 当存储，不碰数据库
    List<String> saved();
    void add(String value);
    void restore(List<String> snapshot);
}

class TxTemplate {
    void run(Runnable action);      // 全程持有快照；抛 RuntimeException 时恢复快照
}

class AccountService {
    void ok();                      // 正常保存两条
    void fail();                    // 保存一条后抛 IllegalStateException
    void catchInside();             // 内部 try/catch 吞掉异常，不向外抛
    void selfCall();                // 在未加事务的方法里直接调用 ok()
}
```

`TestMain` 断言四条：`ok()` → 2 条；`fail()` → 0 条（异常传出模板即回滚）；`catchInside()` → 2 条（异常没传出模板，事务照常提交——**这就是"异常被吞掉不回滚"**）；`selfCall()` → 2 条且模板没被经过（自调用绕过了事务边界）。

### `role-map.ts` 的目标形态

```ts
backend: {
  core: ['java-from-zero', 'spring-from-zero', 'redis', 'mysql-from-zero', 'network-basics'],
  advanced: ['spring', 'concurrency', 'jvm', 'system-design', 'os'],
},
```

把 `spring`（深入阅读篇）从 `core` 移到 `advanced`，`spring-from-zero` 占它在 `core` 的第 2 位。

## 持久化与迁移

无。内容由导入脚本写入既有 `categories` / `knowledge_entries` 表，走的是 `WORK-020` 已有的管理端接口。

## 失败处理与恢复

| 场景 | 表现 | 处理 |
| --- | --- | --- |
| 学习者改错类名 | `javac` 报 `class Container is public, should be declared in a file named Container.java` 或找不到符号 | 正文在每关开头用一张表列出固定符号；starter 已包含骨架 |
| 学习者删了 `TestMain` 依赖的方法 | 编译失败，错误行号指向 `TestMain` | 同上；starter 里这些方法必须存在，只留空实现 |
| 导入时后端未起 | `import-course.mjs` 连接失败 | 本项只保证 `--dry-run` 通过；真实导入属部署动作 |
| `role-map` 改动让 `recommend.test.ts` 红 | 单测失败 | 按断言修正，不加 `skip` |

## 安全与权限

无新增接口与数据。课程正文不得出现凭据、真实口令、内网地址；示例中的配置一律写占位值。

## 可观测性

`verify_course.py` 的输出即证据：它逐个数 `<!-- run -->` 并比对输出。AC-001 要求输出里出现"含代码执行"，防止用 `--no-run` 蒙过去。

## 实施顺序

1. 课程总览 + 第一章（4 课）→ 跑 `verify_course.py --dir content/courses/spring`
2. 第二章（5 课）→ 同上
3. 第三章（4 课）→ 同上
4. 第四章（3 课）→ 同上
5. 第五章（2 课）+ 第六章（2 课）→ 同上
6. 实战线总览 + 5 关 → 本地用 `javac --release 21` 交叉验证 starter 与答案
7. `role-map.ts` + `spring` 阅读线总览修正 → 前端单测
8. 导入 `--dry-run`

## 测试策略

| 层 | 手段 | 覆盖 |
| --- | --- | --- |
| 内容结构 | `verify_course.py --dir content/courses/spring` | AC-001、AC-002（文件数另用 `ls | wc -l`） |
| 可运行示例 | 同上（真 `javac` + `java`，逐字比对） | AC-001、AC-007 |
| 实战判题 | 本机 `javac --release 21 -d out *.java && java -cp out TestMain`，starter 版与答案版各跑一次 | AC-004 |
| 前端 | `pnpm --filter @paideia/knowledge test` | AC-005 |
| 导入 | `import-course.mjs --dry-run` | AC-006 |

沙箱基线是 Java 21，本机是更新的 JDK；因此**所有 Java 示例都用 `--release 21` 验证**，避免"本机能过、沙箱编译不过"。

## 需求追踪

| REQ | 设计落点 |
| --- | --- |
| REQ-001 | 目录、总览 frontmatter、`line_sort: 24` |
| REQ-002 | 六章 20 课的目录表 |
| REQ-003 | 每课「讲解」的 `<!-- run -->` 手写机制块 |
| REQ-004 | 每课 `code-challenge` + 3 题 `quiz`（由 `verify_course.py` 强制） |
| REQ-005 | 实战总览 frontmatter + 每关 `files` 里的 `TestMain.java` |
| REQ-006 | 上面五段固定契约 |
| REQ-007 | `role-map.ts` 目标形态 |
| REQ-008 | `content/reading/spring/00-总览.md` 的"六篇"→"八篇" |

## 待决定事项

无。
