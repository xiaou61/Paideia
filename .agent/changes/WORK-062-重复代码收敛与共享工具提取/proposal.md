---
artifact: proposal
work_id: WORK-062
work: 重复代码收敛与共享工具提取
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 20:09:56 +0800
approver_role: CTO
---

# 重复代码收敛与共享工具提取提案

## 摘要

三路扫描（后端主代码 / 后端测试 / 前端）逐条给了证据后，本项按"**只收敛逐字重复、保留一切承重差异**"的口径做 15 个切片（后端 5 + 测试 5 + 前端 5，另 2 条前端切片合并进三件套里），覆盖约 100 个文件；被判定为"有意为之"与"收益薄"的 8 条明确不做（清单在 requirements 的非目标与已确认事实表）。

**判据模板**（每条都过这三问）：
1. 是**逐字相同**还是语义相近？——只收敛逐字的；
2. 差异是**承重的**吗（超时/顺序/前缀/变体）？——承重的参数化保留在调用点；
3. 落点会让 Modulith 边界或依赖图变复杂吗？——会就不做。

## 推荐方案

| 片 | 落点 | 规模 | 一句话 |
| --- | --- | --- | --- |
| B1 | `security.CurrentUser.userId()` | 11+6 处 | 令牌 subject→long 的 5 行 try/catch 逐字拷贝收一处 |
| B2 | `platform.Hashes.sha256Hex` | 9 处 | 三种写法的 SHA-256 hex 收一处（输出逐位一致） |
| B3 | `platform.ToolArgs` | 12/7/12/2/4 处 | AI 工具入参小工具与分页口径收一处 |
| B4 | `ai.internal.support.ModelJson` | 6 处 | 模型输出的代码围栏剥离与 JsonNode 取值 |
| B5 | `ops.OpsHttp`（包内） | 4 处 | Prometheus/Loki/Tempo 的请求样板与 baseUrl 归一 |
| T1 | `app 测试根包.MockMvcBodies` | 14 处 | `body(MvcResult)` 逐字重复 |
| T2 | `app 测试根包.TestTokens` | 28 类 | Bearer 构造（subject/role 显式传参） |
| T3 | `app 测试根包.Await` | 10 循环 | deadline 轮询（超时值/动作留在调用点） |
| T4 | `app 测试根包.MockMvcSse` | 4 处 | asyncStarted/asyncDispatch 两行样板 |
| T5 | `app 测试根包.SqlCleanup/UserFixture/TestIds` | 5+5+顺手 | 表循环、用户播种、id 后缀 |
| F1 | `core.errorMessage` | 39 处 | 错误对象转文案；`auth.messageOf` 改委托 |
| F2 | `ui.LoadingRow/ErrorAlert/EmptyHint` | ≈65 处 | 加载/错误/空三件套（哑组件，带 className/testId 插槽） |
| F3 | `ui.ListPagination` | 5 处 | admin 分页页脚（testIdPrefix 保住 e2e） |
| F4 | `admin/e2e/session.ts` | 10+11+13 处 | signIn/溢出断言/envelope 桩 |
| F5 | `core.formatLocalDateTime/formatDurationMs` | 12 处 | 时间与时长格式化（顺带修两处漂移：缺 encode、无 Invalid Date 保护） |
| F6 | `core.JSON_HEADERS/jsonRequest` | 4+28 处 | JSON 头+体（3 处 multipart 不动） |
| F7 | `admin/src/ai-window.ts` | 4+5 处 | 时间窗查询串（顺带修一处缺 encodeURIComponent） |

## 范围

**做**：上表 15 片 + 每片后按规则跑验证 + 完成后线上只读冒烟。

**不做**（均有一句话理由，写在 requirements「非目标」与「已确认事实」）：
BizException 工厂、ScheduledTaskRecorder 样板、mapper SQL/redactor 合并、测试基类、Entry fixture，
assistant truncate（模块环）、useNarrowViewport/useDarkMode（语义不同）、Theme 组合根（仅 2 处）、
localStorage（另属 Platform.cache 议题）、F8 API 工厂单例（38 处纯样板，留下一轮）。

## 备选方案

| 方案 | 最强理由 | 没选的原因 |
| --- | --- | --- |
| 建一个 `paideia-common` 模块装全部共享件 | 一处找齐 | 多数件只服务一个模块（ModelJson 属 ai），全塞 common 反而破坏"落点贴近消费者"；platform/security/模块内三级已能表达 |
| 测试建共享基类 `IntegrationTestBase` | 一劳永逸 | `TestProfileConfiguration` 注释已实测：`@Import` 会让 Spring 上下文按配置拆开，每多一份 15–25 秒；静态工具类零上下文成本 |
| 前端把三件套做成带 react-query 的 `QueryState` | 调用更短 | 会往 `@paideia/ui` 引依赖；ui 是设计系统，必须保持哑 |
| 全量重写 `when` 三档兜底为一档 | 更统一 | 三档差异是各自页面的展示决策（ISO 回显 vs `—`），统一等于改行为 |
| 顺手把 localStorage 13 处也收了 | 一次扫清 | 它们该走既有 `Platform.cache` 端口，属设计变更而非重复收敛 |

## 仓库影响

| 路径 | 改动 |
| --- | --- |
| `backend/paideia-platform` | 新增 `Hashes`、`ToolArgs` |
| `backend/paideia-security` | `CurrentUser` 加 `userId()`（`userIdOrNull()`） |
| `backend/paideia-ai/internal/support/ModelJson.java` | 新建 |
| `backend/paideia-app/.../ops/OpsHttp.java` | 新建（包内） |
| 后端各模块调用点 | 约 40 个文件替换（清单见 tasks.md 各片） |
| `backend/paideia-app/src/test/java/io/github/xiaou61/` | 新增 `MockMvcBodies/TestTokens/Await/MockMvcSse/SqlCleanup/UserFixture/TestIds`；约 35 个测试文件替换 |
| `frontend/packages/core` | 新增 `error-message.ts`、`format.ts`、`api.ts` 加 `JSON_HEADERS/jsonRequest` |
| `frontend/packages/ui` | 新增 `query-state.tsx`、`pagination.tsx` |
| `frontend/apps/admin` | `src/ai-window.ts`、`e2e/session.ts`；5 个分页页替换；10 个 spec 换共享 signIn |
| `frontend/apps/app` + 各 packages | 约 60 个文件替换 |
| `.agent/` | 四件工件、测试报告、updates 记录 |

线上冒烟：脚本只存临时目录（不提交），报告落 `testing/report.md` 的「线上冒烟」一节。
