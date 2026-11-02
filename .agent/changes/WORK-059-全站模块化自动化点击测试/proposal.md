---
artifact: proposal
work_id: WORK-059
work: 全站模块化自动化点击测试（ego-browser）
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 09:18:30 +0800
approver_role: CTO
---

# 全站模块化自动化点击测试提案

## 摘要

用 ego-browser 写一套**脚本化点击**测试：每个模块一个 `.mjs` 场景，由一个共用 harness 提供登录、跳转、点击、填表、断言、截图；
一个 runner 按模块或全量执行；一个登记表 + 校验脚本保证「新增路由必定有模块归属、每个模块必定有场景」。

不做 LLM 自由探索，也不扩 Playwright。ego 负责「真的点」，Playwright 继续负责字段级与接口级。

## 推荐方案

**脚本化 ego 点击 + 模块登记表 + 覆盖闸门。**

核心机制：

1. `scripts/ego/modules.mjs` 是唯一模块清单：模块 → 路由 → 场景文件 → 账号角色。
2. `scripts/ego/harness.mjs` 提供 `step()` / `goto()` / `clickText()` / `fill()` / `expectText()` / `expectUrl()` / `shotOnFail()`；
   登录一次，整个 run 复用同一个 TaskSpace 与 Page。
3. `scripts/ego/scenarios/*.mjs` 每个模块一个文件，导出的每条用例是「标题 + 是否写入 + 步骤函数」。
4. `scripts/ego/run.mjs` 支持 `--module <name>` / `--all` / `--read-only` / `--write`，逐条执行、失败不中断、最后写报告。
5. `scripts/ego/check-map.mjs` 解析两个 `router.tsx` 与模块清单，做三件事：路由全覆盖、无重复归属、场景文件存在且至少 3 条用例。
6. 报告写 `.agent/testing/runs/`；截图写 `.agent/testing/.artifacts/`（被忽略，不提交）。

为什么是脚本而不是让 ego 自己探索：回归测试要可重复、可定位、可比较。LLM 探索适合发现新问题，不适合每天跑同一批断言。
ego 的页面 API 已经能完成点击、填表、等待与截图，不需要额外依赖。

## 范围

- 18 个模块（学习者端 12 + 管理端 6），57 条路由全部登记。
- 框架：清单、harness、runner、校验、报告。
- 场景：每个模块至少 3 条只读用例；写入用例覆盖「创建 → 刷新仍在 → 删除」的模块（笔记、简历、知识条目、SQL 数据集）。
- 文档：`scripts/ego/README.md`，写清怎么跑、怎么加模块、怎么写用例。

## 备选方案

### 方案 B：ego 自由探索（LLM 驱动）

让 ego 按「把知识库点一遍」这样的目标自己找路、自己判断。
**最强理由**：能发现人没想到的路径，写用例成本低。
**不选原因**：结果不可重复，失败无法稳定复现，报告无法比较；每轮都要模型推理，慢且贵。
保留为后续可选的「探索模式」，不进本项。

### 方案 C：继续扩 Playwright

**最强理由**：现有 25 个 spec、断言工具成熟、速度最快。
**不选原因**：用户明确要 ego lite 的真实点击；且 ego 能看到用户真实会话与跨页行为，Playwright 在登录限流、真实浏览器环境上更绕。
不删除现有 Playwright，两者并存。

### 方案 D：只做接口走查（已有 ApiWalkthrough）

**最强理由**：成本最低，已覆盖全部端点。
**不选原因**：它证明不了「按钮点了有反应、页面不白屏、入口找得到」——正是用户要的那一层。

## 仓库影响

| 区域 | 影响 |
| --- | --- |
| `scripts/ego/` | 新增目录：清单、harness、runner、校验、18 个场景、README |
| `frontend/apps/*/src` | 仅在选择器不稳定时补 `data-testid`，不改逻辑与样式 |
| `.agent/testing/runs/` | 每次运行追加报告 |
| `.agent/testing/.artifacts/` | 新增忽略目录，存失败截图 |
| `.gitignore` | 增加 `.agent/testing/.artifacts/` |
| 产品行为 | 无变化 |

## 交付拆分

1. **框架**：清单 + harness + runner + 校验 + 报告（可先跑 1 个模块证明链路）。
2. **学习者端场景**：L1–L12，按 4 个一批交付。
3. **管理端场景**：A1–A6。
4. **全量线上跑**：只读全绿，写入轮次验证清理，报告入台账。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| 线上写数据污染 | 专用账号 + 运行前缀 + 结束删除 + 写入必须显式 `--write` |
| 选择器随界面漂移 | 优先 `data-testid`，其次 `loc=role` 文本；失败报告指出具体步骤 |
| 动态内容导致断言不稳 | 只断言「有内容或明确空态」，不写死具体条目标题；必要时先创建自己的夹具 |
| ego API 与 Playwright 不同 | 只用指南列出的 API；需要时用 `page.evaluate()` 兜底 |
| 全量运行时间长 | 分模块可单跑；全量只读按模块顺序执行，报告如实记录耗时 |
| 登录限流 | 整个 run 只登录一次，复用同一 TaskSpace |
| 凭据泄漏 | `.secrets/ego.local.json` 被忽略；报告脱敏；提交前扫 diff |

## 验收映射

| 需求 | 验收 | 交付 |
| --- | --- | --- |
| REQ-001 | AC-001 | 模块清单 + 校验脚本 |
| REQ-002 | AC-003 | 18 个场景文件 |
| REQ-003 | AC-002、AC-004 | runner |
| REQ-004 | AC-001 | 校验脚本反向验证 |
| REQ-005 | AC-005、AC-008 | 前缀与清理 |
| REQ-006 | AC-006 | 本地配置 + 报告脱敏 |
| REQ-007 | AC-007 | 失败证据 |
| REQ-008 | AC-002、AC-005 | 只读/写入分档 |
| REQ-009 | AC-008 | 单次登录、条件等待 |
| REQ-010 | AC-010 | 台账写入 |
| REQ-011 | AC-009 | 只加测试与 testid |
| REQ-012 | AC-007 | 用例标题与步骤记录 |

## 待决定事项

无。环境（线上）与范围（18 个模块）已由用户裁决。
