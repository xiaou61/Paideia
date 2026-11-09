# Paideia

**自托管的中文技术学习平台**：39 条学习线、近 600 条内容、每天一份 AI 速递；学完能自己练 SQL、模拟面试、改简历，学过的东西会进复习队列和知识图谱。

<p>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-blue">
  <img alt="Spring Boot 4.1" src="https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen">
  <img alt="React 19" src="https://img.shields.io/badge/React-19-61dafb">
  <img alt="MySQL 8" src="https://img.shields.io/badge/MySQL-8-4479A1">
  <img alt="Tauri 2" src="https://img.shields.io/badge/Tauri-2-24C8DB">
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-yellow">
</p>

![首页](docs/screenshots/home.png)

它不是"套壳的聊天框"：没有常驻对话窗，AI 只出现在具体动作里——生成条目摘要、按文章出题、把一天的信息压成一句话、按四块学习状态排今天的任务、面试追问、简历体检。每个动作按模型用量扣积分，上游 Key 由平台持有，使用者不用自备。

## 现在能做什么

### 学

- **39 条学习线、177 关**，先按岗位排出「接下来先学这三条」，再一站一站走，走完记一次通过。
- **书籍详解**：按书读，不按条查。已上线《人月神话》《数据密集型应用系统设计》《思考，快与慢》《代码整洁之道》，每本 4–5 篇。
- **源码精读**：拿真实项目逐关读，已上线 MyBatis 3.5.19（24 关）。版本号与 commit 钉在标题下，正文引用的行号以那个 tag 为准。
- **知识库**：近 600 条内容、50 来个分类，全文搜索、附件、条目间关系；正文右侧常驻目录，读到底才是上一篇／下一篇与自评。
- **面经题库**：每天从牛客面经里抽题、AI 逐题解析，按 Java／前端／测试／数据四个方向分开；标题就是题目，正文给结论与机制，底部留「回到原帖」。

### 每天打开的两页

- **每日速递**：每天抓 RSS、GitHub 趋势、Hacker News，AI 提炼成一句话并标热度，每条能一键存成笔记。
- **学习记录**：一屏四块——各线进度、最近没拿满分的关卡、到期复习、掌握度最低的概念；最后一格可以让 AI 按这四块排出今天要做的三件事。

### 练

- **SQL 练习**：在干净的 `orders-public` 数据集（3 张表、12 列）里随便写，结果表与图、执行计划树；可以让 AI 按数据集出题，也能自己造数据集（可视化建表或直接写 DDL）。
- **代码沙箱**（默认关闭）：课程里的可运行示例在 Java 21 / Python 3.12 容器里跑，同一套还支撑 Linux 判题练习场。

### 求职

- **模拟面试**：一次问一个问题，答不上来就说不会，可以带一份简历进去，结束按维度出一份结构化点评。
- **简历**：上传识别、站内编辑自动保存、体检（缺目标岗位、没写数字、某个分区是空的）、版本、按岗位另存一份优化。

### 记住你走到哪

- **自评与掌握度**：条目上标「懂了／还不懂」；同一个概念在图谱里由「看过、自评过、练习达标」三路证据抬档。
- **复习排期**：按到期日排队，答错顺延到明天、档位不变，全对才进一档。
- **笔记**：Markdown 编辑与预览、只读分享链接、AI 提要点、举报。

### 给外部助手

- **MCP 接入**：给 ZCode、Claude Desktop 这类外部助手发访问令牌，让它按权限读写你的笔记与知识库。

## 功能矩阵

| 能力 | 学习者端（Web） | 桌面端 | 管理端 |
| --- | :--: | :--: | :--: |
| 学习线、闯关、进度 | ✓ | ✓ | — |
| 每日速递 / 学习记录 / 复习 | ✓ | ✓ | — |
| 知识库阅读、自评、附件 | ✓ | ✓ | — |
| 面经题库（四个方向） | ✓ | ✓ | 内容导入与审核 |
| 书籍详解、源码精读 | ✓ | ✓ | — |
| 知识图谱（二维 / 3D） | ✓ | ✓ | — |
| SQL 练习、自建数据集 | ✓ | ✓ | 数据集治理 |
| 模拟面试、简历 | ✓ | ✓ | — |
| 笔记、只读分享 | ✓ | ✓ | 举报处理 |
| 分类 / 条目 / 概念 / 内容导入 | — | — | ✓ |
| 用户、积分、AI 运行、运维与事件 | — | — | ✓ |

桌面端是同一份 SPA 产物塞进 Tauri 2 壳，能力与 Web 一致，另加 `paideia://` 深链。

## 截屏

截图取自线上实例（2026-11-09，1440×900，浅色主题）。

**从零开始**：岗位只改排序，不改你能看到什么；「接下来先学这三条」每天重算一次。

![从零开始](docs/screenshots/learn.png)

