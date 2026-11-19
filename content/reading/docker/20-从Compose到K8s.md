---
title: 从 Compose 到 K8s：什么时候该上编排
category: docker
order: 20
minutes: 16
level: intermediate
status: published
---

# 从 Compose 到 K8s：什么时候该上编排

## 内存吃到 91% 的那个下午

```text
$ docker compose ps
NAME    SERVICE   STATUS         PORTS
pg      db        Up 6 days
redis   cache     Up 6 days
api     api       Up 6 days      0.0.0.0:8080->8080/tcp

$ docker stats --no-stream
NAME    CPU %   MEM USAGE / LIMIT
api     3.21%   1.82GiB / 2GiB
pg      0.31%   412MiB / 2GiB
```

三行 `Up 6 days`，一次没重启过；api 的内存贴着 2G 顶格。这时候群里有人说了句"上 K8s 吧"。

我当时差点就答应了。后来把问题拆开看，发现这个现象跟 K8s 一点关系都没有——**内存贴着顶格是 JVM 堆没设、或者有泄漏，换编排系统只会让它贴着新机器的顶格**。

这篇讲的就是怎么区分"该上编排"和"该修别的东西"。标准不是"够不够先进"，是**你的痛属于哪一类**。

## 编排只解决三件事

多机调度、自动恢复、声明式发布。就这三件。

- **多机调度**：容器该放到哪台机器上，挂了以后谁接；
- **自动恢复**：进程死了、节点整台宕了，服务能不能自己回来；
- **声明式发布**：写"我要 3 个实例跑 v2"，系统负责滚到那个状态，过程中不断流。

这三件都不痛的时候上 K8s，你得到的是一套要自己维护的分布式系统，加上一堆本来不存在的新故障类型。

## Compose 的能力边界（这次逐条核实过）

网上流传的对照表有几条是错的，先把准确的版本放这：

| 需求 | Compose 真实情况 |
| --- | --- |
| 跨多台机器调度 | ✗ 它只管本机 Docker |
| 容器进程挂了重启 | ✓ `restart: always` / `unless-stopped`，这个够用 |
| 整台机器宕了 | ✗ 没人接手，得靠外部的负载均衡 + 另一台机器 |
| 手动多副本 | ✓ `docker compose up --scale api=3` 可以，但三个副本全在同一台机器上 |
| 按负载自动扩缩 | ✗ 没有这个机制 |
| 服务发现（同机内） | ✓ 服务名能当 DNS 用，Docker 内置 DNS 会在副本间轮询 |
| 跨机负载均衡 | ✗ 这才是缺的那一块 |
| 滚动更新不断流 | 半 —— `up -d` 会重建容器，但没有"新版本就绪再切流量"的编排 |
| 健康检查 | ✓ `healthcheck` 有，但它只标记 unhealthy，不会摘流 |

三处更正值得记一下，因为它们影响判断：

**一、"Compose 没有副本数概念"** —— 有，`--scale` 就能起多份。它缺的是**自动**扩缩和**跨机**分布。

**二、"Compose 没有服务发现与负载均衡"** —— 同机内它是有的：`curl http://api:8080` 能解析到多个副本并轮询。缺的是跨机器那一层。

**三、"健康检查只是容器内的自检"** —— 对，而且这一点很关键：Compose 的 `healthcheck` 让容器显示 `unhealthy`，但**它不会把你从任何地方摘掉**，因为你本来也没有"端点列表"这种东西。要摘流得靠前面的 nginx 主动探测。

## K8s 的概念替换关系

| K8s 概念 | Compose 里对应什么 | 多了什么 |
| --- | --- | --- |
| `Deployment` | service 定义 + `restart` | 期望副本数、滚动更新策略、跨节点自愈 |
| `Service` | 服务名 + 端口 | 稳定的虚拟 IP / DNS，跨节点负载均衡 |
| `ConfigMap` / `Secret` | `env_file`、挂载的文件 | 与镜像解耦、按 namespace 隔离、可挂卷 |
| `Probe` | `healthcheck` | **readiness 决定给不给流量**，liveness 决定要不要重启 |
| `Ingress` | 前面的 nginx 容器 | 声明式路由、证书、按 host/path 分流 |

### ConfigMap 的"热更新"没那么热

