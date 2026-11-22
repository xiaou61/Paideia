---
artifact: proposal
work_id: WORK-076
work: 知识库文档入库与检索块编辑
status: approved
created: 2026-11-06
updated: 2026-11-06
approved_by: xiaou61
approved_at: 2026-11-06 16:53:45 +0800
approver_role: CTO
---

# 提案

## 摘要

用最小改动在 Paideia 现有知识库/RAG 之上补两块能力：**① 管理员上传 PDF → 解析 → 模型蒸馏成知识条目草稿（进现有 `knowledge_entries`，审核后发布并自动进 RAG）**；**② 管理员对检索切片（`ai_knowledge_chunks`）做单块覆盖编辑 + 版本回滚，且编辑不被增量索引覆盖**。目录树与图谱复用现有实现，只补上传保留目录 + 只读目录阅读视图。不引入 WeKnora 或任何 Go 服务、独立向量库、rerank。

## 推荐方案

**核心机制**：复用已有底座，只在缺口处增补。

1. **条目写入复用现有「自动采集」通道**：`KnowledgeApi.createCollected(CollectedEntry)` 已经具备“按指纹去重 + 由调用方定状态 + 记录来源 URL”三项能力（WORK-025 为八股文抓取所做）。文档蒸馏产出的草稿正需要这三点，因此**不新增写条目路径**，只把蒸馏结果转成 `CollectedEntry`（status=draft，fingerprint=原文规范化哈希，sourceUrl=附件地址）。
2. **文档解析放 ai 模块、纯本地库**：PDF 文本抽取用 Apache PDFBox（纯 Java、无外部服务、无需沙箱——只解析数据不执行代码），抽取与蒸馏统一在 `paideia-ai`（它已依赖 `paideia-knowledge`、有模型接入/计费/观测）。知识模块只额外暴露“按 id 读附件字节”“按路径确保分类”两个模块内端口。
3. **导入做成异步任务**：新增 `ai_knowledge_imports` 任务表 + 定时 relay，沿用现有 `MemoryExtractionRelay`/`EssayCollectionJob` 的“表 + 轮询 worker”模式（项目尚未引入 RabbitMQ）。避免多文件/文件夹上传 + 模型调用把请求卡住。
4. **块编辑用独立“版本化编辑表”覆盖层**：自动切分的块仍由正文（`ai_knowledge_chunks`）派生、可被重建；手工编辑写入新表 `ai_knowledge_chunk_edits`（按 `entry_id + chunk_index + version`）。检索时**用当前编辑版本覆盖同位置自动块**；编辑表不被索引重建触碰，因此天然解决“被覆盖”。版本回滚 = 追加一个新版本（内容取自历史版本），保持只追加。

**受影响的归属范围**：`paideia-knowledge`（模块端口扩展）、`paideia-ai`（导入 + 块编辑，新增表/任务/接口）、`paideia-app`（迁移）、管理端前端（导入页 + 块编辑面板）、学习者端（目录阅读视图）。

**为什么最满足需求**：全部能力建在已验证的现有模式上（自动采集去重、定时 relay、RAG 合并检索），改动面可控、无新中间件、无技术栈引入；数据仍只落 `knowledge_entries`，满足 Q3；块编辑满足 Q4 的“单块覆盖 + 回滚”，并顺带满足 REQ-203 的“不被覆盖”。

## 范围

- 含：PDF 文本解析、蒸馏成条目草稿、指纹去重、来源记录、目录树保留、异步导入任务与重试、单块编辑/版本/回滚/冲突提示、管理端两个新界面、学习者侧目录阅读视图。
- 不含：Word/PPT/Excel、图片 OCR、学习者私有上传、独立向量库/rerank/GraphRAG、条目级版本历史、块的拆分合并重排、引入 WeKnora。

## 备选方案

- **备选 A：直接部署 WeKnora，通过其 MCP/API 接入 Paideia。**
  - 最强理由：文档解析广度、Wiki、块编辑、rerank、GraphRAG 全都现成，几乎零开发。
  - 未选原因：技术栈（Go+Vue/Docker/K8s）与 Paideia 模块化单体不兼容，等于并跑第二套知识库+助手；数据双写、权限模型（多租户 RBAC）与 Paideia 不对齐；上线与运维成本远大于收益。放弃的是“开箱即得的广度”，换来完整可控与一致的技术栈。
- **备选 B：新建独立「文档 Wiki 页」表，与 `knowledge_entries` 并行。**
  - 最强理由：不污染现有条目模型，wiki 页自成体系。
  - 未选原因：用户已定 Q3=进现有条目；另建一套要把 markdown 渲染、全文检索、自评复习、RAG 各写一遍，成本最高。
- **备选 C：导入同步执行，不建任务表。**
  - 最强理由：少一张表、少一个 relay，实现最简。
  - 未选原因：多文件/文件夹 + 模型蒸馏耗时可达分钟级，同步会把请求卡死；失败也无从重试。放弃的是实现简易，换来可用性与可重试。
