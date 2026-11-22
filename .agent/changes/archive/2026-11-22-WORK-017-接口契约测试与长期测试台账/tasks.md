---
artifact: tasks
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
approved_at: 2026-10-18 14:16:13 +0800
approver_role: CTO
---

# 接口契约测试与长期测试台账任务

## T-01 接口清单（L1 的基础）

- [x] 新建 `ApiAccess` 注解（`paideia-platform`）并给 21 个控制器标注；**人工清单 `ApiSurface.java` 已删除**（用户要求更自动）
- [x] 排除 `/api/v1/fixture/**`
- 验证：`ApiSurfaceTest` 的集合比较通过

## T-02 L1 路由清单闸

- [x] 新建 `ApiSurfaceTest.java`，从运行时路由 + 注解派生并检查四类漏扫（需 `@Qualifier("requestMappingHandlerMapping")`：Actuator 也注册了一个）
- [x] 反向验证五组全部按预期红：未标注解、不限方法、越前缀、ADMIN 误声明；恢复后 15 条全绿
- 验证：AC-001

## T-03 L2a 拒绝矩阵

- [x] 新建 `ApiContractTest.java`：匿名 401、越权 403、畸形体 400/415、`X-Trace-Id`、未知路径 404、包络正面样本
- [x] 路径变量替换：`{shareId}` 用随机 UUID、`{source}` 用 `text`，**其余一律填 1**（按名单逐个替换会在新增变量名时漏掉）
- 验证：AC-002..AC-005

## T-04 线上只读冒烟

- [x] 新建 `api-smoke.spec.ts` 与 `playwright.smoke.config.ts`
- [x] `@paideia/app` 增加 `test:api:online` 脚本
- [x] 缺 `E2E_BASE_URL` 时直接失败，不回退 localhost
- [x] `ALLOW_ONLINE_MUTATION` 默认关闭
- 验证：AC-006；**已在本机临时实例上跑通**（10 passed / 1 skipped），线上那一轮待部署后执行

## T-05 CI 闸门

- [x] 新建 `.github/workflows/verify.yml`：backend job（MySQL service container + `mvn -B verify`）、frontend job（typecheck + 单测）
- [x] 断言契约套件 `skipped == 0`（解析 surefire XML；找不到报告也算失败）
- 验证：AC-008（**尚未在 CI 上实跑过**——workflow 需要推送后才生效）

## T-06 部署闸

- [x] `release.ps1`：测试默认执行，新增 `-SkipTests` 逃生口（`-WithTests` 保留为兼容别名）
- [x] 健康检查后追加服务端只读探针（`/`、`/admin/index.html` 期望 200；`/api/v1/me` 期望 401）
- 验证：AC-007（脚本语法解析通过；**未执行真实部署**）

## T-07 台账

- [x] `.agent/testing/README.md` 与 `runs/` 已建
- [x] 首次 contract 与 smoke 运行各写一个文件（`2026-10-18T1442-contract-local.md`、`2026-10-18T1430-smoke-local.md`）
- [x] 提交前扫描不含主机名、账号、令牌、连接串（只写 `local`/`online`）
- 验证：AC-009、AC-010

## T-08 文档收口

- [x] `.agent/notes/dev-workflow.md` 追加"新增接口的完成标准"
- [x] `.agent/INDEX.md` 已加导航
- [x] `updates.md` 记录本轮

## 依赖与顺序

T-01 → T-02 → T-03 是同一上下文，可一并完成；T-04 独立；T-05/T-06 依赖 T-02/T-03 稳定；T-07 跟随每次运行；T-08 最后。

T-05 需要用户在 GitHub 仓库设置里启用 Actions（公开仓库默认开启）。CI 的 MySQL 口令用 workflow 内的 job 级环境变量（容器库，非真实凭据），不写真实口令。
