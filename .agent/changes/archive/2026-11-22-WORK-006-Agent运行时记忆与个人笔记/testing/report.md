---
artifact: test-report
work_id: WORK-006
work: Agent 运行时、记忆与个人笔记
status: passed
evidence: required
verified_commit: 0df2f9c
verified_at: 2026-10-17
created: 2026-10-12
updated: 2026-10-12
accepted_by: xiaou61
accepted_at: 2026-10-29 13:16:58 +0800
---

# Agent 运行时、记忆与个人笔记验证报告

## 验证环境

- 复验时间：2026-10-12（+0800）。
- 本机：Windows，JDK 25.0.4（Temurin）、Maven 3.9.0、Node 24.14.1、pnpm 12.10.1、Playwright 1.64.0。
- 数据库：服务器上的 MySQL 8.0.46，测试库 `paideia_test`（经 SSH 隧道 127.0.0.1:3307）。口令从被忽略的 `backend/config/application-local.yml` 读入环境变量 `PAIDEIA_TEST_DB_PASSWORD`，**未回显**。
- 迁移：本项新增 `V5`（会话与消息）、`V6`（记忆两路）、`V7`（笔记）、`V8`（分享）、`V9`（举报），编号在实施时逐个确认未与并发会话撞号。
- **模型调用全部用桩**：`AssistantConversation` 端口在集成测试里被 `@MockitoBean` 替换。本环境没有可达的上游，也不该花谁的 Key。

## 验证结果

- 后端全量 `mvn -B -o verify`：**112 条测试 0 失败 0 错误 0 跳过**，8 个代码模块 + 新模块 `paideia-note` 全部 SUCCESS，耗时 3:26，退出码 0（`logs/backend-verify.txt`）。`ModularityTest` 通过 = 新增模块与跨模块调用都在边界内。
- 前端类型检查 `pnpm -r typecheck`：**11 个包全过**，退出码 0（`logs/frontend-typecheck.txt`）。
- 前端单测 `pnpm -r test`：**77 条通过**（core 16、ui 17、knowledge 15、auth 13、**assistant 12**、platform-desktop 4），退出码 0（`logs/frontend-unit.txt`）。
- 三端 e2e（**串行**，避免两个套件抢 8080）：学习者端 **19**、管理端 **8**、展览页 **5**，共 **32 条通过**（`logs/e2e-app.txt`、`logs/e2e-admin.txt`、`logs/e2e-ui-kit.txt`）。
- **四条反向验证全部咬住**（详见下文：`## 反向验证`）。
- **WORK-005 的精简路径无回归**：`AiSummaryIntegrationTest` 9 条通过（含缓存命中不再调用、凭据不出响应），本项对 `SummaryService` 的改动只有把限流器换成共享实现。

各验收标准的逐条命令、退出码、结果与证据位置见下表。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 多轮对话、条目为可选上下文 | `mvn -B -o verify`（`AssistantConversationIntegrationTest`） | 0 | passed | `logs/backend-verify.txt`（该用例 11 条通过） |
| AC-002 流式与思考分离 | `pnpm --filter @paideia/app test:e2e`（`assistant.spec.ts`：思考折叠、面板连通） | 0 | passed | `logs/e2e-app.txt`（19 passed） |
| AC-003 会话服务端持久化、换端可续 | `mvn -B -o verify`（`createConversation`／`listActiveByUser`／限流用例共享同一张表） | 0 | passed | `logs/backend-verify.txt` |
| AC-004 记忆两路各自能写能召回 | `mvn -B -o verify`（`LearnerMemoryIntegrationTest`：结构化命中 + 全文命中 + **单字退 LIKE**） | 0 | passed | `logs/backend-verify.txt`（该用例 9 条通过） |
| AC-005 记忆严格私有 | `mvn -B -o verify`（匿名 401／他人看不到／拿别人 id 删不掉且不泄漏存在性） | 0 | passed | `logs/backend-verify.txt` |
| AC-006 记忆可查看并逐条删除 | `mvn -B -o verify` + `pnpm --filter @paideia/app test:e2e`（记忆面板空状态） | 0 | passed | `logs/backend-verify.txt`、`logs/e2e-app.txt` |
| AC-007 只读工具能检索、越权被拒 | `mvn -B -o verify`（`AssistantToolsIntegrationTest`：检索只给已发布、分类形状、标记只给本人、参数越界被夹） | 0 | passed | `logs/backend-verify.txt`（该用例 9 条通过） |
| AC-008 写本人私有数据／写本人笔记、不能改他人 | `mvn -B -o verify`（记忆写入口三态 + 笔记三态；`toolsCannotWrite` 守卫） | 0 | passed | `logs/backend-verify.txt` |
| AC-009 未经点击不产生任何写入 | `mvn -B -o verify`（`draftNoteStagesWithoutWriting`：拟草稿前后 `notes` 行数不变） | 0 | passed | `logs/backend-verify.txt` |
| AC-010 笔记 CRUD 与 AI 内容落进笔记 | `pnpm --filter @paideia/app test:e2e`（`notes.spec.ts`：建档→编辑→保存→**重载仍在**→删除） | 0 | passed | `logs/e2e-app.txt` |
| AC-011 分享免登录可读、不可枚举、产物不含痕迹 | `mvn -B -o verify`（`NoteShareIntegrationTest`）+ `pnpm --filter @paideia/app test:e2e`（**匿名新上下文**打开链接） | 0 | passed | `logs/backend-verify.txt`（该用例 6 条通过）、`logs/e2e-app.txt` |
| AC-012 分享可撤回、到期失效 | `mvn -B -o verify`（撤回立刻失效、别人撤不动、到期读不到） | 0 | passed | `logs/backend-verify.txt` |
| AC-013 举报与管理员下架 | `mvn -B -o verify`（`NoteReportIntegrationTest`：三态 + 下架后链接立刻 404）+ 管理端 e2e | 0 | passed | `logs/backend-verify.txt`（该用例 4 条通过）、`logs/e2e-admin.txt`（8 passed） |
| AC-014 注销级联硬删 | `mvn -B -o verify`（`AccountDeletionCascadeIntegrationTest`：注销后**逐表断言行数为 0**） | 0 | passed | `logs/backend-verify.txt`（该用例 2 条通过） |
| AC-015 用量返还与限流 | `mvn -B -o verify`（用量随 done 事件返还；一分钟用光后第 11 轮 429 且那一轮不落库） | 0 | passed | `logs/backend-verify.txt` |
| AC-016 工具与结构化输出并存 | `mvn -B -o verify`（`draft_note` 以工具参数承载结构化；`toolsCannotWrite` 钉住清单） | 0 | passed | `logs/backend-verify.txt` |

