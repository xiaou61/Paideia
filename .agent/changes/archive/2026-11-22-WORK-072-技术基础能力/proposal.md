---
artifact: proposal
work_id: WORK-072
work: 技术基础能力：RAG + Markdown 编辑器 + Redis 缓存
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 16:01:18 +0800
approver_role: owner
---

# 技术基础能力：RAG + Markdown 编辑器 + Redis 缓存

## 摘要

本项把三个独立的基础能力作为同一条技术底座推进：本地 embedding + RAG、Markdown 编辑器增强、Redis 只读缓存。三者共用一个目标：提升 AI 回答的资料依据、内容维护效率与重复读取性能，但不改变现有业务领域的核心行为。

## 推荐方案

### 1. 本地 RAG

- 在服务器部署 Ollama，加载 `bge-m3`，只绑定 `127.0.0.1:11434`。
- 在 `paideia-ai` 新增 `EmbeddingClient` 端口与 Ollama 实现；不把供应商类型散到业务代码。
- 在 MySQL 新增 `ai_knowledge_chunks` 表，保存条目块、内容 hash 与 float32 向量 BLOB；当前规模在 Java 侧做精确余弦检索，不引入独立向量库。
- 定时按 `knowledge_entries.updated_at` 增量重建；条目更新/下架删除旧块。
- 助手每轮回答前检索 Top-K（默认 6），注入「参考资料」；回答用 `[[entry:id]]` 引用来源。embedding 服务不可用时返回空上下文并显式降级，不阻塞主流程。

### 2. Markdown 编辑器增强

- 保留 `MarkdownField` 的 Markdown 文本框 + `MarkdownBody` 预览架构。
- 在组件内增加标题、加粗、斜体、列表、引用、代码块、链接工具条与快捷键；统一走光标插入。
- 增加可选 `onUpload` 回调，支持粘贴/拖拽图片或 PDF；不改变 Markdown 存储格式与既有渲染。
- 管理端复用同一个 `MarkdownField`，上传走既有 `admin.uploadFile`；笔记页不传上传回调时自然降级为纯 Markdown 工具条。

### 3. Redis 缓存

- 使用已在服务器运行的 Redis，新增 `spring-boot-starter-data-redis`。
- 只缓存分类树与已发布条目摘要/元数据；缓存值为 JSON 字符串，读失败/未启用时回退 MySQL。
- 分类/条目的管理端写操作主动失效对应 key；TTL 作为兜底。
- 不缓存用户私有数据、AI 回答、完整大正文；不做跨用户共享。

## 范围

本项包含上述三块；不包含独立向量数据库、rerank/多模态、WYSIWYG HTML、用户私有数据索引、缓存预热/淘汰策略调优。

## 备选方案

- **RAG 用外部 embedding API**：不选。用户要求本地部署，且外部 API 增加密钥、网络与持续成本；本地 Ollama 可在同一台服务器闭环。
- **RAG 引入独立向量库**：不选。当前条目量小，MySQL BLOB + Java 余弦足够；先验证检索价值，再决定是否升级。
- **Markdown 直接换 HTML WYSIWYG**：不选。会破坏现有 Markdown 存储、课程导入、代码围栏与 diff；工具栏增强风险低得多。
- **用 Caffeine 而不使用 Redis**：单实例下技术上更简单，但用户已明确选择 Redis，且 Redis 已在服务器运行；按 Redis 实现并严格限制缓存范围。

## 仓库影响

- `paideia-ai`：新增 RAG embedding/索引/检索组件、会话上下文字段与迁移后的 chunks 表。
- `paideia-knowledge`：新增 `KnowledgeApi.publishedContents()` 与缓存适配；管理端写操作触发失效。
- `frontend/packages/knowledge`：扩展 `MarkdownField` 工具条与上传回调。
- `paideia-app`：新增 Redis 依赖、embedding/RAG 配置与迁移。
- 文档与验证：新增 `testing/plan.md` 与最终 `testing/report.md`。

## 交付拆分

1. RAG 索引模型 + 本地 embedding 客户端
2. RAG 检索与助手上下文注入
3. Markdown 编辑器工具条与上传
4. Redis 缓存与失效
5. 测试、配置与上线验证

## 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 本地 embedding 服务占用内存/CPU | 先按 `bge-m3` 实测；内存不足改用 `bge-small-zh-v1.5`；只绑定回环 |
| 向量精确扫描随条目增长变慢 | 当前条目量小；超过阈值再迁独立向量库，先记录指标 |
| Markdown 工具栏改变既有编辑行为 | 只做纯文本插入，不改存储；组件单测覆盖插入/预览；现有 notes/admin 共用 |
| Redis 故障或缓存脏读 | 所有缓存操作捕获异常回退 MySQL；写后失效 + TTL；不缓存私有数据 |

## 验收映射

- `REQ-001..009 / AC-001..007`：本地 embedding、索引、检索、引用与降级。
- `REQ-010..012 / AC-008..010`：Markdown 工具栏、粘贴/拖拽上传、存储与渲染不变。
- `REQ-013..014 / AC-011..012`：Redis 缓存范围、写后失效与故障降级。
