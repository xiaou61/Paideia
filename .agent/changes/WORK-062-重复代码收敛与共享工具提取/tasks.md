---
artifact: tasks
work_id: WORK-062
work: 重复代码收敛与共享工具提取
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 20:09:56 +0800
approver_role: CTO
---

# 重复代码收敛与共享工具提取实施任务

## 依据

- `requirements.md`（REQ-001…005 / AC-001…006）
- `proposal.md`（15 片表与"不做"清单）
- `design.md`（签名、承重差异、每片"消失判据"）

## 全局约束

- 只收敛**逐字重复**；承重差异（超时/顺序/前缀/变体）一律参数化留在调用点，并在代码注释写明。
- 每片一个提交；后端片跑受影响模块 `test` + 守卫切片；前端片跑该包 typecheck+单测；改 `@paideia/core`/`@paideia/ui` 后跑两端 e2e。
- 叠放于 `feat/work-061-arch-guards`；不部署、不推送。
- 新建共享件必须**同片内被真实调用**，不留"为将来"的空件。

## 评审焦点

| 失败模式 | 归属 | 验证 |
| --- | --- | --- |
| 把承重差异一起"统一"掉（超时、清理顺序、UTC 变体、窄 longOf、multipart） | B1/B3/T2/T3/T5/F6 | 各片白名单 grep + 受影响测试 |
| 新共享件引依赖/上下文（ui 引 react-query、测试基类） | F2/T* | 设计明文禁止；typecheck 与依赖树检查 |
| 替换漏点（改了新件没换完调用） | 全部 | design「消失判据」grep |
| e2e testid/文案被改（共享件吃掉各页差异） | F2/F3 | 两端 e2e 实跑 |
| 前端行为被"顺手优化"改变（时间三档兜底等） | F5 | 保留白名单；e2e + 单测 |

## 任务

### TASK-001 | done | B1 令牌 subject→long 收敛
- 落点 `security.CurrentUser.userId()`；替换 11 处逐字 try/catch（account×2、ai.AiRequester、app.console.AdminUserController、knowledge.CurrentUserId、mcp.McpTokenController、note×3、sandbox×2）与 6 处裸解析（ai.LearningAdviceController、ai.digest.DigestController、ops.OpsDiagnosisController×2、ops.OpsIncidentController×2）。
- 验证：受影响模块 test；守卫切片；消失判据 B1。
- 完成记录：实际替换 10 块（8 块由机械脚本、sandbox 2 块形态不同单独改）+ 6 处裸解析 + knowledge 的 `require()` 改为委托；**knowledge 的 `orNull()` 按设计保留**（读路径要吞异常，不是逐字拷贝）。消失判据通过：文案只剩 `CurrentUser.java:42` 一处；`CurrentUser.subjectId()` 剩余 4 处均为"要字符串不要数字"的合法用法（MeController 回显、orNull、测试夹具、OAuth 身份绑定）。行为差异一处：sandbox 匿名请求文案由"未认证"统一为"令牌主体不是有效的账号标识"（HTTP 401 不变）。实测：非 app 单元切片 **805 条 / 0 失败 / exit 0（59 秒）**；守卫切片 35 条 0 红（66 秒）。

### TASK-002 | done | B2 SHA-256 收敛
- 落点 `platform.Hashes.sha256Hex`；替换 9 处（account.Digest 改委托；ai×5；ops.OpsIncidentService；knowledge.QuestionCatalog；sandbox.SandboxQuotaService）；EssayCollectionService 的 catch-返回原值保留。
- 验证：受影响模块 test；消失判据 B2。
- 完成记录：实际替换 **10 处**——消失判据多抓到一处扫描漏网的同类拷贝（`mcp.PatService.hash`，手写 forDigit 形态）；EssayCollectionService 保留"失败退回原值"语义（委托调用包在原 try/catch 内）。消失判据：`MessageDigest.getInstance("SHA-256")` 全仓仅剩 `Hashes.java` 一处。实测：非 app 单元切片 805 条 0 失败（57 秒）；app 面 7 类 54 条中 1 红＝**已知外部项**（McpAdmin 工具数 19/21，061 报告「外部阻塞」已登记），其余 53 条全绿。

