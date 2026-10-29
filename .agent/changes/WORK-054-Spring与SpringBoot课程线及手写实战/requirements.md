---
artifact: requirements
work_id: WORK-054
work: Spring 与 Spring Boot 课程线及手写实战
status: approved
mode: strict
mode_reason: 跨内容（content/）、脚本（verify_course.py）、前端岗位地图与学习线数据；新增一条课程线、一条实战线，并改变 /learn 的岗位定位与站序
workflow: full
depends_on: []
related_to: [WORK-020, WORK-038, WORK-047]
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 14:51:02 +0800
approver_role: CTO
---

# WORK-054 Spring 与 Spring Boot 课程线及手写实战需求

## 已确认事实

- `content/courses/java/00-总览.md:82` 原文：**「离写 Spring 服务还差一层：那层是「Spring」阅读线的内容」**——Java 从零开始 38 课只到 Java SE，不含任何 Web/框架内容。
- `content/reading/spring/00-总览.md:33` 原文：**「这条线假设你写过 Spring Boot 项目」**——它是一篇"写过之后的深入篇"，前提正是没人教的那一步。
- 全仓搜 `@RestController` / `@GetMapping` / `SpringApplication.run`：只命中 4 篇**阅读**文章（`code-reading/01`、`spring/00`、`spring/06`、`spring/07`）。没有任何一篇教"从 `pom.xml` 走到 `curl` 返回 200"。`pom.xml` 只在 `courses/java/15-测试与构建.md` 顺带提过一次。
- Spring 阅读线共 9 个文件（`00-总览` + `01`–`08` 八篇正文），`code-challenge` 动手块 **0 个**，quiz 每个正文 1 个。总览写"六篇的走法"、`learning-path` 也写六站，`07`/`08` 后补未更新。
- 现有实战线 4 条，全部是单文件或单方法题：
  | 线 | 形态 | 交付物 |
  | --- | --- | --- |
  | `sql-practice` | SQL 真执行 | 一条查询 |
  | `java-debug` | 单文件真运行 | 修好的 `Main.java` |
  | `code-reading` | 填空 | 若干空 |
  | `java-project` | 多文件 + 固定测试入口 | 一个可编译的小项目 |
