---
artifact: design
work_id: WORK-010
work: 身份化千人千面与求职者面
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-004, WORK-006, WORK-009]
created: 2026-10-15
updated: 2026-10-15
approved_by: xiaou61
approved_at: 2026-10-15 17:57:34 +0800
approver_role: CTO
---

# 设计 · 身份化千人千面与求职者面

## 1. 身份与画像

```sql
CREATE TABLE learner_profiles (
  user_id     BIGINT UNSIGNED NOT NULL,
  identity    VARCHAR(32)     NOT NULL DEFAULT 'job_seeker',  -- 当前身份（单选即当前）
  target_role VARCHAR(64)     NULL,   -- 目标岗位
  target_city VARCHAR(64)     NULL,   -- 目标城市
  timeline    VARCHAR(16)     NULL,   -- autumn | spring
  stage       VARCHAR(16)     NULL,   -- applying | written_test | interview
  updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id)
) ENGINE = InnoDB;
```

**设计选择**：

| 选择 | 理由 |
| --- | --- |
| **身份注册表是代码里的枚举，不是表** | 一期只有 `job_seeker` 实心。留位的身份（考研/考公/日常学习）只在枚举里出现、不做界面——**不做自己都走不到的表** |
| **单选身份 = 一列，不要 `is_current`** | 单选即当前，多一个布尔列就多一个"两行都标了 current"的非法状态 |
| **画像字段全部可空、且是"求职者专用"** | REQ-003 明说不是所有身份共用一套字段。加第二个身份时字段归属要重审，**登记为已知缺口** |
| **不给既有表加身份列** | REQ-004 已裁"记忆不分区"；笔记、自评、精简缓存同理（见 requirements 的逐表理由） |
| 新表**不建跨模块外键** | 沿用 V4/V5 的约定 |

**`AccountApi` 加方法**（它是注释里等的那个"第一个真实消费者"）：读某用户的当前身份。跨模块只走端口，不读 `learner_profiles`。

## 2. harness：注册、选择、工具分层

**harness = 一份提示词 + 一组工具 + 一个 key。** 一期三套：

| key | 提示词类 | 工具集 | 何时用 |
| --- | --- | --- | --- |
| `assistant` | `ConversationPrompt`（**既有，不动**） | 通用组 | 默认 |
| `interview` | `InterviewPrompt`（新增） | 通用组 | 面试页 |
| `review` | `ReviewPrompt`（新增） | **空**（REQ-011 要求不挂工具） | 面试结束的点评 |

- **沿用"一个任务一个类"的既有模式**（`SummaryPrompt` / `ConversationPrompt` 就是两个类，注释明写混用会让约束打架）。不新造"提示词工厂"。
- **工具分两层**：通用组（`SEARCH_ENTRIES` / 列分类 / `MY_MARKS` / `DRAFT_NOTE`，即既有 4 个，**所有 harness 都给**）+ 场景组。**一期场景组是空的**——面试官靠提示词驱动，点评官明确不挂工具。所以一期的工具集变化是"**取子集**"而不是"加新工具"，而"场景工具不外泄"（AC-006）因此有可断言的对象：`interview` / `review` 的工具 schema 名称集合。
- **端口改动**：`AssistantConversation.Request` 加 `harness`；`AgentScopeConversation` 按它选提示词与工具子集。**不加新的端口方法**——复用同一条"流式 + 逐轮累加 + 工具循环"路径，避免把那段最容易出错的循环复制第二份。
- **选择规则（REQ-006）**：

```
harness = request.harness (显式，页面给的)  ??  identityDefault(identity)
一期 identityDefault 恒为 assistant
```

**诚实的自承**：一期 `identityDefault` 恒为 `assistant`，因此"页面优先、身份当默认"这条规则在一期**只有前半段可观察**（面试页显式给 `interview` vs 全站入口不给）。**"不同身份的默认行为不同"无法验收**——已写进需求的已知缺口，测试计划要照实写。

## 3. 会话记录行为（本项唯一对既有表的改动）

```sql
ALTER TABLE ai_conversations ADD COLUMN harness VARCHAR(32) NULL;  -- NULL = 通用助手
```

- **为什么必须记**：会话在恢复时要保持同一行为。若不记，用户从通用入口继续一段面试对话，会**中途换人格**——同一个"人"突然不按面试官说话了。
- **可空**：NULL 表示通用助手，对既有数据是"行为不变"（既有会话全是 NULL）。
- 这是**本项唯一动到的既有表**。记忆两张表、笔记、自评、精简缓存**都不动**。

## 4. 自评 → 结构化记忆（覆盖语义）

**现状缺口**：自评存在 `knowledge_self_assessments`（有界面），但助手只能靠 `MY_MARKS` 工具**当场查**，永远不会"记住"；而 `ai_memories_structured` 这一路**界面上没有任何写入口**，所以它一直是空的。

**做法**：在自评的写入路径（`SelfAssessmentController` 的 PUT / DELETE）之后加一步：

| 自评动作 | 记忆动作 |
| --- | --- |
| PUT（懂了 / 还不懂） | **upsert** 一条 `kind='entry_understanding'`、`ref_id=entryId` 的结构化记忆，内容形如"第 N 条《标题》：还不懂" |
| DELETE（取消标记） | **删掉**那条对应的记忆（否则标记取消了助手还记着，是假记忆） |

**覆盖语义怎么落地（REQ-012 / REQ-014 决定 14）**：给 `ai_memories_structured` 加唯一键 `(user_id, kind, ref_id)`，写入改 `INSERT ... ON DUPLICATE KEY UPDATE content = VALUES(content)`。

