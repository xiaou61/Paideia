---
artifact: design
work_id: WORK-017
work: 接口契约测试与长期测试台账
status: approved
mode: strict
workflow: full
depends_on: [WORK-001]
related_to: [WORK-016]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 14:16:12 +0800
approver_role: CTO
---

# 接口契约测试与长期测试台账设计

## 文件清单

| 文件 | 作用 |
| --- | --- |
| `backend/paideia-platform/src/main/java/io/github/xiaou61/platform/ApiAccess.java` | 访问级别注解（声明处，21 个控制器已标） |
| `backend/paideia-app/src/test/java/io/github/xiaou61/ApiSurfaceTest.java` | L1：运行时路由 vs 清单，集合相等 |
| `backend/paideia-app/src/test/java/io/github/xiaou61/ApiContractTest.java` | L2a：拒绝矩阵 |
| `frontend/apps/app/e2e/api-smoke.spec.ts` | L3：线上只读冒烟 |
| `frontend/apps/app/playwright.smoke.config.ts` | 冒烟专用配置（不启 webServer、不跑 globalSetup） |
| `.github/workflows/verify.yml` | L4：CI |
| `scripts/release.ps1` | L4：部署闸 |
| `.agent/testing/runs/*.md` | 长期台账 |

## ApiAccess（2026-10-18 修订）

```java
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface ApiAccess {
    Access value();
    enum Access { PUBLIC_READ, PUBLIC_WRITE, AUTHENTICATED, ADMIN }
}
```

方法级覆盖类级；`KnowledgeFileController` 是实际用例（上传 ADMIN、读取 PUBLIC_READ）。

`Access` 的四种取值不是装饰，各自驱动不同的矩阵分支：

| 值 | 含义 | 匿名探测 | learner 探测 |
| --- | --- | --- | --- |
| `PUBLIC_READ` | 匿名可读（公开分享、附件） | 调用，断言**不是** 401 | — |
| `PUBLIC_WRITE` | `/auth/**` 凭据交换 | **不调用**（发信、限流会留下痕迹） | — |
| `AUTHENTICATED` | 只要求登录 | 断言 401 | — |
| `ADMIN` | `hasRole('ADMIN')` | 断言 401 | 断言 403 |

排除 `/api/v1/fixture/**`：那是测试专用控制器，会被组件扫描进测试上下文，不属于对外契约。

## L1 枚举实现

```java
@Autowired RequestMappingHandlerMapping mapping;

Set<String> actual = mapping.getHandlerMethods().keySet().stream()
    .flatMap(info -> info.getPatternValues().stream()
        .flatMap(path -> info.getMethodsCondition().getMethods().stream()
            .map(m -> m.name() + " " + path)))
    .filter(line -> line.endsWith(" /api/v1/**") ...)
```

要点：
- 用 `MappingInfo` 而不是扫源码：能拿到类级前缀、`{var}` 规范形式与真实方法集合。
- 过滤条件用 `path.startsWith("/api/v1/")`，不是在字符串里 `contains`（`/probe/**` 之类不该混进来）。
- 断言用 `containsExactlyInAnyOrderElementsOf`，失败信息天然列出双向差异。

## L2a 拒绝矩阵

对清单每条路由生成请求。路径变量按名称填值：

| 变量 | 值 |
| --- | --- |
| `{id}` / `{entryId}` / `{userId}` | `1` |
| `{shareId}` | 随机 UUID（避免撞真实数据） |
| `{source}` | `text` |

四组断言：

1. **匿名 401**（`AUTHENTICATED` / `ADMIN`）：只断言状态码，因为安全层的 401 由 Spring 默认入口点产生，**没有** `ApiResponse` 包络。
2. **越权 403**（`ADMIN`）：learner 令牌；同样只断言状态码。
3. **畸形体**：对有 `@RequestBody` 参数的路由，带对应身份令牌发送 `{ not json`。
   - `consumes` 含 `multipart/form-data` 的（`POST /api/v1/knowledge/admin/files`）期望 **415**，不是 400。
   - 其余期望 **400 + `code=40000`**。
   - 身份选择：`ADMIN` → admin 令牌；其余 → learner 令牌。
4. **响应头**：每个被探测的响应都带 `X-Trace-Id`，且匹配 `[A-Za-z0-9_-]{1,64}`。

另加两条全局断言：
- `GET /api/v1/me` 带 learner 令牌 → 200、`code=0`、`traceId` 非空（包络存在性的唯一正面样本）。
- 未知路径 `/api/v1/__not_a_route` → 404（证明"不存在"不会被兜成 500）。

