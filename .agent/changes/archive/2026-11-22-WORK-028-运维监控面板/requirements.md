---
artifact: requirements
work_id: WORK-028
work: 运维监控面板
status: approved
mode: strict
mode_reason: 新增公网可达的监控组件与端口（部署与网络边界）；应用新增管理面端点与反向代理（授权面）；数据库每日备份与恢复演练（数据安全面）；新增采集脚本以 root 执行（进程执行安全面）；重裁 D-15 基础设施裁决
workflow: full
related_to: [WORK-007, WORK-009, WORK-015, WORK-019, WORK-022, WORK-025, WORK-027]
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 15:42:28 +0800
approver_role: CTO
---

# 运维监控面板需求

## 本项状态与本轮变化

- **2026-10-20 起草并实施上线**。用户原话：「我想在我的后台加一个运维的功能……各种服务监控指标……mysql redis……还有我们部署的那个 laya 是不是也需要呢。，还有什么呢可以想想」。同日第二轮裁决：路线选 **C（Prometheus + Grafana）**、**留历史**（`存，每分钟一行保留 3 天`）、**备份并入本项**。
- 验收见 [testing/report.md](testing/report.md)。本项实质是**重裁 D-15**（可观测性原属暂缓），见 I-01。

## 已确认事实

服务器实测与完整读数见 [research.md](research.md) 第一节。影响设计的六条：

1. **`paideia` unit 无 `User=`，以 root 运行**（RSS 435 MB）——所以它能执行 `systemctl` 与 `docker`，不必装 agent。
2. **`laya` 已用 2.55 GiB / 上限 3 GiB（85%）**——离 OOM 只剩 0.45G，OOM 后静默降级成规则判断。
3. **`redis-server` `DBSIZE` = 0、后端零引用**——装着但没人用。
4. **MySQL `slow_query_log` = OFF**，全库仅 **2.2 MB**——慢查询无法观测；每日全量 dump 成本可忽略。
5. **Docker Hub 与 grafana apt 源直连失败、走 `127.0.0.1:7890` 均成功**——安装必须带代理，否则表现为「卡住无报错」。
6. **`crontab` 空、`/etc/cron.d/` 无备份任务、`scripts/` 无备份脚本**——零备份。公网仅 8081 可达，本项刻意不新增公网端口。

另：Docker 镜像 8.35 GB 中 **6.18 GB 可回收**；`9091` 被 mihomo 占用（不能用）；`release.ps1` 依赖 8081 的 `health`。

### 仓库事实

| 项 | 事实 | 依据 |
| --- | --- | --- |
| Micrometer | `spring-boot-starter-actuator` 已引入，它传递依赖 **`spring-boot-starter-micrometer-metrics`**；`micrometer-registry-prometheus` 由 Boot BOM（micrometer 1.17.1）管版本 | `paideia-app/pom.xml`、Boot 4.1.1 BOM |
| 异步执行器 | `paideia-async` core 32 / **queue 8**，队列满直接 429；由 Boot 自动配置的 `applicationTaskExecutor` 提供，Micrometer 会自动注册指标 | `application.yml`、`TaskExecutionConfiguration` |
| `ai_runs` 统计 | WORK-022 已落地轮次追踪与管理端两页（`/ai-runs/overview`、`/ai-runs/list`） | `ai/internal/telemetry/` |
| 定时任务 | 三处 `@Scheduled`：`ExpiredStatePurger`(5min)、`EssayCollectionJob`(每日)、`TelemetryRetentionPurger`(1h)；`EssayCollectionJob` 注释明写**异常必须吞掉** | 全仓 grep、源码注释 |
| Docker 先例 | `DockerCodeRunner` 用 `docker run` 子进程 + `SandboxCapability` 启动探活 | `sandbox/internal/run/` |
| 管理端结构 | `AdminShell.tsx` 侧栏两层（父项分组 + 子项页面）；`/ai-credits`、`/ai-runs` 已有先例 | 源码 |
| 端点必须声明 | `@ApiAccess` + `ApiAccessDeclarationTest` 强制每个 `/api/v1/**` 端点可解析到访问级别 | `platform/ApiAccess.java` |
| 路径规则顺序 | `/api/v1/xxx/admin/**` 的 `hasRole("ADMIN")` **必须排在任何更宽规则之前**，写反不报错只静默失效 | `SecurityConfiguration` 注释 |
| 前端无 CSP | 全仓无 `Content-Security-Policy` 与 `X-Frame-Options` 设置 | 全仓 grep |
| 迁移 | 最新 `V24`；**编号不许写死** | `db/migration/` |
| 邮件通道 | `MailPort` 三个实现（SMTP / 日志 / 内存）；线上 SMTP 走 QQ | `paideia-account`、`application-local.yml` |

