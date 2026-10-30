---
artifact: design
work_id: WORK-057
work: 源码精读（第三方源码 + MyBatis 专题）
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-014, WORK-040, WORK-046]
created: 2026-10-30
updated: 2026-10-30
approved_by: xiaou61
approved_at: 2026-10-30 14:50:28 +0800
approver_role: CTO
---

# 设计 · 源码精读（第三方源码 + MyBatis 专题）

## 1. 数据契约：`source-walk` 围栏块

只在文档模式注册，与其它块同一张表（`documentRenderers`）。内容示例：

````markdown
```source-walk
{
  "id": "mapper-proxy",
  "title": "接口怎么变成对象",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "<40位 sha>",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    { "path": "org/apache/ibatis/binding/MapperProxy.java", "label": "MapperProxy" }
  ],
  "controls": [
    { "id": "cache", "type": "toggle", "label": "开启一级缓存", "default": true },
    { "id": "method", "type": "select", "label": "调哪个方法", "default": "byId",
      "options": [{ "value": "byId", "label": "查 id" }, { "value": "byName", "label": "查 name" }] }
  ],
  "steps": [
    {
      "title": "invoke 先判断是不是 Object 的方法",
      "file": "org/apache/ibatis/binding/MapperProxy.java",
      "line": 62,
      "lines": 12,
      "explain": "……",
      "when": { "method": "byId" },
      "state": {
        "stack": ["MapperProxy.invoke", "MapperMethod.execute"],
        "vars": [["method", "selectById"], ["args", "[1]"]],
        "tables": [["executor", "CachingExecutor"]]
      },
      "refs": [
        { "label": "MapperMethod.execute", "file": "org/apache/ibatis/binding/MapperMethod.java", "line": 40 }
      ],
      "predict": { "question": "……", "options": ["A", "B"], "answer": 1, "reveal": "……" }
    }
  ]
}
```
````

字段与上限（与既有 schema 同一套校验风格，失败返回 `{ ok:false, error }`）：

| 字段 | 规则 |
| --- | --- |
| `id` | 必填，≤64，页面内唯一 |
| `repo` / `ref` / `commit` / `license` | 必填；`commit` 为 7–40 位十六进制；`license` ≤40 |
| `base` | 必填，相对路径，`^[A-Za-z0-9._/-]+$`，不以 `/` 开头、不含 `..` |
| `files[]` | 1–12 项；`path` 相对 `base`，同上规则且以 `.java`/`.xml` 结尾；`label` ≤40 |
| `controls[]` | 0–4 项；`id` ≤32 且唯一；`toggle.default` 布尔；`select.default` 必须属于 options；options 2–4 |
| `steps[]` | 1–60 项；`line` ≥1，`lines` 1–200；`file` 必须在 `files` 里 |
| `when` | 可选，键必须是已声明的 control id，值类型与该 control 一致 |
| `state` | 可选；`stack` ≤20、`vars`/`tables` ≤20 组；每组的键值各 ≤120 字符 |
| `refs[]` | 0–8 项；`file` 必须在 `files` 里，`line` ≥1 |
| `predict` | 与 `code-tour` 同形：2–4 个选项、`answer` 下标合法、`reveal` ≤1000 |

**步骤过滤是纯函数**：

```ts
export function visibleSteps(walk: SourceWalk, controlValues: Record<string, boolean | string>): TourStep[]
```

`when` 里每个键都相等才保留；`when` 为空恒保留。控件默认值来自 `controls[].default`。
控件变化时当前步重置为 0（不做"尽量停在原步"的猜测——分支换了下标含义就变了）。

## 2. 组件

新增 `frontend/packages/ui/src/markdown/renderers/source-walk.tsx`：

```
SourceWalk
├── Controls        开关/单选；onChange 写本地 state
├── CodePane        文件页签 + 行号 + 当前步高亮 + 「GitHub 原文」
├── StepList        步骤列表（可折叠），当前步带 aria-current
├── StepBody        explain（纯文本/段落）+ refs 按钮 + predict
└── StatePanel      调用栈 / 关键变量 / 注册表 三组
```

- **取文件**：`fetch(new URL(`${base}/${file.path}`, document.baseURI))`，一次并发取全部 `files`，
  `useRef` 缓存 `path → text`。同源校验：解析后的 `origin` 必须等于 `location.origin`，
  否则不请求并显示块内错误。
  `shortcut: 只接受 .java/.xml 与相对路径；要做多语言或整仓浏览时升级。`
- **高亮**：目标区间 = `[line, line+lines-1]`。切换步骤时把该行 `scrollIntoView({block:'center'})`，
  只滚动代码面板（`overflow-auto` 的容器），不滚整页。
- **加载态**：文件未到时显示「加载源码…」骨架；失败显示错误 + 重试按钮，步骤与讲解仍可读。
- **键盘**：代码区容器接 `onKeyDown` 处理 ←/→；`tabIndex={-1}`，与 `code-tour` 一致。
- **进度**：复用 `ProgressPort.tours[walk.id] = { step, total, done }`。不新增端口字段，
  也不改服务端契约（服务端只校验块分数上限，不认 tours 的语义）。
- **预测**：与 `code-tour` 相同——未作答不渲染 explain 的下半段？**不同**：本块的 `explain`
  是主体，预测只覆盖它后面的一小段"揭晓"。即：始终显示 explain，答完预测后显示 `reveal`。
  读者不会因为答不出而读不到讲解（与 UD-005 的"动手"不矛盾：预测是插入的判断点，不是门禁）。

