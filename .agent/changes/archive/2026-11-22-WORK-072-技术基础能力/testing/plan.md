---
artifact: test-plan
work_id: WORK-072
work: 技术基础能力：RAG + Markdown 编辑器 + Redis 缓存
status: draft
created: 2026-11-04
updated: 2026-11-04
---

# 测试计划

## 测试范围

- 本地 embedding 客户端、切块、索引与 RAG 检索/注入。
- Markdown 编辑器工具栏、光标插入与上传失败/无回调场景。
- Redis 缓存读取、写后失效与 Redis 不可用降级。

## 环境与前置条件

- 后端集成测试走 `scripts/test-db.sh`；embedding/Redis 在测试中用桩或关闭开关，不依赖真实服务。
- 前端 `pnpm --filter @paideia/knowledge test` 与 `pnpm -r typecheck`。
- 本地 Ollama/Redis 的端到端检查在部署后执行。

## 验收矩阵

| 验收 | 检查 | 结果要求 |
| --- | --- | --- |
| AC-001 | 手动/部署后 `curl http://127.0.0.1:11434/api/tags` 与 `/api/embed` | 模型可见、embedding 返回维度正确 |
| AC-002 | `KnowledgeRagIndexerTest` + 索引统计 | 只索引 published；无私有数据 |
| AC-003 | `KnowledgeRagServiceTest` 黄金集 / Top-K | 召回结果与人工期望一致 |
| AC-004 | `ConversationServiceTest` 注入 + 前端引用 | 回答上下文含 `[[entry:id]]`；无命中显式空上下文 |
| AC-005 | `KnowledgeRagIndexerTest.changedEntryReindexes` | 更新/删除后旧块消失 |
| AC-006 | embedding disabled/异常用例 | 返回空 RAG 上下文，不阻塞对话 |
| AC-007 | 索引查询与测试断言 | 不出现笔记/记忆/简历 |
| AC-008 | `markdown-field.test.tsx` 工具栏 | 光标处插入、预览更新 |
| AC-009 | `markdown-field.test.tsx` 上传 | 成功插入 Markdown，失败不改正文 |
| AC-010 | 组件预览 + 既有 MarkdownBody | 预览一致、存储仍为 Markdown |
| AC-011 | `KnowledgeCacheIntegrationTest` | 写后命中新值 |
| AC-012 | `KnowledgeRedisCacheTest` | Redis 停止时回退 MySQL，无 500 |

## 自动化检查

- `scripts/test-db.sh mvn -B -pl paideia-ai -am test`
- `scripts/test-db.sh mvn -B -pl paideia-knowledge -am test`
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=...`（缓存/会话集成）
- `cd frontend && pnpm --filter @paideia/knowledge test`
- `cd frontend && pnpm -r typecheck`
- `scripts/test-db.sh mvn -B -pl paideia-app -am -Dtest=MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest -Dsurefire.failIfNoSpecifiedTests=false test`

## 人工检查

- 部署后检查 Ollama `bge-m3` 可调用；关闭 Ollama 再问一次，确认普通回答不报错。
- 管理端 Markdown 工具栏插入、粘贴/拖拽上传。
- Redis 停止时分类/条目页面仍可打开；管理端改分类后缓存消失。

## 回归范围

- 既有助手 chat / MCP、知识库列表/详情、笔记 Markdown 编辑与课程导入。
- 现有 `MarkdownBody` 渲染与附件上传。

## 已知缺口

- embedding 质量与阈值需部署真实模型后评估；首版只做 Top-K 人工黄金集。
- Redis 缓存为单实例最小实现，不做多实例一致性/预热。
