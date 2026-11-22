---
artifact: design
work_id: WORK-009
work: AI 网关与积分配额
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-006, WORK-010]
created: 2026-10-15
updated: 2026-10-15
approved_by: xiaou61
approved_at: 2026-10-15 17:50:31 +0800
approver_role: CTO
---

# 设计 · AI 网关与积分配额

## 1. 装配与配置

```yaml
paideia:
  ai:
    billing:
      enabled: true                 # false 时退回"平台凭据 + 不计量"
      platform-credential:
        base-url: ${PAIDEIA_AI_BASE_URL}
        model:    ${PAIDEIA_AI_MODEL}
        api-key:  ${PAIDEIA_AI_API_KEY}     # 只从环境变量/本地被忽略的配置读，不入库、不进仓库
      pricing:
        # 积分 / 千 token，与"积分:token"换算率合并成一张表
        deepseek-flash:
          input:  1
          output: 2
      reserve:
        floor: 0                    # 低于此余额直接拒绝
      limits:
        max-credits-per-call: 5000  # 单次调用上限（REQ-010）
```

- **价目表可配**（REQ-011）：改配置即改变扣减量，不需要重新构建。
- `apiKey` 走环境变量占位；仓库内只提交占位示例。**平台凭据不入库**——它与"用户自己的 Key 加密落库"不同：平台凭据是部署级配置，放数据库只会多一个泄漏面。
- 明文纪律不变：`ModelAccess.apiKey` 明文只在"即将发起调用"这一小段内存里存在，不进日志、不进响应、不被长期持有。

## 2. 数据模型（新增两张表）

迁移接着仓库当前最大号（起草时为 V10）的下一个；**编号不写死**，实施时按仓库实际最大号取。

```sql
CREATE TABLE ai_credit_accounts (
  user_id    BIGINT UNSIGNED NOT NULL,
  balance    BIGINT          NOT NULL DEFAULT 0,   -- 积分，整数（不用小数：避免浮点误差与展示口径不一致）
  updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id)
) ENGINE = InnoDB;

-- 追加式流水：只 insert，绝不 update/delete（注销时整行删除除外）
CREATE TABLE ai_credit_ledger (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id            BIGINT UNSIGNED NOT NULL,
  delta              BIGINT          NOT NULL,     -- 正=入账，负=出账
  kind               VARCHAR(16)     NOT NULL,     -- ADJUST | RESERVE | SETTLE | REFUND
  ref_message_id     BIGINT UNSIGNED NULL,         -- 指向 ai_messages.id（消耗类才有）
  operator_user_id   BIGINT UNSIGNED NULL,         -- 管理员调整时才有
  note               VARCHAR(200)    NULL,         -- 管理员填的原因
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ledger_user_time (user_id, created_at),
  KEY idx_ledger_message (ref_message_id)
) ENGINE = InnoDB;
```

**设计选择**：
- **余额单独一列而不是每次 sum 流水**：原子扣减要求一条带条件的 `UPDATE`，sum 做不到。
- **积分取整数、不用小数**：避免浮点误差，也避免"余额显示 3.999 分"这种界面问题。价目表里的 `input: 1, output: 2` 是"每千 token 多少分"，计算时向下取整并保证**单次调用至少扣 1 分**（否则长尾调用永远不扣）。
- **流水只增不改**：审计要的就是"发生过什么"。注销时整行删除（见 §8）。
- **不建跨模块外键**（沿用 V4/V5 的约定）；`ref_message_id` 指向本模块的 `ai_messages`，可加外键，但为与既有风格一致仍不加。

## 3. 凭据解析改造

`CredentialService.access(long userId)` 改为返回**平台凭据**：

| 现状 | 改后 |
| --- | --- |
| 读 `ai_model_credentials`（每用户一行），解密返回 | 读 `paideia.ai.billing.platform-credential` 配置，构造 `ModelAccess` |
| 未配置时 `Optional.empty()`（前端提示"请先配 Key"） | 平台未配置时 `Optional.empty()`（前端提示"平台尚未配置 AI 服务"） |

- **2 个调用点签名不变**（`ConversationService:116`、`SummaryService:96`）——这正是接缝的价值，业务代码不动。
- 用户侧端点（`AiCredentialController` 的保存/删除）**下线**；`view` 改为只读说明。`ai_model_credentials` 表**保留不删**（历史数据不主动清理，属已知缺口）。
- `UpstreamGuard` 对平台 `baseUrl` 照常生效。

## 4. 计量与扣减流程

```
① 校验：billing.enabled？余额 ≥ reserve.floor？          → 否则返回"积分不足"（不发上游请求）
② 预留：原子扣减 estimate
     UPDATE ai_credit_accounts SET balance = balance - :est
      WHERE user_id = :uid AND balance >= :est
     影响行数 = 0 → 余额不足，拒绝
     **不写流水**（2026-10-16 按用户要求改）：预留是实现细节，不该出现在用户看的历史里
③ 调用上游（流式），逐轮累计用量（§5）
④ 结算：actual = 用量按价目表折算，并截到单次上限
     actual < reserve → 退还差额（UPDATE balance + diff）
     actual > reserve → 补扣差额；余额不够就**不扣**，差额写进流水备注
     最后**只写一行** SETTLE，delta = −（真实扣掉的额度）；净额为 0 时不写
⑤ 失败：上游未返回任何用量 → 全额退还预留，**不写流水**（净额为零就不是一笔交易）
```

**estimate 怎么算（刻意粗糙）**：

```
estimate = ceil((单次调用上限) × 系数)
```

ponytail: 不引入 tokenizer 来精确预算——那会新增一个依赖，而预留只是为了"并发不超扣"。代价是余额会被短暂多占（毫秒级，④ 立即归还）。升级路径：真出现"并发下余额被占住导致误拒"的实测，再引入 tokenizer 或改成"按上一次同类调用的用量滑动平均"。

