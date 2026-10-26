---
artifact: test-plan
work_id: WORK-042
work: AIOps 自建诊断层
status: draft
created: 2026-10-26
updated: 2026-10-26
---

# AIOps 自建诊断层测试计划

## 测试范围

- 后端：脱敏、Loki 客户端、证据采集、AI 诊断端口（积分/退款/遥测）、诊断表与 mapper、手动端点与权限、webhook 与冷却、三个只读工具。
- 前端：管理端运维页诊断区（发起、进行中、成功报告、失败原因）。
- 部署：Prometheus 保留期、Loki/Alloy 单元与日志查询、Grafana 数据源与规则、发版指标。
- 不测：Tempo/链路、自动修复、多主机（均为非目标）。

## 环境与前置条件

- 后端测试一律走 `scripts/test-db.sh`（独占库 `paideia_run_*`）；**不直接连共用 `paideia_test`**。
- 前端 e2e 需要先构建后端 jar 并 `source backend/config/remote-db.local.env`；管理端 e2e 用本地实例，避免在生产制造诊断记录与积分消耗。
- 生产验证通过 `ssh codenest-online`；Grafana 口令从服务器 `/etc/grafana/grafana.env` 读取，不写入仓库或命令历史。
- 生产检查前确认 `paideia`、`prometheus`、`grafana`、`loki`、`alloy` 五个单元都是 active。
- 诊断账号在部署时配置为真实管理员 id，并确认余额 > 0。

## 验收矩阵

| AC | 检查 | 类型 | 位置 / 命令 | 期望 |
| --- | --- | --- | --- | --- |
| AC-001 | 保留期生效值 | 人工命令 | `curl -s 127.0.0.1:9090/api/v1/status/flags` | `retention.time=30d` |
| AC-002 | Loki/Alloy 存活与日志可查 | 人工命令 | `systemctl is-active loki alloy`；Loki `query_range` | `active`；返回 `paideia.service` 日志 |
| AC-003 | Grafana 数据源与日志面板 | 人工 | Grafana `/api/datasources/uid/paideia-loki/health`；运维页 | `OK`；面板有日志 |
| AC-004 | 两条新规则加载且表达式有值 | 人工命令 | Grafana provisioning API + Prometheus 查询 | 6 条规则；表达式返回数值 |
| AC-005 | 发版指标 | 人工命令 | `curl .../query?query=paideia_deploy_epoch_seconds` | 值在最近 10 分钟 |
| AC-006 | 工具可见性与只读 | 自动化 | `OpsAdminToolsTest`、`ToolCatalogTest` | 非管理员不可见；三个工具只读 |
| AC-007 | 手动诊断与页面展示 | 自动化 + 人工 | `OpsDiagnosisIntegrationTest`、管理端 e2e | 202 → DONE；页面显示报告 |
| AC-008 | webhook 触发与拒绝 | 自动化 + 人工 | `OpsHookIntegrationTest`；生产 curl | 正确令牌触发；错误 401；resolved 忽略 |
| AC-009 | 脱敏 | 自动化 | `OpsLogRedactorTest` | 五类模式全部替换 |
| AC-010 | 余额不足不发请求 | 自动化 | `DefaultOpsDiagnoserTest` | 抛 `INSUFFICIENT_CREDITS`，调用计数 0 |
| AC-011 | 冷却 | 自动化 | `OpsHookIntegrationTest` | 第二次不建行 |
| AC-012 | 报告五节与证据 | 自动化 + 人工 | `OpsDiagnosisPromptTest`；一次真实报告 | 五节齐全；引用数值/日志/发版 |
| AC-013 | 关闭开关 | 自动化 | `OpsDiagnosisIntegrationTest` | 手动明确错误；webhook skipped |
| AC-014 | 无写操作 | 自动化 + 人工 | `OpsAdminToolsTest` 与端点清单 | 全部只读 |
| AC-015 | 受影响测试与 e2e | 自动化 | 见「自动化检查」 | 0 failed；skipped 单独说明 |

