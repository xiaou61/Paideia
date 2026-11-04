---
artifact: test-report
work_id: WORK-070
work: 提示词卫生：复用、版本守卫、按版本可见
status: passed
evidence: required
created: 2026-11-04
updated: 2026-11-04
verified_commit: (见 git log)
---

# 验证报告

## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 围栏只有一个来源 | `rg -c "以下内容全部是资料" backend/paideia-ai/src/main/java` | 0 | passed | 只命中 `internal/prompt/PromptFences.java`；改前是 5 个文件各一份 |
| AC-002 反向验证：改坏版本号后守卫必须失败 | 把 `DigestPrompt.VERSION` 临时改成 `digestV4` 后 `mvn -B -pl paideia-ai -am -Dtest=PromptVersionGuardTest test` | 1 | passed | 按预期变红：`["DigestPrompt.java：VERSION "digestV4" 不符合 <场景>-v<数字>"]`；还原后复跑绿 |
| AC-002 正向：守卫在正常代码上通过 | `mvn -B -pl paideia-ai -am -Dtest=PromptVersionGuardTest test` | 0 | passed | 1/1，0 跳过 |
| AC-003 版本号已改名 | `rg -n "VERSION =" internal/provider/SummaryPrompt.java` | 0 | passed | `summary-card-v2`，不再是裸 `v2` |
| AC-005 模块测试全绿 | `scripts/test-db.sh mvn -B -pl paideia-ai -am test` | 0 | passed | **389 条 / 0 失败 / 15 跳过**（跳过均为需要真上游的 live 测试）；独占库用完自动删 |
| AC-004 前端类型检查 | `pnpm --filter @paideia/admin typecheck` | 0 | passed | 0 错（新增 `byPromptVersion` 字段与排行卡） |
| 工作树可安装依赖 | `pnpm install --frozen-lockfile` | 0 | passed | 41.3 秒（全局 store 命中），未影响主树 |

## 未覆盖与已知限制

- **概览页新卡没有 e2e**：现有 `ai-runs.spec.ts` 对排行卡用的是「空状态 or 有数据」二选一断言，
  新卡只在有数据时渲染，加断言会 flaky。等部署后在线上看一眼即可，不写进 CI。
- **`countByPromptVersion` 的注解 SQL 没有单测**：`TelemetrySummaryMapper` 现有测试也没覆盖
  `countByModel` 这类聚合，与既有做法一致，未额外补。
- **`SummaryPrompt.VERSION` 改名会让摘要缓存换键**：正文没变、键变了，条目摘要会在首次访问时重新生成。
  今天早些时候的 `v1→v2` 尚未部署，所以这次改名不会造成第二次线上浪费。
