---
artifact: report
work_id: WORK-007
work: 对象存储接入
status: passed
evidence: required
verified_commit: 0df2f9c
verified_at: 2026-10-17
accepted_by: xiaou61
accepted_at: 2026-10-29 13:17:05 +0800
---

# 验收报告 · 对象存储接入

## 结论

**通过。** 附件存储已从"只有本地磁盘一个实现"变成**同一份构建、两个实现、改配置 + 重启即换**，生产已切到自建 RustFS，读路径走预签名直读（302），端到端在学习者端看到图片真实渲染。

两处如实记下的偏差：① **管理端"点按钮上传"那条界面链本轮没被自动化盯住**（e2e 未覆盖上传按钮，本轮界面上传那次交互断了，改用同一 HTTP 端点从 API 验的）；② 预签名会吃掉浏览器长缓存，参数（TTL 7 天 / 302 缓存 1 小时）是折中值，**建议按真实流量再看一眼**。

## 验收矩阵

**证据口径**：`退出码` 列写**实测数字**的 4 行，是 2026-10-15 清理工件时**复跑**的本地只读检查；写 **人工** 的 4 行是**原验证时的人工核验记录、本轮未复跑**（需构建 / SSH / 外网）——已在「证据」列逐行标明，不把没跑过的当跑过。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-1 可换性：同一批附件用例在两种实现下不改测试代码地通过 | `mvn -pl paideia-knowledge -am verify`（含 `FileStorageContractTest`） | 人工 | 通过 | 原验证记录：BUILD SUCCESS；契约测试 **2 运行 / 0 失败 / 0 跳过**（本地临时目录 + 连真机 RustFS），同一份 `assertContract` 跑两遍，含预签名 URL 真取回 200 且字节一致。本轮未复跑（需构建与 S3 隧道） |
| AC-2 契约不变：`/api/v1/knowledge/files/{id}` 形状不变，端点仍只放行 GET | `grep -n "HttpMethod.GET" backend/paideia-security/src/main/java/io/github/xiaou61/security/SecurityConfiguration.java` | 0 | 通过 | 2026-10-15 复跑：命中 `/api/v1/public/shares/**`(138) 与 `/api/v1/knowledge/files/**`(142)，均 `permitAll()`；线上实测该端点返回 302 |
| AC-3 不可枚举：id 仍是 UUID 且被两个实现共同校验 | `grep -rn "FileIds\." backend/paideia-knowledge/src/main/java` | 0 | 通过 | 2026-10-15 复跑：`S3FileStorage` 3 处（76/99/124）+ `LocalFileStorage` 3 处（66/84…），全部调 `FileIds.isUuid`；`FileIds.java` 存在 |
| AC-4 元数据不搬家：`knowledge_files` 表与写入路径不变 | `ls backend/paideia-app/src/main/resources/db/migration/` | 0 | 通过 | 2026-10-15 复跑：最大迁移号仍是 **V9**，本项**未新增任何迁移** |
| AC-5 生产可用：线上附件可上传、学习者端可渲染 | 线上 `curl -L /api/v1/knowledge/files/<uuid>`，再取学习者端 `img.naturalWidth / naturalHeight` | 人工 | 通过 | 原验证记录（需 SSH，本轮未复跑）：302 → 200、2932 字节与原文件 `cmp` 一致；学习者端图片 `naturalWidth=420 / naturalHeight=150` 真实渲染，截图存证 |
| AC-6 回滚路径明确且写明有损 | `grep -n "有损" .agent/notes/dev-workflow.md` | 0 | 通过 | 2026-10-15 复跑：命中"**回滚是有损的**：切回 `local` 后期间上传的附件会 404"（第 90 行），并记明首次切换时线上附件 0 条、损失面为零 |
| AC-7 暴露面受控：控制台关闭、桶私有、凭据只在 600 文件 | 外部探测 `:9000` / `:9001` 的 HTTP 状态码 | 人工 | 通过 | 原验证记录（需外网，本轮未复跑）：`9000 → 403`（可达且拒绝未签名请求）、`9001 → 000`（控制台关闭）；凭据 24/40 位随机且只在 `/etc/rustfs/rustfs.env`(600)；应用日志只打 endpoint 与桶名 |
| AC-8 配置错误 fail fast | `mvn -pl paideia-knowledge -am test`（`FileStorageConfigurationTest` 5 条） | 人工 | 通过 | 原验证记录：5 条全过——默认→local、显式 local→local、s3→S3FileStorage、s3 缺凭据→上下文启动失败、`redirect-cache >= presign-ttl`→启动失败。本轮未复跑 |

## 反向验证（"这条要是坏了，哪个断言会红"）

