---
artifact: tasks
work_id: WORK-054
work: Spring 与 Spring Boot 课程线及手写实战
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 14:51:02 +0800
approver_role: CTO
---

# WORK-054 实施任务

## 依据

- `requirements.md`（REQ-001 … REQ-008，AC-001 … AC-007）
- `proposal.md`（推荐方案：手写机制 + 现有 project 判题）
- `design.md`（文件清单、frontmatter、实战五关固定契约、`role-map` 目标形态）

## 全局约束

- 所有 Java 示例只用 Java 8–17 语法；本机验证一律加 `--release 21`。
- 每课七小节顺序固定：这一课你要会 → 为什么 → 讲解 → 动手 → 检查一下 → 常见误区 → 小结。
- 每课至少 2 条 `### 误区：`、恰好 1 个 `quiz`（3 题）、至少 1 个 `code-challenge`、`## 讲解` 至少 1 个 `<!-- run -->`（Java 单文件 `Main`，输出与 ```text 逐字相同）。
- 正文不得出现凭据、真实口令、内网地址。
- 不新增后端接口、迁移、依赖或镜像。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 本机 JDK 能编译、沙箱 JDK 21 编译不过 | TASK-001…006 | `verify_course.py` + 每批本地 `javac --release 21` 抽查 |
| `<!-- run -->` 的输出块与实际输出差一个空格/换行 | TASK-001…006 | `verify_course.py` 逐字比对 |
| 学习者只填空实现、`TestMain` 却编译不过（缺符号） | TASK-007 | starter 版本机编译（允许运行失败，不允许编译失败） |
| `role-map` 改动让 `recommend.test.ts` 既有断言红 | TASK-008 | `pnpm --filter @paideia/knowledge test` |
| 实战线放在 `content/courses/` 却被 `verify_course.py` 按课程结构校验而红 | TASK-007 | `verify_course.py --dir content/courses/spring-handmade` 退出码 0（每关仍按七小节写） |
| 课程文件数与 `order` 不连续（漏一课、重号） | TASK-006 | `ls` 计数 + `order` 顺序核对 |

## 任务

### TASK-001 | done | 课程总览 + 第一章：起步（4 课）

- 对应：`REQ-001`、`REQ-002`、`REQ-003`、`REQ-004`、`AC-001`、`AC-002`
- 依赖：无
- 新建：`content/courses/spring/00-总览.md`、`01-Maven与第一个接口.md`、`02-分层.md`、`03-参数从哪来.md`、`04-返回值与状态码.md`
- 接口：frontmatter 按 `design.md`「课程 frontmatter」段；总览 `order: 0` + `line_name`/`line_kind`/`line_sort: 24`/`line_summary` + 一个 `learning-path`（6 站，对应六章）
- 测试：`scripts/verify_course.py --dir content/courses/spring`
- 步骤：
  1. 写总览（六章走法 + 与 `java-from-zero`/`spring` 的位置说明 + `learning-path` 6 站）；
  2. 写 01：`<!-- run -->` 用纯 Java 手写一个"从 query string 里取参数并返回状态码"的 20 行桩，说明 `curl 200` 前面的东西都是这段逻辑；
  3. 写 02：手写一个把请求交给 service、service 交给 repo 的三层调用，演示为什么不把 SQL 写在 Controller；
  4. 写 03、04：分别手写路径变量切分与状态码映射；
  5. 跑校验，红了按报错修到 0。
- 验证：`python scripts/verify_course.py --dir content/courses/spring` → 期望 `通过：5 个课程文件（含代码执行）`

### TASK-002 | done | 第二章：容器（5 课）

- 对应：`REQ-002`、`REQ-003`、`REQ-004`、`AC-001`
- 依赖：TASK-001
- 新建：`content/courses/spring/05-手写IoC容器.md`、`06-容器里装的是什么.md`、`07-依赖注入与循环依赖.md`、`08-作用域与生命周期.md`、`09-自动配置与自定义starter.md`
- 接口：课 05 的 `<!-- run -->` 必须是 `Container { void register(Class<?>...); <T> T get(Class<T>) }` 的完整可运行版——与实战 L1 的契约同名同签名
- 测试：`scripts/verify_course.py --dir content/courses/spring`
- 步骤：
  1. 写 05：40 行反射容器，输出里打印注入成功与单例相等；
  2. 写 06：手写一个 `Map<String, Object>` 登记表，演示 `BeanDefinition` 与 `BeanFactory` 的分工；
  3. 写 07：手写构造器/字段/setter 三种注入，并用手写容器复现"循环依赖"失败的报错；
  4. 写 08：给手写容器加 `close()` 与逆序销毁日志；
  5. 写 09：手写"扫描 + 条件注册 + 用户定义优先"的 30 行自动配置内核；
  6. 跑校验到 0。
- 验证：`python scripts/verify_course.py --dir content/courses/spring` → 期望 `通过：10 个课程文件（含代码执行）`

### TASK-003 | done | 第三章：横切（4 课）

- 对应：`REQ-002`、`REQ-003`、`REQ-004`、`AC-001`
- 依赖：TASK-002
- 新建：`content/courses/spring/10-手写动态代理.md`、`11-SpringAOP.md`、`12-事务就是切面.md`、`13-全局异常与校验.md`
- 接口：课 10 的 `<!-- run -->` 用 `java.lang.reflect.Proxy`；课 12 的 `<!-- run -->` 手工实现"快照 + 异常回滚"，与实战 L5 的 `TxTemplate` 同名同语义
- 测试：`scripts/verify_course.py --dir content/courses/spring`
- 步骤：
  1. 写 10：动态代理实现前后置日志，并演示自调用**不**经过代理；
  2. 写 11：讲 `@Aspect` 的切点表达式与 CGLIB/JDK 代理的选择，`<!-- run -->` 用手写代理复现"同类自调用失效"；
  3. 写 12：手写 `TxTemplate.run(Runnable)`，演示四个回调场景（与实战 L5 的断言一一对应）；
  4. 写 13：手写一个把异常映射成状态码的 `ExceptionResolver` 链 + 一个字段校验器；
  5. 跑校验到 0。
- 验证：`python scripts/verify_course.py --dir content/courses/spring` → 期望 `通过：14 个课程文件（含代码执行）`

### TASK-004 | done | 第四章：Web（3 课）

- 对应：`REQ-002`、`REQ-003`、`REQ-004`、`AC-001`
- 依赖：TASK-003
- 新建：`content/courses/spring/14-JSON.md`、`15-一次请求的完整旅程.md`、`16-配置从哪来.md`
- 接口：课 14 的 `<!-- run -->` 手写一个反射转 JSON 的 30 行序列化器（不引 Jackson）
- 测试：`scripts/verify_course.py --dir content/courses/spring`
- 步骤：
  1. 写 14：手写反射序列化器，讲清 Jackson 的字段名、null、日期三个坑；
  2. 写 15：手写"过滤器 → 路由 → 拦截器 → 业务"的四段链并按序打印，对照 Spring 的真实顺序；
  3. 写 16：手写一个四级配置覆盖（默认 → 文件 → 环境变量 → 命令行）的解析器；
  4. 跑校验到 0。
- 验证：`python scripts/verify_course.py --dir content/courses/spring` → 期望 `通过：17 个课程文件（含代码执行）`

### TASK-005 | done | 第五章：数据（2 课）

- 对应：`REQ-002`、`REQ-003`、`REQ-004`、`AC-001`
- 依赖：TASK-004
- 新建：`content/courses/spring/17-JdbcTemplate与连接池.md`、`18-手写MyBatis映射.md`
- 接口：课 18 的 `<!-- run -->` 手写"接口 + SQL 字符串 + 反射填值"的映射器，与项目自身的"手写 MyBatis、禁 MyBatis-Plus"规则一致
- 测试：`scripts/verify_course.py --dir content/courses/spring`
- 步骤：
  1. 写 17：用 `List<Map<String,Object>>` 模拟结果集，讲清连接池为什么存在、`ResultSet` 为什么要逐行读；
  2. 写 18：手写从 SQL 到对象列表的映射，讲清 `#{}` 与 `${}` 的差别为什么是安全问题；
  3. 跑校验到 0。