## 反向验证

四条都做了"临时改坏 → 对应用例必须红 → `git checkout --` 还原"，还原后工作区干净（`git status` 无输出）。

| 反向验证 | 做法 | 结果 |
| --- | --- | --- |
| 越权防护真的在跑 | 把 `NoteMapper.findByIdAndUser` 的 `user_id` 条件删掉 | **`NoteIntegrationTest` 4 条挂 1 条**（归属那条） |
| 分享真的免登录 | 把 `/api/v1/public/shares/**` 从 `permitAll()` 改成 `authenticated()` | **`NoteShareIntegrationTest` 6 条挂 4 条** |
| 注销真的级联 | 注释掉 `events.publishEvent(new UserDeletedEvent(...))` | **`AccountDeletionCascadeIntegrationTest` 2 条挂 1 条**（逐表断言那条） |
| 注销真的验口令 | 把口令校验改成 `if (false)` | **`AccountDeletionCascadeIntegrationTest` 2 条挂 1 条**（"口令错时拒绝且什么都不删"） |

## 验收结果

- AC-001 至 AC-016：**全部通过**。
- 业务验收：待用户登记（`approve --artifact test-report`）。

## 失败与未验证项

1. **工具循环与 `draft` 事件的端到端没有覆盖**：真实上游（模型要工具 → 我们执行回喂 → 出草稿）需要可用的模型服务，本环境没有、也不该花谁的 Key。已覆盖的是链路两端——工具**自身**行为（`AssistantToolsIntegrationTest`）与事件**解析**（`sse.test.ts`）；缺的是中间那一段。与 WORK-005 留下的"未做真连上游验证"同性质。
2. **注销后旧的访问令牌在 TTL 内仍能通过鉴权**：JWT 无状态，删 `users` 行不会让已签发的令牌失效。已断言的是"数据全没了"与"刷新记录已删所以刷不出新令牌"。要做立刻失效得在鉴权层加逐请求存活性查询，那会让全部集成测试（都用不存在的用户 id 签令牌）集体改造，本项不做。
3. **未做真连 DeepSeek 的冒烟**：同第 1 条。
4. 未做性能与容量验证。

## 剩余风险

