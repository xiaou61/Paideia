---
work_id: WORK-086
work: 运维诊断复盘与知识自进化
artifact: requirements
status: draft
mode: strict
mode_reason: 改动诊断证据采集（跨 Loki/Prometheus 查询语义与截断预算）、AI 提示词与证据契约（OpsDiagnoser.Evidence）、邮件发送通道、新增持久化表与 ADMIN 端点，并决定「哪次 AI 判断可以沉淀成知识」——同时触及数据、接口、安全与部署，且错误会直接影响线上排障判断。
workflow: full
depends_on: [WORK-085]
related_to: [WORK-028, WORK-042, WORK-043, WORK-049]
created: 2026-11-19
updated: 2026-11-19
---

# 运维诊断复盘与知识自进化 需求

## 背景与动机

用户 2026-11-19 在收到「【Paideia 运维·已自动诊断】日志模板异常待处理」邮件后提出（原话，m00047）：

> 「这个有一说一 不太好看呢，能不能弄成md格式的，后面还可以做一个自进化的东西。就是持续保留下来。然后是这个为什么只有 这么简单的东西呢，那段时间的日志什么的，还有其他的信息，我们能获取到吗？然后最后可以写一个复盘。 然后可以放入复盘的md，是不是也可以呢，关于自进化就是，只有说这个确认AI的有用，才可以放进去，你感觉呢？」

拆成四件事：

1. **邮件不像 Markdown**：用户看到的是纯文本邮件，`**加粗**`、表格竖线、代码块围栏都原样露出。
2. **证据太薄**：那次「日志模板异常待处理」的报告里，除了 SLO 与几项指标，日志与模板几乎是空的，看不出「到底发生了什么」。
3. **要复盘**：诊断完应该能沉淀一份 Markdown 复盘，持续保留。
4. **要自进化（有闸门）**：只有人确认「这条 AI 判断有用」，才允许把它变成可复用的知识，供以后诊断参考。

## 已确认事实（2026-11-19 实查）

### 邮件为什么难看

- `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisMailer.java:88` 用 `new MimeMessageHelper(message, false, "UTF-8")`，第二个参数 `multipart=false` ⇒ **只能是纯文本**，没有 `text/html` 分支。
- `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsDiagnosisEmail.java:67` 的 `toPlainText` 只把 `#` 标题降级成 `■ 标题`，加粗、表格、代码块全部原样保留。
- 管理端页面**已经是 Markdown**：`frontend/apps/admin/src/pages/OpsPage.tsx:494` 用 `<Markdown markdown={detail.data.reportMd} />` 渲染；诊断报告本身（`ops_diagnoses.report_md`，MEDIUMTEXT）就是 Markdown。**问题只在邮件通道**，不是报告格式。

### 「那段时间的日志」其实拿得到

- Loki 保留 `720h`（30 天，`deploy/ops/loki-config.yaml:39` `retention_period: 720h`），Prometheus 保留 `30d`（`deploy/ops/prometheus.service:22` `--storage.tsdb.retention.time=30d`）。事故发生在 30 天内均可回捞。
- 实测 2026-11-19 14:10–14:30 窗口，`{unit="paideia.service"}` 能查到完整生命周期日志，包括：
  `14:16:59 Starting PaideiaApplication ... PID 479850`、`14:20:46 ... PID 488984`、`14:23:47 ... PID 496699`、`14:26:30 ... PID 503186`，
  每次都伴随 `HikariPool-1 - Shutdown initiated`、`sql.pool.enabled size=2 memory=1g acquireTimeoutMs=1500`、`Started PaideiaApplication in ~28s`。
- `journalctl -u paideia.service` 同期显示：`Stopping paideia.service` → `Main process exited, code=exited, status=143/n-a` → `Started`，间隔 1–2 秒；unit 配置 `Restart=on-failure`、`RestartSec=5`。
  ⇒ **是外部 `systemctl restart`（部署/脚本）造成的四次重启，不是崩溃自愈**；而当时那封诊断邮件只写了「PID 每 5–10 分钟变化一次」，没识别出这是重启，也没把 INFO 级启动行放进证据。