## 用户决定

| 决定 | 影响 |
| --- | --- |
| 运维功能放在**自己的管理端后台**（「我想在我的后台加一个运维的功能」） | 管理端要有入口页；不是另起一个网站 |
| 覆盖服务与中间件指标，含 Laya（「各种服务监控指标……mysql redis……还有我们部署的那个 laya」） | 采集面跨到主机、中间件、外部服务 |
| **技术路线选 C：Prometheus + Grafana** | 新增两个常驻进程 + 若干轻量 exporter；重裁 D-15；**放弃「不新增依赖」这条** |
| **要留历史**（选项「存，每分钟一行保留 3 天」） | 需要时序存储与保留期；**不新增 MySQL 表**，时序由 Prometheus 承担 |
| **数据库备份并入本项** | 范围从「只读监控」扩到「定时备份 + 恢复演练」 |

## 实现选择

本项边界内、按仓库既有模式作出的决定，不再逐条交回用户（理由详见 [research.md](research.md) 第四节）。

- **I-01** `always.md` 把「可观测性」从暂缓改为「已定：Prometheus + Grafana（WORK-028）」。
- **I-02 零迁移**：历史由 Prometheus TSDB 承担（15 秒抓取、保留 3 天），800–1500 条序列约 100 MB 量级。
- **I-03** 四个 exporter：`node_exporter`、`mysqld_exporter`、`redis_exporter`、`blackbox_exporter`。
- **I-04 不装 cAdvisor**：容器与 systemd 指标由定时脚本写 textfile，经 `node_exporter --collector.textfile.directory` 暴露。只需「unit 活没活、占多少内存、容器在不在」，不值得为此多一个吃数百 MB 的常驻进程。
- **I-05** 应用指标走独立管理端口绑回环（`8082`）。8081 是公网口，`/actuator/prometheus` 会暴露 JVM 内部结构与业务维度标签。**`release.ps1` 读 8081 的 `health`，改动后必须复核仍通过。**
- **I-06** 不接 Alertmanager、不做日志聚合；用 Grafana unified alerting 直接发邮件。升级触发条件：需要按标签分组去重、抑制、多接收端。
- **I-07 Grafana 不开公网口，由应用按 ADMIN 反代**：Grafana 绑回环、`serve_from_sub_path`、`root_url` 指向 `/api/v1/ops/grafana/`；应用在该前缀要求 `hasRole("ADMIN")` 后转发，管理端同源 iframe 嵌入，Grafana 内部开匿名 Viewer——唯一入口就是那条已鉴权路径，因此不出现第二次登录，也不维护第二套口令。
- **I-08** 备份用 `mysqldump` + systemd timer（`Persistent=true` 错过补跑），落 `/opt/paideia-backup/`，每日全量保留 7 天。
- **I-09** 备份成功/失败与耗时写进同一个 textfile，于是「上次备份是三天前」可以是告警条件。
- **I-10** 巡检的 unit 与容器名单是固定常量，不接受外部输入。
- **I-11** dashboard 与数据源放 `/etc/grafana/provisioning/`，随本项交付、可复现；手工点出来的面板重装即失。

## 背景

平台现在有七个 systemd 单元、七个 Docker 容器、一个 MySQL、一个对象存储、一个本地推理服务，**以及零个能看见它们的地方**。出了事只有两条路：ssh 上去手敲命令，或者等用户报「助手不说话」。而这些隐患全是「静默」的——队列满只是返回 429、Laya OOM 只是降级成规则判断、Firecrawl 容器重启后不会自己回来、镜像 6.2G 可回收但没人知道、库 2.2MB 却零备份。共同点：**不是缺一次 ssh，而是缺一个「现在到底怎么了、过去三天怎么变的」的地方。**

## 目标

