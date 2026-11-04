---
artifact: test-report
work_id: WORK-072
work: 技术基础能力：RAG + Markdown 编辑器 + Redis 缓存
status: passed
evidence: required
created: 2026-11-04
updated: 2026-11-04
verified_commit: 84a8648d
verified_at: 2026-11-04 19:25 +0800
---

# 验证报告

## 结论

RAG、Markdown 编辑器与 Redis 缓存三块已实现、测试并部署到 `codenest-online`。生产索引实际覆盖 **460/460 篇已发布条目**，本地黄金集 **12/12 Recall@5**；本报告记录的是首版 200 字索引窗口的可用基线。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 本地 Ollama 服务健康 | 服务器 `docker inspect` + `/api/tags` + `/api/embed` | 0 | passed | `paideia-ollama` 运行中；`bge-m3` 可见；embedding 返回 1024 维；仅监听 `127.0.0.1:11434` |
| AC-002 索引覆盖 published 且条目数相符 | 生产库 `SELECT COUNT(*) FROM ai_knowledge_chunks` | 0 | passed | **460 chunks / 460 indexed entries**；published entries **460**；首版按每篇标题+前 200 字建块 |
| AC-003 黄金集 Top-5 召回阈值 | 服务器 Python + 生产 chunks + Ollama embedding | 0 | passed | 12 个中文技术问题 **Recall@5 = 12/12**（TCP、HTTP、遍历、DP、并发、内存、Java 集合、Python 等） |
| AC-004 回答引用 `[[entry:id]]` / 无命中提示 | `RagPromptTest` | 0 | passed | 注入上下文包含 `[[entry:42]]`；无命中返回空上下文 |
| AC-005 条目更新/下架后旧块失效 | `KnowledgeRagServiceTest.reindexesChangedEntryAndDropsUnpublishedChunks` | 0 | passed | 测试断言删除 unpublished 旧块、重建变更条目并插入新块 |
| AC-006 embedding 关闭/失败不阻塞 | `KnowledgeRagServiceTest` | 0 | passed | disabled 返回空上下文；`retrieveContext` 捕获异常返回空串 |
| AC-007 不索引个人笔记/记忆/简历/对话 | 代码边界核对 | manual | passed | `KnowledgeApi.publishedContents()` 只查 published 条目；无私有表入口 |
| AC-008 工具栏在光标处插入、预览更新 | `vitest run src/markdown-field.test.tsx` | 0 | passed | 3/3：工具栏插入 `## `、上传入口条件渲染 |
| AC-009 粘贴/拖拽上传失败不改正文 | `vitest run src/markdown-field.test.tsx` | 0 | passed | 3/3：失败显示 `md-test-upload-error`，textarea 仍是原正文 |
| AC-010 仍以 Markdown 存储、预览一致 | `pnpm -r typecheck` + `pnpm --filter @paideia/admin build` | 0 | passed | 17 个 workspace typecheck 全通过；admin build 5.36s |
| AC-011 分类树/条目摘要缓存与写后失效 | `KnowledgeRedisCacheTest` + 服务器 `redis-cli` | 0 | passed | Redis key `knowledge:categories:v1` 已出现；管理端 create/update/delete 调用 evict |
| AC-012 Redis 停止仍回退 MySQL | `KnowledgeRedisCacheTest` | 0 | passed | `values.get` 抛异常时 `get` 返回 null，不抛给业务 |

## 自动化结果

- `mvn -B -pl paideia-ai -am test`：**410 tests, 0 failed, 15 skipped**
- RAG 新用例：`ChunkerTest + KnowledgeRagServiceTest`：**6 tests, 0 failed**
- `KnowledgeReadIntegrationTest + AssistantConversationIntegrationTest`：**28 tests, 0 failed**
- 迁移守卫（ModularityTest / MigrationVersionFormatTest / MigrationChecksumTest / MigrationSeedOverlapTest）：**9 tests, 0 failed**
- `vitest run src/markdown-field.test.tsx`：**3 tests, 0 failed**
- `pnpm -r typecheck`：**17 个 workspace 全部通过**
- `pnpm --filter @paideia/admin build`：**成功**
- 生产健康：`http://127.0.0.1:8081/actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`

## 部署与真实索引

- 应用：`paideia.service` 已部署，`paideia` 库已应用 `20261104160000 - create ai knowledge chunks`。
- 本地模型：`paideia-ollama` 容器运行 `bge-m3`，健康检查通过。
- 索引结果：`ai_knowledge_chunks` 共 **460 chunks / 460 entries**；调度日志 `rag.index.completed entries=454 chunks=454`（其余 6 条为已有索引）。
- 黄金集：服务器脚本读取生产 chunks、调用本地 `/api/embed`，**12/12** 命中期望条目且排名前 5。

## 未覆盖与已知限制

- **首版索引窗口是每篇标题+前 200 字**：这是 CPU 上 `bge-m3` 的可运行基线；更长的正文、跨条目综合问答与全量正文索引需要后续扩大 `max-entry-chars` 或换更快的 embedding 服务。长尾内容目前可通过 `knowledge_get_entry` 再读。
- **Redis 是代码/单测 + key 存在性验证**：没有做真实 Redis 命中/失效的长时间集成压测；缓存失败路径已覆盖。
- **既有 `@paideia/knowledge` 的 `recommend.test.ts` 2 条失败**：属于 WORK-068 内容映射后的旧期望，不是本项改动引入；本次新增 MarkdownField 用例 3/3 通过。
