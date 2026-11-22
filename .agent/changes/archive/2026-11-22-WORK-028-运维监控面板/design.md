---
artifact: design
work_id: WORK-028
work: 运维监控面板
status: approved
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 15:42:28 +0800
approver_role: CTO
---

# 运维监控面板设计

## 系统上下文

```
                          公网只有 8081
                               │
                        ┌──────▼───────┐
                        │  paideia     │  8081 应用 + 静态
                        │  (systemd)   │  8082 管理端口（回环）
                        └──┬────────┬──┘
             /api/v1/ops/  │        │  /actuator/prometheus
              grafana (代理)│        │
                    ┌──────▼──┐   ┌─▼─────────┐
                    │ grafana │   │ prometheus│
                    │ :3000   │◄──┤ :9090     │
                    └────┬────┘   └─┬───┬───┬─┘
                         │            │   │   │
                    SMTP(QQ)    ┌─────┘   │   └──────┐
                                │         │          │
                        node_exporter  mysqld_exporter  redis_exporter
                          :9100          :9104           :9121
                            │                                
                    textfile 目录（每分钟写入）
                     ├ ops.prom      ← systemctl / docker / 备份状态
                            │
                        blackbox_exporter :9115 → laya / rustfs / firecrawl
```

所有监控组件绑 `127.0.0.1`；唯一公网端口仍是 `8081`。

## 组件与职责

| 组件 | 位置 | 职责 |
| --- | --- | --- |
| Prometheus registry | `paideia-app/pom.xml` 加依赖，`application.yml` 开端点 | 把 Micrometer 指标按 Prometheus 文本格式输出到 8082 |
| 摘要接口 | `paideia-app` 新包 `io.github.xiaou61.ops` | 查 Prometheus、判定四态、返回七类摘要 |
| Grafana 代理 | 同上，`OpsGrafanaProxyController` | 把 `/api/v1/ops/grafana/**` 转发到 `127.0.0.1:3000` |
| 管理端页面 | `frontend/apps/admin/src/pages/OpsPage.tsx` | 摘要卡片 + iframe 面板 |
| `ops-textfile` timer | `deploy/ops/` | 每分钟写 `ops.prom`（systemd 单元、Docker、备份状态） |
| `paideia-backup` timer | `deploy/ops/` | 每日 dump 并滚动保留 7 天 |

放在 `paideia-app` 而不是新建 `paideia-ops` 模块：本项只有一条读接口与一个转发器，且它要读**管理端口的实际绑定地址**（`local.management.port`），那个信息只存在于应用上下文里。新建模块只为一个控制器不划算。

## 请求或事件流程

### 摘要页

```
Admin OpsPage
  → GET /api/v1/ops/admin/summary           （learner 令牌 → 403）
  → SecurityConfiguration: /api/v1/ops/admin/** → hasRole("ADMIN")
  → OpsSummaryService.summary()
      ├─ Prometheus instant query × N（带 2s 超时，串行；失败项标 UNKNOWN）
      └─ 判定四态 + 人话原因
  → 200 { groups: [ {key, title, status, reason, metrics:[...] }, ... ] }
```

### 面板

```
Admin OpsPage  <iframe src="/api/v1/ops/grafana/d/paideia-ops?...">
  → SecurityConfiguration: /api/v1/ops/grafana/** → hasRole("ADMIN")
  → OpsGrafanaProxyController 转发到 127.0.0.1:3000（保留子路径与方法）
  → Grafana（anonymous Viewer，serve_from_sub_path）
```

### 每分钟采集

```
ops-textfile.timer (60s) → ops-textfile.service (oneshot, root)
  ├ systemctl show --property=ActiveState,MemoryCurrent,MemoryMax,... <白名单单元>
  ├ docker ps --format ...  与 docker inspect 取 RestartCount（白名单容器）
  ├ 读 /opt/paideia-backup/last-status.json
  └ 原子写 /var/lib/node_exporter/textfile/ops.prom（写 .tmp 再 mv）
```

