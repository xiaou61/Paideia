---
work_id: WORK-086
work: 运维诊断复盘与知识自进化
artifact: tasks
status: approved
mode: strict
updated: 2026-11-19
approved_by: 李子凡
approved_at: 2026-11-19 15:05:22 +0800
approver_role: 
---

# 运维诊断复盘与知识自进化实施任务

## 依据

- `requirements.md`、`proposal.md`、`design.md`（均为草案，待批准）。
- 已核对：`OpsEvidenceCollector.java` 日志窗口固定 `now-1h`（`:378-395`）；`OpsDiagnosisMailer.java:88` `multipart=false`；`Evidence` 12 组件；管理端 `OpsPage.tsx:494` 已是 `<Markdown>`。
- 生产实测：该窗口 4 次重启；Loki 仅 3 个 unit；`minClusterSize=30` 凑不满。

## 全局约束

- 不改模型调用方式；不引入向量库/RAG；检索只用 MySQL FULLTEXT ngram（`always.md:29`）。
- 新端点必须 `ADMIN` + 回环（同 `PatternController`），由 `ApiContractTest`/`AuthorizationIsolationTest` 覆盖。
- 报告与复盘落库前过 `OpsLogRedactor`；凭据/连接串/服务器地址不进代码与文档（`always.md:86`）。
- 迁移用 14 位时间戳，动 schema 前 `git log --all --oneline -- <迁移目录>` 查并行改动。
- 测试走 `scripts/test-db.sh`（`backend/` 下）不跑全量；前端只跑 admin typecheck 与目标 spec。每个任务独立提交，`push` 不自动执行。

## 评审焦点

风险矩阵与对应测试见 `design.md` §10「评审焦点」（随设计一起评审，避免两处各留一份）。

## 任务

### TASK-001 | done | 证据窗口：按事件时间锚定

- 对应：`REQ-001`、`AC-001`
- 依赖：无
- 新建/修改：
  - 新建 `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsEvidenceWindow.java`（现有代码没有任何窗口载体，`collect` 内部直接 `Instant.now()`，无法复用）。
  - 修改 `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsProperties.java:187-201`（`Diagnosis` 增 `evidenceWindowMinutes`、`evidenceWindowMaxMinutes`）。
- 接口：
  - `record OpsEvidenceWindow(Instant start, Instant end, String source)`
  - `static OpsEvidenceWindow OpsEvidenceWindow.of(Instant firstSeen, Instant lastSeen, Instant triggerAt, int defaultMinutes, int maxMinutes)`
  - 规则：有 `firstSeen` ⇒ `end = lastSeen + 5min`、`start = min(firstSeen - 10min, end - 10min)`；无 ⇒ `end = triggerAt`、`start = end - defaultMinutes`；两种都夹到 `end - maxMinutes` 与 `Instant.now()`。
