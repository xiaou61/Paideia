---
work_id: WORK-086
work: 运维诊断复盘与知识自进化
artifact: tasks
status: draft
mode: strict
updated: 2026-11-19
---

# 运维诊断复盘与知识自进化实施任务

## 依据

- `requirements.md`、`proposal.md`、`design.md`（均为草案，待批准）。
- 已核对的现状：`OpsEvidenceCollector.java` 日志窗口固定 `now-1h`（`:378-395`）、`OpsDiagnosisMailer.java:88` 用 `new MimeMessageHelper(message, false, "UTF-8")`、`OpsDiagnoser.Evidence` 12 组件、迁移目录 `backend/paideia-app/src/main/resources/db/migration/`、管理端 `OpsPage.tsx:494` 已用 `<Markdown>`。
- 生产实测：该窗口 4 次重启；Loki 仅 3 个 unit；`minClusterSize=30` 凑不满。

## 全局约束

- 不改模型调用方式；不引入向量库/RAG；检索只用 MySQL FULLTEXT ngram（`always.md:29`）。
- 新端点必须 `ADMIN` + 回环（同 `PatternController`），由 `ApiContractTest`/`AuthorizationIsolationTest` 覆盖。
- 报告与复盘落库前过 `OpsLogRedactor`；凭据/连接串/服务器地址不进代码与文档（`always.md:86`）。
- 迁移用 14 位时间戳；动 schema 前先 `git log --all --oneline -- <迁移目录>` 查并行改动。
- 测试一律走 `scripts/test-db.sh`（`backend/` 下执行）不跑全量；前端只跑 admin typecheck 与目标 spec。
- 每个任务独立提交；`push` 不自动执行。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 事件窗口与诊断触发错位（诊断晚到，取到新日志） | TASK-001 | `OpsEvidenceWindowTest#prefers_incident_first_seen` |
| 窗口超出 Loki 720h 保留期（历史事件补诊断） | TASK-001 | `OpsEvidenceWindowTest#out_of_retention_is_marked` |
| 同一行日志重复 40 次淹没有效信息 | TASK-002 | `OpsLogSignatureTest#collapses_repeated_lines` |
| 某 unit 根本没接入 Loki，被静默当成「无异常」 | TASK-002 | `OpsEvidenceCollectorTest#unit_not_ingested_is_stated` |
| Loki 5xx/超时导致整次诊断失败 | TASK-002 | `OpsEvidenceCollectorTest#loki_down_degrades` |
| `changes(process_start_time_seconds)` 与日志重启次数不一致 | TASK-003 | `OpsEvidenceCollectorTest#restart_count_cross_checks_logs` |
| 报告里出现 `**` 与表格管道符（当前邮件就是这个症状） | TASK-005 | `OpsDiagnosisEmailTest#html_has_no_raw_markdown` |
| commonmark 渲染异常或超大报告导致邮件发不出 | TASK-005 | `OpsDiagnosisEmailTest#html_failure_falls_back_to_plain` |
| 附件失败或 25MB 超限导致正文丢失 | TASK-006 | `OpsDiagnosisMailerTest#attachment_failure_keeps_body` |
| 同一事件确认两份复盘，知识库里出现互相矛盾的结论 | TASK-008 | `PostmortemServiceTest#approve_archives_previous` |
| 草稿被直接当知识使用（未经人工确认） | TASK-011 | `PostmortemKnowledgeTest#only_approved_returned` |
| 复盘正文含数据库口令被写入库与邮件 | TASK-008 | `PostmortemServiceTest#redacts_secrets` |
| 学习者令牌调复盘端点拿到 200 | TASK-009 | `AuthorizationIsolationTest#learner_cannot_touch_postmortems` |
| 知识召回 SQL 在空关键词时扫全表或语法报错 | TASK-011 | `PostmortemKnowledgeTest#empty_keyword_returns_empty` |
| 前端未加导航入口，页面直达但没人找得到 | TASK-010 | `e2e/postmortems.spec.ts` 断言导航可见 |