- 管理端一个入口，一屏看到主机、服务、中间件、外部依赖、定时任务、配置开关、备份，且每项能看出**趋势**而不只是当前值（用户选「留历史」的直接意图）。
- 每项异常给一句人话原因（例：`laya 内存 2.55G / 上限 3G`），不是一串原始指标。
- 关键开关的当前值可见（`ai.essay.enabled`、`ai.decision.enabled`、`sandbox.enabled`、`storage`、SMTP 是否配置）——「为什么没内容」一半是开关关着。
- 数据库每天有一份可恢复的备份，且**恢复动作真的演练过一次**；备份失败、磁盘将满、服务掉线**能主动通知**。

## 非目标

- **不做进程管理**：没有重启 / 停止 / 清理按钮。后台口令走明文 HTTP，一个误点就是全站宕机。
- 不接 Alertmanager、不接 Loki / ELK、不做日志检索页。不做多机 / 集群（只有一台生产机）。
- 不做 AI 调用统计的重做——WORK-022 已有 `ai_runs` 与两个页面，运维页只放跳转。
- 不做大屏、不做对外分享、不做超过保留期的归档。
- 不改连接池、线程池、限流等运行时参数——**只看不改**。
- 不做异地与加密备份（见 Q-03）。

## 用户与工作流程

**每天扫一眼**：进「运维」→ 一屏四块趋势（CPU/内存/磁盘、请求量与错误率、线程池队列、Laya 内存）→ 有红色先看红色 → 深挖点进完整面板。

**排查一次故障**：「助手不响应」→ 线程池队列与拒绝曲线，再看 `ai_runs` 成功率与 p95；「八股文没新内容」→ `ai.essay.enabled` 是否 false、`EssayCollectionJob` 上次成功时间；「附件传不上」→ RustFS 探活与 `storage` 当前值；「决策模型没生效」→ Laya `/health` 与内存是否贴顶。

**改完部署**：刷一次页面，确认服务都回来了、开关值没变、备份时间没变旧。**收到告警**：邮件里带面板链接，点进去就是那条曲线。

## 功能需求

### 应用自身指标

- REQ-001：暴露 JVM 指标——堆已用/上限、非堆、GC 次数与耗时、存活线程数、运行时长。
- REQ-002：暴露 `paideia-async` 的活跃线程、队列长度、队列容量与累计拒绝次数；**队列长度达到容量必须可判为异常**（直接对应「用户看不到回答」）。
- REQ-003：暴露 HikariCP 的活跃、空闲、等待线程数。
- REQ-004：暴露 HTTP 请求量、4xx、5xx 与耗时，并**在面板标注 SSE 端点量不准**（WORK-022：`SseEmitter` 让 handler 立即返回，量不到 5–60 秒的流式生成）。
- REQ-005：指标端点只监听回环，不得从公网访问。

### 主机与中间件指标

- REQ-006：主机——CPU 使用率与核数、负载、内存总量/可用/缓存、swap、各挂载点磁盘、运行时长、网络收发。
- REQ-007：MySQL——连通性、`Threads_connected`/`max_connections`、`Threads_running`、查询速率、`Uptime`、库与表大小；**并暴露 `slow_query_log` 当前值**（OFF 时标注「慢查询无法观测」）。
- REQ-008：Redis——连通性、内存、键数量、命中率。**面板标注「本服务未被应用使用」**（`DBSIZE = 0` 且后端零引用），避免误以为它在关键路径上。

### 服务与容器

- REQ-009：systemd 白名单单元的 `ActiveState`、启动时间、内存占用与上限。
- REQ-010：内存占用达上限 75% 判警告、90% 判异常（Laya 当前 85%，应落在警告区）。
- REQ-011：Docker 白名单容器的运行状态、启动时间与重启次数；**重启次数非零必须可见**（Firecrawl 六个容器 `restart: "no"`，重启后不会自己回来）。
- REQ-012：镜像、容器、卷的磁盘占用与可回收空间。

### 外部依赖探活

- REQ-013：Laya——`/health` 状态码与耗时；不可达判异常并提示「已降级为规则判断」。
- REQ-014：RustFS——端口与 HTTP 可达性。附件全走 S3，**不可达判异常**。
- REQ-015：Firecrawl 反代状态码判别：`401` = 代理活着且能解析到 api，`502` = api 挂了（已发生过的那次），`000`/超时 = 代理本身不在。

