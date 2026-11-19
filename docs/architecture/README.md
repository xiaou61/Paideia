# 架构与流程图存档

这个目录存**长期维护**的架构图与流程图。它存在的理由不是"画得好看"，而是**改代码的人能顺手改图，AI 能写、能改、能自检**。

## 为什么是 mermaid，不是画布工具

图的权威副本必须是**纯文本、跟代码同仓、能一行 diff**。draw.io / Excalidraw 这类画布格式的 diff 全是噪声，AI 只能整张重画、无法局部修改，一年后必然过期且没人敢碰。

mermaid 在 GitHub 上原生渲染，且在 `frontend/` 里已有渲染链路（助手、知识库条目都用 streamdown，mermaid 是它的一等公民插件）——同一份语法既是文档也是产品里能渲染的东西。

## 两类图，两种生产方式

这是本目录最重要的约定。混着来是这类存档腐烂的主要原因。

| | 谁写 | 为什么 |
| --- | --- | --- |
| **结构性的图**<br>模块依赖、表关系、接口清单 | **脚本生成，不让 AI 画** | 答案在 `pom.xml` / `information_schema` 里，不在语义里。模型凭印象复述一定会漏会错，而且错得很像真的 |
| **叙事性的图**<br>请求链路、时序、状态机、业务流程 | **AI 直接写 mermaid** | 答案在代码语义里，抽取不出来，只能理解后表达 |

标了「由脚本生成」的文件**不要手改**——手改会让 `--check` 失败，那是刻意的：那张图必须等于事实。

## 现有图

| 文件 | 类型 | 内容 |
| --- | --- | --- |
| [`01-module-dependencies.md`](01-module-dependencies.md) | 脚本生成 | 后端 12 个 Maven 模块的依赖方向 |
| [`02-request-path.md`](02-request-path.md) | AI 撰写 | 一个请求从浏览器到数据库、以及 AI 流式回答的时序 |
| [`03-deployment.md`](03-deployment.md) | AI 撰写 | 部署形态、信任边界与端口拓扑 |
| [`04-frontend-packages.md`](04-frontend-packages.md) | 脚本生成 | 前端 17 个 workspace 包的依赖方向 |
| [`05-database-tables.md`](05-database-tables.md) | 脚本生成 | 74 张表与 16 条真实外键 |
| [`06-api-surface.md`](06-api-surface.md) | 脚本生成 | 212 个端点 / 53 个控制器的清单 |
| [`paideia-architecture.html`](paideia-architecture.html) | 产物（源是 `.archify.json`） | 20 个运行时组件的全景交互图：5 个视图、可点源码、深浅主题 |

## 全貌图：Archify（第三种生产方式）

上面六张 mermaid 图画的是**一个维度**（依赖方向、时序、拓扑、表关系、接口面）。新人第一句想问的却是"这系统长什么样"——六个维度拼起来才回答得了，而 mermaid 在 20 个节点之后自动布局就挤成一坨。所以多一张**全景图**：

| 文件 | 是什么 |
| --- | --- |
| [`paideia-architecture.archify.json`](paideia-architecture.archify.json) | **权威副本**。纯文本、可一行 diff、由人/AI 手写（不是脚本抽取——全景图要表达的是"哪几个组件算一个故事"，抽取不出来） |
| [`paideia-architecture.html`](paideia-architecture.html) | **产物**。单文件、自带数据、可直接双击打开；不要手改，改 JSON 再生成 |

它满足本目录的硬要求：权威副本是文本、能 diff、能自检；HTML 只是构建产物，和 `web/` 里那些一样。

