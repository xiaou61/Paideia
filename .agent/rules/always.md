---
artifact: project_rules
status: active
configured: true
---

# 项目常驻规范

本文件保存跨工作项长期有效、且已由用户确认的事实与约束。用户确认时间：2026-10-09（确认人 xiaou61，角色 CTO）。尚未裁决的技术选型在文末清单中逐项标注状态，不作为约束生效。

## 项目定位

Paideia 是「千人千面的 AI 个性化学习平台」。目标不是把同一套课程分发给所有人，而是让每个学习者拥有自适应的学习路径、内容与反馈。

## 项目常驻约束（MUST）

- 进入实现、部署、迁移或数据变更前必须确认本文件；未确认项不得按猜测执行。
- 技术栈（2026-10-09 由用户指定）：
  - 后端：Spring Boot 4 + JDK 25（Boot 当前 4.1.1；starter 已改名，`spring-boot-starter-web` → `spring-boot-starter-webmvc`；Undertow 已移除；`@MockBean`/`@SpyBean` 已移除）。
  - 数据访问：**手写 MyBatis，禁止引入 MyBatis-Plus**（当前 3.5.19，无 3.6.x GA）。
  - 数据库：**仅 MySQL，不做多数据库适配**（2026-10-09 用户决定：完全放开，可使用 MySQL 专有语法与特性：upsert、JSON 列与算子、`SKIP LOCKED`、全文检索、自增主键等）。**若将来要换库，等于重写数据访问层，这是已知并接受的代价。**
  - 前端：React 最新稳定版（当前 19.3，无 React 20；React Compiler 1.0 已稳定）。
  - 桌面壳：**Electron**（2026-10-09 由 Tauri 改为 Electron，理由见 D-12）。
  - AI 接入与编排：**AgentScope Java**（2026-10-09 用户决定）。**不使用 Spring AI / LangChain4j**。对应的模块计划是 `ai`（模型接入与编排），尚未创建。
  - AI 模型供应商：**DeepSeek**，模型 id **`deepseek-flash`**（DeepSeek-V4.1-Flash，1M 上下文，2026-09-10 发布）。API 密钥只存本机 Windows 凭据管理器（`paideia-deepseek`），通过环境变量 `DEEPSEEK_API_KEY` 注入，**绝不进入仓库或任何被跟踪文件**。
  - **AI 的 Key 由平台持有、用户按积分消耗**（2026-10-16 由 WORK-009 确立）：上游凭据放**部署配置** `paideia.ai.billing.platform-credential.{base-url,model,api-key}`（环境变量占位、**不入库**），**用户不再自带 Key**——原「纯 BYOK、平台不出钱、不做成本记账」三条裁决就此撤销。本期**没有充值**：积分只能由管理员在管理端发放，余额不足时 AI 调用返回 402。形态、价目表改法、回滚档位与已知缺口见 `.agent/notes/dev-workflow.md` 的「AI 计费与平台凭据」一节。
  - 不得使用 JDK 预览特性（结构化并发在 JDK 25 仍是预览）。
- **不做多租户**（2026-10-09 用户决定）：单一部署形态，不引入租户隔离维度。学习者之间的数据隔离仍需保证，但按普通授权问题处理，不引入租户架构。
- **本期不做向量化检索**（2026-10-09 用户决定）：不引入向量库、不实现语义检索与 RAG；架构上必须为其保留接入位置。内容检索本期用 MySQL 全文检索承担。若将来引入向量检索，注意 MySQL 社区版/企业版无法原生做向量距离排序。
- **前端一套代码同时交付 Web 与 Windows 桌面版，两端同期交付**（2026-10-09 用户决定）：平台能力必须通过适配器接口隔离，共享业务代码不得引用桌面壳专有 API；资源路径必须相对。桌面壳为 Electron，渲染进程由主进程的本地静态服务提供，来源是 `http://127.0.0.1:<port>`，必须显式加入后端 CORS 允许列表。
- **SEO 是后续需求，本期不实现**（2026-10-09 用户决定）：前端架构不得阻断，公开页走独立 surface。
- **认证要求可扩展，本期只做简单实现**（2026-10-09 用户决定）：具体机制待裁决（D-10）。**桌面端同期交付后，会话 Cookie 被排除**——桌面壳页面来源非 http(s) 域，`SameSite`/`Secure` 语义失效并撞 CORS。认证必须收在单一模块的端口之后，使更换机制不改业务代码。
- **消息与异步：引入 RabbitMQ 作为消息中间件**（2026-10-09 用户决定）。实现约束：事件必须先写入 outbox（与业务数据同一事务），再由中继投递到 RabbitMQ；不得在事务提交后直接发布，否则会丢失「数据库已提交但消息未发出」的事件。**状态（2026-10-22 核查）：尚未接入，零落地**——在此之前没有任何能力需要异步事件，全仓没有 amqp 依赖与配置。它是**接入时的约束**，不是"已经在跑"的事实；第一次要做异步时按上面两句执行。
- **中间件与部署自托管在自有服务器**（2026-10-09 用户决定）：RabbitMQ 等中间件与后续部署都放在自有服务器上；部署细节本期不考虑。服务器地址与凭据属敏感信息，一律不进仓库。
- **数据库端口的实际状态（2026-10-16 更新：已对公网开放）**：用户要求去掉隧道，于是按本条原有的路径做了——改 `bind-address = 0.0.0.0`（重启 mysql）**并同步账号的 host 限制**：
  - **只新建了一个 `paideia_test@'%'`，且只授权 `paideia_test.*`**。生产/开发库 `paideia` **没有任何可远程登录的账号**（原先只有 `paideia@127.0.0.1` 与 `paideia@localhost`，这次一个都没改）——所以开了端口也没有能连上生产库的凭据路径。
  - 走公网的只有**测试**：`source backend/config/remote-db.local.env`（匹配 `.gitignore` 的 `*.local.*`，不入库）后再跑 `mvn -B test` 与 e2e；两个 playwright 配置的默认 JDBC 也已改成 `36.212.5.130:3306`。
  - **仍然要求**：凭据只存在于被忽略的本地文件；口令不进命令行（用 stdin 传 SQL）；MySQL 的 X 协议端口 33060 保持只监听 127.0.0.1。
  - **关闭 / 回滚**：`bind-address` 改回 `127.0.0.1` + `systemctl restart mysql`；配置备份在服务器 `/etc/mysql/mysql.conf.d/mysqld.cnf.bak-2026-10-16`。若要收窄，把 `paideia_test@'%'` 换成 `@'<固定出口IP>'`。
  - **已知风险**：3306 现在公网可达，扫描器会持续戳它。当前唯一能连的是那个**只碰得到 `paideia_test`** 的账号（密码 32 位随机），但"测试库被写坏"是可能的——那正是它被写坏的用途。若将来要让**本地跑应用连开发库**（`paideia`），那等于把生产库账号暴露到公网，**先单独确认**。
