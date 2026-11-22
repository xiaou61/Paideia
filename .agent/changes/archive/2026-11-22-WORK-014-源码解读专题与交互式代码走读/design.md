---
artifact: design
work_id: WORK-014
work: 源码解读专题与交互式代码走读
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-009, WORK-010, WORK-011]
created: 2026-10-17
updated: 2026-10-17
approved_by: xiaou61
approved_at: 2026-10-17 23:40:48 +0800
approver_role: CTO
---

# 设计 · 源码解读专题与交互式代码走读

## 系统上下文

```text
管理端编辑 ── MarkdownField ── MarkdownBody ─┐
                                              ├─ Markdown（唯一渲染器）
学习者条目页 ──────────────────── MarkdownBody ─┘        │
                                                        ├─ plugins.code      = Shiki
                                                        └─ plugins.renderers = 五种块
                                                                  │
                                          SourceReadingProvider（页级 context）
                                          ├─ activeStep（地图 ↔ 走读）
                                          ├─ onAskAssistant（AI 预填）
                                          ├─ onOpenEntry（路径跳转）
                                          ├─ progress（localStorage 端口）
                                          └─ renderText（解释文本复用渲染器）
```

阅读路径不访问 GitHub：片段、出处、许可证都在正文 JSON 里。

## 组件与职责

| 模块 | 职责 | 不做什么 |
| --- | --- | --- |
| `renderers/schema.ts` | 五类块的类型、`parse*` 纯函数、上限、`permalinkOf` | 不碰 DOM、不读存储 |
| `renderers/context.tsx` | context 定义、`useSourceReading`、错误类型 | 不认识具体块 |
| `renderers/blocks.tsx` | `BlockFrame`、`BlockFallback`、`AskButton`、`Pill` | 不含业务判定 |
| `renderers/learning-path.tsx` | 站点列表、状态来自 progress | 不负责路由；跳转走 `onOpenEntry` |
| `renderers/code-map.tsx` | 分层布局、SVG 边、HTML 节点按钮 | 不做自动布局 |
| `renderers/code-tour.tsx` | 步骤、行高亮、预测、状态面板、问 AI | 不做 token 着色（见简化说明） |
| `renderers/code-challenge.tsx` | 填空 / 排序 / 找 bug 与判定 | 不执行代码 |
| `renderers/quiz.tsx` | 多题判分 | 不上传成绩 |
| `renderers/index.ts` | `documentRenderers`、`guard()` 错误边界包装 | 不在流式模式启用 |
| `packages/knowledge/src/source-reading-progress.ts` | localStorage 读写、最近学习、重置 | 不依赖 React |
| `packages/knowledge/src/markdown-body.tsx` | 组装 `DocumentContext`，透传 app 回回调 | 不依赖 assistant 包 |
| `packages/assistant/src/context.tsx` | `ask/pendingPrompt/clear` | 不发送 |
| `assistant-shell` / `conversation-panel` | 打开面板、切到对话、填入输入框 | 不调 `sendMessage` |

## 请求或事件流程

### 地图 ↔ 走读

1. 走读块在渲染时用 `activeStep[tourId] ?? 0` 作为当前步。
2. 点「下一步」→ `setActiveStep(tourId, i+1)` → 同页的地图读到变化，高亮 `node.step === i+1` 的节点。
3. 点地图节点 → `setActiveStep(node.tour, node.step)` → `document.getElementById('tour-' + node.tour)?.scrollIntoView({block:'start'})`。
4. 页级 context 只在 `Markdown` 内；离开条目即销毁，不串页。

### 进度

1. `MarkdownBody` 收到 `entryId` 时创建 `progress` 端口（来自 `source-reading-progress.ts`），注入 context。
2. 走读切步 → `progress.update({ tours: { [id]: { step, total } } })`；`done = step === total - 1`。
3. 练习/小测作答 → `progress.update({ challenges|quizzes: { [id]: { score, total } } })`。
4. `read()` 在挂载时读一次作为初始状态；`reset()` 清该条目。
5. `entryId` 缺失（管理端预览、笔记预览）→ 不持久化，块仍可用。

### 问 AI

1. 点「问 AI」→ 拼 `结合《标题》第 N 步（file:line），……`。
2. `ask(text)` 写 `pendingPrompt = { id, text }`。
3. `AssistantShell` effect：打开面板、`setTab('chat')`。
4. `ConversationPanel` effect：`setInput(text)` + `clearPendingPrompt(id)`；**不调用 send**。

## 接口与数据

### 公共 `DocumentContext`

```ts
interface DocumentContext {
  entryId?: number
  entryTitle?: string
  onAskAssistant?: (prompt: string) => void
  onOpenEntry?: (entryId: number) => void
  progress?: {
    read(): EntryProgress | null
    update(patch: ProgressPatch): void
    reset(): void
  }
}
```