## 3. 页面与路由

| 路由 | 页面 | 数据 |
| --- | --- | --- |
| `/source` | `SourceProjectsPage` | 分类树根 `source` 的子分类 + 条目数；进度读 `/learning/progress` |
| `/source/:project` | `SourceProjectPage` | 根下子分类按 slug 定位；条目按 `sortOrder`；顶部元信息条 |
| `/source/:project/:entryId` | `SourceChapterPage` | 条目详情 + `MarkdownBody` + 上一章/下一章 |

- 三条路由都包 `RequireAuth`（与全部学习页面一致）。
- 导航：`NAV` 的 `knowledge` 组加 `{ key: 'source', group: 'knowledge', to: '/source', label: '源码精读', testId: 'learner-nav-source' }`。
  不加进 `preferredNav`（身份排序只管老入口，`books` 也没加）。
- 项目元信息条（`ProjectMeta`）：`repo @ ref`、commit 短号、许可证、章节数、「查看仓库」。
  数据来自**总览条目的正文**？不——来自 `source-walk` 块里的 `repo/ref/commit/license`。
  为了避免每页重复解析 JSON：项目页从**总览条目**（`sortOrder === 0`）里提取第一个 `source-walk` 的元信息；
  抽不到就只显示项目名（不猜）。
- 章节页复用 `MarkdownBody`：`entryId` 传进度端口，`onAskAssistant` 接助手；不复制 `KnowledgeEntryPage`
  的目录与评论逻辑（阅读页不需要二级目录）。

## 4. 静态源码

- 快照位置：`frontend/apps/app/public/source/mybatis-3.5.19/org/apache/ibatis/...`。
- Vite `public/` 原样拷进 `dist/`，随 `release.ps1` 的 `web` 包发布；`base: './'` 下相对路径可用。
- `SecurityConfiguration` 的 GET 白名单加 `"/source/**"`；只放 GET。
- 许可：`public/source/mybatis-3.5.19/LICENSE` 与 `NOTICE`（若上游有）一并拷贝；页面显示来源。

## 5. 内容流水线

- 目录：`content/source/mybatis/00-总览.md` … `24-*.md`。
- frontmatter：`title / category: mybatis-source / order / minutes / level / status`；
  总览额外 `line_parent: source`、`line_name: MyBatis 源码精读`、`line_sort`。
- 导入：`node scripts/import-course.mjs --dir content/source/mybatis --no-track`（幂等）。
- 校验：新增 `scripts/check-source-walks.mjs`
  1. 扫描指定目录的 markdown；提取 ` ```source-walk ` 块；
  2. JSON 解析 → 复用同一份规则（字段/上限/行号落在文件行数内/`file` 在 `files` 中/`answer` 下标）；
  3. 逐块打印 `文件:行 · 块 id · 步骤数`，任何一条不合格退出码非 0。
  它是内容作者的守卫，不进运行时。

## 6. 要改的文件清单

**新增**
- `frontend/packages/ui/src/markdown/renderers/source-walk.tsx`
- `frontend/packages/ui/src/markdown/renderers/source-walk.test.tsx`（纯函数与渲染的最小用例）
- `frontend/apps/app/src/pages/SourceProjectsPage.tsx`
- `frontend/apps/app/src/pages/SourceProjectPage.tsx`
- `frontend/apps/app/src/pages/SourceChapterPage.tsx`
- `content/source/mybatis/*.md`（24 篇）
- `frontend/apps/app/public/source/mybatis-3.5.19/**`（35–45 个文件 + LICENSE）
- `scripts/check-source-walks.mjs`
- `frontend/e2e/source.spec.ts`

**修改**
- `renderers/schema.ts`：`SourceWalk` 类型 + `parseSourceWalk` + `visibleSteps`
- `renderers/index.ts`：注册 `source-walk`
- `ui/src/index.ts`：导出新类型
- `app/src/router.tsx`、`components/LearnerHeader.tsx`
- `paideia-security/.../SecurityConfiguration.java`：`GET /source/**`
- `HomePage.tsx`、`LearnPage.tsx`：删旧入口与图标映射
- `scripts/import-course.mjs`：无需改（`--dir` + `--no-track` 已够）
- 删除：`content/reading/source/`、`content/reading/code-reading/`

## 7. 测试计划

- 单测（`@paideia/ui`）：`parseSourceWalk` 合法/缺字段/越界行号/绝对路径/`..`/未知 control；
  `visibleSteps` 的开关组合与默认值；`parseSourceWalk` 对 24 篇真实内容全通过（内容回归）。
- 组件测：渲染一个两文件、两步骤的块，点击下一步断言高亮行与状态面板变化；点 refs 断言文件切换。
- e2e（`frontend/e2e/source.spec.ts`）：进 `/source` → 项目页 → 章节页 → 切步骤 → 答预测 → 刷新后进度仍在；
  375px 无横向溢出。
- 内容守卫：`node scripts/check-source-walks.mjs content/source/mybatis` 退出码 0。
- 部署后：线上抽查一章 + `curl -o /dev/null -w '%{http_code}' http://host:8081/source/mybatis-3.5.19/<file>` = 200。

## 8. 已知限制

- v1 的轨迹是**预演**：步骤、状态、分支由作者按真实运行结果写，不是实时执行。
- 助手上下文不含整份源码（见 requirements IC-007）。
- 一个项目只支持一个版本；多版本要改路径与页面的元信息模型。
