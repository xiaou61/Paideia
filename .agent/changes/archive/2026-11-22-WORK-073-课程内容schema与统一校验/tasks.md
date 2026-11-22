---
artifact: tasks
work_id: WORK-073
work: 课程内容 schema 与统一校验
status: approved
created: 2026-11-05
updated: 2026-11-05
approved_by: xiaou61
approved_at: 2026-11-05 15:58:23 +0800
approver_role: owner
---

# 课程内容 schema 与统一校验 实施任务

## 依据

- 已批准 `requirements.md`（WORK-073，managed / compact，2026-11-05 批准，xiaou61 / owner）。
- 真实路径与命令已核对：`content/`（436 篇）、`scripts/import-course.mjs`（`main()` 在 462 行）、`scripts/release.ps1`（`mvn -B verify` 在 224 行）、`scripts/check_quiz_explain.py`、`scripts/check_shell_cases.py`、`scripts/verify_course.py`。

## 全局约束

- 不新增运行时依赖：校验器用零依赖 Node ESM + 手写规则；schema 用 JSON 声明式文件。根 `package.json` 无测试框架，验证一律用"运行脚本 + 看退出码/输出"。
- 校验器**只读**内容文件，不得修改 `content/`。
- 只改 `scripts/`、`content/schema/`、`tests/content-schema/` 与两处文档；不碰后端 / 前端 / DB / 部署产物。
- 遵循公开仓库保密规则（无凭据、无服务器地址）。

## 共享契约（跨任务字面一致）

- schema 文件：`content/schema/lesson.schema.json`（JSON；声明 frontmatter 字段与块类型）。
- 白名单文件：`content/schema/whitelist.json`（数组，元素 `{ file, rule, reason }`）。
- 校验模块：`scripts/check-content.mjs`
  - 导出：`export async function validateContent({ scope = 'content' } = {}): Promise<{ errors: Issue[], warnings: Issue[] }>`
  - `Issue`：`{ file: string, line: number, rule: string, message: string }`（`file` 为仓库相对路径，`line` 为 1-based）
  - CLI：`node scripts/check-content.mjs [--scope <dir>] [--exec] [--json]`
  - 退出码：`0` = 无 error；`1` = 有 error，或 `--exec` 子脚本失败
  - 非 `--json` 输出：每行 `文件:行  规则  原因`

## 评审焦点

| 失败模式 | 归属任务 | 测试 / 验证 |
| --- | --- | --- |
| quiz 块 JSON 含裸引号导致解析失败 | TASK-002 | `tests/content-schema/bad/quiz-bad-json.md` → 退出 1 |
| code-challenge 的 `judge` 为空 / seed 路径含 `..` | TASK-004 | `tests/content-schema/bad/shell-case-bad.md` → 退出 1 |
| 两个块 `id` 跨文件重复 | TASK-004 | `tests/content-schema/bad/dup-id/*.md` → 退出 1 |
| `prerequisite` 指向不存在的标题 | TASK-002 | `tests/content-schema/bad/prerequisite-missing.md` |
| `order` 从 0 起不连续 | TASK-002 | `tests/content-schema/bad/order-gap.md` |
| 白名单被用来掩盖新增违规 | TASK-003 | `node scripts/check-content.mjs --check-whitelist-shrink` 条数不得增加 |
| 校验器写入 / 修改内容文件 | TASK-002 | 运行前后 `git status --porcelain content/` 为空 |

## 任务

### TASK-001 | done | 抽取内容形态并固化 schema

- 对应：`REQ-001`、`AC-002`（部分）
- 依赖：无
- 新建/修改：`content/schema/lesson.schema.json`（新建）、`scripts/inventory-content.mjs`（新建）
- 接口：产出 `lesson.schema.json`（结构见「共享契约」）；`inventory-content.mjs` 仅开发期盘点，不被其它任务依赖
- 测试：`node scripts/inventory-content.mjs`、`node scripts/inventory-content.mjs --check-schema`
- 步骤：
  1. 写 `scripts/inventory-content.mjs`：遍历 `content/`，统计每个 frontmatter 键的出现次数与枚举值、每种围栏块计数，打印汇总。
  2. 加 `--check-schema`：把盘点结果与 `lesson.schema.json` 的声明比对，不符退出 1。
  3. 依据盘点写 `content/schema/lesson.schema.json`：frontmatter 5 个必填键（`title`/`category`/`order`/`minutes`/`status`）+ 可选键（`level`/`chapter`/`prerequisite`/`line_*`）与枚举；块类型清单为盘点得到的 **29 种**，其中 7 种 JSON 结构块（`quiz`/`code-challenge`/`sketch`/`learning-path`/`source-walk`/`code-map`/`sql-challenge`）列入 `structuredBlocks`。
  4. 运行 `--check-schema`，把差异改到一致。