| 每日速递 | 学习记录 |
| --- | --- |
| ![每日速递](docs/screenshots/digest.png) | ![学习记录](docs/screenshots/progress.png) |

| 知识库 | 条目 |
| --- | --- |
| ![知识库](docs/screenshots/knowledge.png) | ![条目](docs/screenshots/entry.png) |

| 书籍详解 | 源码精读 |
| --- | --- |
| ![书籍详解](docs/screenshots/books.png) | ![源码精读](docs/screenshots/source.png) |

**知识图谱**：节点是知识点不是文章，颜色深浅是掌握度，标出来的是「到期该复习」。

![知识图谱](docs/screenshots/graph.png)

**SQL 练习**：每次都在干净的数据集里跑，可以看执行计划，也能让 AI 按这份数据集出题。

![SQL 练习](docs/screenshots/sql.png)

| 模拟面试 | 简历编辑器 |
| --- | --- |
| ![模拟面试](docs/screenshots/interview.png) | ![简历](docs/screenshots/resume.png) |

| 目标与阶段 | 我的笔记 |
| --- | --- |
| ![目标与阶段](docs/screenshots/profile.png) | ![我的笔记](docs/screenshots/notes.png) |

**管理端**：分类、条目、概念、内容导入、用户、举报、SQL 数据集、AI 运行与积分审计、运维与事件。

![管理端](docs/screenshots/admin.png)

## 技术栈

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 后端 | Java 25 · Spring Boot 4.1 · 12 个 Maven 模块 | 模块边界由 `paideia-app` 的 `ModularityTest` 守着，跨模块只能走端口 |
| 数据 | MySQL 8 · MyBatis（手写 mapper）· Flyway · Redis（缓存） | SQL 看得见；迁移文件名用 14 位时间戳，并行开发不撞号；没部署 Redis 会自动回退 |
| 前端 | React 19 · Vite 8 · Tailwind 4 · React Router 8（Hash 路由）· TanStack Query | 一份产物同时跑 Web 与桌面 |
| 桌面 | Tauri 2，复用系统 WebView2 | 安装包约 5MB（Electron 壳是 106MB） |
| 沙箱 | 本机 Docker：MySQL 常驻容器池 + Java／Python 镜像（钉 digest） | 默认关闭；代码经 stdin 进容器，没有宿主挂载，根文件系统只读，drop 全部 capability |
| AI | 平台持有上游凭据 + 积分计费；向量检索用本地 Ollama（bge-m3） | 使用者不用自备 Key；凭据缺失不影响启动，只是 AI 入口提示「平台尚未配置」 |

## 快速开始

前置：JDK 25、Maven 3.9+、Node 20.19+（或 22.12+）、pnpm 12、MySQL 8。Redis、Docker、Ollama 都是可选件。

```bash
# 1. 建库
mysql -uroot -p -e "CREATE DATABASE paideia CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 2. 本地配置：仓库里没有示例（application-local.yml 被 .gitignore 挡住），自己写一份
#    最少需要 spring.datasource.{url,username,password} 与 paideia.auth.secret
$EDITOR backend/config/application-local.yml

# 3. 起后端（工作目录必须是 backend/，配置从这里读 ./config/）
cd backend
mvn -B -DskipTests package
java -jar paideia-app/target/paideia-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local

# 4. 起前端（另开一个终端）
cd frontend && pnpm install
pnpm dev          # 学习者端 http://localhost:5173
pnpm dev:admin    # 管理端   http://localhost:5174
```

配置里有两处容易踩：

- 数据源 URL 的 `connectionTimeZone=UTC` 与 `forceConnectionTimeZoneToSession=true` 必须成对出现，否则写入的时间整体差 8 小时。
- **没配 SMTP 时应用会直接启动失败**（验证码写日志等于注册后门）。只想本地跑起来，加一行 `paideia.mail.allow-log-fallback: true`。

要内容不要空库，可以导入一份课程线（`--dry-run` 先看会写什么）：

```bash
PAIDEIA_ADMIN_USER=<管理员> PAIDEIA_ADMIN_PASSWORD=<口令> \
  node scripts/import-course.mjs --dir content/courses --base-url http://127.0.0.1:8080
```

桌面安装包：

```bash
pnpm -w build:desktop   # 产物在 frontend/apps/desktop/src-tauri/target/release/bundle/nsis/
```

NSIS 是每用户安装（装进 `%LOCALAPPDATA%`），不能自选目录。

## 仓库结构

