---
artifact: design
work_id: WORK-049
work: AIOps 缺口修复：事件闭环、历史复用、告警关联与评测可信度
status: approved
created: 2026-10-28
updated: 2026-10-28
approved_by: xiaou61
approved_at: 2026-10-28 13:55:09 +0800
approver_role: CTO
---

# AIOps 缺口修复设计

## 系统上下文

```
Grafana（17 条规则）
  ├ contact-points.yaml：resolved 也投递（REQ-001）
  └ 阈值来自 ops-thresholds.conf（REQ-008）
        │ webhook（回环 + 令牌）
        ▼
OpsHookController ──> OpsIncidentService ──> ops_incidents / ops_incident_events
                              │  ● close(id)：手动关闭（REQ-002）
                              │  ● recentDoneDiagnosisForInstance()（REQ-006）
                              │  ● catchUpCandidate()（REQ-009）
                              ▼
OpsDiagnosisService.start()
  ├ 冷却（现状）
  ├ 同实例复用窗口（REQ-006）→ 只记时间线，返回 -1
  └ 提交异步 run()
        ▼
OpsIncidentContext.build(incidentId)  ← 新类（REQ-004/005）
  ├ 同指纹历史 3 条 + 同告警名补 2 条（含上次结论首行与评分）
  └ 同期 OPEN 事件 5 条
        ▼
OpsEvidenceCollector.collect(..., context)
  ├ 固定指标目录（现状）+ z 值（REQ-007）
  └ context 计入 12k 预算，自身 ≤2000 字符
        ▼
OpsDiagnosisPrompt v4（新增 <context>）
        ▼
一次模型调用 → ops_diagnoses（不变）
```

## 一、REQ-001 事件自动关闭

改动只有配置：`deploy/ops/provisioning/alerting/contact-points.yaml` 的 `paideia-ops-ai` 收件人 `disableResolveMessage: true → false`，并改注释。

行为不变式：

- Grafana 在告警恢复时投递 `status=resolved`，`alerts[].labels` 与 firing 完全相同，因此指纹一致，`OpsIncidentService.resolve` 能找到 OPEN 事件。
- 重复投递 resolved 时第二次 `findOpen` 为空，返回 -1，不追加时间线（现有实现已幂等）。
- 关闭后 `dedupe_key=NULL`，再 firing 走插入分支，生成新事件（现有 `reopenCreatesNewIncident` 已覆盖）。

验证：本地 `OpsHookIntegrationTest` 全绿；生产由收口任务做一次 firing→resolved→firing 合成投递（AC-001）。

## 二、REQ-002 手动关闭

### 接口

```java
// OpsIncidentService（package-private）
Detail close(long id, long actorUserId);

// OpsIncidentMapper
@Update("""
        UPDATE ops_incidents
        SET status = 'RESOLVED', resolved_at = #{at}, dedupe_key = NULL, updated_at = #{at}
        WHERE id = #{id} AND status = 'OPEN'
        """)
int resolveById(@Param("id") long id, @Param("at") Instant at);
```

```java
// OpsIncidentController
@PostMapping("/{id}/close")
ApiResponse<OpsIncidentService.Detail> close(@PathVariable("id") long id)
```

`OpsIncidentEvent` 增加常量 `KIND_CLOSED = "CLOSED"`。

### 语义

1. `findById` 不存在 → 404。
2. 已 `RESOLVED` → 直接返回 detail，不追加时间线（幂等）。
3. `resolveById` 返回 0（并发把状态改了）→ 重新读一次返回 detail，同样不追加。
4. 成功 → 追加 `CLOSED`，message 「人工关闭」，`actor_user_id` 为当前管理员。

不加表、不加列；操作者靠时间线已有的 `actor_user_id` 记录。

前端：事件详情在「添加备注」旁增加「关闭事件」按钮（仅 OPEN 时显示），点击后调用关闭端点并刷新详情与列表。`KIND_LABEL` 增加 `CLOSED: '人工关闭'`。

## 三、REQ-003 分页与筛选

### 接口

```java
// OpsIncidentService
List<View> list(String status, int limit, Integer sinceHours, Instant beforeLastSeen, Long beforeId);

// OpsIncidentMapper
List<OpsIncident> listPage(@Param("status") String status,
                           @Param("since") Instant since,
                           @Param("beforeLastSeen") Instant beforeLastSeen,
                           @Param("beforeId") Long beforeId,
                           @Param("limit") int limit);
```