- 验证：实施实测——`node scripts/inventory-content.mjs` 打印 **434 篇内容**（另 2 篇 README 跳过）/ 17 键 / **29 块型**（原计划的"436 篇 / 15 块型"是按含 README 与 top15 的粗口径，已按实测更正）；`node scripts/inventory-content.mjs --check-schema` → 退出 0 ✓

### TASK-002 | done | 统一静态校验器

- 对应：`REQ-002`、`AC-002`、`AC-005`
- 依赖：TASK-001
- 新建/修改：`scripts/check-content.mjs`（新建）、`tests/content-schema/`（新建样例）
- 接口：实现「共享契约」里的 `validateContent` 与 CLI（字面一致）
- 测试：`tests/content-schema/good/`、`tests/content-schema/bad/*`
- 步骤：
  1. 写 `tests/content-schema/good/`（3 篇合法内容）与 `tests/content-schema/bad/`（缺必填键、`prerequisite` 不存在、`order` 断裂、未登记块类型、frontmatter 字段类型错各一篇）。
  2. 实现 `validateContent({ scope })`：解析 frontmatter + 校验 schema + 结构化块 JSON 与必填键 + `prerequisite` 引用存在 + `order` 连续；返回 `{ errors, warnings }`。
  3. 实现 CLI 包装：`--scope` / `--json`；非 json 时按 `文件:行  规则  原因` 打印；退出码 0/1。
  4. 对 good / bad 各跑一次，校准报错到具体文件与行。
- 验证：实施实测——`--scope tests/content-schema/good` → 0 error；`--scope tests/content-schema/bad` → 退出 1 且逐条带 `文件:行`；连续两次 `--json` 输出字节一致；`git status -- content/` 除新增 `content/schema/` 外无改动

### TASK-003 | done | 存量基线与白名单

- 对应：`REQ-007`、`AC-001`、`AC-005`
- 依赖：TASK-002
- 新建/修改：`content/schema/whitelist.json`（新建）、必要时修正若干内容文件
- 接口：白名单结构见「共享契约」
- 测试：全量校验 + `--check-whitelist-shrink`
- 步骤：
  1. `node scripts/check-content.mjs --scope content` 跑全量，导出 error 清单。
  2. 逐条判断：能直接修的内容修正；确属历史遗留的登记进 `whitelist.json`（`file` + `rule` + `reason`）。
  3. 实现 `--check-whitelist-shrink`：白名单用 `{ maxEntries, entries }` 形式，断言 `entries.length <= maxEntries`，超出即退出 1（只许降上限、不许加条目）。
  4. 再跑全量到 error 为 0（命中白名单的以 warnings 呈现）。
- 验证：实施实测——全量基线只有 2 个真 bug（`java/04` sketch 收尾围栏与结论同一行、`spring-handmade/01` prerequisite 指向不存在的"00 · 总览"），均直接修正内容，未使用白名单；`node scripts/check-content.mjs --scope content` → 0 error / 0 warning；`--check-whitelist-shrink` → `0 条 ≤ 上限 0` ✓；连跑两次一致

### TASK-004 | done | 并入并删除两个旧脚本

- 对应：`REQ-004`、`AC-004`
- 依赖：TASK-002
- 新建/修改：改 `scripts/check-content.mjs`；删 `scripts/check_quiz_explain.py`、`scripts/check_shell_cases.py`；补 `tests/content-schema/bad/`
- 接口：复用「共享契约」`validateContent`
- 测试：新增坏样例
- 步骤：
  1. 把 `check_quiz_explain` 的"quiz 每题必须有非空 `explain`"、`check_shell_cases` 的"`judge` 非空且 ≤20000、seed ≤20 文件 / 单文件 ≤64000 / 合计 ≤256000、seed 路径合法、块 `id` 全仓唯一"并入 `validateContent`。
  2. 增加坏样例 `quiz-bad-json.md`、`shell-case-bad.md`、`dup-id/*.md`。
  3. 删除两个旧脚本；用 `git grep -n "check_quiz_explain\|check_shell_cases"` 确认无残留引用。
