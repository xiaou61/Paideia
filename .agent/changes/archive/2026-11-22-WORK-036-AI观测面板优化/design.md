---
artifact: design
work_id: WORK-036
work: AI 观测面板优化
status: approved
created: 2026-10-23
updated: 2026-10-23
approved_by: xiaou61
approved_at: 2026-10-23 14:34:03 +0800
approver_role: CTO
---

# 设计 · AI 观测面板优化

## A 组：分桶序列

### 接口

```
GET /api/v1/ai/admin/runs/series
  ?from=&to=&kind=&status=&model=&userId=
```

返回：

```json
{
  "from": "...", "to": "...", "bucket": "hour" | "day", "zone": "Asia/Shanghai",
  "points": [
    { "at": "2026-10-22T00:00:00+08:00", "total": 12, "ok": 10, "failed": 1, "timeout": 1,
      "durationP50": 820, "durationP95": 3400, "ttftP50": 210,
      "inputTokens": 12000, "outputTokens": 3000, "cacheReadTokens": 9000, "credits": 42 }
  ]
}
```

`at` 是桶的**起点**（含），桶宽由 `bucket` 决定。

### 分桶规则（纯函数 `Segmentation.of`）

```
跨度 = to - from
跨度 ≤ 48 小时 → hour 桶，否则 → day 桶
日桶边界按 zone 的当日 00:00 对齐；小时桶按整点对齐
```

**为什么要对齐而不是"从 from 起每 24 小时切一段"**：不对齐的话，用户在 15:30 打开页面，每个"日桶"实际是 15:30 到次日 15:30，看起来"昨天"包含今天下午的数据——那是个很容易被当成 bug 的口径错误。

### SQL

```sql
SELECT DATE_FORMAT(CONVERT_TZ(started_at, '+00:00', :offset), :fmt) AS bucket,
       COUNT(*) AS total,
       SUM(status = 'ok') AS ok, SUM(status = 'failed') AS failed, SUM(status = 'timeout') AS timeout,
       SUM(input_tokens) AS inputTokens, SUM(output_tokens) AS outputTokens,
       SUM(cache_read_tokens) AS cacheReadTokens, SUM(credits) AS credits
FROM ai_runs
WHERE started_at >= :from AND started_at < :to
  [AND kind = :kind] [AND status = :status] [AND model = :model] [AND user_id = :userId]
GROUP BY bucket
```

分位数按桶算需要窗口函数套在分组上，MySQL 可以用两层：

```sql
SELECT bucket, MAX(CASE WHEN rn = CEIL(n * 0.5) THEN duration_ms END) AS p50, ...
FROM (SELECT DATE_FORMAT(...) AS bucket, duration_ms,
             ROW_NUMBER() OVER (PARTITION BY DATE_FORMAT(...) ORDER BY duration_ms) AS rn,
             COUNT(*) OVER (PARTITION BY DATE_FORMAT(...)) AS n
      FROM ai_runs WHERE ... AND duration_ms IS NOT NULL
        AND status IN ('ok','failed','timeout')) t
GROUP BY bucket
```

两次查询（计数一次、分位数一次）在应用层按 `bucket` 合并。**不拼成一条**：MySQL 里把聚合与窗口塞进同一层会让执行计划退化，且读起来像谜语。

### 空桶补零

```java
List<Point> fill(List<Point> raw, Instant from, Instant to, ZoneId zone, Granularity g)
```

按 `from` 对齐后的第一个桶起点，逐桶前移直达 `to`，缺的补零值（分位数给 `null`，不是 0——"没有数据"与"0 毫秒"不是一回事）。

## B 组

### 采集健康

```
GET /api/v1/ai/admin/runs/health
```

```json
{ "enabled": true, "sampling": 1.0, "queueDepth": 0, "queueCapacity": 10000,
  "dropped": 0, "writeFailures": 0, "lastSuccessAt": "2026-10-23T05:10:00Z" }
```

`RunWriteQueue` 新增：`int depth()`、`Instant lastSuccessAt()`（`AtomicReference`，在 `write()` 成功后设置）。

采集关闭时 `RunWriteQueue` 的 bean 仍存在（`TelemetryConfiguration` 只是换 `RunRecorder`），所以这几个数字仍然可读，返回 `enabled=false`。

### 采样提示

`overview` 与 `series` 的响应里加 `sampling: number`。前端在 `sampling < 1` 时渲染提示条。

### 积分对账

```
GET /api/v1/ai/admin/runs/reconcile?from=&to=
```

```json
{ "threshold": 10,
  "days": [ { "day": "2026-10-22", "recorded": 42, "ledger": 40, "diff": 2, "abnormal": false } ] }
```

`recorded` = `SUM(ai_runs.credits)`；`ledger` = `-SUM(ai_credit_ledger.delta) WHERE kind NOT IN ('RESERVE','ADJUST')`。

**为什么排除 `RESERVE`**：`reserve` 是「先扣余额、不写流水」（见 `CreditService` 类注释），实际不产生流水行。`ADJUST` 是管理员发分，不是消耗。剩下 `SETTLE` / `MEMORY_EXTRACT` / `INTERVIEW_REVIEW` / `RESUME` / `MCP_ASK` / `CAMP` / `REVIEW` 都是负 `delta` 的消耗，取负号后与 `credits` 同向。

