---
artifact: test-report
work_id: WORK-032
work: MySQL 实训沙箱与题库
status: passed
evidence: required
workflow: full
created: 2026-10-22
updated: 2026-10-22
verified_commit: f7a3c47
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:54 +0800
---

# WORK-032 验证报告

**结论先说**：**16 条验收标准全部有实测证据**。

其中 AC-002 与 AC-007 只能在**真实沙箱**上验（本机没有 Docker，集成测试里的 `CodeRunner`
是桩），因此写成 `scripts/verify_sql_live.py` 对着已部署实例跑，两条都通过并做了反向验证。
详见验收矩阵与「验证环境」。

AC-013 在写本报告时发现"只有结构保证"，已补成真断言并做反向验证。

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25，`scripts/test-db.sh` 独占库（`paideia_run_1022110124_54221` / `paideia_run_1022110245_54284`，跑完自动删） |
| 前端 | `frontend/packages/ui`，vitest 5.0.3 + happy-dom |
| 线上 | 2026-10-21 部署三次（全量 / `--SkipWeb` / 全量），镜像 `paideia-sandbox-mysql:8.0@sha256:e2eafb6e…`；2026-10-22 在服务器上确认 Docker `29.1.3` 与该镜像在位 |
| 上游 | 真实 DeepSeek（线上判题链路实测，非桩） |

## 验证结果

（逐条命令的实际输出见下方「验收矩阵」的证据列；这里只列整体规模。）

- **后端内环**（`scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`）：退出码 0，
  **261 运行 / 0 失败 / 13 跳过**，11 模块全 SUCCESS，61 秒。独占库 `paideia_run_1022110124_54221`，跑完自动删。
- **模块边界与迁移校验**（`… -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest …`）：
  退出码 0，**9 运行 / 0 失败 / 0 跳过**，27 秒。
- **沙箱端点集成测试**（`… -pl paideia-app -am -Dtest=SandboxIntegrationTest …`）：退出码 0，
  **9 运行 / 0 失败 / 0 跳过**，22 秒（含本次新增的 AC-013 一条）。
- **前端 ui 包全量**（`vitest run`）：退出码 0，**209 passed / 12 files**。
- **SQL 题型块**（`vitest run src/markdown/renderers/blocks.test.tsx`）：退出码 0，30 passed
  （含本次新增 3 条 SQL 题用例）。
- **令牌与扫描路径**（`vitest run src/tokens.test.ts src/styles/source-coverage.test.ts`）：退出码 0，3 passed。
- **e2e 打线上**（`pnpm --filter @paideia/app test:e2e`，`E2E_BASE_URL` 指向部署实例）：退出码 0，
  **16 passed / 1 skipped**（2026-10-21）。
- **AC-002 / AC-007 真实沙箱验证**（`python scripts/verify_sql_live.py`，指向部署实例）：
  退出码 0，两条均通过（真容器，非桩）；反向验证见验收矩阵。