- **课程内容源在 `content/courses/`**（2026-10-19 由 WORK-020 确立）：改课程走 PR 改 markdown，
  再跑 `node scripts/import-course.mjs` 导入，**不在管理端手抄**。改完先跑
  `python scripts/verify_course.py`（它会真执行课程里的示例并比对输出）。
  新增一条学习线只需要建内容目录 + 跑导入，**不改后端/前端/迁移**。
- 编码约定（与数据库选型无关，属于通用纪律）：
  - 所有 SQL 只写在 mapper 中，不在 Java 代码里拼接。
  - 时间统一存 UTC，不调用数据库的 `NOW()`/`SYSDATE()` 取业务时间。**建表默认值 `DEFAULT CURRENT_TIMESTAMP(3)` 同样属于"让数据库取时间"**，它按**会话时区**算：服务器 MySQL 的会话时区是 `SYSTEM`（+08:00），因此每个 JDBC 连接串必须同时写 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`——**两个参数缺一不可**，少了后者就是"驱动按 UTC 解释、会话按本地时间写"，所有时间静默快 8 小时（2026-10-10 实测发现并已修，含存量回拨）。服务器没有装载时区表（`SET time_zone='UTC'` 报 1298），驱动会自行折算成 `+00:00` 偏移形式，所以不必装 tz 表。
  - 标识符统一小写 snake_case，避免保留字。
- 已裁决技术选型（2026-10-09 用户决定）：
  - D-01 整体架构形态：**模块化单体**。
  - D-02 模块边界强制手段：**Spring Modulith**（构建期校验模块边界，禁止循环依赖与跨模块访问内部实现）。注意其包结构约定：主应用类所在包的直接子包即模块，模块包根为 API、子包为内部实现。
  - D-03 后端构建工具：**Maven**。
  - D-04 仓库结构：**单仓多模块**（后端 Maven 多模块，前端 pnpm workspace 同仓）。
  - D-05 主键生成：**数据库自增**（MySQL `AUTO_INCREMENT`；MyBatis 用 `useGeneratedKeys` 取值）。
  - D-06 分页方案：**MySQL 分页组件**（不引入多方言抽象；禁止 `RowBounds`，它不是 SQL 分页）。
  - D-07 多数据库适配：**不做适配，完全放开 MySQL**。
  - D-08 数据库迁移工具：**Flyway**。
  - D-10 认证机制：**自签 JWT**；必须收在单一模块的端口之后，以保持可扩展。
  - D-11 前端框架与渲染模式：**Vite + React Router SPA**。
  - D-12 桌面壳技术：**Electron**；桌面版与 Web 同期交付。
  - D-09 AI 接入与编排：**AgentScope Java**，唯一框架，不叠加 Spring AI。
    - **2026-10-09 裁决理由**：v1 需要的四件事它都是一等能力——对话与流式、工具调用、结构化输出（`agent.call(msg, SomeClass.class)` + `getStructuredData(...)`，provider 不支持原生 json_schema 时自动回退为强制工具调用）、以及每次调用的 token 用量（`Msg.getUsage()`）。沙箱执行与人工审批是它独有、且后续可能用到的能力。不叠加 Spring AI 是为了避免两处模型配置。
    - **必须自己补的四项，写进 `ai` 模块的任务清单，不要指望框架给**：① 用量与成本记账（累计 `ChatUsage`，框架无累加器）② 模型调用限流（文档里的 rate-limit middleware 是示例代码，不是框架类）③ 提示词模板与版本管理（框架只有 `sysPrompt(String)`）④ 可抓取的指标（框架只提供 OpenTelemetry 追踪，没有 Micrometer/Prometheus）。
    - **兼容性已由尖刺验证（2026-10-09）**：临时工程用 `spring-boot-starter-parent 4.1.1` + `io.agentscope:agentscope-spring-boot-starter:2.0.4`，`@SpringBootTest` 上下文启动成功（`BUILD SUCCESS`，名称含 agentscope 的 Bean 2 个，`SpringBootVersion=4.1.1`）。仓库 pin 4.0.3/4.0.4 只是没有把升级 PR 合入，实测在 4.1.1 上可加载。尖刺工程是一次性的，步骤与结果记在 `.agent/references/agentscope-and-deepseek-2026-10.md`。
    - **DeepSeek 接入事实已实测（2026-10-09）**，详见 `.agent/references/agentscope-and-deepseek-2026-10.md`。三条要点：（1）DeepSeek 默认开启思考且推理 token 计入 `max_tokens`，给少了会拿到「成功但 content 为空」的响应，必须校验内容非空；（2）AgentScope 没有 DeepSeek 专用 starter（该构件在 Maven Central 上不存在），DeepSeek 由 OpenAI 扩展承载；（3）在 DeepSeek 上工具调用与结构化输出不能在同一次调用里并用（会跳过工具调用），「调工具 + 返回结构化结果」必须拆成两次调用，或把决策留在确定性代码里。
    - **保留薄端口**：`ChatModelBase` 是抽象类，`Msg`/`ChatResponse`/`ChatUsage`/`AgentEvent`/`ReActAgent.Builder` 会渗透进调用点；业务代码不得直接依赖这些类型，必须收在 `ai` 模块的端口之后，否则将来换运行时的成本是"逐处改调用点"。
    - **2026-10-09 变更理由**：原选择 Tauri 是为不打包 Chromium、产物更小，但 Tauri 在 Windows 上要求 Rust 工具链与 MSVC C++ 生成工具，本开发机两者都没有（WebView2 运行时倒是已有）。Electron 只依赖 Node（本机已有），换过来后桌面端立刻可按现有环境构建。代价是安装包与内存占用显著变大，这是明知并接受的取舍。将来若需要更小产物可回到 Tauri，届时需先装 Rust + MSVC。
  - D-13 前端数据层：服务端状态用 **TanStack Query**；客户端临时状态方案暂缓。
  - D-14 消息与异步：**引入 RabbitMQ**（配合 outbox 中继，见上文实现约束）。
- 仓库结构：**单仓多模块**。后端 Maven 多模块，**每个模块一个 Maven 模块**（`groupId` 为 `io.github.xiaou61`，基础包名同为 `io.github.xiaou61`，artifact 命名 `paideia-<module>`）；非法跨模块依赖必须表现为编译错误。前端 pnpm workspace 同仓。**构建与检查命令（2026-10-09 实测）**：
  - 后端全量构建与测试：`scripts/test-db.sh`（默认在 `backend/` 跑 `mvn -B test`；要连打包一起验用 `cd backend && ../scripts/test-db.sh mvn -B verify`）。它给这一轮分配独占库 `paideia_run_<时间>_<pid>`，跑完自动删；**共用 `paideia_test` 直接 `mvn -B test` 不是证据**——同机可能有别的会话在跑，互相删数据会造出"单类绿、全量红"。集成测试需要口令（2026-10-16 起**不需要隧道**：`source backend/config/remote-db.local.env` 即指向测试库）；未配置时相关用例跳过并在 stderr 明确提示，构建不因此失败——所以**每次都要核 Skipped 列**。
  - 后端本地运行：`cd backend && java -jar paideia-app/target/paideia-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`。**工作目录必须是 `backend/`**，配置从 `./config/application-local.yml` 读取（被忽略，含口令）。并发会话用 `--server.port=<别的端口>`。
  - 前端类型检查：`cd frontend && pnpm -r typecheck`；单测：`pnpm -r test`。
  - Web 端到端：`cd frontend && ../scripts/test-db.sh pnpm test:e2e`（需先 `mvn -B -DskipTests package` 产出后端 jar；2026-10-16 起直连测试库，**不再需要隧道**，但要先 `source backend/config/remote-db.local.env`）。并发会话用 `PAIDEIA_BACKEND_PORT=8081 PAIDEIA_APP_PORT=5183 pnpm test:e2e` 各占一组端口。
  - 公开页构建：`cd frontend && pnpm --filter @paideia/public build`。
  - 桌面端：`cd frontend && pnpm -w build:desktop`。**必须同时设置两个镜像**，否则 electron-builder 会去 GitHub 拉二进制并超时（本网络下实测）：
    `ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/" ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries" pnpm -w build:desktop`
    只设 `ELECTRON_BUILDER_BINARIES_MIRROR` 不够：Electron 运行时走的是 `ELECTRON_MIRROR`。
  - 新增源码后如创建了新的跨文件引用，需重建 `.codegraph` 索引再依赖调用关系结论。
  - **本机网络**：git 访问 github.com 必须走本机 Clash 代理（mixed-port `7897`，TUN 关闭）。未配置时代码直连 GitHub，表现为推送时通时不通；已用 `git config --global http.https://github.com/.proxy` 固化。推送失败时先确认代理端口是否变化，不要反复重试。