**已知的正常差异来源**（写进界面说明）：缓存命中不计费；成功率采样会把成功的 `credits` 行删掉而流水保留（**采样 < 1 时对账必然出现正差**——这条很关键，否则每次采样都会报"记录比流水多"）。

下钻：`GET /api/v1/ai/admin/runs?from=<当天>&to=<次日>&...` 复用已有列表；差值最大的轮次由一条 `ORDER BY ABS(credits) DESC` 的查询给出。

### 耗时构成

不新增接口：`detail()` 的响应里加 `breakdown`：

```json
{ "queueMs": 12, "llmMs": 1200, "toolMs": 800, "otherMs": 90, "totalMs": 2102 }
```

`llmMs` = 各 `llm` span 的 `durationMs` 之和；`toolMs` = 各 `tool` span 之和；`queueMs` = `ai_runs.queue_ms`；`otherMs` = `durationMs - queueMs - llmMs - toolMs`，**负数时夹到 0**（时钟不精确、并发工具调用会重叠，实测可能出现负值）。

## C 组：反馈

### 表

```sql
CREATE TABLE ai_feedback (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL,
  conversation_id BIGINT UNSIGNED NULL,
  message_id      BIGINT UNSIGNED NULL,
  trace_id        VARCHAR(40)     NULL,
  kind            VARCHAR(32)     NULL,
  rating          VARCHAR(8)      NOT NULL,
  reason          VARCHAR(24)     NULL,
  created_at      DATETIME(3)     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_feedback_user_trace (user_id, trace_id),
  KEY idx_ai_feedback_created (created_at),
  KEY idx_ai_feedback_rating (rating, created_at)
) ENGINE = InnoDB;
```

- 主键自增、时间由应用写入（项目规则：不让数据库取业务时间）。
- `uk_ai_feedback_user_trace` 让覆盖语义成立。`trace_id` 为 NULL 时 MySQL 允许多行，这是刻意的（关掉采集时没有轮次可挂）。

### 接口

```
POST /api/v1/ai/feedback          { traceId, conversationId, messageId, kind, rating, reason }
GET  /api/v1/ai/admin/feedback    ?from=&to=&rating=&reason=&kind=&page=&size=
```

**提交端点放在 `/api/v1/ai/**`（authenticated），不在 `/admin/**`**——学习者要用。用户 id 从会话上下文取，**请求体里不接受 `userId`**（与记忆控制面同一纪律：传不了别人的）。

`rating` 与 `reason` 用字符串白名单校验，非法值返回 400 而不是静默存。

### 与轮次的关联

`traceId` 从哪来？SSE 的 `done` 事件里加一个 `traceId` 字段。

**这是本项唯一需要动对话主链路的地方**：`AssistantConversationController.onCompleted` 里已经拿到了 `run`，加一行 `payload.put("traceId", ...)` 即可。需要 `RunScope` 暴露 `traceId()`。

**关掉采集时**没有 trace id（`NoopScope` 没有 id）。此时 `traceId` 为 null，反馈仍能提交，只是挂不到轮次上——比"关掉采集就不能反馈"合理。

### 前端

`conversation-panel.tsx` 的每条助手消息下加一行：一个赞按钮、一个踩按钮。踩之后展开四个原因按钮。提交后按钮变成"已反馈"。

**状态放本地**（`useState`），不请求服务端回读：一次会话里刷新页面的概率低，而多一次请求就多一个失败面。代价是刷新后看不出已经反馈过——**这是有意的取舍**，写在注释里。

## D 组

### traceId 搜索

`AiRunMapper.page` 与 `countPage` 加参数 `tracePrefix`：

```sql
<if test="tracePrefix != null">AND trace_id LIKE CONCAT(#{tracePrefix}, '%')</if>
```

**前缀匹配而不是全等**：日志里复制的 id 常常带前后空格或只有前 8 位。`LIKE 'x%'` 能走 `uk_ai_runs_trace` 索引（前缀匹配是范围扫描），全等以外的输入也不会退化成全表扫。

### 子步骤

`SpanSpec` 加：

```java
public static SpanSpec child(String kind, String name, int parentSeq) {
    return new SpanSpec(kind, name, parentSeq);
}
```

`SpanSpec` 从 `record(kind, name)` 变成 `record(kind, name, Integer parentSeq)`，原有工厂传 `null`。`SpanRecord` 已经有 `parentSeq` 字段，`StepScopeImpl.close()` 里把 `spec.parentSeq()` 传进去即可。

前端：`RunSteps` 按 `parentSeq` 算缩进层级，用 `pl-*` 类。

**诚实说明**：仓库里**没有产生子步骤的调用点**——`SpanSpec.retrieval` 定义了零使用，工具内部也没有二次模型调用。所以本期只做"接口与渲染都支持"，并写一个单测证明传了 parent 之后落库与读取都对。**不虚构一个假的子步骤埋点**。

## 配置项

```yaml
paideia:
  ai:
    telemetry:
      reconcile-threshold: 10   # 对账差值绝对值超过它就标记异常
      zone: Asia/Shanghai        # 分桶与"按天"的口径
```

两项都进 `TelemetryProperties`，有默认值。