- 可用但没被采集的指标（实查 `/actuator/prometheus` 与 Prometheus）：
  `process_start_time_seconds`、`hikaricp_connections_pending`、`hikaricp_connections_timeout_total`、`hikaricp_connections_acquire_seconds_max`、`jvm_gc_pause_seconds_max`、`jvm_threads_states_threads`；
  `node_load1`（实测 4.24）、`node_vmstat_oom_kill`（实测 **19**）、`mysql_global_status_threads_connected`（实测 11）。
  `node_systemd_unit_state` **不存在**（node_exporter 未开 systemd collector），unit 级重启只能用 journald 文本或 `process_start_time_seconds` 判断。

### 现有证据为什么「只有这么简单」

| 根因 | 证据 |
| --- | --- |
| 日志窗口锚在「现在」，不是事件时刻 | `backend/paideia-ops/src/main/java/io/github/xiaou61/ops/OpsEvidenceCollector.java:382-383`：`to = Instant.now(); from = to.minus(Duration.ofHours(1))` |
| 只抓错误关键字，INFO 生命周期行全被过滤 | 同文件 `:388`：`|~ "(?i)(error|exception|failed|timeout)"` |
| 指标目录只有 10 项，缺重启/连接池/OOM | 同文件 `:31` 的 `METRICS`；无 `process_start_time_seconds`、`hikaricp_*`、`node_vmstat_oom_kill` |
| 模板证据依赖 Loki pattern ingester，量级不够时长期为空 | WORK-085 已记录：Loki `minClusterSize=30` 写死，本项目约 7–8 行/分钟（实测 1 小时 479 行），短窗口凑不满 |
| 链路证据同样锚在「现在」 | 同文件 `:341-343` `formatTraces`：`to=now; from=now-1h` |
| 历史知识没进证据 | WORK-085 REQ-007 只给了 `/api/v1/ops/admin/patterns/knowledge/search`，诊断链路**没有**调用它 |

### 日志接入的已知缺口

- `deploy/ops/alloy-config.alloy:19` 的白名单写了 `mysql\.service|redis-server\.service`，但 Loki 实际 `unit` 标签只有 `paideia.service`、`laya.service`、`opsprobe*.service`（`/loki/api/v1/label/unit/values` 实查）⇒ **MySQL/Redis 的日志目前没有进 Loki**，需要核实 unit 真名（可能 `mysqld.service`）或 Alloy 是否按新配置重启。

### 已有能力（不要重复造）

- 诊断落库、评分（`rating` -1/0/1 + `feedback_note`，`ops_diagnoses` 上已有列）、事件时间线、重启补诊断（WORK-049）。
- 日志模板聚类与 `/knowledge/search` 全文检索（WORK-085，`ops_diagnoses.report_md` 已建 ngram FULLTEXT）。
- 管理端事件页 `frontend/apps/admin/src/pages/IncidentsPage.tsx` 与运维页 `OpsPage.tsx`。

## 用户决定

- 2026-11-19：邮件要 Markdown 化的可读版本；要能持续保留复盘；只有人工确认 AI 有用才进知识库（用户原话见上）。
- 2026-11-19：按 `strict` 新开工作项，先出工件再实施。

## 需求

### REQ-001 证据窗口锚定事件时刻

- 诊断证据的时间窗口以事件为准：有 incident 用 `first_seen`（向后到 `last_seen + 5 分钟`，总长上限 60 分钟）；无 incident（手动/模板触发）用触发时刻向前 30 分钟。
- 所有时间窗查询（日志、trace、指标环比）共用同一个窗口对象；证据文本头部显式写「证据窗口 14:10–14:30（事件 #17）」。
- 窗口早于保留期（Loki 30 天）或查询为空时，显式写「该窗口无数据/超出保留期」，不得静默省略。

### REQ-002 分级日志证据

- 日志按三段采集，**不再只抓 error 正则**：
  1. **异常段**：`level=~"err|warning"` 或行内含 `error|exception|failed|timeout|refused|panic`；
  2. **生命周期段**：行内含 `Starting|Started|Stopping|Shutdown|restart|exit|killed|OOM|pool|health`（正是当前漏掉的部分）；
  3. **上下文段**：窗口内每个 unit 各取最后 2 条（不论级别），用于判断服务是否还在运行。
