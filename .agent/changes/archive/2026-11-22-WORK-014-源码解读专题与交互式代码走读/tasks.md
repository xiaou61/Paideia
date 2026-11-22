---
artifact: tasks
work_id: WORK-014
work: 源码解读专题与交互式代码走读
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-009, WORK-010, WORK-011]
created: 2026-10-17
updated: 2026-10-17
approved_by: xiaou61
approved_at: 2026-10-17 23:40:49 +0800
approver_role: CTO
---

# 任务 · 源码解读专题与交互式代码走读

**排序原则**：先纯函数（schema 与判定），再视图与联动，再持久化与接线，最后高亮、AI、内容与端到端。每个切片都能单独演示。

**全局约束**：不新增后端端点/表/迁移；v1 只加 `@streamdown/code`；不装 react-flow/d3/mermaid；AI 只预填；练习不执行代码；代码片段锚定固定 commit；每笔提交只含本次范围。

## 任务

### TASK-001 | done | 五类块的 schema 与纯解析函数

- 对应：REQ-002/003/005/006/007/009/010；AC-001、AC-002、AC-003、AC-004、AC-005、AC-006、AC-007
- 依赖：无
- 修改：`frontend/packages/ui/src/markdown/renderers/schema.ts`（新增）、`.../schema.test.ts`（新增）
- 步骤：
  1. 定义 `LearningPath` / `CodeMap` / `CodeTour` / `CodeChallenge` / `Quiz` 类型与上限常量。
  2. 写 `parseLearningPath` / `parseCodeMap` / `parseCodeTour` / `parseCodeChallenge` / `parseQuiz`，返回 `{ ok: true, value } | { ok: false, error }`，错误信息指出第一个不满足的字段。
  3. 写 `permalinkOf(tour, step)` 与行数计算。
  4. 判定纯函数：`judgeFill` / `judgeOrder` / `judgeBug` / `scoreQuiz`。
- 验证：`pnpm --filter @paideia/ui test`（新增 schema 用例全绿）。

### TASK-002 | done | 页级上下文、块外壳与错误边界

- 对应：REQ-004、REQ-016；AC-003、AC-012
- 依赖：`TASK-001`
- 修改：`.../renderers/context.tsx`、`.../renderers/blocks.tsx`、`.../renderers/index.ts`（均新增）
- 步骤：
  1. `SourceReadingProvider` 管理 `activeStep` 映射、`ask`、`progress`、`renderText`、`onOpenEntry`。
  2. `BlockFrame`（标题 + testid）、`BlockFallback`（错误 + 原始文本）、`AskButton`、`Pill`。
  3. `guard(Component)`：类组件错误边界，单块异常只降级自身；包装函数必须是模块级常量（稳定引用）。
  4. `documentRenderers` 注册五种语言别名。
- 验证：`pnpm --filter @paideia/ui test`；ui-kit 里故意放坏 JSON 只降级该块。

### TASK-003 | done | 走读块（步骤、预测、状态面板）

- 对应：REQ-005/006/007/008、REQ-013；AC-002、AC-004、AC-005、AC-013
- 依赖：`TASK-002`
- 修改：`.../renderers/code-tour.tsx`（新增）、`.../code-tour.test.tsx`（新增）
- 步骤：
  1. 代码区逐行渲染：`startLine + index`、`focus` 高亮、横向滚动；`ponytail:` 注释说明不做 token 着色。
  2. 解释区用 `renderText`（关闭嵌套块）；步骤点、上一步/下一步、键盘左右、`aria-current`。
  3. `predict`：未作答不渲染解释；作答后给对错 + `reveal`，提供「直接看解释」。
  4. `state`：变量、调用栈、堆对象、输出四块；无数据时不占位。
  5. 页脚 `repo@commit · path:start-end · license` + permalink；「问 AI」按钮仅在 `onAskAssistant` 存在时渲染。
  6. 切步写 `progress.update({tours})`；`done = step === total-1`。
- 验证：`pnpm --filter @paideia/ui test`；ui-kit 肉眼确认。

### TASK-004 | done | 架构地图与双向联动

