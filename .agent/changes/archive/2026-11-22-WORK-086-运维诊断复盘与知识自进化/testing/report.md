---
work_id: WORK-086
work: 运维诊断复盘与知识自进化
artifact: test-report
status: passed
evidence: required
created: 2026-11-19
updated: 2026-11-19
---

# 验证报告：运维诊断复盘与知识自进化

结论：`AC-001`~`AC-010` 全部有证据；后端窄套件、守卫三件、管理端 typecheck 与目标 spec 全绿；
线上跑通「告警 → 诊断 → 邮件 → 自动起草复盘 → 知识回灌」整条链路。
生产验证期间发现并修复了 2 个只在真实数据下才暴露的缺陷（§4）。

## 验证环境

- 代码：`master`，实施提交 `7d3696c7`…`eaac292f`（含验收期修复 `23ecdd24`、`eaac292f`）。
- 本机：Windows，`scripts/test-db.sh` 指向服务器测试库 `paideia_e2e`（共用库，同一时刻只跑一轮）。
- 线上：`36.212.5.130:8081`，部署 `powershell -File scripts/release.ps1` 两次（97.2s、99.7s，health 32–39s）。
- 数据库：线上 `paideia` 库，`ops_postmortems` / `ops_diagnoses.knowledge_ids` 为本次新增。
- 可观测性：Loki 3.7.9（保留 720h）、Prometheus（保留 30d）、Tempo。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 窗口锚定与越界标注 | `scripts/test-db.sh mvn -B -o -pl paideia-ops -am -Dtest=OpsEvidenceWindowTest` | 0 | passed | `Tests run: 6, Failures: 0`；线上 #24/#27 证据按窗口算 |
| AC-002 三段日志 | `-Dtest=OpsEvidenceCollectorTest` | 0 | passed | `Tests run: 13`（`runsThreeSegmentsPerUnit`、`queriesUseEvidenceWindow`） |
| AC-003 运行态与重启交叉 | `-Dtest=OpsEvidenceCollectorTest` | 0 | passed | `restart_count_cross_checks_logs`、`runtimeQueriesArePinnedToAppJob`；线上 #27「重启 3 次 / 日志 2 次启动」 |
| AC-004 签名聚合 | `-Dtest=OpsLogSignatureTest` | 0 | passed | `Tests run: 4, Failures: 0` |
| AC-005 邮件 HTML 与附件 | `-Dtest=OpsDiagnosisEmailTest,OpsDiagnosisMailerTest` | 0 | passed | 13 + 4 例；线上 `ops.mail.sent alert=WORK-086-验收-自动诊断 diagnosisId=28 recipients=1` |
| AC-006 复盘状态机与自动起草 | `-Dtest=PostmortemServiceTest` | 0 | passed | 11 例；线上 #1 manual→APPROVED、#2 auto→DRAFT |
| AC-007 知识闭环 | `-Dtest=PostmortemKnowledgeTest` | 0 | passed | 4 例；线上 `ops_diagnoses#27.knowledge_ids=1`，报告引用「复盘 #1」 |
| AC-008 七端点契约与权限 | `-Dtest=ApiContractTest,AuthorizationIsolationTest` | 0 | passed | 10 + 9 例；学习者令牌 403 |
| AC-009 安全与脱敏 | `-Dtest=OpsLogRedactorTest,PostmortemServiceTest` | 0 | passed | `redactDocument` 不截断但替换敏感串；新增文件无凭据 |
| AC-010 降级 | `-Dtest=OpsEvidenceCollectorTest,OpsDiagnosisMailerTest,PostmortemServiceTest` | 0 | passed | `lokiServerErrorDegrades`、`oversizedReportFallsBackToPlain`、`update_rejectsArchived` |
| 迁移三件 | `-Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest` | 0 | passed | 8 例；`V20261119160000`、`V20261119160100` 已登记校验和 |
| ops 模块全量 | `scripts/test-db.sh mvn -B -o -pl paideia-ops -am test` | 0 | passed | 82 例 0 failures |
| ai 模块全量 | `scripts/test-db.sh mvn -B -o -pl paideia-ai -am test` | 0 | passed | 457 例 0 failures（14 skipped） |
| 管理端类型 | `pnpm --filter @paideia/admin typecheck` | 0 | passed | 0 error |
| 管理端 e2e（本地） | `E2E_BASE_URL=http://localhost:5174 playwright test e2e/postmortems.spec.ts` | 0 | passed | 2 passed |
| 管理端 e2e（线上） | `E2E_BASE_URL=http://36.212.5.130:8081 E2E_ADMIN_PATH=/admin/index.html` | 0 | passed | 2 passed（真实数据：1 条 APPROVED 复盘） |

### 线上链路（2026-11-19 16:52–17:24）

- **#24**（manual，16:52）：DONE，4446 字符。结论点名「16:48:36 `GracefulShutdown`（PID 716467）→
  16:48:42 新进程 PID 838104 → 16:49:12 Tomcat 监听 → 16:49:13 启动完成 33.495s」。
