---
artifact: requirements
work_id: WORK-057
work: 源码精读（第三方源码 + MyBatis 专题）
status: approved
mode: strict
mode_reason: 新增一个用户可见的顶层入口与三条路由；新增一个解析内容并 fetch 同源文件的围栏块（渲染契约变化）；新增一条免鉴权的静态放行路径（安全边界）；内容涉及第三方许可与固定版本。跨 ui / knowledge / app 三个前端包，以及内容与部署两处。
workflow: full
depends_on: [WORK-014, WORK-040]
related_to: [WORK-012, WORK-046, WORK-004, WORK-020]
created: 2026-10-30
updated: 2026-10-30
approved_by: xiaou61
approved_at: 2026-10-30 14:47:50 +0800
approver_role: CTO
---

# 源码精读（第三方源码 + MyBatis 专题）需求

## 用户诉求（原话）

> 「目前线上的这个源码阅读是什么东西？其实我想要的源码阅读不是一个这种，是一个单独的一个项，
> 然后不读我们的项目，我想读的比如说 mybatis  第一个我们就从 mybatis 开始吧。
> 然后是那种 有源码有讲解 有互动的，你新开一个work 看看打算怎么搞呢？」

> 「是的 应该是这样的，然后主要是互动性强一点，你知道什么意思吗，就是要有很高的互动性这样的。」
> ——2026-10-30，追加对互动强度的要求：不是「能点两下」，是读者必须动手才能往下走，
> 且**改一个条件，看到的链路要真的变**。

> 「先有的就删掉吧。首期做满 别7了，多一点，再多一点。就是详解mybatis 你直接去互联网上找一些类似的
> 作为参考 可以参考他们的这个东西。」
> ——2026-10-30，三项决定：删掉现有两条源码线；首期章节数远多于 7（按「详解」做）；先调研同类内容再定目录。

## 现状：线上「源码解读」是什么（2026-10-30 实测）

它**不是一个独立功能**，是知识库里的两个分类，正文用 WORK-014 的围栏块渲染：

| 分类 slug | 名称 | 线上条数 | 讲什么 |
| --- | --- | --- | --- |
| `source-reading` | 源码解读 | 2 | React 并发渲染入口；**本项目**的 AI 对话计费 |
| `code-reading` | 读代码实战 | 5 | **本项目**的 Spring 请求链路（路由、404、SQL、登录拦截） |

形态：每步一段**摘录代码**（`code-tour` 块自带 `code`）+ 讲解 + 可选的「先预测」+ GitHub 原文链接（固定 commit）+ 章内 `quiz`。有进度（条目页已接 `createServerProgressPort`），但入口只在知识库与首页快捷卡里，没有独立导航。

**与本项的差别**：本项读**第三方**项目、看**整份文件**（不是摘录）、有独立入口。
**这两条线在项内删除**（UD-006）：7 条正文、2 个分类、2 条学习轨道、首页那张「源码解读」快捷卡一起清掉，
内容源目录 `content/reading/source/` 与 `content/reading/code-reading/` 同时从仓库删除。

## 目标

给平台加一个**独立的「源码精读」区**，第一期把一个第三方项目读透：

1. 入口独立：导航「源码精读」→ 项目列表 → 章节列表 → 阅读页，不进知识库浏览流。
2. 读的是真源码：每个项目锁定一个 tag 与 commit，源码文件整份可见（带行号），讲解按步骤在文件上定位、高亮。
3. 有讲解、有很高的互动：读者改一个条件（插件开/关、缓存开/关、换一个方法），看到的调用链、状态与代码位置**真的跟着变**；关键判断点先猜后看；练习不止选择题。
4. 第一期项目 = **MyBatis 3.5.19**（Apache-2.0），按「详解」做 **24 关**（6 篇 × 4 关），覆盖初始化、SQL 构建、代理、执行、结果映射、缓存、插件与基础模块。

## 非目标