### 每日备份

```
paideia-backup.timer (03:15, Persistent=true) → paideia-backup.service
  ├ mysqldump --defaults-extra-file=/etc/paideia-backup/client.cnf paideia | gzip > <日期>.sql.gz
  ├ 保留 7 天（find -mtime +7 -delete）
  └ 写 last-status.json {success, at, durationMs, bytes, error}
```

## 接口与数据

### `GET /api/v1/ops/admin/summary`

响应（示意，实施时字段名以此为准）：

```json
{
  "collectedAt": "2026-10-20T15:40:00Z",
  "prometheusReachable": true,
  "groups": [
    {
      "key": "host",
      "title": "主机",
      "status": "WARN",
      "reason": "磁盘已用 33%，内存可用 8.9G",
      "metrics": [
        { "name": "cpu", "value": 0.12, "unit": "ratio" },
        { "name": "diskUsed", "value": 0.33, "unit": "ratio" }
      ]
    }
  ]
}
```

- `status` 取值 `OK` / `WARN` / `ERROR` / `UNKNOWN`。
- `UNKNOWN` 是**第四态**：Prometheus 不可达、指标缺失或查询超时都落在这里，前端不得把它画成 0 或 OK。
- 一个 group 的查询失败不影响其他 group；`prometheusReachable=false` 时全部为 `UNKNOWN` 并给统一原因。

### `ALL /api/v1/ops/grafana/**`

透传到 `http://127.0.0.1:3000/<去掉前缀的路径>`。允许方法：`GET`、`POST`。转发 `Content-Type`、`Accept`、`Accept-Encoding`（不转发 `Authorization`——Grafana 用匿名 Viewer，转发它只会让令牌进 Grafana 日志）。响应回传状态码、`Content-Type` 与 `Set-Cookie` 以外的头。

### 阈值配置（`application.yml`，前缀 `paideia.ops`）

```yaml
paideia:
  ops:
    prometheus-url: http://127.0.0.1:9090
    query-timeout-ms: 2000
    thresholds:
      disk-used-warn: 0.70
      disk-used-error: 0.85
      unit-memory-warn: 0.75
      unit-memory-error: 0.90
      backup-stale-hours: 36
```

## 持久化与迁移

**零迁移、零新表。** 历史在 Prometheus TSDB（`/var/lib/prometheus`，`--storage.tsdb.retention.time=3d`）；摘要页不落任何数据。

## 失败处理与恢复

| 失败 | 表现 | 处理 |
| --- | --- | --- |
| Prometheus 不可达 | 摘要接口 200，全部 `UNKNOWN`，`prometheusReachable=false` | 前端显示「监控未就绪」而不是全绿 |
| 单个查询超时 | 该 group `UNKNOWN`，其余正常 | 每查询独立 2 秒超时，串行执行 |
| Grafana 不可达 | 代理返回 502 + 一句说明 | 摘要卡片仍可用（不依赖 Grafana） |
| textfile 脚本失败 | `ops.prom` 保持上一版（不删除），脚本退出码非 0 进 journal | 下个周期自动重试；不写空文件覆盖 |
| 备份失败 | `last-status.json` 记失败原因，指标 `paideia_backup_success == 0` | 告警 36 小时无成功即发邮件 |
| 监控单元崩溃 | 各自 `Restart=on-failure` | 与 `paideia` 完全独立，互不影响 |

## 安全与权限

- `8082` 管理端口、`3000`、`9090`、`9100`、`9104`、`9121`、`9115` 全部绑 `127.0.0.1`；Prometheus 与 Grafana 不接受外部连接。
- `SecurityConfiguration` 新增两条，**必须排在 `anyRequest()` 与任何更宽规则之前**：
  ```java
  .requestMatchers("/api/v1/ops/admin/**").hasRole("ADMIN")
  .requestMatchers("/api/v1/ops/grafana/**").hasRole("ADMIN")
  ```
  顺序写反不报错、只静默失效——`ApiAccessDeclarationTest` 是唯一能抓住它的机制，故控制器标 `@ApiAccess(ApiAccess.Access.ADMIN)`。