- 验证：实施实测——`node scripts/check-content.mjs --scope tests/content-schema/bad` 报出 10 个问题，含新增四类 `block.quiz.explain` / `block.challenge.judge` / `block.challenge.seed` / `block.challenge.id`；全量 `content` 仍 0 error（新规则不误伤存量）；两个旧脚本已删除，`git grep` 无代码引用（仅 `.agent/` 历史工件与 WORK-073 自身文档提及，属记录保留）

### TASK-005 | done | import-course 写入前置校验

- 对应：`REQ-003`、`AC-003`
- 依赖：TASK-002
- 新建/修改：`scripts/import-course.mjs`（改）
- 接口：`import { validateContent } from './check-content.mjs'`
- 测试：`--dry-run` 喂坏目录
- 步骤：
  1. 在 `main()`（约 462 行）里、`login` 之后、任何 `ensureParent`/`ensureCategory`/`importLanguage` 写入之前，对目标目录跑 `validateContent`。
  2. 有 error 时打印错误并以非 0 退出，不发起任何写请求；`--dry-run` 同样先校验。
- 验证：实施实测——`node scripts/import-course.mjs --dir tests/content-schema/bad --dry-run` → 退出 1、打印"内容校验未通过（10 个问题），已中止导入"，无 `created/updated`；`--dir tests/content-schema/good --dry-run` → 预检通过、正常 dry-run

### TASK-006 | done | release.ps1 发布前置校验

- 对应：`REQ-005`、`AC-007`
- 依赖：TASK-002
- 新建/修改：`scripts/release.ps1`（改）
- 接口：调用 `node scripts/check-content.mjs --scope content`
- 测试：注入坏样例的干跑
- 步骤：
  1. 在 `release.ps1` 打包/部署动作之前插入校验段：运行校验，`$LASTEXITCODE -ne 0` 时 `throw` 终止。
  2. 确认该段在 `-WithTests` / `-SkipWeb` 等分支下都先执行。
- 验证：实施实测——加 `-ValidateOnly` 安全阀后：正常跑 `release.ps1 -ValidateOnly` → 打印 `0 error` 并"未打包未部署"退出 0；临时注入一份坏内容 → 打包前 `throw "内容校验未通过（退出码 1），已终止发版"` 退出 1；清理后恢复 0

### TASK-007 | done | --exec 档编排 verify_course.py

- 对应：`REQ-006`、`AC-006`
- 依赖：TASK-002
- 新建/修改：`scripts/check-content.mjs`（改）
- 接口：CLI 增加 `--exec`
- 测试：正常 / 失败样例
- 步骤：
  1. 静态校验通过后，`--exec` 以子进程调用 `python scripts/verify_course.py`，透传 stdout/stderr 与退出码。
  2. 静态有 error 时不进入执行档。
- 验证：实施实测——`node scripts/check-content.mjs --exec -- --no-run --dir content/courses/python` → 静态 0、`verify_course` 通过、退出 0；`--exec -- --no-run --dir tests/content-schema/bad` → 退出 1 且打印 `verify_course` 的失败输出。**既有事实**：`verify_course.py --no-run` 对 `spring-boot-project` 课程当前就返回失败（python/java 通过），属本项之前的既有状态、不在范围内，`--exec` 只如实透传、不改其判定。

### TASK-008 | done | 文档与项目规则同步

- 对应：`REQ-005`（说明性）、`AC-001`
- 依赖：TASK-003、TASK-004
- 新建/修改：`.agent/rules/always.md`（改）、`content/courses/README.md`（改）
- 接口：无
- 测试：文档核对
- 步骤：
  1. 在 `always.md`「课程内容源在 `content/courses/`」那条下补：改内容后跑 `node scripts/check-content.mjs --scope content`（要真执行时加 `--exec`）。
  2. 更新 `content/courses/README.md` 的用法段，指向统一命令。
- 验证：实施实测——`.agent/rules/always.md` 与 `content/courses/README.md` 均新增 `node scripts/check-content.mjs` 用法；代码/脚本中无指向已删脚本的引用

## 完成条件

- 全量 `node scripts/check-content.mjs --scope content` 退出 0（AC-001）
- 坏样例矩阵全部被拦（AC-002 / AC-004 / AC-006）
- import 与 release 两处前置生效（AC-003 / AC-007）
- `check_quiz_explain.py`、`check_shell_cases.py` 已删除且无残留引用
- 全部任务 `done`，`testing/report.md` 记录实测输出与退出码
