---
artifact: proposal
work_id: WORK-001
work: 架构选型与项目骨架
status: approved
created: 2026-10-09
approved_by: xiaou61
approved_at: 2026-10-09 10:09:07 +0800
approver_role: CTO
---

# 架构选型与项目骨架提案

本提案只处理**架构与通用（技术）能力**。业务模块划分、业务功能、业务数据模型一律不在此范围内——它们取决于尚未提出的业务需求。以下所有模块都是与业务无关的技术底座。

## 摘要

在已裁决的技术栈上，落地三件事：**后端 Maven 工程骨架 + Spring Modulith 的模块边界强制**、**前端单仓双宿主（Web + Tauri 桌面）的适配器接缝**、**一条可运行的端到端技术链路**（健康检查、一个受保护接口、双端构建）。同时定下模块的粒度与依赖方向，使业务模块后续可以按需求逐个加入而不动底座。

## 推荐方案

### 1. 仓库目录结构

```
Paideia/
  AGENTS.md
  .gitignore
  .agent/                      生命周期工件（已存在）
  backend/                     后端 Maven 工程
  frontend/                    前端 pnpm workspace
  deploy/                      中间件与部署编排（docker compose 等）
```

后端与前端各自独立构建，共处一仓：跨端契约改动可以一次提交完成，CI 配置统一。

### 2. 后端通用模块集合

主应用类所在包的直接子包即模块（Spring Modulith 的约定）。后端为 Maven 多模块工程，**每个模块是一个 Maven 模块**（已决定，见下节）。`groupId` 为 `io.github.xiaou61`，基础包名同为 `io.github.xiaou61`。

| Maven 模块 | 包 | 职责 | 依赖 |
| --- | --- | --- | --- |
| `paideia-platform` | `io.github.xiaou61.platform` | 开放模块（`@ApplicationModule(type = OPEN)`）：统一返回结构与错误码契约、领域异常基类、ID 与时间约定、通用值对象 | 无 |
| `paideia-web` | `io.github.xiaou61.web` | 统一响应包装、全局异常处理、参数校验、请求上下文与追踪标识、CORS、API 版本管理 | `paideia-platform` |
| `paideia-persistence` | `io.github.xiaou61.persistence` | MyBatis 装配、MySQL 分页组件、TypeHandler 注册、审计字段填充、事务边界约定 | `paideia-platform` |
| `paideia-security` | `io.github.xiaou61.security` | JWT 签发与校验、权限模型与校验注解、当前用户上下文、**认证端口（AuthPort，可扩展点）** | `paideia-platform` |
| `paideia-app` | `io.github.xiaou61` | Spring Boot 启动模块：主类 `PaideiaApplication`、配置装配、Modulith 边界校验测试 | 以上全部 |

外加一个聚合父 POM（`packaging=pom`）统一版本与依赖管理。主类放在基础包根（`io.github.xiaou61`），这是 Spring Modulith 识别模块的前提。

明确**延后**（等有实际技术需求再建立，骨架不预留空壳）：

- `events` — 事件基类、outbox 表与中继、RabbitMQ 投递。等第一条需要异步的链路出现时建立。
- `ai` — 模型接入端口、限流、用量记账、提示词管理。等 D-09 裁决后建立。
- `storage` — 文件与对象存储抽象。等出现文件上传需求时建立。
- `observability` — 骨架先用 Boot 自带的 Actuator + Micrometer，独立模块等有明确需求再说。

不在骨架里堆空壳的理由：没人用的模块会被误当成"已经设计好了"，而模块的边界应当由真实使用来验证。

### 3. 模块边界的强制层次（已决定：每个模块一个 Maven 模块）

后端每个模块是一个 Maven 模块，模块间依赖必须在 `pom.xml` 中显式声明。未声明即不可见，**非法跨模块依赖是编译错误**，无法靠跳过测试绕过。

这条边界覆盖到哪里，需要说准：

- **覆盖：模块到模块的依赖方向。** `paideia-web` 若未声明依赖 `paideia-security`，它连 `paideia-security` 的类都编译不过；循环依赖在 Maven 里同样无法声明。这是可用的最硬一层边界。
- **不覆盖：模块内部包的可见性。** Maven artifact 会导出其全部包，所以"`io.github.xiaou61.web.internal` 不应被外部引用"仍属约定，由 Spring Modulith 的校验（可选叠加 ArchUnit 规则）在构建期拦截，不是编译期。

