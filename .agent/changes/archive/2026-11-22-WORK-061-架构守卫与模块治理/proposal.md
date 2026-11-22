---
artifact: proposal
work_id: WORK-061
work: 架构守卫与模块治理
status: approved
created: 2026-11-02
updated: 2026-11-02
approved_by: xiaou61
approved_at: 2026-11-02 18:34:50 +0800
approver_role: CTO
---

# 架构守卫与模块治理提案

## 摘要

本项做五件彼此独立、都属「让现有设计真正生效」的工程治理，不引入新技术件：

| 片 | 一句话 | 形态 |
| --- | --- | --- |
| P1 | 三条最强守卫进入每次提交的回路 | AGENTS.md 改一行命令 |
| P2 | 架构档案索引的计数漂移由 `--check` 拦住 | 生成脚本加约 15 行断言 |
| P3 | `paideia-ops` 拆成第 12 个 Maven 模块 | 物理搬家，包名零改 |
| P4 | 记忆提取失败不重跑 ⇒ 持久任务行 + 中继重试 | 一张表 + 一个中继 + 抢占语义 |
| P5 | ai 公开面收紧 | 8 个类型移进 internal |

**阅读要点**：P4 不是「补 RabbitMQ」，是把代码 javadoc 里已登记的那条缺口（设计 §10）用最小手段关掉；D-14 的 AMQP 仍不落地。

## 推荐方案

| 方向 | 现成的东西 | 本期怎么做 |
| --- | --- | --- |
| 守卫回路 | 三条守卫已存在且断言很硬（403 矩阵、`@ApiAccess` 全量声明） | 第二切片命令加三个类名；实测后用真实条数/耗时更新注记 |
| 档案防漂移 | `gen-arch-diagram.mjs --check` 已算全部六个数 | 加 README「现有图」表计数断言；README 仍是人手维护 |
| ops 边界 | ops 已是独立包、依赖面只有 3+1 个模块 | 建 `paideia-ops` 模块，目录整体平移，pom 接上 |
| 记忆提取重试 | 游标抢占（条件 UPDATE）已有；退款口径已有；AiUserDeletionListener 已有 | 同一事务里加任务行；执行前抢占；退避重试；上限 3 次 |
| ai 公开面 | 22 个根类型是真被 app 用的 | 只移那 8 个没人用的；编译器 + ModularityTest 背书 |

## 范围

**做**：

1. AGENTS.md 第二切片命令 + 注记更新（P1）；
2. `gen-arch-diagram.mjs` 的 README 断言 + 反向验证（P2）；
3. `paideia-ops` 模块（pom、30 个类、app 依赖、root pom modules、四图重生成）（P3）；
4. `ai_memory_extraction_jobs` 表 + 迁移 + 校验和登记 + 中继 + 抢占/退避/上限/卡死回收 + 注销级联 + 保留清理 + 单测（P4）；
5. 8 个类型移入 internal（P5）；
6. 分片提交 + updates.md 记录 + 验证报告。

**不做**：RabbitMQ/AMQP、通用任务平台、CI、console 提取、前端、注销与自评桥的语义变更、`ops.*` 配置改名。

## 备选方案

| 方案 | 最强理由 | 没选的原因 |
| --- | --- | --- |
| P1 改为建 GitHub Actions | 真正的无人值守回路 | 集成测试要 MySQL 服务容器 + 口令 secret；成本不成比例，且现状是 agent 本地切片在跑 |
| P2 把 README 改成生成物 | 彻底不存在漂移 | README 有散文、说明与「不包含什么」清单，生成化会把维护权也拿走 |
| P3 顺带改包名（如 `io.github.xiaou61.ops` → 独立根） | 归属更纯粹 | 包名即 modulith 模块名，改动面扩到全仓 import 与测试；换 Maven 归属已达成目标 |
| P4 上 RabbitMQ + outbox 中继（照 D-14 字面） | 一步到位、为将来铺路 | 没有跨进程消费者；多一个中间件 = 多一个会挂的部署单元；单实例下进程内中继语义等价 |
| P4 只把失败写进日志并在下轮多整理一次 | 零新表 | 无法定位具体失败切片，会重复整理已成功的部分；不能退避，会连续烧调用 |
| P5 移全部 30 个类型 | 包根最干净 | 22 个被 app 集成测试真实使用，移了要重构测试缝，是负收益 |
| 五片拆五个 WORK | 每项更小 | 用户一次批准、同一 worktree、同批验证更省；且五片互不冲突 |

## 仓库影响

| 路径 | 改动 |
| --- | --- |
| `AGENTS.md` | 第二切片命令加三个类名，注记更新 |
| `scripts/gen-arch-diagram.mjs` | `--check` 增加 README 计数断言 |
| `docs/architecture/README.md` | 模块数 11 → 12（生成脚本的断言会逼着改） |
| `docs/architecture/01-module-dependencies.md` | 重生成（新增 paideia-ops 节点与边） |
| `backend/pom.xml` | modules 增加 paideia-ops |
| `backend/paideia-ops/**` | 新建：pom.xml + `io/github/xiaou61/ops` 30 个类 |
| `backend/paideia-app/pom.xml` | 增加 paideia-ops 依赖 |
| `backend/paideia-app/src/main/java/io/github/xiaou61/ops/**` | 删除（迁出） |
| `backend/paideia-app/src/main/resources/db/migration/V2026...__create_memory_extraction_jobs.sql` | 新建（14 位时间戳） |
| `backend/paideia-ai/.../memory/` | MemoryExtractionService 重试化；任务表 Mapper；中继；保留清理；MemoryProperties 加参数 |
| `backend/paideia-ai/.../user/AiUserDeletionListener.java` | 级联删除任务行 |
| `backend/paideia-ai/.../{8 个根类型}` | 移入 internal 对应子包 |
| `.agent/changes/WORK-061-...`、`.agent/history/updates.md` | 工件与记录 |