SQL（MyBatis `<script>`）：

```sql
SELECT ... FROM ops_incidents
<where>
  <if test="status != null">status = #{status}</if>
  <if test="since != null">AND last_seen &gt;= #{since}</if>
  <if test="beforeLastSeen != null">
    AND (last_seen &lt; #{beforeLastSeen}
         OR (last_seen = #{beforeLastSeen} AND id &lt; #{beforeId}))
  </if>
</where>
ORDER BY last_seen DESC, id DESC
LIMIT #{limit}
```

keyset 而不是 OFFSET：同一 `last_seen` 的行靠 `id` 破平，翻页不重不漏（`idx_ops_incidents_status_last` 命中）。

### 端点

```
GET /api/v1/ops/admin/incidents?status=OPEN&limit=50&sinceHours=168&beforeLastSeen=<ISO>&beforeId=42
```

- `limit` 上限仍 100。
- `sinceHours` 空 = 全部；只接受正整数，非法值 400。
- 两个游标参数必须同时给或同时不给；只给一个 400。

### 前端

`useInfiniteQuery`，queryKey `['admin-ops-incidents', status, sinceHours]`；`getNextPageParam` 取最后一页最后一行的 `lastSeen`/`id`。时间筛选用三个按钮（全部 / 24 小时 / 7 天），与现有状态按钮同排。列表底部「加载更多」按钮，`hasNextPage=false` 时隐藏。

判定「还有下一页」不需要 count：本页条数等于 limit 即认为可能还有；最后返回空页时按钮消失。

## 四、REQ-004 / REQ-005 证据上下文

### 新类

```java
// package io.github.xiaou61.ops
@Service
class OpsIncidentContext {

    /** 无事件（手动诊断且未挂事件）时返回固定文案，不返回 null。 */
    String build(Long incidentId, String alertName, Instant now);
}
```

上限：返回字符串 ≤2000 字符；超出时先截历史段，再截同期段。段落格式：

```
## 历史同类事件
- 2026-10-20 14:02 持续 35 分钟 状态已恢复 诊断#7 评分不准 结论：发版后 5xx 突增，根因是 NPE
## 同期其他事件
- [OPEN] HTTP 5xx 比例超过 5% severity=critical 首次=2026-10-28 12:40
```

空段落必须显式写「无历史同类事件」「无同期事件」。

### 查询

```java
// OpsIncidentMapper
List<HistoryRow> listHistoryByFingerprint(@Param("fingerprint") String fingerprint,
                                          @Param("excludeId") long excludeId,
                                          @Param("since") Instant since,
                                          @Param("limit") int limit);

List<HistoryRow> listHistoryByAlertName(@Param("alertName") String alertName,
                                        @Param("excludeId") long excludeId,
                                        @Param("since") Instant since,
                                        @Param("limit") int limit);

List<OpsIncident> listOpenSince(@Param("since") Instant since,
                                @Param("excludeId") long excludeId,
                                @Param("limit") int limit);

record HistoryRow(long incidentId, String alertName, Instant firstSeen, Instant lastSeen,
                  String status, Long diagnosisId, String reportMd, Integer rating) {}
```

SQL 要点：历史查询 `LEFT JOIN ops_diagnoses d ON d.id = i.last_diagnosis_id`，这样一次拿到结论与评分；`since = now - 30 天`；先按指纹查 3 条，不足时按 alert_name 查 2 条并跳过已在结果里的 id。同期查询只取 `status='OPEN' AND last_seen >= now-30min AND id <> 本次`，上限 5。

结论首行：报告 Markdown 的第一行非空、非 `#` 标题的文本；没有报告时写「无结论」。

### 证据与提示词

```java
// OpsDiagnoser.Evidence（字段顺序即证据优先级）
record Evidence(String trigger, String alertName, String question,
                String summary, String metrics, String baselines,
                String context, String alerts, String logs, String traces, String deploys) {}
```

`OpsEvidenceCollector`：

```java
OpsDiagnoser.Evidence collect(String trigger, String alertName, String question, String context);
```

- `context` 为 null 时按空串处理。
- `nonLogChars` 计入 context，日志预算相应减少。
- `formatLogs` 与 trace 的降级文案不变。