1. **AgentScope 2.0.0 很年轻**（GA 三个月、仓库一年内 891 个未关 issue）。本项新增了对它**流式增量**与**工具调用累积**的依赖（`TextAccumulator`／`ThinkingAccumulator`／`ToolCallsAccumulator`），比 WORK-005 只用"模型 + formatter"更深入一层。
2. **限流是单实例内存态**（`UserRateLimiter`，代码里有 `ponytail:` 注释标明天花板）：重启清零、多实例各算各的。
3. **各模块的注销删除清单是手写的**（项目不建跨模块外键）。护栏是 `AccountDeletionCascadeIntegrationTest` 的逐表断言——新增用户私有表却忘了登记，那条用例会红；但**忘了同时加断言**就不会红。
4. **`ai_entry_summaries` 的公开结果会随注销消失**：那是用户生成的公开内容，别人可能已读过。用户裁决 10（级联硬删）涵盖它，但值得知道这一后果。
5. **记忆召回的全文那一路是关键词驱动**（第 2 条裁决的取舍）：过去说过的话只有在**字面对得上**时才被召回。换说法就召不回来，这是"不用语义检索"的直接代价。
6. **`UserRateLimiter` 的窗口是进程内且按用户**：恶意用户换账号即可绕过；本项没有按来源限流（登录有，AI 调用没有）。
7. **证据文件在本机**：`testing/logs/` 被 `.gitignore` 忽略（与 WORK-001..005 一致），换台机器看不到这些日志。

## 2026-10-17 重新核对（结算）

`LearnerMemoryIntegrationTest` 9/0、`NoteIntegrationTest` 4/0、`NoteShareIntegrationTest` 6/0、`NoteReportIntegrationTest` 4/0、授权隔离 9/0；前端 `@paideia/assistant` 24/0、学习者端 e2e 34 passed / 1 skipped。

**如实登记一处偶发（未修）**：`AssistantConversationIntegrationTest` 在全量跑里出现过一次 `ConcurrentModificationException`，位置是 Spring MockMvc 的响应头 `LinkedCaseInsensitiveMap`——异步 SSE 线程与容器线程同时写头，属 **MockMvc 的线程安全问题**，不是业务代码；同一条用例单独跑 12/12 绿。此前同一类异常还表现为 `HeaderValueHolder.getStringValues` 的 NPE。要根治得让该测试类的异步派发串行化（或改用真实端口），属测试基建，本轮未改。

## 2026-10-17 积分闸回滚修复后的复核

本轮改动落在 `ConversationService.prepare`：去掉 `@Transactional`，让用户消息先提交、再做积分预留，并把所有可能抛异常的读操作移到 `reserve` 之前（保证「预留之后不再有会抛的动作」）。另外 `AiCreditIntegrationTest` 原本断言「被拒的那一轮不留任何消息」，已改为「留用户消息、不留助手消息」——AC-003 只管上游没有收到请求，不要求丢用户输入。

本项相关证据随后重跑：`LearnerMemoryIntegrationTest` 9/0、`MemoryAutoExtractionIntegrationTest` 12/0、`AssistantConversationIntegrationTest` 单独跑 12/12（整套跑时它仍偶发 MockMvc 响应头竞态，见上节）。

锚点改绑到 `e941bcb`。

## 2026-10-17 测试基建改动后的复核

本轮改动是测试基建：测试库改本机、四个前端包换 happy-dom、会话集成测试改用同步执行器。
业务代码未变。重跑结果：后端全量 **257 条 / 0 失败 / 0 error / 4 跳过**（墙钟 1 分 31 秒），
前端单测 **176 条全过**，`typecheck` 干净。

锚点改绑到 `d184e36`（其后只有 `.agent/` 工件变化）。

## 复验记录（2026-10-18，锚点 0df2f9c）

**为什么会有这一段**：原报告的证据是在更早的提交上采的，之后仓库里又落了 WORK-014／015／016 三个工作项
（源码与配置都变过），校验器因此把本报告判为 `stale`。本轮在**当前 HEAD**（`9c99033`）上重跑了与本项
**受影响面**相关的那几组检查，逐条结果如下。**没有改任何功能代码**。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 会话与工具全量 | `mvn -B test`（`AssistantConversationIntegrationTest` / `AssistantToolsIntegrationTest` / `LearnerMemoryIntegrationTest` / `Note*`） | 0 | passed | 后端全量 143 运行 / 0 失败 / 4 跳过 |
| 前端助手与笔记 | `pnpm --filter @paideia/assistant test` | 0 | passed | 25 passed（5 文件） |
| 学习者端 e2e | `pnpm --filter @paideia/app test:e2e` | 0 | passed | 60 passed / 2 skipped / 0 failed |
| 类型与前端单测 | `pnpm typecheck` / `pnpm test` | 0 | passed | typecheck 0；单测共 **240** 条全过 |

**这段区间里本项的受影响面**：`ConversationService.prepare` 去掉 `@Transactional`（WORK-011 的 AC-004 修复）、`ConversationService` 接入两个记忆开关（WORK-013）、记忆两路扩列与自动整理（WORK-013）——**都是本项建立的那条链路上的追加**。

**结论**：本项建立的会话、工具、记忆、笔记四条链路在当前 HEAD 上全绿。**其中两处行为变化是后两个工作项带来的**（`prepare` 不再包事务、记忆多了一路自动写入），它们各有自己的报告，本项只确认"没有被弄坏"。