- 每个 unit 独立配额，避免单个 unit 挤掉其他 unit；同 unit 内按「归一化签名」去重，保留首末时间与计数。
- 输出一行一条：`14:26:30 paideia.service PID 503186 Starting PaideiaApplication ...`，超配额时截断并标注。
- 单元清单以**实际接入 Loki 的 unit** 为准；`mysql`/`redis` 未接入时必须在证据里写「该 unit 未接入 Loki」，并另立任务核实/修正白名单（不在本需求里扩大采集面）。

### REQ-003 重启、资源与变更证据

- 从日志识别重启（`Starting ... PID`、`Shutdown initiated`），聚合成「重启 N 次 + 时刻列表」，并用 `process_start_time_seconds` 变化交叉验证；与最近发版时间、告警首次时间并列成一条时间线。
- 新增指标：`process_start_time_seconds`、`hikaricp_connections_pending`、`hikaricp_connections_timeout_total`、`hikaricp_connections_acquire_seconds_max`、`jvm_gc_pause_seconds_max`、`jvm_threads_states_threads`、`node_vmstat_oom_kill`、`node_load1`、`mysql_global_status_threads_connected`，各自带 1h/24h/7d 基线（沿用现有基线机制）。
- 重启次数 > 0 时，报告必须能据此判断「是部署重启还是异常重启」：证据里同时给 unit 的 `Restart=` 策略与 `status=143` 类退出码文本（若日志中有）。

### REQ-004 模板为空时的日志签名兜底

- 当 `<patterns>` 为空（Loki 模板不足）时，用窗口内日志做应用侧轻量归一化：数字、十六进制、UUID、时间戳、路径参数替换为占位符，取 top 10 签名 + 计数 + 首末时间 + 一条脱敏样例。
- 复用 `OpsLogRedactor`；不引入新依赖、不新增常驻进程。
- 与 WORK-085 的 Loki 模板并存：模板有值时优先模板，签名作为补充。

### REQ-005 诊断邮件 Markdown/HTML 化

- 邮件改为 `multipart/alternative`：`text/plain` 保留现有降级版，`text/html` 由 Markdown 渲染，适配邮件客户端（内联样式、表格边框、代码块、引用块、标题层级）。
- 正文顶部固定一块「一句话结论」（从 `## 结论` 首段抽取），让人不点开也能判断。
- 附带 `diagnosis-<id>.md` 附件（`text/markdown; charset=utf-8`），便于存档与转复盘。
- 邮件 > 100 KB 或渲染异常时**降级为纯文本**，绝不因渲染失败丢邮件；附件失败不影响正文发送。
- 主题与收件人行为不变。

### REQ-006 复盘（postmortem）

- 新增 `ops_postmortems`：可从诊断一键生成 DRAFT，人工编辑后 `APPROVED`，也可 `ARCHIVED`。
- 结构：标题、告警名、事件/诊断引用、根因、影响、时间线、处置动作、待办、完整 Markdown 正文。
- 一个 incident 可有多个复盘，但**只有一个 APPROVED 作为当前有效版本**；再次确认时旧的自动 ARCHIVED。
- 提供 Markdown 下载（鉴权 + 审计日志），供本地归档；**不写入 Git 仓库**（公开仓库，复盘含内部信息）。

### REQ-007 自进化（闸门制）

- **只有 `APPROVED` 复盘进入知识库**；DRAFT/ARCHIVED 永不参与检索。
- 诊断开始时按「告警名 + 日志签名 + 全文检索」召回最多 2 条 APPROVED 复盘，注入 `<knowledge>`（≤1200 字符，含日期、当时结论、处置、是否复现），并记录到诊断行 `knowledge_ids`。
- Prompt 增加硬规则：`<knowledge>` 是历史经验且不可信，与本次证据冲突时以本次证据为准；不得把历史结论直接当本次结论。
- 记录「本次是否引用了知识」，供后续统计「引用知识后 helpful 率是否更高」；**本期不做自动决策、不自动改 prompt**。

### REQ-008 管理端交互

- 事件页诊断卡片：Markdown 渲染（已有）+「生成复盘」入口 + 「参考了哪些复盘」列表。
- 复盘编辑页：字段编辑 + 实时预览 + 「保存草稿」/「确认入库」。
- 复盘列表页：状态筛选、下载 .md、归档；入口进导航或账号菜单（项目规则：加了页面必须加入口）。