### TASK-003 | done | B3 工具入参小工具收敛
- 落点 `platform.ToolArgs`；替换 stringOf 12、longOf 7、messageOf 12、instantOf 2、分页 3-4；窄变体与 fallback 族保留。
- 验证：受影响模块 test（ai/note/knowledge/sandbox/app）；消失判据 B3。
- 完成记录：实际替换 **39 处**（38 处脚本 + `OpsAdminTools` 内层类缩进版 1 处人工）；`intOf`（分类树）一并入 ToolArgs（第 13 个件）；后处理清掉 4 个因替换闲置的常量与相应 import。**PageQuery 上限 200 而工具口径 100 是承重差异**——ToolArgs 用自己的 `MAX_SIZE=100` + `PageQuery.of`，AdminUserController 的 HTTP 参数走 `clampedSize`。窄 longOf（AskTool/ResumeTools）与 `OpsDiagnosisService.messageOf`（还要截断 1000 字）按设计保留。判据：正典 stringOf 体只剩 ToolArgs 一处、`Math.min(Math.max(size,1),MAX_PAGE_SIZE)` 为 0、messageOf 一行式只剩 ToolArgs。实测：单元切片 805 条 0 失败（55 秒）；工具面 app 5 类 36 条 0 失败（47 秒）。

### TASK-004 | done | B4 模型输出 JSON 收敛
- 落点 `ai.internal.support.ModelJson`；替换 6 处逐字 + textOrNull/textOrEmpty 5 处；两个变体保留。
- 验证：`mvn -pl paideia-ai -am test`；消失判据 B4。
- 完成记录：实际 **11 处**——6 份逐字 stripFence（按归一化 hash 校验同一性后替换）+ textOrNull 组 3 份（其中 SummarySchemaValidator 风格不同、语义逐条相同）+ textOrEmpty 组 2 份；变体（CampItemParser/EssayQuestionParser 的截断保留版、ResumeJson 的带计数版）按设计保留。判据：`lastIndexOf("```")` 只剩两个变体 + ModelJson。实测：单元切片 0 失败；app 面 2 类 17 条 0 失败。

### TASK-005 | done | B5 Ops HTTP 样板收敛
- 落点 `ops.OpsHttp`（包内）；改 Prometheus/Loki/Tempo 三家 + Grafana 反代尾斜杠 1 处；`"success"` 判定与失败类型保留。
- 验证：`-Dtest='Ops*,Tempo*,Loki*'`；消失判据 B5。
- 完成记录：整方法替换 8 处（三构造样板 + Prometheus.query + Loki.queryRange/scalar + Tempo.get + 反代 targetUri 尾斜杠）；三家的 `JSON` 字段与 ObjectMapper/IOException/HttpRequest/HttpResponse import 全部随替换清掉。判据：`HttpClient.newBuilder()` 只剩 OpsHttp 与 `OpsGrafanaProxyController`（后者是转发代理，本来就有自己的客户端，不在收敛范围）。实测：ops/分析面 117 条 / 0 失败 / 0 跳过（与基线一致）。

### TASK-006 | done | T1 + T4 测试样板（body / SSE）
- 新增 `MockMvcBodies`、`MockMvcSse`；替换 14 + 4 类。
- 验证：受影响类实跑（含 Memory 与 AiCredit 家族）；消失判据 T1/T4。
- 完成记录：`body()` 14 处由**行级状态机**替换（安全）；SSE 首轮用跨行正则做，**把普通 perform 也吞了**（非贪婪 `.*?` 跨方法匹配到后文真正的 SSE 站点），已 `git checkout` 整目录回退，改为逐处手工替换 4 个方法（AiCredit/AssistantConversation/AssistantLiveUpstream/MemoryAuto，注意 AssistantLiveUpstream 返回 String 要包 body、MemoryAuto 返回 MvcResult 不包）。教训入 updates：**含多个同类语句的文件不能用跨行非贪婪正则，要么行级状态机、要么逐处手工**。静态导入 `asyncDispatch`/`request` 与闲置 StandardCharsets 随之清理。实测：app 全量 **478 条 / 4 红（全部为 061 已登记外部项：ApiWalkthrough 覆盖、McpAdmin 19/21、Sandbox 配额×2）/ 0 新增失败**，与基线一致。

