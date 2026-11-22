---
artifact: design
work_id: WORK-032
work: MySQL 实训沙箱与题库
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-015, WORK-020, WORK-024]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 18:06:32 +0800
approver_role: CTO
---

# 设计 · MySQL 实训沙箱与题库

## 实测基线（2026-10-21，服务器 codenest-online）

所有数字都是本机真跑出来的，不是估的。这些数字直接决定了下面每一个配置项。

| 测项 | 命令要点 | 实测结果 | 对设计的影响 |
| --- | --- | --- | --- |
| 镜像大小 | `docker images mysql:8.0` | 已拉取，digest `sha256:7dcddc01…` | 按 digest 钉住 |
| 裸容器启动 | `docker run --rm mysql:8.0 true` | **0.72–0.87 s** | 容器开销本身可忽略 |
| `mysqld --initialize` | 空 datadir 初始化 | **17.89 s** | **绝不能每次运行都初始化** |
| 初始化后 datadir | `du -sh` | **180 MB** | 镜像里带一份，运行时拷贝 |
| 预初始化后启动 | 拷好 datadir 直接起 | **4.36 / 4.45 / 4.61 / 4.71 s** | 这是用户每次要等的真实成本 |
| 常驻内存 | `docker stats`（512m 上限） | **348 MB**（空载与 500 行负载后几乎无差） | 512m 上限合理，1G 多余 |
| 库层超时 | `SET SESSION max_execution_time=300` | 0.56 s 报 **3024** | 可用，但**不等于**墙钟硬杀 |
| 不设超时 | `max_execution_time=0` 跑九表笛卡尔积 | **259.5 s** 后才返回 10 亿行 | **必须有容器级硬杀**，否则一次运行能占满 CPU 数分钟 |
| `secure_file_priv` 默认 | `SELECT @@secure_file_priv` | `NULL`（不限） | 必须显式收紧 |
| `secure_file_priv` 指向不存在的路径 | 启动测试 | **mysqld 直接 Aborting** | 收紧时必须指向**已存在**的目录 |
| `local_infile` 默认 | `SELECT @@local_infile` | `0` | 默认安全，仍显式写死 |
| datadir 复用 | 同一 datadir 起两次 | 第二次 `CREATE DATABASE t` 报 **1007 已存在** | **每次运行必须用一份干净拷贝**，不能直接挂载镜像内 datadir |

最后一行是个真实脚印：第一次测试时我直接复用了同一个 datadir，第二次启动发现上次建的库还在。这证明「每次干净」不能靠"容器销毁"，因为 datadir 是挂进来的。

## 系统上下文

```text
学习者浏览器
  ├─ code-challenge (kind=sql-write / sql-fix)          ← 关卡内
  │     ├─ GET  /api/v1/sandbox/status  → { languages:[…,'sql'] }
  │     └─ POST /api/v1/sandbox/run     → { tables:[{columns,rows}], stdout, … }
  │           客户端按题目口径判题 → 上报分数 → learning_progress
  │
  └─ /practice/sql (自由练习)                            ← 独立页
        └─ 同两个端点，但不写任何进度

服务端
  └─ SandboxController
       ├─ SandboxQuotaService   （UTC 日切配额 + code_runs 审计）
       └─ DockerCodeRunner
            └─ docker run --rm … mysql:8.0@sha256:…
                 ├─ 拷 datadir 到 tmpfs
                 ├─ 起 mysqld（socket only，无网络）
                 ├─ 灌数据集 → 跑用户 SQL → 抓结构化结果
                 └─ 容器销毁（datadir 随之消失）
```

## 组件与职责

