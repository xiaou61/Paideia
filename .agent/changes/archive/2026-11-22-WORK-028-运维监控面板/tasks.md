---
artifact: tasks
work_id: WORK-028
work: 运维监控面板
status: approved
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 15:42:28 +0800
approver_role: CTO
---

# 任务 · 运维监控面板

**排序原则**：先把「应用能出指标」与「数据库有备份」两个独立价值点做完（T-01、T-05/T-06），再做依赖它们的采集与面板。**T-01 是唯一会影响既有部署链路的任务**（管理端口），必须单独验收通过后再往下走。

**硬约束**：所有服务器命令带 `http_proxy=http://127.0.0.1:7890`（直连实测失败）；不新增公网端口；不新增数据库迁移。

## A 应用侧指标

### TASK-001 | done | 管理端口与 Prometheus 端点

- 在 `backend/paideia-app/pom.xml` 加 `io.micrometer:micrometer-registry-prometheus`（不写版本，由 Boot BOM 管）。
- `application.yml` 的 `management` 段加 `server.port: 8082`、`server.address: 127.0.0.1`，`endpoints.web.exposure.include` 追加 `prometheus`。
- **接口块**：无新增接口；`GET http://127.0.0.1:8082/actuator/prometheus` 返回 `text/plain` 且含 `jvm_memory_used_bytes`、`executor_queue_remaining_tasks`、`hikaricp_connections_active`。
- **验收**：本机 `mvn -pl paideia-app -am test` 通过；起本地实例后 curl 8082 得 200 且三个指标名都出现；**`curl 8081/actuator/health` 仍 200**（这条是防御 T-01 弄坏 `release.ps1` 的关键）。
- **反向验证**：把 `management.server.address` 改成 `0.0.0.0` 后，`ss -ltn` 能看出 8082 对外监听——确认这条配置真的在生效，而不是被忽略。

### TASK-002 | done | 阈值配置与四态判定模型

- 新增 `paideia.ops.*` 配置项（见 design 接口与数据节），绑定到 `OpsProperties`。
- 新增纯函数 `OpsStatus.evaluate(value, warn, error)`：`null / NaN` → `UNKNOWN`；`>= error` → `ERROR`；`>= warn` → `WARN`；否则 `OK`。**`UNKNOWN` 必须排在所有比较之前**，NaN 参与比较全是 false，漏掉这一支会静默变成 `OK`。
- **验收**：`OpsStatusTest` 覆盖 OK/WARN/ERROR/UNKNOWN 四态 + 边界值（恰好等于阈值）+ `null` + `Double.NaN`，全绿。
- **反向验证**：删掉 NaN 分支后测试必须红。

## B 服务器采集

### TASK-003 | done | Prometheus 与四个 exporter

- 装 `prometheus`（apt，2.45.3）与 `node_exporter` / `mysqld_exporter` / `redis_exporter` / `blackbox_exporter`（下载走代理），各一个 systemd 单元，全部 `--web.listen-address=127.0.0.1:<端口>`，`MemoryMax` 按 design 预算设上限。
- `prometheus.yml` 定义五个 job：应用（8082）、node（9100）、mysqld（9104）、redis（9121）、blackbox（9115，三个 HTTP 目标）；`--storage.tsdb.retention.time=3d`、`--storage.tsdb.path=/var/lib/prometheus`。
- `node_exporter` 带 `--collector.textfile.directory=/var/lib/node_exporter/textfile`。
- **验收**：`systemctl is-enabled` 全 `enabled` 且 `is-active` 全 `active`；`curl -s 127.0.0.1:9090/api/v1/targets` 里五个 job 全 `up`；`ss -ltn` 确认 9090/9100/9104/9121/9115 只绑回环。
- **失败处理**：应用目标暂时不通（T-01 未部署）不得阻塞其他四个 job 起来。

### TASK-004 | done | ops-textfile 采集脚本