- `frontend/packages/knowledge/src/role-map.ts:65` 把 `spring` 放在后端岗位 `core` 数组第 2 位（`java-from-zero` 之后）。
- 沙箱判题（`paideia-sandbox`）：镜像 `eclipse-temurin:21-jdk-alpine`，`--network=none`、只读根、`/tmp` 64MB tmpfs、非 root、256MB 内存上限；语言仅 `java` / `python` / `sql`。**镜像里没有任何 Spring 依赖**。
- `backend/paideia-sandbox/images/` 只有一个自建镜像（mysql）。**本机没有 Docker**（WORK-032 已记录），所以任何需要新镜像的判题都无法在本机验证。
- `scripts/verify_course.py` 对 `content/courses/` 强制：七个小节按序、≥2 条 `### 误区：`、≥1 个 `code-challenge`、恰好 1 个 `quiz`、`## 讲解` 里至少一个 `<!-- run -->` 且输出与紧随的 ```text 逐字相同。Java 示例会被写成单文件 `Main.java` 用 `javac` 编译后 `java Main` 执行。
- `run-challenge.tsx:76-92`：多文件项目模式由正文 `files` 字段带固定支撑文件（`TestMain.java`），前端只允许改 `Main.java`——`java-project` 线正是这样判题的，且**不需要任何镜像改造**。
- 课程导入唯一入口 `scripts/import-course.mjs`：一条线 = 一个目录 + `00-总览.md`（含 `line_name` / `line_kind` / `line_sort`）+ N 课；改内容走 PR 改 markdown 再导入，不在管理端手抄。

## 用户决定

- 2026-10-29 用户确认："补齐 Spring / Spring Boot 相关的东西，完整到一直到 Spring Boot"。
- 2026-10-29 用户确认："各种原理都要讲明白的那种"——机制优先，不只给用法。
- 2026-10-29 用户确认："SpringBoot 相关的课程你也可以看看网上一些好的开源项目之类的，按照他们的思路来弄"——参考成熟开源教材的组织思路，不自己发明目录。
- 2026-10-29 用户确认："现在真的太少了，要加很多很多"——规模要求：课程线与实战线都要成体量，不是补两三篇。
- 2026-10-29 用户确认"开始吧"，授权直接建项并实施，不再逐项确认。

## 实现选择

- **可运行示例一律是纯 Java 手写最小实现，不贴 Spring 代码**。原因有三：① `verify_course.py` 用裸 `javac` 执行，沙箱里也没有 Spring 依赖，贴 Spring 代码只能变成"不能运行的块"；② 手写一遍容器/代理/路由，比读注解更能讲清原理，正对"原理都要讲明白"；③ 真 Spring 代码放在**非运行块**里作对照，读者能看到"我们手写的这 30 行，就是它在做的事"。
- **实战线用现有 `java-project` 的 project 模式判题，不新建镜像**。手写一个迷你 Spring（容器 → 路由 → 代理 → 事务 → MVC），5 关各带固定 `TestMain`。理由：本机没有 Docker，任何需要新镜像的方案都无法验证，按"无证据不算通过"不能写进交付；而手写判题完全走已有沙箱，本机可用 `javac --release 21` + `java` 复现。
- **不新增后端接口、不新增迁移、不改数据库**。学习线与课程仍由 `import-course.mjs` 导入。
- 课程目录 `content/courses/spring/`，分类 slug `spring-from-zero`，与 `java-from-zero` / `mysql-from-zero` 同构。
- 实战目录 `content/courses/spring-handmade/`，`line_kind: reading` + `line_mode: practice` + `verify: project`（与 `java-project` 完全同构——它也是放在 `content/reading/` 下的 practice 线，但为了避免与阅读线混排，本次放 `content/courses/` 并显式声明 `line_kind: reading`）。**待实施时以 `java-project` 的实际目录与导入行为为准**。

## 背景

平台已经有 25 条学习线、38 课 Java、15 课 MySQL、21 课 Python，后端岗位的地图把 Spring 排在核心第 2 位。但那条"核心"是 9 篇没有动手块的阅读文章，而它自己声明"假设你写过 Spring Boot 项目"。中间那一级台阶（怎么从零写出一个能跑的 Spring Boot 服务、以及它内部怎么工作）在平台上是空的。

同时，所有实战线都停在"单文件一个小函数"，学习者从头到尾没有经历过"有 URL、有生命周期、有分层"的东西。

## 目标

1. 新增课程线 `spring-from-zero`：从 Maven 与第一个 `curl` 走到打包部署，**每一课的机制用手写最小实现演示**，共 20 课 + 总览。
2. 新增实战线 `spring-handmade`（手写一个迷你 Spring）：5 关，用固定 `TestMain` 真判题，覆盖容器 / 路由 / 代理 / 事务 / MVC。
3. 修正岗位地图与站序：`spring-from-zero` 进后端核心第 2 位；`spring` 阅读线按它的真实定位（写过之后读的深入篇）调整；阅读线总览"六篇"改为实际篇数。
4. 全程不引入新依赖、不需要 Docker、不改数据库。

## 非目标

- 不引入 Spring 依赖到沙箱镜像（本机无 Docker，无法验证）。
- 不做"给每个学习者开一台常驻机器跑自己的服务"。
- 不重写已有 9 篇 Spring 阅读文章的正文（只在需要时修正总览的篇数与前提描述）。
- 不做 Spring Cloud / 微服务 / 响应式的课程（本期只到单体 Web 服务）。
- 不改 `verify_course.py` 的结构校验规则（除非出现"本机通过、沙箱失败"的真实证据）。

## 用户与工作流程

- **完全没写过 Web 的人**：从 `java-from-zero` 学完 Java SE 之后，进 `spring-from-zero` 第一课，一路到"打包成 jar 并在自己机器上跑起来"。
- **想搞懂原理的人**：每课的"讲解"先手写机制，再看 Spring 怎么写；"动手"是改一段纯 Java 代码让固定用例变绿。
- **想要手感的人**：进 `spring-handmade`，从 0 写出一个能被测试入口调用的迷你框架。
- **已经会写 Spring 的人**：`spring` 阅读线仍在，讲代理、事务失效、循环依赖这些坑。

## 功能需求

- **REQ-001** 新增 `spring-from-zero` 课程线，`line_kind: course`，站序在 `java-from-zero` 之后、`spring` 深入线之前。
- **REQ-002** 课程覆盖交付链路的最小完整集：构建工具与项目结构、分层、参数绑定、返回值与状态码、JSON、IoC 容器、依赖注入与循环依赖、作用域与生命周期、自动配置与自定义 starter、配置优先级与 Profile、动态代理与 AOP、事务、全局异常与校验、过滤器与拦截器、JdbcTemplate 与连接池、手写 MyBatis 映射、切片测试、启动流程与失败排查、打包与部署。
- **REQ-003** 每一课的 `## 讲解` 含至少一个**纯 Java 手写机制**的可运行示例，输出被逐字校验；真 Spring 代码放非运行块。
- **REQ-004** 每一课有 `code-challenge` 动手块（`kind` 只允许 `fill` / `order` / `bug` / `run`）与 3 题 `quiz`；答案能从本课正文推出。
- **REQ-005** 新增 `spring-handmade` 实战线，5 关，每关带固定 `TestMain.java`（`files` 字段）作为只读测试入口，学习者只改 `Main.java`。
- **REQ-006** 实战线 5 关分别覆盖：反射容器与构造器注入、作用域与生命周期、注解扫描与路由表、动态代理实现前后置通知、事务边界与自调用失效。
- **REQ-007** 岗位地图：`spring-from-zero` 进后端 `core`，排在 `java-from-zero` 之后；`spring` 阅读线按真实定位调整。
- **REQ-008** `spring` 阅读线总览中"六篇"与实际篇数一致。

