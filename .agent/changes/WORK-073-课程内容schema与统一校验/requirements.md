---
artifact: requirements
work_id: WORK-073
work: 课程内容 schema 与统一校验
status: approved
created: 2026-11-05
updated: 2026-11-05
mode: managed
mode_reason: 覆盖 content/ 与 scripts/ 的多文件改动，需要跨会话追踪；不改变运行时行为、不碰数据库迁移、公共接口与安全面，故用 managed 而非 strict
workflow: compact
depends_on: []
related_to: [WORK-020]
approved_by: xiaou61
approved_at: 2026-11-05 15:56:31 +0800
approver_role: owner
---

# 需求

## 背景

内容目前是"数据驱动"的，但只驱动到一半：

- 课程 / 阅读 / 源码精读的全部正文与 frontmatter 都在 `content/`（436 篇 markdown：课程 106、阅读 306、源码精读 24）。
- 加内容不改代码：`scripts/import-course.mjs` 走已有的管理端 API 幂等导入，`content/courses/` 的来源与导入纪律已由 WORK-020 确立（见 `.agent/rules/always.md`）。
- 但"什么算一份合法内容"没有一份机读定义。结构知识散在 13 个 `check-*.py` / `check-*.mjs` / `verify_*.py` 里（合计约 1770 行，仅 `verify_course.py` 就 491 行），彼此重叠、口径不一，且只在有人记得跑的时候才跑。

本项只做第一步：把"内容合法性"从散落脚本里收成一份正式 schema + 一个统一静态校验入口，并让导入在写入前用它兜底。**不重写 `verify_*.py` 里"真执行"的能力**（那属于另一个决定）。

## 已确认事实

1. `content/` 现有 436 篇 `.md`（`courses` 106 / `reading` 306 / `source` 24）与 2 个 `.json`。
2. frontmatter 出现过 17 个键：`title` / `category` / `order` / `minutes` / `status` 为 434 篇共用；`level` 415、`chapter` 144、`prerequisite` 93；线相关键 11 个（`line_sort` 42、`line_name` 42、`line_kind` 36、`line_summary` 36、`line_mode` 11、`line_theory` 8、`verify` 8、`line_parent` 5、`line_parent_name` 5）。
3. 正文围栏块至少 15 种：`text` 1045、`java` 406、`sketch` 401、`quiz` 369、`code-challenge` 186、`python` 147、`sql` 105、`bash` 74、`js` 35、`yaml` 31、`learning-path` 30、`dockerfile` 27、`mermaid` 24、`source-walk` 24、`ts` 22。
4. 现有静态 / 动态校验脚本共 13 个（`check_mapper_script_xml` 96 行、`check_quiz_explain` 59、`check_seed_fences` 47、`check_shell_cases` 120、`check_sql_judge_parity` 79、`check-diagrams` 140、`check-ops-thresholds` 75、`check-practice-coverage` 83、`check-source-walks` 137、`verify_course` 491、`verify_handmade` 186、`verify_spring_project` 200、`verify_sql_live` 160）。
5. `scripts/import-course.mjs` 按"分类 + 标题"幂等建 / 更条目，凭据只从环境变量读；脚本里不含任何一门课的名字。
6. 项目当前**没有 CI**；发布走 `scripts/release.ps1`。`always.md` 里"审查按需触发、不装自动化"只针对 `ocr` 审查，不针对内容校验。
7. `check_seed_fences.py` 读的是 `backend/paideia-app/src/main/resources/db/devdata/V953__seed_source_reading.sql`（源码精读种子 SQL），**不在 `content/`**，与课程内容校验无关。
8. `check_quiz_explain.py` 与 `check_shell_cases.py` 的扫描根是 `content/`（全量），非只 `courses`；实测块分布 courses quiz 97 / code-challenge 100、reading quiz 248 / code-challenge 62、source quiz 24 / code-challenge 24。**只覆盖 courses 时删除它们，会让 reading/source 的同类守卫失守。** `check-practice-coverage.mjs` 是覆盖 courses+reading 的覆盖率盘点，不是有效性门禁。

## 用户决定

- 2026-11-05：三条扩展性方向（内容数据化 / MCP 唯一对外面 / 契约观测加厚）里，先只做"内容数据化"这一条。
- 2026-11-05：本项按 `managed` 模式建一个 WORK，先做需求深挖，暂不写代码。
- 2026-11-05：第一版校验范围覆盖**全部 `content`（436 篇：courses 106 / reading 306 / source 24）**。原定"只 courses"在复核脚本真实扫描范围后改为全部（见事实 8 与 Q6=A）。
- 2026-11-05：校验分**两档**——默认静态结构校验（供导入前 / 提交前用，要快）；`--exec` 档再编排现有 `verify_course.py` 做真执行校验，**不重写**其判定。
- 2026-11-05：自动入口 = 手动命令 + 导入前置 + `release.ps1` 发布前跑一次；**不装 git 钩子、不建 CI**。
- 2026-11-05：存量不合规用**显式白名单**过渡（每条写文件 + 规则 + 原因），白名单**只减不增**；不强制一次修到零。
- 2026-11-05：统一命令**取代并删除** `check_quiz_explain` 与 `check_shell_cases`（二者扫描全 `content`，职责被完整覆盖）；`check_seed_fences`（devdata 种子）、`check-practice-coverage`（覆盖率报告）及其余脚本全部不动。