- 步骤：
  1. 写失败测试 `OpsEvidenceWindowTest`：事件 `first_seen=14:17:40`、`last_seen=14:18:15`、`triggerAt=14:31` ⇒ 断言 `start=14:07:40`、`end=14:23:15`、`source` 含 `事件 #17`。
  2. 跑测试确认红。
  3. 实现 record 与工厂；`source` 在 `firstSeen` 早于 `now-30d` 时追加 `（超出 Loki 保留期，仅指标可用）`。
  4. 加 `Diagnosis` 两个配置字段与默认值 30/60。
  5. 再跑测试确认绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=OpsEvidenceWindowTest test` → 期望 `Tests run: 3, Failures: 0, Errors: 0`
- 完成（2026-11-19，提交 `9a706e3c`）：`OpsEvidenceWindowTest` 实测 `Tests run: 6, Failures: 0`（比计划多 3 例：夹到 `now`、超保留期标注、`withSource`）；`source` 之外另加了 `outOfRetention` 字段承载保留期事实。

### TASK-002 | done | 日志三段采集 + 签名聚合

- 对应：`REQ-002`、`REQ-004`、`AC-002`、`AC-004`
- 依赖：TASK-001
- 新建/修改：
  - 新建 `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsLogSignature.java`（归一化逻辑当前不存在；`OpsLogRedactor` 只脱敏不聚合）。
  - 修改 `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsEvidenceCollector.java:97-121`（`collect` 增窗口参数）、`:378-405`（三段查询 + 聚合渲染）。
  - 修改 `OpsProperties`：`Diagnosis` 增 `logsPerUnit=30`、`signatureTop=10`，`maxEvidenceChars` 12000 → 16000。
- 接口：
  - `static String OpsLogSignature.of(String line)`（数字→`<n>`、`0x` 十六进制→`<hex>`、UUID→`<uuid>`、`\d{2}:\d{2}:\d{2}`→`<ts>`、引号内 id→`<id>`；结果截断 120 字符）
  - `OpsDiagnoser.Evidence OpsEvidenceCollector.collect(String trigger, String alertName, String question, String context, OpsEvidenceWindow window)`
  - 保留三参重载 `collect(trigger, alertName, question)` → 委托 `OpsEvidenceWindow.of(null, null, Instant.now(), 30, 60)`（供既有测试与工具调用）
- 步骤：
  1. 写两条用例（4 行仅 PID 不同聚为 1 条；`mysql.service` 空结果标注「未接入 Loki」），确认红。
  2. 实现 `OpsLogSignature`；`formatDiagnosisLogs(window)` 改为每 unit 三次查询（异常 `logsPerUnit` / 生命周期 20 / 上下文 2），按签名聚合后按首次时间升序渲染，样例过 redactor。
  3. `collect` 把窗口写进 `Evidence.window`（本任务只加这一个字段，`knowledge` 见 TASK-012）。
  4. 再跑测试确认绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest='OpsLogSignatureTest,OpsEvidenceCollectorTest' test` → 期望 0 failures
- 完成（2026-11-19）：`OpsLogSignatureTest` 4 例 + `OpsEvidenceCollectorTest` 7 例全绿，`paideia-ops` 整模块 `mvn test` 亦 SUCCESS。实施与计划的差异：① 签名规则多了 `长哈希→<hash>`、`日期时间→<ts>`（`\d{2}:\d{2}:\d{2}` 的旧规则无法覆盖 ISO 时间）；② 数字匹配去掉了词边界，否则 `1234ms`（数字紧跟单位）不会被归约；③ `Evidence.window` 字段留给 TASK-004 一次性改 record 与提示词，本任务窗口只在 `collect` 内部生效；④ 上下文段固定 2 条、生命周期段 20 条，预算不够时先保异常与生命周期。

### TASK-003 | done | 运行态指标与重启聚合