- **本地开发直连自有服务器上的 MySQL 实例**（2026-10-09 用户决定），不用本地容器起实例；连接信息放被忽略的本地配置文件，不进仓库。
- 涉及学习者个人数据的功能，必须先明确数据隔离、留存与隐私要求，再进入设计（待决定项见 WORK-001 需求文档）。
- 本地提交需用户明确授权；`git push`、远端分支、tag、部署必须单独授权。
- **本仓库是公开仓库**：凭据、密钥、令牌、私钥、连接串、服务器地址一律不得写入任何被 Git 跟踪的文件（源码、配置、文档、`.agent/` 工件、更新历史、提交信息）。本地敏感配置放被 `.gitignore` 忽略的文件；仓库内只提交占位示例。详见根 `AGENTS.md` 的保密规则。
- **代码审查按范围执行，不逐 commit 重跑**（2026-10-09 用户决定）：收到审查请求时审查其指定的 ref 区间（`--from <base> --to HEAD`）或指定提交，不为每个 commit 单独出报告——逐 commit 会重复计费，且拆碎的提交单独看会丢掉完整意图、产生误报。
- **审查工具与报告归档**（2026-10-09 用户决定）：使用 `ocr`（`alibaba/open-code-review`，已全局安装于本机）。报告写入 `.agent/reviews/`，文件名 `<YYYY-MM-DD>-<范围>.md`——审 ref 区间写 `<base7>..<head7>`，全库扫描写 `scan-<head7>`——随仓库提交（用户决定报告进 Git）。报告只记录范围、结论与证据，**不得回显 provider / api_key 等配置**；ocr 自身配置目录（`config.json` 含 API key）位于本机用户目录，绝不得进入仓库。
- **审查按需触发，不装自动化**（2026-10-09 用户决定）：不设置 `post-commit` 钩子、不由 CI 触发，避免每个 commit 都消耗模型额度；由用户发起。ocr 默认只审代码文件（`.md` 等按 `unsupported_ext` 跳过），纯文档改动不产生报告。
- **审查结论只修严重项**（2026-10-10 用户决定）：**只修 critical 与 high**（以及由它们推出的必修簇）；**medium 与 low 不修**，既不作为待办积压，也不再逐条分诊。报告仍全量归档——它是证据，与"改哪些"是两件事。
- **运维脚本与一次性脚本不进审查范围**（2026-10-26 用户决定）：`ocr` 一律加这组排除——
  `--exclude 'deploy/**,scripts/**,*.ps1,*.sh,*.py,*.mjs'`。
  理由：2026-10-26 那次 84 条 high 里有三成落在这类文件上（`release.ps1` 的引号拼接、`grafana.ini` 的 `cookie_secure`、`reorder-java-course.py`/`seed-demo-data.py`/`gen-arch-diagram.mjs` 这类跑一次就丢的内容脚本），人肉筛掉它们每次都要重做一遍。`content/` **不在排除清单里**（题库与课程正文是产品数据，值得看），但它的 findings 默认按 medium 处理。
