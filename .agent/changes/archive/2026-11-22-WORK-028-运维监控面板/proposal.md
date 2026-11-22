---
artifact: proposal
work_id: WORK-028
work: 运维监控面板
status: approved
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 15:42:28 +0800
approver_role: CTO
---

# 运维监控面板提案

## 摘要

在 codenest-online 上装 **Prometheus + Grafana**，配四个轻量 exporter（主机 / MySQL / Redis / 黑盒探活），应用另开一个回环管理端口输出 Prometheus 格式；systemd 单元、Docker 容器与**每日备份状态**由一个定时脚本写 textfile 供采集。Grafana 不开公网端口，由 `paideia` 以 `hasRole("ADMIN")` 反向代理到管理端 iframe。

交付后管理员在管理端一个页面里就能看到「现在怎么了、过去三天怎么变的」，并有四条邮件告警；同时补上项目至今**完全缺失的数据库每日备份**与一次真实恢复演练。

## 推荐方案

### 为什么是这条路

用户点名要看的是 MySQL、Redis、Laya 与「各种服务监控指标」。这个清单直接排除了两个备选：

- **Spring Boot Admin** 是 Spring 应用监视器，它的采集模型建立在「被监控方也跑 Spring 且能上报 actuator」之上。MySQL 服务端状态、Redis、Laya（Python 进程）、Docker 容器、systemd 单元**没有一个是 Spring 应用**，五项里占五项。
- **自建只读页**能覆盖全部，但要自己写趋势存储、自己写告警、自己写每个中间件的采集。它省下的是两个进程，付出的是把「时序数据库 + 告警引擎 + 面板」三件事各实现一遍。

Prometheus 生态恰好把这五类目标都变成「一个 exporter 或一个 textfile」：`mysqld_exporter` 直读 MySQL 的 `SHOW GLOBAL STATUS`，`redis_exporter` 读 `INFO`，`blackbox_exporter` 打 HTTP 探活，`node_exporter` 读主机并把 textfile 目录一起暴露。**用户要的每一项都有一等公民支持，这是选 C 的实质理由。**

### 关键机制

1. **应用侧**：加 `micrometer-registry-prometheus`（版本由 Boot BOM 管），`management.server.port=8082` + `address=127.0.0.1`，`management.endpoints.web.exposure.include` 加 `prometheus`。Micrometer 已自动为 Boot 的 `applicationTaskExecutor` 与 HikariCP 注册指标，REQ-001..003 因此**几乎零代码**。
2. **主机与中间件**：四个 exporter 各一个 systemd 单元，全部绑回环。
3. **服务与容器**：`ops-textfile.timer` 每分钟跑一次脚本，`systemctl show` 读白名单单元的 `ActiveState`/`MemoryCurrent`/`MemoryMax`，`docker ps` 读容器状态与重启计数，写 `*.prom`；`node_exporter` 用 `--collector.textfile.directory` 暴露。
4. **备份**：`paideia-backup.timer` 每日跑 `mysqldump | gzip`，保留 7 天；成功/失败/耗时写同一个 textfile，于是「备份陈旧」是一条能告警的指标。
5. **面板**：Grafana 数据源与 dashboard 都用 provisioning 文件，四块趋势图 + 七类摘要。
6. **入口**：管理端新增「运维」页，摘要卡片由应用新增的 `/api/v1/ops/admin/summary` 提供（它直接读 Prometheus 的 instant query 并做四态判定），完整面板用 iframe 嵌同一个源的代理路径。

### 为什么摘要要由 Java 算而不是纯 Grafana

四态判定（正常 / 警告 / 异常 / 未知）与「一句人话原因」是**产品逻辑**，写在 Grafana 的 threshold 里就等于把它锁在面板 JSON 中：前端拿不到、测试测不到、改阈值要改 JSON。放一条 `GET /api/v1/ops/admin/summary` 之后，它就和其他管理端接口一样可测、可断言，且「未知」这个第四态有明确落点（Grafana 里表达「数据缺失」很别扭）。

## 范围

**包含**：八个 systemd 单元（prometheus / grafana / 四个 exporter / 两个 timer）、一个回环管理端口、一个运维管理端页面与一条摘要接口、一个 Grafana 代理路径、七类指标采集、四条告警、每日备份与一次恢复演练、D-15 更新。

**不包含**：进程启停动作、日志检索、多机聚合、异地备份、指标长期归档、cAdvisor、Alertmanager、对外状态页。

## 备选方案

### A 自建只读采集 + 管理端一页

**最强的理由**：零新增常驻进程；所有判定逻辑都在 Java 里，可测；不引入第二个 Web UI 与第二套配置语言；不依赖代理下载外部制品。对一台 4 核机来说这是最保守的选择。

