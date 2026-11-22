---
artifact: design
work_id: WORK-001
work: 架构选型与项目骨架
status: approved
created: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 10:27:18 +0800
approver_role: CTO
---

# 架构选型与项目骨架设计

本设计只覆盖**架构与技术底座**。业务模块、业务实体、业务接口不在范围内。文中带「计划」标记的名称是尚未创建的文件与类。

## 系统上下文

- **后端**：Spring Boot 4.1.1 / JDK 25，Maven 多模块，MySQL 为唯一数据库，Flyway 管 schema，手写 MyBatis 为数据访问方式。
- **前端**：Vite + React Router SPA，一份代码同时构建 Web 产物与 Electron 桌面安装包；桌面端与 Web 同期交付。
- **中间件**：自托管在自有服务器。本期只需 MySQL；RabbitMQ 等 `events` 模块建立时接入。
- **外部接口**：模型供应商（D-09 未裁决，本期不接入）。桌面壳为 Electron，不需要额外工具链（Node 即可）；若回到 Tauri 才需要 Rust + MSVC + WebView2。

## 组件与职责

### 仓库结构（计划）

```
backend/                                   聚合父 POM（packaging=pom）
  paideia-platform/                        通用契约（普通闭包模块）
  paideia-web/                             Web 基础设施
  paideia-persistence/                     数据访问基础设施
  paideia-security/                        认证授权基础设施
  paideia-app/                             Spring Boot 启动模块
frontend/
  pnpm-workspace.yaml, package.json
  packages/ui/ libs
  packages/core/                           领域状态、API 客户端、Platform 与 Auth 端口
  packages/platform-web/                   平台能力的 Web 实现
  packages/platform-desktop/               平台能力的桌面实现
  apps/app/                                主体 SPA（Web 与桌面共用产物）
  apps/public/                             公开页与 SEO surface
  apps/desktop/                            Electron 壳
deploy/                                    docker-compose.yml、.env.example
```

### 后端模块（计划）

| 模块 | 包 | 职责 | 依赖 |
| --- | --- | --- | --- |
| `paideia-platform` | `io.github.xiaou61.platform` | `ApiResponse<T>`、`ErrorCode`、`PageQuery`、`PageResult<T>`、`BizException` 基类、时间与 ID 类型约定。**普通（闭包）模块**：对外契约全部位于包根，子包视为内部实现 | 无 |
| `paideia-web` | `io.github.xiaou61.web` | 全局异常处理、统一响应包装、参数校验装配、请求上下文与追踪标识（MDC）、CORS 配置 | `paideia-platform` |
| `paideia-persistence` | `io.github.xiaou61.persistence` | MyBatis 装配、MySQL 分页组件、审计字段填充、TypeHandler 注册点、事务边界约定 | `paideia-platform` |
| `paideia-security` | `io.github.xiaou61.security` | `AuthPort`（认证端口）、`JwtTokenService`（JWT 实现）、`CurrentUser` 上下文、权限校验注解、Spring Security 装配 | `paideia-platform` |
| `paideia-app` | `io.github.xiaou61` | 主类 `PaideiaApplication`、配置类、Modulith 边界校验测试、Flyway 迁移目录 | 以上全部 |

主类位于基础包根（`io.github.xiaou61`），其余模块为其直接子包——这是 Spring Modulith 识别模块的前提。

### 模块边界的强制方式

两层，缺一不可：

1. **Maven 层（编译期）**：模块间依赖必须在 `pom.xml` 声明。未声明即编译不过，循环依赖无法声明。这是模块**依赖方向**的硬边界。
2. **Modulith 层（构建期）**：`ApplicationModules.of(PaideiaApplication.class).verify()` 放在测试中，检查循环依赖与**跨模块访问内部实现包**。Maven 的 artifact 会导出全部包，所以"不该被外部使用的 `internal` 子包"只能靠这一层拦。

**关于 `paideia-platform` 的模块类型（实施期修正）**：设计初稿把它标为开放模块（`@ApplicationModule(type = OPEN)`），实施时撤销了这个标注。它的对外契约本来就全部位于包根，开放只会放行外部对 `platform.internal` 的访问、白白丢掉一条边界检查。改回普通闭包模块后，`web` 访问 `platform.internal` 会让构建失败——这一条已实测（见 tasks.md 的 TASK-001 结果）。**纪律**：只有确实需要外部访问内部实现的基础机制才考虑开放；新增共享机制前先确认它属于基础设施而非用例。