- 验证：`python scripts/verify_course.py --dir content/courses/spring` → 期望 `通过：19 个课程文件（含代码执行）`

### TASK-006 | done | 第六章：交付（2 课）+ 全量校验

- 对应：`REQ-002`、`REQ-003`、`REQ-004`、`AC-001`、`AC-002`、`AC-007`
- 依赖：TASK-005
- 新建：`content/courses/spring/19-切片测试.md`、`content/courses/spring/20-打包与部署.md`
- 接口：无新增符号
- 测试：`scripts/verify_course.py --dir content/courses/spring`、`ls | wc -l`、`rg`
- 步骤：
  1. 写 19：手写一个"只替换依赖、不启动整容器"的装配器，讲清 `@WebMvcTest` 切的是什么；
  2. 写 20：手写 `jar` 的清单与 `--spring.profiles.active` 的生效顺序，列启动失败的六条报错原文；
  3. 跑全量校验与计数；
  4. 跑 `rg "spring-boot|spring-core" content/courses/spring/` 确认只出现在正文与非运行块。
- 验证：
  - `python scripts/verify_course.py --dir content/courses/spring` → `通过：21 个课程文件（含代码执行）`
  - `ls content/courses/spring/*.md | wc -l` → `21`
  - `order` 从 0 到 20 无缺号

