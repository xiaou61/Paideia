---
artifact: proposal
work_id: WORK-014
work: 源码解读专题与交互式代码走读
status: approved
workflow: full
depends_on: [requirements]
related_to: [WORK-009, WORK-010, WORK-011]
created: 2026-10-17
updated: 2026-10-17
approved_by: xiaou61
approved_at: 2026-10-17 23:40:47 +0800
approver_role: CTO
---

# 提案 · 源码解读专题与交互式代码走读

## 摘要

做一个**可交互的源码课程专题**：内容仍住在知识库里（一个分类 + 一组 JSON 围栏块），但读者体验从"看文章"变成"走一条学习路径"——架构地图与走读双向联动、关键步骤先预测再揭晓、状态快照展示变量与调用栈、三种动手练习、即时判分小测、本地进度与继续学习。AI 在每个关键点可被唤起，只预填不自动发送。

不新增后端模块、表或端点；v1 只新增一个依赖（Shiki）。代码执行沙箱是另一个工作项（WORK-015），本项不碰。

## 推荐方案

### 内容模型：五种块 + 一个页级上下文

| 块 | 围栏语言 | 作用 |
| --- | --- | --- |
| 学习路径 | `learning-path` | 专题总览：有序站点、预计时长、本站状态 |
| 架构地图 | `code-map` | 可点击的节点图；点节点跳到走读步骤 |
| 走读 | `code-tour` | 行号 + 高亮 + 解释；可选预测题与状态快照 |
| 动手练习 | `code-challenge` | 填空 / 排序 / 找 bug，客户端判定 |
| 小测 | `quiz` | 多题即时判分与得分 |

块之间靠 **`SourceReadingProvider`（React context，页级）** 共享状态：`activeStep` 按走读块 id 记录，地图据此高亮节点；地图点击反向写入 `activeStep` 并滚动到走读块。进度通过 context 里的 `progress` 端口读写 `localStorage`，`entryId` 为空时（管理端预览）自动降级为不持久化。

### 关键取舍与理由

1. **地图手写、不引图库**：作者给 `layer`/`order`，我们用绝对定位的 HTML 按钮 + 背后一层 SVG 画边。节点天然可键盘聚焦（图形库的 SVG 可访问性通常要额外补）；体积为零；分层图足够表达"请求从哪到哪"。
2. **状态快照由作者写**：真实仓库跑不起来，执行器需要沙箱；快照能覆盖绝大多数教学场景，而且可离线、可审校。执行沙箱单开 WORK-015。
3. **练习客户端判定**：三种题型都是确定性判定，不需要运行时。判分逻辑是纯函数，单测覆盖。
4. **进度只存本地**：不建表、不加端点；`localStorage` 已有先例。跨设备是独立小项（待确认问题 1）。
5. **解释文本复用同一渲染器**：通过 context 注入 `renderText`，避免 `renderers` 与 `markdown.tsx` 循环依赖；嵌套渲染显式关闭自定义块，防止递归。
6. **Shiki 只作用于普通围栏代码块**：走读块逐行渲染（行号、focus 高亮），不做 token 级着色——这是有意的简化，升级路径在 `ponytail:` 注释里（见设计）。
7. **AI 只预填**：避免用户没点就扣积分。

## 范围

**v1 包含**：五种块；地图/走读双向联动；预测式步骤；状态快照；三种练习；小测得分；本地进度、重置与「继续学习」；AI 预填（每步、状态面板、篇末）；Shiki + 语言白名单 + 懒加载；首页入口；管理端预览一致；作者指南与一篇样板。

**v1 不包含**：自动索引、代码执行、动画引擎、Mermaid、react-flow/d3、服务端进度、用户投稿/评论/收藏、跨设备同步。

## 备选方案

### A. 只做走读 + 小测（首版方案）

**最强理由**：两天能上线，风险最低。
**为什么不选**：用户明确说"太简单"；调研也显示同类产品没有只做逐步高亮的。缺地图联动、状态可视化、预测、练习、进度，教学闭环不成立。

### B. 引入 react-flow + Mermaid + 执行器

**最强理由**：能力最全，地图可拖拽缩放、图可自动布局、练习可真跑。
**为什么不选**：三个依赖带来体积、样式与安全面；执行器是独立工作项（WORK-015）。v1 先用零依赖方案验证内容与交互，再按需加。

### C. 独立"课程"模块（专用表 + 编排器）

**最强理由**：路径、进度、统计都能结构化。
**为什么不选**：会复制知识库的分类、正文、图片、关系、自评与 AI 检索；v1 的收益不需要新建内容系统。