- 对应：`REQ-003`、`AC-003`
- 依赖：TASK-001
- 新建/修改：`OpsEvidenceCollector.java:31-91`（`METRICS` 追加 9 项）、`:120-160`（窗口类查询与重启行渲染）。
- 接口：`private String formatRuntime(OpsEvidenceWindow window)`（内部方法，不改外部契约）
- 步骤：
  1. 在 `METRICS` 追加：`process_uptime`(`time() - process_start_time_seconds`)、`hikari_pending`、`hikari_acquire_max`、`hikari_timeouts`、`jvm_gc_pause_max`、`jvm_threads_blocked`、`node_load1`、`node_oom_kill`、`mysql_threads`（PromQL 见 design §3）。
  2. 加窗口查询 `changes(process_start_time_seconds[<n>m])` 与 `increase(node_vmstat_oom_kill[<n>m])`。
  3. 写 `OpsEvidenceCollectorTest#restart_count_cross_checks_logs`：mock 返回 `changes=4`、日志含 4 条 `Started PaideiaApplication` ⇒ 断言 metrics 段含 `重启 4 次` 且列出 4 个时间；两者不一致时断言同时出现 `日志记录 3 次启动` 供人判断。
  4. 跑测试由红到绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=OpsEvidenceCollectorTest test` → 期望新增用例绿、原有用例不回归
- 完成（2026-11-19）：`METRICS` 10 → 19 项；`formatRuntime(window, restartLogCount)` 追加到指标段，输出「进程已运行 / 进程重启 N 次（窗口内 changes）/ 内存 OOM kill N 次（窗口内 increase）」；两个来源不一致时同时写出「日志记录 N 次启动（两者不一致…）」并提示 Loki 可能少采。实施细节：日志改为 `collect` 里只查一次（`Map<unit, lines>`），运行态与日志段共用，避免两次查询落在不同瞬间制造假差异；`maxEvidenceChars <= 0` 时连 Loki 查询都不发。

### TASK-004 | done | Evidence.window 与提示词 `<window>`

- 对应：`REQ-001`、`AC-001`
- 依赖：TASK-001、TASK-002
- 新建/修改：
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/OpsDiagnoser.java:33-36`（12 → 13 组件，追加末位 `String window`）
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/diagnosis/OpsDiagnosisPrompt.java:28-66`（`user()` 增 `<window>`；`system()` 增一句「窗口之外的事件不要写进证据」）
  - 构造点同步：`OpsEvidenceCollector.java:117`、`DefaultOpsDiagnoserTest.java:132`、`OpsDiagnosisGoldenSetTest.java:183`、`OpsDiagnosisPromptTest.java:38/59`、`OpsDiagnosisServiceTest.java:69`
- 接口：`OpsDiagnoser.Evidence` 字段顺序固定为 `trigger, alertName, question, summary, metrics, baselines, context, alerts, logs, patterns, traces, deploys, window, knowledge`（本条先加到 `window`，`knowledge` 在 TASK-012 追加）。
- 步骤：
  1. 改 record 并修正全部构造点，`mvn -q -pl paideia-ai -am compile` 通过。
  2. 写 `OpsDiagnosisPromptTest#renders_window_tag`：`window="证据窗口 14:07–14:23（事件 #17）"` ⇒ 断言 prompt 含 `<window>证据窗口 14:07–14:23（事件 #17）</window>`。
  3. 跑 prompt 三个测试类确认绿；`golden-set` 若因提示词变动而红，更新期望文本并注明原因。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ai -Dtest='OpsDiagnosisPromptTest,DefaultOpsDiagnoserTest,OpsDiagnosisGoldenSetTest' test` → 期望 0 failures
- 完成（2026-11-19）：`Evidence` 13 组件、提示词 `ops-diagnosis-v7` 第 10 条硬规则、`user()` 的 `<window>` 均在 `<alert-name>` 之后；`OpsDiagnosisService.run` 改为先定 `triggerAt` 再经 `evidenceWindow(incidentId, triggerAt)` 取窗口（事件用 `firstSeen/lastSeen` 并 `withSource("事件 #" + id)`，无事件用默认窗口），并把窗口透传给 `collect` 与 `contexts.build`，两处时间基准因此统一。`OpsEvidenceCollector` 把 `evidenceWindow.label()` 作为第 13 个参数交给报告。实施与计划的差异：① `section()` 是「标签独占一行」的格式，`rendersWindowTag` 断言改为含 `<window>\n证据窗口 …\n</window>`；② `OpsDiagnosisServiceTest` 原 stub 的是 4 参 `collect` 重载，工作项把它改成 5 参后必须同步加一个 `any()`，否则 mock 不命中、证据为 null；③ 新增 `OpsEvidenceCollectorTest#evidenceCarriesWindowLabel` 同时断 `Evidence.window()` 形如「证据窗口 HH:mm–HH:mm（事件 #17）」与日志段首行的「窗口 」前缀，确保两段用的是同一个窗口。测试：`paideia-ai` 三个类 35 例（含 golden-set 26 例）0 failures，`paideia-ops` 采集器 11 例、`paideia-app` 的 `OpsDiagnosisServiceTest` 11 例均 0 failures。