提示词 v4：

- `OpsDiagnosisPrompt.VERSION = "ops-diagnosis-v4"`。
- `user()` 在 `<baselines>` 之后插入 `<context>`。
- `system()` 增一条规则：「`<context>` 是历史与同期事实；引用历史结论时必须写明日期，且不得把上次结论当成本次结论；历史为空时不要推断有过类似故障。」
- `<context>` 与 `<logs>`/`<alerts>`/`<traces>` 一样按不可信数据处理。

## 五、REQ-006 同实例复用窗口

```java
// OpsIncidentService
/** @return 命中的已完成诊断 id；没有则空。 */
Optional<Long> recentDoneDiagnosisForInstance(long incidentId, Instant now, long windowSeconds);

// OpsDiagnosisMapper
List<RecentDone> recentDoneSince(@Param("since") Instant since, @Param("limit") int limit);
record RecentDone(long id, Long incidentId, Instant finishedAt, String labelsJson, String alertName) {}
```

流程（`OpsDiagnosisService.start`，仅 `incidentId != null`）：

1. 现有事件冷却检查先跑（窗口 600s，通常已能挡住同事件）。
2. 读本次事件的 `labels_json`，取 `instance`；为空时用空串。
3. 查 `now - windowSeconds` 内的 DONE 诊断（最多 20 条），join 事件的 `labels_json`，逐条比较 instance；命中第一个即停。
4. 命中 → 给新事件追加 `NOTE`「复用诊断 #N（同实例，<秒>s 内已有结论）」→ `return -1`，不写 `last_diagnosed_at`（窗口过后仍可诊断）。
5. 没命中 → 走原流程。

配置：`paideia.ops.diagnosis.related-window-seconds` 默认 600；`0` 关闭。

只在 `trigger=alert` 时生效；手动诊断不受影响。上一次诊断 FAILED 不在 `DONE` 集合里，因此不会拦。

## 六、REQ-007 z-score

### 录制规则（`deploy/ops/prometheus-rules.yml`，组 `paideia-baseline`）

对 cpu / 5xx / p95 各加三条：

```yaml
- record: paideia:baseline_cpu:mean7d
  expr: avg_over_time(paideia:baseline_cpu:current[7d])
- record: paideia:baseline_cpu:std7d
  expr: stddev_over_time(paideia:baseline_cpu:current[7d])
- record: paideia:baseline_cpu:z
  expr: >-
    (paideia:baseline_cpu:current - paideia:baseline_cpu:mean7d)
    / clamp_min(paideia:baseline_cpu:std7d, 0.5)
```

分母下限（保证低方差序列不产生天文数字）：cpu `0.5`（百分点）、5xx `0.01`（百分点）、p95 `0.005`（秒）。

### 证据

`metricBaseline` 增加 `mean7d`、`std7d`、`z` 三个值；样本不足时仍写「基线不足」，z 写「—」。

### 告警

三条基线告警的表达式在现有比值条件上并联 z 条件，保留原有的绝对值门槛：

```
($A > 3 * $B && $A > 10) || (z > 3 && $A > 10)    // 5xx
($A > 2 * $B && $A > 70) || (z > 3 && $A > 70)    // CPU
($A > 2 * $B && $A > 1)  || (z > 3 && $A > 1)     // P95
```

z 序列通过新增 `refId`（Prometheus 数据源 instant query）读录制值；`noDataState` 保持 `NoData`/`OK` 现状。

### 测试

`deploy/ops/prometheus-rules.test.yml` 增加三组用例：z>3 触发、方差大不触发、样本不足不触发。

## 七、REQ-008 阈值单一来源

### 文件

`deploy/ops/ops-thresholds.conf`（新增，非敏感，入库）：

```sh
# 阈值单一来源（WORK-049）。改完重跑 install-ops.sh 并重启 paideia。
DISK_USED_ERROR=0.85
UNIT_MEMORY_ERROR=0.90
BACKUP_STALE_HOURS=36
```

### 渲染（`deploy/ops/install-ops.sh`）