- **不做整仓浏览器**：不列全仓文件树、不搜索全仓、不做 IDE。只展示章节用到的文件（整项目预估 35–45 个）。
- **不在线编译/运行 MyBatis**，不接 JVM 调试器，不做断点。沙箱那条路（WORK-015/032/056）不扩展到 Java 真实框架。
  **首期的执行轨迹是作者按真实运行结果预演的**，不是实时执行；改成真跑（Maven 离线依赖 + 数据库）要单开阶段。
- **不做自由画布/任意输入重放**：开关与分支来自内容作者声明的有限集合，不做"随便改代码看结果"。
- **不自动追最新版本**：版本由内容作者手工升级，升级是一次独立改动；不做多版本并存。
- **不做 mybatis-spring / Spring Boot starter 的集成源码**：那是另一个仓库（`mybatis-spring`），要另开一个项目。
- **不自动生成讲解**（不接 AI 生成正文）。讲解由作者写，行号锚定固定 commit。
- **删除现有两条源码线**（`source-reading` / `code-reading`，UD-006）；它们的正文与分类不迁移、不保留。
- **不把整份源码拼进助手上下文**。v1 助手看到的是章节正文与步骤说明，不是整文件。
- **不新增后端表、端点、迁移**。
- **不做代码高亮引擎自研**：语法着色走现有 streamdown/Shiki 能力；只对本块做行号与范围高亮。

## 已确认事实

以下均于 2026-10-30 查证，不是推测。

| 项 | 事实 | 依据 |
| --- | --- | --- |
| 现有源码内容 | 生产库 `source-reading`（id 16）2 条（id 5053、10303）、`code-reading`（id 43）5 条（id 10245–10248、10300）；另有两条学习轨道 `source-reading`(id 5, 1 关) 与 `code-reading`(id 36, 4 关) | 服务器上 `select ... from knowledge_categories/knowledge_entries/learning_tracks` 实查 |
| 删除的连带影响 | `learning_progress.entry_id` 与 `learning_track_stages.entry_id` 都是 **ON DELETE CASCADE**；管理端**只有轨道 upsert、没有删除端点**，所以两条轨道要一次性 SQL 清 | `V18__create_learning_progress.sql:31`、`V22__create_learning_tracks.sql:52`、`KnowledgeAdminController` |
| 删除要动的引用 | 首页快捷卡 `home-entry-source-reading` 指向 `/knowledge/entries?q=源码解读`；`LearnPage` 有 `source-reading`/`code-reading` 两个图标映射 | `HomePage.tsx:119-126`、`LearnPage.tsx:85-86` |
| 现有块的种类 | `code-tour`（摘录+步骤）、`code-map`、`learning-path`、`code-challenge`、`quiz`、`sketch`，都只在文档模式启用 | `frontend/packages/ui/src/markdown/renderers/index.ts` |
| `code-tour` 的契约 | 每步**自带** `code`，`line` 是文件内绝对起始行，`focus` 是相对 `code` 的行下标；`permalinkOf` 生成 GitHub 链接 | `renderers/schema.ts` 的 `TourStep` / `CodeTour` / `permalinkOf` |
| 正文载体 | 章节正文 = `knowledge_entries.body`；渲染走 `MarkdownBody`（标题锚点、附件地址、AI 预填、进度都在这层） | `frontend/packages/knowledge/src/markdown-body.tsx` |
| 进度已服务端化 | 条目页用 `createServerProgressPort`，写 `PUT /api/v1/learning/entries/{id}`，并合并 localStorage | `frontend/apps/app/src/pages/KnowledgeEntryPage.tsx`、`packages/knowledge/src/learning-progress.ts` |
| 静态资源放行 | `SecurityConfiguration` 里是 **GET 白名单**：`/`、`/index.html`、`/assets/**`、`/admin/**`、PWA 几个文件。`/source/**` 不在其中，不放开会 401 | `paideia-security/.../SecurityConfiguration.java` |
| 路由与资源基址 | SPA 是 **hash 路由**（`createHashRouter`），Vite `base: './'`；文档地址停在 `/index.html#/...`，相对路径 `source/...` 在 Web 与桌面壳都解析到站点根 | `frontend/apps/app/src/router.tsx:48`、`vite.config.ts:17` |
| 内容导入 | `node scripts/import-course.mjs --dir <目录> [--dry-run] [--no-track]`，走管理端 API，按「分类+标题」幂等；`line_parent` 支持父子分类（WORK-046 书籍） | `scripts/import-course.mjs` 头注释与 `lineSpecOf` |
| MyBatis 版本 | 3.5 线最新 tag 是 **3.5.19**；`3.6.0` 已存在但生态资料少 | `git ls-remote --tags https://github.com/mybatis/mybatis-3` |
| MyBatis 许可 | **Apache-2.0** | `gh api repos/mybatis/mybatis-3/license` → `Apache-2.0` |
| 导航 | 页头四组 `NAV`，每一项必须有 `key`/`group`/`to`/`label`/`testId`；`preferredNav` 是联合字面量，只有需要身份排序的 key 才加 | `frontend/apps/app/src/components/LearnerHeader.tsx:57`、`packages/identity/src/config.ts:16` |

