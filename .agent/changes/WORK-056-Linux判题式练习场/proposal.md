---
artifact: proposal
work_id: WORK-056
work: Linux 判题式练习场
status: approved
created: 2026-10-29
updated: 2026-10-29
approved_by: xiaou61
approved_at: 2026-10-29 17:02:06 +0800
approver_role: CTO
---

# Linux 判题式练习场提案

> 需求见 `requirements.md`（已签署）。本提案只回答"怎么做、代价是什么、还能怎么做"。

## 摘要

在现有代码沙箱里加一种语言 `shell`，语义是**判题式执行**：一次提交 = 一段学习者脚本 + 本题自带的初始文件（seed）+ 本题自带的判定脚本（judge），三样东西在同一个一次性容器里落地，执行完按 judge 的退出码给结论，并回报学习者的输出与"文件被改成了什么样"。

复用的是已有的隔离参数、并发闸、配额和审计；新增的是**一个语言分支、一个端点、一种前端题型**。不新建模块、不新增表、不做终端。

## 推荐方案

**A. 判题式（推荐，也是本提案的范围）**

| 维度 | 取值 |
| --- | --- |
| 执行单位 | 一次 HTTP 请求 = 一个容器 = 一次"跑完就判" |
| 传输 | 复用项目模式的分帧协议（文件数 → 路径/字节数/内容），送 `run.sh` + `judge.sh` + seed |
| 入口 | 容器内固定的 `entry.sh`（常量），请求里的任何字段都进不了命令行 |
| 判定 | judge 退出码 0 = 通过；非 0 = 未通过（附 judge stderr）；超时/启动失败 = 未能判定 |
| 变更摘要 | 执行前后各做一次 `find -printf '%M %s %p'` 快照，宿主机展示两者的 diff |
| 镜像 | `debian:12-slim` + coreutils/procps/grep/sed/gawk/findutils/util-linux/systemd，按 digest 钉 |
| 资源 | 256m 内存、0.5 核、64 pids、15 秒墙钟、64MB tmpfs |
| 网络 | `--network=none`（不开放） |

选它的理由只有一条：**隔离靠"用完即弃"**。每次请求一个干净容器，跨用户、跨次都不存在共享状态，"限制每个人的文件沙箱"这件事自动成立，不需要磁盘配额、空闲回收、重连与注销级联。

**B. 持久容器 + Web 终端（不在本项范围）**

每人一个长活容器，Web 终端敲命令。能做，但代价是：磁盘配额（overlayfs 上限难做，要限容卷）、空闲回收、容器重建、WebSocket 断线重连、注销级联清理，以及一个更宽的输入面（任意交互命令）。而且 systemd、防火墙、SSH 在容器里依然练不了——最想"真机手感"的那几关恰恰是它解决不了的。

## 范围

**做**

- `SandboxProperties` 增加 `shellImage` 与 shell 的资源上限，`shellImage` 为空即整体关闭。
- `DockerCommand` 增加 shell 入口脚本（复用现有加固参数与分帧协议）。
- 新端点 `POST /api/v1/sandbox/shell/run`，复用 `RunSlot`、`SandboxQuotaService`、`code_run` 审计。
- 前端 `code-challenge` 增加 `kind: "shell"`，含编辑器、运行、判定徽标、输出、文件变更。
- 内容：Linux 线 6 道题。
- 部署：目标机预拉镜像 + 配置项 + 线上真跑一遍。

**不做**

- 交互终端、持久卷、快照分支、多机调度。
- 判题脚本的沙箱化（judge 与学习者脚本同权限跑；见风险 R-3）。
- 修改既有 `run`/`sql` 题型的任何行为。

## 备选方案

| 方案 | 取舍 | 结论 |
| --- | --- | --- |
| **逐个命令起容器**（每敲一条命令执行一次） | 无法表达跨命令状态（建目录→改权限→再验证），而这正是 Linux 线 12 关以后的内容形态 | 否 |
| **把 Linux 题塞进现有 `run` 题型**（比对 stdout） | 权限、属主、进程状态都不体现在输出里；靠学习者自己 `echo` 出来等于教他骗自己 | 否 |
| **用 `sql-write` 那套"结果哈希"判题** | 判据是文件系统状态，没有"结果集"可哈希 | 否 |
| **判题放在宿主机**（不在容器内跑 judge） | 宿主机要读容器内文件，破坏"用完即弃"，且宿主上执行不可信脚本等于没有隔离 | 否 |
| **判题脚本由后端白名单生成**（作者不能写） | 能防"judge 里写坏东西"，但每加一道题都要发版，内容与代码重新耦合 | 否（用审计兜底，见 R-3） |

## 仓库影响