### `code-tour`

```json
{
  "id": "react-workloop",
  "repo": "facebook/react",
  "commit": "d75b0697",
  "license": "MIT",
  "file": "packages/react-reconciler/src/ReactFiberWorkLoop.js",
  "steps": [
    {
      "title": "进入 renderRootConcurrent",
      "line": 1234,
      "code": "prepareFreshStack(root, lanes);\n...",
      "language": "javascript",
      "focus": [1, 4],
      "explain": "先重置工作栈，避免复用上一次的残留状态。",
      "predict": {
        "question": "为什么不直接进入 workLoop？",
        "options": ["上一次的工作可能未完成", "为了预分配内存", "为了触发 GC"],
        "answer": 0,
        "reveal": "workInProgress 可能指向半成品树，必须先重置。"
      },
      "state": {
        "vars": [{ "name": "lanes", "value": "SyncLane" }],
        "stack": ["renderRootConcurrent", "performConcurrentWorkOnRoot"],
        "heap": [{ "id": "root", "label": "FiberRoot", "fields": { "tag": "3" } }],
        "output": []
      }
    }
  ]
}
```

校验：`repo ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`；`commit ^[0-9a-f]{7,40}$`；`file` 不以 `/` 开头、不含 `..`；`steps` 1–20；`line ≥ 1`；`code` ≤ 200 行 / 20000 字符；`focus` 1-based 且落在代码行数内；`explain` ≤ 2000；`predict.options` 2–4、`answer` 合法；`state.vars` ≤ 30、`stack` ≤ 30、`heap` ≤ 20。

`permalink = https://github.com/{repo}/blob/{commit}/{file}#L{line}-L{line+lines-1}`。

### `code-map`

```json
{
  "id": "react-render-map",
  "title": "一次更新的路径",
  "nodes": [
    { "id": "schedule", "label": "scheduleUpdate", "layer": 0, "order": 0, "file": "ReactFiberWorkLoop.js", "line": 500, "tour": "react-workloop", "step": 0 },
    { "id": "render", "label": "renderRootConcurrent", "layer": 1, "order": 0, "tour": "react-workloop", "step": 1 }
  ],
  "edges": [{ "from": "schedule", "to": "render", "label": "调度" }]
}
```

布局：`x = 24 + layer * 220`、`y = 24 + order * 96`，节点 176×64；容器高 = `maxY + 88`；边用 SVG 贝塞尔/直线 + 箭头。校验：节点 1–24、边 ≤ 48、边两端必须存在、`layer/order` 0–12、`step` 整数 ≥ 0。

### `learning-path`

```json
{
  "title": "读懂 React 的更新流程",
  "steps": [
    { "entryId": 12, "title": "从一个 setState 说起", "summary": "先建立全局图景", "minutes": 12 },
    { "entryId": 13, "title": "调度：Lane 与优先级", "minutes": 20 }
  ]
}
```

校验：`steps` 1–20；`entryId` 正整数或省略（省略时不可点击）；`minutes` 1–600。

### `code-challenge`

```json
{ "id": "fill-1", "kind": "fill", "title": "补上重置逻辑", "language": "javascript",
  "template": "function prepare(root) {\n  {{0}}(root);\n}", "options": [["prepareFreshStack", "createRoot", "flushSync"]], "answers": [0], "explain": "..." }

{ "id": "order-1", "kind": "order", "title": "把顺序排对", "lines": ["c", "a", "b"], "answer": [1, 2, 0], "explain": "..." }

{ "id": "bug-1", "kind": "bug", "title": "找 bug", "language": "java", "code": "...", "bugLine": 4, "explain": "..." }
```

判定：`fill` 每空所选索引等于 `answers[i]`；`order` 当前序列等于 `answer`；`bug` 所选行等于 `bugLine`。`explain` 无论对错都显示。

### `quiz`

`questions` 1–10，`options` 2–6，`answer` 0-based 合法，`explain` ≤ 1000；显示「答对 N / 总数」。

### 进度

```ts
type EntryProgress = {
  updatedAt: string
  title?: string
  tours: Record<string, { step: number; total: number; done: boolean }>
  challenges: Record<string, { score: number; total: number }>
  quizzes: Record<string, { score: number; total: number }>
}
```

存储键 `paideia:source-reading:v1`；整库一个 JSON，读失败视为空；写入失败静默（隐私模式下不报错）。`recentReading(limit)` 按 `updatedAt` 倒序返回 `{ entryId, title, visited }`。

## 持久化与迁移

- **无数据库迁移**。内容仍是 `knowledge_entries.body`。
- 本地进度是新增键，不影响既有 `paideia:*` 键。
- 旧条目没有新围栏块 → 渲染路径不变。

## 失败处理与恢复