### 前端包（计划）

| 包 | 职责 | 硬约束 |
| --- | --- | --- |
| `packages/ui` | 纯展示组件、设计令牌 | 不得引用任何平台 API |
| `packages/core` | 领域状态装配、API 客户端、`Platform` 与 `Auth` 端口定义 | 不得引用 `electron` 或 `@tauri-apps/*`（有自动化检查） |
| `packages/platform-web` | `Platform` 端口的浏览器实现 | — |
| `packages/platform-desktop` | `Platform` 端口的 Electron 实现（读 preload 注入的桥，不 import Electron） | — |
| `apps/app` | 主体 SPA，消费 `core` 与由宿主注入的 `Platform` 实现 | 资源路径必须相对（Vite `base: './'`） |
| `apps/public` | 预渲染的公开页，与 `app` 共享 `ui`/`core` | 不进入桌面构建 |
| `apps/desktop` | Electron 壳：主进程用自带本地静态服务提供 `apps/app` 的构建产物 | — |

`Platform` 端口本期只定义骨架期真正需要的方法（本地缓存读写、运行环境标识）；文件读写、深链、自动更新等能力等出现实际需求时再加，避免定义一堆没人实现的方法。

**实施期确认（TASK-003 实际落地）**：

- **路由用 Hash 路由**（`createHashRouter`）。这样无论宿主把它放在什么来源下（Web 的 http、桌面壳的本地静态服务）都不需要额外配置；换成基于浏览器历史的路由就要为两端各配一次回落。
- **开发期用 Vite 代理**转发 `/api` 与 `/actuator` 到后端，因此开发期不需要跨域配置。桌面壳没有代理，其来源 `http://127.0.0.1:5310` 必须显式加入后端 CORS 允许列表，否则会出现"Web 能用、exe 报跨域"。**注意两处都要配**：`paideia.web.cors.allowed-origins` 管 `/api/**`，而 Actuator 端点由它自己的 HandlerMapping 处理，只认 `management.endpoints.web.cors.allowed-origins`。
- 端口实际形态：`kind` 与 `cache` 两项，与上面的最小集合一致。`packages/core` 不依赖 React，是纯 TypeScript。
- 令牌读取收在 `createApiClient` 的 `getToken` 上；TASK-005 接认证时只改这一处。
- 前端包直接导出 TypeScript 源码（无独立构建步骤），只有 `apps/app` 有构建产物。
- pnpm 12 默认启用供应链策略（含最小发布年龄门槛），安装较新版本时它会在 `pnpm-workspace.yaml` 追加 `minimumReleaseAgeExclude`，属预期行为。
- e2e 由 Playwright 同时拉起后端与前端（后者缺一不可：只起前端会拿到代理错误，验证不了"页面展示的是接口真实数据"）。

## 请求或事件流程

### 受保护请求

1. 客户端携带 `Authorization: Bearer <token>`。
2. `paideia-security` 的 JWT 过滤器解析令牌，校验签名与有效期，把主体写入 `CurrentUser` 上下文。
3. 控制器从上下文取身份，**不接受客户端传入的用户标识**。
4. 返回 `paideia-platform` 的契约类型。
5. `paideia-web` 包装为 `ApiResponse<T>`，异常由全局处理器兜底。

### 事件流程

本期不实现。`events` 模块建立后的既定路径：领域事件先与业务数据写入同一事务的 outbox 表，由中继投递到 RabbitMQ。不得在事务提交后直接发布。

### 请求上下文

追踪标识在过滤器入口生成并放入 MDC 与 `ApiResponse.traceId`；跨模块传递只用方法参数与上下文对象，不引入全局可变态。

## 接口与数据

骨架只暴露**技术性**接口，不含业务语义：

| 接口 | 用途 | 备注 |
| --- | --- | --- |
| `GET /actuator/health` | 启动与依赖健康检查 | AC-002 |
| `POST /api/v1/auth/token` | 用受控的测试主体签发令牌，验证认证链路 | 仅开发 profile 启用；上线前移除或加保护 |
| `GET /api/v1/me` | 返回令牌对应的主体 | AC-003 的前端联调目标，同时验证身份只来自令牌 |

契约类型：

- `ApiResponse<T>`：`code`、`message`、`data`、`traceId`。
- `PageQuery`：`page`、`size`、`sort`。**显式传参，不用 `ThreadLocal` 传分页状态。**
- `PageResult<T>`：`total`、`page`、`size`、`items`。