- 元规则：若项目代码出现与本节同等长期有效的规则变更，更新本文件而不是只写进单个工作项。

## 前端结构：四个面与设计系统（2026-10-09 由 WORK-002 确立）

前端是 pnpm workspace 单仓多包，**四个应用面**各司其职，共享 `packages/` 下的包：

| 面 | 路径 | 产物去向 | 说明 |
| --- | --- | --- | --- |
| 学习者端 `@paideia/app` | `frontend/apps/app/` | Web **与**桌面安装包 | 同时出两种产物，端口 5173 |
| 管理端 `@paideia/admin` | `frontend/apps/admin/` | 仅 Web | 只对管理员开放，端口 5174 |
| 公开页 `@paideia/public` | `frontend/apps/public/` | 预渲染静态页 | SEO surface，不引 `core` 的 API 客户端、不用 `AppShell` |
| 组件展览 `@paideia/ui-kit` | `frontend/apps/ui-kit/` | 仅开发与内审 | 不进任何产品产物，端口 5175 |

- **管理端与组件展览不是 `apps/app` 的依赖**，因此"不进桌面安装包"是构造上的事实，不依赖打包器消除死代码。桌面壳只消费 `apps/app` 的产物。
- **设计系统取 Vercel Geist 的实测令牌**：浅色底纯白、正文 `hsl(0 0% 9%)`、默认边框 `hsl(0 0% 92%)`、组件底 `hsl(0 0% 95%)`；深色对应 `4%`/`93%`/`18%`/`10%`。圆角 6px（菜单/模态 12px）。层次靠字号、留白与 1px 细线建立，**不靠色阶堆叠**。
- **玻璃拟态默认不用**。Vercel 官方的对外设计规范把 glass effects 列入 Hard reject；`.glass` 只保留为可选工具类，用于确实有内容可透的场景（如浮在图片上的顶栏），其 `@supports` 降级形态必须保持完整。
- **颜色只有一个来源**：`frontend/packages/ui/src/styles/globals.css`。应用与共享包不得出现硬编码颜色字面量（含文档文案里的），由 `packages/ui/src/tokens.test.ts` 强制。
- **Tailwind v4 的扫描路径必须显式登记**：`@import "tailwindcss" source(none)` + `@source` 写物理源码路径（`packages/...`、`apps/...`），**不能写包名**——pnpm 把 workspace 包软链在 `node_modules` 下，会被 v4 的自动探测排除，跨包类名会静默丢失。**新增应用、或新增任何含 `.tsx` 的包时，都必须同步在 `globals.css` 里补一行 `@source`**：漏了的症状是"布局类不生效但没有任何报错"（凡是别处也用过的类名仍然会生成，只有本包独有的那些消失——WORK-003 就漏了 `packages/auth`，`justify-end` 这类只在那儿出现的类名一直没生成）。这条由 `packages/ui/src/styles/source-coverage.test.ts` 强制，不靠人记住。
- **对比度由 `packages/ui/src/theme/contrast.test.ts` 守住**：正文与次要文本在页面底、卡面与玻璃层上均须 ≥4.5:1（按合成后颜色算），焦点指示 ≥3:1。静止边框低于 3:1 是刻意的（Geist 亦然），不要再提这条为缺陷。
- **`packages/ui` 是纯展示层**：不得引用 `@paideia/core`、`platform-web/desktop`、`react-router` 或桌面壳 API，由 `packages/core/src/workspace-boundaries.test.ts` 强制。检查同时认 `from`、纯副作用 `import 'x'` 与动态 `import('x')` 三种写法。
- **角色逻辑只有一处**：`packages/core/src/session.ts` 的 `createSessionReader` / `canAccessAdmin`。业务组件不得自己比较角色字符串。客户端解析令牌载荷**不校验签名**，只用于界面分流，**不是授权边界**——后端必须自己拦越权；也不得加任何"以管理员身份预览"的调试开关。
- 四个应用共用同一份编译后的 CSS（`@source` 登记在一处换来"不会漏"），因此各应用 CSS 体积不是按需裁剪的。

