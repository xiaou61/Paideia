---
artifact: proposal
work_id: WORK-015
work: 代码执行沙箱与可运行练习
status: approved
workflow: full
depends_on: [requirements]
related_to: [WORK-014, WORK-009]
created: 2026-10-18
updated: 2026-10-18
approved_by: xiaou61
approved_at: 2026-10-18 08:15:10 +0800
approver_role: CTO
---

# 提案 · 代码执行沙箱与可运行练习

## 摘要

在练习块里加第四种题型「运行」：读者改代码、点运行，服务端在**一次性容器**里执行并把 stdout / stderr / 退出码 / 耗时回传，判定仍在客户端。隔离选 **Docker + 全套加固（无网络、只读根、非 root、丢弃全部 capability、pids/内存/CPU/时间硬上限）**；gVisor 作为**可切换的升级档**，不阻塞 v1。

服务端只执行、不判题、不存用例、不存源码。

## 三档隔离对比

| | A. 加固容器（推荐） | B. gVisor（runsc） | C. 微虚拟机（Firecracker） |
| --- | --- | --- | --- |
| **挡住什么** | 资源滥用、文件系统篡改、提权到容器内 root、访问宿主文件（靠只读挂载 + cap drop + 非 root）、出网（`--network=none`） | 在 A 之上**挡掉大部分内核提权**：系统调用经用户态内核转发，容器共享内核这一面被收窄 | 最彻底：独立内核，硬件虚拟化边界 |
| **挡不住什么** | **内核漏洞导致的容器逃逸**。这是本档的已知天花板，必须写在文档里 | gVisor 自身的兼容性与漏洞面；部分 syscall 行为与真实 Linux 有差异 | 需要 KVM（云主机常不给嵌套虚拟化）；启动与内存开销最高 |
| **部署代价** | 零新增：服务器已有 Docker | 装 `runsc` 二进制 + 在 `daemon.json` 注册 runtime；`--runtime=runsc` 即可切换 | 需 KVM、独立进程管理与镜像格式转换（OCI → rootfs），工作量是另一个数量级 |
| **本机能否验证** | 否（无 Docker） | 否 | 否 |
| **内存开销** | 最低（练习程序 256MB 上限） | 中（用户态内核有额外开销） | 最高（每个 microVM 一份内核） |
| **生产机可行性** | 可行：4C/16G，已在跑 Docker | 可行，但要占额外内存，且与 MySQL/RustFS/Firecrawl 抢资源 | 大概率不可行（无 KVM 保证，且内存不够） |

**推荐 A，并把 runtime 做成配置项**：`paideia.sandbox.runtime`（默认空 = 普通容器；设成 `runsc` 就切 gVisor）。这样将来装 gVisor 不用改一行代码。

**为什么不是 C**：生产机同时跑 MySQL、RustFS、Firecrawl，Firecracker 要 KVM + 每实例一份内核，成本和风险都不成比例。

## 推荐方案

### 机制

1. **执行器就是 `docker run` 子进程**，不新建独立服务。
   - 应用用 `ProcessBuilder` 起 `docker run --rm ...`，把代码从 stdin 写进容器（`-i` + 镜像里的 `sh -c 'cat > /tmp/Main.java; javac ...'`）。
   - 超时由应用侧 `destroyForcibly()` + `--stop-timeout 0` 双保险。
   - 并发用 `Semaphore`（默认 2），排不上队直接返回 429，不无限堆线程。
   - **为什么不起独立 runner 服务**：多一个部署单元、多一套健康检查与内网鉴权，而当前部署形态是 systemd + 裸 jar + 本机 Docker。`docker run` 本身就是独立进程，已满足"执行器崩溃不影响 API"。
   - **已知限制**：如果将来把应用本身容器化，就没有 docker socket 可用，那时必须改成独立 runner 服务。这一条写进设计。
2. **无网络、只读根、非 root、cap 全丢**：
   ```
   --network=none --read-only --tmpfs /tmp:rw,size=64m,exec
   --user 65534:65534 --cap-drop=ALL --security-opt=no-new-privileges
   --memory=256m --memory-swap=256m --cpus=0.5 --pids-limit=64
   --ulimit nofile=64 --ulimit nproc=64
   ```
   `javac` 需要写 class 文件，所以 `/tmp` 必须是可写且可执行的 tmpfs（有 64MB 上限）。
3. **镜像钉 digest**：`eclipse-temurin:21-jdk-alpine`、`python:3.12-alpine` 按 digest 引用；部署文档给出 `docker pull` 命令。**不装用户依赖**。
4. **判定留在客户端**：服务端只回 `stdout / stderr / exitCode / durationMs / timedOut / oom`。用例比较在 `code-challenge` 里做——那样服务端不存题库，"隐藏用例"这件事也就不存在（正文里的用例本来对读者可见）。
5. **配额与审计**：一张 `code_runs` 表（只记 hash 与长度，不存源码），按 `user_id + created_at >= 今天` 数次数；超过 50 返回 429。审计行记录语言、结果、耗时、输出字节、是否被拒。
6. **能力探测**：启动时跑一次 `docker version`（短超时），失败则整个能力标记为不可用；前端通过 `GET /api/v1/sandbox/status` 知道要不要显示「运行」。配置 `enabled=false` 也走同一条路。

### 为什么这最满足需求