## 实现选择

（待需求批准后、拆任务时再落；本阶段只记录会影响验收的取舍。）

## 目标

1. 给 `content/` 的内容（frontmatter + 正文块）定义一份**唯一、可机读**的 schema。
2. 提供一个**统一静态校验命令**，能对内容给出可定位（文件 + 行）的错误，并覆盖现有分散静态检查中属于"内容结构"的那部分。
3. 让 `import-course.mjs` 在**写入前**先跑校验；校验不过即中止，库不产生任何变更。
4. 存量内容能在统一命令下达到一个可复现的"零错误"基线（或对已知例外有显式白名单）。

## 非目标

- 不改任何运行时渲染 / 导入后的用户可见行为。
- 不重写 `verify_course.py` / `verify_sql_live.py` / `verify_spring_project.py` / `verify_handmade.py` 里"真执行"的部分；最多把它们编排进统一入口，不改变其判定。
- 不动后端、前端、数据库迁移与部署。
- 不引入新的内容门户 / CMS / 后台编辑器。

## 功能需求

- REQ-001：存在一份 schema 定义，作为内容合法性的唯一权威（字段、类型、必填、枚举、引用完整性、块类型）。
- REQ-002：存在一个统一校验命令，对指定范围内容做静态校验，逐条报"文件:行 + 规则 + 原因"，并汇总 error / warn 数。
- REQ-003：`import-course.mjs` 在写入前调用该校验；有 error 时中止导入且不写库（dry-run 同样适用）。
- REQ-004：统一命令**取代并删除** `check_quiz_explain.py` 与 `check_shell_cases.py` 的职责（覆盖全 `content` 的 quiz / code-challenge 结构检查）；`check_seed_fences`、`check-practice-coverage`、`check_mapper_script_xml`、`check_sql_judge_parity`、`check-diagrams`、`check-ops-thresholds`、`check-source-walks` 及全部 `verify_*` 动态脚本保持不动。
- REQ-005：统一命令在三个点被调用：手动执行；`import-course.mjs` 写入前（REQ-003）；`release.ps1` 发布前一次。**不装 git 钩子、不新建 CI。**
- REQ-006：统一命令提供 `--exec` 档：静态校验通过后，编排现有 `verify_course.py` 做真执行校验，不改变其判定逻辑；默认档不执行任何示例。
- REQ-007：存在一份显式白名单，逐条记录"文件 + 规则 + 原因"；白名单只减不增，新增违规不得直接进白名单。

## 质量要求

- 错误信息必须可定位到文件与行，不能只说"某篇有问题"。
- 同一份内容、同一命令，两次运行结果一致（确定性）。
- 校验器不得修改内容文件。

## 约束与依赖

- 遵循 `.agent/rules/always.md`：内容源在 `content/`，导入走 `import-course.mjs`，本项**扩展**而不是绕过该纪律。
- 公开仓库保密规则：不得引入凭据；示例与占位不得含敏感信息。

## 关联工作项

- `related_to: WORK-020`：WORK-020 确立了 `content/courses/` 的来源与导入纪律；本项在同一管线上增加 schema 与前置校验，不改其行为，可独立交付。
- `depends_on`：无。

## 验收标准

- AC-001：对 `content/` 全部 436 篇跑统一校验，输出 error 数为 0（或所有 error 均已列入显式白名单并注明原因）；白名单规模不得因本项扩大（只允许后续减少）。
- AC-002：构造的坏样例（缺必填 frontmatter 键 / `prerequisite` 指向不存在 / 出现未登记的块类型 / `order` 断裂 / frontmatter 字段类型错误）逐条被拦下，且报出文件与行。
- AC-003：向 `import-course.mjs` 喂一份坏内容时，在写入前失败，目标库无任何变更（测试库或 dry-run 可证）。
- AC-004：逐条列出 `check_quiz_explain` / `check_shell_cases` 的检查项与统一命令的映射并核验等价；这两个旧脚本已删除，其余脚本未被改动。
- AC-005：同一份内容连续运行两次，输出一致且不产生文件改动。
- AC-006：`--exec` 档能成功编排 `verify_course.py`；对一份已知会失败的样例，`--exec` 返回非零并给出该脚本的失败输出（证明"不重写、只编排"）。
- AC-007：`release.ps1` 在发布前调用统一命令；校验失败时不继续发布。

## 假设

- 假设 frontmatter 的 17 个键里，`line_*` 组只出现在"有总览（order 0）"的学习线上；否则需要放宽 schema。
- 假设 `reading` / `source` 与 `courses` 共用同一套 frontmatter 核心键与块类型；若发现差异，以 schema 中"可选键 + 允许的块类型"兼容，不拆第二套 schema。

## 待确认问题

- 无。6 个问题（范围 / 深度 / 入口 / 存量 / 旧脚本 / 脚本修正）均已确认，见「用户决定」。

