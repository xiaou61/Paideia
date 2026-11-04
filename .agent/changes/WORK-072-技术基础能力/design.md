---
artifact: design
work_id: WORK-072
work: 技术基础能力：RAG + Markdown 编辑器 + Redis 缓存
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 16:01:18 +0800
approver_role: owner
---

# 设计

## 系统上下文

本项横跨 `paideia-ai`、`paideia-knowledge`、`paideia-app` 与 `frontend/packages/knowledge`。RAG 的 embedding 服务是服务器本地 Ollama 进程；缓存是服务器已有 Redis；Markdown 编辑在共用包内完成，不改变正文的 Markdown 存储。

## 组件与职责

| 组件 | 职责 |
| --- | --- |
| `EmbeddingProperties` / `EmbeddingClient` / `OllamaEmbeddingClient` | embedding 配置、端口与本地 Ollama HTTP 实现 |
| `KnowledgeChunk` / `KnowledgeChunkMapper` | `ai_knowledge_chunks` 表读写 |
| `KnowledgeRagService` | 条目切块、增量索引、查询向量与 Top-K 检索、格式化上下文 |
| `KnowledgeApi.publishedContents()` | 向 AI 模块提供全部已发布条目正文 |
| `ConversationService` / `TurnPreparation` | 在 `prepare` 阶段取 RAG 上下文并带入本轮 |
| `ConversationPrompt` / `AgentScopeConversation` | 把 RAG 上下文放进系统提示词 |
| `MarkdownField` | 工具栏、快捷插入、粘贴/拖拽上传和预览；仍输出 Markdown |
| `KnowledgeRedisCache` | 分类树与条目摘要的 JSON Redis 缓存、失效与降级 |
| `CategoryAdminService` / `EntryAdminService` | 写后失效对应缓存 |

## 接口与数据

### Embedding

```java
interface EmbeddingClient {
    boolean enabled();
    List<float[]> embed(List<String> texts);
}
```

配置前缀 `paideia.ai.embedding`：`enabled`、`base-url`（默认 `http://127.0.0.1:11434`）、`model`（默认 `bge-m3`）、`timeout-seconds`。

### RAG 表

`ai_knowledge_chunks`：`id`、`entry_id`、`chunk_index`、`title`、`content`、`content_hash`、`embedding(LONGBLOB)`、`dimension`、`updated_at`，唯一键 `(entry_id, chunk_index)`。

`KnowledgeRagService.retrieveContext(question, topK=6)` 返回格式化系统侧文本：
- 每块标题带 `参考条目 [[entry:<id>]]`
- 无命中或服务不可用时返回空串；调用方不把它当“没有资料”强行生成引用

### 会话上下文

`ConversationService.TurnPreparation` 增加 `String knowledgeContext`；`AssistantConversation.Request` 增加 `String knowledgeContext`。`AgentScopeConversation.systemPrompt` 在页面场景之后、学情/记忆之前拼接 RAG 上下文。

### Markdown 编辑器

`MarkdownField` 新增可选 `onUpload?: (file: File) => Promise<{ url: string; name: string }>`；工具栏命令只对 textarea 当前 selection 做文本插入。管理端 `KnowledgeEntriesPage` 传入 `admin.uploadFile` 的异步封装；笔记页不传则不显示上传能力。

### Redis 缓存

`KnowledgeRedisCache` 用 `StringRedisTemplate` 存 JSON：
- `knowledge:categories:v1`（TTL 10 分钟）
- `knowledge:entry-briefs:v1:<hash>`（TTL 5 分钟）
读取异常/未启用回 MySQL；`CategoryAdminService` 与 `EntryAdminService` 写操作清对应 key。

## 持久化与迁移

新增 `V20261104160000__create_ai_knowledge_chunks.sql`。只建表，不写业务数据；`content_hash` 用来判断条目是否需要重建。

## 失败处理与恢复

- embedding 服务关闭/超时：`KnowledgeRagService` 捕获后返回空上下文；AI 回答继续，不阻塞，且日志记录 `rag.unavailable`。
- Redis 不可用：缓存读取/写入捕获异常并回 DB；不把缓存错误返回给用户。
- 索引重建失败：记录错误并保留旧块，下一轮定时任务重试；不删除旧块直到新块写入成功。

## 安全与权限

RAG 只读 `published` 条目；不索引笔记/记忆/简历/对话。引用使用已有 `[[entry:id]]`，未知 id 由前端渲染为不可点文本。缓存只存公共内容，不存令牌或私有数据。

## 可观测性

- `ai_knowledge_chunks` 的条目/块数量可作为健康指标。
- RAG 检索记录命中数量与耗时（现有 `ai_runs` 或日志均可）。
- Redis 缓存命中/回退在 debug 日志中记录，不把异常抛给调用方。

## 实施顺序

1. 迁移 + `EmbeddingClient` + chunk mapper + 索引服务
2. `KnowledgeApi.publishedContents()` + 检索注入聊天
3. MarkdownField 工具栏与上传
4. Redis 缓存 + 管理端失效
5. 测试与验证

## 测试策略

- `ChunkerTest`：段落/超长/空正文；块不重叠失控。
- `KnowledgeRagServiceTest`：假 EmbeddingClient 验证索引、余弦排序、空上下文降级。
- `ConversationServiceTest`：RAG 上下文进入请求且在 embedding 不可用时为空。
- `MarkdownField` 组件测试：工具栏插入、预览更新、无 onUpload 降级。
- `KnowledgeRedisCacheTest`：Redis 不可用回退、写后失效（用假客户端或集成测试）。
- 后端切片：`paideia-ai`、`paideia-knowledge` 受影响单测；迁移三守卫；前端 `pnpm -r typecheck` 与 admin build。

## 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001..009 / AC-001..007 | Ollama/embedding、chunks、RAG 服务、会话注入、引用与降级 |
| REQ-010..012 / AC-008..010 | MarkdownField 工具栏与上传 |
| REQ-013..014 / AC-011..012 | Redis 缓存、失效、回退 |

## 待决定事项

- 黄金集问题清单与具体阈值在实现时补。
- `bge-m3` 若内存/延迟不达标，按配置切换到 `bge-small-zh-v1.5`。