## 账号与认证（2026-10-09 由 WORK-003 确立）

- **账号是业务实体，单独一个模块**：`backend/paideia-account`。依赖方向 **account → security**，
  `paideia-security` 是认证基础设施、**永远不认识账号表**。其它业务模块要按 `userId` 关联数据时，
  依赖 `AccountApi`（包根）而不是账号表或 `internal` 实现。
- **凭据纪律**：口令用 **BCrypt**（Spring Security 自带）；**refresh 令牌、邮箱验证码、图形验证码答案只存哈希**
  （SHA-256），明文只在响应或邮件里出现一次；密码、令牌、验证码明文**永不进日志、异常消息与响应体**；
  对外的用户视图里没有口令摘要。
- **令牌形态**：access 是无状态 JWT（默认 15 分钟，claims 含 `sub` 与 `role`）；
  refresh 是**不透明随机串 + `refresh_tokens` 状态表**（只存哈希，按 `family_id` 成链）。
  轮换时旧的一次性作废；**旧 refresh 被再次使用即判定盗用并吊销整条链**；登出吊销整条链。
- **access 在登出后仍有效**（剩余寿命 ≤15 分钟）。这是 JWT 的固有代价；要彻底即时失效必须引入
  服务端黑名单，属未做的事，不要在别处声称"登出即失效"。
- **角色只有 `admin` 与 `learner`**，注册出来的一律是学习者；管理员只能由种子迁移或运维手段产生。
  前端角色判断只有一处（`packages/core` 的 `canAccessAdmin`），后端角色映射在
  `JwtAuthenticationConverter`。**前端守卫不是授权边界**，真正的拦截必须在后端接口上。
- **令牌存放**：access 只在内存、refresh 走 `Platform` 端口（Web 落 localStorage、桌面落主进程缓存）。
  业务代码不得直接读写令牌——只有 `packages/auth` 可以。已知天花板：XSS 能读到 refresh。
- **401 统一处理**：`packages/auth` 的 `createAuthorizedFetch` 负责"附加令牌 → 401 刷新一次 → 重放一次"，
  并**把并发 401 合并为一次刷新**（refresh 一次性，分开刷必然互相打死）。业务页面不要自己写刷新逻辑。
- **`/api/v1/auth/**` 整体放行**（见 `SecurityConfiguration` 的类注释）：该前缀下**只允许放"用凭据换令牌"
  或"令牌自身操作"的端点，不得挂任何读取业务数据的接口**，否则它会默默变成公开接口。
  **不存在**不校验凭据就签发令牌的端点（`AuthController` 那个已删除）。
- **开发端口必须进后端 CORS 白名单**，即使请求经过 Vite 代理也一样——**浏览器对同源的非简单请求也会发
  `Origin` 头**，后端按来源判定、未列入直接 403。应用端口：5173 学习者端、5174 管理端、5175 组件展览、
  5310 桌面壳。`paideia.web.cors.allowed-origins` 与 `management.endpoints.web.cors.allowed-origins`
  是两套独立开关，**都要配**。