### 调研：同类内容怎么组织（2026-10-30 联网核实）

| 来源 | 结构 | 取什么 / 不取什么 |
| --- | --- | --- |
| [芋道源码《精尽 MyBatis 原理与源码解析》](https://www.iocoder.cn/MyBatis/good-collection/) | 核心 25 篇：会话 1、初始化 4、SQL 初始化 2、SQL 执行 5（Executor / StatementHandler / KeyGenerator / ResultSetHandler / 延迟加载）、插件 2、基础模块 11（解析器、反射、异常、数据源、事务、缓存、类型、IO、日志、注解、Binding）；另有调试与项目结构 2 篇、Spring 集成 5 篇 | 取**覆盖度与分层**：基础模块也讲；不取"纯文字 + 代码片段"的形态 |
| [udbwcso《MyBatis 源码解析》](http://www.iocoder.cn/MyBatis/udbwcso/SqlSessionFactory) | 7 篇：SqlSessionFactory / Configuration / 事务管理器 / SqlSession / mapper 动态代理 / MapperMethod / TypeHandler | 取**最小主线**，作为阅读顺序的骨架 |
| [小傅哥《手写 MyBatis：渐进式源码实践》](https://bugstack.cn/md/product/book/mybatis.html) | 22 章 5 段：基本调用链路与 XML → 数据源与执行 → 参数与结果 → 注解/ResultMap/事务/自增主键/动态 SQL/插件/两级缓存 → Spring 集成与设计模式 | 取**先后顺序**（先跑通主链路再补外围）与"每章先说要解决什么问题"；不取"从零手写"路线（本项读真源码，不造轮子） |
| WORK-014 已调研的同类产品（walk-the-code、VisuAlgo、Python Tutor、DeepWiki、CodeCrafters、Hello 算法、Distill） | 引导走读、图与代码联动、预测后揭晓、动手练习、进度 | 取这五件；不取自动索引、真实执行与动画引擎 |

**结论**：目录取「芋道的覆盖度 + udbwcso 的主线顺序 + 小傅哥的分段」，互动取上表最后一行，再加本项的「改条件链路要变」。

## 用户决定

| 编号 | 决定 | 影响 |
| --- | --- | --- |
| UD-001 | 源码阅读要**独立成一个项**，不是现有知识库分类那种形态 | 新增顶层入口与独立路由 |
| UD-002 | **不读本项目**，读第三方开源项目 | 内容只放第三方；现有两条线不属于本项 |
| UD-003 | 第一个项目从 **MyBatis** 开始 | 首期内容范围 |
| UD-004 | 要「有源码、有讲解、有互动」 | 整份源码可见 + 步骤讲解 + 小测 |
| UD-005 | 互动性要**很高**：改条件链路要真的变，练习不能只有选择题 | 新块需要「开关/分支 + 状态面板 + 跨文件跳转 + 多题型」，不是加几个按钮 |
| UD-006 | **删掉现有的两条源码线**（`source-reading` 2 条 + `code-reading` 5 条，含分类与学习轨道） | 生产库与仓库同时清理；首页快捷卡一并删除 |
| UD-007 | 首期不是 7 关，要**多得多**，按「详解 MyBatis」的规模做 | 首期定为 24 关（6 篇 × 4 关），内容量是原计划的 3 倍以上 |
| UD-008 | 目录要**参考互联网上同类内容** | 先做同类调研再定目录，不凭空排章节 |

## 实现选择

只记录会改变交付物形态、且已由仓库事实约束的选择。

| 编号 | 选择 | 理由 |
| --- | --- | --- |
| IC-001 | 源码快照放 `frontend/apps/app/public/source/mybatis-3.5.19/...`，随前端构建进 `web/`，由 Spring 静态提供 | 不建表、不加读接口；版本固定后文件不再变；hash 路由 + `base: './'` 下相对路径在 Web 与桌面壳都成立。代价是内容修正要发前端版，可接受 |
| IC-002 | **新开一个围栏块 `source-walk`**，不改造 `code-tour` | `code-tour` 的契约是"每步自带 code"，改它要动既有 6 篇正文与全部单测；新块只声明「文件 + 绝对行区间」，代码由整文件提供 |
| IC-003 | 块的步骤用 `line`（绝对起始行）+ `lines`（覆盖行数），不沿用 `focus` 的相对下标 | 整文件模式下行号必须能直接对上文件；两套语义混在一个字段里必然出错 |
| IC-004 | 章节正文仍是 `knowledge_entries`，分类形态 = 父分类 `source` + 每项目一个子分类（`--no-track`），与书籍详解同形 | 与 WORK-024 同一取舍：不重造渲染、进度与导入流水线；也不引入关卡解锁 |
| IC-005 | 进度复用 `PUT /api/v1/learning/entries/{id}`（条目页已在用），不建新表 | 同一份数据可被学习记录/复习读到；v1 不做项目级汇总接口，项目页从分类条目 + 本地合并算 |
| IC-006 | 阅读页是**独立页面** `/source/:project/:entryId`，渲染 `MarkdownBody` + 项目元信息条 + 上一章/下一章 | 直接跳 `/knowledge/entries/:id` 会把用户带回知识库语境，与 UD-001 冲突；页面只需组装，不复制 `KnowledgeEntryPage` 的 TOC/助手逻辑 |
| IC-007 | 助手上下文 v1 不含整文件 | `entryBody` 是整篇正文，塞 2 万行会撞上下文预算；步骤的 `explain` 已带关键结论。要改另开项 |
| IC-008 | 首期 **24 关**（总览 + 23 章），按 6 篇组织：起步 / SQL 的诞生 / 接口变成调用 / 执行 / 结果与缓存 / 扩展与底座 | 24 关才能同时覆盖主链路与基础模块；分篇让读者可以按主题跳读，不必从头读到尾 |
| IC-009 | 互动是**数据驱动**的：开关与分支在块里声明，步骤带 `when` 条件，切换后重算可见步骤；不在块里写业务逻辑 | 内容作者能独立改互动，不用改组件；也保证"切换后链路真的变"是可校验的纯函数行为 |
| IC-010 | 状态面板按「调用栈 / 关键变量 / 注册表」三组渲染，数据随步给出 | 复用现有 `state` 快照的思路；三个组的语义固定，作者只填内容，避免每章各造一套展示 |
| IC-011 | 删除走「先内容源、再线上条目、再分类、最后轨道」四步；线上用管理端删除条目 + 一次性 SQL 清轨道，操作前后各查一次计数 | 管理端没有删轨道的端点；条目一删，进度与关卡行按 CASCADE 自动消失，轨道壳会留在 `/learn` 里成为一个空线 |

## 功能需求

**REQ-001 独立入口**
页头「源码精读」入口；`/source` 项目列表，卡片显示项目名、一句话定位、版本、许可证、章节数、我的进度（x/y）。

**REQ-002 项目章节列表**
`/source/:project` 按内容顺序列出章节：序号、标题、时长、是否完成；顶部固定显示 `repo @ tag`、commit、许可证与「查看仓库」。

**REQ-003 章节阅读页**
`/source/:project/:entryId`：标题 → 元信息条（项目、tag、commit、许可）→ 正文 → 上一章/下一章。
正文里的 `source-walk` 块同时呈现**整份源码**与**步骤讲解**；桌面端左右两栏（代码可滚动、讲解随步骤切换），窄屏上下排列。

**REQ-004 源码面板**
- 显示文件完整内容与行号，文件头显示路径与「GitHub 原文」链接（固定 commit 与行区间）。
- 切换步骤时，高亮该步的 `line`–`line+lines-1`，并滚动到可见位置。
- 一章可涉及多个文件：步骤可指向任意已声明文件，切换时面板换文件。
- 只读取**同源相对路径**；绝对 URL、`..` 越界、非 `.java` 扩展名一律拒绝并降级为块内错误提示。

**REQ-005 高互动（核心）**
块内提供以下五类互动，每一条都必须是"读者做了动作 → 页面有可观察变化"：

1. **实验开关**：每个开关（如「装上插件」「关掉一级缓存」）勾选/取消后，可见步骤序列重排，状态面板与高亮位置同步变化；被条件隐藏的步骤不出现在步骤列表里，也不参与进度计算。
2. **分支选择**：单选（如「查 id / 查 name / 调 update」）决定走哪条轨迹；切换后当前步骤重置到该分支的第一步，并给出"你选的分支与上一条的差别"的一句话提示。
3. **状态面板**：每一步显示三组内容——调用栈（从入口到当前位置）、关键变量（对象字段/参数值）、注册表或缓存（如 `Configuration.mappers`、`Executor` 类型）。步骤切换时三组同步更新，不做动画补间。
4. **跨文件跳转**：讲解中的类名/方法名是可点击的定位点；点击后代码面板打开目标文件并高亮目标行，提供「返回上一步位置」。跳转不改变当前步骤的进度。
5. **多题型练习**：预测（先猜后看）、排序（把调用链排对）、填空（补关键一行）、找错（指出哪一步条件写反）；每题答完显示解释，答错可重试，得分取最高一次。

**REQ-010 每章互动下限**
每一章必须满足：至少 1 个可切换的开关或分支、至少 2 处「先猜后看」、至少 1 道非选择题（排序/填空/找错）、每一步都有状态面板内容。总览章可以只有地图与 1 处预测。

**REQ-006 进度**
- 答题得分与「读到最后一步」写入服务端进度（复用现有端口与会话合并逻辑）。
- 项目页与章节页显示同一份进度，刷新与换设备后一致（已登录时）。
- 不引入「上一章通过才解锁下一章」。

**REQ-007 内容与流水线**
- 新增 `content/source/mybatis/`（24 篇章节 markdown）与 `frontend/apps/app/public/source/mybatis-3.5.19/`（源码快照）。
- 每篇 frontmatter 声明 `category`、`order`、`minutes`、`level`、`status`；总览声明 `line_parent: source`、`line_name`、`line_sort`。
- 沿用 `import-course.mjs --dir content/source/mybatis --no-track`，第二次运行必须 `created 0 / updated 0`。
- 每篇正文顶部给出：来源仓库、tag、commit、许可证，以及「本页引用的是 Apache-2.0 代码，仅作讲解」。

**REQ-011 清理现有源码线**
- 删除生产库 7 条正文（id 5053、10303、10245–10248、10300）、分类 `source-reading`(16) 与 `code-reading`(43)、学习轨道 `source-reading`(5) 与 `code-reading`(36)。
- 删除仓库内容源 `content/reading/source/` 与 `content/reading/code-reading/`，以及首页快捷卡 `home-entry-source-reading` 与 `LearnPage` 的两个图标映射。
- 删除前打印待删清单（id + 标题）并计数；删除后复查：两个分类 0 条、两条轨道 0 行、`/learn` 不再出现这两条线。
- **不动** `git` 分类（id 36 是 Git 线，与本次无关，别按 id 误删）。

**REQ-008 安全**
- `SecurityConfiguration` 静态白名单只加 `GET /source/**`，不得放宽到其它方法或其它路径。
- 块解析出的路径必须是相对路径，且解析后的 URL 与当前站点同源；解析失败不发起请求。

**REQ-009 可访问性**
- 源码面板可用键盘操作（步骤前后、文件切换）；高亮不只靠颜色（同时有左侧标线与行号加粗）。
- 代码区 `role="region"` 带可读标签；步骤按钮有 `aria-current`。

## 首期目录（24 关）

章节标题与顺序可以在实施中微调，但**篇数与总关数不降**。

**篇一 · 起步（4）**
1. 总览：MyBatis 的三层结构与一次查询的完整链路
2. 跑起来：`SqlSessionFactoryBuilder` 与 `XMLConfigBuilder`
3. `Configuration`：配置对象里到底存了什么
4. 映射文件：`XMLMapperBuilder` 如何产出 `MappedStatement`

**篇二 · SQL 的诞生（4）**
5. 注解方式：`MapperAnnotationBuilder` 与 XML 混用
6. 动态 SQL（上）：`SqlNode` 树与组合模式
7. 动态 SQL（下）：`SqlSource` 与 `BoundSql` 的生成时机
8. 参数名：`ParamNameResolver` 与 `@Param` 的真实作用

**篇三 · 接口变成调用（4）**
9. `MapperRegistry` 与 `MapperProxyFactory`：接口怎么变成对象
10. `MapperProxy`：JDK 动态代理为什么够用
11. `MapperMethod`：方法签名到 `SqlCommand`
12. `SqlSession`：三种实现与生命周期

**篇四 · 执行（4）**
13. `Executor` 家族与 `CachingExecutor`
14. 一级缓存：作用域、失效与坑
15. `StatementHandler` 与 `ParameterHandler`
16. 主键回填 `KeyGenerator` 与批量执行

**篇五 · 结果与缓存（4）**
17. `ResultSetHandler`：自动映射 vs `resultMap`
18. `TypeHandler` 与类型模块
19. 延迟加载：`ProxyFactory` 与 Javassist / CGLIB
20. 二级缓存与 `Cache` 装饰器链

**篇六 · 扩展与底座（4）**
21. 插件：`Plugin.wrap` 与四个拦截点
22. 数据源与连接池：`PooledDataSource`
23. 事务与日志：`JdbcTransaction` / `LogFactory`
24. 反射与元数据：`MetaObject` / `Reflector`（收尾：接下来怎么自己读）

## 质量要求

- 新块的 schema 校验是纯函数并被单测覆盖：合法、缺字段、越界行号、绝对 URL、`..` 各一条用例。
- 开关/分支的步骤过滤是纯函数并被单测覆盖：同一组开关组合永远得到同一份步骤序列；被隐藏的步骤不计入进度与总分。
- 阅读页有一条 e2e：打开章节 → 切下一步 → 高亮行变化 → 答一题 → 刷新后进度还在。
- 导入后线上抽查一篇：`/source/mybatis/<id>` 能渲染、代码可滚动、GitHub 链接 200。
- 源码文件的许可证与版本在每篇正文与实际快照上一致（人工核对一次）。

## 约束与依赖

- **许可**：MyBatis 为 Apache-2.0，允许引用与再分发，必须保留版权与许可声明；页面显示项目许可证，仓库内保留 `LICENSE` 副本与来源说明。
- **版本**：快照锁定 `mybatis-3.5.19`（commit 在实施时写入内容）；升级不混在同一次改动里。
- **体积**：快照只放章节用到的文件（预估 35–45 个、400–800 KB），不整仓拷贝（整仓约 2.1 万行）。
- **依赖 WORK-014**：围栏块的注册表、错误边界、文档模式限定、`quiz` 块全部沿用。
- **依赖 WORK-040**：内容导入与「父子分类 + `--no-track`」的既有约定。
- **不改 schema、不加迁移**；部署仍是 `scripts/release.ps1`。

## 关联工作项

| 工作项 | 关系 | 说明 |
| --- | --- | --- |
| WORK-014 源码解读专题与交互式代码走读 | 硬依赖 | 块注册表、错误边界、`quiz`/`code-map`、AI 预填都来自它 |
| WORK-040 开源项目详解 | 硬依赖 | 定下了「简讲放知识库、精讲放学习线」的分工；本项是第三种形态（整文件精读），不与之冲突 |
| WORK-046 书籍详解专题 | 软关联 | 独立的顶层入口 + 父子分类 + 固定 slug 路由，是本项直接照搬的先例 |
| WORK-012 流式 Markdown 渲染 | 软关联 | 块所在的渲染管线 |
| WORK-020 学习线与从零课程 | 软关联 | 进度与条目承载方式 |

## 验收标准

| 编号 | 可观察结果 |
| --- | --- |
| AC-001 | 页头点「源码精读」进入 `/source`，可见 MyBatis 卡片，显示 `3.5.19`、`Apache-2.0` 与章节数 |
| AC-002 | 打开任一章，源码面板显示完整文件与行号；点击下一步，高亮区间变化且目标行滚入视口 |
| AC-003 | 面板与步骤里的「GitHub 原文」链接指向固定 commit 的对应行区间，打开后行号一致 |
| AC-004 | 预测题答错后显示揭晓解释；章末小测答完显示得分，刷新页面后得分仍在（已登录） |
| AC-005 | 项目页的进度与章节页的完成状态一致；换设备登录后仍是同一份 |
| AC-006 | 375px 宽下代码与讲解上下排列，无横向溢出；键盘可切换步骤 |
| AC-007 | 把块里的 `file` 改成 `https://evil.example/x.java` 或 `../../etc/passwd`，块显示错误提示且**不发请求**（反向验证） |
| AC-008 | 未登录 `GET /source/<文件>` 返回 200；`POST /source/<文件>` 不是 200（白名单只放 GET） |
| AC-009 | `import-course.mjs --dir content/source/mybatis --no-track` 第二次运行 `created 0 / updated 0` |
| AC-010 | 抽查一篇：正文顶部有仓库、tag、commit、许可与来源链接，许可证与快照一致 |
| AC-011 | 在任一章取消一个开关：步骤数与步骤内容变化，状态面板对应字段变化；勾回后序列与位置复原 |
| AC-012 | 切换分支后，第一步的高亮行区间与状态面板里的命令名/SQL 与另一分支不同 |
| AC-013 | 点击讲解里的跨文件定位点，代码面板切到目标文件并高亮目标行；点「返回」回到原文件与原行 |
| AC-014 | 逐章核对：开关/分支 ≥1、预测 ≥2、排序/填空/找错 ≥1、每步有状态内容（清单与截图留证） |
| AC-015 | 现有两条线清理完成：分类 16/43 各 0 条、轨道 5/36 各 0 行；`git` 分类（id 36，8 条）不受影响——**分类 id 36 与轨道 id 36 是两张表里的两个对象**；`/learn` 不再出现「源码解读」「读代码实战」 |
| AC-016 | 线上 `/source/mybatis` 显示 24 关，逐关可打开；第二次导入 `created 0 / updated 0` |
| AC-017 | 首页不再有「源码解读」快捷卡；全仓 `rg "source-reading|code-reading"` 不再有内容与路由引用（历史工件除外） |

## 假设

- 首期读者是已登录的学习者（与现有全部学习页面一致），不做匿名访问。
- 24 关按篇分批交付：先出「篇一 + 引擎」验证形态，再连续补完其余五篇；**不停在验证关，最终 24 关全上线**。
- 更多项目（Spring、Redis、Netty）在 MyBatis 验收后按同一流水线追加，不预先造。
- 代码语法高亮沿用现有能力即可，不做逐行 diff 或类型跳转。

## 待确认问题

无。UD-006（删除现有两条线）、UD-007（做满 24 关）、UD-008（先调研同类）已在 2026-10-30 由用户确认。