## 5. 修既有计量缺陷（本项的技术核心）

**问题**（已核实）：`AgentScopeConversation.stream` 的工具循环最多 4 轮，每轮一次真实上游调用，但只在最后一轮上报用量；绕满 4 轮走 `onFailed` 时**一次都不记**。

**修法**：

1. **逐轮累加**：`Round` 携带的 usage 改为"截至本轮累计"，或在外层用一个累加器把每轮 `toUsage(last.getUsage())` 相加。取后者更小。
2. **用量与结果解耦**：`Listener` 增加一个 `onUsage(TokenUsage)` 回调，在**流终止时**（成功或失败都）调用一次；`onCompleted()` 不再携带用量。

   **为什么改签名而不是给 `onFailed` 加参数**：用量和结果是两件事——调用可能失败，但已经烧掉的 token 是真的。分开之后，调用方"是否落库这条消息"与"是否为这次调用扣费"成为两个独立决定，不需要在错误路径上重复判断。`WORK-006` 给 `onFailed` 定的契约（"调用方不把这轮写进历史"）保持不变。

3. **端口纪律不变**：新增的 `onUsage` 同样只传值对象，不泄漏框架类型（D-09）。

**回归**：`AssistantConversationIntegrationTest` 里已有一条断言 `token_usage` 落库的用例——改完后它应仍然通过；新增一条"N 轮工具往返 → 用量为各轮之和"的用例（AC-002）。

## 6. 审计视图（管理端）

一页表格，数据来源 `ai_credit_ledger` join `ai_messages`：

| 时间 | 用户 | 模型 | input | output | 扣减积分 | 操作人 | 原因 |
| --- | --- | --- | --- | --- | --- | --- | --- |

- 管理员调整（kind = ADJUST）的行走同一张表，`operator_user_id` 与 `note` 非空。
- 读接口挂 `/api/v1/ai/admin/**`，由**路径级 `hasRole('ADMIN')`** 兜住（**新前缀必须自身加规则，否则落到 `.anyRequest().authenticated()`，普通用户就能读别人的用量**）。
- 不做导出、不做图表（非目标）。

## 7. 用户侧

- 学习者端显示**余额**与**自身消耗明细**（REQ-008）。
- **积分不足**时：AI 入口可用但点击后给出明确文案（"积分不足，当前无自动充值，请联系管理员"），**不发起上游请求**（REQ-006）。
- 凭据界面改为只读说明（REQ-002）。

## 8. 注销级联

`ai_credit_accounts` 与 `ai_credit_ledger` 登记进 `AiUserDeletionListener` 的固定表清单；并入既有"注销后逐表断言行数为 0"的用例（AC-007）。

**注销时审计删不删（原待确认 1）已裁决为「一并删除」**（2026-10-15 用户确认，见 `requirements.md` 用户决定 8）。因此本节如上：`ai_credit_accounts` 与 `ai_credit_ledger` 都登记进清单，`ai_credit_ledger` 在注销时**整行删除**——这是本项目里唯一一处允许对追加式流水做删除的地方，理由是与 WORK-006 裁决 10 保持一致。**不做"保留审计但脱敏"的折中**（用户明确选了硬关联 + 一并删）。

## 9. 防刷

- 保留 `UserRateLimiter`（请求频次）。
- 新增单次调用积分上限（REQ-010）：一次调用最多能扣多少。
- 不做图形验证码、不做人机校验（非目标）。

## 10. 回滚

- **档位一（推荐）**：`billing.enabled=false` → 回到"平台凭据 + 不计量"，用户无感，**不影响已生成的流水**。
- **档位二**：把 `CredentialService.access` 改回读用户凭据 + 恢复用户侧端点——**代码回滚**，且用户凭据早已不再维护（表里可能有历史数据但没人再更新）。
- **不做**整表删除的回滚脚本（流水是审计证据）。

## 11. 实现选择（记录理由，不反复交回用户）

| 选择 | 理由 |
| --- | --- |
| 余额用整数、不用小数 | 避免浮点误差与展示不一致；价目表按"每千 token"折算后取整 |
| 预留-结算，而不是纯后扣 | 纯后扣在并发下会超扣（AC-004）；预留是"带条件的原子 UPDATE"，最简单可靠的做法 |
| 平台凭据放配置而非数据库 | 部署级单例，入库只增加泄漏面；用户侧凭据才需要加密落库 |
| 新增 `onUsage` 回调而非给 `onFailed` 加参数 | 用量与结果是两件事，分开后错误路径不必重复判断 |
| `ai_model_credentials` 表保留不删 | 删表是不可逆操作，收益只是"干净"；列入已知缺口 |
| 审计不做聚合表，直接 join | 量级是"助手消息数"，join 足够；预聚合是过度设计 |

## 12. 已知缺口（登记，不在本项解决）

1. **进程在"预留"之后、"结算"之前被杀** → 留下一笔未结算的预留，余额被多占。本项只保证不超扣。**2026-10-16 起预留不再写流水**（用户要求历史里不出现"预扣"），所以定位方式随之从"缺 `REFUND` 行"改成**不变式失配**：正常路径下「余额 == 初始余额 + 流水求和」严格成立，不成立就说明有一笔未结算的预留。不引入定时任务（线上是单实例、无定时任务基设，与 WORK-006 裁决 10 同一条约束）。
2. **缓存折扣拿不到**：`TokenUsage` 只有 `input/output/total`，若上游 `ChatUsage` 不暴露缓存命中维度，计费会按全价偏高。设计阶段需核实；若确实拿不到，记录为已知偏差而不是自行估算。
3. `ai_model_credentials` 表与用户侧旧数据的不清理。