| 组件 | 职责 | 不能做 |
| --- | --- | --- |
| `SandboxProperties` | 语言白名单、镜像映射、资源上限 | 不解析 SQL、不判题 |
| `DockerCommand` | 构造命令行与容器内启动脚本（纯函数） | 不执行、不决策 |
| `DockerCodeRunner` | 起容器、传数据、收结果、硬杀 | 不认识题目、不知道正确答案 |
| `SqlResultCodec` | 解析 mysqld 的结构化输出为 `tables` | 不做判题归一化 |
| `SandboxQuotaService` | 配额与审计 | 按语言分档配额（SQL 20/天，其它 50/天） |
| 前端 `judgeSql` | 按题目口径比对结果集 | 不执行 SQL |
| `verify_course.py` | 用同一份语料验题库 | 不启动容器（只比对已有结果） |

## 请求或事件流程

### 一次 SQL 运行

```text
1. 客户端 POST /api/v1/sandbox/run
   { language:"sql", code:"SELECT …", dataset:"orders-v1", timeoutMs?:… }

2. 控制器
   a. language 白名单校验（sql 必须出现在配置的可用语言里）
   b. SandboxQuotaService.requireQuota(userId)   → 超额直接 429，不启动容器
   c. dataset 必须是已知键（来自服务端白名单，不接受任意 SQL）

3. DockerCodeRunner
   a. 起容器：--network=none --read-only --memory=512m --cpus=1 --pids-limit=128
              --tmpfs /tmp:rw,size=768m,mode=1777
   b. 容器内：cp -a /opt/datadir-base /tmp/data → 起 mysqld（socket only）
   c. 轮询 ping 直到就绪；超过 BOOT_TIMEOUT（实测 4.5s × 3 余量 = 15s）即判失败
   d. 灌数据集（服务端内置的 SQL，来自配置而非请求）
   e. 执行用户 SQL，会话内先 SET SESSION max_execution_time
   f. 以 --batch --raw --column-names 导出结果到 fd 3，与程序输出分离
   g. 输出总量超限即停止读取并标记 truncated/over_limit

4. 外层墙钟硬杀：WRAPPER_TIMEOUT = 20s，到点 destroyForcibly + 删容器

5. 返回 { tables, stdout, exitCode, durationMs, timedOut, overLimit, truncated }
```

**为什么结果走 fd 3**：沿用 WORK-023 的做法。SQL 的 `SELECT '…'` 可以打印任意内容；如果结果集与程序输出混在 stdout 里，就没有可靠的解析边界。

### 多语句（U-07 裁决允许）

一次运行可能包含多条语句（`BEGIN; UPDATE …; SELECT …; ROLLBACK;`）。约定：

- 结果契约是**数组**，每个元素一个结果集，按语句顺序；
- `BEGIN`/`COMMIT`/`ROLLBACK`/`SET` 不产生结果集，不占数组位置；
- 每条结果集带 `statementIndex`，前端展示时标出"第 N 条语句的结果"；
- **任一语句报错即停止后续语句**，把已产生的结果集一起返回（用户要看到"前面几步成了、第几步崩了"）；
- 事务语义由 mysqld 自己保证，沙箱不做任何模拟——`ROLLBACK` 之后读到旧值就是真的读到了旧值。

### 判题（客户端）

```text
1. 客户端持有题面的 datasets[]，其中公开集 key 随题面下发，隐藏集 key 不下发
2. 对每个要用的数据集：POST run → tables
3. judgeSql(tables, 题面期望口径)：
   a. 列名集合比对（或按位置，取决于题面 normalize）
   b. 行序列比对；行序忽略时按归一化后的行做多重集比较
   c. 每格按列类型归一：DECIMAL 转数值、时间统一格式、NULL 显式
4. 每个数据集都通过 → 本题满分；否则按通过数计分
```

隐藏数据集**只给 key、不给内容**：客户端照样能请求执行（服务端认识这个 key），但它拿不到期望结果，也就无法硬编码。期望结果的比对靠客户端用参考解在同数据集上跑出来的结果？——不行，那等于把答案给客户端。

