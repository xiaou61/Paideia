---
artifact: design
work_id: WORK-061
work: 架构守卫与模块治理
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 18:34:50 +0800
approver_role: CTO
---

# 架构守卫与模块治理设计

## 系统上下文

五片互不依赖，各自的上下文如下。

**P1 守卫回路**

```
开发者/会话改代码
  └─ 提交前：脚本切片二
       ├─ ModularityTest（模块边界）
       ├─ 迁移三件（命名/校验和/种子隔离）
       └─ 新增：ApiSurfaceTest（端点必须声明 @ApiAccess）
                ApiContractTest（声明 ADMIN 的端点，学习者必须 403；401/404/405/包络/traceId）
                AuthorizationIsolationTest（令牌伪造、跨用户读写、CORS）
```

**P2 档案防漂移**

```
gen-arch-diagram.mjs --check
  ├─ 四张结构图与声明比对（已有）
  └─ README「现有图」表六个数与事实比对（新增）
       ├─ 不符 → 打印哪一项、退出 1
       └─ 生成模式：只重写四张结构图，不碰 README
```

**P3 模块搬家**

```
backend/pom.xml modules: 11 → 12
paideia-app ──依赖──> paideia-ops ──依赖──> platform / persistence / security / ai
（包名 io.github.xiaou61.ops 不变 ⇒ spring-modulith 模块图不变；
  只换 Maven 归属；app 与 ops 的类名、端点、配置前缀零改动）
```

**P4 记忆提取重试**

```
一轮对话结束（回答已发给用户）
  └─ scheduleIfDue（同步、极轻）
       └─ 【一个事务】claimExtractionSlice（条件 UPDATE 抢游标）
                     + INSERT ai_memory_extraction_jobs（pending）
            ├─ 抢到 → 事务提交后：executor.execute(dispatch(jobId))
            └─ 没抢到 → 什么都不做
dispatch：条件 UPDATE pending → running（attempts+1, locked_at=now）
  ├─ 抢赢 → run()：设置/凭据检查 → 预留 → 上游调用 → 结算或全额退款 → 写记忆
  │        ├─ 成功 → DELETE 任务行
  │        └─ 失败 → attempts < 3 ? pending + 退避(next_attempt_at) : failed + last_error
  └─ 抢输/线程池满 → 留在 pending，中继会拾

中继 MemoryExtractionRelay（@Scheduled，默认 60 秒）
  1. 回收卡死：running 且 locked_at 早于超时（默认 10 分钟）→ 置回 pending
  2. 拾取到期 pending（LIMIT 5）→ 走同一 dispatch

failed 行：保留供诊断，由 MemoryRetentionPurger 按时限回收
注销：AiUserDeletionListener 增加一条 deleteByUser
```

## 组件与职责

| 组件 | 职责 | 改动 |
| --- | --- | --- |
| `AGENTS.md` 测试节 | 切片二命令与注记 | 加三个类名；实测后更新条数/耗时 |
| `scripts/gen-arch-diagram.mjs` | 结构图生成与防漂移 | `--check` 增加 README 计数断言函数 |
| `docs/architecture/README.md` | 档案索引 | 模块数 11 → 12 |
| `backend/pom.xml` | 模块清单 | 加 `paideia-ops` |
| `backend/paideia-ops/pom.xml` | 新模块声明 | 新建；依赖 platform、persistence、security、ai |
| `paideia-ops/.../ops/**`（30 个类） | 运维面（Prometheus/Loki/Tempo 客户端、事件、诊断、反代） | 从 app 迁入，**内容不改** |
| `paideia-app/pom.xml` | 装配 | 加 ops 依赖 |
| `MemoryExtractionService` | 编排：入队判定、dispatch、run | 入队改为同事务写任务行；失败不再直接吞 |
| `MemoryExtractionJobs`（新建） | 任务表读写：enqueue、claim、complete、fail、reclaim、deleteByUser、due | 新建（独立 bean，保证 @Transactional 生效） |
| `MemoryExtractionJobMapper`（新建） | 手写 MyBatis 注解 SQL | 新建 |
| `MemoryExtractionRelay`（新建） | @Scheduled 中继：回收 + 拾取 | 新建 |
| `MemoryProperties` | 提取参数 | 加 attempts/退避/卡死超时/中继周期（默认值即可用） |
| `MemoryRetentionPurger` | 保留清理 | 增加 failed 行回收 |
| `AiUserDeletionListener` | 注销级联 | 增加任务表删除 |
| `ai` 包根 8 个类型 | 内部类型 | 移入 internal 对应子包 |

