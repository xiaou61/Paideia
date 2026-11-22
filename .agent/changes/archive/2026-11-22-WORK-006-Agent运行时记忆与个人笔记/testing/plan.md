---
artifact: test-plan
work_id: WORK-006
work: Agent 运行时、记忆与个人笔记
status: ready
created: 2026-10-12
updated: 2026-10-12
---

# Agent 运行时、记忆与个人笔记测试计划

## 测试范围

十六个纵向切片各自的行为与它们共同构成的链路：多轮对话与流式、记忆两路、只读工具、
写路径（草案 + 用户确认）、笔记 CRUD、只读分享、举报与下架、注销级联、用量与限流。
关注的是**用户可见行为与契约**（谁能读到什么、失败是否可见、失效是否真的失效），
不是实现细节。

## 环境与前置条件

- 真实 MySQL（`paideia_test`，经 127.0.0.1:3307 隧道）+ 环境变量 `PAIDEIA_TEST_DB_PASSWORD`；
  未配置时相关用例**跳过并计入 Skipped**（不是静默通过）。
- 迁移由 `paideia-app` 的测试上下文执行（`V5`–`V9` 在本项新增）。
- **模型调用一律用桩**：`AssistantConversation` 被 `@MockitoBean` 替换；本环境没有可达上游，
  也不该花谁的 Key。
- e2e 需要后端 jar（`mvn -B -DskipTests package`）与 Vite dev server，由 Playwright 的
  `webServer` 拉起；**三端串行跑**（并行时两个套件会抢 8080）。

## 验收矩阵

见 `report.md` 的结构化矩阵：AC-001…AC-016 逐条对应命令、退出码、结果与证据位置。

## 自动化检查

- 内环：纯函数用例（`ConversationWindowTest`、`sse.test.ts`、`SearchQueryTest`），不启容器。
- 外环：后端全量 `mvn -B -o verify`；前端 `pnpm -r typecheck` + `pnpm -r test`；
  改动那一端的 e2e。
- 最外环（本项收口时跑）：三端 e2e 全量 + 桌面产物（桌面端不跑 e2e，与既有约定一致）。

## 人工检查

- 无（本项没有只能靠人看的界面判断；界面行为都由 e2e 覆盖）。

## 回归范围

- **WORK-005 的精简路径**：`AiSummaryIntegrationTest` 9 条（缓存命中不再调用、凭据不出响应、
  SSRF 拒绝、限流）必须继续通过——本项动了 `SummaryService`（限流器换成共享实现）。
- 既有模块边界（`ModularityTest`）：本项新增一个模块与多处跨模块调用。
- 既有三端 e2e：本项改了助手壳（加标签页）、页头（加注销入口）、管理端侧栏（加举报处理）。

## 已知缺口

1. **工具循环与 `draft` 事件的端到端**没有自动化覆盖（需要真实上游）。
2. **注销后旧访问令牌在 TTL 内仍有效**（JWT 无状态的固有性质，不做存活性查询）。
3. 未做真连上游的冒烟、未做性能与容量验证。
