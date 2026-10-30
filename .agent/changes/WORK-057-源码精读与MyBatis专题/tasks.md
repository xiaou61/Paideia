---
artifact: tasks
work_id: WORK-057
work: 源码精读（第三方源码 + MyBatis 专题）
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-014, WORK-040, WORK-046]
created: 2026-10-30
updated: 2026-10-30
approved_by: xiaou61
approved_at: 2026-10-30 14:50:38 +0800
approver_role: CTO
---

# 任务 · 源码精读（第三方源码 + MyBatis 专题）

**排序原则**：先快照与契约（可单测），再组件与页面（可演示），再清理旧内容，最后按篇写内容与上线。
每个切片都能独立验证；内容分六批，每批四关。

**全局约束**：不新增后端表/端点/迁移；不改 `code-tour` 契约；源码只从同源相对路径读取；
每笔提交只含本次范围。

### TASK-001 | pending | 源码快照与许可

- 对应：REQ-004 / REQ-007；AC-003、AC-010
- 修改：`frontend/apps/app/public/source/mybatis-3.5.19/**`（新增）
- 步骤：
  1. `git clone --depth 1 --branch mybatis-3.5.19 https://github.com/mybatis/mybatis-3` 到 `F:/caches/tmp`。
  2. 抄出 24 关用到的 Java 文件（预估 35–45 个）与 `LICENSE`；保留目录结构。
  3. 在快照根写 `SOURCE.md`：仓库、tag、commit、许可证、拷贝日期。
- 验证：`find` 计数与 `du -sh` 记录；`git rev-parse HEAD` 记下 commit。

### TASK-002 | pending | `source-walk` 契约与纯函数

- 对应：REQ-003 / REQ-004 / REQ-005；AC-007
- 修改：`frontend/packages/ui/src/markdown/renderers/schema.ts`、`schema.test.ts`、`renderers/index.ts`、`ui/src/index.ts`
- 步骤：
  1. 加 `SourceWalk` / `WalkControl` / `WalkStep` / `WalkState` 类型与上限常量。
  2. `parseSourceWalk`：字段、上限、`file` 必须在 `files`、`when` 键必须是已声明控件。
  3. `visibleSteps(walk, values)`：`when` 全等才保留；默认值来自 `controls[].default`。
  4. `parsePredict` 复用（已导出即可，不导出就把逻辑保持在同一文件）。
- 验证：`pnpm --filter @paideia/ui test`；用例覆盖合法、缺字段、越界、绝对路径、`..`、未知 control。

### TASK-003 | pending | `source-walk` 组件

- 对应：REQ-003 / REQ-004 / REQ-005 / REQ-009；AC-002、AC-011、AC-012、AC-013
- 修改：`frontend/packages/ui/src/markdown/renderers/source-walk.tsx`（新增）、`blocks.tsx`（如需新 Pill）
- 步骤：
  1. 取文件（并发 + 缓存 + 同源校验 + 失败降级）。
  2. 代码面板：文件页签、行号、当前步高亮、滚动定位、GitHub permalink。
  3. 控件区：toggle / select；变化后重置到第 0 步。
  4. 步骤区：列表、上一步/下一步、键盘 ←/→、`aria-current`。
  5. 状态面板三组；refs 跳转与「返回」。
  6. 预测：答完显示 `reveal`；答错可重试；不影响 explain 可见。
  7. 进度写 `ProgressPort.tours[walk.id]`。
- 验证：组件测试（两文件、两步骤、一个 toggle）；`pnpm --filter @paideia/ui test`。

### TASK-004 | pending | 页面、路由与导航

- 对应：REQ-001 / REQ-002 / REQ-003；AC-001、AC-005、AC-006
- 修改：`frontend/apps/app/src/pages/Source{Projects,Project,Chapter}Page.tsx`、`router.tsx`、`components/LearnerHeader.tsx`
- 步骤：
  1. 三条路由（`RequireAuth`），slug 定位项目，entryId 定位章节。
  2. 项目页从总览条目的第一个 `source-walk` 抽元信息；抽不到只显示项目名。
  3. 章节页接 `MarkdownBody`（entryId / onAskAssistant）+ 上一章/下一章。
  4. 导航加 `source` 项（`knowledge` 组）。
