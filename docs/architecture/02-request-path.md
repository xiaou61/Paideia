# 请求链路

这份图回答"一个请求进来之后，会经过哪些地方"。它属于**叙述性**的图——答案在代码语义里，抽取不出来，所以由人/AI 撰写，不生成。

## 普通请求的时序

```mermaid
sequenceDiagram
  autonumber
  participant B as 浏览器 / Tauri 桌面壳
  participant F as TraceIdFilter
  participant S as Spring Security
  participant C as 模块 Controller
  participant M as 模块 Service
  participant DB as MySQL

  B->>F: HTTP 请求（带 Bearer JWT）
  F->>F: 生成 / 透传 traceId，写进 MDC
  F->>S: 继续过滤链
  S->>S: 解析 JWT → 认证上下文
  alt 路径不匹配任何放行规则且未登录
    S-->>B: 401
  else 已登录 / 命中 permitAll
    S->>C: 进入 Controller
    C->>M: 调用本模块 Service（只经 API 端口跨模块）
    M->>DB: 手写 MyBatis
    DB-->>M: 行
    M-->>C: 领域对象
    C-->>B: ApiResponseBodyAdvice 统一包一层
  end
```

**过滤顺序要点**：`TraceIdFilter` 是 `HIGHEST_PRECEDENCE`，所以它比安全链更早，认证失败的那条路径也带 traceId。安全规则按**声明顺序**取第一个匹配项——把 `/admin/**` 的 `hasRole('ADMIN')` 写在宽的 `/api/**` 规则之后，它**永不生效且不报错**，这是本仓库最容易被静默破坏的一处。

## 模块之间的调用规则

跨模块只能走对方的 **API 端口**（模块包根目录下的接口，如 `KnowledgeApi` / `AccountApi` / `NoteApi`），不许碰 `internal` 下的实现。`spring-modulith` 在构建期校验这条：跨模块访问内部实现或出现循环依赖会直接让构建失败。

```mermaid
flowchart LR
  subgraph 模块 A
    ctrlA["Controller"] --> svcA["Service"]
    svcA --> portA["本模块 API 端口"]
  end
  subgraph 模块 B
    portB["B 的 API 端口"] --> implB["internal 实现"]
  end
  svcA -- "只允许这一条" --> portB
  svcA -. "禁止：直接引用 internal" .-> implB
```

## AI 流式回答的时序

这是全仓唯一一条**不按"请求-响应"收尾**的链路：由 `SseEmitter` 持有连接，上游增量逐个推下去。

```mermaid
sequenceDiagram
  autonumber
  participant FE as 前端助手面板
  participant AC as AssistantConversationController
  participant CS as ConversationService
  participant CR as CreditService
  participant UP as 上游模型（DeepSeek）
  participant DB as MySQL

  FE->>AC: POST /api/v1/ai/conversations/{id}/messages（Accept: text/event-stream）
  AC->>CS: prepare（先落用户消息）
  Note over CS: prepare 刻意不带事务：<br/>预留是最后一个可能抛的动作，<br/>否则被积分闸拦下时用户消息会被回滚丢掉
  CS->>CR: reserve（余额不足 → 402，一个上游请求都不发）
  CS->>DB: 写用户消息
  CS-->>AC: 本轮上下文
  AC->>UP: 流式请求（带工具定义）
  loop 上游增量
    UP-->>AC: token / 思考 / 工具调用
    AC-->>FE: SSE event（text / reasoning / tool ...）
  end
  Note over AC,UP: 有工具调用就再跑一轮，<br/>但积分只结算一次总量
  AC->>CR: settle（预扣 → 按实际用量退补）
  CR->>DB: 写积分流水
  AC->>DB: 写助手消息
  AC-->>FE: done
```

三处容易画错、实际必须按这里画的点：

- 预留必须在写用户消息**之后**、且 `prepare` 不能包事务。反过来的话，被 402 拦下的那一轮里用户的消息会随事务回滚消失，而界面因为只有本地乐观状态，看不出来——刷新才丢。
- 工具循环里**每一轮都是真实上游调用**，但账只结一次。曾经只上报最后一轮的用量，实测对不上账。
- 思考内容（reasoning）不能进对话历史，只用于展示。
