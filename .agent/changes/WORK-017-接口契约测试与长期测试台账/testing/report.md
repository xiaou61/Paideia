---
artifact: test-report
work_id: WORK-017
work: 接口契约测试与长期测试台账
status: passed
mode: strict
workflow: full
evidence: required
created: 2026-10-18
updated: 2026-10-18
verified_commit: 6b30d4f
accepted_by: xiaou61
accepted_at: 2026-10-29 13:18:10 +0800
---

# 接口契约测试与长期测试台账验证报告

## 验证环境

- 代码：`6b30d4f`，工作区不干净（并发会话在飞改动 + 本项未提交）
- 后端：JDK 25.0.4、Maven 3.9.0、Spring Boot 4.1.1、Spring Framework 7.0.9
- 数据库：本机 MySQL 8.0，独立验证库 `paideia_verify_work017`（从零跑 Flyway）。
  **不用共用 `paideia_test`**：它与 e2e 的 `db/devdata` 已漂移，本轮开始时以
  `V14 checksum mismatch` 让全部集成测试上下文起不来（既有环境问题，与本项无关）。

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 端点声明检查 | `mvn -B -o -pl paideia-app test -Dtest=ApiSurfaceTest` | 0 | 通过 | 6 条全绿；四类缺陷均反向验证过 |
| AC-001 反向验证 | 注入未标注解/不限方法/越前缀/ADMIN 误声明后重跑 | 1 | 通过 | 四类均被点名；恢复后全绿 |
| AC-002 拒绝矩阵 | `mvn -B -o -pl paideia-app test -Dtest=ApiContractTest` | 0 | 通过 | 9 条全绿，遍历全部端点 |
| AC-003 越权 403 | 同上（`declaredAdminReallyIsAdmin`） | 0 | 通过 | 17 条 ADMIN 路由全部 403 |
| AC-004 错误码与包络 | 同上（`malformedBodyIsAClientError`） | 0 | 通过 | 400/415 与 `code=40000` |
| AC-005 X-Trace-Id | 同上（`everyResponseCarriesTraceId`） | 0 | 通过 | 除凭据交换外全部端点 |
| AC-006 全接口自查 | `mvn -B -o -pl paideia-app test -Dtest=ApiWalkthroughTest` | 0 | 通过 | **69 步 / 69 通过 / 0 失败**，覆盖 62 个端点全集（连续三轮稳定） |
| AC-006 覆盖断言 | 同上（`assertFullCoverage`） | 0 | 通过 | 首次运行抓出 5 条未覆盖，补齐后为空 |
| AC-007 无 CI、未改部署 | `git status -- .github scripts/release.ps1` | 0 | 通过 | `.github` 不存在；`release.ps1` 已还原无 diff |
| AC-008 缺口令时跳过 | 不设 `PAIDEIA_TEST_DB_PASSWORD` 跑契约套件 | 0 | 通过 | 15 条全部 Skipped、`BUILD SUCCESS`（既有规则，已在文档点名） |
| AC-009 台账文件 | 运行后检查 `.agent/testing/runs/` | 0 | 通过 | 每轮自动生成一份；含步数、覆盖端点数、逐条结果 |
| AC-010 台账脱敏 | 人工扫描 run 文件 | 人工 | 通过 | 只写 `local`，无主机名/账号/令牌/连接串 |
| 回归 | `mvn -B -o -pl paideia-platform test` | 0 | 通过 | 11 条全绿（新增注解未破坏平台契约） |
| 回归 | `mvn -B -o -pl paideia-app test -Dtest=ModularityTest` | 0 | 通过 | 模块边界校验通过 |

## 验收结果

全部 10 条通过。

## 失败与未验证项

无未通过的验收标准。以下是有意不做的：

- **不做线上冒烟**：用户明确排除 CI 与部署自动化，自查只跑本地。
- **沙箱不验真实执行**：只验接口可达（503 未启用也算通），真跑代码由 `SandboxIntegrationTest` 覆盖。

## 剩余风险

- **访问级别仍要人工声明**（`@ApiAccess`）。漏声明会红、标错也会红（L2a 的声明与行为对照），
  但"声明和业务意图不符"这件事机器无法判断。
- **自查的覆盖面靠 `ApiWalkthroughTest` 手工追加**：覆盖断言保证"没漏端点"，但保证不了
  "新端点的断言足够深"——它只验状态码与链路可达。
- 共用测试库 `paideia_test` 与 e2e 种子的漂移仍在（本轮用独立库绕开）。
- 无请求体的写端点（4 条）无法在 `ApiContractTest` 里无副作用探测鉴权；
  但它们的**正面路径**已由 `ApiWalkthroughTest` 真实走过（真数据、真副作用、结尾清理）。
- **自查对顺序敏感的两处已修**：来源地址随机化（避开同来源发信限流）；
  路径归一化改为"变量少的 pattern 优先"，否则 `/credits/audit` 会被 `/credits/{userId}` 抢走。
  连续三轮合跑稳定通过。

## 台账

- `2026-10-18T1535-walkthrough-local.md`：69 步全通过