- 新增 `deploy/ops/ops-textfile.sh` + `ops-textfile.service`(oneshot) + `ops-textfile.timer`(60s)。
- 脚本内容：对白名单单元 `paideia laya rustfs mysql redis-server mihomo` 读 `ActiveState`、`MemoryCurrent`、`MemoryMax`、`ActiveEnterTimestamp`；对白名单容器（`firecrawl-*` 七个）读运行状态与 `RestartCount`；读 `/opt/paideia-backup/last-status.json`。
- 输出指标名：`paideia_unit_active{unit=}`、`paideia_unit_memory_bytes{unit=}`、`paideia_unit_memory_max_bytes{unit=}`、`paideia_container_running{name=}`、`paideia_container_restarts{name=}`、`paideia_backup_success`、`paideia_backup_age_seconds`、`paideia_backup_last_bytes`。
- **写入必须原子**：写 `.tmp` 再 `mv`，否则 Prometheus 可能读到半行。
- **验收**：手动跑一次后 `curl 127.0.0.1:9100/metrics | grep paideia_` 能看到上述指标；`MemoryMax` 对 `laya` 为 3221225472；容器数等于 `docker ps` 的白名单命中数。
- **评审焦点**：单元不存在时不得让脚本退出（要输出 0 而不是整份文件缺失）；`docker` 不可用时其余指标仍要写出。

### TASK-005 | done | 备份 timer 与滚动保留

- 新增 `deploy/ops/paideia-backup.sh` + `.service`(oneshot) + `.timer`(每日 03:15, `Persistent=true`)。
- 凭据从 `/etc/paideia-backup/client.cnf`（600）经 `--defaults-extra-file` 读取；`--single-transaction --routines --triggers --events`；gzip 落 `/opt/paideia-backup/`；`find -mtime +7 -delete`。
- 成功后写 `last-status.json`（`success/at/durationMs/bytes`），失败写 `success:false` 与 `error`。
- **验收**：`systemctl start paideia-backup.service` 后文件存在、大小 > 0、`last-status.json` 的 `success` 为 true；`gzip -t` 通过；**`journalctl -u paideia-backup` 里不出现口令**。

### TASK-006 | done | 恢复演练

- 在 `paideia_test` 之外的临时库上还原最近一次备份，比对表数量与 `knowledge_entries`、`refresh_tokens` 行数。
- **验收**：还原库的表数量与源库一致；两个关键表行数一致；演练命令与输出写进 `testing/report.md`。演练结束后删掉临时库。

## C 面板与入口

### TASK-007 | done | Grafana 与 provisioning

- 装 Grafana（官方 apt 源走代理），绑 `127.0.0.1:3000`，`serve_from_sub_path=true`，`root_url` 带 `/api/v1/ops/grafana/`，开 anonymous Viewer + `allow_embedding=true`。
- `provisioning/datasources/prometheus.yaml` 指向 `127.0.0.1:9090`；`provisioning/dashboards/paideia-ops.json` 含四块趋势（主机资源 / 请求量与错误率 / 线程池队列与拒绝 / Laya 内存水位）+ 备份新鲜度单值面板。
- **验收**：本机 `curl -sI 127.0.0.1:3000/api/health` 200；`curl` 带子路径前缀访问 dashboard JSON 能列出 `paideia-ops`；`ss -ltn` 确认 3000 只绑回环。

### TASK-008 | done | 摘要接口

- 新增 `OpsSummaryController` (`GET /api/v1/ops/admin/summary`) + `OpsSummaryService` + `PrometheusClient`（`java.net.http.HttpClient`，2 秒超时）。
- 七个 group：`host`、`services`、`middleware`、`external`、`scheduled`、`switches`、`backup`；每个 group 独立查询、独立失败。
- `switches` 组直接读 `@ConfigurationProperties`/`Environment`（`ai.essay.enabled`、`ai.decision.enabled`、`sandbox.enabled`、`knowledge.storage`、SMTP host 是否非空），不经 Prometheus。
- **验收**：`OpsSummaryIntegrationTest` —— ADMIN 200 且七个 group 齐全；LEARNER 403；匿名 401；Prometheus 指向不可达地址时 200 且所有 group `UNKNOWN`、`prometheusReachable=false`。
- **接口块**：`OpsSummaryService.summary()` 返回 `OpsSummary`（含 `collectedAt`、`prometheusReachable`、`groups`）；`OpsGroup`（`key`、`title`、`status`、`reason`、`metrics`）；`OpsStatus` 枚举 `OK/WARN/ERROR/UNKNOWN`。

### TASK-009 | done | Grafana 代理