### 定时任务与配置开关

- REQ-016：三处 `@Scheduled` 任务的上次运行时间、是否成功、耗时。
- REQ-017：关键开关的生效值：`ai.essay.enabled`、`ai.decision.enabled`、`sandbox.enabled`、`knowledge.storage`、SMTP 是否已配置。
- REQ-018：能区分「任务未启用」与「启用但上次失败」——都是「没新内容」，原因完全不同。

### 备份

- REQ-019：每日全量 `mysqldump`（结构 + 数据 + 触发器 + 事件），压缩存放，保留 7 天，超出滚动删除。
- REQ-020：失败时保留原因，且**24 小时内被通知**。
- REQ-021：「最近一次成功备份时间」与「最近一次结果」作为指标暴露。
- REQ-022：备份文件不含明文口令；凭据从权限 600 的独立文件读，不进命令行或仓库。
- REQ-023：**恢复演练必须真跑一次**并写进验证报告：临时库还原最近一次备份，核对表数量与关键表行数。

### 采集与保留

- REQ-024：抓取 15 秒、保留 3 天，两者都在配置里，不写死在代码里。
- REQ-025：单个目标不可达时其余照常采集；不可达本身变成一条指标（`up == 0`），不让整次抓取失败。
- REQ-026：每个探活独立超时（≤ 2 秒），一个慢探活不得拖住整次抓取。

### 面板与入口

- REQ-027：管理端新增「运维」入口（父项分组 + 子页面），与现有侧栏结构一致。
- REQ-028：首屏给出七类摘要——主机、服务、中间件、外部依赖、定时任务、配置开关、备份，每项为**正常 / 警告 / 异常 / 未知**之一；**取不到数据必须显示「未知」，不得显示 0 或「正常」**。
- REQ-029：首屏提供进入完整面板（趋势、任意时间范围、分目标下钻）的入口，且**不要求第二次登录**。
- REQ-030：面板以可复现的配置交付（数据源与 dashboard 都是配置文件），重装后不改代码即可恢复。

### 告警

- REQ-031：至少四条告警：采集目标下线、磁盘可用 < 15%、备份超 36 小时未成功、Laya 内存超上限 90%。阈值可配置。
- REQ-032：告警走邮件，复用线上 SMTP 配置；**凭据只在服务器上被忽略的文件里**。
- REQ-033：同一条告警在恢复前不重复轰炸（基础去重）。

### 安全

- REQ-034：Prometheus、Grafana、四个 exporter 全绑回环；公网仍只有 `8081`。
- REQ-035：Grafana 唯一入口是应用上 `hasRole("ADMIN")` 的代理路径；控制器标 `@ApiAccess(ADMIN)`，规则**排在更宽规则之前**。
- REQ-036：运维侧**无写操作**（仅 `GET`），唯一例外是 backup timer（不经 HTTP）。
- REQ-037：采集脚本以固定参数、固定白名单执行，用户可控输入不得进入任何命令行。

## 质量要求

- 监控崩溃不得影响 `paideia`：进程独立、各自 `Restart=`。每个进程有内存上限或实测记录。
- 新增进程全部开机自启（`is-enabled` = `enabled`）。
- 数据缺失时面板仍可读，不出现整页错误。阈值集中在配置里，不散落在脚本与面板里各写一份。

## 约束与依赖

- 云安全组只放行 8081（实测 8080/80 不可达）。**本项不新增公网端口**。
- 安装依赖代理 `127.0.0.1:7890`（直连 Docker Hub 与 grafana apt 源实测失败，不带时表现为「下载卡住、无报错」）。
- 迁移编号不得写死（当前 V24）；本项**预期零迁移**，中途若需建表须重新评估。
- 不得把服务器凭据、S3 密钥、SMTP 授权码写进仓库或被跟踪文件。
- 应用以 root 运行是既有事实，本项**不改变**（改普通用户会牵动部署脚本与文件权限）。

## 关联工作项

