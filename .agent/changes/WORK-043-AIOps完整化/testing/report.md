---
artifact: test-report
work_id: WORK-043
work: AIOps 完整化：事件、基线、链路、SLO 与评测
status: passed
evidence: required
verified_commit: c01f4c2
created: 2026-10-27
updated: 2026-10-27
accepted_by: xiaou61
accepted_at: 2026-10-29 13:21:16 +0800
---

# WORK-043 验证报告

日期：2026-10-27。每条结论对应一次真实执行。后端测试经 `scripts/test-db.sh` 分配独占库；
部署从干净工作树 `.worktrees/work-043-p2` 发出；生产验证走 `ssh codenest-online`。

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 | `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=OpsIncidentMapperTest,OpsHookIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 通过 | 指纹只取 alertname/severity/instance/job/unit；`dedupe_key` 唯一键让同一指纹只有一个 OPEN；11 执行 / 0 失败 |
| AC-002 | 同上 | 0 | 通过 | `detailAndNote`：时间线按 id 升序、NOTE 追加在尾部；生产事件页 `/ops/incidents` 可读 |
| AC-003 | `... -Dtest=OpsDiagnosisServiceTest,OpsHookIntegrationTest ...` | 0 | 通过 | 诊断落 `incident_id`、`last_diagnosed_at` 在窗口内拒绝第二次；生产事件 #4 → 4 分钟后同指纹被冷却拦下 |
| AC-004 | `... -Dtest=OpsEvidenceCollectorTest ...` | 0 | 通过 | 8 执行 / 0 失败；`baselineFormatting` 断言 `1h/24h/7d_same_hour/baseline/ratio/samples_7d` 六项；样本 <100 写"基线不足"；生产诊断 #8 报告原文引用 `samples_7d=211` 与"回退" |
| AC-005 | `promtool check rules` + `promtool test rules`（在服务器 `/tmp/slo-check`） | 0 | 通过 | `SUCCESS: 56 rules found`；7 个用例 SUCCESS（正常/尖峰/样本不足/全 200/全 500 快烧/只有 4xx 与 actuator/延迟 50%）；生产 `paideia-baseline` 21 条在求值 |
| AC-006 | 生产 Grafana API `/api/prometheus/grafana/api/v1/rules` | 0 | 通过 | 17 条规则 `health=ok`、`state=inactive`；无 DatasourceNoData 活动告警（P1 的 `or vector(0)` 修复生效） |
| AC-007 | `ssh codenest-online ss -ltn`（grep 3200/4317/4318 三个端口） | 0 | 通过 | 三个端口全部 `127.0.0.1`；`systemctl is-active tempo` = active；按 trace id 查询返回 200 |
| AC-008 | `-Dtest=TraceIdFilterTest` + 生产 curl | 0 | 通过 | 有 span 时用 span 的 traceId，无 span 时沿用入站 `X-Trace-Id` 或生成；响应头与 Tempo 中的 traceId 同一串 |
| AC-009 | `-Dtest=TempoClientTest,OpsEvidenceCollectorTest` | 0 | 通过 | 4 + 8 执行 / 0 失败；`traceEvidence` 断言只带最慢 span 与白名单属性。**生产上 error/slow 过滤器在健康期返回空，报告写"无匹配 trace"**——如实降级 |
| AC-010 | `... -Dtest=OpsFeedbackIntegrationTest ...` + `pnpm --filter @paideia/admin typecheck` | 0 | 通过 | 5 执行 / 0 失败：非法评分 400、`{}` 400、重评覆盖（增量口径）、时间线 NOTE、空统计给 null、学习者 403 / 匿名 401；UI 三档按钮 + 备注框，typecheck 无输出 |
| AC-011 | `... -pl paideia-ai -am -Dtest=OpsDiagnosisGoldenSetTest ...` | 0 | 通过 | 14 执行 / 0 失败 / **1 跳过**（跳过的是 live 模式，见"未覆盖"第 1 条）；12 条金集，prompt 模式断言五节、8 个证据标签、`mustMention` 在证据里真实存在、`mustNotMention` 不出现、长度 < 24000 |
| AC-012 | 生产 `curl /actuator/prometheus` 与 `curl prometheus/api/v1/query` | 0 | 通过 | 错口令登录后 `paideia_login_attempts_total{outcome="failure"}=1.0`；35 秒后 `paideia:sli_login_success:ratio1h=1`；`paideia:sli_scheduled_success:ratio5m=1`；无样本不误报由 promtool 的"只有 4xx 与 actuator"用例守住 |
| AC-013 | Grafana API `/api/v1/provisioning/alert-rules` | 0 | 通过 | 7 条 `paideia-slo-*` 规则加载、全部 `noDataState=OK`、severity 正确（4 critical / 3 warning）；算式由 promtool 的"全 500"用例覆盖，**真实高流量快烧未构造**，见"未覆盖" |
| AC-014 | 生产合成 webhook（两次） | 0 | 通过 | 事件 #4/#5 → 诊断 #8/#9 `DONE`；报告原文引用 `slo_http_availability sli_1h=100.000% burn_1h=0.00x`，并按 burn rate 判断"告警与 SLI 自相矛盾" |
| AC-015 | `scripts/test-db.sh mvn -B -T 1C -pl '!paideia-app' test`（在合并后的工作树） | 0 | 通过 | **330 执行 / 0 失败 / 14 跳过**，78 秒；`Ops*Test` 全部 0 失败（含 042 的 `OpsDiagnosisIntegrationTest` 3、`OpsSummaryIntegrationTest` 5、`OpsAdminToolsTest` 7） |
| AC-016 | `ss -ltn` 对比 + `TempoTraceRedactorTest` + `OpsLogRedactorTest` | 0 | 通过 | 监控端口全部回环（9090/9100/9104/9121/9115/3000/3100/3200/4317/4318）；公网侧无新增；trace 证据无 `db.statement`/`authorization`/请求体；日志脱敏 4 条用例 |
| 模块与迁移闸 | `... -pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest ...` | 0 | 通过 | 9 执行 / 0 失败（46 秒） |
| 管理端构建 | `pnpm --filter @paideia/admin typecheck` + `vite build` | 0 | 通过 | tsc 无输出；`✓ built in 2.35s` |
| 学习者端构建 | `pnpm --filter @paideia/app build` | 0 | 通过 | 懒加载守卫通过（编辑器 124.6 kB gzip，门槛 160） |
| 生产部署 | `powershell -File scripts/release.ps1 -WithWeb` | 0 | 通过 | **112.3 秒**：后端 26.5s / 前端 11.5s / 打包压缩 26.1s / 上传 16.2s / web 2.3s / 健康检查 23.9s；变化 55 个文件 |
| 面板 | 生产 Grafana DB 读 `dashboard.grafana.app/paideia-ops` | 0 | 通过 | panel 数 **13**（原 10 + SLI 1h / 燃尽率 / 7 天预算剩余） |
| 新端点鉴权 | `curl -o /dev/null -w '%{http_code}' /api/v1/ops/admin/diagnoses/stats` | 0 | 通过 | 无令牌 **401** |
| 反向验证：NaN 当数值 | 把 `sloSummary` 的 `isNaN` 判定临时去掉跑 `-Dtest=OpsEvidenceCollectorTest` | 1 | 通过 | 1 条失败（`nanSliIsNotANumber`），恢复后 8/0 |
| 反向验证：trace status 空串 | 把 `asText("unset")` 临时改回 `asText("")` 跑 `-Dtest=TempoClientTest` | 1 | 通过 | 1 条失败（`missingStatusReadsAsUnset`），恢复后 4/0 |

## 一、实施中抓到的真缺陷

### 1. SLI 无样本时证据写成 `sli_1h=NaN%`（P5 修）

生产演练的诊断 #9 报告原文：

> `slo_ai_success sli_1h=NaN% burn_1h=NaNx`，只能说明该 SLI 在当前窗口无有效样本

模型把 `NaN` 当成了真实测量值，进一步推测"存在 NaN 缺口可能触发异常求值"——一段完全基于假前提的推理。
根因：`OpsEvidenceCollector.sloSummary` 只判 `null`，而 Prometheus 在分子分母都无样本时返回的是 `NaN`（不是空集）。

### 2. trace 的 `status=` 是空串（P5 修）

同一份报告：

> 二者 `status=` 为空，无法判断是 5xx、鉴权失败还是客户端中断

根因：proto3 JSON **省略零值**，`status.code=UNSET(0)` 时整个 `status` 字段不出现，`asText("")` 得到空串。
正常 span 全是 UNSET，所以这条在健康期必然出现。

### 3. 5xx 告警在"完全没有 5xx"时被判成 NoData（P1 修）

`sum(rate(...{status=~"5.."}[5m]))` 在没有 5xx 时是空集，`空 / 非空` 也是空集，Grafana 判 DatasourceNoData
并产生活动告警。修法：分子分母都加 `or vector(0)`。

### 4. Grafana 双单元抢端口（部署脚本修）

服务器上同时有 apt 包自带的 `grafana-server.service` 与本项目自写的 `grafana.service`，两者抢
`127.0.0.1:3000`。`systemctl restart grafana-server` 完全改不了真正在跑的那个，而它自己进入
`Restart=on-failure` 重启循环（半小时 66 次）。`install-ops.sh` 现在会检测到就 `disable --now`。

## 二、不属于本项但阻塞构建的一处

另一个会话的 `a25333a`（GitHub 热榜按语言分类）新增了迁移 `V20261027150000__digest_item_language.sql`，
却没登记校验和，`MigrationChecksumTest` 对所有人变红。已用 `-Dmigration.checksums.update=true` 登记并单独提交
（`17fa00a`），没碰那个迁移的内容。

## 三、未覆盖与已知限制

- **金集 live 模式没跑**：需要真实 `DEEPSEEK_API_KEY` 与 12 次调用。prompt 模式证明了"证据被正确带进提示词"，
  但"模型确实会引用 burn rate"只有 2 次生产演练作为样本。
- **没有构造真实高流量快烧**：合成 webhook 验证了"SLO 告警名 → 事件 → 诊断"这条下游路径；
  "燃尽率真的超过 14.4 并让 Grafana 规则从 inactive 变 firing"没实测——需要把线上打到 5xx 或高延迟，
  代价与风险都不合适。promtool 用合成 counter 覆盖了算式（全 500 时 1h 与 5m 燃尽率都 > 14.4）。
- **`paideia_ai_runs_total` 在生产上仍为空**（线上没人用 AI 助手），"真实调用产生一条 ok 计数"没有端到端证据；
  指标装配由 `DefaultRunRecorderTest` 与 `SloMetricsTest` 覆盖。
- **7d 基线在冷启动期不可用**（`samples_7d < 100`），证据写"基线不足"。设计行为，一周后自然恢复。
- **管理端评分区没有 e2e 点击**：`feedback.spec.ts` 只在库里有已完成诊断时验证按钮存在，
  评分写入的正确性靠 `OpsFeedbackIntegrationTest`；不制造真实上游调用是刻意的（会花钱）。
- **Tempo 磁盘预算未实测**：`MemoryMax=512M`、块保留 720h，实际块大小要跑满一周才能确认。
- **既有缺陷未修**：`HttpMessageNotWritableException`（`Content-Type: text/javascript`）在 P1 报告里出现过一次，
  与本项无关。`api-shape.json` 的 diff 来自另一个会话的改动，未纳入本项提交。
