---
artifact: test-report
work_id: WORK-073
work: 课程内容 schema 与统一校验
status: passed
evidence: required
created: 2026-11-05
updated: 2026-11-05
---

# 课程内容 schema 与统一校验 验证报告

## 验证环境

- 日期：2026-11-05
- 工作树：`F:\Paideia\.worktrees\WORK-073`（分支 `work/WORK-073`）
- Node v24.14.1，Python 3.11.5；校验器零依赖、离线运行。
- 说明：代码尚未提交（`verified_commit` 缺失），本轮证据来自工作树内新鲜执行。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| TASK-001 schema 与盘点一致（434 篇 / 17 键 / 29 块型） | `node scripts/inventory-content.mjs --check-schema` | 0 | passed | `testing/logs/inventory.txt` |
| AC-001 全量静态校验（434 篇，0 error / 0 warning） | `node scripts/check-content.mjs --scope content` | 0 | passed | `testing/logs/ac001-full.txt` |
| AC-001 白名单只减不增（0 条 ≤ 上限 0） | `node scripts/check-content.mjs --check-whitelist-shrink` | 0 | passed | `testing/logs/whitelist.txt` |
| AC-002 坏样例逐条被拦并带文件:行 | `node scripts/check-content.mjs --scope tests/content-schema/bad` | 1 | passed | `testing/logs/bad.txt` |
| AC-003 坏内容导入前中止、库无变更 | `node scripts/import-course.mjs --dir tests/content-schema/bad --dry-run` | 1 | passed | `testing/logs/import-bad.txt` |
| AC-004 两旧脚本职责并入并已删除 | `node scripts/check-content.mjs --scope tests/content-schema/bad` + `Test-Path` 两脚本 | 1 | passed | `testing/logs/ac004.txt` |
| AC-005 确定性（两次输出字节一致） | 两次 `--json` 输出比对 | 0 | passed | `testing/logs/determinism.txt` |
| AC-006 --exec 编排通过用例 | `node scripts/check-content.mjs --exec -- --no-run --dir content/courses/python` | 0 | passed | `testing/logs/exec-pass.txt` |
| AC-006 --exec 失败样例且透传输出 | `node scripts/check-content.mjs --exec -- --no-run --dir tests/content-schema/bad` | 1 | passed | `testing/logs/exec-fail.txt` |
| AC-007 发版前校验通过 | `powershell -File scripts/release.ps1 -ValidateOnly` | 0 | passed | `testing/logs/release-validate.txt` |
| AC-007 注入坏内容在打包前终止 | 同上，临时注入一份坏内容 | 1 | passed | `testing/logs/release-inject-bad.txt` |

## 验收结果

| AC | 结论 | 证据 |
| --- | --- | --- |
| AC-001 | 满足 | 全量 0 error；白名单 0 条 ≤ 上限 0 |
| AC-002 | 满足 | bad 目录逐条报出，均带 `文件:行` |
| AC-003 | 满足 | import 坏目录写入前中止、无写入 |
| AC-004 | 满足 | 四类旧规则并入并命中；`check_quiz_explain.py`/`check_shell_cases.py` 已删除，无代码引用 |
| AC-005 | 满足 | 两次输出字节一致；内容文件未被校验器改动 |
| AC-006 | 满足 | --exec 通过/失败两档均正确透传 verify_course 结果 |
| AC-007 | 满足 | -ValidateOnly 通过；注入坏内容在打包前终止 |

## 失败与未验证项

- **未跑 `verify_course.py` 全量真执行**：其结构检查（`--no-run`）对 `spring-boot-project` 课程**当前就返回失败**（python/java 课程通过）。这是本项之前已存在的状态、不在 WORK-073 范围；`--exec` 只负责如实编排与透传，未改其判定。
- 未提交代码，故无 `verified_commit` 锚点。

## 剩余风险

- schema 只约束 frontmatter 与块类型/JSON 合法性；块内部业务字段（如 sketch 的具体字段）未逐项建模，留给后续迭代。
- `content/schema/` 为新增目录，需随代码一起提交，否则校验器在干净检出上会因找不到 schema 而报错（当前 `validateContent` 读取 schema 未做缺失兜底）。