- **WORK-022**：已有 `ai_runs` 与两个页面，本项**不重做**只放跳转；其「SSE 量不准」结论被 REQ-004 引用。
- **Laya 服务**：`laya.service`（含 `MemoryMax=3G`）接入于 WORK-026；该工件目前只在本地、未随分支入库，所以这里不作 `related_to` 登记。本项监控不改它，Laya 贴顶是最可能第一个报出的告警。
- **WORK-007**：线上 `storage: s3`，RustFS 是附件单点，故 REQ-014 判为异常级。
- **WORK-015**：`DockerCodeRunner` / `SandboxCapability` 是本项调用 Docker CLI 的先例。
- **WORK-025 / WORK-027**：都靠定时任务，REQ-016/018 直接服务它们；**WORK-009**：线程池 429 与积分 402 要能区分。

## 扩展点候选

用户问「还有什么呢可以想想」。逐条评估（含为什么不做）见 [research.md](research.md) 第三节。结论：**做**备份与新鲜度、定时任务结果、关键开关值、Firecrawl 容器与 502、Laya 内存贴顶、磁盘与可回收镜像、线程池队列与拒绝数；**C 路线白送**磁盘增长预测、内存泄漏趋势、错误率与延迟分位、重启次数；**不做**证书到期、日志聚合、进程动作、对外状态页、Alertmanager、cAdvisor、pushgateway。

## 验收标准

- AC-001：管理端出现「运维」入口，首屏七类摘要都有明确四态之一（正常 / 警告 / 异常 / 未知），无空白区块。
- AC-002：停掉 `laya` 后，Laya 一项在 30 秒内变为异常并给出原因；主机、中间件、备份仍正常显示（验证 REQ-025 的隔离）。
- AC-003：学习者令牌访问 `/api/v1/ops/**` 返回 403，未登录 401；公网访问 `8082`、`3000`、`9090`、`91xx` 全部连接失败。
- AC-004：面板在**不要求第二次登录**的情况下可用（验证 I-07）。
- AC-005：注入一个队列满的指标值后，面板显示异常并给出队列长度/容量（不做真实并发压满）。
- AC-006：手动触发一次 dump，文件存在且能在临时库完整还原；表数量与关键表行数与源库一致。
- AC-007：把备份脚本改成必定失败后，告警邮件在 24 小时内发出。
- AC-008：用替代数据源构造「磁盘可用 < 15%」，告警触发一次（不真的写满磁盘）。
- AC-009：三个采集目标任一指向不可达地址时，其余仍正常采集，`up` 把该目标标为 0。
- AC-010：运维侧接口清单只含 `GET`；`ApiAccessDeclarationTest` 通过。
- AC-011：`systemctl` 与 `docker` 的执行参数逐条断言，用户可控输入无法进入命令行（与 `DockerCommandTest` 同口径）。
- AC-012：整机重启后监控单元自动恢复（`is-enabled` 全 `enabled`），面板无断点。
- AC-013：`release.ps1` 部署后健康检查仍通过（验证 I-05 没破坏既有部署路径）。

## 假设

- 生产形态仍是「systemd + 裸 jar + 宿主 Docker」，不容器化；只有一个生产实例，Prometheus 单机足够。
- 3 天保留期够回答当前问题，延长只是改一行配置。
- 管理员接受通过管理端 iframe 看面板，而不是另开网址输第二套口令。
- 备份留在本机同盘即可接受（异地见 Q-03）。

## 待确认问题

**Q-01（已定，实测后改判）** 原计划「回环 + ADMIN 反代 + 匿名 Viewer」。实测三条路都走不通（详见报告「实测暴露的缺陷」#6），最终采用**应用放行反代前缀 + Grafana 自带登录**：摘要卡片仍受 ADMIN 保护，面板由 Grafana 会话把关。代价是两套口令。

**Q-02（不阻断）** Redis 零引用零键：删掉 / 留着并标「未使用」（**默认**）/ 纳入正常监控。建议另议「用起来还是删掉」。

**Q-03（不阻断）** 备份只留本机同盘，机器或盘坏掉就一起没了。**RustFS 在同一台机上，救不了同一台机**。本项先做本机 + 保留 7 天。

**Q-04（不阻断）** 慢查询不可观测（`slow_query_log=OFF`），开启需重启 MySQL。**建议本项只如实显示「OFF」**。

**Q-05（需要你给）** 告警邮件收件地址。默认用线上 SMTP 发件人自己给自己发（`3153566913@qq.com`）。

