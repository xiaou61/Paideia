---
artifact: test-report
work_id: WORK-083
work: 试卷与智能组卷
status: passed
evidence: required
mode: strict
workflow: full
created: 2026-11-22
updated: 2026-11-22
accepted_by: xiaou61
accepted_at: 2026-11-22 14:48:11 +0800
---

# 试卷与智能组卷验证报告

结论：`AC-001`~`AC-008` 全部有证据；后端窄套件（knowledge 21 条 + app 23 条）、守卫切片 35 条、
前端 e2e 14 条全部全绿，均为**本轮全新执行**（2026-11-22 11:52–12:14），不是沿用实施期的旧记录。

## 验证环境

- 代码：`F:\Paideia`，`master`，HEAD `c2b93fea`（2026-11-21）。自 2026-11-19 实施完成后源码未再变动。
- 后端：JDK 25 / Spring Boot 4.1.1；一律经 `scripts/test-db.sh` 连远程共用测试库 `paideia_e2e`（账号只授权该库，**不派生独占库**，因此同一时刻只跑一轮）。
- 前端：Node + pnpm，Playwright（`chromium` project，`workers: 1`）。
- 后端产物：`mvn -B -DskipTests package` 重新打包（`testing/logs/backend-package.log`，BUILD SUCCESS 7.697s），e2e 用该 jar 起 `--spring.profiles.active=local`。
- **环境前置（重要）**：本机 vite 8.3.4 的 dev server 默认只绑 `[::1]:5173`，而 `playwright.config.ts` 的 app `url` 是 `http://127.0.0.1:5173`，探活必然失败（表现为 `Timed out waiting 60000ms from config.webServer`）。跑 e2e 前先手动把 dev server 起在 IPv4 上——`pnpm --filter @paideia/app exec vite --host 127.0.0.1`——Playwright 会因 `reuseExistingServer: true` 复用它（日志里可见 `WebServer is already available`）。这是环境差异，不是产品缺陷。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 抽取 387 篇 / 1147 题、坏块计数且不入库 | `../scripts/test-db.sh mvn -B -pl paideia-knowledge,paideia-app -am -Dtest=QuestionIndexIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/paper-backend-tests.log`：`QuestionIndexIntegrationTest` 4 条（真实内容目录扫描，断言题数与坏块计数一致） |
| AC-002 同参数两次组卷逐题相同 | 同上命令（`PaperAssemblerTest` + `PaperIntegrationTest`） | 0 | passed | `PaperAssemblerTest` 4 条（同种子两次装配逐题相等）、`PaperIntegrationTest` 7 条（同参数两次建卷比对题目序列） |
| AC-003 交卷前接口不返回 `answer` | 同上命令（`PaperIntegrationTest` + `PracticeIntegrationTest`） | 0 | passed | `PaperIntegrationTest` 断言试卷详情 JSON 不含 `answer`；`PracticeIntegrationTest` 只返回题面与选项 |
| AC-004 判分：全对=概念正确率 100% 且无薄弱项；错题计数正确 | 同上命令（`PaperScorerTest` + `PaperIntegrationTest`） | 0 | passed | `PaperScorerTest` 5 条（全对/部分对/全错三态）；`PaperIntegrationTest` 覆盖交卷后成绩体与错题数（含 409 重复交卷、快照判分） |
| AC-005 关系可查：给定概念列出其题目与前置概念下的题目 | 同上命令（`QuestionIndexIntegrationTest` + `WrongBookIntegrationTest`） | 0 | passed | `QuestionIndexIntegrationTest` 按概念查题；`WrongBookIntegrationTest` 3 条（错题按概念聚合） |
| AC-006 他人试卷 id 404；注销级联 | 同上命令 + 守卫切片（`AuthorizationIsolationTest`） | 0 | passed | `PaperIntegrationTest` 换用户读同一 id 得 404；`AuthorizationIsolationTest` 9 条令牌隔离矩阵；注销级联见 `QuestionAttemptIntegrationTest` 6 条 |
| AC-007 刷题按标签筛选，组合取交集 | `-Dtest=PracticeFilterTest` + e2e `practice.spec.ts` | 0 | passed | `PracticeFilterTest` 4 条（课程线=python 且难度=中，组合取交集）；`testing/logs/e2e-practice-papers-graph.log` 中 `practice.spec.ts` 4 条通过 |
| AC-008 刷题即时判定并落进度，不生成试卷记录 | `-Dtest=PracticeSelectorTest` + e2e `practice.spec.ts` | 0 | passed | `PracticeSelectorTest` 4 条（不落试卷记录）；e2e 提交一题即时出对错与解析 |
| 难度标注（TASK-008） | `-Dtest=DifficultyLabelTest,QuestionLabelingIntegrationTest` | 0 | passed | 4 + 2 条（规则、AI 回退分支、标注落库） |
| 跨模块边界与端点访问级别 | `../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | passed | `testing/logs/guard-slice.log`：35 条全绿（ApiContractTest 10 / ApiSurfaceTest 7 / AuthorizationIsolationTest 9 / ModularityTest 1 / 迁移三件 8），BUILD SUCCESS 01:40 min |
| 迁移三件（命名/校验和/种子隔离） | 同上守卫切片 | 0 | passed | `MigrationVersionFormatTest` 3 / `MigrationChecksumTest` 2 / `MigrationSeedOverlapTest` 3 |
| 前端 e2e：刷题 / 试卷 / 图谱不回归 | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/practice.spec.ts e2e/papers.spec.ts e2e/graph.spec.ts` | 0 | passed | `testing/logs/e2e-practice-papers-graph.log`：`Running 14 tests` → `14 passed (1.3m)` |