**这里必须做一个明确取舍**，两条路：

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **甲：隐藏集期望结果由服务端比对** | 客户端把 `tables` POST 回一个 `/sandbox/check`，服务端与库里存的期望比 | 答案不泄露 | 服务端要认识"正确答案"，打破"只执行不判题" |
| **乙：隐藏集只下发期望结果哈希** | 客户端把归一化后的结果做规范序列化，比哈希 | 不泄露原答案，仍客户端判题 | 归一化必须在两侧完全一致，否则误判 |

选**乙**：它保住 WORK-015 的"服务端不存答案"这条纪律，代价是归一化必须严格一致——而这正是 REQ「两侧语料对照」要解决的问题。哈希用归一化后的规范序列化（列名排序、行排序、值按类型规整）的 SHA-256。

> 规范序列化的格式必须唯一：`列名以 \u001f 连接 \n 行以 \u001f 连接 \n 行与行以 \u001e`。任一侧实现不同就会得到不同哈希，语料测试会立刻抓到。

### 自由练习

```text
GET  /practice/sql  → 选数据集（一期只有一个 orders-v1）
粘贴/编写 SQL → 运行 → 结果表格
无任何 learning_progress 写入（AC-011 用数据库断言）
```

## 接口与数据

### `GET /api/v1/sandbox/status`（扩展，不新增端点）

```json
{
  "enabled": true,
  "docker": true,
  "runtime": "",
  "languages": ["java", "python", "sql"],
  "remaining": 42
}
```

`languages` 必须是**镜像实际可用**的列表：SQL 镜像缺失时不能出现 `sql`。

### `POST /api/v1/sandbox/run`（扩展请求，响应加字段）

请求：

```json
{
  "language": "sql",
  "code": "SELECT o.id, SUM(i.unit_price*i.qty) AS total FROM …",
  "dataset": "orders-v1",
  "caseId": "my-03-write"
}
```

响应新增 `tables`：

```json
{
  "stdout": "id\ttotal\n1\t487.50\n",
  "tables": [
    { "columns": ["id", "total"], "rows": [["1", "487.50"]] }
  ],
  "exitCode": 0,
  "durationMs": 5210,
  "timedOut": false,
  "overLimit": false,
  "truncated": false
}
```

**值一律以字符串返回**：类型判断交给前端按题目声明的列类型做归一，避免 JSON 数字与 `DECIMAL` 精度在两侧各丢一次。

### 题目元数据（内容源格式）

```jsonc
{
  "id": "my-03-write",
  "kind": "sql-write",
  "title": "按总额降序列出每笔订单",
  "goal": "把「多表连接 + 聚合 + 排序」组合成一次查询",
  "trap": "排序键有重复值时顺序不稳定",
  "dataset": ["orders-public", "orders-hidden-a"],
  "reference": "SELECT o.id, SUM(i.unit_price*i.qty) AS total FROM …",
  "alternatives": [
    "SELECT o.id, (SELECT SUM(...) FROM order_items i WHERE ...) AS total FROM …"
  ],
  "antiPatterns": [
    "SELECT o.id, SUM(i.qty) AS total FROM …",          // 把件数当金额
    "SELECT o.id, SUM(i.unit_price*i.qty) AS total FROM … ORDER BY total"  // 缺少可判定的稳定顺序
  ],
  "normalize": { "rowOrder": "strict", "columnOrder": "ignore" },
  "expectHash": { "orders-public": "sha256:…", "orders-hidden-a": "sha256:…" }
}
```

`expectHash` 由门禁脚本生成并回写，**不手写**。

### 数据集

`content/courses/mysql/datasets/orders-public.sql` 等：一组 `CREATE TABLE` + `INSERT`，是**受信任的内容**，不是用户输入。服务端只从配置指向的白名单里取。

### 数据库

**不新增表**。审计沿用 `code_runs`，`language='sql'`。`expectHash` 存在**内容文件**里，不入库——题目是内容，随课程源文件走。

## 持久化与迁移