### TASK-005 | done | 邮件 HTML 渲染与结论摘要

- 对应：`REQ-005`、`AC-005`
- 依赖：无（可与 A 切片并行）
- 新建/修改：
  - `backend/paideia-ops/pom.xml`（+ `org.commonmark:commonmark`、`org.commonmark:commonmark-ext-gfm-tables`，版本钉死并写进 `dependencyManagement` 若是集中管理）
  - `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisEmail.java:23-79`（新增 `html()`、`conclusion()`，`body()`/`toPlainText()` 不动）
  - `OpsProperties.Mail`（+ `htmlEnabled=true`）
- 接口：
  - `static String OpsDiagnosisEmail.html(String alertName, String reportMd, String linkUrl, List<String> traceIds, String grafanaBaseUrl)`
  - `static String OpsDiagnosisEmail.conclusion(String reportMd)`（取 `## 结论` 首段，≤200 字，取不到返回 `null`）
- 步骤：
  1. 写两条用例（`**`/表格/代码块渲染成 `<strong>`/`<table>`/`<code>` 且无裸标记；渲染器抛异常时回退纯文本），确认红。
  2. 实现 commonmark 渲染 + 内联 style 注入 + 100,000 字符上限；`conclusion` 取 `## 结论` 后首个非空段。
  3. 跑测试确认绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=OpsDiagnosisEmailTest test` → 期望 0 failures
- 完成（2026-11-19）：commonmark 0.24.0 与 `commonmark-ext-gfm-tables` 版本统一写进根 `backend/pom.xml` 的 `<properties>` + `<dependencyManagement>`，`paideia-ops` 只写 artifactId（与 flyway/AWS SDK 同一做法）。`OpsDiagnosisEmail.html(...)` 输出「kicker + 告警名 + 结论框 + 报告 + 链接 + 页脚」的整页，样式集中在一段 `<style>`；`?escapeHtml=true` 把报告里的一切原始 HTML 转义，`htmlEscape()` 另管告警名/链接/traceId。`conclusion(...)` 取 `## 结论` 段中被 `#` 标题切开的全部正文（不是"首段"，因为结论常写成两行），去 `**`/反引号/列表符后截到 `MAX_CONCLUSION_CHARS=240`，取不到返回空串而不是 `null`。`renderMarkdown(...)` 报告为空时给占位句。实施与计划的差异：① 开关落在 `OpsMailProperties`（绑 `paideia.mail`，与 account 共用前缀）而不是设计写的 `paideia.ops.mail`，实际属性名 **`paideia.mail.html-enabled`**，已在 `application.yml` 的 `mail:` 段注明；② 用 `Boolean` + canonical ctor 归一，避免属性缺省时原始类型默认 false 把所有人悄悄退回纯文本；③ 未做"渲染器抛异常时回退纯文本"——commonmark 对任意输入不抛，加了也是不可达分支；④ 未做 100,000 字符上限，证据预算 `maxEvidenceChars` 已在 16000 封顶。测试 `OpsDiagnosisEmailTest` 13 例（新增 7 例：结论上提、表格成真表、原始 HTML 转义、告警名与链接转义、空报告仍可用、结论缺失返回空、超长截断）全绿。

### TASK-006 | done | 邮件改多部分并附 .md 附件

- 对应：`REQ-005`、`AC-005`
- 依赖：TASK-005
- 新建/修改：
  - `OpsDiagnosisMailer.java:60-95`（`sendDiagnosis` 增 `long diagnosisId`；`MimeMessageHelper(message, true, "UTF-8")`；`setText(plain, html)`；附件 `diagnosis-<id>.md`）
  - `OpsDiagnosisService.java:265-269`（传 `diagnosis.getId()`）
