---
artifact: requirements
work_id: WORK-055
work: Spring Boot 小项目（沙箱里真跑 mvn test）
status: approved
mode: strict
mode_reason: 新增一种沙箱语言与镜像（执行面）、给执行器加一条按语言的超时/内存档位、放宽前端 code-challenge 的语言白名单、并新增一条内容线；触及安全边界（容器能力、无网络、只读根）与配额口径
workflow: full
depends_on: []
related_to: [WORK-015, WORK-032, WORK-038, WORK-052, WORK-054]
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 16:30:35 +0800
approver_role: CTO
---

# Spring Boot 小项目（沙箱里真跑 mvn test）需求

## 目标

在平台里开一条**"从零做一个小 Spring Boot 项目"**的实战线：每关给一个可运行的 Maven 骨架，
用户只改 1–3 个文件，点运行后在**沙箱里真跑 `mvn test`**，按测试通过数判分。

不是"再多一条阅读线"，而是**第一次让整项目（pom + 多文件 + 依赖）在沙箱里编译并跑测试**。

## 非目标

- **不做本地执行**：不调用用户机器（桌面端不加执行能力）。理由见 proposer「备选方案」。
- **不做浏览器内终端**、不做文件树编辑器：一关只改 1–3 个文件，沿用现有"一个可改 + 若干只读"的形态。
- **不放开网络**：容器仍然 `--network=none`，依赖全部烤进镜像。
- **不做增量编译**：每次运行是新容器，改一个文件也全量编译（实测 16 秒地板价）。要压下去得像 SQL 池那样常驻容器，另开工作项。
- **不迁移已有内容**：`spring-handmade`（手写迷你 Spring）保持沙箱里的纯 Java 单文件形态，不改判分方式。

## 已确认事实（2026-10-29 尖刺实测）

| 项 | 事实 | 依据 |
| --- | --- | --- |
| 离线可跑 | `--network=none` 下 `mvn -o test` 正常，`@SpringBootTest` 起上下文并打本地 HTTP 都通 | `.agent/notes/spring-project-sandbox-spike.md` |
| 现约束可跑 | `--read-only` + 非 root(999) + 1g 内存 + tmpfs 下也通，**沙箱参数一条都不用放松** | 同上 |
| 耗时 | 全量 `mvn -o -q test`：**1 核 27.1 秒 / 2 核 14.6 秒**；纯单测 16.4 秒（地板价） | 同上 |
| 镜像 | 989 MB（含 98MB 离线仓库）；构建 7 分钟（一次性） | 同上 |
| 三条硬约定 | ① 离线仓库在非 root 可读路径 + `-Dmaven.repo.local=/opt/m2/repository`；② `HOME`/`MAVEN_CONFIG` 指可写目录；③ `/app/target` 要 tmpfs | 同上 |
| 现有超时不够 | java 档 `timeoutMs = 10_000`，Spring 一次要 14–27 秒 | `SandboxProperties` |
| 项目模式已有 | 多文件 `files`（路径 + 内容）+ 固定测试入口，路径做白名单校验 | `SandboxController.validateProject` |
| 前端语言白名单 | `code-challenge` 的 `language` 只允许 `java \| python` | `renderers/schema.ts` |

## 用户决定

- **2026-10-29**：要加这条线；同意先用"云端真跑"，本地执行只作降级；同意先做沙箱链路再铺内容。

## 功能需求

- **REQ-001 新语言 `spring`**：沙箱支持 `language=spring` 的项目模式；镜像、超时、内存按语言独立配置（**不写死**）。
- **REQ-002 超时档位**：`spring` 单独一档，默认 90 秒；普通 java 仍是 10 秒。
- **REQ-003 判分口径**：判分读 surefire 结果（`Tests run: X, Failures: Y`），把每条失败的测试名放进输出。
- **REQ-004 骨架在镜像里**：`pom.xml`、`Application`、`application.yml` 由镜像提供；关卡只下发用户要改的文件与测试文件。
- **REQ-005 前端**：`code-challenge` 允许 `language: spring`；编辑器按 Java 高亮；语言标签显示为「Java · Spring」。
- **REQ-006 内容**：一条实战线（slug `spring-boot-project`），第一期 3 关起步，每关一个可跑骨架 + 一组断言。
- **REQ-007 配额与审计不变**：仍走既有沙箱配额与 `durationMs`/退出码记录，不新增计费维度。
- **REQ-008 安全不放松**：`--network=none`、`--read-only`、`--cap-drop=ALL`、非 root、pids/内存上限全部保留。

## 质量要求

- 镜像与运行参数**配置化**（`sandbox.spring-image` / `spring-timeout-ms` / `spring-memory`），换镜像不改代码。
- 镜像缺失时 `languages()` 不暴露 `spring`，前端不渲染运行按钮（与现有口径一致）。
- 一次运行至少给 2 核，否则 27 秒；超时上限要覆盖 3 倍实测中位数。

## 约束与依赖

- 依赖服务器有 Docker 与足够磁盘（镜像 989MB，当前 40G 可用）。
- 依赖现有项目模式的分帧协议与路径白名单——**新增语言不新开协议**。

## 关联工作项

- **WORK-015 / 032**：沙箱执行层的来源（一次性容器、SQL 池）。
- **WORK-038**：项目模式的来源（多文件 + 固定测试入口）。
- **WORK-054**：`spring-from-zero` 课程线与 `spring-handmade` 实战线——本项是"第三条：整项目真跑"。

## 验收标准

- **AC-001** `POST /api/v1/sandbox/run` 传 `language=spring` + 骨架文件 + 正确实现 → 用例全绿，输出里含 surefire 汇总。
- **AC-002** 同一请求把实现改错 → 失败，输出点名失败的测试，退出码非 0。
- **AC-003** 语言未配置镜像时 `languages()` 不含 spring，接口返回"只支持 …"而不是 500。
- **AC-004** 沙箱参数不放松：`docker inspect` 或单测断言命令里仍有 `--network=none` / `--read-only` / `--cap-drop=ALL` / 非 root。
- **AC-005** 一次运行墙钟 ≤ 90 秒仍超时保护生效；1 核下 27 秒内完成（实测基线）。
- **AC-006** 前端渲染：关卡里 `language: spring` 的块能渲染运行按钮，编辑器按 Java 高亮。
- **AC-007** 内容线 3 关可通关：starter 必不绿、参考解必全绿。

## 假设

- 3 关的内容量足以验证链路；完整 10–15 关是后续内容工作，不影响本项验收。
- 服务器 4 核，Spring 运行同时最多 2 个并发是可以接受的（与 SQL 池同口径观察）。

## 待确认问题

- **Q-001 并发槽**：一次 Spring 运行占槽 15–27 秒（SQL 是毫秒级）。是否给 Spring 单独限并发（例如同时最多 1 个）？
  **我建议**：先按现有槽位跑 1 周看峰值，没有排队再谈。
- **Q-002 内容深度**：第一期 3 关（路由 + 分层 + 事务）够不够作为"小项目"的展示？
  **我建议**：先 3 关打通链路，下一期补到 8–10 关（含从零建骨架的操作说明）。
