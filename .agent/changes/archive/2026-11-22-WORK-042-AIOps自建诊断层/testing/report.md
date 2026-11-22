---
artifact: test-report
work_id: WORK-042
work: AIOps 自建诊断层
status: passed
evidence: required
created: 2026-10-26
updated: 2026-10-26
accepted_by: xiaou61
accepted_at: 2026-10-29 13:21:07 +0800
---

# AIOps 自建诊断层验证报告

## 验证环境

- 本机：Windows + Git Bash；Maven 3.9 / JDK 25；Node/pnpm workspace。
- 后端测试库：`scripts/test-db.sh` 分配的独占库 `paideia_run_*`，每个用例组独立、结束后删除。
- 生产：`codenest-online`（Ubuntu 24.04，4 核 / 15GB），systemd 单元 `paideia`、`prometheus`、`grafana`、`loki`、`alloy` 均为 active。
- 发版：干净工作树 `.worktrees/release-042`，两次发版分别 122.8 秒（含前端）与 68.5 秒（仅后端）。
- 提交：`e6f2657`（实现）、`84ba364`（部署与指标口径修复）。远端未推送。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 | `ssh codenest-online 'curl -s 127.0.0.1:9090/api/v1/status/flags'` | 0 | 通过 | `storage.tsdb.retention.time = 30d`；Prometheus 数据目录从 96MB 开始增长 |
| AC-002 | `systemctl is-active loki alloy` + Loki `query_range` | 0 | 通过 | 两个单元 active；`unit` 标签值含 `paideia.service`/`laya.service`，查到日志行 |
| AC-003 | Grafana `/api/datasources/uid/paideia-loki/health`；dashboard API | 0 | 通过 | 数据源 `status=OK`；`paideia-ops` 面板 10 个，最后一个是 Loki logs 面板 |
| AC-004 | Grafana provisioning rules API | 0 | 通过 | 6 条规则（4 旧 + 2 新）；全部 `health=ok`；5xx 与日志规则均 inactive/Normal；活动告警 0 |
| AC-005 | Prometheus `paideia_build_info` / `paideia_deploy_epoch_seconds` | 0 | 通过 | 版本 `84ba364`；部署时间戳存在（首次因 CRLF 被 node_exporter 拒绝，已修并复验） |
| AC-006 | `mvn -Dtest=OpsAdminToolsTest` | 0 | 通过 | 7 条：ADMIN scope、只读、非管理员不可见、未知枚举拒绝 |
| AC-007 | `OpsDiagnosisIntegrationTest` + 管理端 e2e | 0 | 通过 | 3 条集成测试；e2e `ops.spec.ts` 2 passed；生产报告可读 |
| AC-008 | 生产回环 webhook + `OpsHookIntegrationTest` | 0 | 通过 | 正确令牌 `accepted=true,id=1/2`；集成测试覆盖错误令牌 401、非回环拒绝、resolved 忽略、冷却 |
| AC-009 | `OpsLogRedactorTest` | 0 | 通过 | 4 条：邮箱、Bearer、JWT、赋值式令牌、长十六进制、控制字符、截断 |
| AC-010 | `DefaultOpsDiagnoserTest` + 生产流水 | 0 | 通过 | 4 条：未配置不入上游、余额不足不入上游、失败退款、成功结算；流水 `OPS_DIAGNOSIS -13 / -11` |
| AC-011 | `OpsHookIntegrationTest` + `OpsDiagnosisServiceTest` | 0 | 通过 | 同告警冷却窗口内第二次不建行；手动不受冷却；单飞 429 |
| AC-012 | 生产诊断报告 | 0 | 通过 | 五节齐全；证据含 `http_5xx_percent = 0.00%`、`http_p95_seconds = 0.108 s`、发版时间/版本 |
| AC-013 | `OpsDiagnosisIntegrationTest` + `OpsDiagnosisServiceTest` | 0 | 通过 | 关闭开关：手动 409、不落行；webhook 跳过 disabled |
| AC-014 | `OpsAdminToolsTest` + 端点清单 | 0 | 通过 | 三个工具 `readOnly=true/destructive=false`；新增端点无写操作 |
| AC-015 | 后端切片 + 迁移守卫 + 前端 e2e | 0 | 通过 | `paideia-ai` 8、应用切片 34、模块/迁移 9、管理端 e2e 2；0 failed |

## 验收结果

15 条 AC 均有对应证据，报告记为 `passed`。其中 AC-001/002/003/004/005/008/012 在生产实测；其余由自动化测试覆盖。现有四条告警、运维页七张卡片与 `admin_ops_summary` 的行为未变。

生产实测数据：

- 两次真实诊断均 `DONE`：输入 1054/1058 tokens，输出 3157/2721 tokens，报告 2394/3077 字符；积分分别扣 13 与 11。
- Loki 已采集 `paideia.service` 与 `laya.service` 日志；`unit`、`level` 标签可用。
- 发版指标 `paideia_build_info{version="84ba364"}` 与部署时间戳可查。

## 失败与未验证项

- **生产管理端手动按钮未用管理员 JWT 实测**：管理员口令不在本会话可用范围内。手动端点由 `OpsDiagnosisIntegrationTest`（3 条，模型打桩）与管理端 e2e（2 条，页面接入）覆盖；生产用同一条编排链路的 webhook 路径验证。
- **Grafana → 应用的自动投递没有制造真实告警验证**：联系人已加载且含 webhook + `headers`，生产直接调用端点验证了令牌与回环；没有临时改阈值制造一次真实 firing 通知。
- **MCP 管理员 PAT 未在生产实调**：三个工具的可见性与只读由 `OpsAdminToolsTest` 的目录视图覆盖，未在生产创建 PAT。
- **报告质量没有金集评测**：本项只保证五节、引用证据、不执行动作；两次真实报告的结论都明确标注了证据不足或矛盾，属于预期行为。
- **全量后端测试未跑**：按项目规定只跑受影响切片；改动未触及公共契约与数据层之外的模块。

## 剩余风险

- 日志脱敏是模式匹配：能覆盖邮箱、Bearer、JWT、赋值式令牌与长十六进制，但无法保证识别所有自定义敏感格式；出站上限为 12,000 字符。
- 诊断成本按次计：两次真实诊断各约 11–13 积分；告警风暴由 10 分钟冷却与单飞挡住，但没有按天预算上限。
- Loki/Alloy 的实测内存与磁盘增长会在运行一段时间后复核；当前分别设置 `MemoryMax=512M/256M`。
- 新增的 `http.server.requests` 直方图会增加少量序列数；只对这一个指标开启。
- 生产 Grafana 的 `paideia-mail` 旧联系人仍留在数据库（无规则引用，不再发信）；清理需要单独删一次。