- **限流是必需项而非常项**：发信按"同邮箱间隔 + 同邮箱每小时 + 同来源每小时"三道，验证码按尝试次数
  （超限即作废），登录失败按"标识 + 来源"（**只统计失败，成功即清零**——用"每次调用都计数"的写法会让
  正常登录几次就被封）。验证码与限流状态目前是**进程内**的：单实例有效、重启即清零，
  **多实例部署时必须换共享存储**（代码里已标注）。来源 IP 取自直连地址，上反向代理后需改读 `X-Forwarded-For`。
- **邮件走 `MailPort` 端口**，三个实现：SMTP（真实）、日志（未配 `paideia.mail.host` 时的本地默认，
  验证码写日志且每次 WARN 告警）、内存信箱（仅测试，由 `paideia-account` 的 test-jar 提供）。
  SMTP 凭据只进环境变量或被忽略的本地配置。**未配 SMTP 的实例不得放到他人可访问的环境**——那等于把日志变成注册后门。
- **种子账号只放 `db/devdata`**（`seed-learner` / `seed-admin`，口令固定，仅测试库使用），任何 profile
  默认都不加载，只有端到端测试显式加上；生产迁移目录里永远没有它。
- **迁移的两个坑（已实测）**：①Boot 4 把自动装配拆成独立模块，**只引 `flyway-core` 时迁移从不执行、
  不报错也不打日志**，必须有 `spring-boot-flyway`；②夹具用 V900+、生产迁移从 V1 起，两套编号在
  Flyway 眼里互为乱序/未来版本，故测试与 e2e 配置里同时放开 `out-of-order` 与
  `ignore-migration-patterns: ['*:future','*:missing']`，**生产配置不动**。
- **迁移文件名用 14 位时间戳**（2026-10-21 定，起因是多会话并行撞版本号）：
  `V20261021103045__描述.sql`，不要写 `V25__`。**实测**（用项目自己的 Flyway 13.10 在独占库上跑）：
  两个分支都写 `V25` → `Found more than one migration with version 25`，**应用整体起不来**；
  改时间戳后正常续接、幂等、可继续追加。守卫是 `MigrationVersionFormatTest`（纯数字号 / 同秒重复都会红）。
  **它治不了的**：同一秒生成两个文件（概率大降但不是零）、两个会话改同一张表的不同列而版本号不冲突
  （任何工具都查不出，只能合并前协调）。历史 `V1..V24` 已在白名单里冻结，不要再加。
- **⚠️ 时间戳迁移还有第三种失败：版本号倒挂（2026-10-21 实测，线上挂了 20 分钟）**。
  两个会话各自生成时间戳，A 会话先跑完、先部署（版本 `…135406` 上了生产），B 会话的迁移时间戳
  **更早**（`…134700`）、后部署 → Flyway 报
  `Detected applied migration not resolved locally` + `Detected resolved migration not applied`
  → **应用整个起不来**。
  **测试一路绿的原因**：`application-test.yml` 开了 `out-of-order: true`，生产**刻意没开**（规则见上）。
  所以这类问题只在生产暴露，本地永远看不见。
  **处理方式（按代价从低到高）**：
  1. **合并前 `git log --all --oneline -- <迁移目录>` 看清楚两边各加了什么时间戳**；
  2. 若发现自己的更早，**把自己的迁移重命名成比对方更晚的时间戳**（内容不变，校验和不变），
     再 `mvn clean package`——**不 clean 时旧文件会残留在 `target/classes` 里，jar 里会同时打两个版本**；
  3. 构建前确认 jar 内容：`unzip -l … | grep 迁移名`，**只能有一个**；
  4. 从**主树**构建（工作树可能是合并前的快照，缺对方的文件 → `applied migration not resolved locally`）。
- **生产迁移不得写业务数据**（2026-10-21 定，起因是 V24 撞种子的 201 个错误）：
  生产迁移只建结构；种子数据一律放 `db/devdata`。若确需在迁移里插行，**必须显式写 `id` 且 ≥ 900**。
  这类冲突只在**零数据新库**上出现，生产库与开发库都看不出来，唯一可靠的拦截是构建期守卫
  `MigrationSeedOverlapTest`。**换迁移工具挡不住它**——成因是"迁移写数据 + 种子硬编码 id"这个组合，
  与 Flyway 的版本号模型无关，所以 2026-10-21 决定**保留 Flyway**、用纪律与守卫解决。

## 服务器环境（2026-10-09 勘察）

访问方式：本机 `quick-server` 技能的别名 `codenest-online`（地址与凭据见该技能配置与 Windows 凭据管理器）。**凭据不得写入任何文件、命令参数或仓库。**