因此 Modulith 的角色随之变化：模块依赖方向交给编译器保证，它的价值转为**事务性 outbox 与事件发布注册表**、**模块级集成测试**、**模块图文档生成**，并继续承担模块内部包的访问检查。两者叠加，边界有编译期与构建期两道拦截。

代价（已知并接受）：业务模块落地时要维护 10 个以上 pom；Maven 模块边界比包边界难改，所以**模块集合需要在业务需求明确时一次性规划好**；同时要设计 Maven 层面谁是 API、谁是实现。缓解方式是模块内部包的约定交给 Modulith 校验，不在 Maven 层面再拆一层。

### 4. 前端 workspace 结构

```
frontend/
  package.json
  pnpm-workspace.yaml
  packages/ui/                 纯展示组件，不含平台 API
  packages/core/               领域状态、API 客户端、Platform 与 Auth 端口定义
  packages/platform-web/       Web 实现：浏览器文件访问、本地缓存
  packages/platform-desktop/   桌面实现：Tauri 插件（fs / store / 深链 / 更新器）
  apps/app/                    主体 SPA（Web 与桌面共用同一份产物）
  apps/public/                 公开页与 SEO surface（本期只放一个预渲染页作验证）
  apps/desktop/                Tauri 壳，消费 apps/app 的产物
```

两条纪律，加一条自动化检查：

- `packages/core` 与 `packages/ui` **不得出现任何桌面壳专有 API 引用**（检查在 AC-007）。
- 资源路径一律相对（Vite `base: './'`），否则桌面壳加载时全部 404。
- 平台差异只通过 `Platform` 端口暴露：文件读写、安全存储、深链、自动更新、本地缓存各是一个方法，Web 与桌面各一份实现。业务代码只依赖端口。

### 5. 骨架的端到端技术链路

骨架要能证明的链路（都不含业务功能）：

1. 后端启动，健康检查端点返回 UP。
2. 后端暴露一个**受保护接口**，身份只从 JWT 解析，不接受客户端传入的用户标识。
3. 前端启动，登录后调用该接口并渲染真实返回数据。
4. 一条仓储用例在 MySQL 上通过，分页由 MySQL 分页组件产生。
5. 同一条命令产出 Web 产物与 Windows 桌面安装包，桌面版能启动并调用后端。
6. 一个预渲染的公开页面，证明 SEO surface 与桌面构建互不影响。

关于 AC-006（授权隔离）的验证方式：证明"用户 A 读不到用户 B 的数据"需要一个带归属的实体，而实体属于业务。因此隔离验证使用**仅存在于测试作用域**的示例资源（测试迁移 + 测试代码，不进生产 schema），用于证明越权读取会被拒。业务侧的同类验证随第一个用户维度功能落地。

### 6. 中间件与本地开发

- 中间件（MySQL、RabbitMQ 等）与后续部署都放在已提供的自有服务器上。
- 后端仓库内提供 `deploy/` 编排文件（docker compose 形式），只引用环境变量，**不写任何地址与凭据**（见根 `AGENTS.md` 保密规则）。
- 骨架阶段只需要 MySQL：RabbitMQ 等 `events` 模块建立时再接入。
- 本地开发推荐用容器起 MySQL，避免与服务器环境互相污染；连接信息放被忽略的本地配置文件。

## 范围

包含：仓库结构、后端通用模块集合与依赖方向、边界强制的两个方案、前端 workspace 与适配器接缝、端到端技术链路、骨架阶段所需的中间件接入方式、交付切分。

不包含：任何业务功能与业务模块、业务数据模型、向量检索、RAG、AI 具体接入（等 D-09）、离线同步、性能压测、部署实施。

## 备选方案

**后端单一 Maven 模块（方案 A）。** 最强的一面是文件少、迭代快：业务模块只需加包，模块边界在单一构建单元里可以自由重构，不必维护十几个 pom；且模块集合尚未定型时改动成本几乎为零。未选理由：你明确要求"边界最硬"，而方案 A 的模块间依赖只能靠 Modulith 校验测试拦截——虽然同样会让构建失败，但理论上存在被跳过的可能，编译器不参与。代价：模块数量增长后，pom 数量与构建编排的维护成本上升，且模块集合必须在需求明确时一次规划到位。

**后端单模块 + ArchUnit，不用 Modulith。** 最强的一面是依赖最小、规则完全自定义，不要求包结构符合谁的约定。未选理由：Modulith 除了边界校验还提供事务性 outbox、模块级集成测试与文档生成，而这三样在后续会用到；且它由 Spring 官方维护、与 Boot 4 版本同步。代价：接受它的包结构约定与一个框架依赖。