- 不新建部署单元，服务器现成 Docker 即可。
- 隔离面覆盖需求里点名的四件事：无网络、资源上限、非 root、无宿主凭据。
- 判定与内容留在已有体系（正文 JSON + 本地进度），不新增题库模型。
- gVisor 只换一个配置值，升级路径明确。

## 范围

**v1 包含**：`GET /sandbox/status`、`POST /sandbox/run`、`code_runs` 审计表与迁移、每日配额、超时/内存/输出上限、`code-challenge` 的 `run` 题型、前端运行面板与结果呈现、部署文档与镜像准备、纯逻辑与端口层测试。

**v1 不包含**：交互式 stdin/REPL、多文件项目、用户自定义依赖、持久化工作区、分布式调度、代码执行结果入库（只进审计）、gVisor 的实际安装（做成配置项）。

## 备选方案

### A. 独立 runner 服务（单独进程 + HTTP）

**最强理由**：真正的进程级隔离，应用不必有 docker socket；将来容器化部署也成立。
**为什么不选**：多一个部署单元、健康检查、内网鉴权与端口，收益在"应用容器化"那天才兑现。v1 用 `docker run` 已满足需求；设计里写明何时该换。

### B. 用户浏览器里跑（Pyodide / WebContainer）

**最强理由**：零服务端风险、零资源成本。
**为什么不选**：Java 不支持；真实 repo 的依赖装不进来；"练习"要的是真实工具链行为，不是子集模拟。

### C. 只做静态检查（不执行）

**最强理由**：最安全。
**为什么不选**：用户明确要"练习需要跑代码"。

## 仓库影响

| 位置 | 影响 |
| --- | --- |
| `backend/paideia-sandbox/`（新增模块） | 端口 `CodeRunner`、`DockerCodeRunner`、`DisabledCodeRunner`、`SandboxProperties`、配额与审计、控制器 |
| `backend/paideia-app/pom.xml` | 依赖新模块 |
| `backend/paideia-app/src/main/resources/db/migration/V13__create_code_runs.sql` | 审计表 |
| `backend/paideia-app/src/main/resources/application.yml` | `paideia.sandbox.*` 默认值 |
| `backend/paideia-security/.../SecurityConfiguration.java` | `/api/v1/sandbox/**` 需登录（不加 admin 规则） |
| `frontend/packages/ui/src/markdown/renderers/schema.ts` | `RunChallenge` 类型与校验 |
| `.../renderers/code-challenge.tsx` | `run` 题型：编辑器、运行、结果 |
| `frontend/packages/knowledge/src/client.ts` | `runCode` / `sandboxStatus` |
| `frontend/apps/app/src/pages/KnowledgeEntryPage.tsx` | 把沙箱客户端注入块上下文 |
| 测试 | 后端：配额、审计、超时/内存/拒绝；前端：解析、渲染、判定 |

## 交付拆分

| 切片 | 内容 | 可独立验证 |
| --- | --- | --- |
| 1 | 迁移 + 审计表 + 配额计数 | 集成测试：第 51 次被拒 |
| 2 | `CodeRunner` 端口 + `DockerCodeRunner` + 加固参数 | 单测断言命令行的每个加固标志都在 |
| 3 | 控制器 + 能力探测 + 失败分类 | MockMvc：禁用时 503、未登录 401、超限 429 |
| 4 | 前端 `run` 题型 + 结果面板 | 组件测试：通过/失败/超时/编译失败 |
| 5 | 部署文档 + 镜像 + 真机验证 | 服务器上跑 Java/Python 各一个样例 |

## 风险与缓解措施

| 风险 | 缓解 |
| --- | --- |
| **容器逃逸（内核漏洞）** | 非 root、cap 全丢、只读根、无网络、no-new-privileges；**文档里写明这是天花板**；`runtime` 配置可切 gVisor |
| 挖矿/代理 | 无网络 + CPU 0.5 + 墙钟 10s + pids 64 |
| 资源打满影响 API | 全局并发信号量 2、内存 256m、单次输 64KB；API 只等待子进程不共享线程池 |
| 镜像膨胀/拉取失败 | 钉 digest；启动探测；失败时能力不可用而不是报错 |
| 输出刷屏 | stdout/stderr 各截断到 64KB，标记 truncated |
| 部署后才发现跑不了 | 能力端点暴露 `enabled/docker/runtime/images`，前端据此隐藏按钮 |

## 验收映射

| 需求 | 验收 |
| --- | --- |
| REQ-001 运行题型 | AC-001 |
| REQ-002 执行契约 | AC-001、AC-003、AC-004 |
| REQ-003 隔离 | AC-002、AC-008 |
| REQ-004 资源上限 | AC-003、AC-004 |
| REQ-005 并发与配额 | AC-005、AC-006 |
| REQ-006 审计 | AC-007 |
| REQ-007 失败分类 | AC-003、AC-004 |
| REQ-008 判定 | AC-001 |
| REQ-009 与 WORK-014 的关系 | AC-009 |
| REQ-010 部署独立 | AC-008、AC-010 |
| REQ-011 可关闭 | AC-009 |

## 待决定事项

1. **是否在本轮就把 gVisor 装上**。建议：**先不装**，`runtime` 配置留好；等你确认要更强的隔离，我再上服务器装 `runsc`（需重启 Docker，会短暂中断 Firecrawl 与 RustFS）。
2. 镜像来源：默认 Docker Hub 官方镜像；若服务器拉取受限，改用内网镜像仓库，配置项已支持替换镜像名。
