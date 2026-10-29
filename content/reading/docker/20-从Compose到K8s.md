---
title: 从 Compose 到 K8s：什么时候该上编排
category: docker
order: 20
minutes: 26
level: intermediate
status: published
---

## 这一关在解决什么

一台机器上 Compose 跑得好好的，业务一涨：第二台机器怎么加？容器挂了谁来重启？
滚动更新怎么做？于是有人建议"上 K8s"——然后三个月过去了，服务还没跑起来。

**结论先说**：编排解决的是**多机调度 + 自动恢复 + 声明式发布**。这三件都不痛的时候，
K8s 只会给你增加一套要维护的分布式系统。判断标准是**痛的哪一种**，不是"够不够先进"。

## 讲解

### Compose 的能力边界

Compose 能做的：一台机器上定义多个服务、网络、卷、依赖顺序、健康检查、`up/down/logs`。
它做得很好，也很轻。

Compose 做不到的：

| 需求 | Compose | 为什么 |
| --- | --- | --- |
| 跨多台机器调度 | ✗ | 它只知道本机 Docker |
| 容器挂了自动重建 | 半（`restart: always` 能重启进程） | 但节点整体掉了没人管 |
| 滚动更新（不中断） | 半（`up -d` 会重建） | 没有"新版本就绪再切流量" |
| 按负载扩缩容 | ✗ | 没有副本数概念 |
| 服务发现与负载均衡 | ✗ | 只能靠容器名在本机内解析 |

### K8s 的核心不是"更多 YAML"，是三个概念

| K8s 概念 | 对应 Compose 的什么 | 多了什么 |
| --- | --- | --- |
| `Deployment` | service 定义 + `restart: always` | 期望副本数、滚动更新策略、自愈 |
| `Service` | 服务名 + 端口 | 稳定的虚拟 IP / DNS，跨节点负载均衡 |
| `ConfigMap` / `Secret` | `env_file` | 与镜像解耦、可热更、可做权限隔离 |
| `Probe`（liveness/readiness） | `healthcheck` | readiness 决定**要不要给流量**，liveness 决定**要不要重启** |
| `Ingress` | nginx 容器 + 反代 | 声明式路由与证书 |

**liveness vs readiness 是 K8s 最容易被忽略的一对**：

- `readinessProbe` 失败 → 从 Service 的端点里摘掉（不再给流量），但容器不重启；
- `livenessProbe` 失败 → 杀容器重启。

把数据库慢导致的超时写成 liveness，就会**越慢越重启、越重启越慢**——经典的雪崩写法。

### 一个最小可用清单

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: app
spec:
  replicas: 2
  selector: { matchLabels: { app: app } }
  template:
    metadata: { labels: { app: app } }
    spec:
      containers:
        - name: app
          image: registry.example.com/app@sha256:...   # 仍然钉 digest
          ports: [{ containerPort: 8080 }]
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 5
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 30
          resources:
            requests: { cpu: "250m", memory: "512Mi" }
            limits:   { cpu: "1",    memory: "1Gi" }
```

三个细节决定它能不能活：

1. **`requests` 必须写**：调度器靠它选节点；不写等于"随便塞"，最后互相挤死。
2. **`limits` 与 JVM 联动**：`MaxRAMPercentage` 会读 limits 的 memory（见第 16 关）。
3. **readiness 与 liveness 分开**：readiness 查依赖，liveness 只查"我还活着"。

### 什么时候**不**该上 K8s

- **一台机器、一个服务、日活几千**：Compose + `restart: always` 就够，再加个 nginx。
- **团队没人懂**：K8s 的运维成本是持续的（升级、证书、网络插件、存储类），不是一次性的。
- **只是想要"挂了自己起来"**：`restart: unless-stopped` + systemd 也能做到。

上 K8s 的三个**真实触发点**（满足任一才考虑）：

1. 单机资源不够，需要跨多台调度（且能接受调度失败）；
2. 需要零中断发布（滚动更新 + readiness 摘流）；
3. 多团队共享一个集群，需要资源配额与隔离。

### 迁移路径：别一步跳

```text
单机 docker run
  → docker compose（同机多服务，有依赖与健康检查）
    → compose + 多机（每台一份，前面挂负载均衡，手工同步配置）
      → 轻编排（Nomad / Swarm / ECS 之类）
        → K8s（有明确的多机调度 + 自愈 + 发布需求）
```

每一步都是"上一层的痛点真的出现了"才走下一步。**跳级的人最后都在补上一层的课。**

## 动手

在你的 Compose 文件里找出三件事，判断是否已经到了 K8s 的门槛：

```text
$ docker compose ps           # 现在跑着几个服务
$ docker compose config       # 检查 restart/healthcheck 有没有写
$ docker stats --no-stream    # 单机资源利用率
```

如果 `docker stats` 显示 CPU/内存常年低于 30%、服务数量个位数、你没有"跨机"需求——
**留在 Compose**，把 `restart`、`healthcheck`、`max-size` 配全，收益比上 K8s 大得多。

## 检查一下

```quiz
{"id":"docker-20-quiz","title":"编排三问","questions":[
 {"question":"readinessProbe 失败会怎样？","options":["从 Service 端点摘掉、不再给流量，容器不重启","重启容器","杀掉整个 Pod"],"answer":0,"explain":"readiness 只影响是否接收流量；把它和 liveness 混用（依赖慢就重启）会造成雪崩。"},
 {"question":"下列哪种情况最需要 K8s？","options":["需要跨多台机器调度并零中断发布","单机跑三个服务","想要容器挂了自动重启"],"answer":0,"explain":"自动重启可以用 restart 策略；跨机调度 + 滚动发布才是编排的核心价值。"},
 {"question":"resources.requests 不写会有什么后果？","options":["调度器按「无需求」塞节点，最后互相挤死","Pod 起不来","镜像拉不下来"],"answer":0,"explain":"requests 是调度依据；不写等于声明「我不占资源」，超卖后互相抢占就会集体抖动。"}
]}
```

## 常见误区

### 误区：K8s 是"容器化的下一步"，早晚要上

**你以为**：别人都上了，我们不上会落后。
**实情**：K8s 解决的是**多机与运维规模**问题。单机日活几千的服务上 K8s，
你会多出**一套要维护的分布式系统**，而没有任何一项痛点被解决。它也不是免费的：
控制面占用、网络插件、存储、证书、升级，全是持续投入。

### 误区：Compose 只是本地开发玩具

**你以为**：生产不能用 Compose。
**实情**：单机生产用 Compose + `restart: unless-stopped` + 日志上限 + 健康检查，
是非常常见且稳定的形态。很多团队在单机上跑了几年。**它的问题是"不能跨机"，不是"不能生产"。**

## 小结

Compose 与 K8s 的分界线是**多机、自愈、零中断发布**这三件事。三件都不痛，留在 Compose
把基础配全；痛了再按 `compose → 多机 → 轻编排 → K8s` 一步步走。上 K8s 之后最该先学会的是
`readiness` 与 `liveness` 的区别，以及 `requests/limits` 必须写——这两处写错，
比不会用 Deployment 更容易出事故。
