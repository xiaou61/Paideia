---
artifact: tasks
work_id: WORK-001
work: 架构选型与项目骨架
status: approved
created: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 10:27:19 +0800
approver_role: CTO
---

# 架构选型与项目骨架实施任务

## 依据

- 已批准需求：`requirements.md`（REQ-001..REQ-008，AC-001..AC-008）
- 已批准提案：`proposal.md`
- 本工作项设计：`design.md`
- 技术选型裁决记录：`.agent/references/technology-options.md`、`.agent/rules/always.md`

## 全局约束

- 只建架构与技术底座，**不引入任何业务模块、业务实体或业务功能**。
- 后端模块间依赖必须在 `pom.xml` 声明；跨模块访问对方内部实现包由 Modulith 校验拦截。
- 一切连接信息、密钥只从环境变量或被忽略的本地配置读取；仓库内只放 `.env.example` 占位。本仓库为公开仓库。
- 数据库只面向 MySQL，可用其专有语法；主键为自增；分页由 MySQL 分页组件产生。
- 前端共享包（`packages/ui`、`packages/core`）不得引用桌面壳专有 API；资源路径必须相对。
- 每项任务的完成以「验证」列出的命令实际执行为准；未运行的检查在报告中如实写为未运行。

## 任务

### TASK-001 | done | 后端 Maven 多模块工程与模块边界强制

- 完成：2026-10-09
- 结果：`mvn -B verify` BUILD SUCCESS（paideia-platform 7 个测试、paideia-app 的 ModularityTest 通过）；`GET /actuator/health` 返回 200 与 status UP；向 paideia-platform 注入对 paideia-app 的依赖后 `mvn compile` 因 cyclic reference 失败，还原后恢复成功；TASK-002 引入第二个模块后补验了 Modulith 层：`web` 访问 `platform.internal` 使 ModularityTest 报 "depends on non-exposed type" 并失败，还原后恢复
- 对应：`REQ-005`、`AC-002`、`AC-004`
- 依赖：无
- 修改（计划）：`backend/pom.xml`、`backend/paideia-platform/pom.xml`、`backend/paideia-platform/src/main/java/io/github/xiaou61/platform/**`、`backend/paideia-app/pom.xml`、`backend/paideia-app/src/main/java/io/github/xiaou61/PaideiaApplication.java`、`backend/paideia-app/src/test/java/io/github/xiaou61/ModularityTest.java`
- 测试（计划）：`backend/paideia-platform/src/test/java/io/github/xiaou61/platform/PlatformContractTest.java`、`backend/paideia-app/src/test/java/io/github/xiaou61/ModularityTest.java`
- 步骤：
  1. 建聚合父 POM：JDK 25、Boot 4.1.1 依赖管理、模块清单。
  2. 建 `paideia-platform`：`ApiResponse<T>`、`ErrorCode`、`PageQuery`、`PageResult<T>`、`BizException`，并标注 `@ApplicationModule(type = OPEN)`。
  3. 建 `paideia-app`：主类 `PaideiaApplication` 于基础包根、Actuator 依赖、`GET /actuator/health` 可访问。
  4. 接入 Spring Modulith 与 `ApplicationModules.of(PaideiaApplication.class).verify()` 测试。
  5. 人为在 `paideia-platform` 中反向引用 `paideia-app` 的类型（或删除 pom 中已声明的依赖），确认构建失败，记录输出后回退。
- 验证：`cd backend && mvn -q verify` 通过；人为制造非法跨模块依赖时 `mvn -q verify` 失败（附记录）

### TASK-002 | done | Web 基础设施：统一响应、全局异常、请求上下文

- 完成：2026-10-09
- 结果：`mvn -B verify` BUILD SUCCESS（paideia-web 9 个测试：TraceIdFilterTest 4 个、WebPipelineTest 5 个）；覆盖正常包装、业务异常映射 404、校验失败映射 400、未捕获异常兜底 500、已包装响应不二次包装、追踪标识沿用与非法值丢弃、请求结束清理 MDC
- 对应：`REQ-004`、`AC-002`
- 依赖：TASK-001
- 修改（实际）：`backend/pom.xml`（登记新模块与依赖管理）、`backend/paideia-web/**`、`backend/paideia-app/pom.xml`、`backend/paideia-app/src/main/resources/application.yml`
- 测试（实际）：`backend/paideia-web/src/test/java/io/github/xiaou61/web/{TraceIdFilterTest,WebPipelineTest,ProbeController,WebSliceTestApplication}.java`

### TASK-003 | done | 前端 workspace 与主体 SPA 骨架