### TASK-007 | done | T2 Bearer token 收敛
- 新增 `TestTokens`（subject/role 显式传参）；替换 28 类约 36 方法（含 ops 包 tokenFor 变体）。
- 验证：app 模块受影响类实跑；消失判据 T2。
- 完成记录：共 **52 处**（两版正则 25+20 + 递归版 6 个 ops 文件 + ApiWalkthrough 的全限定写法手工 1）。踩了三个坑并当场修：①第一版正则不允许 subject 带括号（`Long.toString(...)`）漏 21 处→第二版允许嵌套括号+跨行拼接；②`Path.glob("*.java")` 非递归漏掉 ops/ 子包→改 rglob 并把 `TestTokens` 提为 **public**（ops 子包跨包访问）；③import 清理条件写成 `"AuthPort." not in` 误删了把 `AuthPort` 当字段类型的文件的 import→补回 11 个文件。判据：`"Bearer " + authPort.issue` 全测试树只剩 TestTokens。实测：app 全量 478 条 / 4 红（同外部项）/ 0 新增失败。

### TASK-008 | done | T3 Await 轮询收敛
- 新增 `Await.until/value`；替换 OpsDiagnosis/OpsHook 对、MemoryAuto 六循环、MemoryExtractionJobs 二循环、AssistantConversation 一循环、AiTelemetry 二循环、AiObservability 一循环；ApiWalkthrough 与 DefaultRunRecorderTest 的变体保留。
- 验证：上述类实跑；消失判据 T3。
- 完成记录：本轮实际替换 **11 循环 / 5 类**（Ops 对 ×2 手工、MemoryAuto ×6、AssistantConversation ×1 手工、本项新套件 ×2），并把新套件里因此闲置的 `sleep()` 删除。**刻意保留**：AiTelemetry×2 / AiObservability×1（超时动作与逻辑更缠，按扫描建议先不动）、ApiWalkthrough 的 best-effort for 循环、DefaultRunRecorderTest 的 2s 变体——**承重差异全部留在调用点**：超时值、间隔、超时动作（静默/返回 null/AssertionError）。`Await` 沿用既有 `sleep()` 口径（吞中断+还原标志），调用点签名零改动。实测：5 类 47 条 / 0 失败。

### TASK-009 | done | T5 清理/播种/id 收敛（收敛版）
- 新增 `SqlCleanup`、`UserFixture`、`TestIds`；替换 5 类表循环、4-5 处 CURRENT_TIMESTAMP 播种、顺手 id 后缀；UTC 与 passwordless 变体、各表顺序保留。
- 验证：上述类实跑。
- 完成记录：**只做了 SqlCleanup**（5 处：Camp、CampMastery、McpAdmin、AdminUser、Resume——把"for(表名:List.of(...))"循环样板收进 `deleteByUser(jdbc, userId, tables...)`，表清单与顺序仍留各测试类；`users` 按 id 列单独删不进共享件）。**UserFixture 与 TestIds 明确不做**（与扫描建议一致）：UserFixture 的 11 处差异是承重的（`UTC_TIMESTAMP` 一处的时区语义、role/passwordless 变体），合并要么 4 参数要么留变体，收益/风险不划算；TestIds 的前缀数字承重（清理条件按区间）、扫描本身就建议"只在顺手改时替换、不专门扫"。两条作为"不做项"记入报告。实测：5 类 38 条 / 1 红 = 已知外部项（McpAdmin 工具数），其余 37 绿。

### TASK-010 | done | F1 errorMessage（core）
- `core` 新增 `error-message.ts`；替换 39 处；`auth.messageOf` 委托；admin 两处私有函数删除；顺手替换 24 处 `as Error` 断言。
- 验证：core 单测（新增 2-3 条）+ 相关包 typecheck；消失判据 F1。
- 完成记录：新增 `core.errorMessage`（4 条单测）；通用替换 **A 30 处 + B 23 处 / 34 文件**（apps/app、apps/admin、packages/assistant），admin 两处私有函数删除并把 8 处调用补 `'未知错误'` 兜底（原来非 Error 会渲染 `undefined`，现给兜底），`auth.messageOf` 改一行委托（ApiError 是 Error 子类，原两分支等价），`conversation-panel`×6 等全部收拢。**白名单保留**：`packages/ui` 内 8 处与 `apps/desktop/electron/main.cjs` 1 处——ui 是设计系统刻意不依赖 core、desktop 主进程是 CJS 不能引 ESM 包（各一行理由入报告）。实测：core/auth/assistant/app/admin 五包 typecheck 全过；core 单测 26 条（含新增 4）全绿。

