---
artifact: test-plan
work_id: WORK-073
work: 课程内容 schema 与统一校验
status: ready
created: 2026-11-05
updated: 2026-11-05
---

# 课程内容 schema 与统一校验 测试计划

## 测试范围

- 新增 `scripts/check-content.mjs` 的静态校验能力（frontmatter / 块类型 / 结构化块 JSON / prerequisite / order / 白名单）。
- 新增 `content/schema/lesson.schema.json` 与 `whitelist.json`。
- `scripts/import-course.mjs` 写入前校验、`scripts/release.ps1 -ValidateOnly` 发版前校验。
- `--exec` 编排 `verify_course.py`。
- 两个旧脚本 `check_quiz_explain.py` / `check_shell_cases.py` 的职责被取代并删除。

## 环境与前置条件

- Windows + Node v24.14.1 + Python 3.11.5。
- 工作树 `F:\Paideia\.worktrees\WORK-073`（分支 work/WORK-073）。
- 校验器零依赖，不需要数据库 / 网络。

## 验收矩阵

| AC | 检查 | 命令 | 预期 |
| --- | --- | --- | --- |
| AC-001 | 全量静态零错误 + 白名单只减不增 | `node scripts/check-content.mjs --scope content` / `--check-whitelist-shrink` | 退出 0 |
| AC-002 | 坏样例逐条被拦并报文件:行 | `node scripts/check-content.mjs --scope tests/content-schema/bad` | 退出 1 |
| AC-003 | 坏内容导入前中止、库不变 | `node scripts/import-course.mjs --dir tests/content-schema/bad --dry-run` | 退出 1、无 created/updated |
| AC-004 | 两旧脚本职责并入 + 已删除 | `check-content --scope bad`（含 quiz.explain/judge/seed/id）+ `Test-Path` 两脚本 | 退出 1 / 均不存在 |
| AC-005 | 确定性、不改内容 | 两次 `--json` 比对 + `git status -- content/` | 一致 / 无内容改动 |
| AC-006 | --exec 编排 verify_course | `--exec -- --no-run --dir content/courses/python` / `... --dir tests/content-schema/bad` | 0 / 非 0 且透传输出 |
| AC-007 | 发版前校验 | `release.ps1 -ValidateOnly` / 注入坏内容后同上 | 0 / 非 0 且终止 |

## 自动化检查

均为上面的 node / powershell 命令；无额外测试框架（根 `package.json` 无测试配置）。

## 人工检查

- 阅读 `content/schema/lesson.schema.json` 确认字段/枚举与 `scripts/inventory-content.mjs` 盘点一致。
- 确认 `always.md` / `content/courses/README.md` 指向新命令。

## 回归范围

- `verify_course.py` 的判定**未改动**（只由 `--exec` 调用）。
- 其余 11 个校验脚本未改动。

## 已知缺口

- 只验证静态结构与块 JSON；块内部全部业务字段（如 sketch 的必填字段）未逐项建模。
- `verify_course.py` 对 `spring-boot-project` 课程当前返回失败，属既有状态、不在本项范围。
