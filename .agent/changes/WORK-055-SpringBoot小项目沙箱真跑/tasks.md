---
artifact: tasks
work_id: WORK-055
work: Spring Boot 小项目（沙箱里真跑 mvn test）
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 16:30:35 +0800
approver_role: CTO
---

# Spring Boot 小项目实施任务

## 依据

`requirements.md`（REQ-001…008 / AC-001…007）、`proposal.md`（配置化档位、参数不放松）、
`design.md`（脚本五步）、`.agent/notes/spring-project-sandbox-spike.md`（实测数字与三条约定）。

## 全局约束

- 沙箱参数不放松：`--network=none`、`--read-only`、`--cap-drop=ALL`、非 root。
- 镜像/超时/内存**全部配置化**，代码里不写死镜像名。
- 未配置镜像时不得暴露 `spring` 语言（否则用户看到必然失败的运行按钮）。
- 路径校验沿用现有 `PROJECT_PATH` 白名单。

## 评审焦点

| 失败模式 | 归属任务 | 测试 |
| --- | --- | --- |
| 加了 spring 却漏了 `--network=none` | TASK-002 | `DockerCommandTest`（命令含三个安全参数） |
| spring 用 10 秒 java 超时 → 每次都超时 | TASK-001 | `SandboxPropertiesTest`（spring=90s，java 仍是 10s） |
| 镜像没配却暴露语言 | TASK-001 | `SandboxPropertiesTest`（空 image → 不出现） |
| 非项目模式传 spring 导致脚本读不到文件卡死 | TASK-003 | 接口返回 400 |
| 用户文件路径穿越 | 复用现有 | `SandboxProjectValidationTest`（既有用例覆盖） |
| 离线仓库路径指错 → "parent POM absent" | TASK-004 | 真实调用（AC-001） |

## 任务

### TASK-001 | pending | 语言档位配置

- 对应：`REQ-001`、`REQ-002`
- 依赖：无
- 修改：`SandboxProperties.java`
- 接口：`getSpringImage()/setSpringImage(String)`、`getSpringTimeoutMs()/setSpringTimeoutMs(long)`、`getSpringMemory()/setSpringMemory(String)`；`imageFor("spring")`、`timeoutMsFor("spring")`、`memoryFor("spring")`、`languages()` 含 spring
- 测试：`SandboxPropertiesTest`
- 验证：`mvn -pl paideia-sandbox test` → 0 failed

### TASK-002 | pending | 运行脚本与容器参数

- 对应：`REQ-003`、`REQ-004`、`REQ-008`
- 依赖：TASK-001
- 修改：`DockerCommand.java`（`SPRING_PROJECT_SCRIPT`、tmpfs 512m、`HOME`/`MAVEN_CONFIG`）
- 测试：`DockerCommandTest`（参数不放松 + 脚本含 `-Dmaven.repo.local`）
- 验证：同上

### TASK-003 | pending | 控制器守卫

- 对应：`REQ-001`
- 依赖：TASK-001
- 修改：`SandboxController.java`（spring 必须项目模式）
- 测试：`SandboxProjectValidationTest` 加一条
- 验证：同上

### TASK-004 | pending | 镜像与服务器配置

- 对应：`REQ-004`、`REQ-008`
- 依赖：TASK-002
- 新建：镜像 `paideia-spring:1`（`maven:3.9-eclipse-temurin-21` + `/opt/m2` a+rX + `/opt/skeleton`）
- 修改：服务器 `/opt/paideia-app/application.yml` 加 `sandbox.spring-image` / `spring-timeout-ms: 90000` / `spring-memory: 1g`
- 验证：真实 `POST /sandbox/run`（AC-001/AC-002）

### TASK-005 | pending | 前端语言白名单与渲染

- 对应：`REQ-005`
- 依赖：TASK-001
- 修改：`renderers/schema.ts`（`'java' | 'python' | 'spring'`）、`run-challenge.tsx`（标签「Java · Spring」，高亮沿用 java）
- 测试：`schema.test.ts` / `blocks.test.tsx`
- 验证：`pnpm --filter @paideia/ui test`

### TASK-006 | pending | 内容 3 关 + 校验脚本

- 对应：`REQ-006`、`REQ-007`
- 依赖：TASK-004、TASK-005
- 新建：`content/courses/spring-boot-project/`（总览 + 3 关）、`scripts/verify_spring_project.py`
- 验证：`verify_spring_project.py`（starter 必不绿、参考解必全绿）

### TASK-007 | pending | 上线与验收记录

- 对应：`AC-001`…`AC-007`
- 依赖：全部
- 修改：`testing/report.md`
- 验证：线上 3 关各跑一次，记录耗时与判分

## 完成条件

- 线上 `language=spring` 能跑：正确实现全绿、错误实现点名失败，单次 ≤ 90 秒。
- 前端能渲染 spring 关卡并按 Java 高亮。
- 3 关内容可通过；starter 不绿、参考解全绿。
- 沙箱安全参数在单测里被钉住（无网络/只读/非 root）。