- 对应：REQ-003/004；AC-003、AC-018
- 依赖：`TASK-002`、`TASK-003`
- 修改：`.../renderers/code-map.tsx`（新增）、`.../code-map.test.tsx`（新增）
- 步骤：
  1. 按 `layer/order` 算绝对坐标；容器高度自适应；背后 SVG 画边 + 箭头。
  2. 节点是 `<button>`：可聚焦、可回车；悬停/聚焦高亮相连边。
  3. 点击节点 → `setActiveStep(tour, step)` + `scrollIntoView`；当前 `activeStep` 对应节点加高亮。
  4. 窄屏容器横向滚动，节点不换行错位。
- 验证：`pnpm --filter @paideia/ui test`（两个方向各一条断言）。

### TASK-005 | done | 学习路径块

- 对应：REQ-002、REQ-012；AC-001、AC-009
- 依赖：`TASK-002`
- 修改：`.../renderers/learning-path.tsx`（新增）、`.../learning-path.test.tsx`（新增）
- 步骤：
  1. 渲染有序站点：序号、标题、摘要、预计时长。
  2. 状态取 `progress.read()`：有 tour 进度且 done → 已完成；有任意记录 → 进行中；否则未开始。
  3. 点击走 `onOpenEntry`；没有回调时按钮禁用。
- 验证：`pnpm --filter @paideia/ui test`。

### TASK-006 | done | 动手练习块

- 对应：REQ-009；AC-006
- 依赖：`TASK-001`、`TASK-002`
- 修改：`.../renderers/code-challenge.tsx`（新增）、`.../code-challenge.test.tsx`（新增）
- 步骤：
  1. `fill`：模板里的 `{{i}}` 渲染成 `<select>`；提交后逐空判对错并给解析。
  2. `order`：行列表 + ↑/↓ 按钮（键盘可用，不做拖拽）；提交后比对。
  3. `bug`：每行一个可点按钮；选中后提交比对。
  4. 结果写 `progress.update({challenges})`；「重做」重置本题。
- 验证：`pnpm --filter @paideia/ui test`（三种题型各对/错两条）。

### TASK-007 | done | 小测块

- 对应：REQ-010；AC-007
- 依赖：`TASK-002`
- 修改：`.../renderers/quiz.tsx`（新增）、`.../quiz.test.tsx`（新增）
- 步骤：
  1. 逐题渲染；点选即判对错 + 解析；对错不只靠颜色。
  2. 块末显示「答对 N / 总数」；成绩写 `progress.update({quizzes})`。
- 验证：`pnpm --filter @paideia/ui test`。

### TASK-008 | done | 本地进度与最近学习

- 对应：REQ-011、REQ-012；AC-008、AC-009
- 依赖：`TASK-001`
- 修改：`frontend/packages/knowledge/src/source-reading-progress.ts`（新增）、`.../source-reading-progress.test.ts`（新增）、`.../index.ts`
- 步骤：
  1. 版本化键 `paideia:source-reading:v1`；`read/update/reset/recentReading(limit)`。
  2. 读写全部 try/catch；损坏 JSON 视为空。
  3. `recentReading` 按 `updatedAt` 倒序，返回 `{entryId,title,visited}`；title 缺省时用 `条目 #id`。
- 验证：`pnpm --filter @paideia/knowledge test`（含损坏 JSON 与 localStorage 抛错两条）。

### TASK-009 | done | 接入 Markdown 与预览一致

- 对应：REQ-001/016/017/020；AC-012、AC-015、AC-019
- 依赖：`TASK-003`、`TASK-004`、`TASK-005`、`TASK-006`、`TASK-007`、`TASK-008`
- 修改：`packages/ui/src/markdown/markdown.tsx`、`.../markdown.test.tsx`、`packages/knowledge/src/markdown-body.tsx`、`.../markdown-body.test.tsx`（新增）
- 步骤：
  1. `Markdown` 增加 `documentContext` 与内部 `documentBlocks`；文档模式合并 `documentRenderers`，流式模式不合并。
  2. `SourceReadingProvider` 包住 `Streamdown`；`renderText` 用嵌套 `Markdown`（`documentBlocks={false}`）。
  3. `MarkdownBody` 新增 `entryId/entryTitle/onAskAssistant/onOpenEntry`，组装 `DocumentContext` 并创建 progress 端口。
  4. 管理端预览不传这些，块可看、不可问、不写进度。