1. `source "$HERE/ops-thresholds.conf"`，校验三个变量非空且范围合法（0<ratio<1，hours>0）。
2. 计算 `DISK_FREE_RATIO=$(awk "BEGIN{printf \"%.2f\", 1-$DISK_USED_ERROR}")`。
3. 用 `sed` 替换已安装的 `/etc/grafana/provisioning/alerting/paideia-rules.yaml` 中三个占位符：
   - `__DISK_FREE_RATIO__`、`__UNIT_MEMORY_RATIO__`、`__BACKUP_STALE_HOURS__`。
4. 渲染后 `grep -q '__[A-Z_]*__'` 仍命中则报错退出（防止半渲染）。
5. 写 systemd drop-in（沿用现有 `ops.conf`，保留令牌与诊断账号）：

```
Environment="PAIDEIA_OPS_THRESHOLDS_DISKUSEDERROR=0.85"
Environment="PAIDEIA_OPS_THRESHOLDS_UNITMEMORYERROR=0.90"
Environment="PAIDEIA_OPS_THRESHOLDS_BACKUPSTALEHOURS=36"
```

Spring 宽松绑定把 `PAIDEIA_OPS_THRESHOLDS_DISKUSEDERROR` 绑到 `paideia.ops.thresholds.disk-used-error`（无短横线形式，已验证规则）。

### 源文件

`paideia-rules.yaml` 里三处硬编码换成占位符。该文件与 `contact-points.yaml` 一样属于模板：安装时渲染，仓库不保证可直接加载。

回滚：备份 `/etc/grafana/provisioning` 与 `/etc/systemd/system/paideia.service.d/ops.conf`；恢复后 `systemctl restart paideia grafana-server`。

## 八、REQ-009 重启补诊断

```java
// OpsIncidentMapper
Optional<OpsIncident> findCatchUpCandidate(@Param("since") Instant since,
                                            @Param("diagnosedBefore") Instant diagnosedBefore);
```

```sql
SELECT ... FROM ops_incidents i
WHERE i.status = 'OPEN'
  AND i.created_at >= #{since}
  AND (i.last_diagnosed_at IS NULL OR i.last_diagnosed_at < #{diagnosedBefore})
  AND NOT EXISTS (SELECT 1 FROM ops_diagnoses d
                  WHERE d.incident_id = i.id AND d.status = 'DONE')
ORDER BY i.last_seen DESC, i.id DESC
LIMIT 1
```

`OpsDiagnosisService` 的 `@EventListener(ApplicationReadyEvent.class)` 在 `cleanupStale` 之后：

1. 开关 `catch-up-enabled` 默认 true；`diagnosis.user-id <= 0` 时直接返回（与 webhook 同一纪律，测试环境默认 off）。
2. `since = now - 6h`；`diagnosedBefore = now - 10min`。
3. 命中 → `start("restart", id, alertName, null, userId)`；时间线写「重启补诊断」。
4. 每次启动最多调用一次 `start`；`start` 内部仍受单飞与冷却约束。

## 九、REQ-010 / REQ-011 评测

- `golden-set.json` 从 12 条扩到 24 条，新增：目标下线、磁盘不足、备份陈旧、Laya 内存、ERROR 突增、日志基线、SLO 慢烧，以及五种降级（Loki 不可达、Tempo 不可达、基线不足、SLI 无流量、Prometheus 不可达）。每条 `mustMention` 至少两个可在证据里核对的事实。
- `OpsDiagnosisGoldenSetTest`：证据标签增加 `<context>`；`goldenSetIsWideEnough` 从 ≥10 改为 ≥20；`evidenceOf` 读 `context` 字段。
- live 模式已具备「逐条打分 + 通过率 + `target/ops-eval-report.md`」；本次只补一条文档：正常运行命令为 `PAIDEIA_OPS_EVAL=1 DEEPSEEK_API_KEY=... mvn -B -pl paideia-ai test -Dtest=OpsDiagnosisGoldenSetTest`，结果文件路径写入当次测试报告。

## 十、REQ-013 Grafana 深链

事件详情头部增加两个链接（新窗口打开）：

```ts
const base = '/api/v1/ops/grafana/d/paideia-ops/ops'
const from = new Date(incident.firstSeen).getTime() - 15 * 60_000
const to = new Date(incident.resolvedAt ?? incident.lastSeen).getTime() + 15 * 60_000
面板：`${base}?from=${from}&to=${to}`
日志：`${base}?viewPanel=10&from=${from}&to=${to}`
```