```
backend/           Spring Boot 4 多模块，每个模块一个 Maven 工程
  paideia-app        启动模块；集成测试、结构迁移（db/migration）与种子（db/devdata）的落点
  paideia-account    注册登录、验证码、OAuth、令牌轮换、账号注销
  paideia-security   鉴权与授权规则
  paideia-knowledge  分类、条目、附件、概念与掌握度、进度与复习、题库、学习轨道
  paideia-ai         摘要、对话、积分、速递、训练营、面试、简历、向量检索、AI 观测
  paideia-note       笔记与只读分享
  paideia-mcp        MCP 网关、个人访问令牌、调用用量
  paideia-sandbox    代码执行、SQL 容器池、数据集
  paideia-ops        运维摘要、Analytics、事件、自诊断
  paideia-persistence / paideia-web / paideia-platform   数据访问、Web 基础设施、共享契约
frontend/          pnpm workspace：5 个可运行端 + 11 个库包
  apps/app           学习者端（Web 与桌面共用）
  apps/admin         管理端
  apps/desktop       Tauri 2 外壳（src-tauri 在这里）
  apps/public        预渲染静态页（SEO 入口，单独构建，不进主站）
  apps/ui-kit        组件展览（仅开发）
  packages/ui        设计系统：令牌、基元、外壳、markdown 渲染（全站唯一实现）
  packages/core      端口与 API 客户端
  packages/auth      令牌存储、401 刷新重放、登录门
  packages/identity  画像读写（目标岗位/城市/阶段/求职季）、导航顺序常量、阶段建议
  packages/assistant 摘要卡、积分面板、SSE 客户端
  packages/knowledge 知识库客户端、目录、图谱布局、学习进度
  packages/camp      训练营：薄弱点、主题表、每日计划
  packages/resume    简历 DTO、自动保存状态机、模板
  packages/sql-workbench SQL 编辑器、结果表、执行计划、数据集设计
  packages/platform-web / platform-desktop   平台能力的两个适配层
content/           内容源文件（markdown + front matter，导入成条目）
  courses/           学习线
  reading/           按主题的内容，含书籍详解
  source/            源码精读（按项目、按关）
scripts/           测试库、内容导入与校验、架构图生成、发版
docs/architecture/ 架构图（mermaid；四张结构图由脚本从代码生成）
```

两条硬约束：业务代码不得直接依赖模型客户端的类型（收在 `paideia-ai` 的端口之后）；前端不得直接引用桌面壳专有 API（收在 `@paideia/platform-*` 适配器之后）。越界访问别的模块的 `internal` 包、或出现循环依赖，`ModularityTest` 直接判构建失败；前端越界由 `workspace-boundaries` 测试拦下。

## 常用命令

| 想干什么 | 命令 |
| --- | --- |
| 只跑一个后端模块的单测 | `cd backend && mvn -B -pl paideia-ai test` |
| 后端全量（独占测试库） | `scripts/test-db.sh` |
| 前端单测 + 类型检查 | `cd frontend && pnpm -r test && pnpm -r typecheck` |
| 只跑一条 e2e | `cd frontend && ../scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/knowledge.spec.ts` |
| 校验内容与架构图 | `node scripts/check-content.mjs`、`node scripts/check-diagrams.mjs` |
| 只校验、不发版 | `powershell -File scripts/release.ps1 -ValidateOnly` |

碰数据库的验证一律走 `scripts/test-db.sh`：它给每次运行分配独占的 `paideia_run_<时间>_<pid>` 库，成功后自动删除、失败保留库名。共用同一个库会让并行会话互相删数据——单类跑全绿、全量跑红。**没配测试库口令时，集成测试是整模块跳过而不是失败**，看结果要盯 `Skipped` 那一列。

## 部署

一个 jar 同时提供 API 与两份前端产物：学习者端在 `/`，管理端在 `/admin/`；桌面安装包单独出，不进 jar。配置、口令与上游凭据只存在于服务器上，发版不会覆盖服务器上的 `application.yml`。

```bash
powershell -File scripts/release.ps1
```

默认后端 + 前端（前端自上次发版起没动过就跳过那次构建）。开关：`-SkipWeb` 只发后端、`-WithWeb` 强制重建前端、`-WithTests` 发版前先跑全量后端测试、`-ValidateOnly` 只校验内容。脚本里不写服务器地址与凭据，走本机 SSH 别名 + Windows 凭据管理器；换机器发版要按自己的服务器重新接一遍。

## 这个项目不做什么

- **不做多租户、不做水平扩展**：一台服务器、一个 jar、一个 MySQL。要扩容只能换机器，这是刻意的取舍。
- **不做通用 AI 聊天产品**：没有常驻对话窗，AI 都绑在具体动作上，用完即走。
- **沙箱不是通用判题平台**：镜像固定 Java 21 / Python 3.12，默认关闭；没装 Docker 时相关入口直接不渲染。
- **内容以中文技术向为主**：Java、后端、数据库、运维占大头，前端与算法线还在补。
- **不是身份提供方**：OAuth 只用于登录与账号绑定，不对外签发凭证。

## 贡献

仓库是公开的，**任何凭据都不写入被 Git 跟踪的文件**（包括文档、测试夹具、提交信息）。提交前跑一遍相关的窄测试（见「常用命令」），提交信息写清改了什么、为什么。发现安全问题请私下联系，不要开公开 issue。

## 许可证

[MIT](LICENSE) © 2026 xiaou61