| 失败 | 行为 |
| --- | --- |
| JSON 解析失败 / 字段非法 / 越界 | 该块显示"数据格式有误：<第一个错误>" + 原始文本；其余块正常 |
| 组件渲染抛错 | `guard()` 错误边界捕获，降级同一条提示 |
| `localStorage` 不可用 / 内容损坏 | 视为空进度；写入 try/catch 静默 |
| 未知语言 | 纯文本显示 |
| Shiki 加载失败 | 普通代码块无高亮（与接入前一致） |
| 没有 `onAskAssistant` | 不渲染「问 AI」 |
| 没有 `onOpenEntry` | 路径站不可点击（管理端预览） |
| 地图引用了不存在的 tour/step | 点击无跳转；不报错 |

**不重试、不缓存错误**：内容错误由作者在预览里修。

## 安全与权限

- 作者权限仍由 `/api/v1/knowledge/admin/**` 的 `hasRole('ADMIN')` 保护；本项不加端点。
- 代码与模板按文本节点渲染，不 `dangerouslySetInnerHTML`。
- 解释文本经 `renderText`（同一净化管线）；嵌套渲染关闭自定义块，避免 `code-tour` 递归。
- permalink 只由校验字段拼出，不接受任意 URL。
- 练习不执行代码；进度不上传。
- 许可证由内容审核保证；渲染层只展示 `license` 文本。

## 可观测性

- 不新增后端指标。错误以块内提示呈现，作者在预览就能看到。
- 测试钩子：`data-testid` = `code-tour`、`code-tour-step`、`code-tour-next`、`code-tour-predict`、`code-tour-state`、`code-tour-ask`、`code-map`、`code-map-node-{id}`、`learning-path`、`learning-path-step-{i}`、`challenge`、`challenge-check`、`quiz`、`quiz-option-{i}`。
- 构建后记录 app 主 chunk 与 markdown chunk 体积，写进 `testing/report.md`。

## 实施顺序

1. schema + 解析单测（纯函数）。
2. context + 块外壳 + 错误边界。
3. 五个块（先走读与小测，再地图/路径/练习）。
4. 进度存储与继续学习。
5. 接入 Markdown / MarkdownBody / 预览。
6. Shiki + 白名单 + 体积记录。
7. AI 预填桥 + 首页入口。
8. 样板条目 + e2e + 作者指南 + 报告。

## 测试策略

| 层 | 用例 |
| --- | --- |
| `@paideia/ui` | 五个 `parse*` 的合法/非法；走读切步与键盘；预测未答不显示揭晓；状态面板随步变化；地图点节点改 activeStep；块间不串；fallback；练习三种题的对错；小测得分；流式模式不启用块 |
| `@paideia/knowledge` | 进度读写/损坏容错/重置/最近学习；`MarkdownBody` 透传回调 |
| `@paideia/assistant` | `ask` 打开面板并填输入；**断言不调用 send**；清空 pending 后输入保留 |
| app e2e | 管理 API 建条目 → 首页入口 → 走读 → 预测 → 练习 → 小测 → 问 AI（未发送）→ 无 github.com 请求 → 刷新恢复进度 |
| admin e2e | 预览里五种块结构存在 |
| 构建 | 主包体积不增加；Shiki 在 markdown chunk |

e2e 自建内容，不依赖种子数据。

## 需求追踪

| 设计点 | 需求 / 验收 |
| --- | --- |
| 分类 + 五种块 | REQ-001 / AC-016、AC-019 |
| learning-path + 进度 | REQ-002、REQ-011、REQ-012 / AC-001、AC-008、AC-009 |
| code-map + context | REQ-003、REQ-004 / AC-003 |
| code-tour | REQ-005 / AC-002 |
| predict | REQ-006 / AC-004 |
| state | REQ-007 / AC-005 |
| permalink | REQ-008 / AC-013 |
| code-challenge | REQ-009 / AC-006 |
| quiz | REQ-010 / AC-007 |
| AI 预填 | REQ-013、REQ-014 / AC-010、AC-011 |
| Shiki | REQ-015 / AC-014 |
| 降级 + 预览一致 | REQ-016、REQ-017 / AC-012、AC-015 |
| 入口 + 零外部请求 | REQ-018、REQ-019 / AC-016、AC-017 |
| 既有行为 | REQ-020 / AC-019 |
| 内容口径 | AC-020 |

## 简化与已知天花板

- **走读块不做 token 级着色**：逐行渲染 + 行号 + focus 高亮，语法着色只作用于普通围栏代码块。升级路径：用 `@streamdown/code` 的 `highlight` API 按行切 token（`ponytail:` 注释）。
- **地图不做自动布局/缩放/拖拽**：作者给层与序。节点超过 24 个时先拆成两张图。
- **进度不跨设备**：v1.5 再评估是否加表。
- **解释里的链接**沿用渲染器默认外链处理，不做额外白名单。