**边界必须写清（否则会被误读为"改动了既有记忆语义"）**：

- **只对"自评桥"这一条来源做覆盖**。
- 用户手点「记住这条」写的是**全文**记忆（另一张表，`ai_memories_text`），**仍是追加**，不受影响。
- MySQL 唯一键里 `NULL` 不参与去重，所以将来若有"没有 `ref_id` 的结构化记忆"，不会被这条约束吞掉。

## 5. 点评与评估落库

- **点评 = `harness=review` 的一次独立调用，工具集为空**。这同时绕开 DeepSeek 的"工具调用与结构化输出不能在同一次调用里并用"——评分那一次本来就不带工具。
- 产出结构化 JSON（各维度评分 + 改进点）。
- **落库到新表**（不塞进消息文本，因为"进度与弱项"要能查询）：

```sql
CREATE TABLE ai_interview_evaluations (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id         BIGINT UNSIGNED NOT NULL,
  conversation_id BIGINT UNSIGNED NOT NULL,
  score_json      JSON            NOT NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_interview_eval_user (user_id, created_at)
) ENGINE = InnoDB;
```

- 这是"学习行为数据"的第一块真砖（比自评可信）——但**本项不做基于它的掌握度推断与路径编排**（那是 `deferred-scope.md` 里仍未承接的那几项，见「已知缺口」）。

## 5.1 面试一期的形态（题源与边界）

这一节落实 `requirements.md` 里三条"不阻断批准、实施时随设计定"的实现选择——**在这里定，不再交回**：

| 项 | 一期决定 | 理由 |
| --- | --- | --- |
| **题源** | **知识库已发布条目**为主（走既有只读端口 `KnowledgeApi.searchPublished` / `findPublishedContent`） | 复用既有端口，不新建题源体系 |
| **用户笔记是否送上游** | **默认不参与**；要参与必须由用户在面试开始时**显式勾选** | 笔记是严格私有数据（WORK-006 裁决 3，连分享都自带边界）。送进上游＝**那些内容离开本机**（上游是第三方）。取**保守方向**——默认不送，要送得用户自己点 |
| **题量 / 时长** | **不限时**；题量由用户**主动结束**控制 | 一期不做自动结束：按题数或按时长都得先有真实消耗数据才能定阈值，现在定就是猜 |
| **开场** | 由该身份的配置包给一段开场白（`identity → 助手开场白`，与推荐配置同一处载体） | 避免把开场白写死在提示词里 |
| **中断** | 用户可随时退出；不落未发生的数据 | AC-009 |

**这条把"笔记默认不参与"定为保守默认。** 若你希望默认参与，改的是两处（配置的默认值 + 勾选的初始状态），不影响其他设计——但方向从"用户主动送出私有数据"变成"平台默认送出"，我不建议。

## 6. 推荐配置包

- 一期 = **代码/配置里的映射**（`identity` → 推荐分类 slug 列表、首页卡片与顺序、导航入口顺序、助手开场白），**不做表**。
- 理由：线上只有两个账号，规则映射都无从调参，建表只会多一处要维护的配置载体。
- 落点：学习者端首页与导航读这份配置；导航里把当前身份可用的入口排前。

## 7. 注销级联

`learner_profiles` 与 `ai_interview_evaluations` 登记进 `AiUserDeletionListener` 的固定表清单（REQ-014），并入既有"注销后逐表断言行数为 0"的用例。

## 8. 与 WORK-009 的接口

- 所有 AI 调用走 **WORK-009 的平台凭据**；余额不足时面试入口给明确提示（AC-010 依赖它）。
- **验收前置**：`WORK-009` 落地后普通用户积分为 0 且不做充值 → **验收本项前必须由管理员给验收账号发放积分**。这条写进测试计划。

## 9. 实现选择（记录理由，不反复交回用户）

| 选择 | 理由 |
| --- | --- |
| 复用 `AssistantConversation` 加 `harness` 入参，而不是新开端口方法 | 复用同一条"流式 + 逐轮累加 + 工具循环"路径；那段循环是本项目最容易出错的地方，不复制第二份 |
| 三份提示词各一个类 | 沿用既有模式；注释已经写明混用会让约束打架 |
| 会话表加 `harness` 可空列 | 恢复会话时行为不突变；NULL 对既有数据是行为不变 |
| 评估结果单独建表，不进消息文本 | "进度与弱项"要能查询，塞进文本等于不可查 |
| 推荐配置放代码/配置、不建表 | 0 用户无从调参，建表是空壳 |
| 身份注册表是枚举、不建表 | 同上；一期只有一个真值 |
| 自评桥用 `ON DUPLICATE KEY UPDATE` | 覆盖语义 + 并发安全，不需要"先查后写" |

## 10. 已知缺口（登记，不在本项解决）

1. **一期只有"求职者"一个身份** → 多身份切换路径**无法端到端验收**；`identityDefault` 恒为 `assistant`，使"身份默认行为不同"也**无法验收**。测试计划必须照实写明这是缺口，不是漏测。
2. **画像字段是"求职者专用、全部可空"** → 加第二个身份时字段归属要重审（继续可空堆下去，还是按身份分表）。
3. **不做基于面试评分的掌握度推断与路径编排**——`deferred-scope.md` 里那五项产品命门仍然推迟；本项只是把第一份行为数据落下来。
4. **会话的 `harness` 只是标记，不限制用户跨界继续对话**（从通用入口继续一段面试对话，仍按面试行为）——这是刻意的：行为跟着会话走，比跟着入口走更符合"那是同一个人说过的话"。
5. **内容面（用户上传 + AI 审核）不在本项**，见 requirements 的「关联工作项」。