分项计数（`paper-backend-tests.log`，`Total time: 02:07 min`）：

- `paideia-knowledge` 21 条：`PaperAssemblerTest` 4、`PaperScorerTest` 5、`DifficultyLabelTest` 4、`PracticeFilterTest` 4、`PracticeSelectorTest` 4。
- `paideia-app` 23 条：`PaperIntegrationTest` 7、`PracticeIntegrationTest` 1、`QuestionAttemptIntegrationTest` 6、`QuestionIndexIntegrationTest` 4、`QuestionLabelingIntegrationTest` 2、`WrongBookIntegrationTest` 3。

## 验收结果

| AC | 结果 | 说明 |
| --- | --- | --- |
| AC-001 | passed | 抽取计数与坏块计数由集成测试对照真实内容目录断言，不硬编码 1147 |
| AC-002 | passed | 纯函数层与接口层各证一次 |
| AC-003 | passed | 交卷前响应体无 `answer` 字段 |
| AC-004 | passed | 判分三态 + 成绩聚合 + 重复交卷 409 |
| AC-005 | passed | 概念查题 + 错题按概念聚合 |
| AC-006 | passed | 他人 404 + 令牌隔离矩阵 + 注销级联 |
| AC-007 | passed | 纯函数交集 + 页面级标签筛选 |
| AC-008 | passed | 即时判定 + 不落试卷记录 |

## 失败与未验证项

- **未验证**：抽取题数的绝对值（387 篇 / 1147 题）是建项时的计数；内容会变，测试断言的是「与实际扫描结果一致 + 坏块有计数」。这条按计划属于内容 PR 的评审范围，不在自动化内。
- **未验证**：AI 难度标注的质量（`DifficultyLabelTest` 只证规则与回退分支）；标注准确率属抽检项。
- **不可重放**：TASK-009 记录过一次 `release.ps1 -WithWeb` 部署成功（168.6s）与线上 `GET /api/v1/learning/papers`→401、`/actuator/health`→200 UP；那是一次性部署证据，本轮未重复部署。
- **未跑**：后端全量套件（667 条）。本项未改公共设施，守卫切片已覆盖模块边界与端点声明；全量留给交接或发版。
- **环境差异**：e2e 需要先把 vite 起在 `127.0.0.1`（见「验证环境」），否则 Playwright 的 webServer 探活超时。

## 人工检查

- 窄屏（≤420px）下刷题页与试卷页不横向滚动、选项可点：本轮以 `layout.mobile` 不回归为界，未重复截图（TASK-009 已记）。