## 任务

### TASK-001 | pending | 证据窗口：按事件时间锚定

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

### TASK-002 | pending | 日志三段采集 + 签名聚合

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

### TASK-003 | pending | 运行态指标与重启聚合

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

### TASK-004 | pending | Evidence.window 与提示词 `<window>`

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

### TASK-005 | pending | 邮件 HTML 渲染与结论摘要

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

### TASK-006 | pending | 邮件改多部分并附 .md 附件

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

### TASK-007 | pending | 复盘表与诊断关联列

- 对应：`REQ-006`、`AC-006`
- 依赖：无
- 新建/修改：
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V<时间戳>__create_ops_postmortems.sql`（DDL 见 design §5，含 `FULLTEXT ... WITH PARSER ngram`）
  - 新建 `backend/paideia-app/src/main/resources/db/migration/V<时间戳>__ops_diagnoses_knowledge.sql`（`ADD COLUMN knowledge_ids VARCHAR(255) NULL`）
- 步骤：
  1. `cd backend && git log --all --oneline -- paideia-app/src/main/resources/db/migration | head -5`，确认无人正在改这两张表；确认后取当下时间戳命名。
  2. 写 DDL 两个文件（第二个时间戳比第一个晚 1 分钟）。
  3. 起一次测试库跑迁移三件：`MigrationVersionFormatTest`、`MigrationChecksumTest`、`MigrationSeedOverlapTest`。
  4. 在测试库执行 `SHOW CREATE TABLE ops_postmortems` 人工确认 FULLTEXT 与字符集。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest='MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest' -Dsurefire.failIfNoSpecifiedTests=false test` → 期望 0 failures

### TASK-008 | pending | 复盘状态机与草稿生成

- 对应：`REQ-006`、`REQ-009`、`AC-006`
- 依赖：TASK-007
- 新建/修改：
  - 新建 `PostmortemRow.java`、`PostmortemMapper.java`（注解 SQL，与 `PatternMapper` 同风格）
  - 新建 `PostmortemService.java`（草稿生成、编辑、审批、归档；正文模板拼接；redactor）
- 接口：
  - `long PostmortemService.createDraft(long diagnosisId, Long actorUserId)`（读 `ops_diagnoses` + 关联 incident，套模板生成 `body_md`，写 DRAFT）
  - `void PostmortemService.update(long id, PostmortemDraft draft, long actorUserId)`（APPROVED 编辑后退回 DRAFT）
  - `void PostmortemService.approve(long id, long actorUserId)`（同 incident 旧的 APPROVED → ARCHIVED）
  - `void PostmortemService.archive(long id, long actorUserId)`
  - `PostmortemDetail PostmortemService.get(long id)`
- 步骤：
  1. 写三条失败测试（审批归档前一份、`password=abc123` 落库为 `<redacted>`、草稿含三节），确认红。
  2. 实现 Mapper + Service，跑至绿。
- 验证：`cd backend && scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=PostmortemServiceTest test` → 期望 0 failures

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
  1. 页面：列表（状态筛选）→ 详情编辑（复用 `@paideia/ui` 的 `<Markdown>` 预览）→ 审批/归档/下载按钮。
  2. `OpsPage` 诊断详情加按钮，调用 `POST /postmortems` 后跳转编辑页。
  3. 写 `frontend/apps/admin/e2e/postmortems.spec.ts`：断言导航可见、打开列表、创建草稿后出现 DRAFT 徽标。
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

## 完成条件

- `AC-001`~`AC-010` 每条在 `testing/report.md` 有证据行（命令/退出码/结果）。
- 后端窄套件与守卫三件全绿；admin typecheck 与目标 spec 通过。
- 生产重放含 4 次重启、Hikari 关闭、三段日志与知识回灌。
