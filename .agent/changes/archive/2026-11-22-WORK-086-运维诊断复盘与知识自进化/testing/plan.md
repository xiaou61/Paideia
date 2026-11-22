---
work_id: WORK-086
work: 运维诊断复盘与知识自进化
artifact: test-plan
status: draft
mode: strict
updated: 2026-11-19
---

# 运维诊断复盘与知识自进化测试计划

## 测试范围

- 证据窗口：事件锚定、保留期越界标注（AC-001）。
- 日志采集：三段查询、签名聚合、unit 未接入显式标注、Loki 不可达降级（AC-002、AC-004、AC-010）。
- 运行态指标与重启聚合：9 个新指标、`changes(process_start_time_seconds)` 与日志交叉（AC-003）。
- 邮件：HTML 渲染无裸 Markdown、结论卡片、纯文本降级、多部分与 .md 附件（AC-005、AC-010）。
- 复盘：DDL、状态机、审批互斥归档、脱敏、下载（AC-006、AC-009）。
- 知识闭环：只召回 APPROVED、空关键词不查库、召回失败降级、`knowledge_ids` 落库、提示词 `<knowledge>` 与规则 10（AC-007）。
- 接口与权限：7 个端点 ADMIN + 回环、学习者 403（AC-008、AC-009）。
- 生产重放：2026-11-19 14:10–14:30 窗口的 4 次重启与 Hikari 关闭（AC-001~004 的真实证据）。

不在本计划内：Grafana 面板改动（沿用 WORK-085 面板）、Loki `minClusterSize` 调整、MySQL/Redis 日志接入（见「已知缺口」）。

## 环境与前置条件

- 后端测试必须经 `scripts/test-db.sh`，并在 `backend/` 目录执行：
  - 本机库：脚本派生独占 `paideia_run_<时间>_<pid>`。
  - 远程测试库（默认，凭据在忽略文件 `backend/config/remote-db.local.env`）：**同一时刻只允许一轮测试**，与其它会话串行。
- 前端：`pnpm -C frontend --filter @paideia/admin typecheck`；e2e 需 `PAIDEIA_ADMIN_PORT`（默认 5174）与后端端口未被占用，避免 `reuseExistingServer` 复用到别人的服务。
- 生产验证：`ssh codenest-online`（36.212.5.130），只读查询 Loki/Prometheus；重放诊断需要 ADMIN 令牌。
- 邮件验证：使用本机 SMTP 桩或线上真实 SMTP（发给本人），不向他人发信。
- 迁移前确认没有并行会话改同一批表：`git log --all --oneline -- backend/paideia-app/src/main/resources/db/migration | head -5`。

## 验收矩阵