**13 条跳过逐条核过**（跳过不等于通过）：`AgentLiveConversationTest` 2 条、
`LayaContentClassifierLiveTest` 3 条、`DigestLiveProbeTest` 4 条、`DigestSummarizerLiveTest` 3 条、
`DigestWeeklyPromptLiveTest` 1 条 —— 全部是「未配置真实上游凭据就跳过」的既有设计，
与 WORK-032 无关，没有一条是本项该跑的。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 `sql` 作为第三种语言，未知语言仍拒绝 | `scripts/test-db.sh mvn -B -pl paideia-sandbox -Dtest=DockerCommandTest test` | 0 | passed | 10 条全过，含「语言白名单按镜像可用性给出」「配置了 sql 镜像才出现 sql」；线上 `/api/v1/sandbox/status` 的 `languages` 含 `sql` |
| AC-002 一次运行在干净实例里完成，前后不共享 | `python scripts/verify_sql_live.py`（真实沙箱） | 0 | passed | 第一次 `CREATE TABLE probe_zz` + 插入 42 → 读到 `[["42"]]`；第二次查同一张表 → `ERROR 1146 … Table 'practice.probe_zz' doesn't exist`，证明实例已销毁、状态未跨次保留 |
| AC-003 返回结构化结果集（列名 + 行值） | `scripts/test-db.sh mvn -B -pl paideia-sandbox -Dtest=SqlResultCodecTest test` | 0 | passed | 7 条全过：单结果集含 NULL 与定点数尾零、多 XML 文档按 resultset 切段、空结果集零行、超行数上限整批作废 |
| AC-004 三道上限（库层超时 / 墙钟硬杀 / 行数字节） | `scripts/test-db.sh mvn -B -pl paideia-sandbox -Dtest=DockerCommandTest test` | 0 | passed | `sql命令同样加固但资源上限单独一档`：`--memory=512m` `--cpus=1` `--pids-limit=128`；`sql脚本显式收紧文件读写开关`：`--secure-file-priv` + `--pid-file=/tmp/data/mysqld.pid` |
| AC-005 允许多语句并返回多个结果集 | 线上手验（2026-10-21） | 人工 | passed | `BEGIN` 内读到 `1.00`，`ROLLBACK` 后回到 `199.00`；`SqlResultCodecTest#BEGIN与ROLLBACK不产生结果集所以序号连续但缺项` 覆盖序号口径 |
| AC-006 结果按语句顺序排列 | `scripts/test-db.sh mvn -B -pl paideia-sandbox -Dtest=SqlResultCodecTest test` | 0 | passed | `多个XML文档首尾相接按resultset切段` 断言顺序；线上多语句实测顺序与语句一致 |
| AC-007 硬编码答案在隐藏数据集上被判错 | `python scripts/verify_sql_live.py`（真实沙箱） | 0 | passed | 取参考解在公开集的真实结果（ann=2 / bob=1 / cid=0）原样写成 `UNION ALL` 字面量，在隐藏集上算出的哈希与 `expectHash[orders-hidden-a]` **不一致**（判错）。反向验证：把目标数据集改成 `orders-public` → 脚本报「硬编码竟然判对了」，恢复后转绿 |
| AC-008 每题至少 2 个数据集（1 公开 + 1 隐藏） | `python -c` 逐题读 `content/courses/mysql/*.md` 的 `dataset` 字段 | 0 | passed | 11 道可执行题全部为 `['orders-public','orders-hidden-a']` |
| AC-009 12 关每关至少一道可执行题 | `python scripts/sql_cases.py --check` + `python scripts/verify_course.py --dir content/courses` | 0 | passed | 12 道题通过；50 个课程文件含代码执行全通过 |
| AC-010 期望值由参考解真跑生成 | `python scripts/sql_cases.py --check` | 0 | passed | 门禁跑 reference 算哈希，题面只带 `expectHash`，无一处泄漏 reference/antiPatterns |
| AC-011 自由练习不写任何进度 | `pnpm --filter @paideia/app test:e2e`（`sql-practice.spec.ts`） | 0 | passed | 用**网络层证据**：监视整个练习过程，`PUT /api/v1/learning/*` 一个都没发出（不是查条数） |
| AC-012 沙箱不可用时降级为只读题面 | `vitest run src/markdown/renderers/blocks.test.tsx` | 0 | passed | 新增用例：`ready: false` 时不渲染运行按钮，显示「暂时无法运行…暂时只能阅读」 |
| AC-013 `code_runs` 有 `language='sql'` 且不含源码 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=SandboxIntegrationTest test` | 0 | passed | 新增 `sqlRunAuditsWithoutSource`：9 条全过。断言审计行 `language='sql'`、`code_length` 等于源码长度、`code_hash` 为 64 位且**不含 `customers` 字样**。反向验证：把断言改成 `java` → 红（`expected: "java" but was: "sql"`），恢复后转绿 |
| AC-014 前端判题与门禁脚本同一语料同一结论 | `python scripts/check_sql_judge_parity.py` + `vitest run src/markdown/renderers/sql-judge.test.ts` | 0 | passed | 24 条语料两侧无差异；TS 侧 24 条逐条断言（含规范序列化） |
| AC-015 SQL 日配额 20 次独立于其它语言 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=SandboxIntegrationTest test` | 0 | passed | 线上 `/api/v1/sandbox/status` 的 `sqlDailyLimit=20`；`SandboxQuotaService` 按 `language` 分别 `countSince`，配额耗尽用例在同一次集成跑里通过 |
| AC-016 多语句事务语义真实生效 | 线上手验（2026-10-21） | 人工 | passed | `BEGIN` 内读到 `1.00`，`ROLLBACK` 后回到 `199.00`——事务语义真实生效，不是文本匹配 |