## 质量要求

- 所有 Java 示例必须在 **Java 21** 下编译通过（沙箱基线），不使用 22+ 语法。
- 中文正文遵守既有文风：先结论后原因、术语用大白话解释、每节带具体例子（`.agent/notes/dev-writing` 与既有课程为准）。
- 不复制网上教材原文；只借用其章节组织思路。
- 课程正文不得出现任何凭据、内网地址、真实口令。

## 约束与依赖

- 依赖 `scripts/verify_course.py`（结构 + 真执行）与 `scripts/import-course.mjs`（导入）。两者不改规则。
- 依赖沙箱已有的 `java` 语言与 project 模式（`--network=none`、JDK 21、256MB）；**不新增镜像**。
- 导入需要运行中的后端（`PAIDEIA_BASE_URL`，默认 `http://127.0.0.1:8081`）与管理端凭据；本项只保证 `--dry-run` 与本地校验通过，真实导入属部署动作。

## 关联工作项

- 承接 `WORK-020`（学习线与从零课程：建立了 `content/courses/` 的内容源与导入流程）。
- 承接 `WORK-038`（实战线的 project 判题模式与 `line_mode: practice`）。
- 软关联 `WORK-047`（岗位地图 `role-map.ts`；本项只调整条目，不改机制）。
- 与 `WORK-053` 无重叠：本项零后端接口、零迁移、零前端页面。

## 验收标准

- **AC-001** `python scripts/verify_course.py --dir content/courses/spring` 退出码 0，且输出包含"含代码执行"（证明每个 `<!-- run -->` 都真跑过、输出逐字一致）。
- **AC-002** `content/courses/spring/` 恰好 21 个文件（1 总览 + 20 课），`order` 从 0 到 20 连续无缺、无重复。
- **AC-003** `python scripts/verify_course.py --dir content/courses/spring-handmade` 退出码 0，5 关各自有 `code-challenge`。
- **AC-004** 实战线 5 关的**答案版**（把答案填进 `Main.java`）在本机 `javac --release 21` 编译 + `java TestMain` 后全部用例通过；**starter 版**（未改）在同一入口下失败——两者都要有实际运行输出。
- **AC-005** `cd frontend && pnpm --filter @paideia/knowledge test` 全绿（`role-map` 改动不破坏 `recommend` 的既有断言）。
- **AC-006** `node scripts/import-course.mjs --dry-run --dir content/courses/spring` 与 `... --dir content/courses/spring-handmade` 都退出码 0，且列出 21 + 6 条待写入。
- **AC-007** 全仓 `rg "spring-boot|spring-core" content/courses/spring/` 的命中只出现在**非运行块**与正文里，不存在任何 `<!-- run -->` 依赖 Spring 依赖。

## 假设

- 默认"课程"与"实战"都放在 `content/courses/` 下由 `import-course.mjs` 导入；`content/reading/` 只放纯阅读线。
- 假设平台 `/learn` 对 `line_kind: reading` + `line_mode: practice` 的实战线渲染方式与 `java-project` 一致（同为 practice 轨道）。

## 待确认问题

无。用户已授权直接实施，且本项不改变任何产品范围、数据边界或部署形态。
