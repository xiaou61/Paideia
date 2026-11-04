---
artifact: tasks
work_id: WORK-072
work: 技术基础能力：RAG + Markdown 编辑器 + Redis 缓存
status: approved
created: 2026-11-04
updated: 2026-11-04
approved_by: xiaou61
approved_at: 2026-11-04 16:01:18 +0800
approver_role: owner
---

# 实施任务

## 依据

- `requirements.md`（REQ-001..REQ-014 / AC-001..AC-012）
- `proposal.md`、`design.md`

## 全局约束

- Markdown 仍是正文唯一存储格式；RAG 只索引已发布条目；缓存只存公共摘要/分类。
- 所有碰库测试走 `scripts/test-db.sh`；迁移文件名 14 位时间戳。
- embedding/Redis 失败必须降级，不能阻断主流程。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 长条目切块丢内容或重叠失控 | TASK-002 | `ChunkerTest.longBodyKeepsAllText` |
| embedding 服务关闭时聊天被阻塞 | TASK-003 | `KnowledgeRagServiceTest.disabledReturnsEmpty` |
| 条目更新/下架后旧向量仍被召回 | TASK-002 | `KnowledgeRagServiceTest.changedEntryReindexes` |
| Markdown 工具栏在光标处插入错误 | TASK-004 | `MarkdownField.test.tsx.insertsAtCursor` |
| Markdown 上传失败破坏正文 | TASK-004 | `MarkdownField.test.tsx.uploadFailureKeepsBody` |
| Redis 停止后知识库页面 500 | TASK-005 | `KnowledgeRedisCacheTest.redisFailureFallsBack` |
| 写分类/条目后缓存未失效 | TASK-005 | `KnowledgeCacheIntegrationTest.writeInvalidates` |

## 任务

### TASK-001 | done | RAG 迁移、embedding 端口与依赖

- 对应：`REQ-001`、`AC-001`
- 依赖：无
- 新建/修改：
  - `backend/paideia-app/src/main/resources/db/migration/V20261104160000__create_ai_knowledge_chunks.sql`
  - `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/rag/EmbeddingProperties.java`
  - `EmbeddingClient.java`、`OllamaEmbeddingClient.java`
- 接口：
  ```java
  interface EmbeddingClient { boolean enabled(); List<float[]> embed(List<String> texts); }
  ```
- 步骤：建表；接口与 HTTP 实现；配置 `paideia.ai.embedding`；失败记录日志。
- 测试：`OllamaEmbeddingClientTest`（解析响应，不要求真实 Ollama）。
- 验证：`mvn -B -pl paideia-ai -am -DskipTests test-compile`。

### TASK-002 | done | 索引与切块

- 对应：`REQ-002..004、REQ-007`、`AC-002、AC-005`
- 依赖：TASK-001
- 新建：`KnowledgeChunk.java`、`KnowledgeChunkMapper.java`、`KnowledgeRagIndexer.java`、`Chunker.java`
- 修改：`KnowledgeApi.java`、`KnowledgeApiImplementation.java`、`EntryMapper.java`
- 接口：
  ```java
  List<EntryContent> KnowledgeApi.publishedContents();
  void KnowledgeRagIndexer.reindexChanged();
  ```
- 测试：`ChunkerTest`、`KnowledgeRagIndexerTest`。
- 验证：窄测试通过，索引 hash 变更能替换旧块。

### TASK-003 | done | RAG 检索与聊天注入

- 对应：`REQ-005..009`、`AC-003、AC-004、AC-006`
- 依赖：TASK-001、TASK-002
- 新建：`KnowledgeRagService.java`、`RagHit.java`
- 修改：`ConversationService.java`、`AssistantConversation.java`、`ConversationPrompt.java`、`AgentScopeConversation.java`、`AssistantConversationController.java`
- 测试：`KnowledgeRagServiceTest`、`ConversationServiceTest`（RAG 空/有上下文两条）。
- 验证：`mvn -B -pl paideia-ai -am test`（受影响单测）。

### TASK-004 | done | Markdown 编辑器工具条与上传

- 对应：`REQ-010..012`、`AC-008..010`
- 依赖：无
- 修改：`frontend/packages/knowledge/src/markdown-field.tsx`、`frontend/apps/admin/src/pages/KnowledgeEntriesPage.tsx`
- 接口：
  ```ts
  onUpload?: (file: File) => Promise<{ url: string; name: string }>
  ```
- 步骤：工具条按钮 -> 在 textarea selection 插入 Markdown；粘贴/拖拽调用 onUpload；失败提示；无 onUpload 时禁用上传入口。
- 测试：`frontend/packages/knowledge/src/markdown-field.test.tsx`（新增）。
- 验证：`pnpm -r typecheck`；`pnpm --filter @paideia/knowledge test`。

### TASK-005 | done | Redis 缓存与失效

- 对应：`REQ-013..014`、`AC-011..012`
- 依赖：无
- 修改：
  - `backend/paideia-knowledge/pom.xml`（`spring-boot-starter-data-redis`）
  - 新建 `internal/cache/KnowledgeRedisCache.java`
  - `KnowledgeApiImplementation.java`、`CategoryAdminService.java`、`EntryAdminService.java`
  - `backend/paideia-app/src/main/resources/application.yml`、`application-test.yml`
- 接口：
  ```java
  <T> T get(String key, TypeReference<T> type);
  void put(String key, Object value, Duration ttl);
  void evictCategories();
  void evictEntryBriefs();
  ```
- 测试：`KnowledgeRedisCacheTest`（不可用时回退）；`KnowledgeCacheIntegrationTest`（写后失效）。
- 验证：`pnpm` 不涉及；后端窄测试 + `paideia-app` 集成测试。

### TASK-006 | done | 部署配置与本地服务

- 对应：`REQ-001`、`AC-001`
- 依赖：TASK-001
- 修改：`backend/paideia-app/src/main/resources/application.yml`（embedding 默认地址/模型、RAG 开关）、`application-test.yml`（关闭）
- 文档/运维：在部署说明中记录 Docker Ollama 启动与模型拉取命令，不写密钥。
- 验证：服务器 `docker` 部署后 `curl 127.0.0.1:11434/api/tags` 与 `/api/embed` 健康。

### TASK-007 | done | 收口与验证报告

- 对应：全部 AC
- 依赖：TASK-001..006
- 修改：`testing/plan.md`、`testing/report.md`、`.agent/history/updates.md`
- 验证：结构化证据表覆盖 AC-001..AC-012；记录 embedding/Redis 不可用降级、前端 typecheck/build 结果；绑定实际提交。

## 完成条件

- 三条能力都有可观察的入口与失败降级；RAG 不索引私有数据；缓存不缓存私有数据。
- 所有测试输出写入验证报告；未执行的项明确写“未运行”，不写成通过。