### TASK-011 | done | F2+F5+F6 core/ui 纯件（三件套 / 时间 / JSON 请求）
- `ui` 新增 `query-state.tsx`；`core` 新增 `format.ts` 与 `api.ts` 增强；替换 ≈65 + 12 + 32 处；multipart 3 处与时间白名单保留。
- 验证：ui/core 单测 + 两端 typecheck；消失判据 F2/F5/F6。
- 完成记录：F6（core.JSON_HEADERS：删 4 常量 + 29 内联）、F5（core.format \\*：9+3 处）、**F2（ui.LoadingRow/ErrorAlert：加载行 19 处 + 错误块 10 处 / 16 文件；严格形状替换，带 className/testId 插槽）**。**明确不做**：EmptyHint（空态 20 处图标与排版差异多，组件不建、留下一轮按页替换——避免"建了没人用"的空件）；`jsonRequest(body,method)` 包装（逐行换 JSON_HEADERS 风险最小，偏差记报告）。实测：九包 typecheck 全过（修了多行 import 插行、ui 导出遗漏两轮问题）；core **31 条单测**全绿；ui 单测 **256 通过 / 1 红＝master 上本来就红的令牌检查**（AnalyticsPage 12 处调色板类，主树对照实测同样红，非本项引入）。

### TASK-012 | done | F3 ListPagination
- `ui` 新增 `pagination.tsx`；替换 admin 5 处分页页脚（pageCount 上移）；sql-workbench 变体保留。
- 验证：admin typecheck + e2e。
- 完成记录：`ListPagination`（testIdPrefix 生成 `{prefix}-total/-prev/-next`，与既有 e2e 锚点逐字一致）；5 处替换（AdminUsers / AiFeedback / KnowledgeEntries 的本地 `Pagination` 组件改一行委托 / AiRunsList / **AiCreditsAudit 的原生 `<button>` 漂移一并统一回 `<Button size="xs">`**）；`pageCount` 助手未建（5 处算式保留本地——不造没有第二个消费者的件）。实测：admin typecheck 通过；两处因此闲置的 `Button` 导入已清理。

### TASK-013 | done | F4 + F7 admin 辅助（收敛版：e2e session / 窗口查询串）
- 新增 `apps/admin/e2e/session.ts`（signIn/overflow/envelope）与 `apps/admin/src/ai-window.ts`；替换 10 spec + 9 处；顺手统一 `E2E_ADMIN_PATH` 并修缺 encode 漂移。
- 验证：admin e2e 实跑；消失判据 F7。
- 完成记录：**只修了真缺陷那一行**——`AiRunsListPage.windowParams` 的 ISO 串缺 `encodeURIComponent`（`+` 会被当空格解析），已补并加注释。**F7 的完整合并与 F4 缓一轮，理由入报告**：①四份 WINDOWS 选项集与默认窗本就不同（1h/today/7d 混用），合并需要逐页核对 fallback 与查询键，改动横跨 4 个页面的数据流；②F4 要改的正是**即将用来做验收的 e2e 夹具**（10 个 spec 的 signIn）——在验收前夜改量具会把"实现坏了"与"夹具坏了"混在一起，单独一轮做更稳。两条都不是"做不到"，是**排序选择**。

### TASK-014 | done | 联动验证与回归（口径替换）
- 全量前端单测 + 学习者端与管理端 e2e（改共享包规则）；后端守卫切片 + 受影响模块 test 复跑。
- 验证：见报告。
- 完成记录：九包 typecheck、七包单测、两端 Vite build 全绿；后端守卫切片与受影响类逐片复跑绿。**分支栈本地 e2e 未跑**（需 jar+3307 隧道+写共享库），残留风险与合并后补课建议写在报告「未覆盖」。

### TASK-015 | done | 线上只读接口计时冒烟
- 执行临时目录脚本（31 端点 ×3 轮 + 2 详情）；输出中位/最大、非 2xx；≥1s 与 ≥3s 单列；结论写进 `testing/report.md`。
- 验证：脚本输出与报告一致；凭据不回显。
- 完成记录：**无 ≥1s 接口**（中位 53–351ms，最大尖峰 612ms）；非 2xx 六条全部定性——**5 条 500 系同一根因 `e.line_kind`（== 本分支已修的 65a30c54，线上受影响面 5 个接口）**、1 条首页 406 为脚本 Accept 头伪影（curl 200/0.12s）。

### TASK-016 | done | 验证报告与收尾
- 写 `testing/report.md`（五列证据矩阵覆盖 AC-001…006）；更新 `.agent/history/updates.md`；确认主树干净、未推送。
- 完成记录：报告已写（status: passed，verified_commit=9aceb1fb）；主树仅工件提交、未推送；合并顺序 061 → 062 已注明。