| 位置 | 改动 | 规模 |
| --- | --- | --- |
| `backend/paideia-sandbox/.../run/SandboxProperties.java` | 6 个新字段 + 5 个方法加分支 | 小 |
| `backend/paideia-sandbox/.../run/DockerCommand.java` | `SHELL_SCRIPT` 常量 + `scriptFor` 分支 | 小 |
| `backend/paideia-sandbox/.../run/ShellRunner.java`（新） | 组装 stdin 分帧、执行、解析 trailer、截断、超时 | 中 |
| `backend/paideia-sandbox/.../web/SandboxController.java` | 新端点 + 请求/响应记录 + 校验 | 小 |
| `backend/paideia-sandbox/.../audit/SandboxQuotaService.java` | 按语言取限额（sql/shell 各一档） | 小 |
| `frontend/packages/ui/src/markdown/renderers/` | schema 增加 `shell` kind；`shell-challenge.tsx` 新组件；注册表 | 中 |
| `content/reading/linux-from-zero/*.md` | 6 道题（每关至多一道） | 中 |
| `scripts/` | 一条内容侧结构校验（judge 非空、seed 路径合法、id 唯一） | 小 |
| 部署配置 | `PAIDEIA_SANDBOX_SHELL_IMAGE` + 预拉镜像 | 小 |

**零迁移**：`code_run` 的 `language` 是字符串列，`shell` 直接可用。

## 交付拆分

1. **T1 配置与开关**（可在本地单测完成）
2. **T2 容器命令与入口脚本**（单测覆盖参数与脚本内容）
3. **T3 ShellRunner**（分帧、执行、解析、截断）
4. **T4 端点**（配额/并发/审计/校验）
5. **T5 前端 schema 与组件**
6. **T6 内容 6 道题 + 结构校验**
7. **T7 部署与线上真跑**（唯一依赖服务器的任务）

T1–T4 是后端一条链，T5 可并行；T6 依赖 T5 的 JSON 契约；T7 最后。

## 风险与缓解措施

| 编号 | 风险 | 缓解 |
| --- | --- | --- |
| R-1 | **新语言漏一条加固参数**（模板复制时少写一行） | 不复刻一份参数表，直接走 `DockerCommand.of("shell", …)` 的同一处组装；单测断言命令里必须包含 `--network=none`、`--read-only`、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、非 root `--user` |
| R-2 | 用户脚本伪造判定（自己打印 trailer 说"通过"） | 判定结果只看 judge 退出码，且 trailer 由容器内固定 `entry.sh` 在 judge 之后写；自欺没有收益，属"可接受"而不是"需防御" |
| R-3 | judge 脚本是作者写的任意 shell，可能被内容侧误写成危险命令（例如 `rm -rf /`） | 主要在容器内且根只读、非 root、无网，破坏半径被限制在一次性 tmpfs；另外审计表存 `code_hash`，题目来源可追 |
| R-4 | `debian:12-slim` 比 alpine 大（约 120MB），拉取与磁盘增加 | 镜像预拉一次，之后不再动；两台镜像总量仍在可接受范围 |
| R-5 | 15 秒墙钟对"编译/大量 IO"类题不够 | 首批 6 道题都是纯状态操作（毫秒级）；不够时按题给上限由服务端取 `min(题目上限, 配置上限)`，先不做 |
| R-6 | 与 WORK-055 同时改 `SandboxProperties`/`DockerCommand`，合并冲突 | 两处都只加分支不改既有行为；本轮先在 worktree 里做，合并前与 WORK-055 的改动对一次 |
| R-7 | 一次性容器约 1 秒启动，重复提交体验一般 | 与现有 java/python 一致（SQL 才做了池化）；本项不做池化，理由见 design |

## 验收映射

| AC | 由谁覆盖 |
| --- | --- |
| AC-001（关掉就不出现） | T1 单测 + T4 端点 + T5 组件降级 |
| AC-002 / AC-003（通过 / 未通过） | T3 + T4 + T7 线上两题各跑一次 |
| AC-004（死循环） | T3 超时分支 + T7 线上复现 |
| AC-005（无限输出） | T3 截断 + T7 |
| AC-006（并发拒绝不扣配额） | T4 单测（沿用 `RunSlot` 语义） |
| AC-007（断网 / 非 root） | T2 单测 + T7 容器内实测 |
| AC-008（跨次隔离） | T7：同一题提交两次，第二次看不到第一次的文件 |
| AC-009（unit 文件判错） | T6 里那道题 + T7 |
| AC-010（审计无明文） | T4 + 查 `code_run` 一行 |

## 待决定事项

1. 首批 6 道题具体落在哪几关（建议：权限、管道、文本处理、进程/信号、Shell 脚本、systemd unit 各一道）。已按 REQ-008 的能力分布确定，**不需要再决定**。
2. 是否在 `/sandbox/status` 之外再暴露一个"今天还剩几次"的 shell 专用字段——沿用现有 `usedToday(userId, language)` 即可，**不需要新字段**。

无阻断性待决事项。