无迁移。`code_runs.language` 是 `VARCHAR(16)`，`sql` 放得下，`status` 也复用现有枚举（新增 `OVER_LIMIT` 需要一个值，见下）。

**`CodeRun.Status` 需要加一个 `OVER_LIMIT`**：结果集超行数上限与 `TIMEOUT` 是不同原因，不能混报。这是唯一的 Java 枚举扩展，不涉及迁移。

## 失败处理与恢复

| 失败 | 检测点 | 返回 | 用户看到 |
| --- | --- | --- | --- |
| 沙箱关闭 / 镜像缺失 | `/status` 的 `languages` 不含 sql | 不渲染运行按钮 | 只读题面，选择题仍可做 |
| 配额耗尽 | `requireQuota`（启动容器前） | 429 | 「今天的运行次数已用完」 |
| 数据集未知 | 白名单校验 | 400 | 不出现（客户端不会发） |
| mysqld 起不来 | 启动轮询超 `BOOT_TIMEOUT` | 503 + `status=DISABLED` | 「运行环境暂时不可用」 |
| SQL 语法错 | 客户端错误码 1064 | 200 + `exitCode≠0` + `stderr` | 显示报错原文 |
| 库层超时 | 错误码 3024 | 200 + `timedOut` | 「查询超过时限」 |
| 容器墙钟超时 | 外层 `destroyForcibly` | 200 + `timedOut` | 同上，但提示「已强制终止」 |
| 结果超限 | 读取时计数 | 200 + `overLimit` | 「返回行数过多，请加条件」 |

**任何一种失败都不写学习进度**。分数只在客户端确认某数据集通过后才上报。

## 安全与权限

沿用 WORK-015 的全部加固，SQL 特有的几条：

| 项 | 做法 | 依据 |
| --- | --- | --- |
| 网络 | `--network=none`；mysqld 用 `--skip-networking` 只走 socket | 双重关闭 |
| 文件系统 | 根只读；datadir 与 socket 都在 tmpfs | 不留任何持久文件 |
| `secure_file_priv` | 指向 tmpfs 内一个**已创建**的空目录 | 实测指向不存在的路径会启动失败 |
| `local_infile` | 显式 `--local-infile=0` | 默认已关，仍写死 |
| 危险语句 | 数据集与用户 SQL 分两次执行；用户 SQL 不允许多语句除非题目声明 | 防止一次请求跑一串 DDL |
| 宿主挂载 | **不挂载宿主目录**：datadir 从镜像 `/opt/datadir-base` 拷进 tmpfs | 实测复用挂载会残留数据 |
| 进程 | `--pids-limit=128`（mysqld 自身线程较多，比 Java/Python 的 64 宽） | 实测启动后线程数需留余量 |
| 内存 | `--memory=512m`（实测常驻 348MB） | 低于此值有 OOM 风险 |

**上传的 SQL 不落库**：`code_runs` 只存 `code_hash` 与 `code_length`，与现有语言一致（AC-013）。

## 可观测性

- `code_runs` 里 `language='sql'` 的行可直接统计使用量与失败分布；
- 新增 `status='over_limit'` 的值，运维页的沙箱卡片按语言分组时自动多一档；
- 容器启动耗时写日志（`durationMs` 已包含），用于发现镜像变慢。

## 实施顺序

1. **镜像与启动脚本**（不改 Java 代码）：手工构造预初始化镜像，量出启动耗时与内存，确认 4.5s / 348MB 在生产镜像上成立。
2. **命令构造与逐条加固断言**：`DockerCommand` 加 SQL 分支，先写测试后写实现。
3. **结果编解码**：`SqlResultCodec` 把 batch/raw 输出解析成 `tables`，含多结果集与 NULL 表示。
4. **判题口径**：先写 `tests/sql-judge/corpus.json`，再分别写 TS 与 Python 两侧，跑对照。
5. **内容门禁扩展**：四类元数据校验 + 反模式/alternative 断言 + `expectHash` 生成。
6. **题库**：12 关接题，先做 3 道跑通全链路，再补满。
7. **自由练习页**。

