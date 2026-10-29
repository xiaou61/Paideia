---
artifact: proposal
work_id: WORK-055
work: Spring Boot 小项目（沙箱里真跑 mvn test）
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 16:30:35 +0800
approver_role: CTO
---

# Spring Boot 小项目提案

## 摘要

新增沙箱语言 `spring`：镜像里预置 Maven + 离线依赖 + 项目骨架，用户提交多文件后跑 `mvn -o test`，
按 surefire 结果判分。尖刺已验通（无网络、只读根、非 root、1g 内存下 14–27 秒），
所以本项是**接线 + 内容**，不是基建探索。

## 推荐方案

沿用现有项目模式的分帧协议，只加一条按语言的**镜像 / 超时 / 内存 / tmpfs** 档位：

- `sandbox.spring-image`（默认空 → 语言不暴露）、`sandbox.spring-timeout-ms`（默认 90 000）、
  `sandbox.spring-memory`（默认 1g）。
- 容器仍然 `--network=none --read-only --cap-drop=ALL`、非 root；只多两件事：
  `/tmp` 给 512m、注入 `HOME=/tmp/home` 与 `MAVEN_CONFIG=/tmp/home/.m2`。
- 脚本：从 stdin 读「文件数 + 每个文件」，写进 `/tmp/app/src/`（骨架来自 `/opt/skeleton`），
  再 `mvn -o -B -Dmaven.repo.local=/opt/m2/repository test`，最后打印 surefire 汇总。

## 范围

做：后端 `spring` 语言（配置项 / 脚本 / 控制器守卫）、前端 `code-challenge` 语言白名单与渲染、
镜像与骨架（服务器构建）、3 关内容、一条 e2e。

不做：本地执行、浏览器终端、增量编译、把 `spring-handmade` 改成真跑。

## 备选方案

| 方案 | 最强理由 | 没选的原因 |
| --- | --- | --- |
| 用户本地跑 + 提交证据（Exercism 式） | 快（5–10 秒）、真实工作流 | 判分不可信；且用户要先装 JDK+Maven 下几百 MB 依赖 |
| 桌面端（Electron）在本地执行 | 真正"在他机器上改" | 要给桌面壳加执行 IPC = 新攻击面；跨平台探测 JDK/Maven 成本高 |
| 浏览器内终端 + 长会话容器 | 体验最真 | 要长期会话、终端复用、限时回收；工作量仅次于桌面端 |
| 只做静态校验（不跑） | 零基建 | "项目能跑起来"是这个功能的核心价值，静态校验给不了 |

## 仓库影响

| 路径 | 改动 |
| --- | --- |
| `backend/paideia-sandbox/.../run/SandboxProperties.java` | `springImage` / `springTimeoutMs` / `springMemory` + `imageFor` / `timeoutMsFor` / `memoryFor` / `languages()` |
| `backend/paideia-sandbox/.../run/DockerCommand.java` | `SPRING_PROJECT_SCRIPT`、tmpfs 512m、`HOME`/`MAVEN_CONFIG`、spring 必须项目模式 |
| `backend/paideia-sandbox/.../web/SandboxController.java` | spring 非项目模式 → 明确拒绝 |
| `frontend/packages/ui/src/markdown/renderers/schema.ts` | `language` 增加 `spring` |
| `frontend/packages/ui/src/markdown/renderers/run-challenge.tsx` | 语言标签（Java · Spring）与高亮沿用 java |
| `content/courses/spring-boot-project/` | 新线 3 关 |
| 服务器 | 镜像 `paideia-spring:1`；`application.yml` 加三个键 |

无迁移、无新表。

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| 一次运行 15–27 秒，槽位被长期占用 | 超时 90 秒硬上限；先按现有槽位跑，观察排队（Q-001） |
| 镜像 989MB 占盘 | 服务器 40G 可用；构建脚本 `--pull=false` 可复用层 |
| 用户提交超大文件 | 沿用 `maxProjectFiles` / 单文件 / 总量三条上限 |
| 用户改测试文件作弊 | 与现有 java-project 同一口径（测试文件标只读、服务端不校验）；本项不新增防护，风险登记 |

## 验收映射

REQ-001/002/008 → TASK-001、TASK-002；REQ-003 → TASK-002；REQ-004 → TASK-003；REQ-005 → TASK-004；
REQ-006/007 → TASK-005、TASK-006。

## 待决定事项

见需求 Q-001（并发槽）、Q-002（内容深度）。两项都不阻断本项开工。
