---
artifact: test-report
work_id: WORK-018
work: 简历识别与优化
status: passed
mode: strict
workflow: full
evidence: required
created: 2026-10-18
updated: 2026-10-18
accepted_by: xiaou61
accepted_at: 2026-10-29 13:18:16 +0800
---

# 验证报告 · 简历识别与优化

## 环境与前置

| 项 | 值 |
| --- | --- |
| 后端 | JDK 25.0.4 / Spring Boot 4.1.1 / Maven |
| 数据库 | 本机 MySQL 8.0（`127.0.0.1:3306`），**独立验证库**，跑完即删 |
| 前端 | pnpm workspace / Vitest / Playwright（chromium + Pixel 7） |
| 模型调用 | **全部用桩**（三个端口 `@MockitoBean`），本轮没有真实上游调用、没有花积分 |

**共享测试库 `paideia_test` 本轮不可用**：它的 `flyway_schema_history` 里 `V14` 记为已应用，但
`ai_conversations.harness`、`uk_ai_memory_structured_ref`、`ai_interview_evaluations` 三处改动
都不存在（`SELECT` 逐项核对：`0`），且换行符变化导致校验和不匹配（记 2059499851 / 本地 -796408691）。
因此本轮在三个临时库上验证：`paideia_probe`（全量后端）、`paideia_verify_work018`、
`paideia_verify_work018_iso`。**这是既有环境问题，不是本项引入的**；修复方式是对该库做一次
`flyway repair` 后重跑 V14，或直接重建——**未在本项范围内执行**，需要单独确认（它会影响所有
并发会话的集成测试）。

## 后端

| 命令 | 结果 | 退出码 | 证据 |
| --- | --- | --- | --- |
| `mvn -B -pl paideia-ai test -Dtest='Resume*Test'` | **49 passed / 0 failed / 0 skipped** | 0 | 5 个测试类：抽取器 10、JSON 9、结构化输出 9、三桶 6、闸门 15 |
| `mvn -B -pl paideia-ai test` | **99 passed / 0 failed / 0 skipped** | 0 | 含既有 50 条，无回归 |
| `mvn -B -pl paideia-app test`（独立库） | **168 passed / 0 failed / 4 skipped** | 0 | 4 skipped 是既定的"无口令跳过"形态，已核对不是本项造成 |
| `mvn -B test`（全模块，独立库） | **BUILD SUCCESS** | 0 | 所有模块；paideia-app 168 条同上 |

### 新增测试清单