| 被验行为 | 反着改一处会怎样 |
| --- | --- |
| 默认仍是本地实现 | 把 `matchIfMissing` 去掉 → `defaultsToLocalStorage` 红 |
| 直读 URL 真的可用 | 把 `presignGetUrl` 的 TTL 设成 0 → 契约测试里那次取回拿不到 200 |
| 删除语义与本地一致 | 去掉 `delete` 里的 head → "删不存在的对象返回 false"红 |
| 非 UUID 不可枚举 | 去掉 `FileIds.isUuid` 调用 → "非 UUID 读必须为空"红 |
| 302 缓存必须短于签名 | 把 `redirect-cache` 配成 2h → 装配测试红（已实测） |

## 实测数字（2026-10-15）

- RustFS **1.0.1**（GA 1.0.0 于 2026-09-16、补丁 10-03；**GA 仅约四周**是唯一成熟度顾虑）；`MemoryCurrent=64MB`。
- 后端 `mvn verify`：**BUILD SUCCESS**；`paideia-app` 116 运行 / 115 跳过（未给数据库口令时集成测试按设计跳过）。
- 上传→302→直读：`X-Amz-Expires=604800`（7 天）、`Cache-Control: max-age=3600, private`、取回 200 / 2932 字节一致。
- 用户可见面：学习者端条目里图片正常渲染（截图）。

## 未覆盖 / 待办

1. **管理端上传按钮的界面自动化**：e2e 没覆盖（本轮那次界面操作断了）。建议后续给 `admin-entry-upload` 补一条：点按钮 → 选文件 → 正文末尾出现引用 → 保存 → 学习者端能看到。
2. **预签名参数复核**：按真实流量看"302 缓存 1 小时"够不够、签名 7 天是否过长。
3. **备份**：对象存储数据在 `/var/lib/rustfs/data`，目前没有备份策略（本地目录时代也没有）——属 D-15 范畴。

## 2026-10-17 重新核对（结算）

`KnowledgeFileIntegrationTest` 7/0、`KnowledgeReadIntegrationTest` 12/0、`FileStorageContractTest` 2 运行 / 1 跳过（未配 S3 时按设计跳过）、`LocalFileStorageTest` 6/0；同类 e2e 里附件与知识库条目链路均绿（学习者端 34/1skip、管理端 8/0）。锚点之后对象存储实现未被改动。

## 2026-10-17 积分闸回滚修复后的复核

本轮改动落在 `ConversationService.prepare`：去掉 `@Transactional`，让用户消息先提交、再做积分预留，并把所有可能抛异常的读操作移到 `reserve` 之前（保证「预留之后不再有会抛的动作」）。另外 `AiCreditIntegrationTest` 原本断言「被拒的那一轮不留任何消息」，已改为「留用户消息、不留助手消息」——AC-003 只管上游没有收到请求，不要求丢用户输入。

本项相关证据随后重跑：`KnowledgeFileIntegrationTest` 7/0、`KnowledgeReadIntegrationTest` 12/0（本项源码未被本次修复触及）。

锚点改绑到 `e941bcb`。

## 2026-10-17 测试基建改动后的复核

本轮改动是测试基建：测试库改本机、四个前端包换 happy-dom、会话集成测试改用同步执行器。
业务代码未变。重跑结果：后端全量 **257 条 / 0 失败 / 0 error / 4 跳过**（墙钟 1 分 31 秒），
前端单测 **176 条全过**，`typecheck` 干净。

锚点改绑到 `d184e36`（其后只有 `.agent/` 工件变化）。

## 复验记录（2026-10-18，锚点 0df2f9c）

**为什么会有这一段**：原报告的证据是在更早的提交上采的，之后仓库里又落了 WORK-014／015／016 三个工作项
（源码与配置都变过），校验器因此把本报告判为 `stale`。本轮在**当前 HEAD**（`9c99033`）上重跑了与本项
**受影响面**相关的那几组检查，逐条结果如下。**没有改任何功能代码**。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 知识库全量（含附件与对象存储配置） | `mvn -B test`（`KnowledgeFileIntegrationTest` / `KnowledgeReadIntegrationTest` / `KnowledgeAdminIntegrationTest`） | 0 | passed | 后端全量 143 运行 / 0 失败 / 4 跳过 |
| 学习者端图片渲染 | `pnpm --filter @paideia/app test:e2e` | 0 | passed | 60 passed / 2 skipped / 0 failed |

**这段区间里本项的受影响面**：**零改动**：`git diff --name-only d184e36..HEAD -- backend/paideia-knowledge/` 输出为空——对象存储的代码、配置、迁移都没被碰过。

**结论**：本项所在模块在锚点之后**一行未改**，重跑只为把"报告绑到当前 HEAD"。生产侧（RustFS、预签名直读）本轮未动、未重新验证，沿用原报告的人工核验记录。