重新生成（[Archify](https://github.com/tt-a1i/archify) v3.2.0，一次性 clone 到仓库外）：

```bash
git clone --depth 1 https://github.com/tt-a1i/archify "$TEMP/archify-src"
node "$TEMP/archify-src/archify/bin/archify.mjs" finalize architecture \
  docs/architecture/paideia-architecture.archify.json \
  docs/architecture/paideia-architecture.html \
  --repo-root . --quality standard
```

`finalize` 依次过四道闸（schema 校验 → 布局几何 → 交付写盘 → 无头浏览器实测），任何一道不过就不覆盖旧产物；`--repo-root` 是给图上的 `SRC` 深链取仓库路径。

两条维护约束，不写在别人脑子里就一定会忘：

- **`meta.repository.revision` 要跟着改**。它把图上的源码跳转钉在某个提交，不更新就跳到旧代码。
- **改了模块边界、外部依赖、部署拓扑的人**，在同一提交里改 `.archify.json` 再重新生成 HTML。

## 这份存档**不**包含什么

写出来是为了免得有人以为它完整：

- **没有接口的请求/响应结构**。`06` 只到"哪个前缀下有几个端点"，字段级契约在代码与测试里。要自动生成得引 springdoc + OpenAPI，那会改变公开的接口面，是另一个决定。
- **没有前端路由表**。路由在 `apps/app/src/router.tsx` 与 `apps/admin/src/router.tsx`，改得不频繁，一页一图的价值低于维护成本。
- **全景图不是 C4 的 component 层**。`paideia-architecture` 只放 20 个"运行时说得上话"的组件（够讲清故事），不是把每个 controller / service / 组件都画进去——那必然挤成一坨。
- **没有状态机图**。目前没有一处真正的状态机（会话与任务状态都是枚举字段），画出来是假的。
- **时序图不能自动生成**。`02` 是人工维护的，改链路时要人工改——这是本目录唯一靠自觉的部分。

新增图之前先问：它属于上面哪一类缺失，还是重复已有的一张。**图的成本是它存在之后的每一次改动**，不是画它的那一次。

## 改图之前先跑这两条

```bash
node scripts/check-diagrams.mjs          # 语法校验：全部 .md 里的 mermaid 图块
node scripts/gen-arch-diagram.mjs --check # 四张结构图（后端/前端/数据库/API）与声明是否一致
```

第一条是**给 AI 用的**，不是给 CI 用的：它让 AI 写完图能立刻确认语法是对的，不必等到有人打开文档才发现图是坏的。第二条防漂移（四张结构图各查一次）。

生成结构图用 `node scripts/gen-arch-diagram.mjs`（不加 `--check` 时直接重写文件）。

## 纪律

**改了模块边界、新增外部依赖、改数据库表、改部署拓扑的人，在同一个提交里改对应的图。** 工具管不了漂移，只有这条管得住。

新增一张图时的判断顺序：

1. 这个问题的答案能从代码/配置里**抽取**出来吗？能 → 写脚本生成，别手画。
2. 不能，得靠理解语义 → 写成 mermaid 放进对应的 `.md`。
3. 要的是**全景**（一份图讲清系统长什么样、能点进去看）→ 用 Archify 写 `.archify.json` 再生成 HTML，源文件留在本目录。
4. 只是要一张"给人看的漂亮图"（对外演示、评审封面）→ 用 draw.io 之类的画布工具，**但不要放进本目录当档案**。

## 已知限制

- `check-diagrams.mjs` 只做**语法**校验，不做渲染。所以"节点挤在一起"、"箭头跨太长"这类布局问题它查不出来，只能靠人眼看图。刻意如此：渲染要 headless 浏览器（几十秒 + 一次下载），语法校验才是每次改完都能跑的那部分。
- **`subgraph` 标题里带全角括号必须加引号**：`subgraph 运维面（含 Grafana）` 会报 `Lexical error ... Unrecognized text`，写成 `subgraph "运维面（含 Grafana）"` 才行。半角括号同样有风险，一律加引号最省事。节点标签里的 `**加粗**` 也不被支持，会被当成普通字符。
- mermaid 在节点数超过二三十个之后自动布局会明显变挤。真到那一步就拆图，不要硬塞。
- 本目录的图**不得包含服务器地址、端口映射到具体主机的信息、任何凭据**——本仓库是公开仓库。
