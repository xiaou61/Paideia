---
artifact: proposal
work_id: WORK-060
work: 数据集工坊与公开私有治理
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 11:10:58 +0800
approver_role: CTO
---

# 数据集工坊与公开私有治理提案

## 摘要

本项在 WORK-035 的三源数据集模型上做两件事，**不新增表、不改上传校验**：

1. **公开/私有变成用户可见、可选、可切换的一等属性**：保存时选，目录里看，作者可改；
2. **AI 数据集工坊**：助手能新建或基于现有数据集改，页面把「几张表、哪些表」可视化，用户确认后才保存。

两件事共用同一条写路径：`SqlDatasetUploadService` 的白名单 + 沙箱干跑 + 配额。AI 只产出 SQL，目录只读，
唯一写库的动作是用户点击。

## 推荐方案

| 方向 | 现成的东西 | 本期怎么做 |
| --- | --- | --- |
| 公开/私有 | `sql_datasets.source` + `status` 已能表达 private/draft 与 community/published | 不新增列；上传接受 `visibility`，新增作者切换端点 |
| 上传者 | `owner_user_id` 已记录归属 | 新增 `AccountDirectory`（只读用户名）→ sandbox 的 `DatasetOwnerNames` 端口 → app 装配根桥接；不冗余用户名 |
| 目录 | `SqlDatasets.options` 已有可见集合 | 新增 `SqlDatasetCatalogService` + `GET /sql-datasets/catalog`，补表数/行数/上传者/是否本人 |
| AI 工具 | `planner_build_dataset` 已存在且只读，只是在 `planner` 组 | 给 `ASSISTANT` 的组清单加 `planner`；新增只读 `sql_read_dataset` |
| AI 回填 | `applySql` 的「待消费一次」通道 + 对话里的 SQL 代码块 | 同形新增 `applyDatasetSql`；对话里多一个「存为数据集」 |
| 页面 | 现有 `<details>` 数据集面板 + 右侧表结构 | 面板升级为「新建 / 基于当前数据集改」两模式；目录做成弹窗 |

**核心机制不变：内部助手工具保持只读。** `planner_build_dataset` 只拼 SQL，`sql_read_dataset` 只读 SQL；
保存仍走页面按钮与上传接口。这样「AI 很能干」与「模型不能替用户写库」同时成立。

## 范围

**做**：
1. 上传 `visibility`（private/public）+ 作者切换端点 + 目录端点；
2. 上传者用户名解析（account 目录 → sandbox 端口 → app 桥接）；
3. 学习者端数据集目录弹窗（公开 / 我的，表数、行数、上传者、使用、切换可见性）；
4. 数据集面板两模式（新建 / 改现有）、保存前表数预览、公开/私有选择；
5. 助手「存为数据集」回填通道；
6. 助手工具接线（`planner` 组 + `sql_read_dataset`）；
7. 管理端列表补上传者列。

**不做**：原地编辑、数据集搜索/分页/评分/评论、匿名访问、AI 执行 SQL、独立页面（先弹窗）。

## 备选方案

| 方案 | 最强理由 | 没选的原因 |
| --- | --- | --- |
| 新增 `visibility` 列 | 语义直白 | 与 `source/status` 重复；两套真源迟早打架，还要迁移 |
| 上传时把用户名冗余进表 | 目录查询简单 | 迁移 + 用户名变更/注销后的脏数据；读时解析只多一次批量查询 |
| 给助手开写权限，直接落库 | 一步到位 | 推翻「自主只建议不动作」的平台级约束；保存必须用户点 |
| 新开独立数据集页面 | 目录更完整 | 用户说「页面或弹窗」；弹窗不新增路由与导航入口，先把联动跑通 |
| 用 `sql_describe_dataset` 代替 `sql_read_dataset` | 不用加工具 | 结构不含 INSERT 数据；「给某表加 5 行」必须看到原 SQL 才能不丢数据 |

