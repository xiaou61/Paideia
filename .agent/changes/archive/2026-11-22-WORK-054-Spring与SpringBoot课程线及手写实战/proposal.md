---
artifact: proposal
work_id: WORK-054
work: Spring 与 Spring Boot 课程线及手写实战
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 14:51:02 +0800
approver_role: CTO
---

# WORK-054 提案

## 摘要

在 `java-from-zero`（38 课，Java SE）与 `spring`（9 篇，深入阅读）之间，补上一整级台阶：一条 20 课的 `spring-from-zero` 课程线，加一条 5 关的 `spring-handmade` 实战线。两者的共同手法是**先手写一遍机制，再看 Spring 怎么写**——这是在不给沙箱装 Spring 依赖的前提下把原理讲透的唯一低成本路径。

## 推荐方案

**课程线 `spring-from-zero`（20 课，六个章节）**

参考 Spring 官方 Guides 的"每个概念一个能跑的最小工程"、Baeldung 的分主题式组织、以及 `spring-projects/spring-petclinic` 与 `spring-boot` 仓库自身 `samples/` 的目录分层思路，但**不复制正文**。目录按"能跑 → 会分层 → 懂容器 → 懂横切 → 会接数据 → 能交付"推进，与 `java-from-zero` 的"能跑 → 会表达 → 会组织 → 会用标准库 → 懂运行时"同一条节奏：

| 章 | 课 | 内容 |
| --- | --- | --- |
| 一 起步（1–4） | 4 | Maven 与第一个 `curl` 200；分层；参数从哪来；返回值与状态码 |
| 二 容器（5–9） | 5 | 手写 40 行 IoC 容器；BeanDefinition 与 BeanFactory；依赖注入与循环依赖；作用域与生命周期；自动配置与自定义 starter |
| 三 横切（10–13） | 4 | 手写动态代理；Spring AOP；事务就是切面；全局异常与校验 |
| 四 Web（14–16） | 3 | Jackson 在做什么；过滤器/拦截器/一次请求的完整旅程；配置优先级与 Profile |
| 五 数据（17–18） | 2 | JdbcTemplate 与连接池；手写 MyBatis 映射 |
| 六 交付（19–20） | 2 | 切片测试；打包、启动失败排查与部署 |

每一课的"讲解"里，**机制用纯 Java 手写并真跑**（`<!-- run -->`，输出逐字校验），Spring 的真实写法放在非运行块作对照。例如讲自动配置时不背注解，而是写一个 30 行的"扫描所有类、按条件注册、用户定义优先"的小程序；讲 AOP 时用 `java.lang.reflect.Proxy` 手写一遍。

**实战线 `spring-handmade`（5 关）**

用现有 `project` 判题模式（`java-project` 已经在跑），每关一个固定 `TestMain.java` 作只读测试入口，学习者只改 `Main.java`：

1. **容器**：反射 + 构造器注入，测试入口按类型取出实例并调用；
2. **作用域与生命周期**：单例复用、原型每次新建、销毁回调按逆序触发；
3. **路由**：扫注解建路径表，按 method + path 派发；
4. **代理**：动态代理实现前后置通知，同类自调用**不**经过代理（把坑变成用例）；
5. **事务**：异常时回滚、捕获时不回滚、自调用失效——三条都和真 Spring 一致。

### 为什么是这个方案

- **不需要新镜像**：不用引入 Spring 依赖、不用 Docker，判题走已有 `java` + project 模式。本机无 Docker，任何需要新镜像的方案都无法在验收时给出证据。
- **原理讲得比"贴注解"更透**：手写容器与代理以后，`@Autowired`、`@Transactional` 失效的每个场景都能被解释成"代理没被经过"，而不是"加个注解试试"。
- **判题是真的**：`TestMain` 固定入口 + 多文件编译，学习者改不了测试，和 `java-project` 同一套可信度。
- **顺序对得上**：课程第 5–13 课讲的机制，正好是实战 5 关要写的代码——两条线互为复述，不是两份无关内容。

### 放弃的东西

- 放弃了"在沙箱里真跑 Spring Boot"的体验（真起 Tomcat、真 `curl`）。这是本次最有教学价值、但也是唯一无法在本机验证的东西。
- 放弃 Spring Cloud、响应式、Security 的独立课程（Security 只在过滤链那一课出现，不单独成课）。
- 放弃重写 9 篇 Spring 深入阅读文章，只修总览的篇数与前提陈述。

