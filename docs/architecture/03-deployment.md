# 部署形态

这份图回答"东西都跑在哪、谁连谁"。**坐标与凭据一律不写在这里**——本仓库是公开仓库，服务器地址、口令、密钥只存在于被 `.gitignore` 忽略的本地配置和服务器上 600 权限的 env 文件里。

## 单机部署的拓扑

一个 jar 同时提供 API 与前端静态资源，管理端挂在 `/admin/` 路径下。公网只开一个应用端口。

```mermaid
flowchart TB
  subgraph 公网
    U["浏览器"]
    D["Tauri 2 桌面壳<br/>（同一套前端代码）"]
  end

  subgraph 服务器
    direction TB
    APP["paideia（systemd）<br/>单个 jar：REST API + 前端静态资源<br/>/ 学习者端，/admin/ 管理端"]

    subgraph 依赖服务
      DB[("MySQL 8<br/>库 paideia / paideia_test")]
      OBJ[("对象存储<br/>S3 兼容，预签名直读")]
      REDIS[("Redis<br/>未被应用使用，仅作探活对象")]
    end

    subgraph "运维面（全部绑回环，不开新公网端口）"
      PROM["Prometheus"]
      GRAF["Grafana<br/>经应用反代进管理端 iframe"]
      EXP["node / mysqld / redis exporter<br/>blackbox"]
    end
  end

  UPSTREAM["上游模型 API<br/>（平台持凭据，用户按积分消耗）"]

  U --> APP
  D --> APP
  APP --> DB
  APP --> OBJ
  APP --> UPSTREAM
  APP -. "反代" .-> GRAF
  PROM --> APP
  PROM --> EXP
  GRAF --> PROM
  EXP -. "探活" .-> APP
  EXP -. "探活" .-> OBJ
  EXP -. "探活" .-> REDIS
```

几个**读图时容易误判**的地方：

- **Redis 不是应用的依赖。**它跑着、被探活着，但应用一行 Redis 代码都没有（`OpsSummaryService` 里明写"未被应用使用"）。看到它在图上就以为要配 Redis，是常见误会。
- **RabbitMQ 还没上线。**`.agent/rules/always.md` 的 D-14 裁决了"引入 RabbitMQ + outbox 中继"，但代码里既没有 amqp 依赖也没有 outbox 表，所以这张图里没有它。**这是"已裁决 ≠ 已实现"的典型**——读规则时别把决策当成现状。
- **运维面没有独立公网端口。**Prometheus / Grafana / 各 exporter 全绑回环，Grafana 只能经应用的反代进管理端。所以"运维面板打不开"要先看应用是否活着。
- **对象存储的读路径是 302 预签名直读**，不经过应用转发。代价是每次新签名 URL 都会被浏览器当新资源，**吃掉一年的静态缓存**（折中：302 本身缓存 1 小时）。
- 附件读取与公开分享是**全项目仅有的免鉴权读端点**，安全性靠 UUID 不可枚举，不靠认证。改这两条路径前先想清楚这一点。

## 请求的信任边界

```mermaid
flowchart LR
  subgraph 匿名可访问
    A1["GET / 与 /admin 静态资源"]
    A2["/api/v1/auth/**（凭据交换）"]
    A3["GET 附件 / 公开分享（UUID 即凭据）"]
    A4["/actuator/health"]
  end
  subgraph 登录即可
    B1["/api/v1/ai/**"]
    B2["/api/v1/sandbox/**"]
    B3["其余 /api/**"]
  end
  subgraph 仅管理员
    C1["/api/v1/ai/admin/**"]
    C2["/api/v1/knowledge/admin/**"]
    C3["/api/v1/notes/admin/**"]
    C4["/api/v1/ops/admin/**"]
  end
  subgraph 仅本机来源
    D1["/actuator/metrics/**<br/>/actuator/prometheus"]
  end
```

规则按**声明顺序**取第一个匹配项，所以 `/admin/**` 这类 `hasRole('ADMIN')` 必须排在任何更宽的 `/api/**` 规则**之前**。顺序写反不会报错，只会在某天表现为"任何登录用户都能改知识库"。

## 构建与发布

```mermaid
flowchart LR
  W["工作区改动"] --> R["scripts/release.ps1"]
  R --> C1{"frontend/ 有改动？"}
  C1 -- 是 --> B1["构建前端"]
  C1 -- 否 --> SKIP["跳过（省约 80 秒）"]
  B1 --> P["打包成单个 jar"]
  SKIP --> P
  P --> DEP["部署到服务器并重启 systemd 单元"]
```

`release.ps1` 从**当前工作区**构建，所以带别人的未完成改动发版会把它们一起发出去——发版前先确认工作区干净。