我在上一版里写过"ConfigMap/Secret 可热更"。**只有一种挂载方式会更新，而且有延迟。**

| 用法 | 改了 ConfigMap 之后 |
| --- | --- |
| 以 volume 挂载成文件 | 会更新，但延迟到 kubelet 的同步周期（默认约 1 分钟，加上缓存可能更久） |
| `env` / `envFrom` 注入环境变量 | **永远不会变**，必须重建 Pod |
| `subPath` 挂载的单个文件 | **永远不会变** |
| `immutable: true` 的 ConfigMap | 改了也不会传播 |

即使文件更新了，**你的应用也得自己重新读**——Spring Boot 不会因为 `application.yml` 变了就重新加载配置。所以"用 ConfigMap 做热更新"实际上需要：挂 volume + 应用侧监听文件变化（或定时重读）+ 避开 subPath。这三件凑齐才算热更。

### readiness 该查什么，这是个容易写错的地方

`readinessProbe` 失败 → 从 Service 的端点里摘掉，不再给流量，**容器不重启**。
`livenessProbe` 失败 → 杀容器重启。

我原来写"readiness 查依赖，liveness 只查我还活着"。**"查依赖"这个说法要小心。**

如果 readiness 里查数据库/Redis 能不能连，而数据库是一次抖动（比如主从切换 10 秒），会发生什么？**所有 Pod 的 readiness 同时失败 → 一起被摘流 → 服务 100% 不可用。** 本来只是"慢一点"，变成"全站 502"。

正确做法是：**readiness 只回答"我这个实例现在能不能服务"，不回答"我的依赖健不健康"。** 依赖挂了应该由应用自己降级处理，而不是让编排器把全部实例一起摘掉。

Spring Boot 的 actuator 正好把这件事分成了两组，`/actuator/health/liveness` 和 `/actuator/health/readiness`。**往 readiness 里加自定义检查要有节制**——加一个"依赖数据库"进去，就是把单点故障放大成集群级故障。

## 一个最小可用清单

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: app
spec:
  replicas: 2
  selector:
    matchLabels: { app: app }
  template:
    metadata:
      labels: { app: app }
    spec:
      containers:
        - name: app
          image: registry.example.com/app@sha256:<64 位十六进制>
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
            failureThreshold: 3
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 30
            periodSeconds: 10
          resources:
            requests: { cpu: "250m", memory: "512Mi" }
            limits:   { cpu: "1",    memory: "1Gi" }
---
apiVersion: v1
kind: Service
metadata:
  name: app
spec:
  selector: { app: app }
  ports:
    - port: 80
      targetPort: 8080
```

三个细节决定它能不能活：

1. **`requests` 必须写**。调度器靠它选节点；不写等于声明"我不占资源"，超卖之后互相抢占，表现是所有 Pod 一起抖动。
2. **`limits` 与 JVM 联动**。`limits.memory` 会给容器设 cgroup 上限，JVM 的 `MaxRAMPercentage` 会读它。不设或设得比堆小，就是 OOMKilled——而 OOMKilled 的 Pod 事件里只会写 `Exit Code: 137`，看不出是 Java 堆的问题。
3. **`livenessProbe.initialDelaySeconds` 要够长**。Spring Boot 冷启动常见 20~40 秒，liveness 设 5 秒就开始探活，会得到一个完美的"起来就被杀、反复重启"循环。

顺便说 `image` 那个 digest：`@sha256:` 后面必须是 64 位十六进制，写 `@sha256:...` 或者 `@sha256:abc123` 都会被 API server 直接拒绝（`invalid reference format`）。用 digest 而不是 tag 的理由是 tag 可以被覆盖——同一个 tag 今天拉到的和上周拉到的可能不是同一个镜像。

## 什么时候**不**该上 K8s

- **一台机器、几个服务、日活几千**：Compose + `restart: unless-stopped` + nginx 就够，能稳定跑几年；
- **团队里没人愿意维护它**：K8s 的成本是持续的（版本升级每 4 个月一次、证书轮换、CNI 插件、存储类），不是一次性投入；
- **只是想要"挂了自己起来"**：`restart` 策略 + systemd 就能做到，不需要编排器。

上 K8s 的三个真实触发点，满足任一才值得考虑：

1. 单机资源真的不够，必须跨多台机器调度；
2. 需要零中断发布，且手工切流已经出过事故；
3. 多团队共用一个集群，需要资源配额与隔离。

### 迁移路径

```text
单机 docker run
  → docker compose（同机多服务，有依赖与健康检查）
    → compose + 多机（每台一份，前面挂负载均衡，手工同步配置）
      → 轻编排（Nomad / Swarm / ECS）
        → K8s（有明确的多机调度 + 自愈 + 发布会需求）