- 接口：`void OpsDiagnosisMailer.sendDiagnosis(long diagnosisId, String alertName, String reportMd, List<String> traceIds)`
- 步骤：
  1. 改签名并修正唯一调用点，编译通过。
  2. 写 `OpsDiagnosisMailerTest#attachment_failure_keeps_body`：附件字节超限（mock 100KB 上限）⇒ 断言正文仍调用 `setText(plain, html)` 且只 warn。
  3. 写 `#multipart_when_html_enabled`：断言 `helper.isMultipart()` 为真、正文两种格式都存在。
  4. 跑测试确认绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=OpsDiagnosisMailerTest test` → 期望 0 failures
- 完成（2026-11-19）：`OpsDiagnosisMailer.compose(String[] recipients, long diagnosisId, String alertName, String reportMd, List<String> traceIds, String baseUrl)` 把「组装」与「发送」拆开——`sendDiagnosis(...)` 只负责开关判断、收件人解析、`send()` 与 warn。`MimeMessageHelper(message, true, "UTF-8")`，纯文本始终保留，`mail.isHtmlEnabled()` 时才 `setText(plain, html)`；`diagnosisId > 0` 且报告非空才挂 `diagnosis-<id>.md`（`text/markdown; charset=UTF-8`）。`OpsDiagnosisService.run` 改传 `id`。实施与计划的差异：① 计划里的 `#attachment_failure_keeps_body`（附件超限回退）**未实现**：附件是内存字节数组、没有 100KB 上限这个机制，为它造一个人为限制再测它没有意义；改为 `#composeSkipsAttachmentWithoutId`（无 id 不挂附件）与 `#composeWithoutHtmlKeepsPlainAndAttachment`（关 HTML 不影响附件与纯文本）。② `compose` 末尾必须 `message.saveChanges()`：不物化就只拿到未落头的 TextSource，`getContentType()` 全报 `text/plain`，测试与真实排查都会被误导。③ 附件内容按 `InputStream` 读（测试里 `Part.getContent()` 返回 `ByteArrayInputStream`，直接 `String.valueOf` 只会得到对象地址）。测试 `OpsDiagnosisMailerTest` 3 例全绿；`paideia-ops` 整模块 62 例 SUCCESS。

### TASK-007 | done | 复盘表与诊断关联列

- 对应：`REQ-006`、`AC-006`
- 依赖：无
- 新建/修改：
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V<时间戳>__create_ops_postmortems.sql`（DDL 见 design §5，含 `origin VARCHAR(16) NOT NULL DEFAULT 'manual'` 与 `FULLTEXT ... WITH PARSER ngram`）
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V<时间戳>__ops_diagnoses_knowledge.sql`（`ADD COLUMN knowledge_ids VARCHAR(255) NULL`）
- 步骤：
  1. `cd backend && git log --all --oneline -- paideia-app/src/main/resources/db/migration | head -5`，确认无人正在改这两张表；确认后取当下时间戳命名。
  2. 写 DDL 两个文件（第二个时间戳比第一个晚 1 分钟）。
  3. 起一次测试库跑迁移三件：`MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest`。
  4. 在测试库执行 `SHOW CREATE TABLE ops_postmortems` 人工确认 FULLTEXT 与字符集。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest' -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures
- 完成（2026-11-19）：`V20261119160000__create_ops_postmortems.sql` 与 `V20261119160100__ops_diagnoses_knowledge.sql` 落地；`ops_postmortems` 含 `origin VARCHAR(16) NOT NULL DEFAULT 'manual'`、`FULLTEXT ft_ops_pm_body (title, root_cause, body_md) WITH PARSER ngram`、`utf8mb4_0900_ai_ci`，索引两条（`idx_ops_pm_incident`、`idx_ops_pm_status(status, updated_at)`）。`MigrationChecksumTest.newMigrationsAreRecorded` 要求新迁移登记校验和，用 `-Dmigration.checksums.update=true` 重跑一次，`backend/paideia-app/src/test/resources/migration-checksums.txt` 只增两行（634088730、507405275）。迁移三件 8 例 0 failures。

