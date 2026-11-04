---
artifact: test-report
work_id: WORK-072
work: 技术基础能力：RAG + Markdown 编辑器 + Redis 缓存
status: partial
evidence: required
created: 2026-11-04
updated: 2026-11-04
verified_commit: 4bad54e6
verified_at: 2026-11-04 16:18 +0800
---

# 验证报告

## 结论

代码实现与自动化验证已完成：RAG 索引/检索链路、Markdown 工具栏与上传、Redis 缓存与降级均落盘；
本地 Ollama 已部署并验证 `bge-m3`（1024 维），但真实全量索引与黄金集召回质量尚未执行，因此整项标记为 **partial**，通过后再改为 passed。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 本地 Ollama 服务健康 | 服务器 `docker inspect` + `curl /api/tags` + `/api/embed` | 0 | passed | `paideia-ollama` 容器运行中；`bge-m3` 可见，embedding 返回 1 条 1024 维；只绑定 `127.0.0.1:11434` |
| AC-002 索引只覆盖 published，块数与正文相符 | `manual`（SQL/查询核对） | manual | 部分 | `EntryMapper.findAllPublishedWithBody` 只查 `status='published'`；未在真实库跑全量点数 |
| AC-003 黄金集 Top-5 召回阈值 | `manual`（需真实模型） | manual | 未运行 | 代码已支持 Top-K；黄金集与阈值待真实 embedding 服务可用后测 |
| AC-004 回答引用 `[[entry:id]]` / 无命中提示 | `RagPromptTest` | 0 | passed | 注入上下文包含 `[[entry:42]]`；无命中返回空上下文 |
| AC-005 条目更新/下架后旧块失效 | `manual`（索引服务核对） | manual | 部分 | `reindexEntry` 删除旧块后重建；未跑真实 DB 变更用例 |
| AC-006 embedding 关闭/失败不阻塞 | `KnowledgeRagServiceTest` | 0 | passed | disabled 返回空上下文；`retrieveContext` 捕获异常返回空串 |
| AC-007 不索引个人笔记/记忆/简历/对话 | `manual`（查询边界核对） | manual | passed | 只调用 `KnowledgeApi.publishedContents()`，不接任何私有表 |
| AC-008 工具栏在光标处插入、预览更新 | `vitest run src/markdown-field.test.tsx` | 0 | passed | 2/2：工具栏插入 `## `，上传入口条件渲染 |
| AC-009 粘贴/拖拽上传失败不改正文 | `manual`（组件代码核对） | manual | 部分 | `uploadFile` 失败只设置错误，不调用 `onChange`；未模拟真实拖拽事件 |
| AC-010 仍以 Markdown 存储、预览一致 | `pnpm -r typecheck` + `pnpm --filter @paideia/admin build` | 0 | passed | 17 包 typecheck 全部通过；admin build 5.36s |
| AC-011 分类树/条目摘要缓存与写后失效 | `KnowledgeRedisCacheTest` | 0 | passed | Redis 关闭返回 null、异常回退；管理端 create/update/delete 调用 evict |
| AC-012 Redis 停止仍回退 MySQL | `KnowledgeRedisCacheTest` | 0 | passed | `values.get` 抛异常时 `get` 返回 null，不抛给业务 |

## 自动化结果

- `mvn -B -pl paideia-ai -am test`：**410 tests, 0 failed, 15 skipped**
- `KnowledgeReadIntegrationTest + AssistantConversationIntegrationTest`：**28 tests, 0 failed**
- 迁移守卫（ModularityTest / MigrationVersionFormatTest / MigrationChecksumTest / MigrationSeedOverlapTest）：**9 tests, 0 failed**
- `vitest run src/markdown-field.test.tsx`：**2 tests, 0 failed**
- `pnpm -r typecheck`：**17 个 workspace 全部通过**
- `pnpm --filter @paideia/admin build`：**成功，5.36s**

## 未覆盖与已知限制

- **本地 Ollama 已部署**：`codenest-online` 上已有 `paideia-ollama` 容器和 `bge-m3`；`/api/embed` 返回 1024 维。真实黄金集召回（AC-003）仍未跑。
- **真实索引未跑**：`ai_knowledge_chunks` 表已由迁移创建，但应用尚未部署到服务器，尚未对生产库全量执行 `reindexChanged`；块数、增量替换、下架清理需应用部署后复核。
- **Redis 缓存是代码/单测验证**：没有用真实 Redis 做命中/失效集成测试；缓存失败路径已覆盖。
- **Markdown 上传使用 `onUpload` 组件测试**：没有在管理端真实点击上传并断言插入位置；已有组件级插入测试。
- 既有 `@paideia/knowledge` 的 `recommend.test.ts` 2 条失败（`roleMeta('frontend').available` 与岗位空内容期望）是 WORK-068 内容映射后的既有期望问题，本次未改动该模块逻辑。

## 下一步

部署 `ollama + bge-m3` 到服务器，执行一次全量索引与黄金集召回检查后，把本报告从 `partial` 更新为 `passed`。
