---
artifact: test-report
work_id: WORK-020
work: 从零开始学编程（首期 Python・Java）
status: passed
evidence: required
workflow: full
created: 2026-10-19
updated: 2026-10-19
verified_commit: 7dd4258
accepted_by: xiaou61
accepted_at: 2026-10-29 13:18:30 +0800
---

# 从零开始学编程验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25 全量测试（隔离库，一人一库）+ 线上 codenest-online（生产库 V19） |
| 前端 | 学习者端与管理端从当前工作区重打并部署 |
| 上游模型 | 集成测试用桩；线上真人验收真连 DeepSeek |
| 内容 | `content/courses/` 下 Python 13 篇、Java 13 篇 |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 学习线来自分类标记，不建新表 | `mvn -pl paideia-app -am test` | 0 | passed | `LearningProgressIntegrationTest.linesComeFromCategoryMarks`（9 passed） |
| AC-002 站序按 sort_order，缺省退回 (published_at,id) | 同上 | 0 | passed | 同用例断言 `stationCount` 与顺序；线上实测「00 → 01」推进 |
| AC-003 通过判定在服务端：小测 + 挑战都 ≥80% | `mvn -pl paideia-knowledge test` | 0 | passed | `LearningProgressRulesTest`；线上实测只交小测不通过、补挑战后 `passed` |
| AC-004 线上 `/learn` 显示两门课 | `curl /api/v1/learning/lines`（线上） | 人工 | passed | 返回 `python-from-zero` 13 站、`java-from-zero` 13 站 |
| AC-005 完成一课后状态与「下一站」正确 | 线上 PUT `/learning/entries/36` | 人工 | passed | `status=passed`、`passedAt` 有值、`passedCount 1/13`、下一站推进到「01 · 环境与第一行代码」 |
| AC-006 导入脚本幂等 | `node scripts/import-course.mjs` 连跑两次 | 0 | passed | 第一次 `created 26 / relations 22`；第二次 `created 0 / updated 0 / relations 0` |
| AC-007 新增一条线零代码 | `git status` + 导入脚本 | 0 | passed | 两门课只靠内容目录 + 导入上线，未改后端/前端/迁移（V17 早已就位） |
| AC-008 线上学习者端 e2e 不回归 | `playwright test`（E2E_BASE_URL） | 0 | passed | **60 passed / 22 skipped / 0 failed**（含 `learn.spec.ts` 3 条） |
| AC-009 管理端 e2e 不回归 | 同上（管理端） | 0 | passed | **20 passed / 0 failed** |
| AC-010 全量后端不回归 | `scripts/test-db.sh mvn -B test` | 0 | passed | **475 执行 / 0 失败 / 5 跳过**（跳过均为需真上游用例） |
| AC-011 前端单测与类型检查 | `pnpm -r test` / `pnpm -r typecheck` | 0 | passed | 250 passed（ui 148 / knowledge 42 / assistant 25 / auth 13 / identity 3 / resume 15 / desktop 4）；typecheck 0 error |
| AC-012 课程内容自身可验证 | `python scripts/verify_course.py` | 0 | passed | 25 → 26 个课程文件全部通过（真执行示例并比对输出） |
| AC-013 隔离与隐私：A 看不到 B 的进度、注销后进度被删 | `mvn -pl paideia-app -am test -Dtest=LearningProgressIntegrationTest,AccountDeletionCascadeIntegrationTest` | 0 | passed | 归属断言用例 + 注销级联逐表断言行数为 0 |
| AC-014 既有功能不回归 | 线上学习者端 + 管理端 e2e 全套 | 0 | passed | 60 passed / 22 skipped / 0 failed；管理端 20 passed |
| AC-015 360px 适配 | `playwright test e2e/learn.spec.ts`（含窄屏用例） | 0 | passed | 「课程条目页在 360px 下不横向溢出」线上通过 |
| AC-016 线清单由内容驱动 | `curl /api/v1/learning/lines`（线上） | 人工 | passed | 两条线都来自分类表（`line_kind='course'`），前端无硬编码线名 |
| AC-017 新增一条线零代码 | 两门课的实际导入过程 | 人工 | passed | Python 与 Java 两条线只靠内容目录 + 导入上线，`git status` 显示未改任何 .java/.tsx/迁移 |
| AC-018 源码解读被认成一条线 | `mvn ... LearningProgressIntegrationTest` + 本地 e2e | 0 | passed | 本地：`linesComeFromCategoryMarks` 断言 `source-reading` 出现且只出现一次；**线上该分类不存在，对应 e2e 已按种子依赖跳过** |
| AC-019 无 `order` 的线站序不变 | `mvn -pl paideia-app -am test -Dtest=LearningProgressIntegrationTest` | 0 | passed | 用例断言未设 `sort_order` 时退回 `(published_at, id)`；`LearningProgressRules`/Mapper 两条路径都有覆盖 |

## 本次修复的两个真实缺陷

1. **走查在空库上必红**（`ApiWalkthroughTest`）：概念列表原来在**建概念之前**调，
   空库返回 `[]`，契约快照里该端点形状就成了空数组，换机器重跑必然对不上。
   改成先建再列。
2. **V17 的线标记在空库上影响 0 行**：`UPDATE ... WHERE slug='source-reading'` 早于
   V953 建该分类。生产库顺序对、隔离库不对。不动 V17（改它会撞校验和守卫），
   新增 `V955__mark_source_reading_line.sql` 幂等收尾，只在 devdata 里。

## 未达与未做

1. **`source-reading`（源码解读）在线上不存在**：它是本地 e2e 种子（V953）建的分类，
   生产迁移目录不含 devdata。所以 `learn.spec.ts` 里依赖它的那条在线上跳过
   （`test.skip(REMOTE, …)`）。线上"读源码与专题"分组目前是空的。
2. **没有"做完整门课"的 e2e**：那需要逐题作答整门课程，耗时不可接受。
   线上验收用的是"第一站通过 + 下一站推进"这一条真实链路。
3. **概念图谱在线上为空**：概念表需管理员录入（WORK-021 的设计如此，不是缺陷）。