**前后端分仓。** 最强的一面是各自 CI 独立、权限与发布节奏解耦。未选理由：单一开发者的跨端契改动会变成两次提交且容易不同步，收益不存在而成本照付。

**先不做桌面壳，只留接缝。** 最强的一面是省掉 Rust 工具链与桌面构建的复杂度，能更快看到 Web 跑起来。未选理由：你已决定桌面端与 Web 同期交付。代价：构建机需要先装好 Rust 工具链与 MSVC C++ 生成工具。

**业务模块现在就划分。** 未选理由：业务模块边界必须由业务需求决定，现在划分等于按猜测固化最难改的结构。代价：骨架里不会有任何业务包，模块边界要到第一个业务工作项才真正被使用验证。

## 仓库影响

新建 `backend/`、`frontend/`、`deploy/` 三个顶层目录与 `.gitignore`（已建）。`.agent/` 工件不受影响。当前工作区改动仍未提交，本地提交需用户授权。

## 交付拆分

每一片都能独立验证，不按技术分层切割。

| 片 | 内容 | 对应验收 |
| --- | --- | --- |
| 1 | 后端 Maven 工程、`platform` + `web` 模块、Modulith 校验接线、健康检查 | AC-002、AC-004 |
| 2 | 前端 workspace、`packages` 骨架、主体 SPA 壳、调用后端接口 | AC-003 |
| 3 | 持久层底座：Flyway、MyBatis 装配、MySQL 分页组件、一条仓储用例 | AC-005 |
| 4 | `security` 模块：JWT 签发校验、AuthPort、受保护接口、越权拒绝检查 | AC-006 |
| 5 | Tauri 桌面壳、`platform-web` / `platform-desktop` 两端实现、双端构建 | AC-007 |
| 6 | 公开页与 SEO 路径：一个预渲染页面 + 接入说明 | AC-008 |

`events`、`ai`、`storage` 不在这六片里，随对应需求建立。

## 风险与缓解措施

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 模块集合尚未定型，而 Maven 模块边界比包边界难改 | 中 | 业务需求明确时一次性规划模块集合；模块内部包的边界交给 Modulith 校验，不在 Maven 层面再拆一层 |
| 平台专有 API 泄漏进共享前端代码，破坏双端复用 | 中 | 自动化检查禁用共享包引用桌面壳 API |
| 认证从"可扩展"退化为硬编码 JWT | 中 | 认证收在 `security` 模块的 AuthPort 之后；骨架阶段即建立端口，不让业务代码直接依赖 JWT 细节 |
| 构建机缺 Rust / MSVC 导致桌面端搭不起来 | 中 | 第 5 片开始前先验证环境；这也是选择"Web 先跑通、桌面随后"顺序的原因 |
| 骨架里堆太多"以后会用到"的模块 | 中 | 只建四个通用模块；其余延后，避免空壳被误认为已设计 |
| 服务器地址或凭据进入公开仓库 | 高 | `.gitignore` 已建并自检；`deploy/` 只引用环境变量；建议启用 GitHub Secret scanning 与 Push protection |

## 验收映射

| 验收标准 | 对应部分 |
| --- | --- |
| AC-001 | 已由需求阶段的技术选项登记表（`.agent/references/technology-options.md`）与已批准需求满足 |
| AC-002 | 推荐方案 5 第 1 条 + 交付拆分 1 |
| AC-003 | 推荐方案 5 第 3 条 + 交付拆分 2 |
| AC-004 | 推荐方案 3 + 交付拆分 1 |
| AC-005 | 推荐方案 5 第 4 条 + 交付拆分 3 |
| AC-006 | 推荐方案 5 末段 + 交付拆分 4 |
| AC-007 | 推荐方案 4 + 交付拆分 5 |
| AC-008 | 推荐方案 4（`apps/public`）+ 交付拆分 6 |

## 已决定事项（本提案的待决点已由用户裁决）

| 决策点 | 结论 |
| --- | --- |
| Maven 模块粒度 | 每个模块一个 Maven 模块（方案 B），非法跨模块依赖为编译错误 |
| `groupId` / 基础包名 | `io.github.xiaou61` / `io.github.xiaou61` |
| 前端包管理器 | pnpm（未另行指定，按默认采用），Node 跟随当前 LTS |
| 本地开发数据库 | 直连自有服务器上的 MySQL 实例，不用本地容器 |

## 待决定事项

无。