- **备选 D：块编辑直接改 `ai_knowledge_chunks` 内容并加 `locked` 标记。**
  - 最强理由：单表、改动更少。
  - 未选原因：现有索引对整条目 `deleteByEntry + insert`，加标记后重建逻辑要在“保留手工块”和“重排自动块”之间做复杂的索引对齐；且无版本历史（Q4 要求回滚）。独立编辑表把“自动派生”和“人工覆盖”彻底分层，更稳、可回滚。

## 仓库影响

- `paideia-knowledge`：`KnowledgeApi` 增 `findAttachment`、`ensureCategoryPath`；`KnowledgeApiImplementation` 实现。不新增表。
- `paideia-ai`：新增 `DocumentDistiller` 端口 + AgentScope 实现；新增 `internal.importing`（PDF 抽取、导入服务/任务/relay/controller）与 `internal.rag` 的块编辑服务 + controller；`KnowledgeRagService.retrieveContext` 合并编辑覆盖；新增两张表 mapper。
- `paideia-app`：两条迁移（导入任务表、块编辑表）；结构图 `docs/architecture/05-database-tables.md` 重生成。
- 前端：`@paideia/knowledge` admin-client 扩展；`apps/admin` 两个新页/面板 + 侧栏入口；`apps/app` 目录阅读视图。
- 依赖：`paideia-ai/pom.xml` 加 PDFBox（版本实施时确认）。

## 交付拆分

1. **M1 文档入库**：PDF 抽取 → 蒸馏成条目草稿 → 指纹去重/来源 → 异步任务与重试 → 管理端导入页（含文件夹保留目录）。
2. **M2 块编辑**：块编辑表 + 服务（只重嵌该块/版本/回滚/冲突）→ 检索合并覆盖 → 管理端块编辑面板。
3. **M3 阅读视图**：学习者侧按目录树的只读连续阅读视图（复用 MarkdownBody）。
4. **M4 收尾**：迁移校验、结构图重生成、切片 + 受影响 e2e、验收报告。

## 风险与缓解措施

- **PDF 抽取质量**（扫描件无文本、复杂版式）：TASK 探针先测；无文本 → 明确失败、不生成空条目；不做 OCR（已列非目标）。
- **蒸馏产出不可控**（模型返回结构不合规）：结构化输出 + 校验，失败标 failed、可重试，不落半截条目。
- **模型成本/延迟**：走现有计费与限流；导入异步化；单文件可设大小上限。
- **编辑覆盖语义**：独立编辑表 + `base_content_hash` 冲突检测；正文变更导致错位时提示而非静默。
- **embedding 不可用**：保存编辑时可退回“只存内容、标记待重嵌”，不阻塞正文、不 500（与 WORK-072 降级一致）。
- **模块边界**：跨模块只走 `KnowledgeApi`；`spring-modulith` 构建期守卫。
- **迁移撞号**：14 位时间戳文件名 + 合并前核对。

## 验收映射

| REQ | 主要任务/里程碑 | AC |
| --- | --- | --- |
| REQ-101 文档解析 | M1 / TASK-003、005 | AC-101 |
| REQ-102 蒸馏成草稿 | M1 / TASK-004、005 | AC-101、102 |
| REQ-103 来源溯源 | M1 / TASK-005 | AC-101 |
| REQ-104 目录树保留 | M1 / TASK-002、007、012 | AC-104 |
| REQ-105 幂等去重 | M1 / TASK-005 | AC-103 |
| REQ-106 失败可见 | M1 / TASK-006、012 | AC-105 |
| REQ-107 用量记账 | M1 / TASK-005 | （间接）AC-102 |
| REQ-201 块查看 | M2 / TASK-008、010、013 | AC-201 |
| REQ-202 块编辑只重嵌本块 | M2 / TASK-009 | AC-201 |
| REQ-203 不被覆盖/冲突提示 | M2 / TASK-009 | AC-202 |
| REQ-204 版本与回滚 | M2 / TASK-009、013 | AC-203 |
| REQ-205 回滚即重索引 | M2 / TASK-009 | AC-203 |
| REQ-301 复用分类树 | M1/M3 / TASK-002、014 | AC-301 |
| REQ-302 Wiki 阅读页 | M3 / TASK-014 | AC-301 |
| REQ-303 图谱复用 | M3 / —（不改） | AC-302 |
| QR-002 降级 | M2 / TASK-009 | AC-204 |
| QR-001 权限 | M1/M2 / TASK-007、010 | AC-204、安全测试 |

## 待决定事项

- PDFBox 具体版本与许可证确认（实施前，探针结论记录）。
- 蒸馏“单文档 → 单条目”还是“单文档 → 多条目”的切分策略（首版建议单文档单条目，长文按标题分段时再评估）。
- 导入单次文件大小上限与页数上限的取值。