第 1、2、3 段不依赖题库，可先做；第 4 段是关键路径（题目可信度的技术前提）。

## 测试策略

| 层 | 测什么 | 怎么测 |
| --- | --- | --- |
| 纯函数 | 命令行每条加固标志、超时/超限判定、结果解析 | 单测逐条断言，沿用 `DockerCommandTest` 写法 |
| 判题 | 归一化每条规则的正反例 | `corpus.json` 语料 + 两侧对照测试（AC-014） |
| 内容门禁 | 每题四类元数据齐全、反模式必被拒、alternative 必通过 | `verify_course.py` 扩展，**并要有一个故意不合格的样例证明它真会报错** |
| 集成 | 容器能起、能跑、能销毁、跨次不残留 | `SandboxIntegrationTest` 加 SQL 用例，仅在服务器上有 Docker 时跑 |
| 端到端 | 关卡里做题 → 计入进度；练习页不写进度 | 学习者端 e2e + 数据库断言（AC-011） |

**反向验证的硬要求**：门禁的"反模式必被拒"这条，必须用一个**故意写错的反模式**（让它能通过）去证明门禁会报错。否则这条断言本身没被验过。

## 需求追踪

| 需求 | 设计落点 |
| --- | --- |
| REQ-001 语言白名单 | `SandboxProperties.languages()` 加入 sql（按配置可用性） |
| REQ-002 一次性实例 | datadir 拷贝进 tmpfs + `--rm` |
| REQ-003 结构化结果 | `tables` 字段 + `SqlResultCodec` + fd 3 |
| REQ-004 三道上限 | `max_execution_time` / 20s 墙钟 / 行数与字节上限 |
| REQ-005 按结果判 | `judgeSql` 只吃 `tables` |
| REQ-006 可配归一化 | `normalize` 字段 |
| REQ-007 多数据集 | `dataset[]`，公开/隐藏 |
| REQ-008 期望由参考解生成 | 门禁跑 `reference` 回写 `expectHash` |
| REQ-009 反模式与 alternative | 门禁断言 |
| REQ-010 判定在客户端 | 隐藏集用哈希，不用服务端比对 |
| REQ-011 五题型 | schema 扩展 |
| REQ-012 每课有非执行题 | 门禁检查 |
| REQ-013 goal/trap | 门禁检查非空 |
| REQ-014 12 关接题 | 内容任务 |
| REQ-015 自由练习 | `/practice/sql` |
| REQ-016 独立配额 | `SandboxQuotaService` 按 language 取值（20 / 50） |
| REQ-017 复用审计表 | `language='sql'` |
| REQ-018 真实能力探测 | `/status.languages` 按镜像可用性 |

## 已知限制与升级路径

1. **每次运行 4.5 秒**。若用户抱怨，升级路径是"同一题的多数据集共用一个容器"（把一次运行改为批量数据集），能把 N 个数据集从 N×4.5s 降到 4.5s + ε。**不在 v1 做**：它会让一次请求的失败面变大（一个数据集崩了全崩）。
2. **隐藏集靠哈希保护**。哈希不可逆，但如果期望结果空间很小（比如 `SELECT COUNT(*)`），暴力枚举是可行的。缓解：隐藏集的数据量设计成结果不是小整数，且题目至少有一个结果"形状复杂"的数据集。
3. **归一化两侧必须严格一致**。这是选方案乙的固有代价；语料测试是唯一护栏，新增归一化规则时必须同时加语料。
4. **不支持 SQL 的逐步执行**（WORK-023 的 trace 只覆盖 Python）。要做需要另立一项。
5. **不判性能**。`EXPLAIN` 相关题目只能判"输出里是否出现某个访问方式"，判不了"你写得快不快"。