- 转发器**不得**把 `Authorization` 头带给 Grafana（见接口节）。
- 采集脚本的 `systemctl` / `docker` 参数全部是常量；脚本不接受参数，不读环境变量作为命令内容。
- `mysqldump` 凭据走 `--defaults-extra-file`（600），不出现于 `ps`、journal 或仓库。
- Grafana 的 `GF_SECURITY_ADMIN_PASSWORD` 与 SMTP 密码从环境文件（600）注入，不进仓库。

## 可观测性

监控系统自己的状态也要能被看见：

- `up{job=...}` 覆盖 prometheus / grafana（黑盒）/ 四个 exporter / 应用。
- 摘要接口自身：`paideia_ops_summary_unknown_groups`（有多少 group 是未知）——若它一直非 0，说明监控链路本身有问题。
- 备份：`paideia_backup_success`、`paideia_backup_age_seconds`、`paideia_backup_last_bytes`。

## 实施顺序

1. 应用侧指标（依赖 + 管理端口 + 端点）——**先证明 8082 能出文本，且 8081 部署链路不受影响**。
2. 装 Prometheus + 抓应用与四个 exporter——先出第一张图。
3. textfile 脚本（systemd / docker / 备份状态）。
4. 备份 timer + 恢复演练（独立可验，不依赖前面）。
5. Grafana + provisioning 面板。
6. 应用侧摘要接口 + 代理 + 管理端页面。
7. 告警规则 + 邮件验证。
8. `always.md`、更新历史、验证报告。

顺序理由：2 与 4 是整件事的两个价值点，各自可独立验收；3 依赖 2 的 textfile 目录，6 依赖 2 与 5。

## 测试策略

| 层 | 内容 |
| --- | --- |
| 单元 | 四态判定（含 UNKNOWN 分支）、阈值边界、Prometheus 响应解析、转发路径构造（子路径 + query 保留） |
| 集成（`paideia-app`） | `SUMMARY` 接口：ADMIN 200 / LEARNER 403 / 匿名 401；Prometheus 桩不可达时全 UNKNOWN 且 200 |
| 契约 | `ApiAccessDeclarationTest` 覆盖新端点；GET-only 断言 |
| 服务器实测 | 每个 AC 一条命令与输出，写进 `testing/report.md` |

**不做**：真并发压满线程池（AC-005 用注入值）；不写 e2e（面板由 iframe 承载，e2e 只能验证 iframe 存在，价值低——这条要写进报告的「未覆盖」）。

## 需求追踪

| 设计元素 | REQ | AC |
| --- | --- | --- |
| 8082 + prometheus registry | 001-005 | 003、013 |
| node/mysqld/redis exporter | 006-008 | 009 |
| textfile 脚本 | 009-012、016、021 | 002、011 |
| blackbox | 013-015 | 009 |
| `paideia.ops.thresholds` | 010、031 | 005、008 |
| backup timer | 019-023 | 006、007 |
| `OpsPage` | 027-030 | 001、004 |
| `SecurityConfiguration` 两条规则 | 035 | 003、010 |
| alert rules | 031-033 | 007、008 |

## 待决定事项

- **D-1** Grafana 访问形态：默认 I-07（回环 + 反代 + 匿名 Viewer）。若改为开公网 3000，需要用户在云控制台加安全组规则——**这一步 Agent 做不了**。
- **D-2** 告警收件地址（Q-05）。
- **D-3** 代理器的已知限制：只支持 GET/POST；Grafana Live（`/api/live/ws`）不转发，故面板用固定间隔自动刷新而非 Live 推送。若将来需要 Live，升级触发条件是「确实需要秒级推送」。
