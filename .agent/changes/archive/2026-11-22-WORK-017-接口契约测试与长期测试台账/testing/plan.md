---
artifact: test-plan
work_id: WORK-017
work: 接口契约测试与长期测试台账
status: ready
mode: strict
workflow: full
created: 2026-10-18
updated: 2026-10-18
---

# 接口契约测试与长期测试台账测试计划

## 测试范围

本项交付的是**测试基础设施**，所以要验两件事：这些闸门自己能跑通，且它们真的会咬。

| 层 | 载体 | 验什么 |
| --- | --- | --- |
| L1 | `ApiSurfaceTest` | 运行时路由与清单集合相等 |
| L2a | `ApiContractTest` | 每条路由的拒绝路径一致 |
| L3 | `api-smoke.spec.ts` | 部署实例的只读契约与鉴权边界 |
| L4 | `verify.yml`、`release.ps1` | 闸门被真正执行 |

## 环境与前置条件

- 后端：JDK 25、Maven、**独立测试库**（不要用共用 `paideia_test`——它与 e2e 种子已漂移）
- 前端：Node 24、pnpm 12
- L3 需要 `E2E_BASE_URL` 与学习者账号口令（只从环境变量来）
- 集成测试需要 `PAIDEIA_TEST_DB_PASSWORD`

## 验收矩阵

| AC | 验证方式 | 位置 |
| --- | --- | --- |
| AC-001 | 自动化 + 反向验证（临时加一条未登记路由） | `ApiSurfaceTest.runtimeRoutesMatchManifest` |
| AC-002 | 自动化；匿名遍历非公开路由 | `ApiContractTest.anonymousIsRejectedEverywhereItShouldBe` |
| AC-003 | 自动化；learner 遍历 ADMIN 路由 | `ApiContractTest.learnerIsForbiddenOnAdminRoutes` |
| AC-004 | 自动化 | `malformedBodyIsAClientError` |
| AC-005 | 自动化 | `everyResponseCarriesTraceId` |
| AC-006 | 代码检查 + 本机跑一轮 | `api-smoke.spec.ts` |
| AC-007 | 人工执行一次部署 | `scripts/release.ps1` |
| AC-008 | CI 首跑 | `.github/workflows/verify.yml` |
| AC-009 | 检查台账目录 | `.agent/testing/runs/` |
| AC-010 | 提交前扫描 | 同上 |

## 自动化检查

```bash
cd backend && source config/remote-db.local.env
PAIDEIA_TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/<独立库>?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=utf8' \
  mvn -B -o -pl paideia-app test -Dtest='ApiSurfaceTest,ApiContractTest'

cd frontend && pnpm --filter @paideia/app typecheck
MSYS_NO_PATHCONV=1 E2E_BASE_URL=… E2E_LEARNER_IDENTIFIER=… E2E_LEARNER_PASSWORD=… \
  pnpm --filter @paideia/app test:api:online
```

## 人工检查

- 反向验证：加一条未登记的 `@GetMapping`，确认 `ApiSurfaceTest` 红并点名它；删掉恢复绿。
- 部署闸：真跑一次 `release.ps1`，确认测试默认执行、探针在健康检查后运行。
- CI：推送后看首轮结论。

## 回归范围

- 既有 143 条后端测试（新测试不得改变它们的结果）
- 前端 `pnpm -r typecheck` 与 `pnpm -r test`

## 已知缺口

- 清单的访问级别若登记错误，矩阵不会发现（只按清单驱动）。
- 成功路径的覆盖依赖业务集成测试，无硬门槛。
- `release.ps1` 的探针只在服务器本地跑，无法在 CI 里验。