## 仓库影响

| 路径 | 改动 |
| --- | --- |
| `backend/paideia-sandbox/.../dataset/SqlDatasetUploadService.java` | `upload` 增加 `visibility`；插入 source/status |
| `backend/paideia-sandbox/.../dataset/SqlDatasetMapper.java` | 新增 `demoteToPrivate` |
| `backend/paideia-sandbox/.../web/SqlDatasetController.java` | 上传 visibility、`/visibility`、`/catalog` |
| `backend/paideia-sandbox/.../dataset/SqlDatasetCatalogService.java` | 新建：目录组装 + 上传者名字 |
| `backend/paideia-sandbox/.../dataset/SqlDatasetTools.java` | 新增 `sql_read_dataset` |
| `backend/paideia-sandbox/.../DatasetOwnerNames.java` | 新建端口 |
| `backend/paideia-account/.../AccountDirectory.java` + internal | 新建只读用户名目录 |
| `backend/paideia-app/.../DatasetOwnerNamesConfiguration.java` | 新建装配桥 |
| `backend/paideia-ai/.../tools/AgentToolBridge.java` | `ASSISTANT` 加 `planner` 组 |
| `backend/paideia-ai/.../conversation/PlannerPrompt.java` / `page-context.ts` | 数据集工具的使用说明 |
| `frontend/packages/knowledge/src/client.ts` | `uploadSqlDataset` 加 visibility；`datasetCatalog`、`setDatasetVisibility` |
| `frontend/apps/app/src/pages/SqlPracticePage.tsx` | 数据集面板两模式、预览、可见性、浏览入口 |
| `frontend/apps/app/src/components/DatasetCatalogDialog.tsx` | 新建目录弹窗 |
| `frontend/packages/assistant/src/context.tsx` / `conversation-panel.tsx` | `applyDatasetSql` + 「存为数据集」 |
| `frontend/packages/sql-workbench/src/dataset-schema.ts` | `parseCreateTableNames`（只用于预览） |
| `frontend/apps/admin/src/pages/SqlDatasetsPage.tsx` | 上传者列 |

无迁移、无新表；`/sandbox/status` 形状不变。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| 作者把公开集切回私有，正在用它的人突然失败 | 二次确认 + 文案写明「其他人将不能再使用」；不自动删除内容 |
| 目录暴露隐私 | 只返回用户名与规模；不返回邮箱、角色、SQL、数据行；别人的私有集不查 |
| AI 读整份 SQL 把数据行带进模型 | 只在用户点「加」时调用；工具描述明确限定；不进入页面上下文 |
| 生成 SQL 超出 3 表/12 列/200 行上限 | `planner_build_dataset` 负责新建的硬限制；改现有由模型写 SQL，保存时仍过白名单 + 干跑 |
| 预览表数与真实结构不一致 | 预览只是 `CREATE TABLE` 计数，标注「以保存时沙箱实际执行为准」；真实结构来自干跑 |
| 上传者注销后名字丢失 | 显示「已注销用户」，不回退到用户 id |
| 旧客户端/旧 e2e 被新端点影响 | 新端点独立；`/sandbox/status` 与 `upload` 的旧字段保持兼容（visibility 缺省 private） |

## 验收映射

| 需求 | 落点 |
| --- | --- |
| REQ-001 / REQ-002 | TASK-003 + TASK-006 |
| REQ-003 / REQ-004 / REQ-005 | TASK-005 |
| REQ-006 / REQ-007 | TASK-001 + TASK-005 |
| REQ-008 / REQ-009 / REQ-010 / REQ-011 | TASK-001 |
| REQ-012 / REQ-013 / REQ-014 / REQ-015 | TASK-002 + TASK-004 |
| REQ-016 | TASK-002 |
| AC-001…AC-011 | TASK-007 验证矩阵 |

## 待决定事项

- 无阻断项。UI 先做弹窗（I-01）；独立页面作为后续增量。