| 验收 | 证明方式 | 命令 / 位置 | 通过标准 |
| --- | --- | --- | --- |
| AC-001 窗口按事件锚定 | 单测 + 生产重放 | `OpsEvidenceWindowTest#prefers_incident_first_seen`、`#out_of_retention_is_marked`；TASK-014 步骤 2 | 断言 `start=14:07:40`/`end=14:23:15`；越界时 `source` 含「超出 Loki 保留期」；生产证据头显示该窗口 |
| AC-002 三段日志 | 单测 + 生产重放 | `OpsEvidenceCollectorTest#unit_not_ingested_is_stated`、`#loki_down_degrades`；TASK-014 步骤 2 | 证据含异常/生命周期/上下文三段；`mysql.service` 标注未接入；重放证据含 `HikariPool-1 - Shutdown initiated` 与 4 个 PID |
| AC-003 运行态与重启 | 单测 + 生产重放 | `OpsEvidenceCollectorTest#restart_count_cross_checks_logs`；TASK-014 步骤 2 | metrics 段含 `重启 4 次`、`process_uptime`、`hikari_pending`、`node_load1`、`node_oom_kill`，且与日志条数一致 |
| AC-004 签名兜底 | 单测 | `OpsLogSignatureTest#collapses_repeated_lines` | 4 行同签名聚合为 1 条，显示 `首 … 末 … ×4` |
| AC-005 邮件 HTML | 单测 + 收信 | `OpsDiagnosisEmailTest#html_has_no_raw_markdown`、`#html_failure_falls_back_to_plain`、`OpsDiagnosisMailerTest#multipart_when_html_enabled`、`#attachment_failure_keeps_body`；TASK-014 步骤 3 | HTML 含 `<strong>/<table>/<code>` 且无 `**`；附件 `diagnosis-<id>.md` 可打开；降级时正文仍送达 |
| AC-006 复盘表与状态机 | 迁移三件 + 单测 | `MigrationVersionFormatTest`/`MigrationChecksumTest`/`MigrationSeedOverlapTest`；`PostmortemServiceTest#approve_archives_previous`、`#draft_from_diagnosis_has_sections` | 迁移绿；同 incident 旧 APPROVED 自动 ARCHIVED；草稿含三节 |
| AC-007 闸门式知识 | 单测 + 生产闭环 | `PostmortemKnowledgeTest#only_approved_returned`、`#empty_keyword_returns_empty`、`#search_failure_empty`；`OpsDiagnosisServiceTest#records_knowledge_ids`；`OpsDiagnosisPromptTest#renders_knowledge_block`；TASK-014 步骤 4 | 草稿不参与召回；空关键词 0 次查库；`knowledge_ids="12,15"`；prompt 含规则 10；生产第二次诊断带出前一条复盘 |
| AC-008 管理端闭环 | typecheck + e2e | `pnpm -C frontend --filter @paideia/admin typecheck`；`playwright test e2e/postmortems.spec.ts` | 0 errors；导航可见、创建/审批/下载走通 |
| AC-009 安全 | 守卫三件 + 单测 | `ApiContractTest`、`AuthorizationIsolationTest`；`PostmortemServiceTest#redacts_secrets` | 新端点全部声明 ADMIN；学习者 403；库中无明文口令 |
| AC-010 降级 | 单测 | `OpsEvidenceCollectorTest#loki_down_degrades`、`OpsDiagnosisEmailTest#html_failure_falls_back_to_plain`、`PostmortemKnowledgeTest#search_failure_empty`、`PostmortemServiceTest#rejects_archived_edit` | 每条降级路径都有显式文案或 409，诊断不整体失败 |

## 自动化检查

内环（每个任务）：只跑该任务的测试类

```powershell
cd F:\Paideia\backend
scripts/test-db.sh mvn -B -pl paideia-ops -Dtest=OpsEvidenceWindowTest test
scripts/test-db.sh mvn -B -pl paideia-ai   -Dtest=OpsDiagnosisPromptTest test
```

切片级（A/B/C 各自完成后）：

```powershell
cd F:\Paideia\backend
scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test                                  # 88s / 410 条
scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest,ApiSurfaceTest,ApiContractTest,AuthorizationIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

前端：

```powershell
pnpm -C F:\Paideia\frontend --filter @paideia/admin typecheck
pnpm -C F:\Paideia\frontend --filter @paideia/admin exec playwright test e2e/postmortems.spec.ts
```

## 人工检查

- 收信：HTML 在 QQ 邮箱网页端与手机客户端都正常（表格不塌、代码块不溢出）；「结论」卡片在首屏。
- 回放：拿 14:10–14:30 的原始日志与诊断证据并排看，确认没有把窗口外事件算进来。
- 复盘编辑：Markdown 预览与下载的 `.md` 内容一致。
- 知识闭环：确认复盘前 `<knowledge>` 写「本次未取得历史复盘」；确认后同一告警再诊断能带出该条。

## 回归范围

- `OpsDiagnosisService` 的 single-flight、冷却、`catchUp` 不受窗口改动影响（既有用例必须全绿）。
- 模式触发（WORK-085）路径不邮件：`incidentId == null` 分支行为不变。
- 管理端既有 OpsPage 详情/反馈/事件页不回归（`e2e/ops.spec.ts`、`e2e/incidents.spec.ts`）。
- 迁移对已有 `ops_diagnoses` 行无破坏（只加可空列）。

## 已知缺口

- MySQL/Redis 日志仍取不到：alloy 白名单写了但未落库；本项只在证据里标注「未接入」，不扩大采集面。
- Loki `minClusterSize=30` 未改：`patterns` 段在低流量项目上仍常为空，属 WORK-085 已记录限制。
- 复盘不支持图片与附件：正文只接受文本/链接。
- 生产重放依赖 30 天保留期；超过 30 天的历史事件只能验指标段。
- 远程测试库不支持并行：TASK-014 与其它会话的测试必须错峰。