### D. 自动索引任意 repo（DeepWiki 式）

**最强理由**：内容成本接近零。
**为什么不选**：幻觉与许可证风险落在我们身上；运行时抓取引入外部依赖。用户已明确"都是我们内置的"。

## 仓库影响

| 位置 | 影响 |
| --- | --- |
| `frontend/packages/ui/src/markdown/renderers/schema.ts`（新增） | 五类块的类型、校验、上限、permalink |
| `.../renderers/context.tsx`（新增） | 页级上下文（activeStep、ask、progress、renderText） |
| `.../renderers/blocks.tsx`（新增） | 块外壳、错误降级、问 AI 按钮 |
| `.../renderers/{learning-path,code-map,code-tour,code-challenge,quiz}.tsx`（新增） | 五个块 |
| `.../renderers/index.ts`（新增） | 注册表与错误边界包装 |
| `packages/ui/src/markdown/markdown.tsx` | 文档模式合并块；注入上下文；接 Shiki |
| `packages/ui/package.json` | 新增 `@streamdown/code`（钉版本） |
| `packages/ui/src/styles/globals.css` | `@source` 指向 `@streamdown/code/dist` |
| `packages/knowledge/src/markdown-body.tsx` | 透传 `entryId/title/onAskAssistant/onOpenEntry` |
| `packages/knowledge/src/source-reading-progress.ts`（新增） | localStorage 读写、最近学习 |
| `packages/knowledge/src/index.ts` | 导出进度读取（首页用） |
| `packages/assistant/src/context.tsx` | `ask/pendingPrompt/clearPendingPrompt` |
| `packages/assistant/src/{assistant-shell,conversation-panel}.tsx` | 消费 pendingPrompt，只填不发 |
| `apps/app/src/pages/KnowledgeEntryPage.tsx` | 注入 ask 与跳转 |
| `apps/app/src/pages/HomePage.tsx` | 快捷入口 + 继续学习 |
| 测试 | ui 解析/交互单测、knowledge 进度与透传、assistant 预填、两端 e2e |

## 交付拆分

| 切片 | 内容 | 可独立验证 |
| --- | --- | --- |
| 1 | schema + 五个纯解析函数 + 单测 | 合法/非法输入各自得到值或错误 |
| 2 | 上下文 + 五个块视图 + 错误边界 | ui-kit 里可走、可点、可答 |
| 3 | 进度存储 + 继续学习 | 刷新后恢复；首页显示最近 |
| 4 | 接入 Markdown/预览 | 学习者页与管理端预览一致 |
| 5 | Shiki + 白名单 + 体积记录 | 着色生效、主包不变 |
| 6 | AI 预填桥 + 首页入口 | 只填不发；入口可达 |
| 7 | 样板条目 + e2e + 报告 | 从首页走完一站 |

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| 许可证 | MIT/Apache-2.0/BSD 优先；页脚强制显示；GPL/AGPL 只链接 |
| 行号漂移 | 锚定固定 commit；permalink 指向该 commit |
| 体积 | 只加 `@streamdown/code`；地图与状态面板零依赖；构建后对比主包 |
| 解析/渲染安全 | 代码按文本渲染；解释走净化管线且关闭嵌套块；外链白名单 |
| 坏内容炸页 | 每块 try/catch + 错误边界；schema 上限；降级为原始文本 |
| 进度丢失 | localStorage 读写全部 try/catch；解析失败视为空进度，不报错 |
| 内容成本 | 先做一篇样板 + 作者指南；AI 只起草，行号人工核 |

## 验收映射

| 需求 | 验收 |
| --- | --- |
| REQ-001/018 | AC-016、AC-019 |
| REQ-002/011/012 | AC-001、AC-008、AC-009 |
| REQ-003/004 | AC-003 |
| REQ-005 | AC-002 |
| REQ-006 | AC-004 |
| REQ-007 | AC-005 |
| REQ-009 | AC-006 |
| REQ-010 | AC-007 |
| REQ-013/014 | AC-010、AC-011 |
| REQ-015 | AC-014 |
| REQ-016/017 | AC-012、AC-015 |
| REQ-019/020 | AC-017、AC-019 |
| 内容口径 | AC-020 |

## 待决定事项

1. 进度是否上服务端（建议 v1 本地，v1.5 再加表）。
2. Mermaid 是否进 v1（建议不进）。
3. 样板选 React 或 Spring Boot（不影响实现）。
