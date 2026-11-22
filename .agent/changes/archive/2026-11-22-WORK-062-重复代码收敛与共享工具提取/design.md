---
artifact: design
work_id: WORK-062
work: 重复代码收敛与共享工具提取
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 20:09:56 +0800
approver_role: CTO
---

# 重复代码收敛与共享工具提取设计

## 系统上下文

```
后端：platform（零依赖）◄── security ──◄ 各业务模块
      │                        │
      ├─ Hashes.sha256Hex      └─ CurrentUser.userId()
      └─ ToolArgs.*（工具入参）
      模块内：ai.internal.support.ModelJson、ops.OpsHttp（同包 package-private）

测试：paideia-app/src/test/java/io/github/xiaou61/ 根包静态工具类
      MockMvcBodies / TestTokens / Await / MockMvcSse / SqlCleanup / UserFixture / TestIds
      （零 pom 改动、零 Spring 上下文成本——既有 TestProfileConfiguration 禁止测试类
        自行 @Import，共享基类会按配置拆上下文，每份 15–25 秒）

前端：core（纯函数）◄── ui（哑组件）◄── apps/packages
      core: errorMessage / formatLocalDateTime / formatDurationMs / JSON_HEADERS / jsonRequest
      ui:   LoadingRow / ErrorAlert / EmptyHint / ListPagination
      admin: src/ai-window.ts、e2e/session.ts
```

## 组件与职责（签名 = 实现契约）

### 后端