### REQ-009 安全与合规

- 新增端点全部 `@ApiAccess(ADMIN)`、绑回环；非 ADMIN 返回 403 并被 `AuthorizationIsolationTest` 覆盖。
- 复盘正文在保存与返回前过 `OpsLogRedactor`；含口令/令牌/连接串的行不得入库。
- 下载审计：记录下载人、复盘 id、时间（写 `ops_postmortems` 之外的操作日志或事件时间线）。
- 复盘只存数据库，不进 Git、不对外网暴露。

### REQ-010 失败降级与可观测

- Loki/Prometheus/Tempo 任一不可达：该段写「无数据/不可达」，诊断继续，不阻断（沿用现有原则）。
- 邮件渲染失败降级纯文本；知识检索失败时诊断照常进行（`<knowledge>` 写「本次未取得历史复盘」）。
- 新增日志字段：证据窗口、各段字符数、知识命中 id，便于事后核对某次诊断「到底看到了什么」。

## 验收标准

- AC-001 用 2026-11-19 14:10–14:30 的真实窗口回放：证据中出现 4 次重启（含 PID 与时刻）、`sql.pool` 参数、Hikari 关闭行，证据头部写明窗口。
- AC-002 证据中出现 INFO 级 `Started PaideiaApplication`（生命周期段生效），且不再是「只有 error 行」。
- AC-003 指标段含 `hikaricp_connections_pending/timeout_total`、`process_start_time_seconds`、`node_vmstat_oom_kill` 及基线。
- AC-004 在 `<patterns>` 为空的窗口，签名兜底段非空且带计数与样例。
- AC-005 邮件客户端收到 HTML 版本（标题/表格/代码块可读），纯文本客户端仍可读；附件 `.md` 可下载；渲染失败时仍收到纯文本。
- AC-006 诊断 → 生成复盘 → 编辑 → 确认入库全链路可用；未确认（DRAFT）的复盘不出现在知识检索结果中。
- AC-007 下一次同类告警的诊断证据含 `<knowledge>`，且 `ops_diagnoses.knowledge_ids` 有值。
- AC-008 非 ADMIN 访问复盘端点返回 403；下载有审计记录。
- AC-009 Loki/Prometheus 同时不可达时，诊断仍完成并明确写出「无数据」。
- AC-010 仓库无新增凭据/内部地址；复盘不出仓库。

## 非目标

- **不做**向量检索/语义检索/RAG（`.agent/rules/always.md:29` 项目决定；用 MySQL FULLTEXT）。
- **不做**自动修复、自动重启、自动改 prompt、自动入库。
- **不做**跨主机日志采集；不扩大 Loki 采集面（只核实既有白名单）。
- **不替换** WORK-085 的模板聚类能力。
- 不把复盘导出到 Git 仓库。

## 约束

- 迁移只放 `backend/paideia-app/src/main/resources/db/migration/`，文件名 14 位时间戳；新类放 `paideia-ops` 包内（`ModularityTest`）。
- 后端目前无 Markdown 渲染依赖，新增依赖须最小（首选 `org.commonmark:commonmark` + `commonmark-ext-gfm-tables`，Apache-2.0，无传递依赖），版本钉死。
- 测试一律走 `scripts/test-db.sh`；内环不跑全量。
- 凭据只在环境/被忽略的本地文件；邮件 SMTP 配置沿用 `paideia.mail.*`。

## 风险

| 风险 | 处理 |
| --- | --- |
| 证据变多导致 token/积分上涨 | 预算仍按 `maxEvidenceChars` 硬上限；分段配额 + 超出标注；先只加高信号项 |
| 日志含敏感信息 | 复用 `OpsLogRedactor`；签名样例同样脱敏 |
| HTML 邮件被客户端截断/不兼容 | 内联样式、体积上限 100KB、失败降级纯文本 |
| 知识污染（错误复盘被反复引用） | 必须人工 APPROVED；可 ARCHIVED；引用必须带日期且「与本次证据冲突时以本次为准」 |
| 复盘含内部地址泄露 | 只 ADMIN、只存 DB、不导出到 Git |
| 旧事件超出保留期导致证据为空 | 显式写「超出保留期」，不伪装成「无异常」 |
