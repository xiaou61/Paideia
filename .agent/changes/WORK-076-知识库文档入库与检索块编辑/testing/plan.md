---
artifact: testing-plan
work_id: WORK-076
work: 知识库文档入库与检索块编辑
status: done
created: 2026-11-06
updated: 2026-11-06
---

# 验证计划

## 分层与取舍

本项 `mode: strict`：跨 `knowledge` / `ai` 两个模块、新增 2 条迁移、新增管理端端点与学习者端页面。验证按"最窄可信检查"分层，**不跑无差别全量**（项目约定：切片提交不跑 `mvn verify` 全量）。

| 层 | 验什么 | 手段 |
| --- | --- | --- |
| 模块内单测 | 校验器、编排、块编辑/RAG 合并、mapper 之外的纯逻辑 | Maven `-Dtest=<类名>`（Mockito，无 DB） |
| 迁移 | 两条新迁移能被应用、命名与校验和合法、不与种子冲突 | `scripts/test-db.sh` 全新独占库跑 Flyway + 迁移三件 |
| 守卫 | 新端点已纳入接口契约、401/403 矩阵、模块边界、接口面计数 | `ApiContractTest` / `AuthorizationIsolationTest` / `ModularityTest` / `ApiSurfaceTest` |
| 真实 SQL | mapper SQL 在真实 MySQL 上正确（内存单测测不出 `SELECTid`、列前缀歧义） | `scripts/test-db.sh` + 集成测试（真实 controller→service→mapper→DB） |
| 全链路（模型打桩） | 上传→抽取→落库→去重→目录树→发布进语料 | `KnowledgeImportFlowIntegrationTest`（只把 `DocumentDistiller` 换成桩） |
| 前端 | 类型、client 载荷、构建、页面可达与失败态 | `typecheck` / `vitest` / `build` / Playwright（3 条 e2e 真跑 + 1 条 mock 失败态取证） |
| 结构图 | 表/端点计数与声明一致 | `node scripts/gen-arch-diagram.mjs --check` |

## 刻意不做的

- **不打真实模型**：全仓 AI 测试统一打桩（不打真实上游、不花积分）。真实蒸馏质量留作部署后人工试跑，在报告「未执行与残留风险」里如实记录，不冒充通过。
- **不跑 `mvn verify` 全量**：只跑受影响的切片 + 守卫组合。
- **不做并发/压测**：本项是管理端低频写入路径，无并发要求。

## 判据

- 每一条 AC 必须有"命令 + 结果 + 证据"对应；缺证据的 AC 标为未覆盖，不得写"满足"。
- 反向可证：迁移三件与接口契约守卫能因注入错误而红（既有守卫自带反向样例）。