## 自动化检查

```bash
# 1. paideia-ai 新逻辑
cd backend && mvn -B -pl paideia-ai -Dtest=OpsDiagnosisPromptTest,DefaultOpsDiagnoserTest test

# 2. 应用侧切片（独占库）
scripts/test-db.sh mvn -B -pl paideia-app -am \
  -Dtest=OpsLogRedactorTest,LokiClientTest,OpsDiagnosisMapperTest,OpsEvidenceCollectorTest,OpsDiagnosisIntegrationTest,OpsAdminToolsTest,OpsHookIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 3. 跨模块纪律与迁移
scripts/test-db.sh mvn -B -pl paideia-app -am \
  -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 4. 前端
cd frontend && pnpm --filter @paideia/admin typecheck
cd frontend && pnpm --filter @paideia/admin test:e2e -- e2e/admin-ops.spec.ts
```

补充：

- 若新增了跨模块引用，先 `codegraph index --force F:/Paideia` 再跑调用关系检查。
- 反向验证：脱敏测试先注释掉实现跑一次必须红；余额不足测试先去掉预留检查必须红。
- 全量后端只在交接或改动公共契约时跑：`scripts/test-db.sh mvn -B -T 1C test`。

## 人工检查

### 生产部署后

1. `systemctl is-active paideia prometheus grafana loki alloy` → 5 个 `active`。
2. `ss -lntp | grep -E '3100|9090|3000'` → 只出现 `127.0.0.1`。
3. Loki 查询最近 10 分钟 `unit="paideia.service"` → 至少一条。
4. Grafana 数据源 health → `OK`；运维页 iframe 能看到日志面板。
5. 发一次版 → `paideia_deploy_epoch_seconds` 更新。
6. 管理端点：手动发起一次诊断 → 等待完成 → 报告五节齐全，引用数值合理。
7. webhook：用服务器本地 curl 带正确令牌触发 → 新建诊断；不带令牌 → 401；`status=resolved` → 忽略。
8. 现有四条告警仍发邮件（可临时把磁盘阈值调高验证一次，再改回；或核对 Grafana 通知历史）。

### 页面

- 键盘：Tab 能到「AI 诊断」按钮与输入框，Enter 提交。
- 进行中状态不只靠颜色（有文字「诊断中」）。
- 失败时显示错误原因，不是空白。

## 回归范围

- 运维页七张卡片与 `admin_ops_summary` 返回值不变。
- 现有四条告警的阈值、`notification_settings` 与邮件投递不变（收件人从 `paideia-mail` 改为合并后的 `paideia-ops`，两者都含邮件）。
- 管理员/学习者权限隔离：学习者访问 `/api/v1/ops/admin/**` 仍 403。
- MCP：现有工具清单与权限不变；新工具只在 ADMIN + admin scope 下可见。
- AI 观测：新 `kind=ops_diagnosis` 出现在筛选里；其它统计不变。
- 迁移：`MigrationSeedOverlapTest` 通过；生产库只新增一张表。

## 已知缺口

- 没有链路数据，报告无法指出具体 span 或外部调用耗时；只能依据指标与日志推断。
- 没有历史基线/异常检测；报告对比的是固定阈值与最近窗口，不是"平时这个点应该多少"。
- 诊断质量依赖提示词与模型，没有金集评测；本项只保证"引用证据、五节齐全、不执行动作"。
- 生产日志量小（约 6200 行/天），Loki 的实际资源开销按同类部署估算，需在部署后记录实测内存与磁盘。
- 管理端 e2e **不点「开始诊断」**：本地 e2e 后端会加载 `application-local.yml` 的真实平台凭据，点一下就是真实上游调用与积分消耗。提交 → 落库 → 报告展示由 `OpsDiagnosisIntegrationTest`（模型打桩）覆盖；e2e 只证明页面接上与空状态/列表文案。
- 应用崩溃后遗留的 `RUNNING` 行只在下次启动时按超时清理，没有独立清扫任务。