## 范围

- 新增 `content/courses/spring/`（21 个 markdown 文件）。
- 新增 `content/courses/spring-handmade/`（6 个 markdown 文件，含 5 个 `TestMain` 支撑文件）。
- 改 `frontend/packages/knowledge/src/role-map.ts`（两处数组条目）。
- 改 `content/reading/spring/00-总览.md`（篇数与前提）。
- 不动后端、不动数据库、不动迁移、不动沙箱镜像。

## 备选方案

**A. 预烤 Spring 依赖镜像，沙箱里真起服务真 curl。**
最强的教学形态：学习者写一个 Controller，点运行，服务真的起来、响应体真的被断言。反对理由是硬的——本机没有 Docker，镜像无法构建也无法验证，`TestMain` 起 context 的耗时与 256MB 内存上限都只能猜。按"无证据不算通过"，这个方案现在无法给出任何证据。**保留为将来方案**：一旦开发机或服务器有 Docker，可在现有 `images/mysql` 的同构位置加 `images/spring`，并按 digest 钉住。

**B. 只扩写现有 9 篇阅读文章到 20 篇。**
改动最小、风险最低。但它补不上缺口：阅读线**没有动手块**，而用户明确要"实战环节"，且 `line_kind: reading` 的线在 `/learn` 里是"读完了事"的定位，进不了 `verify` 判题。放弃。

**C. 把 Spring 内容并进 `java-from-zero` 当第 38–57 课。**
`java-from-zero` 的定位是"完全没写过代码的人"，38 课已经很长；再塞 20 课会让这条线的完成率进一步下滑，也不符合"一条线一个主题"的既有约定。放弃。

## 仓库影响

- `content/courses/` 新增两个目录，不改任何既有课程文件。
- `role-map.ts` 的 `TIERS.backend.core` 数组插入 `spring-from-zero`；`spring` 移到 `advanced`。**必须同步检查 `recommend.test.ts`**：现有断言里有"后端岗位的核心/进阶取自表"这一条，改数组可能让它红。
- `content/reading/spring/00-总览.md` 的"六篇"与实际八篇对齐（含 `learning-path` 的 steps）。
- 无迁移、无接口、无配置变更。

## 交付拆分

1. 课程线骨架与总览 + 起步章（1–4）
2. 容器章（5–9）
3. 横切章（10–13）
4. Web 章（14–16）
5. 数据章（17–18）
6. 交付章（19–20）
7. 实战线 5 关
8. 岗位地图与阅读线顺序修正
9. 校验、单测与导入 dry-run

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| `<!-- run -->` 的 Java 示例在本机能过、在沙箱 JDK 21 上编译失败 | 全部示例只用 Java 8–17 语法；本地用 `javac --release 21` 交叉验证 |
| 20 课里部分课的"手写机制"变成牵强的玩具例子 | 每课先问"这个机制的最小可运行内核是什么"；写不出纯 Java 内核的课（如 Jackson）改为手写一个反射转 JSON 的 30 行实现，而不是硬凑 |
| `role-map` 改动让 `recommend.test.ts` 变红 | 改完立刻跑 `pnpm --filter @paideia/knowledge test`，按断言修正 |
| 实战 5 关的 `TestMain` 与学习者 `Main.java` 的接口约定不一致，导致"改对了也编译不过" | 每关的 `TestMain` 与 starter 的公共符号在课程正文里逐一列出；答案版与 starter 版都在本机跑过 |
| 内容体量大，一次做完难保证每课质量 | 按章交付，每章跑一次 `verify_course.py`，红了当场修 |

## 验收映射

| 需求 | 验收 |
| --- | --- |
| REQ-001、REQ-002 | AC-001、AC-002 |
| REQ-003 | AC-001、AC-007 |
| REQ-004 | AC-001（结构校验含动手块与 quiz） |
| REQ-005、REQ-006 | AC-003、AC-004 |
| REQ-007 | AC-005 |
| REQ-008 | 人工核对（`spring/00-总览.md` 篇数与文件数一致） |
| 全局 | AC-006 |

## 待决定事项

无。