## 持久化与迁移

- **迁移工具**：Flyway，目录 `backend/paideia-app/src/main/resources/db/migration/`（计划），命名 `V<版本>__<描述>.sql`。启动时执行；迁移失败即启动失败，不静默降级。
- **主键**：`BIGINT AUTO_INCREMENT`，MyBatis 侧用 `useGeneratedKeys="true" keyProperty="id"` 取值。
- **分页组件（实施期修正：不做 SQL 改写拦截器）**。设计初稿写的是 `MySQLPageInterceptor` 按参数追加 `LIMIT` 并生成 `COUNT`。实施时改为**约定式显式分页**：
  - `PageQuery` 提供 `limit()` 与 `offset()`（`offset()` 返回 `long`，避免页码接近上限时溢出 `int`）。
  - mapper 自己写 `LIMIT #{limit} OFFSET #{offset}`，分页参数由调用方从 `PageQuery` 显式传入，不引入 `ThreadLocal`。
  - 总数由**独立的 count 语句**提供，不由框架改写生成。含 `GROUP BY`/`DISTINCT` 的语句尤其不能靠自动改写得到正确总数。
  - 不使用 `RowBounds`（它在驱动取回全部结果后才跳过，且不提供总数）。
  - 取舍理由：自动改写 SQL 是 PageHelper 那类方案出问题的根源（线程本地状态污染、COUNT 改写错误），而显式写法只多两行 SQL。节省的机械量不抵排查成本。
- **审计字段（实施期修正）**：创建/更新时间交给 MySQL 列默认值（`DEFAULT CURRENT_TIMESTAMP`，必要时 `ON UPDATE CURRENT_TIMESTAMP`），不在持久层做统一填充程序。初稿的"不依赖数据库默认值"是为多库可移植服务的，该约束已由用户撤销；在只面向 MySQL 的前提下，列默认值是最少代码且最可靠的做法。
- **测试用示例表**：位于 `backend/paideia-persistence/src/test/resources/db/testdata/`（版本号从 900 起，避开生产迁移），**不进入生产 schema**。授权隔离验证依赖它。

## 失败处理与恢复

- 全局异常处理把 `BizException` 与校验错误映射为稳定的 `ErrorCode` 与 HTTP 状态；未捕获异常兜底为 500 并携带 `traceId`，日志同时写入 `traceId`。
- 认证失败返回 401，越权返回 403；两者的响应体不暴露资源是否存在。
- 数据库不可用或迁移失败：启动失败，日志输出连接的目标主机与库名，**不含密码**。
- 模型与消息相关失败处理本期不涉及（模块未建）。

## 安全与权限

- **令牌**：自签 JWT（HS256），签名密钥只从环境变量或被忽略的本地配置读取，长度校验不低于 32 字节。`iat`、`exp` 必填。
- **无状态**：`SessionCreationPolicy.STATELESS`，不启用 cookie 会话，因此关闭 CSRF；这也使同一套认证在桌面壳中可用。
- **可扩展点**：业务代码只依赖 `AuthPort`；换 OIDC 等机制时替换该端口的实现与解码器配置，不改业务代码。
- **CORS**：只允许配置中显式列出的来源；未列入的来源会被直接拒为 403（不是返回 200 少一个放行头）。桌面壳来源 `http://127.0.0.1:5310` 必须显式配置。安全链必须加 `.cors()`，否则预检 OPTIONS 会先被 `anyRequest().authenticated()` 挡成 401，表现为"同源可以、跨域全挂"。
- **凭据边界**：连接信息一律来自环境变量或被忽略的本地配置文件；仓库内只放 `.env.example` 占位。数据库端口对公网开放是用户已确认的取舍，不再收紧。
- **身份来源单一**：接口不接受客户端传入的用户标识，所有归属判断取自令牌。

**实施期确认（TASK-005 实际落地）**：