### 不变量

- 矩阵**永不**发送合法业务数据，因此不会写库、跑代码、花积分。
- 畸形体在参数绑定阶段就被拒，到不了 service。

## 令牌

`AuthPort.issue(new AuthPort.Subject(...))` 直接签发，不依赖账号表——与既有集成测试一致。

- learner：`Subject("1", ROLE_LEARNER)`
- admin：`Subject("1", ROLE_ADMIN)`

注意：`GET /api/v1/me` 会读账号表，主体 `1` 在测试库里存在（种子账号 id=1）。为稳妥，包络断言改用 `GET /api/v1/ai/credits`（只读、按令牌主体查积分，不存在的用户返回 0 或空而非异常）。**两者都试，取实际通过的那个，并在注释里写明原因。**

## 上下文复用

两个测试类都用：

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
```

**不加 `properties`**——新增配置键会让 Spring 多起一个上下文（15–25 秒）。这与 `application-test.yml` 的既有纪律一致。

## 跳过的边界

按 `.agent/rules/always.md` 的既有规则，缺 `PAIDEIA_TEST_DB_PASSWORD` 时集成测试跳过而不失败。本项**不改这条规则**，改为在 CI 里：

1. 用 MySQL service container 提供数据库并注入口令；
2. 构建后断言 `ApiSurfaceTest` 与 `ApiContractTest` 的 `skipped == 0`（解析 surefire XML）。

因此"本地可能跳过"与"合并必不跳过"同时成立，且不违背已签署规则。

## 线上冒烟

`playwright.smoke.config.ts`：

- `testDir: './e2e'`、`testMatch: 'api-smoke.spec.ts'`
- `use.baseURL = process.env.E2E_BASE_URL`（缺失时**直接抛错**，不回退 localhost——避免"以为在测线上其实在测本机"）
- 无 `webServer`、无 `globalSetup`

用例（全部只读）：

| 编号 | 请求 | 期望 |
| --- | --- | --- |
| SM-01 | `GET /actuator/health` | 200，`status=UP` |
| SM-02 | `GET /api/v1/me`（无令牌） | 401 |
| SM-03 | `GET /api/v1/ai/admin/credits/audit`（learner 令牌） | 403 |
| SM-04 | `GET /api/v1/knowledge/categories`（learner） | 200 + `code=0` |
| SM-05 | `GET /api/v1/knowledge/entries?size=1`（learner） | 200 |
| SM-06 | `GET /api/v1/notes`（learner） | 200 |
| SM-07 | `GET /api/v1/ai/settings`（learner） | 200 |
| SM-08 | `GET /api/v1/knowledge/entries/999999999`（learner） | 404 |
| SM-09 | `GET /api/v1/__not_a_route` | 404 |
| SM-10 | `POST /api/v1/notes`（无令牌） | 401 |
| SM-11 | 上述任一响应的 `X-Trace-Id` | 存在且格式合法 |

登录：一次 `POST /api/v1/auth/login`，取 `data.accessToken`；失败则整组红。admin 令牌同理，仅在需要时登录一次。

**写路径**：`ALLOW_ONLINE_MUTATION=1` 才注册，默认 `test.skip`。即使打开，也只做"创建 → 删除"的自清理闭环，且标题带随机前缀。

## 台账写入

测试不自动写台账（测试里写文件会在 CI 里产生噪音与并发问题）。改为**人执行时**记录：

- `contract` 层：跑完 `mvn -B verify` 后，把 surefire 汇总抄进新文件。
- `smoke` 层：跑完冒烟后，把 passed/failed 抄进新文件。

如果将来证据量变大，再考虑由脚本生成——本期不做（YAGNI）。

## release.ps1 改动

1. 新增 `-SkipTests`；测试**默认执行**（`mvn -B verify`），失败即 `throw`。
2. 健康检查通过后追加服务端只读探针（经 quick-server 在服务器本地 curl）：
   - `/actuator/health` → 含 `"status":"UP"`
   - `/api/v1/me` 无令牌 → 401
   - `/api/v1/__not_a_route` → 404
   - `/` → 200
   任一不符即 `throw`。
3. 脚本仍然**不含任何服务器地址与凭据**（沿用 quick-server 别名）。

## 不做

- 不改安全层 401/403 的响应包络。
- 不做 OpenAPI 快照。
- 不做按 git diff 的选择性测试。
- 不为覆盖率设门槛。