### TASK-007 | done | 实战线 spring-handmade（总览 + 5 关）

- 对应：`REQ-005`、`REQ-006`、`AC-003`、`AC-004`
- 依赖：TASK-002（L1/L2 的契约）、TASK-003（L4/L5 的契约）
- 新建：`content/courses/spring-handmade/00-总览.md` 与 `01`–`05` 五课，每课的 `code-challenge` 的 `files` 里带固定 `TestMain.java`
- 接口：严格按 `design.md` 的「实战 5 关的固定契约」；`Main.java` 里的类名、方法名、签名一律不得改动
- 测试：本机交叉验证脚本（临时目录 + `javac --release 21` + `java TestMain`），starter 版与答案版各跑一次
- 步骤：
  1. 写总览（五关怎么排、与课程 05–12 的对应关系、判题方式）；
  2. 写 L1：starter 留空实现，`TestMain` 断言构造器注入与单例；
  3. 写 L2：加作用域与逆序销毁断言；
  4. 写 L3：路由表与 404/405；
  5. 写 L4：动态代理与自调用绕过；
  6. 写 L5：事务快照、回滚、吞异常、自调用；
  7. 逐关跑 starter（必须编译通过、运行失败）与答案版（必须全绿）；
  8. 跑 `verify_course.py --dir content/courses/spring-handmade` 到 0。
- 验证：
  - `python scripts/verify_course.py --dir content/courses/spring-handmade` → `通过：6 个课程文件`
  - 五关答案版 `java -cp out TestMain` 全部打印 `ALL PASS`
  - 五关 starter 版 `javac` 返回 0、`java TestMain` 非 0

### TASK-008 | done | 岗位地图与 Spring 阅读线定位修正

- 对应：`REQ-007`、`REQ-008`、`AC-005`
- 依赖：TASK-001（`spring-from-zero` 的 slug 存在）
- 修改：`frontend/packages/knowledge/src/role-map.ts`、`content/reading/spring/00-总览.md`
- 接口：`TIERS.backend.core` 与 `TIERS.backend.advanced` 按 `design.md` 的目标形态
- 测试：`frontend/packages/knowledge/src/recommend.test.ts`
- 步骤：
  1. 改 `role-map.ts` 两个数组；
  2. 跑 `pnpm --filter @paideia/knowledge test`，按失败断言修正；
  3. 改 `spring/00-总览.md`：`## 六篇的走法` → `## 八篇的走法`，补 `07`/`08` 两行，`learning-path` steps 补到 8 站，`line_summary` 加"写过之后再来读"的定位，并在开头补一句指向 `spring-from-zero`；
  4. 再跑一次测试。
- 验证：`cd frontend && pnpm --filter @paideia/knowledge test` → 期望全绿（0 failed）

### TASK-009 | done | 导入 dry-run 与全量复核

- 对应：`AC-006`、`AC-001`、`AC-003`
- 依赖：TASK-006、TASK-007、TASK-008
- 修改：无（只验证）
- 测试：`import-course.mjs --dry-run`
- 步骤：
  1. 跑两条 `--dry-run`，确认待写入条数与 `21 + 6` 一致；
  2. 重跑两条 `verify_course.py`；
  3. 重跑前端单测；
  4. 把结果写进 `.agent/history/updates.md`。
- 验证：
  - `node scripts/import-course.mjs --dry-run --dir content/courses/spring` → 退出码 0、列出 21 条
  - `node scripts/import-course.mjs --dry-run --dir content/courses/spring-handmade` → 退出码 0、列出 6 条

## 完成条件

- AC-001 … AC-007 全部有本次实际执行输出作为证据。
- 真实导入与部署不在本项完成条件内（用户未授权部署）。