- 验证：本地起前后端，手动走三条路由；`pnpm --filter app typecheck`。

### TASK-005 | pending | 静态放行

- 对应：REQ-008；AC-008
- 修改：`backend/paideia-security/.../SecurityConfiguration.java`
- 步骤：GET 白名单加 `"/source/**"`（只这一条）。
- 验证：`curl` 未登录 GET 200；POST 非 200；后端切片测试或现有安全测试补一条断言。

### TASK-006 | pending | 清理现有源码线

- 对应：REQ-011；AC-015、AC-017
- 修改：删 `content/reading/source/`、`content/reading/code-reading/`；`HomePage.tsx`、`LearnPage.tsx`
- 步骤：
  1. 仓库：删内容目录与首页卡、图标映射。
  2. 线上：打印待删 7 条（id + 标题）→ 管理端 `DELETE /entries/{id}` → `DELETE /categories/{16,43}` →
     一次性 SQL `DELETE FROM learning_tracks WHERE id IN (5,36)`。
  3. 复查计数与 `/learn` 列表。
- 验证：AC-015 的四个计数；`rg` 无残留引用（历史工件除外）。

### TASK-007 | pending | 内容校验脚本

- 对应：REQ-007；AC-009
- 修改：`scripts/check-source-walks.mjs`（新增）
- 步骤：扫描 `.md` → 提取 `source-walk` 块 → 解析 JSON → 校验字段/行号/文件存在/答案下标 → 汇总。
- 验证：对 24 篇退出码 0；故意改坏一条得到非 0 与具体位置。

### TASK-008 | pending | 篇一 · 起步（4 关）

- 对应：REQ-007 / REQ-010；AC-014
- 内容：01 总览与全链路、02 工厂与解析、03 Configuration、04 MappedStatement
- 验证：`check-source-walks.mjs` + 本地渲染逐关点开。

### TASK-009 | pending | 篇二 · SQL 的诞生（4 关）

- 内容：05 注解方式、06 动态 SQL（SqlNode）、07 SqlSource/BoundSql、08 参数名
- 验证：同上。

### TASK-010 | pending | 篇三 · 接口变成调用（4 关）

- 内容：09 Registry/Factory、10 MapperProxy、11 MapperMethod、12 SqlSession
- 验证：同上。

### TASK-011 | pending | 篇四 · 执行（4 关）

- 内容：13 Executor 家族、14 一级缓存、15 StatementHandler/ParameterHandler、16 主键与批量
- 验证：同上。

### TASK-012 | pending | 篇五 · 结果与缓存（4 关）

- 内容：17 ResultSetHandler、18 TypeHandler、19 延迟加载、20 二级缓存
- 验证：同上。

### TASK-013 | pending | 篇六 · 扩展与底座（4 关）

- 内容：21 插件、22 数据源、23 事务与日志、24 反射与元数据
- 验证：同上。

### TASK-014 | pending | e2e

- 对应：AC-002、AC-004、AC-006、AC-011、AC-013
- 修改：`frontend/e2e/source.spec.ts`（新增）
- 步骤：项目卡 → 章节 → 下一步 → 高亮变化 → 取消开关步骤数变化 → 答预测 → 刷新进度仍在；
  375px 断言 `scrollWidth <= clientWidth`。
- 验证：`playwright test e2e/source.spec.ts`。

### TASK-015 | pending | 导入、部署与线上验证

- 对应：AC-001、AC-003、AC-008、AC-016、AC-017
- 步骤：
  1. `node scripts/import-course.mjs --dir content/source/mybatis --no-track --dry-run` → 正式导入。
  2. `powershell -File scripts/release.ps1 -WithWeb`（前端静态文件变了，必须重建）。
  3. 线上抽查：`/source`、一章的渲染与高亮、`curl` 静态文件 200、第二次导入全 0。
- 验证：逐条记入验证报告（命令、输出、截图/计数）。