## 接口与数据

### 任务表（P4）

```sql
CREATE TABLE ai_memory_extraction_jobs (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  user_id           BIGINT       NOT NULL,
  conversation_id   BIGINT       NOT NULL,
  from_message_id   BIGINT       NOT NULL,
  to_message_id     BIGINT       NOT NULL,
  status            VARCHAR(16)  NOT NULL,            -- pending / running / failed
  attempts          INT          NOT NULL DEFAULT 0,
  next_attempt_at   DATETIME(3)  NOT NULL,
  locked_at         DATETIME(3)  NULL,
  last_error        VARCHAR(500) NULL,
  created_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_memory_job_slice (conversation_id, from_message_id, to_message_id),
  KEY idx_memory_job_due (status, next_attempt_at)
);
```

约束与语义：

- **游标与任务行同事务**：`claimExtractionSlice` 与 `INSERT` 在同一个 `@Transactional` 方法里，回滚则两者都不发生。
- **抢占**：`UPDATE ... SET status='running', attempts=attempts+1, locked_at=NOW(3) WHERE id=? AND status='pending'`，影响行数为 1 才算抢到（沿用仓内条件 UPDATE 手法）。
- **退避**：`next_attempt_at = now + min(1 分钟 × 2^(attempts-1), 10 分钟)`。
- **上限 3 次**：达到后 `status='failed'`，不再被中继拾取；行保留供诊断。
- **卡死回收**：`status='running' AND locked_at < NOW(3) - 10 分钟` → 置回 `pending`（崩溃恢复）。
- **计费**：每次尝试独立走既有「预留 → 结算/全额退款」；失败尝试的退款落流水，不双扣。
- **注销**：`deleteByUser(user_id)`。
- **回收**：`failed` 且 `created_at` 超过保留期的行由保留清理器删除。

### README 断言（P2）

在 `--check` 里对 `docs/architecture/README.md` 匹配「现有图」表的六项：

| 断言项 | 正则形态 | 事实来源 |
| --- | --- | --- |
| 后端模块数 | `后端 (\d+) 个 Maven 模块` | backend/pom.xml modules |
| 前端包数 | `前端 (\d+) 个 workspace 包` | frontend 扫描 |
| 表数、外键数 | `(\d+) 张表与 (\d+) 条真实外键` | 迁移扫描 |
| 端点数、控制器数 | `(\d+) 个端点 / (\d+) 个控制器` | 控制器扫描 |

任一不匹配：打印「README 与事实不一致：后端模块数 README=11 事实=12」，退出 1。生成模式不读写 README。

### P3 的 pom 依赖面（实现约束）

`paideia-ops` 的 `<dependencies>` 只允许：`paideia-platform`、`paideia-persistence`、`paideia-security`、`paideia-ai`（+ 外部库如 Spring Web/Micrometer，以编译器实际需要为准）。若编译暴露了未预期的模块依赖，停下来改设计而不是顺手加依赖。

### P5 的类型去向

| 类型 | 去向 |
| --- | --- |
| `AssistantAnswerer` | `ai/internal/conversation/` |
| `ConversationTurn` | `ai/internal/conversation/` |
| `ContentClassification`、`ContentClassifier` | `ai/internal/decision/` |
| `NoteDraft` | `ai/internal/tools/` |
| `RunKind`、`RunOutcome` | `ai/internal/telemetry/` |
| `SummaryVisibility` | `ai/internal/summary/` |

若某类型被其他根类型（公开契约）的签名引用，则留在根并在报告注明——以编译器与 `ModularityTest` 判定。