| 项 | 现状 |
| --- | --- |
| 系统 | Ubuntu 24.04.4 LTS，4 核 / 16GB 内存 / 79GB 磁盘（约 66GB 可用） |
| JDK | Temurin **25.0.4**（LTS）位于 `/opt/jdk25`，另有 `/opt/jdk17`。**两者均未注册到 `update-alternatives`，`JAVA_HOME` 未配置，`java` 不在 PATH 上**，使用前需接线 |
| MySQL | **8.0.46**（Ubuntu 包），运行中；2026-10-16 起监听 `0.0.0.0:3306`（对公网开放，仅 `paideia_test@'%'` 可连且只授权测试库），X 协议 33060 仍只监听 127.0.0.1 |
| Redis | 7.0.15，运行中，只监听本机，**当前为空且后端零引用**——没被任何一条业务路径用到（运维页如实标注「未被应用使用」） |
| 缺失 | 没有 RabbitMQ、没有 Nginx；80/443 未监听（Docker 已于 2026-10-15 装上，见 Firecrawl） |
| 运行方式 | paideia / laya / rustfs / mysql / redis-server / mihomo 各一个 systemd 单元 |
| **监控（WORK-028）** | prometheus / grafana / node-exporter / mysqld-exporter / redis-exporter / blackbox-exporter / ops-textfile.timer / paideia-backup.timer，**全部只监听 127.0.0.1**，公网仍只有 8081；六个常驻进程合计约 335MB。管理端「运维」页 = 七张四态卡片（`/api/v1/ops/admin/summary`，ADMIN）+ Grafana 面板（反代，Grafana 自己的会话） |
| **备份（WORK-028）** | `paideia-backup.timer` 每日 03:15（`Persistent=true`，错过补跑）→ `/opt/paideia-backup/`，保留 7 天。**本机同盘，救不了整机损坏** |

**项目隔离要求（用户 2026-10-09 决定）**：Paideia 与 Code-Nest 彻底分离。Paideia 使用独立目录（`/opt/paideia-app`）、独立数据库与独立数据库账号；不复用、不修改其他项目的服务与数据。Code-Nest 的应用与 SQL 转储已按用户指示从该服务器删除。**库里只有两个（2026-10-10 用户要求统一，不再增加）**：`paideia`（开发与部署共用）与 `paideia_test`（测试沙箱）。

**传输层：不要求 HTTPS（用户 2026-10-09 决定）**：自托管环境用明文 HTTP 即可，不配 TLS。已知并接受的后果：密码与令牌在网络上明文传输，链路上任何一环都能读到。两条要记住的推论：① 浏览器会对登录页显示"不安全"，这不是 bug；② 将来若只把前端或后端一端换成 HTTPS，浏览器会因混合内容直接拦掉请求，**要上就两端一起上**。触发重评：一旦对外网开放或出现不只有自己使用的真实凭据。

## 部署（2026-10-10 首次部署，供验收）

- **形态：一个 jar 同时提供 API 与前端静态**（服务器上没有 Nginx、没有 Docker）。前端产物放 `/opt/paideia-app/web/`（学习者端在根，管理端在 `web/admin/`），由 `spring.web.resources.static-locations=file:/opt/paideia-app/web/` 提供。**两个前端各占一个地址：学习者端 `/`、管理端 `/admin/`**——但由**同一个进程**提供，因为这台机器对公网只开了一个端口（8080/80/81/8082 实测全部超时，只有 8081 通）。Spring 不会给子目录自动补 index，所以 `paideia-web` 的 `WebCorsConfiguration` 里加了两条 view controller 把 `/admin` 与 `/admin/` 转发到 `/admin/index.html`。**要拆成两个端口**：先在云安全组放开一个端口，再起第二个实例（同一 jar、另一份配置只改 `server.port` 与 `static-locations`）；内存不是瓶颈（16G 里用了 1.5G）。
- **进程**：systemd 单元 **`paideia`**（`systemctl status/restart/stop paideia`），`ExecStart` 为 `/opt/jdk25/bin/java -jar /opt/paideia-app/app.jar --spring.config.additional-location=file:/opt/paideia-app/`，日志走 journald（`journalctl -u paideia`）。
- **配置**：`/opt/paideia-app/application.yml` **只存在于服务器上**（含数据源口令、JWT 密钥、SMTP 授权码），由本地被忽略的配置生成，**绝不进仓库**。关键项：`server.port=8081`、`paideia.knowledge.upload-dir=/opt/paideia-app/uploads`。
- **端口 8081**：服务器安全组只放开少数端口——实测 **8080 与 80 从公网不可达**（HTTP 000）、**8081 可达**。换端口只需改配置里的 `server.port` 再 `systemctl restart paideia`。
- **数据库**：开发与部署共用 **`paideia`**（2026-10-10 用户要求统一，`paideia_prod` 已删除）。**测试仍用 `paideia_test`**，原因是硬的：测试要加载 `db/devdata` 的种子账号（`seed-admin` 的口令公开写在仓库里），而部署端口 8081 公网可达——两者共用一个库就等于在线上开一个口令人人皆知的管理员。**要真正把测试并进来，必须先让测试不依赖种子账号**（集成测试改为自建数据、e2e 改用运行时生成的账号），这是一次约 1.5 小时的改写，尚未做。
- 曾经的坑要记住：`paideia` 的历史里一度带着 `V950`，说明**有运行时误连过 dev 库**（正是 `playwright.config.ts` 警告过的那个）；已修（删掉那条历史记录与两个种子账号），现在历史是干净的 V1→V3。
- **部署实例不加载种子**：`db/devdata` 不在默认迁移目录，因此部署库里没有 `seed-admin`／`seed-learner`（那两个口令公开写在仓库里，放到公网可达的实例上等于开放管理权限）。管理员账号用**一次性 SQL** 单独建，口令不落任何被跟踪文件。
- **同源部署必须放行静态资源**：`SecurityConfiguration` 里加了 `GET /`、`/index.html`、`/assets/**`、`/admin/**`、`/favicon.ico` 的放行——不放行时连登录页都返回 401（实测）。**放行静态文件不等于放行数据**：授权仍只由 `/api/**` 上的规则决定。

