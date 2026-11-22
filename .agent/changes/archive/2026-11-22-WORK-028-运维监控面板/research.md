# 调研 · 运维监控面板

本文件保存 WORK-028 的实测证据与候选分析，供复查用；需求与验收在 [requirements.md](requirements.md)。

## 一、服务器现状实测（2026-10-20，codenest-online，只读）

### 1.1 资源

```
4 核 / 15992 MB（已用 7014 / 可用 8978）/ swap 3914MB 全部空闲
磁盘 /dev/sda2  79G  用 25G  剩 51G  33%
```

### 1.2 在跑的服务（systemd，全部 active）

`paideia` `laya` `rustfs` `mysql` `redis-server` `mihomo` `docker` `fail2ban`

`paideia.service` 的关键行（`systemctl cat paideia`）：

```ini
[Service]
Type=simple
WorkingDirectory=/opt/paideia-app
ExecStart=/opt/jdk25/bin/java -jar /opt/paideia-app/app.jar --spring.config.additional-location=file:/opt/paideia-app/
Restart=on-failure
RestartSec=5
```

**没有 `User=`，因此以 root 运行**，JVM RSS 435 MB。这是「应用能执行 `systemctl` 与 `docker`」的前提，也是本项能不加 agent 就拿到服务状态的原因。

`laya.service` 的 cgroup 读数：

```
MemoryCurrent=2742591488   (2.55 GiB)
MemoryMax=3221225472       (3 GiB)   => 85%
ActiveEnterTimestamp=Tue 2026-10-20 13:59:01 CST
```

### 1.3 端口占用

| 端口 | 占用者 | 是否可给本项 |
| --- | --- | --- |
| 8081 | `paideia`（`[::]`，公网可达） | 已有，不改 |
| 8091 | `laya-serve`（回环） | 已有 |
| 6379 | `redis-server`（回环） | 已有 |
| 3306 / 33060 | `mysqld` | 已有 |
| 9000 | `rustfs`（`0.0.0.0`） | 已有 |
| 3002 / 3003 | firecrawl api / nginx 反代 | 已有 |
| **9091** | **`mihomo`** | **不可用**——计划里的 pushgateway 默认端口撞车，本项不需要 pushgateway |
| **3000 / 9090 / 9100 / 9104 / 9121 / 9115** | **空闲** | 计划使用 |

### 1.4 安装可行性（这是本项最容易卡住的一步）

直连与代理两组对照：

| 目标 | 直连 | 带 `http_proxy=127.0.0.1:7890` |
| --- | --- | --- |
| `docker manifest inspect prom/prometheus:v2.54.1` | 失败 | **ok** |
| `docker manifest inspect grafana/grafana:11.3.0` | 失败 | **ok** |
| `https://packages.grafana.com/oss/deb/dists/stable/Release` | — | **200** |
| `https://github.com/prometheus/node_exporter/releases/latest` | — | **302** |

apt 源现状：

```
prometheus:  候选 2.45.3+ds-2ubuntu0.3   (noble/universe，来自 mirrors-internal.cmecloud.cn)
grafana:     不在 apt 源，需要加官方源
```

结论：**systemd 单元里必须显式带代理环境变量**，否则表现为「下载卡住、无报错」。

### 1.5 Docker

```
7 容器全 Up：firecrawl-api-1 / firecrawl-searxng / firecrawl-auth-proxy-1 /
             firecrawl-playwright-service-1 / firecrawl-redis-1 /
             firecrawl-rabbitmq-1 (healthy) / firecrawl-nuq-postgres-1 (healthy)

Images          10   8.346GB   可回收 6.176GB (74%)
Containers       7   20.33MB   可回收 0B
Local Volumes    6   343.8MB   可回收 210.1MB (61%)
```

磁盘构成：`/var/lib/docker` 4.9G、`/opt/laya-test` 1.8G、`/var/lib/mysql` 378M、`/opt/paideia-app` 183M。

**已知缺陷（既有，非本项引入）**：七个容器里只有 searxng 是 `unless-stopped`，其余六个是 `restart: "no"`——整机重启后全停，必须手动 `docker compose up -d`。REQ-011 的重启次数指标正是让这件事可见。

### 1.6 MySQL

```
version 8.0.46-0ubuntu0.24.04.4
max_connections   151
Threads_connected  11      Threads_running 2
slow_query_log    OFF     long_query_time 10.000000
Uptime            5079s
```

库体积（`information_schema`）：

```
paideia 全库 2.2 MB
refresh_tokens          0.3 MB / 731 行
knowledge_entries       0.3 MB /  35 行
ai_runs                 0.1 MB /   3 行
learning_track_stages   0.1 MB /  40 行
```

`slow_query_log = OFF` 意味着「某个查询慢」今天完全无法被观测，且这个开关改不了（要改服务端变量 + 重启）。

### 1.7 备份现状

```
crontab -l            => no crontab for root
ls /etc/cron.d/       => e2scrub_all  sysstat   （无备份任务）
仓库 scripts/         => 无任何 backup 相关脚本
```

**结论：零备份。** 库只有 2.2MB，一次全量 dump 压缩后几十 KB——这是本轮扫描里性价比最高的修复。

### 1.8 Actuator 现状

```
GET http://127.0.0.1:8081/actuator/health   => 200 {"groups":["liveness","readiness"],"status":"UP"}
GET http://127.0.0.1:8081/actuator/metrics  => 401
management.endpoints.web.exposure.include   => health,info,metrics
```