## 失败与未验证项

### 1. AC-002 / AC-007 已在真实沙箱上验证（不再是缺口）

这两条原本没有断言，原因很具体：**本机没有 Docker**（`docker: command not found`），
而 `SandboxIntegrationTest` 里 `CodeRunner` 是桩——它验的是控制器那一层，验不了"容器真的起、
真的销毁"和"隐藏数据集真的在服务端"。

补法是 `scripts/verify_sql_live.py`：对着**已部署实例**跑，两条都是真容器：

- **AC-002**：第一次建 `probe_zz` 并读到 42，第二次查同一张表报
  `Table 'practice.probe_zz' doesn't exist`。这条测的是"状态没跨次保留"，比"命令里有 `--rm`"
  更接近要证明的事。
- **AC-007**：把参考解在公开集的真实结果（ann=2 / bob=1 / cid=0）原样写成 `UNION ALL` 字面量，
  在隐藏集上算出的哈希与 `expectHash` 不一致。**硬编码在隐藏集上判错，说明隐藏集真的起作用。**

两条都做了反向验证：把 AC-007 的目标数据集改成 `orders-public`，脚本立刻报
「硬编码在隐藏数据集上竟然判对了——隐藏集没有起作用」，恢复后转绿。

**注意**：这个脚本要凭据、且要求沙箱开着，所以**不放进常规测试**，需要时手动跑：

```
PAIDEIA_BASE_URL=… PAIDEIA_LEARNER_IDENTIFIER=… PAIDEIA_LEARNER_PASSWORD=… \
  python scripts/verify_sql_live.py
```

改容器生命周期、数据集内容或判题口径的人应当重跑它。

### 2. 部署后才暴露的两个真缺陷（已修 + 已加回归）

两条都是「部署后才暴露、本地测不出」：

- **SQL 容器在 `--read-only` 下静默卡死**。本地测试都没加 `--read-only`，mysqld 写不了
  `/var/run/mysqld` 时不报错也不退出，只是挂住（线上表现是每次 20 秒超时）。
  修法：`--pid-file` / `--log-error` 指到 tmpfs + `--mysqlx=0`；回归在
  `DockerCommandTest#sql脚本显式收紧文件读写开关`（提交 `370af1c`）。
- **`crypto.subtle` 在 http 下是 `undefined`**。本项目不配 HTTPS，而 dev server 跑在 localhost
  （安全上下文），线上判题直接抛错。修法：换纯 TS SHA-256，用 Node `createHash` 逐条对照
  13 个样本；回归在 `sha256.test.ts`（提交 `f01b1b2`）。

### 3. TASK-010 的 e2e 级验收未覆盖

关卡正文里真跑沙箱拿结果集这条路，**只有线上手验过一次**（2026-10-21，第 02 关正确 SQL →
两数据集通过并上报进度）。e2e 没覆盖，因为线上不保证沙箱开着，本地起沙箱成本高。
替代证据是 `blocks.test.tsx` 三条用例（注入假沙箱，验判题结果→进度接线）。
**这是本项在 e2e 级别唯一没达成的验收。**

## 剩余风险

1. **AC-002 / AC-007 的证据来自线上手动脚本，不在 CI 里**：`verify_sql_live.py` 要凭据、
   要沙箱开着，所以不随常规测试跑。改动容器生命周期或数据集结构时**必须手动重跑**，
   否则这两条会在无人察觉的情况下失效。
2. **SQL 判题依赖客户端算哈希**：服务端不认识"正确答案"（这是刻意的设计），但代价是
   判题逻辑一旦被绕过（改前端），用户能自己给自己判对。当前判断是可接受——进度只影响
   自己的学习记录，不进任何对外统计。
3. **线上 SQL 镜像的 digest 是硬编码在配置里的**：换镜像要改 `application.yml` 并重启，
   没有自动跟随。

---

## 附：本报告未覆盖的既有问题

跑全量前端单测时发现两处**与 WORK-032 无关**的遗留红测，已在 2026-10-22 一并修掉
（`f10b859`）：

- `tokens.test.ts`：`AdminUsersPage.tsx` 用 `bg-black/40` 绕过令牌（WORK-030 遗留）
- `source-coverage.test.ts`：`packages/camp/src` 没登记进 `@source`（WORK-029 遗留，
  camp 包独有的 Tailwind 类名一直静默不生成）