**监控与备份（WORK-028，2026-10-20）**：

- **`release.ps1` 只重启 `paideia`**，不碰监控单元——监控是独立 systemd 单元，部署不影响它们，也不该重启它们（重启会丢掉 Grafana 的会话与 Prometheus 的内存缓冲）。
- 首次安装或改动监控配置：`ssh codenest-online 'cd /opt/paideia-ops/staging && PAIDEIA_DB_PASSWORD=… GRAFANA_SMTP_PASSWORD=… GRAFANA_ADMIN_PASSWORD=… bash install-ops.sh'`。**必须带 `http_proxy=http://127.0.0.1:7890`**（直连 Docker Hub 与 grafana apt 源均失败，不带时表现为「下载卡住、无报错」）。
- **Grafana 面板需要它自己的口令**（`/etc/grafana/grafana.env`，600）。iframe 的子请求带不上平台令牌（项目无 Cookie、纯 Bearer），所以鉴权落在 Grafana 自己的会话上；应用对 `/api/v1/ops/grafana/**` 是放行的。摘要卡片仍走 `hasRole("ADMIN")`。
- **四条告警必须各自写 `notification_settings.receiver`**：不写时 Grafana 13 会路由到 `empty` 收件人，告警照常 firing、日志正常、**一封邮件都不发**。
- **告警表达式必须用 PromQL 的 `bool` 修饰符**：`x > 阈值` 在条件不成立时返回空结果，Grafana 判 NoData——「一切正常」与「采不到数」会长得一模一样。

**两个操作陷阱（都实测踩过，别再踩）**：

1. **删掉一个资源文件（如迁移 SQL）后必须 `mvn clean`**。Maven 不会从 `target/classes` 删掉已移除的资源，它会继续被打进 jar——本次真的把两个临时探针迁移带上了部署（它们会改全文索引）。
2. **用 Git Bash 调 remote 脚本时，`/opt/...` 这类参数会被 MSYS 转成 Windows 路径**，要加 `MSYS_NO_PATHCONV=1`。另外 `pkill -f '<模式>'` 会连**执行它的那条会话**一起匹配杀掉（命令串里含同样的模式），要按 PID 杀，或把模式写成 `http[.]server` 这种不匹配自身的正则。

## 待用户裁决清单

技术选型一律由用户裁决，Agent 只提供备选与优缺点；完整登记表见 `.agent/references/technology-options.md`。

| 项 | 编号 | 状态 |
| --- | --- | --- |
| 整体架构形态 | D-01 | 已定：模块化单体 |
| 模块边界强制手段 | D-02 | 已定：Spring Modulith |
| 后端构建工具 | D-03 | 已定：Maven |
| 仓库结构 | D-04 | 已定：单仓多模块 |
| 主键生成 | D-05 | 已定：数据库自增 |
| 分页方案 | D-06 | 已定：MySQL 分页组件 |
| 多数据库适配 | D-07 | 已定：不做适配，完全放开 MySQL |
| 迁移工具 | D-08 | 已定：**Flyway（2026-10-21 二次复核后维持）**——考虑过 Liquibase / MyBatis Migrations / jOOQ DDL 差分。前两者同样要唯一起名、同样有校验和，等于换个名字的同一个问题；jOOQ DDL 差分是唯一真正消灭命名空间争抢的方案，但代价是引依赖 + 学习成本 + MySQL DDL 解析边界。故改为「**14 位时间戳版本号 + 两道构建期守卫**」：治多会话撞号（实测撞号会让应用整体起不来），并承认它治不了「同秒生成」与「改同一张表的语义冲突」 |
| AI 接入与编排 | D-09 | 已定：AgentScope Java（唯一框架，不叠加 Spring AI）；Boot 4.1.x 兼容性尖刺**已通过** |
| 认证机制 | D-10 | 已定：自签 JWT（收在端口后保持可扩展） |
| 前端框架与渲染 | D-11 | 已定：Vite + React Router SPA |
| 桌面壳与交付时机 | D-12 | 已定：Electron，与 Web 同期交付（原 Tauri，2026-10-09 因缺 Rust/MSVC 改为 Electron） |
| 前端数据层 | D-13 | 服务端状态已定 TanStack Query；客户端临时状态暂缓 |
| 消息与异步 | D-14 | 已定：RabbitMQ（配合 outbox 中继）；**尚未接入**，见上文状态说明 |
| 其余基础设施 | D-15 | 暂缓：缓存、国际化、测试栈（**对象存储 2026-10-15 接入见 WORK-007**；**可观测性 2026-10-20 已定 Prometheus + Grafana，见 WORK-028**） |
| 部署形态 | — | 自托管在自有服务器；细节本期不考虑 |
| 数据与隐私 | — | 暂缓：角色模型、留存期限、删除权、未成年人合规、AI 对话数据处理 |