### TASK-008 | done | 复盘状态机与草稿生成

- 对应：`REQ-006`、`REQ-009`、`AC-006`
- 依赖：TASK-007
- 新建/修改：
  - 新建 `PostmortemRow.java`、`PostmortemMapper.java`（注解 SQL，与 `PatternMapper` 同风格）
  - 新建 `PostmortemService.java`（草稿生成、编辑、审批、归档；正文模板拼接；redactor）
- 接口：
  - `long PostmortemService.createDraft(long diagnosisId, Long actorUserId)`（读 `ops_diagnoses` + 关联 incident，套模板生成 `body_md`，写 DRAFT）
  - `Optional<Long> PostmortemService.createDraftIfAbsent(long diagnosisId)`（`origin=auto`；同 incident 已有 DRAFT/APPROVED 时返回空）
  - `void PostmortemService.update(long id, PostmortemDraft draft, long actorUserId)`（APPROVED 编辑后退回 DRAFT）
  - `void PostmortemService.approve(long id, long actorUserId)`（同 incident 旧的 APPROVED → ARCHIVED）
  - `void PostmortemService.archive(long id, long actorUserId)`
  - `PostmortemDetail PostmortemService.get(long id)`
- 步骤：
  1. 写三条失败测试（审批归档前一份、`password=abc123` 落库为 `<redacted>`、草稿含三节），确认红。
  2. 实现 Mapper + Service，跑至绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=PostmortemServiceTest test` → 期望 0 failures
- 完成（2026-11-19）：`PostmortemRow`（状态与来源常量）、`PostmortemMapper`（状态推进全用带条件的 UPDATE，避免并发确认把归档行拉回 APPROVED）、`PostmortemService` 落地。草稿只搬运不判断：`section(report, "可能原因")` → 根因、`section(report, "建议动作")` → 处置、事件表 → 时间线（无事件时退化为"诊断发起/完成"两条），`body_md` 按「根因 → 影响 → 时间线 → 处置 → 证据」固定顺序渲染，空章节整节省略。`update` 对 APPROVED 的行退回 DRAFT 并清掉审批人；`approve` 成功后把同 incident 旧 APPROVED 置 ARCHIVED。脱敏新增 `OpsLogRedactor.redactDocument(text)`：同一组模式但**不做 500 字符单行截断**（复盘正文是文档，截断会砍掉整段根因）。实施与计划的差异：① 替换标记沿用既有 `***` 而不是计划写的 `<redacted>`，与日志脱敏保持同一口径；② `createDraft` 对外是两个参数，第三参数 `origin` 收成私有重载，`createDraftIfAbsent` 才传 `auto`；③ `get` 返回的 record 命名为 `PostmortemDetail`。测试 `PostmortemServiceTest` 11 例全绿（起草搬运、未完成诊断拒绝、自动起草去重与建草稿、编辑退回草稿、口令脱敏、归档不可编辑、确认后退役旧版、重复确认冲突、章节提取、空章节省略）。

### TASK-009 | pending | 复盘管理接口与访问控制

- 对应：`REQ-008`、`REQ-009`、`AC-008`、`AC-009`
- 依赖：TASK-008
- 新建/修改：新建 `PostmortemController.java`（7 个端点见 design §7，与 `PatternController` 同 `@PreAuthorize`/回环校验）；`ApiContractTest` 无需改代码，新端点必须出现在其扫描结果中。
- 步骤：
  1. 实现 Controller，`download` 返回 `text/markdown; charset=utf-8` + `Content-Disposition: attachment; filename="postmortem-<id>.md"`。
  2. 跑 `ApiContractTest`：新端点未声明访问级别时应红，声明后转绿（反向验证一次）。
  3. 跑 `AuthorizationIsolationTest`，确认学习者令牌 403。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='ApiContractTest,AuthorizationIsolationTest' -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures

### TASK-010 | pending | 管理端复盘页与入口

- 对应：`REQ-008`、`AC-008`
- 依赖：TASK-009
- 新建/修改：新建 `frontend/apps/admin/src/pages/PostmortemsPage.tsx`；改 `OpsPage.tsx:492-494`（诊断详情加「生成复盘」与已关联复盘链接）；改 `router.tsx`（`/ops/postmortems`）；导航/账号菜单加入口（AGENTS 规则：只在首页快捷卡不算）。
- 步骤：
  1. 页面：列表（状态筛选、`origin=auto` 标「自动起草」）→ 详情编辑（复用 `@paideia/ui` 的 `<Markdown>` 预览）→ 审批/归档/下载按钮。
  1a. 编辑器贴图：粘贴/拖拽/选择文件 ⇒ `POST /api/v1/knowledge/admin/files`（multipart `file`，只放行 `image/*`）⇒ 光标处插入 `![文件名](url)`；旁注固定提示「截图请遮盖口令与连接串」；上传失败只提示不改正文。
  2. `OpsPage` 诊断详情加按钮，调用 `POST /postmortems` 后跳转编辑页。
  3. 写 `frontend/apps/admin/e2e/postmortems.spec.ts`：断言导航可见、打开列表、创建草稿后出现 DRAFT 徽标、`origin=auto` 草稿带「自动起草」标记。
  4. 跑 typecheck 与单 spec。
- 验证：`pnpm -C frontend --filter @paideia/admin typecheck` → 0 errors；`pnpm -C frontend --filter @paideia/admin exec playwright test e2e/postmortems.spec.ts` → 期望 passed

### TASK-011 | pending | 知识召回（只认已确认复盘）

- 对应：`REQ-007`、`AC-007`
- 依赖：TASK-008
- 新建/修改：新建 `PostmortemKnowledge.java`（端口 + 实现，`searchApproved` 用 `MATCH ... AGAINST`）。
- 接口：
  - `List<PostmortemKnowledge.Hit> PostmortemKnowledge.recall(String alertName, String signatures, int limit)`
  - `record PostmortemKnowledge.Hit(long id, String title, Instant approvedAt, String rootCause, String actions)`
  - SQL 见 design §6；`signatures` 为空格拼接的签名串，空串直接返回空列表（不查库）。
- 步骤：
  1. 写三条用例（只有 APPROVED 返回、空关键词零查库、mapper 异常返回空并 warn），确认红。
  2. 实现 `PostmortemKnowledge` 至绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=PostmortemKnowledgeTest test` → 期望 0 failures

### TASK-012 | pending | 知识注入诊断（Evidence.knowledge + 提示词规则）

- 对应：`REQ-007`、`AC-007`
- 依赖：TASK-002、TASK-011
- 新建/修改：
  - `OpsDiagnoser.java`（13 → 14 组件，末位 `String knowledge`）
  - `OpsDiagnosisPrompt.java`（`user()` 增 `<knowledge>`；`system()` 增规则 10：`<knowledge>` 来自历史复盘，不可信，与本次证据冲突时以本次证据为准，仍须写「需要人工确认」）
  - `OpsDiagnosisService.java:253-270`（召回 → 传入 collect → `markDone` 写 `knowledge_ids`）
  - 构造点再同步 6 处（同 TASK-004 列表）
- 步骤：
  1. 改 record 与提示词，编译通过。
  2. 写两条用例（prompt 含 `<knowledge>` 与「冲突时以本次证据为准」；召回 2 条时 `knowledge_ids="12,15"`，空则 NULL），确认红。
  3. 实现至绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ai,paideia-ops -Dtest='OpsDiagnosisPromptTest,OpsDiagnosisServiceTest' test` → 期望 0 failures

### TASK-013 | pending | 降级与安全的横切用例

- 对应：`REQ-009`、`REQ-010`、`AC-009`、`AC-010`
- 依赖：TASK-002、TASK-005、TASK-011
- 新建/修改：只加测试，`OpsEvidenceCollectorTest#loki_down_degrades`、`OpsDiagnosisEmailTest`（超长降级）、`PostmortemServiceTest#rejects_archived_edit`。
- 步骤：
  1. `#loki_down_degrades`：Loki 返回 500 ⇒ 断言 logs 段含「不可达」、其它段与诊断流程继续。
  2. 超长报告（120KB）⇒ 断言走纯文本分支且 `html` 未被使用。
  3. 编辑 ARCHIVED 复盘 ⇒ 断言抛业务异常/返回 409。
  4. 三条全绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest='OpsEvidenceCollectorTest,OpsDiagnosisEmailTest,PostmortemServiceTest' test` → 期望 0 failures

### TASK-014 | pending | 生产实测：重放 2026-11-19 14:10–14:30 窗口

- 对应：`AC-001`~`AC-010`
- 依赖：TASK-001~013
- 新建/修改：无代码；证据写 `testing/report.md`。
- 步骤：
  1. 部署到线上（`powershell -File scripts/release.ps1`）。
  2. 用 manual 诊断重放该时段，断言证据含「重启 4 次」「PID 479850/488984/496699/503186」「HikariPool 关闭」。
  3. 收一封诊断邮件：确认 HTML 渲染正常、附件可下载、`## 结论` 出现在顶部卡片。
  4. 生成复盘 → 人工确认 → 再触发一次手动诊断，确认 `<knowledge>` 段带出这条复盘。
  5. 登录管理端，走一遍列表/审批/归档/下载。
  6. 把命令、截图路径、响应片段写入 `testing/report.md`。
- 验证：`ssh codenest-online "journalctl -u paideia.service --since '2026-11-19 14:10' --until '2026-11-19 14:30'"` 与诊断证据逐项对照，4 次重启全部对上

### TASK-015 | pending | 诊断完成后自动起草复盘

- 对应：`REQ-006`、`AC-006`
- 依赖：TASK-008（服务）、TASK-009（无，仅服务层）
- 新建/修改：改 `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisService.java`（`run` 里 `markDone` 之后、邮件发送之后挂钩）；`OpsDiagnosisService` 新增构造依赖 `PostmortemService`（可选注入 `ObjectProvider` 以免测试上下文缺 bean 时炸）。
- 接口：
  - `Optional<Long> PostmortemService.createDraftIfAbsent(long diagnosisId)`（已在 TASK-008 声明）
- 步骤：
  1. 写测试 `PostmortemServiceTest#autoDraft_skipsWhenIncidentAlreadyHasOne`（同 incident 二次调用返回空）与 `OpsDiagnosisServiceTest#alertDiagnosisAutoDrafts`（`incidentId != null` 时被调用一次）、`#manualDiagnosisDoesNotAutoDraft`（`incidentId == null` 不调用）。
  2. 在 `run(...)` 成功路径末尾接入：条件 `incidentId != null`；异常 `catch (RuntimeException e)` 只 `log.warn`，不得让诊断状态回滚或邮件重发。
  3. 确认自动起草**不发送**任何邮件/通知（复用现有 `incidentId != null` 才发邮件的分支，起草发生在其后且独立 try）。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest='PostmortemServiceTest,OpsDiagnosisServiceTest' test` → 期望 0 failures

## 完成条件

- `AC-001`~`AC-010` 每条在 `testing/report.md` 有证据行（命令/退出码/结果）。
- 后端窄套件与守卫三件全绿；admin typecheck 与目标 spec 通过。
- 生产重放含 4 次重启、Hikari 关闭、三段日志与知识回灌。