- 完成：2026-10-09
- 结果：`pnpm -r typecheck` 四个包全部通过；`pnpm -r test` 通过（core 6 个用例）；`pnpm build` 产出 `dist/index.html` 与 `./assets/index-*.js`（相对路径，桌面壳所需）；`pnpm test:e2e` 2 个用例通过——Playwright 自行拉起后端与前端，断言页面 `platform-kind` 为 `web`、`health-status` 为 `UP`、原始响应体含后端字段，并校验响应带 `X-Trace-Id`
- 实施期决定：路由用 `createHashRouter`（桌面壳从自定义协议加载页面，基于历史的路径路由会失效）；开发期用 Vite 的 `/api`、`/actuator` 代理避免跨域，桌面壳没有代理，届时须把其来源加入后端 CORS 允许列表；令牌读取在 `createApiClient` 的 `getToken` 处预留为返回 null，TASK-005 只改这一处
- 对应：`REQ-004`、`AC-003`
- 依赖：TASK-002
- 修改（实际）：`frontend/package.json`、`frontend/pnpm-workspace.yaml`、`frontend/tsconfig.base.json`、`frontend/packages/{ui,core,platform-web}/**`、`frontend/apps/app/**`
- 测试（实际）：`frontend/packages/core/src/api.test.ts`、`frontend/apps/app/e2e/home.spec.ts`

### TASK-004 | done | 持久层底座：Flyway、MyBatis 装配与 MySQL 分页组件

- 完成：2026-10-09
- 结果：`mvn -B clean verify`（带 `PAIDEIA_TEST_DB_PASSWORD`）BUILD SUCCESS，PaginationIntegrationTest 4 个用例在**真实 MySQL** 上通过（页大小与总数正确、翻页不重不漏、越界页返回空、归属过滤只返回本用户行且对照查询证明过滤是生效的那一步、超限页大小被收敛）。未配置口令时该测试类跳过并在 stderr 明确提示，构建不因此变红
- 实施期决定（偏离设计初稿，均为减少机械复杂度，理由记在 design.md）：
  1. **不做 SQL 改写拦截器**。设计初稿写的是 `MySQLPageInterceptor` 按参数追加 LIMIT 并生成 COUNT；实施时改为约定式显式分页——`PageQuery` 提供 `limit()`/`offset()`，mapper 自己写 `LIMIT #{limit} OFFSET #{offset}`，总数由独立的 count 语句提供。自动改写 SQL 是 PageHelper 那类方案出问题的根源，而省下的只是每处两行 SQL
  2. **审计字段改用 MySQL 列默认值**（`DEFAULT CURRENT_TIMESTAMP`），不在持久层做统一填充程序。原来的"不依赖数据库默认值"是为多库可移植服务的，该约束已撤销，MySQL 默认值是这里最少代码且最可靠的做法
- 对应：`REQ-003`、`AC-005`
- 依赖：TASK-001
- 修改（实际）：`backend/pom.xml`、`backend/paideia-persistence/**`、`backend/paideia-app/pom.xml`、`backend/paideia-app/src/main/resources/db/migration/.gitkeep`、`backend/paideia-platform/src/main/java/io/github/xiaou61/platform/PageQuery.java`
- 测试（实际）：`backend/paideia-persistence/src/test/java/io/github/xiaou61/persistence/PaginationIntegrationTest.java`
- 运行前提：需要 `PAIDEIA_TEST_DB_PASSWORD`（及其余可选读数）与 `127.0.0.1:3307` 的 SSH 隧道

### TASK-005 | done | 认证与授权：JWT、AuthPort、越权拒绝

- 完成：2026-10-09
- 结果：`mvn -B verify`（带凭据）BUILD SUCCESS；JwtAuthServiceTest 8 个用例通过（签发校验往返、篡改载荷被拒、异密钥签发被拒、过期被拒、格式非法被拒、弱密钥在构造期失败、缺密钥可被识别、默认值生效）；AuthorizationIsolationTest 6 个用例在真实 MySQL 上通过（无令牌 401、主体取自令牌、伪造令牌 401、两个令牌各自只看到自己的行、指定他人归属 403、失败响应不泄堆栈）；真实 HTTP 手验：健康 UP、无令牌 401、签发令牌后 `/api/v1/me` 返回 `learner-a`
- 实施期决定：
  1. **未配置密钥时生成一次性随机密钥并告警**，而不是拒绝启动。原因是应用现在必须有数据源才能起来，再多一个启动前置条件会让骨架开箱跑不动；随机密钥无法跨重启利用，风险显著低于写死的默认密钥。配置了密钥则校验长度（≥32 字符）
  2. **本地配置放 `backend/config/application-local.yml`**（被 gitignore），不放 `src/main/resources`——放在资源目录会被打进 jar，产物里就带着数据库口令
  3. **修复了一个真实缺陷**：`ApiResponseBodyAdvice` 原先直接跳过已包装的响应，导致控制器自己构造 `ApiResponse` 的接口 `traceId` 恒为 null。现在会补上标识，并加了断言防回归
  4. **测试夹具经 test-jar 复用**：`paideia-persistence` 构建 test-jar，`paideia-app` 的隔离测试直接复用同一份示例表迁移与 mapper，避免两处各写一份日后漂移
  5. 测试用密钥写在 `src/test/resources/application-test.yml`，带明文标注"仅测试，任何真实环境不得复用"