- **#25/#26**（manual）：FAILED，`模型输出被长度上限截断` ⇒ 见 §4 缺陷 2。
- **#27**（manual，修复后 17:12）：DONE，5082 字符、6235 tokens。证据「进程已运行 0 分钟」「进程重启 3 次」
  「日志记录 2 次启动（两者不一致…）」；正文引用「`<knowledge>` 复盘 #1（16:48，APPROVED）」并声明不能当本次结论。
- **#28**（Grafana webhook，`WORK-086-验收-自动诊断`，17:23）：DONE，4149 字符、4563 tokens。
  日志 `ops.mail.sent ... recipients=1`、`created postmortem #2 from diagnosis #28 (auto)`。

知识回灌落库（`ops_diagnoses`）：

```
id  incident_id  status  knowledge_ids  len
24  NULL         DONE    NULL           7420
25  NULL         FAILED  NULL           NULL   -- 4096 截断，见 §4
26  NULL         FAILED  NULL           NULL   -- 同上
27  NULL         DONE    1              8344   -- 召回复盘 #1
28  19           DONE    NULL           4149   -- 关键词不重合，未召回（预期）
```

复盘状态机（`ops_postmortems`）：

```
id  incident_id  diagnosis_id  status    origin  title
1   NULL         24            APPROVED  manual  发版重启与运行态指标口径（11-19 16:48）
2   19           28            DRAFT     auto    WORK-086-验收-自动诊断 复盘（11-19 17:23）
```

`#1` 走完「创建草稿（`POST /postmortems {diagnosisId:24}`）→ `PUT` 改标题/根因/影响/时间线/处置 → `APPROVE`」；
`#2` 由 TASK-015 在告警诊断成功后自动起草，`origin=auto`，未发任何通知。

全文检索（ngram）实测命中：

```
SELECT id, MATCH(title, root_cause, body_md) AGAINST ("日志 模板 异常 待处理 重启 发版"
       IN NATURAL LANGUAGE MODE) AS score FROM ops_postmortems WHERE status="APPROVED";
→ 1, 0.9061905741691589
```

### 历史窗口数据仍在（2026-11-19 14:07–14:23 CST）

用新代码同一组 LogQL 直查线上 Loki（纳秒窗口 1795068420–1795069439）：

- **生命周期段**（30 行，limit 30）：`14:16:59 PID 479850 Starting`、`14:17:27 Started in 30.153s`、
  `14:20:46 PID 488984 Starting`、`14:21:11 Started in 27.335s`、`14:23:47 PID 496699 Starting`、
  `14:20:40 HikariPool-1 - Shutdown initiated/completed`。
- **异常段**（4 行）：全部是 `SqlContainerPool` 的 INFO（`acquireTimeoutMs=1500`），无真实 ERROR ⇒
  旧代码只抓 `error|exception|failed|timeout` 时几乎必然空手，这就是「报告这么简单」的直接原因。
- **上下文段**（100 行，被 limit 截断）：说明该窗口日志密度远高于「479 行/小时」均值。

## 验收结果

`AC-001`~`AC-010` 逐条通过，证据见 §「验证结果」矩阵与 §「线上链路」。
用户已在 2026-11-19 15:05 批准需求（含「支持贴图」「自动起草」两条追加），
业务验收待用户在本报告上签署。

## 失败与未验证项

1. **没有用应用自身重放 14:10–14:30 的精确窗口**：`OpsIncidentMapper.upsertFiring` 的重启分支只更新
   `last_seen = VALUES(last_seen)`，而 `OpsHookController:105` 传的 `lastSeen` 恒为 `now`；手动诊断端点又不接
   `incidentId`，因此任何 HTTP 重放都会把窗口拉回「此刻往前 60 分钟」。替代证据：同一组 LogQL 直查该窗口
   （上表）+ 线上真实诊断验证窗口逻辑。
2. **邮件 HTML 只证到「已发送」**：`ops.mail.sent ... recipients=1` 证明多部分消息被 SMTP 接受；HTML 正文、
   结论卡片与 `.md` 附件由 `OpsDiagnosisEmailTest`（13 例）与 `OpsDiagnosisMailerTest`（4 例）覆盖，
   未读收件箱逐字核对。
3. **复盘贴图未在线上走一遍**：`POST /api/v1/knowledge/admin/files` 是知识库既有端点，TASK-010 只是调用方；
   线上验证需要一张真实截图，本次未做视觉确认。
4. **两次诊断失败未被修复掩盖**：#25/#26 以「模型输出被长度上限截断」失败，修复（4096→8192）后 #27 用
   6235 tokens 成功，但「报告长度会随证据变长持续增长」这一点没有额外的长度护栏，只调大了上限。

## 剩余风险

- 诊断证据本身不落库（只存 `report_md`），「证据里到底写了什么」只能从报告反推；#25/#26 的截断原因是按
  token 用量推的，不是读到证据文本得出的。
- `log_errors = 无数据`（Prometheus 无该序列）在线上依旧存在，报告只能写「不是 0」。
- 复盘正文里的贴图指向按 UUID 公开可读的附件端点（与知识库附件同一裁决），依赖编辑者自觉遮盖口令；
  编辑器已固定提示，但没有技术强制。
- 磁盘 80%、`node_load1` 偏高由 #24 报告指出，属运维待办，不在本工作项范围。
