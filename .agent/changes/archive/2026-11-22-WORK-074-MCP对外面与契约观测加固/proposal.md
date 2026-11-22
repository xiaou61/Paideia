---
artifact: proposal
work_id: WORK-074
work: MCP 对外面与契约观测加固
status: approved
created: 2026-11-05
updated: 2026-11-05
approved_by: xiaou61
approved_at: 2026-11-05 16:29:54 +0800
approver_role: owner
---

# 方案

## 背景与目标

见 `requirements.md`。一个 WORK、两期：先 B（MCP 对外面），后 C（契约观测）。

## 方案对比

### B-1 对外能力清单

- **方案 A（选用）运行时快照**：`paideia-app` 的 Spring 上下文里 `ToolCatalog` 是唯一真源；用一个集成测试读取 `catalog.snapshot()` 生成清单并断言治理不变量。
- 方案 B 静态解析源码：`new ToolDescriptor(` 在仓库出现 78 处（含测试桩/帮助方法），静态解析易误判、且与运行时注册可能漂移。**不选**。

### B-2 tool 契约测试（无副作用）

- **方案 A（选用）判定层测试**：复用 `ToolCatalog.view()` 的过滤链与 `McpToolInvoker` 的拒绝分支（未知工具 / 视图不可见 / scope 不足 / ADMIN 角色不足 / 限流 / 配额），用桩 caller 断言"被拒且**未执行**工具"。全部无副作用。
- 方案 B 真实调用每个 tool：会写库/花积分，违背 Q2 决策。**不选**。

### B-3 收敛审计

- 产出 `docs/` 下的 REST↔tool 对照审计（人工 + 脚本辅助），列出重复/错位项与处置；不改代码。

### C-1 跨模块事件/端口守卫

- **方案 A（选用）自写源码/反射守卫测试**：不引入新依赖，扫描 `backend/**/src/main` 中"谁 import 了什么事件/端口、谁发送了哪个事件"，对照一份声明表断言。
- 方案 B 引入 ArchUnit：能力强但新增依赖、学习成本，项目当前无此依赖。**本期不选**（可作为后续升级）。

### C-2 paideia-ai 包根 API 守卫

- 自写守卫测试：扫描非 `paideia-ai` 的 `src/main` 是否 `import io.github.xiaou61.ai.internal.`；有则失败（测试代码豁免）。零依赖。

### C-3 指标

- MCP 工具调用：扩展现有 `McpMetrics`（Prometheus/Micrometer）增加按 `tool` 的调用量与失败计数（当前已有 `calls{toolset,result}`、`duration{tool}`、`denied{reason}`，补 `tool` 维度）。
- 内容导入：导入脚本 `import-course.mjs` 结束时把"创建/更新/跳过/冲突"汇总写入一个后端 admin 端点，落 Analytics 表；管理端 Analytics 展示。

### C-4 导入漂移告警

- 复用 WORK-028 的 Prometheus+Grafana：后端暴露"内容漂移数"指标（源内容哈希 vs 最近导入记录），Grafana 规则告警（邮件）。漂移口径：按内容文件 sha256 与最近成功导入记录比对。

## 影响面

- 后端：`paideia-app`（装配/测试）、`paideia-mcp`（指标扩展）、`paideia-ops`（Analytics）、`paideia-platform`（如需只读快照 API）。
- 前端：管理端 Analytics 展示（若做导入指标可视化）。
- 无数据库迁移（Analytics 若需新表则另评，倾向复用现有表/只读聚合）。

## 风险与回退

- 契约/守卫测试不得引入非确定性或依赖网络；全部可离线跑（集成测试用 `scripts/test-db.sh` 独占库）。
- 每一切片独立提交，可单独回退。