- 对应：`REQ-006`、`AC-006`
- 依赖：TASK-002、TASK-004
- 修改（实际）：`backend/pom.xml`、`backend/paideia-security/**`、`backend/paideia-app/pom.xml`、`backend/paideia-app/src/test/**`、`backend/paideia-web/src/main/java/io/github/xiaou61/web/ApiResponseBodyAdvice.java`、`frontend/apps/app/src/pages/HomePage.tsx`、`frontend/apps/app/e2e/home.spec.ts`、`frontend/apps/app/playwright.config.ts`
- 测试（实际）：`backend/paideia-security/src/test/java/io/github/xiaou61/security/JwtAuthServiceTest.java`、`backend/paideia-app/src/test/java/io/github/xiaou61/AuthorizationIsolationTest.java`、`frontend/apps/app/e2e/home.spec.ts`
- 运行前提：后端启动需要数据源，本地用 `--spring.profiles.active=local`（读被忽略的 `backend/config/application-local.yml`），因此 e2e 也依赖 3307 隧道

### TASK-006 | done | Electron 桌面壳与平台适配器双实现

- 结果补充（安装包已产出）：`Paideia Setup 0.0.1.exe` 111,510,247 字节。**关键坑**：只设 `ELECTRON_BUILDER_BINARIES_MIRROR` 不够，`electron-builder` 还会去 GitHub 拉 Electron 运行时；必须同时设 `ELECTRON_MIRROR`。完整命令：
  `cd frontend && ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/" ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries" pnpm --filter @paideia/desktop exec electron-builder`
- 打包产物运行时验证：启动 `release/win-unpacked/Paideia.exe` 后，渲染静态服务返回 200，后端 `/actuator/health` 请求计数由 1.0 增至 2.0，Paideia 进程 4 个，CORS 白名单来源获得放行头
- 完成：2026-10-09
- 结果：
  - 已完成：`packages/platform-desktop`（读 preload 注入的桥，不 import Electron）；`apps/desktop`（Electron 主进程用**自带本地静态服务**提供渲染进程，故页面来源是真实 http 来源 `http://127.0.0.1:5310`，不必放行语义含糊的 `null` 来源；preload 经 contextBridge 只暴露缓存能力）；组合根按构建模式选择平台实现；`pnpm -w build:desktop` 的前半段（SPA desktop 模式构建 + 渲染产物就位）通过；静态检查通过
  - 运行时已验证：启动桌面应用后，`http://127.0.0.1:5310/` 返回 HTTP 200，后端 `/actuator/health` 的请求计数由 1.0 增至 2.0——说明桌面端确实渲染并成功调用了后端
  - 已产出 Windows 安装包，并用打包后的应用完成运行时验证（见上方结果补充）
  - **未运行**：`apps/desktop` 的 Playwright Electron 用例未在本机跑通——本环境里 node 无法 spawn `cmd.exe`（实测 `error=ENOENT`，而该文件存在），Playwright 在 Windows 上正是经 cmd.exe 启动 Electron。该用例保留，在正常 shell 或 CI 中应可运行
- 实施期变更：
  1. **桌面壳由 Tauri 改为 Electron**（用户于 2026-10-09 决定）：Tauri 要求 Rust + MSVC，本机都没有；Electron 只需 Node。代价是产物与内存变大
  2. 组合根静态引入两种平台实现：`platform-desktop` 不 import Electron，打进 Web 产物无害且不会被调用
  3. 新增 `.env.desktop`（只含后端地址，无凭据）并在 `.gitignore` 中显式放行——它必须进仓库，否则桌面构建拿不到后端绝对地址
  4. **顺带修掉一个真实缺陷**：CORS 只映射了 `/api/**`，而前端还会调 `/actuator/health`；且 Actuator 端点由自己的 HandlerMapping 处理，**不吃** `WebMvcConfigurer` 的 CORS 注册，必须另配 `management.endpoints.web.cors.*`。同时给安全链加上 `.cors()`，否则预检会被认证要求挡成 401