metrics 已启用但被 Security 的 `anyRequest().authenticated()` 挡住。本项**不**把它开给公网，而是换一个回环管理端口（见 requirements I-05）。

注意：`scripts/release.ps1` 的部署后健康检查读的正是 `8081/actuator/health`，改管理端口时必须确认它仍通过。

### 1.9 依赖可用性（决定是否新增依赖）

`paideia-app/pom.xml` 有 `spring-boot-starter-actuator`；Boot 4.1.1 的这个 starter 传递依赖 **`spring-boot-starter-micrometer-metrics`**，因此 Micrometer 与 `MeterRegistry` 已在 classpath。`micrometer-registry-prometheus` 的版本由 Boot BOM（micrometer 1.17.1）管理，本地 `~/.m2` 已有该版本。

**所以「应用暴露 Prometheus 格式」的增量只有一条 dependency + 一行配置。**

## 二、路线对比（用户已裁 C）

| | A 自建只读页 | B Spring Boot Admin | **C Prometheus + Grafana（已选）** |
| --- | --- | --- | --- |
| 覆盖 JVM | 是 | 是 | 是 |
| 覆盖 MySQL / Redis | 要自己写 JDBC / RESP | 否 | 是（exporter） |
| 覆盖 Laya / RustFS / Firecrawl | 要自己写探活 | 否 | 是（blackbox） |
| 覆盖 systemd / Docker | 要自己起子进程 | 否 | 是（textfile 脚本） |
| 历史趋势 | 要自己建表 + 清理 | 有限 | 是（TSDB） |
| 告警 | 要自己写 | 有限 | 是（Grafana unified alerting） |
| 新增常驻进程 | 0 | 1 | 2 重（prom + grafana）+ 4 轻（exporter） |
| 新增依赖 | 0 | 1 | 0（Java 侧仅加 registry） |
| 面板维护成本 | 自己写页面 | 低 | 另一套配置语言 |

用户看过上表后选 C，明确接受多进程与「另一套配置语言」的代价。

## 三、扩展点逐条评估

### 3.1 做（有明确痛点）

| 候选 | 痛点来源 | 落在哪个 REQ |
| --- | --- | --- |
| 线程池队列与拒绝数 | 队列只有 8，满了 429，服务端零计数 | REQ-002 |
| Laya 内存贴顶 | 2.55G/3G = 85%，OOM 后静默降级 | REQ-010 / 013 / 031 |
| Firecrawl 容器与反代 502 | 已真实发生过一次，靠用户发现 | REQ-011 / 015 |
| 磁盘与可回收镜像 | 6.2G 可回收，看不见就不回收 | REQ-012 / 031 |
| 定时任务结果 | `EssayCollectionJob` 异常被吞，失败只有一行日志 | REQ-016 / 018 |
| 关键开关值 | 「为什么没新内容」一半是开关关着 | REQ-017 |
| 备份新鲜度 | 零备份 | REQ-019..023 |

### 3.2 C 路线白送的

- **磁盘增长预测**：`predict_linear(node_filesystem_avail_bytes[6h], 86400*7)` 一句 PromQL。
- **内存泄漏趋势**：长期曲线直接看得出斜率。
- **错误率与延迟分位**：`http_server_requests` 自带。
- **重启次数与可用率**：`up` 指标 + Grafana 内置。

### 3.3 明确不做

| 候选 | 不做的理由 |
| --- | --- |
| 证书 / 域名到期 | 项目走明文 HTTP、无域名，没有证书 |
| 日志聚合检索 | journald 已有；接 Loki 是另一个体量（还要对象存储与索引） |
| 进程动作（重启/清理） | 明文 HTTP 下后台口令是唯一门槛，误点即全站宕机 |
| 对外状态页 | 没有外部用户规模，也没有 SLA 对象 |
| Alertmanager | Grafana unified alerting 够用；升级条件写进 requirements I-06 |
| cAdvisor | 第三个常驻守护进程、数百 MB，而只需要「容器在不在」 |
| pushgateway | 本项没有短生命周期批处理作业（备份用 systemd timer，直接写 textfile） |

### 3.4 值得单独记一笔的

- **Redis 装着、开着、零引用、零键**：`DBSIZE = 0`，`backend/**/pom.xml` 与 `application.yml` 里没有任何 Redis 依赖或配置。它现在唯一的作用是占一个进程和一点内存。建议面板如实标注「未被应用使用」，另议「用起来还是删掉」。
- **慢查询不可观测**：`slow_query_log=OFF`。开启要改服务端变量、重启 MySQL。本项只显示「OFF」，开启留作独立动作。
- **异地备份缺失**：RustFS 与 MySQL 在同一台机，同机备份救不了机器整体损坏。需要第二台机或外部对象存储，属另一件事。

## 四、内存与磁盘预算（估算，实施后核对）

| 组件 | 常驻内存（估） | 磁盘（3 天） |
| --- | --- | --- |
| prometheus | 100–200 MB | 100 MB 量级 |
| grafana | 80–150 MB | 少量（dashboard 与 sqlite） |
| node_exporter | 20 MB | 0 |
| mysqld_exporter | 20 MB | 0 |
| redis_exporter | 15 MB | 0 |
| blackbox_exporter | 20 MB | 0 |
| **合计** | **约 250–400 MB** | **约 100 MB** |

机器当前可用 8.9 GB，51 GB 磁盘空闲——放得下。但与 Laya 的 2.55G / 3G 相比，本项的实际价值恰恰是「让那 85% 被看见」。实施后以 `systemd-dm` 与 `du` 实测值替换本表。