- 实现类名是 `JwtAuthService`（不是初稿写的 `JwtTokenService`），JOSE 能力来自 `spring-boot-starter-oauth2-resource-server`，不手写 JWT。资源服务器的过滤器与 `AuthPort` 共用同一个解码器，避免"签发一套规则、校验另一套"。
- **密钥缺失时的行为**：生成一次性随机密钥并 WARN，而不是拒绝启动。应用现在必须有数据源才能起来，再加一个启动前置条件会让骨架开箱跑不动；随机密钥无法被跨重启利用，风险显著低于写死的默认密钥。配置了密钥则校验长度不低于 32 字符。
- **本地配置的位置**：`backend/config/application-local.yml`，被 `.gitignore` 忽略。**不能放在 `src/main/resources`**——资源目录会被打进 jar，产物里就带着数据库口令。运行本地实例时工作目录必须是 `backend/`，Spring Boot 才读得到 `./config/`。
- **放行规则**：健康检查、`/error`、以及仅 dev/local profile 存在的 `POST /api/v1/auth/token`；其余（含 Actuator 其他端点）一律要求已认证。
- 测试夹具经 `paideia-persistence` 的 **test-jar** 共享给 `paideia-app` 的隔离测试，避免同一张夹具表在两处各写一份。
- 顺带修掉一个真实缺陷：`ApiResponseBodyAdvice` 原先跳过已包装的响应，使控制器自己构造 `ApiResponse` 的接口 `traceId` 恒为 `null`；现在会补上标识，并有断言防回归。

## 可观测性

- Actuator 暴露 `health`、`info`、`metrics`；Micrometer 默认指标随 Boot 提供。
- 日志为 Boot 默认格式加 `traceId`；结构化日志与链路追踪属于延后的 `observability` 模块。
- 启动时输出：生效的 profile、连接的目标库（主机与库名，不含密码）、模块结构校验结果。

## 实施顺序

按可独立验证的纵向切片推进，顺序见 `tasks.md`：工程与边界 → Web 基础设施 → 前端骨架 → 持久层 → 认证授权 → 桌面壳 → 公开页 → 验证文档。`events`、`ai`、`storage` 不在本期。

## 测试策略

### 工具与版本（2026-10-09 核实）

| 层 | 工具 | 说明 |
| --- | --- | --- |
| 测试框架 | **JUnit 6.1.3**（`junit-bom`） | JUnit 6 已 GA，要求 Java 17+，注解与用法同 5；5.x 线仍在维护（5.14.x） |
| 断言 | **AssertJ 3.27.7** | 失败信息比 `assertEquals` 清晰得多 |
| 桩 | **Mockito 5.24.0** + `@MockitoBean` / `@MockitoSpyBean` | Boot 4 已移除 `@MockBean`/`@SpyBean`，替代注解来自 Spring Framework 6.2+ |
| 模块边界 | `ApplicationModules.verify()`；**ArchUnit 1.5.1** | 模块校验为主，ArchUnit 补自定义规则 |
| 切片测试 | `@WebMvcTest` + **`MockMvcTester`**；**`@MybatisTest`**（`mybatis-spring-boot-starter-test` 4.0.0） | 切片比全量 `@SpringBootTest` 快一个量级；`MockMvcTester` 是 AssertJ 风格的新 API |
| 集成测试 | **Testcontainers 2.0.5**（`testcontainers-bom`）或服务器测试库 | 注意 2.0 是破坏性大版本：模块改名为 `testcontainers-mysql`，包路径同步变化 |
| 测试数据 | **Instancio 6.1.0** | 生成测试对象，省掉手写 builder；需要可复现时用 `@Seed` |
| 属性测试 | `jqwik 1.10.1` | 仅用于纯算法与解析器，可选 |
| 前端组件 | **Vitest 5.0.3** + Testing Library 16.3.3 + user-event 14.6.7 | 默认 jsdom；需要真实布局或焦点行为时按需启用 Browser Mode（稳定性官方未明确标注） |
| 前端 API 打桩 | **MSW 3.0.2** | 在网络层拦截，同一套 handler 同时用于测试与本地开发，比 mock 模块更真实也更耐用 |
| 端到端 | **Playwright 1.64.0** | 自动等待、web-first 断言、trace viewer 抑制不稳定；CI 用 sharding 扩展开 |
| 桌面端到端 | Playwright 的 Electron 支持（`_electron.launch`） | 从源码启动，不依赖安装包；需要能 spawn `cmd.exe` 的环境 |

实施期确认的三处 Boot 4 细节，与设计初稿不同，按实际执行：

