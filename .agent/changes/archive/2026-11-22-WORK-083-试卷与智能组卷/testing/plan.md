---
artifact: test-plan
work_id: WORK-083
work: 试卷与智能组卷
status: ready
mode: strict
workflow: full
created: 2026-11-22
updated: 2026-11-22
---

# 试卷与智能组卷测试计划

## 测试范围

本项把「正文内嵌小测」抽成题库，并在其上长出三件事：规则组卷 + 交卷判分、按标签刷题、错题本。验证按这条链路分四层，每层只证自己那一段：

- **题库层**：抽取计数与坏块处理、难度规则、标签派生（`paideia-knowledge`）。
- **组卷与判分层**：同参数可复现、交卷前不泄题、判分与成绩聚合（`paideia-knowledge` 纯函数 + `paideia-app` 集成）。
- **接口与归属层**：用户隔离、他人试卷 404、注销级联、刷题不生成试卷（`paideia-app` 集成 + 守卫切片）。
- **用户可见层**：刷题页与试卷/成绩/错题页的入口与主路径（`apps/app` 的 Playwright e2e）。

不在本计划内：主观题批改、AI 补题质量、导出打印、考试排期（需求已列为非目标）。

## 环境与前置条件

- 后端测试一律经 `scripts/test-db.sh`（**唯一入口**）。它按 `backend/config/remote-db.local.env` 连远程测试库：**该账号只能用一个库、建不了派生库**，所以同一时刻只允许一轮测试在跑。
- 命令在 `backend/` 下执行，脚本路径写 `../scripts/test-db.sh`（脚本本身在仓库根的 `scripts/`）。
- e2e 在仓库根执行，走 `scripts/test-db.sh pnpm --filter @paideia/app test:e2e <spec>`；它会自带后端与前端服务，端口由 `PAIDEIA_*_PORT` 控制。
- 上游前置：`requirements.md`（approved 2026-11-18 15:29:52，xiaou61/CTO）与 `tasks.md`（approved 2026-11-18 15:44:39）均为 approved，`full` 模式的上游门槛已满足。

## 验收矩阵

| AC | 验证方式 | 具体用例/命令 |
| --- | --- | --- |
| AC-001 抽取 387 篇 / 1147 题，坏块计数且不入库 | 集成 | `QuestionIndexIntegrationTest`（真实内容目录跑抽取，断言题数与坏块计数） |
| AC-002 同参数两次组卷逐题相同 | 单测 + 集成 | `PaperAssemblerTest`（同种子两次装配逐题相等）；`PaperIntegrationTest`（同参数两次建卷比对题目序列） |
| AC-003 交卷前接口不返回 `answer` | 集成 | `PaperIntegrationTest`（读取试卷详情的 JSON 不含 `answer` 字段）；`PracticeIntegrationTest`（刷题响应只给题面与选项） |
| AC-004 判分：全对=概念正确率 100% 且无薄弱项；错题计数正确 | 单测 + 集成 | `PaperScorerTest`（纯函数全对/部分对/全错三态）；`PaperIntegrationTest`（交卷后成绩体与错题数） |
| AC-005 关系可查：给定概念列出其题目与前置概念下的题目 | 集成 | `QuestionIndexIntegrationTest`（按概念查题）；`WrongBookIntegrationTest`（错题按概念聚合） |
| AC-006 他人试卷 id 404；注销级联 | 集成 + 守卫 | `PaperIntegrationTest`（换用户读同一 id 得 404）；`AuthorizationIsolationTest`（令牌隔离矩阵）；注销级联见 `QuestionAttemptIntegrationTest` 与 TASK-009 记录 |
| AC-007 刷题按标签筛选，组合取交集 | 单测 + e2e | `PracticeFilterTest`（标签组合交集）；`e2e/practice.spec.ts`（页面上选课程线=python + 难度=中） |
| AC-008 刷题即时判定并落进度，不生成试卷记录 | 单测 + e2e | `PracticeSelectorTest`（不落试卷）；`e2e/practice.spec.ts`（提交一题即时出对错与解析） |
| 跨模块边界与端点访问级别（本项新增 `/api/v1/learning/**`） | 守卫 | `ModularityTest`、`ApiSurfaceTest`、`ApiContractTest` |

## 自动化检查

按「先窄后宽」排序，全部为**全新执行**：

1. 题库与组卷纯函数：`cd backend && ../scripts/test-db.sh mvn -B -pl paideia-knowledge -am -Dtest=DifficultyLabelTest,PaperAssemblerTest,PaperScorerTest,PracticeFilterTest,PracticeSelectorTest -Dsurefire.failIfNoSpecifiedTests=false test`
2. 接口与归属集成：`cd backend && ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=QuestionIndexIntegrationTest,PaperIntegrationTest,QuestionAttemptIntegrationTest,PracticeIntegrationTest,WrongBookIntegrationTest,QuestionLabelingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
3. 守卫切片（跨模块契约 + 端点访问级别 + 迁移三件）：`cd backend && ../scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test`
4. 前端 e2e（只跑本项两个文件）：`bash scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/practice.spec.ts e2e/papers.spec.ts`

不跑全量后端套件：本项未改跨模块契约以外的公共设施，第 3 条已覆盖模块边界与端点声明；全量留给交接或发版。

## 人工检查

- 窄屏（≤ 420px）下刷题页与试卷页不横向滚动、选项可点（TASK-009 已记，本轮以 e2e 的 `layout.mobile` 不回归为界，不重复截图）。
- 题库内容质量不属本计划：抽出来的题干/解析与原文一致性由内容 PR 评审，测试只证「抽得到、抽得对位」。

## 回归范围

- `learning_progress` 写入口径未改（错题与刷题进度复用既有 `ProgressPort`），回归靠 `QuestionAttemptIntegrationTest`。
- 既有「练习册」（WORK-048，`extractQuizzes`）仍读正文，本项只新增只读抽取，不改其路径。
- 训练营（WORK-029）判分与出题未改，仅在概念证据上并存。

## 已知缺口

- **抽取题数以真实内容目录为准**：内容会变（新讲义带新小测），387/1147 是建项时计数。集成测试断言的是「与实际扫描结果一致 + 坏块有计数」，不是硬编码 1147。
- **难度标注含 AI 回退**：`DifficultyLabelTest` 只证规则与回退分支，AI 标注质量不在此列（TASK-008 记录里已标为抽检）。
- **部署与线上健康**：TASK-009 记录过一次 `release.ps1 -WithWeb` 成功与线上 401/200 探测；那是一次性部署证据，不可重放为回归用例。