| 件 | 落点与签名 | 调用点 | 承重差异（留在调用点） |
| --- | --- | --- | --- |
| `CurrentUser.userId()` | `paideia-security`：`public static long userId()`；catch `NumberFormatException \| BizException` → `BizException(UNAUTHENTICATED, "令牌主体不是有效的账号标识")`；另加 `public static long userIdOrNull()`（返回 0 当 null 由调用方判）——**仅当既有 `orNull()` 语义能被表达时才加，否则不加** | 11 处 try/catch 拷贝 + 6 处裸解析 | sandbox 两处原只 catch `NumberFormatException`，换后匿名请求文案从"未认证"变"令牌主体不是有效的账号标识"（HTTP 401 不变，属统一）；裸解析 6 处换后伪造令牌 500→401（修正） |
| `Hashes.sha256Hex` | `paideia-platform`：`public static String sha256Hex(String value)`；`MessageDigest` 失败抛 `IllegalStateException("JVM 缺少 SHA-256 实现")` | 9 处 | `EssayCollectionService` 的 catch **返回原值**（不丢内容的有意设计）保留：调用处包 try/catch 不动；`account.Digest` 改一行委托，其调用方不动 |
| `ToolArgs` | `paideia-platform`：`stringOf(Map,String)`（trim 空→null）、`longOf(Map,String)`、`intOf(Map,String)`、`instantOf(Map,String)`、`messageOf(RuntimeException)`（空则 `getClass().getSimpleName()`）、`pageOf/sizeOf(Map)`→现成 `PageQuery`（size 默认 20、clamp 1..100） | 12/7/12/2/4 处 | `AskTool`/`ResumeTools` 的"只认 Number"窄 longOf **保留本地**；`OpsAdminTools.stringArg/intArg`（带 fallback 的另一族）**保留本地** |
| `ModelJson` | `paideia-ai/internal/support/`：`static String stripFence(String)`（首换行后到末 ``` 之间 trim；不满足条件原样返回）、`static String textOrNull(JsonNode,String)`、`static String textOrEmpty(JsonNode,String)`（缺失→"" 且 trim） | 6 处逐字 + 5 处取值 | `CampItemParser.stripFence`/`EssayQuestionParser` 变体（截断时保留全部正文）**保留本地**；`SqlModelCall` 内联版保留 |
| `OpsHttp` | `paideia-app/ops` 包内 package-private：`static HttpClient client(Duration)`、`static Optional<JsonNode> getJson(HttpClient, String url, Duration)`（send + status==200 + readTree + InterruptedException 还原中断 + 失败 empty）、`static String trimTrailingSlash(String)` | Prometheus/Loki/Tempo 三家 + Grafana 反代 1 处 | 各家失败返回类型与 `"status":"success"` 校验**保留**（Loki/Tempo 没有该字段，不收紧）；不建接口/继承 |

### 测试（都在 `paideia-app/src/test/java/io/github/xiaou61/`，package-private static）

| 件 | 签名 | 调用点 |
| --- | --- | --- |
| `MockMvcBodies.body` | `static String body(MvcResult result)`（UTF-8） | 14 类 |
| `TestTokens` | `static String bearer(AuthPort authPort, String subject)` / `(..., String role)`；**不提供默认 subject/role** | 28 类约 36 方法 |
| `Await` | `static boolean until(BooleanSupplier ready, long timeoutMs, long intervalMs) throws InterruptedException`；`static <T> Optional<T> value(Supplier<T> probe, Predicate<T> ready, long timeoutMs, long intervalMs) throws InterruptedException`；**不断言、不吞中断** | 7 类 10 循环 |
| `MockMvcSse` | `static MvcResult exchange(MockMvc mockMvc, MockHttpServletRequestBuilder request) throws Exception`（asyncStarted→asyncDispatch） | 4 类 |
| `SqlCleanup` | `static void deleteByUser(JdbcTemplate, Object userId, String... tablesInOrder)`；`static void deleteAll(JdbcTemplate, String... tablesInOrder)`；**顺序=参数顺序** | 5 类的表循环样板（Camp/CampMastery、AdminUser、McpAdmin、Resume） |
| `UserFixture` | `static long insertLearner(JdbcTemplate, String username, String email, String passwordHash)`（按 username 回查 id，避免 MAX(id) 竞态） | 4–5 处 CURRENT_TIMESTAMP 变体（UTC 变体与 passwordless 变体保留） |
| `TestIds` | `static String suffix()`（`% 1_000_000L`）；`static long prefixed(String prefix, String suffix)`；**前缀与模数入参化** | 顺手替换（不专门扫全仓） |

### 前端

| 件 | 落点与签名 | 承重差异 |
| --- | --- | --- |
| `errorMessage` | `@paideia/core`：`errorMessage(cause: unknown, fallback?: string): string`（Error→message；否则 fallback ?? String(cause)） | `auth.messageOf` 改委托（保留 ApiError 分支与"请求失败，请稍后重试"）；admin 两处私有同名函数删除、兜底"未知错误"经 fallback 传入；顺手消掉 `(x.error as Error).message` 的不安全断言（24 处） |
| `LoadingRow` | `@paideia/ui`：`{ label: string; className?: string; testId?: string }`（Spinner + muted 文本，容器 `<p>`） | 卡片内 admin 版（`pb-3`）经 className 传 |
| `ErrorAlert` | `@paideia/ui`：`{ title: string; error: unknown; className?: string; testId?: string }`（内部 2 行归一，不依赖 core） | 各页文案不同 → title 传参 |
| `EmptyHint` | `@paideia/ui`：`{ title: string; description?: ReactNode; icon?: ReactNode; className?: string; testId?: string }`（Empty+EmptyMedia(variant=icon)） | 图标每页不同 → icon 插槽 |
| `ListPagination` | `@paideia/ui`：`{ total?: number; page: number; totalPages: number; onPage: (p:number)=>void; testIdPrefix?: string; className?: string }` | testIdPrefix 保住 e2e（`admin-entry-prev/next` 等）；`pageCount` 计算一并上移 |
| `formatLocalDateTime` | `@paideia/core`：`(value: string \| null \| undefined, fallback = '—')`（解析失败原样返回） | AnalyticsPage 的 `toLocaleTimeString` 与 AiRunsScreen 的 clock 保留本地 |
| `formatDurationMs` | `@paideia/core`：`(value: number \| null \| undefined, fallback = '—')`（<1000 → `N ms`，否则 `N.N s`） | 无 |
| `JSON_HEADERS` / `jsonRequest` | `@paideia/core`（`src/api.ts`）：`JSON_HEADERS` 常量 + `jsonRequest(body, method='POST')` → `RequestInit` | 3 处 multipart **不动**（手设 Content-Type 会写坏 boundary） |
| `admin/e2e/session.ts` | `signIn(page, account = ADMIN)`、`overflowX(page)`、`expectNoOverflow(page, label)`（expect.poll 版）、`envelope<T>(data)` | users.spec 的 `E2E_ADMIN_PATH` 与 `ADMIN_ENTRY` 顺手统一；负例账号经参数传 |
| `admin/src/ai-window.ts` | `WINDOWS`、`windowParams(choice, fallback='7d')`、`queryString(record)`（跳过 null/''） | 三份 WINDOWS 选项集不同 → 以并集为准并在页面传 fallback；同步修 `AiRunsListPage` 缺 encodeURIComponent 的真实漂移 |

## 每片的"消失判据"（替换完整性用 grep 验证，比数文件更硬）

| 片 | 判据（执行后应为空或只剩白名单） |
| --- | --- |
| B1 | `rg "令牌主体不是有效的账号标识" backend --type java` 仅剩 `CurrentUser.java` 一处；`rg "Long.parseLong(CurrentUser.subjectId())" backend` 为空 |
| B2 | `rg "MessageDigest.getInstance(\"SHA-256\")" backend --type java` 仅剩 `Hashes.java`（及 Essay 的保留 catch 调用处表述） |
| B3 | `rg "String text = raw.toString\(\).trim\(\)" backend --type java` 为空；`class SimpleName` 式 messageOf 重复为 0 |
| B4 | `rg "lastIndexOf(\"```\")" backend/paideia-ai --type java` 仅剩 ModelJson 与两个保留变体 |
| B5 | 三家 client 中 `HttpClient.newBuilder()` 仅剩 `OpsHttp` |
| T1 | `rg "getContentAsString\(StandardCharsets.UTF_8\)" backend/*/src/test` 仅剩 MockMvcBodies 与白名单 |
| T2 | `rg "\"Bearer \" \+ authPort.issue" backend/paideia-app/src/test` 为 0 |
| T3 | `rg "System.currentTimeMillis\(\) \+ 1?_?0000" backend/paideia-app/src/test` 仅剩白名单（ApiWalkthrough best-effort 循环等） |
| T4 | `rg "asyncDispatch\(started\)" backend/paideia-app/src/test` 仅剩 MockMvcSse |
| F1 | `rg "cause instanceof Error \? cause\.message" frontend` 为 0（含 admin 两处私有函数已删） |
| F2 | `rg "data-testid=\"[a-z-]+-state\"" frontend` 数量不变（testId 仍由页面传）；`rg "<Spinner />" frontend/apps --glob '*.tsx'` 仅剩共享件与动画类用法 |
| F3 | admin 五个分页页不再各自内联 `上一页/下一页` 按钮对 |
| F5 | `rg "toLocaleString\('zh-CN', \{ hour12: false \}\)" frontend` 仅剩白名单（AnalyticsPage 时分秒版等） |
| F6 | `rg "'Content-Type': 'application/json'" frontend` 仅剩 core 与三处 multipart 周边 |
| F7 | `rg "WINDOWS\b" frontend/apps/admin/src` 仅剩 `ai-window.ts` 与页面 import |

## 顺序与依赖

B1→B5 与 T1→T5 互相独立；F1/F5/F6（core）先行，F2/F3 依赖 F1（ErrorAlert 内部自带归一，不硬依赖，但同批更省）；F4/F7 独立。T3 依赖 B 片全部完成后再动（避免与刚改的 Memory 测试互相踩）。
每片一个提交；前端片按包 typecheck+单测，改 `core/ui` 后跑两端 e2e。