```

每一步都是"上一层的痛点真的出现了"才走下一步。跳级的人最后都在补上一层的课——最常见的是跳过"多机手工部署"，直接上 K8s，结果连"健康检查怎么写"都没搞清，只是把 Compose 的坑搬到了 YAML 里。

## 动手

回到开头那个下午，用三条命令判断该不该上：

```text
$ docker compose ps                  # 几个服务、跑了多久、重启过没
$ docker compose config              # restart / healthcheck / 日志上限写全了吗
$ docker stats --no-stream           # 单机 CPU、内存的真实水平
```

判断方法：

- `docker stats` 里 CPU 常年低于 30%、内存有富余、服务个数个位数 → **留在 Compose**，先把 `restart`、`healthcheck`、日志上限（`logging.options.max-size`）配全；
- 有服务**贴着内存或 CPU 上限** → 那是容量或泄漏问题，换编排系统不解决；
- 需要第二台机器才能继续扩容 → 这时候才轮到编排。

开头那个 api 的例子，正确处置是先把 `-Xmx` 或 `MaxRAMPercentage` 调到 `limits` 的 70~75%，再观察一周。**如果内存还在涨，那就是泄漏**，跟 K8s 没关系。

## 检查一下

```quiz
{
  "id": "docker-20-quiz",
  "title": "编排三问",
  "questions": [
    {
      "question": "readinessProbe 失败会怎样？",
      "options": ["从 Service 端点摘掉、不再给流量，容器不重启","重启容器","删掉整个 Pod"],
      "answer": 0,
      "explain": "readiness 只影响是否接收流量。把「依赖能不能连上」写进 readiness 会让一次数据库抖动把全部 Pod 一起摘掉，单点故障变成集群级故障。"
    },
    {
      "question": "下列哪种情况最需要 K8s？",
      "options": ["需要跨多台机器调度，并且要零中断发布","单机跑三个服务，想把它们管起来","想要容器挂了自动重启"],
      "answer": 0,
      "explain": "自动重启用 restart 策略就行；跨机调度 + 滚动发布才是编排的核心价值。"
    },
    {
      "question": "resources.requests 不写会有什么后果？",
      "options": ["调度器按「不占资源」安排，节点超卖后互相抢占","Pod 起不来","镜像拉不下来"],
      "answer": 0,
      "explain": "requests 是调度依据，limits 是运行时上限。不写 requests 等于声明不需要资源，多个这样的 Pod 挤在一起就会互相拖死。"
    }
  ]
}
```

## 三件我原来写错的事

**一、"Compose 没有副本数和负载均衡"。** 它有：`--scale` 起多副本、服务名做 DNS 并在副本间轮询。缺的是**跨机**和**自动**。

**二、"ConfigMap/Secret 可热更新"。** 只有 volume 挂载会更新（还要等 kubelet 同步），`env`、`envFrom`、`subPath` 三种用法永远不变，得重建 Pod。而且应用自己还得重新读文件。

**三、"readiness 查依赖更保险"。** 正相反。依赖抖动时所有实例一起被摘流，比不查更糟。readiness 只该回答"我这个实例能不能服务"。

## 这一课之后

Compose 与 K8s 的分界线是**多机、自愈、零中断发布**这三件事。三件都不痛，就把 Compose 的基础配全——`restart`、`healthcheck`、日志上限、资源上限；痛了再按 `compose → 多机 → 轻编排 → K8s` 一步步走。

上 K8s 之后最该先学会的不是 Deployment 怎么写，而是 `readiness` 与 `liveness` 的分工、`requests/limits` 必须写、以及 `initialDelaySeconds` 要给冷启动留够时间——这三处写错，比不会写 YAML 更容易出事故。