- 对应：`REQ-007`、`AC-007`
- 依赖：TASK-003
- 修改（实际）：`frontend/packages/platform-desktop/**`、`frontend/apps/desktop/**`、`frontend/apps/app/**`、`frontend/package.json`、`frontend/pnpm-workspace.yaml`、`.gitignore`、`backend/paideia-web/src/main/java/io/github/xiaou61/web/WebCorsConfiguration.java`、`backend/paideia-security/src/main/java/io/github/xiaou61/security/SecurityConfiguration.java`、`backend/paideia-app/src/test/**`
- 测试（实际）：`frontend/packages/core/src/workspace-boundaries.test.ts`（共享包不得引用桌面壳 API，2 个用例）、`frontend/apps/desktop/e2e/desktop.spec.ts`（未在本机运行）、`backend/paideia-app/src/test/java/io/github/xiaou61/AuthorizationIsolationTest.java` 中的 3 个 CORS 用例

### TASK-007 | done | 公开页与 SEO 接入路径验证

- 完成：2026-10-09
- 结果：`pnpm --filter @paideia/public build` 通过，两遍构建（客户端 + SSR）后 `renderToString` 的 699 个字符正文被注入 `dist/index.html`，无 JavaScript 也能读到内容；surface 隔离双向对照通过——桌面端产物不含公开页文案（`grep` 退出码 1），公开页产物不含主体应用文案
- 实施期决定：用「客户端构建 + SSR 构建 + 注入」三小步实现预渲染，而不是引入预渲染插件——需要的只是 `renderToString` 一次调用。SSR 构建必须把工作区包设为 `ssr.noExternal`，否则 Node 无法直接加载导出 TypeScript 源码的包
- 对应：`REQ-008`、`AC-008`

- 对应：`REQ-008`、`AC-008`
- 依赖：TASK-003
- 修改（计划）：`frontend/apps/public/**`、`frontend/pnpm-workspace.yaml`
- 步骤：
  1. 建 `apps/public`：一个公开页面，构建产出预渲染的静态 HTML，与 `apps/app` 共享 `ui`/`core`。
  2. 确认该 surface 不进入桌面构建产物。
  3. 在设计文档的对应位置写明后续接入 SSR 的路径（保留说明，不实现）。
- 验证：`cd frontend && pnpm --filter public build` 产出含内容的静态 HTML（非空壳）；`pnpm -w build:desktop` 产物中不含公开页
- 备注：本期只验证路径可行，不实现完整 SEO

### TASK-008 | done | 验证报告与交付说明

- 完成：2026-10-09
- 结果：新增 `testing/plan.md`（测试范围、环境前置、验收矩阵、已知缺口）与 `testing/report.md`（状态 `partial`，带结构化证据矩阵，每个 AC 一行命令/退出码/结果/证据位置）；证据落盘在 `testing/logs/` 四个文件；`.agent/rules/always.md` 的构建命令已由占位替换为实测命令；`.agent/INDEX.md` 已登记后端 6 项与前端 8 项模块
- 报告状态为 `partial` 的原因：AC-007 的安装包一项未完成（网络），桌面端 Playwright 用例在本机未运行（环境不允许 spawn `cmd.exe`）

- 对应：`AC-001`..`AC-008`
- 依赖：TASK-001..TASK-007
- 修改（计划）：`.agent/changes/WORK-001-架构选型与项目骨架/testing/plan.md`、`testing/report.md`、`.agent/rules/always.md`（补齐实际构建与检查命令）、`.agent/INDEX.md`（登记模块与路径）
- 步骤：
  1. 逐条 AC 记录实际执行的命令与结果；未运行的项如实写为未运行并说明原因。
  2. 把真实的构建与检查命令写进项目常驻规范，替换当前的占位说明。
  3. 在 `.agent/INDEX.md` 登记前端与后端模块的位置与用途。
- 验证：`project-lifecycle.ps1 validate` 通过；报告中的每条命令可复现

## 完成条件

- TASK-001..TASK-008 全部为 `done`。
- AC-001..AC-008 均有可复现的验证记录；未运行的检查明确标注。
- 项目的构建与检查命令已写入 `.agent/rules/always.md`。
- 未引入任何业务模块或业务功能；`events`、`ai`、`storage` 仍未创建。
