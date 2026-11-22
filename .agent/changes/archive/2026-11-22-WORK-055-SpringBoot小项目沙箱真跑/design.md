---
artifact: design
work_id: WORK-055
work: Spring Boot 小项目（沙箱里真跑 mvn test）
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 16:30:35 +0800
approver_role: CTO
---

# Spring Boot 小项目设计

## 系统上下文

请求链路不变：前端 run-challenge → `POST /api/v1/sandbox/run`（`language=spring` + `files`）
→ `SandboxController` 校验 → `DockerCodeRunner.run(project=true)` → `DockerCommand.of(...)` →
一次性容器执行 `SPRING_PROJECT_SCRIPT` → 解析输出。

与其它语言唯一的差别是**镜像、档位与脚本**；协议、鉴权、配额、审计全部复用。

## 组件与职责

| 组件 | 职责 | 改动 |
| --- | --- | --- |
| `SandboxProperties` | 每种语言一个镜像与档位 | 加 `springImage` / `springTimeoutMs` / `springMemory`；`languages()` / `imageFor` / `timeoutMsFor` / `memoryFor` 各加一支 |
| `DockerCommand` | 组装 `docker run` | spring：tmpfs `/tmp` 512m、注入 `HOME`/`MAVEN_CONFIG`、入口脚本换成读文件 + `mvn` |
| `SandboxController` | 校验与拒绝 | spring 必须带 `files`（项目模式），否则 400 |
| 镜像 `paideia-spring:1` | JDK21 + Maven + `/opt/m2`（a+rX）+ `/opt/skeleton` | 服务器构建 |
| `run-challenge.tsx` / `schema.ts` | 渲染与语言白名单 | 允许 `spring`，按 Java 高亮 |

## 请求或事件流程（脚本）

1. `mkdir -p /tmp/app/home /tmp/app/src`；`cp -a /opt/skeleton/. /tmp/app/`（pom + Application + yml + resources）。
2. 读 `count`；循环 `path` / `size` / 内容，写到 `/tmp/app/src/<path>`（路径已由控制器白名单校验）。
3. `cd /tmp/app && HOME=/tmp/app/home MAVEN_CONFIG=/tmp/app/home/.m2 mvn -o -B -q -Dmaven.repo.local=/opt/m2/repository test`
4. 打印判定行：`grep -h 'Tests run' target/surefire-reports/*.txt` 汇总 + 失败用例名。
5. 退出码 = mvn 的退出码（测试失败非 0）。

## 接口与数据

无新接口。`RunRequest.language` 新增取值 `spring`；`RunView` 字段不变（`stdout/stderr/exitCode/durationMs`）。

## 持久化与迁移

无。内容走既有知识库条目 + `code-challenge` 围栏块。

## 失败处理与恢复

| 失败 | 处理 |
| --- | --- |
| 镜像未配置 | `languages()` 不含 spring → 接口 400「只支持 …」 |
| spring 未带 files | 400「Spring 项目必须以项目模式提交」 |
| `mvn` 超时 | 现有超时分支：`destroyForcibly` + `docker rm -f`，`timedOut=true` |
| 依赖缺失（离线仓库不全） | mvn 退出非 0，stderr 有 "offline mode" 字样；运维侧重烤镜像 |

## 安全与权限

- 参数**一个都不放松**：`--network=none`、`--read-only`、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、非 root、pids/内存上限。
- 路径白名单沿用 `PROJECT_PATH`（无空格/引号/`$`/`..`）。
- `/opt/m2` 在镜像里只读，用户代码改不到依赖。

## 可观测性

沿用 `durationMs`、退出码、超时/内存标记；`ai_runs` 不涉及。建议上线后看一次 Spring 运行的耗时分布（是否接近 27 秒上限）。

## 实施顺序

1. 后端档位与脚本（`SandboxProperties` / `DockerCommand` / `SandboxController`）。
2. 单测：断言 spring 的命令里仍有无网络/只读/非 root/90 秒；未配置镜像时不暴露语言。
3. 服务器：构建镜像 + 写三个配置键 + 重启。
4. 真实调用验一遍（正确实现全绿、错误实现点名失败）。
5. 前端语言白名单与渲染。
6. 3 关内容 + 一条 e2e。

## 测试策略

- 后端：`DockerCommandTest` 加 spring 用例（参数不放松）、`SandboxPropertiesTest`（语言暴露与档位）。
- 接口：临时脚本打线上一次（不落成 e2e，避免每次 CI 跑 27 秒的重任务）。
- 内容：`verify_course.py` 不覆盖这类；用 `scripts/verify_spring_project.py`（本项新增）在本地用同一镜像跑 starter 与参考解。

## 需求追踪

REQ-001/002/008 → TASK-001/002；REQ-003/004 → TASK-002/003；REQ-005 → TASK-004；REQ-006/007 → TASK-005/006。

## 待决定事项

Q-001 并发槽（建议先观察）、Q-002 内容深度（建议先 3 关）。