- 走已有反代，不开新端口、不加 Grafana 权限。
- 日期非法时回退到「现在 - 1 小时 / 现在」。
- 受限：Grafana 若要求登录，链接会落到它的登录页（既有约束，不在本项改）。

## 十一、REQ-014 日志单元可配置

```java
// OpsProperties.Diagnosis
private List<String> units = List.of("paideia", "laya");
```

- `OpsEvidenceCollector.logs(unit, level, minutes, limit)`：`units.contains(unit)` 才接受，否则仍抛「未知单元」。
- `formatDiagnosisLogs`：遍历 `units`，每个单元一条 `{unit="X"} |~ ...` 查询，总行数仍受 `maxLogLines` 与字符预算限制。
- 配置示例：`paideia.ops.diagnosis.units: [paideia, laya, rustfs]`。
- 单元名进 LogQL 前只做白名单比较，不做转义拼接。

## 失败处理与恢复

| 失败 | 行为 |
| --- | --- |
| resolved 重复投递 | 无 OPEN 事件，返回 -1，不写时间线 |
| 手动关闭并发 | 条件 UPDATE 只成功一次；第二次返回当前详情，不追加 |
| 分页游标只给一半 | 400，不猜 |
| 历史查询超时 | context 写「历史查询失败」，诊断继续 |
| 同期查询超时 | context 写「同期事件不可达」，诊断继续 |
| 复用窗口查询失败 | 记日志，不拦诊断（宁可多花一次，不可少诊断一次） |
| 阈值文件缺失/非法 | 安装脚本非零退出，不覆盖线上规则 |
| 环境变量未生效 | 应用回落默认值；摘要与规则可能暂时不一致，校验脚本报警 |
| 补诊断失败 | 走现有 FAILED 与退款路径；下次启动仍满足条件时可再补（有 6h 窗口） |
| 深链日期非法 | 回退当前时间窗 |

## 安全与权限

- 关闭端点与其他 ops 端点同属 `/api/v1/ops/admin/** → hasRole("ADMIN")`；操作者只从令牌取。
- 历史与同期证据只含告警名、时间、状态、评分与报告首行；不含报告全文、标签全集、凭据。
- 日志单元白名单比较，模型/用户输入不进入 LogQL。
- 阈值文件不含凭据；systemd drop-in 仍是 600。
- 深链不带令牌，只带时间范围。

## 可观测性

- 复用事件时间线：关闭、复用诊断、补诊断都落时间线。
- 补诊断与复用命中各记一条 `log.info`（`ops.diagnosis.reused` / `ops.diagnosis.catch-up`）。
- 不新增指标（本项不改变 SLO 口径）。

## 测试策略

- 单元：上下文格式化与截断、复用窗口匹配、补诊断候选条件、分页游标 SQL、日志单元校验。
- 集成（MySQL 测试库）：关闭幂等与重开、分页不重不漏、历史/同期查询、复用窗口只认 DONE、补诊断开关。
- 规则：`promtool check rules` + `promtool test rules`（z-score 三组用例）。
- 前端：管理端 typecheck；事件页关闭/分页/深链的 e2e（若时间允许走一个 spec）。
- 金集：prompt 模式 CI；live 手动。
- 生产：收口任务做 firing→resolved→firing 与快烧演练。

## 需求追踪

| 需求 | 落点 |
| --- | --- |
| REQ-001 | contact-points.yaml |
| REQ-002 | OpsIncidentService.close / mapper.resolveById / controller / IncidentsPage |
| REQ-003 | listPage SQL / controller 参数 / useInfiniteQuery |
| REQ-004/005 | OpsIncidentContext + Evidence.context + prompt v4 |
| REQ-006 | recentDoneSince + OpsDiagnosisService.start |
| REQ-007 | prometheus-rules.yml / metricBaseline / Grafana 表达式 |
| REQ-008 | ops-thresholds.conf + install-ops.sh + paideia-rules.yaml 占位符 |
| REQ-009 | findCatchUpCandidate + ApplicationReady |
| REQ-010/011 | golden-set.json + GoldenSetTest |
| REQ-012 | 收口任务的演练步骤 |
| REQ-013 | IncidentsPage 深链 |
| REQ-014 | Diagnosis.units + collector |

## 待决定事项

无。实现细节均在已批准需求与上方默认值范围内。