- **测试切片的坐标与包名变了**：`@WebMvcTest` 现在位于 `spring-boot-webmvc-test` 模块的 `org.springframework.boot.webmvc.test.autoconfigure` 包，不再是 `spring-boot-test-autoconfigure` 下的 `...autoconfigure.web.servlet`。每个技术模块的测试切片各自独立成 artifact，按需引入。
- **模块内的切片测试需要自己的启动配置**：`@WebMvcTest` 要在测试所在包向上找到配置类，而 `@SpringBootApplication` 在 `paideia-app`、该模块又不能依赖它（会成环）。做法是在测试源码里放一个同包的 `@SpringBootApplication` 类。**必须用 `@SpringBootApplication` 而不是 `@SpringBootConfiguration`**——后者不含组件扫描，会导致所有控制器都注册不上、请求一律 404（这一版踩过）。也不要用 `@ContextConfiguration` 显式指定配置类，那会关掉切片自己的组件过滤。
- **`MockMvcTester` 用 `MockMvcTester.create(mockMvc)` 从切片提供的 `MockMvc` 构建**，比依赖自动配置更稳；它的断言入口是 `assertThat(mvc.get().uri(...))`（请求构建器实现了 `AssertProvider`）。

### 速度手段（按收益排序）

1. **Spring 上下文缓存**是最大的一环：配置不同的 `@SpringBootTest` 会各自新建上下文并重复完整启动。让同类测试共用一套配置，并用 `spring.test.context.cache.statistics=true` 观察命中情况。
2. **优先用切片测试**（`@WebMvcTest`、`@MybatisTest`），不要一律用全量 `@SpringBootTest`。
3. **Testcontainers 复用**（仅本地）：跳过容器启动与建库开销；官方标注为实验特性且不适用于 CI。
4. **JUnit 并行执行只用于纯单元测试**；涉及数据库写入的集成测试保持串行，否则相互污染。
5. Maven 侧：surefire 跑单元测试、failsafe 跑 `*IT`，让 `mvn test` 保持轻快；`forkCount=1C`，多模块用 `-T 1C`。

### 明确不要做的事

**不要用 H2 或内存数据库替代 MySQL。** 这是最像"免费提速"、实际最常把坏 SQL 送进生产的陷阱：方言、`AUTO_INCREMENT`、`LIMIT`、`JSON`、`ON DUPLICATE KEY UPDATE`、排序规则都会不同，本地全绿不能证明在 MySQL 上跑得通。本项目只面向 MySQL 且允许使用专有语法，这个陷阱尤其危险。

### 测试数据库

优先指向服务器上的独立测试库 `paideia_test`：与开发同源、不需要 Docker（本机当前没有 Docker）。将来本机装好 Docker 后可切到 Testcontainers 换取更好的隔离，届时只需改配置。

### 授权隔离的验证

使用仅存在于测试作用域的示例表与对应 mapper（含 `owner_id`），断言用户 A 的令牌读不到用户 B 的行。该测试保留为授权契约的回归测试；等第一个真实带归属的业务实体出现后，可迁移到真实实体上并删除示例表。

### 未经验证的项

桌面端的 Playwright Electron 用例需要能 spawn `cmd.exe` 的环境（Playwright 在 Windows 上经 cmd.exe 启动 Electron）。本机开发环境不允许该 spawn，因此该用例保留但记为未运行；安装包构建还需要能访问 GitHub 以拉取 NSIS 与签名辅助二进制。

## 需求追踪

| 验收标准 | 设计对应 | 验证方式 |
| --- | --- | --- |
| AC-002 | 组件与职责（`paideia-app`）、可观测性 | `GET /actuator/health` 返回 UP + 运行记录 |
| AC-003 | 前端包（`apps/app`）、接口与数据 | Playwright 端到端 |
| AC-004 | 模块边界的强制方式 | 边界校验测试 + 人为违反记录 |
| AC-005 | 持久化与迁移（分页组件） | 仓储集成测试 |
| AC-006 | 安全与权限、测试策略 | 越权拒绝集成测试 |
| AC-007 | 前端包、安全与权限（CORS） | 双端构建 + 桌面端启动测试 + 静态检查 |
| AC-008 | 前端包（`apps/public`） | 预渲染产物检查 |

## 待决定事项

1. **应用数据库账号**：当前提供的是 `root`。建议为应用单独建库账号，权限只覆盖所需库，凭据放被忽略的本地配置。这只是建议，最终由用户决定。
2. **测试库位置**：默认用服务器上的独立测试库（与开发同源、免装 Docker）；若希望测试完全隔离，可改用本地容器。
3. **`POST /api/v1/auth/token` 的去留**：它是骨架期验证认证链路用的技术端点。默认仅在开发 profile 启用，上线前移除或加保护。