- 新增 `OpsGrafanaProxyController`，`@RequestMapping("/api/v1/ops/grafana/**")`，方法 `GET`/`POST`，转发到 `http://127.0.0.1:3000` 并保留子路径与 query。
- **不转发 `Authorization`**；不转发 `Host`；响应透传状态码与 `Content-Type`；连接失败返回 502 + 可读说明。
- **验收**：`OpsGrafanaProxyTest` —— 路径与 query 拼接正确（含末尾斜杠、含 `?`、含编码字符）；`Authorization` 不出现在转发请求里；目标不可达返回 502。
- **反向验证**：故意把子路径拼接改成丢掉 query，测试必须红。

### TASK-010 | done | 安全规则与接口声明

- `SecurityConfiguration` 加两条 `hasRole("ADMIN")`，**位置在 `anyRequest()` 与任何更宽规则之前**；控制器标 `@ApiAccess(ApiAccess.Access.ADMIN)`。
- **验收**：`ApiAccessDeclarationTest`、`ApiSurfaceTest`、`AuthorizationIsolationTest` 全绿；LEARNER 令牌对两个前缀都 403。

### TASK-011 | done | 管理端运维页

- 新增 `pages/OpsPage.tsx`（摘要卡片 + iframe），路由 `/ops`，`AdminShell` 侧栏加一项「运维」。
- 卡片四态配色与 `Badge` 对齐现有令牌；`UNKNOWN` 显示「未知」并给原因，**不得显示 0**。
- **验收**：`pnpm --filter @paideia/admin test` 通过；本地起服务后页面能在 ADMIN 会话下渲染七个卡片，iframe 指向 `/api/v1/ops/grafana/`。

## D 告警

### TASK-012 | done | 四条告警与邮件通道

- Grafana provisioning `alerting/` 四规则：目标下线（`up == 0` 持续 2 分钟）、磁盘可用 < 15%、`paideia_backup_age_seconds > 36h`、Laya 内存 / 上限 > 0.9。
- SMTP 配置指向线上 QQ 通道，密码从 `/etc/grafana/grafana.env`（600）注入；contact point 收件地址按 Q-05。
- **验收**：`curl 127.0.0.1:3000/api/v1/provisioning/alert-rules` 能列出四条；用构造的指标值（textfile 里临时写一个超阈值样本）触发一次并确认邮件到达；随后移除构造样本。
- **未覆盖声明**：告警的「恢复后不重复」行为只做人工观察一次，不写自动测试。

## E 收尾

### TASK-013 | done | 文档与验收

- 更新 `.agent/rules/always.md`：D-15 行改为已定 Prometheus + Grafana；「服务器环境」表补监控单元与端口；「部署」节补一条「监控单元不随 `release.ps1` 重启」。
- 逐条跑 AC-001..013，把命令与输出写进 `testing/report.md`；**实测 Prometheus 磁盘增量**填进 design 的估算处。
- 追加 `.agent/history/updates.md`。

## 评审焦点

规格暗示但容易被漏掉的失败模式，逐条指到任务与测试：

| 失败模式 | 任务 | 测试 |
| --- | --- | --- |
| 管理端口改动悄悄破坏部署健康检查 | T-001 | 验收里显式 curl 8081/health |
| 阈值判定把「无数据」当「正常」 | T-002 | `OpsStatusTest` 的 NaN/null 分支 + 反向验证 |
| textfile 半行写入被采集 | T-004 | 原子写（`.tmp` + `mv`） |
| 凭证出现在 `ps` / journal | T-005 | 验收显式 grep journal |
| 子路径代理丢 query，面板筛选项失效 | T-009 | 反向验证 |
| `hasRole` 规则顺序写反后静默失效 | T-010 | `ApiAccessDeclarationTest` |
| 恢复演练只看文件存在、不看内容 | T-006 | 比对表数量与行数 |
| 监控抢占 Laya 内存导致 OOM | T-003 | 各单元 `MemoryMax` + 报告实测 |

## 阻塞与依赖

- T-003 依赖 T-001 已部署（应用目标要能通）；其余可并行。
- T-007 依赖 T-003（数据源）；T-008 依赖 T-003 与 T-004；T-011 依赖 T-008 与 T-009。
- T-005 与 T-006 独立，可与前面并行。
- T-012 依赖 T-005（备份指标）与 T-007（Grafana）。