- 验证：`pnpm --filter @paideia/ui test`、`pnpm --filter @paideia/knowledge test`、两端 typecheck；管理端预览肉眼确认。

### TASK-010 | done | Shiki 高亮与体积记录

- 对应：REQ-015；AC-014
- 依赖：`TASK-009`
- 修改：`packages/ui/package.json`、`packages/ui/src/markdown/markdown.tsx`、`packages/ui/src/styles/globals.css`、`packages/ui/src/styles/source-coverage.test.ts`
- 步骤：
  1. `pnpm --filter @paideia/ui add @streamdown/code@<稳定版>` 钉版本。
  2. `plugins.code = code`；白名单语言常量；未知语言纯文本。
  3. `@source '../../node_modules/@streamdown/code/dist/*.js'` 并加入覆盖测试。
  4. 构建前后记录主 chunk 与 markdown chunk 体积。
- 验证：`pnpm --filter @paideia/ui test`；`pnpm --filter @paideia/app build` 记录体积。

### TASK-011 | done | 助手预填桥

- 对应：REQ-013、REQ-014；AC-010、AC-011
- 依赖：`TASK-003`
- 修改：`packages/assistant/src/context.tsx`、`.../assistant-shell.tsx`、`.../conversation-panel.tsx`、`.../conversation-panel.test.tsx`、`apps/app/src/pages/KnowledgeEntryPage.tsx`
- 步骤：
  1. 上下文增加 `pendingPrompt/ask/clearPendingPrompt`（`ask` 稳定引用）。
  2. shell 打开面板并切 chat；panel 填入 input 并清空 pending，**不发送**。
  3. 条目页把 `ask` 与 `onOpenEntry` 传给 `MarkdownBody`。
- 验证：`pnpm --filter @paideia/assistant test`；单测断言 `sendMessage` 零调用。

### TASK-012 | done | 首页入口与继续学习

- 对应：REQ-012、REQ-018；AC-009、AC-016
- 依赖：`TASK-008`、`TASK-011`
- 修改：`apps/app/src/pages/HomePage.tsx`
- 步骤：
  1. `QUICK_LINKS` 增加「源码解读」，`testId="home-entry-source-reading"`，指向知识库分类（或总览条目）。
  2. 新增「继续学习」区：`recentReading(3)`；有数据才显示；点击进条目。
- 验证：`pnpm --filter @paideia/app typecheck`；e2e 导航。

### TASK-013 | done | 样板条目、端到端与文档

- 对应：REQ-019、REQ-020、AC-017、AC-019、AC-020
- 依赖：`TASK-010`、`TASK-012`
- 修改：`apps/app/e2e/knowledge.spec.ts`、`apps/admin/e2e/knowledge.spec.ts`、`.agent/notes/source-reading-authoring.md`（新增）、`testing/plan.md`、`testing/report.md`
- 步骤：
  1. e2e 通过管理 API 自建分类与条目（五块齐全），断言走读、预测、练习、小测、问 AI 行为与进度恢复。
  2. `page.on('request')` 断言无 `github.com`。
  3. 管理端 e2e 断言预览五种块存在。
  4. 写作者指南：五种 JSON 模板、上限、许可证口径、permalink 规则、AI 起草后人工核行号。
  5. 报告记录实际命令与未覆盖项。
- 验证：`pnpm --filter @paideia/app test:e2e`、`pnpm --filter @paideia/admin test:e2e`；`validate` 通过。

## 完成条件

- 五种块在 ui-kit、学习者页、管理端预览三处一致可用；流式回答不启用。
- 地图与走读双向联动；预测、状态、练习、小测、得分与进度全部可用。
- 刷新后进度恢复；首页有入口与「继续学习」。
- 「问 AI」只预填不发送。
- Shiki 生效、主包体积不增加。
- 既有 e2e 全绿；新增 e2e 覆盖 AC-002/003/004/006/007/008/010/017。
- `validate` 通过；`testing/report.md` 据实记录未覆盖项。