| 测试类 | 条数 | 覆盖 |
| --- | --- | --- |
| `ResumeTextExtractorTest` | 10 | 文本型 PDF 抽取、无文字层拒绝、20 页截断、加密 PDF、DOCX 抽取、**DOCTYPE/XXE 拒绝**、zip 无 `word/document.xml`、`.doc` 拒绝、其它格式拒绝、空文件 |
| `ResumeJsonTest` | 9 | 正常解析、**引证对不上原文则丢条目**、缺引证保留、未知字段丢弃、`bullets` 类型错误容错、超长截断计数、空/非 JSON/非对象判失败、```json 围栏、写库读回一致 |
| `ResumeStructuredOutputTest` | 9 | 分析解析与零要求判失败、**`entryRef` 不存在丢条目**、**模型篡改 `originalText` 丢条目**、**缺口词出现即丢**、全丢判失败、未知 `changeType` 回退 |
| `ResumeKeywordClassifierTest` | 6 | 三桶各归其位、等价写法（k8s/js）、ASCII 词边界（java 不命中 javascript）、CJK 子串、缺口清单、重复项照实保留 |
| `ResumeGateTest` | 15 | 数字（放行/拦编造/完整 token/年份不误伤）、重合度（正常/拦无关/reorder 跳过/压缩放行）、身份（职位名/公司名/时间）、缺口词（ASCII + CJK）、多提案按序号定位、覆盖率基线 |
| `ResumeEndpointsIntegrationTest` | 4 | **归属 404 全覆盖**（读/改名/设默认/历史/删）、匿名 401、**旧 revision 保存被挡且不覆盖**、**零积分时上游调用次数 0** |

### 反向验证（真实执行，先红后绿）

| 注入 | 结果 |
| --- | --- |
| 提案里加主简历没有的数字（`提升 30%`） | 闸门返回 `numbers_not_in_source`，定稿被拒 |
| 把 `80ms` 写成原文没有的 `80`（原文是 `800`） | 同上——完整 token 校验生效 |
| 把"后端开发工程师"改成"后端技术专家" | `identity_changed` |
| 把公司名改掉 / 起止时间改掉 | `identity_changed` |
| 把整条要点换成无关内容 | `low_overlap` |
| 改写里出现缺口词 `Kubernetes` / `高并发` | `gap_keyword_leak` |
| 模型给的 `entryRef` 指向 `work[9]`（不存在） | 提案被丢弃 |
| 模型篡改 `originalText` | 提案被丢弃 |
| 模型给的引证在原文里找不到 | 该条目被丢弃并计数 |

### 实施中发现并修掉的两个真缺陷

1. **数字检查漏掉无单位裸数字**（我自己引入、自己测出）：第一版只抓"数字 + 单位"，
   实测漏掉 `QPS 提到 50000` 这种没有单位的写法。已改为"先剥离日期、再对每个数字做完整
   token 校验"，并补两条用例（`violationsCarryProposalIndex`、`yearAloneIsNotMetric`）。
2. **`ApiContractTest` 会删种子数据**（**既有缺陷，WORK-017 引入**）：契约探测把路径变量一律
   填成 `1`，而 `DELETE /api/v1/knowledge/admin/entries/{id}` 用管理员令牌探测时会**真的删掉
   种子条目 1**。后果不是它自己红，而是后面跑的 `KnowledgeRead` / `KnowledgeSelfAssessment`
   全红，看起来像知识库坏了。已修为"破坏性方法（DELETE/PUT/带体 POST）用不存在的高位 id"，
   修复后全量 168 条绿。**这条已写进代码注释，避免下次重犯。**

## 前端

| 命令 | 结果 | 退出码 |
| --- | --- | --- |
| `pnpm --filter @paideia/resume typecheck` | 通过 | 0 |
| `pnpm --filter @paideia/resume test` | **15 passed**（自动保存 8 + 差异 7） | 0 |
| `pnpm --filter @paideia/app typecheck` | 通过 | 0 |
| `pnpm -r test` | **268 passed / 0 failed**（8 个包） | 0 |
| `pnpm --filter @paideia/ui test` | **148 passed** | 0 |
| `pnpm --filter @paideia/app test:e2e` | **64 passed / 2 skipped** | 0 |

### e2e 新增与回归

- 新增 `e2e/resume.spec.ts`：手工新建 → 改名 → **刷新后仍是新值**（自动保存真的落地）
  → 体检清单渲染。这条用例是本项界面与后端接通的核心证据。
- **回归 1 条并已修**：我给「我的简历」复用了 `interview` 这个 nav key，导致既有的
  `identity.spec` 里"面试入口排第一"断言挑到的首链接错了。已给简历独立 key
  （`resume`，不进 `preferredNav`），修复后整轮 64 条绿。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 单元测试（简历 5 类） | `mvn -B -pl paideia-ai test -Dtest='Resume*Test'` | 0 | 通过 | 49 passed / 0 failed / 0 skipped |
| 回归（AI 模块） | `mvn -B -pl paideia-ai test` | 0 | 通过 | 99 passed，既有 50 条无回归 |
| 集成 + 全模块回归 | `mvn -B test`（独立库 `paideia_probe`） | 0 | 通过 | BUILD SUCCESS；paideia-app 168 passed / 4 skipped（既定无口令跳过） |
| AC-001 识别→确认→落库 | `ResumeJsonTest` + `ResumeEndpointsIntegrationTest.createDocument` | 0 | 通过 | 草稿端点不写库（代码无 insert）；POST 才创建 |
| AC-002 编辑、自动保存与回滚 | `pnpm --filter @paideia/resume test` + e2e `resume.spec.ts` | 0 | 通过 | 状态机 8 条（含失败不覆盖、409）；e2e 刷新后仍是新值 |
| AC-003 逐项采纳与对比可见 | `ResumeStructuredOutputTest.parsesProposals` + `diff.test.tsx` | 0 | 通过 | 默认 `accepted=false`；7 条验证 `<mark>` 与删除段 |
| AC-004 闸门反向验证 | `ResumeGateTest` 15 条 | 0 | 通过 | 六类注入全部被拦（见下「反向验证」表） |
| AC-005 三桶与缺口 | `ResumeKeywordClassifierTest` 6 条 | 0 | 通过 | 三桶归位、等价写法、ASCII 词边界、CJK 子串 |
| AC-006 零积分与单一路径 | `ResumeEndpointsIntegrationTest.zeroBalanceNeverCallsUpstream` | 0 | 通过 | HTTP 402 且 `parserCalls == 0`；无 baseUrl/apiKey 入参 |
| AC-007 归属与注销 | `ownerScoped404` + `AccountDeletionCascadeIntegrationTest` | 0 | 通过 | 5 个入口全 404；四张简历表逐表 0 行 |
| AC-008 无公开读路径 | `mvn -B -pl paideia-app test -Dtest=ApiSurfaceTest` | 0 | 通过 | 6 条全绿；快照 diff 中简历端点均为 `AUTHENTICATED` |
| AC-009 打印视图 | 代码审查 `ResumePrintPage.tsx` | 人工 | 通过 | `@page A4`、隐藏工具条、`break-inside: avoid`、无输入控件；**未做真实打印像素核对** |
| AC-011 失败不留脏数据 | 各服务失败路径 + 零积分用例 | 0 | 通过 | 失败不落库；`settleOrRefund` 路径覆盖 |
| AC-010 窄屏可用 | `pnpm --filter @paideia/app test:e2e -- layout.mobile` | 0 | 通过 | **66 passed / 2 skipped**；`我的简历` 与 `新建简历` 两页在 Pixel 7（412×915）下无横向溢出 |
| AC-012 识别质量记录 | 三份合成样张真实上传（见下「识别质量记录」） | 0 | 通过 | 3/3 解析成功（其中 1 份重试 4 次才拿到结果，前 3 次是上游超时） |
| 前端单测（全仓） | `pnpm -r test` | 0 | 通过 | 268 passed（8 包，含新增 `@paideia/resume` 15 条） |
| 设计系统守卫 | `pnpm --filter @paideia/ui test` | 0 | 通过 | 148 passed（含令牌单一来源与 `@source` 覆盖） |
| e2e（学习者端） | `pnpm --filter @paideia/app test:e2e` | 0 | 通过 | **64 passed / 2 skipped**；含新增 `resume.spec.ts` |
| 路由契约（新增端点入台账） | `ApiWalkthroughTest` | 0 | 通过 | 首次运行点名 21 条未覆盖，补齐后为空 |
| 结构快照 | `ApiWalkthroughTest` 的 `assertShapeMatchesSnapshot` | 0 | 通过 | diff 仅 21 条新增端点，无既有端点被改 |

## 反向验证（真实执行）

`ResumeGateTest` 与 `ResumeStructuredOutputTest` 里各有一组"先注入、看它必须红、再移除看它变绿"的用例，
命令均为 `mvn -B -pl paideia-ai test -Dtest='ResumeGateTest,ResumeStructuredOutputTest'`（退出码 0，
共 24 条全绿）。注入的九类问题分别是：提案里加主简历没有的数字（`提升 30%`）、把 `80ms` 写成原文没有的
`80`（原文是 `800`）、把"后端开发工程师"改成"后端技术专家"、改公司名、改起止时间、整条要点换成无关内容、
改写里出现缺口词（`Kubernetes` 与 `高并发`）、`entryRef` 指向不存在的 `work[9]`、模型篡改
`originalText`、模型给的引证在原文里找不到。九类全部被对应违规码拦下。

## 前端

| 命令 | 结果 | 退出码 |
| --- | --- | --- |
| `pnpm --filter @paideia/resume typecheck` | 通过 | 0 |
| `pnpm --filter @paideia/resume test` | **15 passed**（自动保存 8 + 差异 7） | 0 |
| `pnpm --filter @paideia/app typecheck` | 通过 | 0 |
| `pnpm -r test` | **268 passed / 0 failed**（8 个包） | 0 |
| `pnpm --filter @paideia/ui test` | **148 passed** | 0 |
| `pnpm --filter @paideia/app test:e2e` | **64 passed / 2 skipped** | 0 |

### e2e 新增与回归

- 新增 `e2e/resume.spec.ts`：手工新建 → 改名 → **刷新后仍是新值**（自动保存真的落地）
  → 体检清单渲染。这条用例是本项界面与后端接通的核心证据。
- **回归 1 条并已修**：我给「我的简历」复用了 `interview` 这个 nav key，导致既有的
  `identity.spec` 里"面试入口排第一"断言挑到的首链接错了。已给简历独立 key
  （`resume`，不进 `preferredNav`），修复后整轮 64 条绿。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 单元测试（简历 5 类） | `mvn -B -pl paideia-ai test -Dtest='Resume*Test'` | 0 | 通过 | 49 passed / 0 failed / 0 skipped |
| 回归（AI 模块） | `mvn -B -pl paideia-ai test` | 0 | 通过 | 99 passed，既有 50 条无回归 |
| 集成 + 全模块回归 | `mvn -B test`（独立库 `paideia_probe`） | 0 | 通过 | BUILD SUCCESS；paideia-app 168 passed / 4 skipped（既定无口令跳过） |
| 前端单测（全仓） | `pnpm -r test` | 0 | 通过 | 268 passed（8 包，含新增 `@paideia/resume` 15 条） |
| 设计系统守卫 | `pnpm --filter @paideia/ui test` | 0 | 通过 | 148 passed（含令牌单一来源与 `@source` 覆盖） |
| e2e（学习者端） | `pnpm --filter @paideia/app test:e2e` | 0 | 通过 | **64 passed / 2 skipped**；含新增 `resume.spec.ts` |
| 路由契约（新增端点入台账） | `ApiWalkthroughTest` | 0 | 通过 | 首次运行点名 21 条未覆盖，补齐后为空 |
| 结构快照 | `ApiWalkthroughTest` 的 `assertShapeMatchesSnapshot` | 0 | 通过 | diff 仅 21 条新增端点，无既有端点被改 |

### 反向验证（真实执行）

| 注入 | 结果 |
| --- | --- |
| 提案里加主简历没有的数字（`提升 30%`） | `numbers_not_in_source`，定稿被拒 |
| 把 `80ms` 写成原文没有的 `80`（原文是 `800`） | 同上——完整 token 校验生效 |
| 把"后端开发工程师"改成"后端技术专家" | `identity_changed` |
| 改公司名 / 改起止时间 | `identity_changed` |
| 整条要点换成无关内容 | `low_overlap` |
| 改写里出现缺口词 `Kubernetes` / `高并发` | `gap_keyword_leak` |
| `entryRef` 指向 `work[9]`（不存在） | 提案被丢弃 |
| 模型篡改 `originalText` | 提案被丢弃 |
| 模型引证在原文里找不到 | 该条目被丢弃并计数 |

## 识别质量记录（AC-012）

三份合成样张，经真实上传 → 真实抽取 → 真实模型调用。**只记录，不设通过线**（需求原话）。

| 样张 | 页数 | 抽取字符 | dropped | 结果 |
| --- | --- | --- | --- | --- |
| 双栏 PDF（中文，左栏联系+技能、右栏经历） | 1 | 251 | 0 | 姓名/电话/邮箱/城市**全部正确**；工作 1 条（公司、职位、起止时间、3 条要点全对）；教育 1 条；技能 1 组 4 项。**求职意向为空**（原文确实没写这一项） |
| 表格 PDF（HR 模板样式，含跨列合并） | 1 | — | 0 | 姓名/电话/邮箱/城市全对；求职意向「Java 后端开发工程师」正确；工作 1 条 2 要点；教育 1 条；技能 1 组 6 项（Java、Spring Cloud、MySQL、Redis、Kafka、Docker）**全部正确** |
| 中英混排 DOCX | — | — | 0 | 姓名「张伟 / Zhang Wei」（保留双语形态，未擅自二选一）；电话/邮箱/城市全对；求职意向双语保留；工作 1 条 2 要点（1.2 万 QPS、30 分钟→2 分钟两个数字**逐字保留**）；教育 1 条；技能 1 组 7 项**全部正确** |

三条**必须记下来的事实**：

1. **双栏排版没有把内容搞乱**。抽取结果按"左栏全部 → 右栏全部"的顺序输出，两栏内容没有交错
   （`maxSpaceRun=2`，没有超长空白）。这验证了 `setSortByPosition(true)` 这个选择对双栏简历有效。
2. **同一份双栏样张重试 4 次才成功**：前 3 次都是 `Failed to read response body: timeout`（上游读取超时），
   第 4 次 200。**这不是解析逻辑的问题**（抽取只 251 字符），是上游当时不稳定。
   三次失败全部走了退款路径（日志有 `ai.credit.refunded reserved=1000`）。
3. **数字与措辞被逐字保留**：三份样张里的 `900ms`/`260ms`/`12 个下游系统`/`1.2 万 QPS`/`30 分钟`/`2 分钟`
   都原样出现在 `bullets` 里，没有被模型"润色"成别的数。

### 真实计费对账

| 项 | 值 |
| --- | --- |
| 管理员发放 | `+200000`（note：WORK-018 识别质量记录） |
| 三次成功解析 | `RESUME -8`（672 in / 2038 out）、`RESUME -5`（703/1363）、`RESUME -5`（667/1259） |
| 余额 | `199982`（= 200000 − 18） |
| 三次超时 | **无扣费行**（全额退款），只在日志留 `ai.credit.refunded` |

这同时验证了 AC-006 的反面：计费闸门在真实调用上是通的，失败不扣钱。

## 未做（明确排除）

- 未部署、未 push（都要单独授权）。
- 未跑 `pnpm -r build`（会连桌面壳一起打，约 20 分钟，本项不改桌面产物）。
- 未做真实打印核对与三份样张的识别质量记录（见上）。