**未选的原因**：要自己实现趋势存储（用户明确要历史）、自己实现告警、自己为 MySQL/Redis 各写采集。这三件加起来远超「装两个进程 + 四个 exporter」的工作量，且将来每加一个中间件都要写代码。用户看过覆盖范围对比后选 C。

**放弃的东西**：不新增依赖的简洁性、单一语言栈、以及「一个仓库装得下全部逻辑」。

### B Spring Boot Admin

**最强的理由**：现成 UI，Java 侧几乎零代码，能改日志级别、看线程 dump，对纯 Spring 应用的排障体验优于 Grafana。

**未选的原因**：覆盖范围与用户点名的清单几乎不重叠（见上）。它能看的（JVM、HTTP、连接池）恰好是 Prometheus 也能看的，而它看不到的正是用户最想看的。

**放弃的东西**：无。若将来只想看 Spring 内部状态，它仍可作为补充。

## 仓库影响

| 区域 | 影响 |
| --- | --- |
| `backend/paideia-app/pom.xml` | 新增 `micrometer-registry-prometheus`（BOM 管版本） |
| `backend/paideia-app/src/main/resources/application.yml` | 管理端口、`prometheus` 端点、阈值配置项 |
| `backend/paideia-app/src/main/java/.../ops/` | 新增摘要接口、Prometheus 客户端、四态判定（**新包，需评估放在 app 还是新模块**，见设计 T-02） |
| `backend/paideia-security/.../SecurityConfiguration.java` | 新增 `/api/v1/ops/admin/**` 与 Grafana 代理前缀两条规则，**位置在更宽规则之前** |
| `frontend/apps/admin/src/pages/OpsPage.tsx` + 路由 + 侧栏 | 新页面与入口 |
| `scripts/` 与新增 `deploy/ops/` | systemd 单元、exporter 配置、采集脚本、备份脚本、provisioning 文件 |
| `scripts/release.ps1` | 需复核健康检查未受管理端口影响（不改逻辑） |
| `.agent/rules/always.md` | D-15 与「服务器环境」表更新 |
| 数据库 | **零迁移** |

## 交付拆分

1. **可观测地基**：管理端口 + Prometheus registry + exporter + Prometheus + Grafana（能出图）。
2. **服务与容器与备份**：textfile 采集脚本 + 备份 timer + 三条 systemd timer/unit。
3. **告警**：四条 alert rule + 邮件通道（凭据从服务器侧注入）。
4. **应用入口**：`/api/v1/ops/admin/summary` + 代理路径 + 管理端页面。
5. **验证与文档**：恢复演练、AC 逐条、`always.md` 更新、更新历史。

## 风险与缓解措施

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 直连不可达导致安装卡住（已实测） | 实施停摆，表现为无报错等待 | 所有安装命令带 `http_proxy=127.0.0.1:7890`；先跑 `manifest inspect` 验证 |
| 4 核机新增 6 个单元 | 与 Laya / Firecrawl 抢资源 | 每个单元设 `MemoryMax`；实测内存占用写进报告；Grafana 与 Prometheus 都是单进程无 JVM |
| Grafana 匿名 Viewer 被误开公网 | 面板数据泄露 | Grafana `http_addr=127.0.0.1`；`ss -ltn` 验收检查；代理路径 `hasRole("ADMIN")` |
| 反向代理与 Grafana 子路径不匹配 | 面板白屏 | `serve_from_sub_path=true` + `root_url` 带前缀；验收时实测 iframe 加载 |
| 管理端口改动影响部署脚本 | 部署后健康检查失败 | `release.ps1` 不改，只复核；AC-013 专门验 |
| 备份脚本把口令写进日志 | 凭据泄露到 journald | 用 600 权限的 `--defaults-extra-file`，脚本不 echo 口令 |
| 磁盘保留期估算偏差 | 磁盘被吃 | 3 天 + 15 秒起步，实测后可在报告里下调；AC 记录实测增量 |

## 验收映射

| REQ | 落在哪一步 | AC |
| --- | --- | --- |
| REQ-001..005 | 交付 1 | AC-003、AC-013 |
| REQ-006..008 | 交付 1 | AC-009 |
| REQ-009..012 | 交付 2 | AC-002、AC-011 |
| REQ-013..015 | 交付 1 | AC-009 |
| REQ-016..018 | 交付 2、4 | AC-001、AC-018 相关断言 |
| REQ-019..023 | 交付 2 | AC-006、AC-007 |
| REQ-024..026 | 交付 1 | AC-009 |
| REQ-027..030 | 交付 4 | AC-001、AC-004 |
| REQ-031..033 | 交付 3 | AC-007、AC-008 |
| REQ-034..037 | 交付 1、4 | AC-003、AC-010、AC-011 |

## 待决定事项

- **D-1** Grafana 的访问形态（I-07 vs 开公网 3000 + 自带登录）——默认按 I-07。
- **D-2** 告警收件地址（Q-05）。
- **D-3** 备份是否加异地（Q-03）——本期不做。
