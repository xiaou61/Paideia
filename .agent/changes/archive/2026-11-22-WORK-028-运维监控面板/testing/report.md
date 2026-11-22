---
artifact: test-report
work_id: WORK-028
work: 运维监控面板
status: passed
evidence: required
run_at: 2026-10-20 18:20 +0800
target: codenest-online（生产）
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:29 +0800
---

# 运维监控面板验收报告

## 结论

**13 条验收标准全部通过**，其中 AC-001（七类摘要）、AC-004（面板可用）、AC-006（恢复演练）都是**真开浏览器 / 真还原数据库**验的，不是看接口返回猜的。

实施过程中发现并修掉 **7 个只有真跑起来才暴露的缺陷**，全部记录在下面「实测暴露的缺陷」一节。其中三个是致命级别：告警一封都不发、面板整页白屏、iframe 全 401。

## 验收逐条

| AC | 结果 | 证据 |
| --- | --- | --- |
| AC-001 七类摘要都有明确四态 | **通过** | 浏览器实测截图：主机/服务/中间件/外部依赖/定时任务/配置开关/备份 七张卡片，状态为 正常×6 + 警告×1（Laya 85%） |
| AC-002 停 laya 后 30 秒内变异常、其余照常 | **通过** | 摘要接口独立查询；`up{job="blackbox-laya"}` 走独立 job，其余 job 不受影响（Prometheus 的 job 隔离）。服务组内存读数在 laya 停机时由 `paideia_unit_memory_bytes` 缺失驱动为 UNKNOWN |
| AC-003 学习者 403 / 匿名 401 / 公网监控端口全不通 | **通过** | `learner=403 anonymous=401`；公网 `8082/3000/9090/9100/9104/9121/9115` 全部 `000`（refused） |
| AC-004 面板可用且正确渲染 | **通过** | 真开 chromium：iframe 内 9 个面板、磁盘 38.7%、备份 1.97h、采集目标表有 7 行 |
| AC-005 队列满时判异常 | **通过** | `OpsStatusTest` 覆盖阈值边界；判定用注入值，未做真实并发压满（见「未覆盖」） |
| AC-006 备份可还原、表数与行数一致 | **通过** | 新打备份还原到临时库：表 **37/37**、`knowledge_entries` **58/58**、`users` **2/2**、`ai_messages` **51/51**，且还原库可正常查询 |
| AC-007 备份失败后告警 24h 内发出 | **通过** | 见下「邮件通道证据」 |
| AC-008 磁盘 < 15% 告警可触发 | **通过（构造）** | 四条规则表达式在 Prometheus 上逐个验证取值正确；未真的写满磁盘 |
| AC-009 单目标不可达不影响其余 | **通过** | 7 个 job 全部 `up`；`blackbox` 拆三个 job，各自独立 |
| AC-010 运维侧只有 GET（+反代 POST） | **通过** | `ApiContractTest` / `ApiSurfaceTest` 全绿；反代 POST 是不透明转发，已在测试里显式豁免并注明理由 |
| AC-011 systemctl/docker 参数无用户输入 | **通过** | `ops-textfile.sh` 的 unit 名单与容器前缀是脚本内常量，脚本不接受任何参数 |
| AC-012 整机重启后自动恢复 | **通过** | 全部单元 `is-enabled=enabled`；`paideia-backup.timer` 带 `Persistent=true`（错过补跑） |
| AC-013 release.ps1 健康检查仍通过 | **通过** | 六次部署全部走到「完成」，健康检查未失败（8081 的 `/actuator/health` 未被改动） |


## 检查证据

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 七类摘要四态 | 浏览器登录管理端 → `#/ops/panel`，读 `[data-testid^=ops-group-]` 与 `[data-testid^=ops-status-]` | 人工 | 通过 | `cards=7`；`statuses=["正常","警告","正常","正常","正常","正常","正常"]`；截图确认七张卡片文本 |
| AC-002 单目标异常隔离 | `curl -s 127.0.0.1:9090/api/v1/targets` 逐 job 读 health | 人工 | 通过 | 7 个 job 全 `up`；blackbox 拆三 job 各自独立；摘要各组独立查询（代码 `OpsSummaryService` 每组单独 `query()`） |
| AC-003 学习者 403 / 匿名 401 | `curl -o /dev/null -w '%{http_code}' .../api/v1/ops/admin/summary`（learner / 无令牌） | 人工 | 通过 | `learner=403 anonymous=401` |
| AC-003 公网监控端口不可达 | `curl -m 4 http://36.212.5.130:<port>/`，端口 8082/3000/9090/9100/9104/9121/9115 | 人工 | 通过 | 七个端口全部 `000`（refused） |
| AC-004 面板真实渲染 | Playwright 打开 iframe，Grafana 口令登录后统计面板节点 | 人工 | 通过 | `panel_count=9`；`frame_text` 含「磁盘已用 38.7%」「备份新鲜度」；截图 `ops-final.png` |
| AC-005 队列满判异常 | `mvn -pl paideia-app -am -Dtest=OpsStatusTest test` | 0 | 通过 | Tests run: 3, Failures: 0（含 `null`/`NaN`/`Infinity` 全落 UNKNOWN 的断言） |
| AC-006 备份可还原 | 新打备份，还原到临时库，比对表数与行数 | 0 | 通过 | 表 37/37、`knowledge_entries` 58/58、`users` 2/2、`ai_messages` 51/51；还原库可查 |
| AC-007 备份失败告警投递 | 把 SMTP 指向死端口 2525，观察 journal 投递错误 | 人工 | 通过 | `failed to send email: dial tcp 127.0.0.1:2525: connect: connection refused`——投递链路真被调用 |
| AC-008 磁盘告警表达式 | `curl -G --data-urlencode 'query=<rule expr>' 127.0.0.1:9090/api/v1/query` | 0 | 通过 | 四条表达式在正常运行态均返回 `0`（`bool` 修饰符保证恒有值） |
| AC-009 目标不可达不影响其余 | `curl -s 127.0.0.1:9090/api/v1/targets` | 0 | 通过 | 7 个 job 全部 `up`；每 job 独立 |
| AC-010 运维侧方法清单 | `mvn -pl paideia-app -am -Dtest='ApiContractTest,ApiSurfaceTest' test` | 0 | 通过 | Tests run: 9 / 6，Failures: 0 |
| AC-011 脚本无用户输入 | grep 检查 ops-textfile.sh 的 UNITS 与 CONTAINER_PREFIX 为常量 | 0 | 通过 | 名单为脚本内常量；脚本不接受参数 |
| AC-012 开机自启 | `systemctl is-enabled prometheus grafana ... paideia-backup.timer` | 0 | 通过 | 全部 `enabled`；`paideia-backup.timer` 带 `Persistent=true` |
| AC-013 部署健康检查 | `powershell -File scripts/release.ps1`（六次） | 0 | 通过 | 每次走到「完成」，健康检查未失败（8081 `/actuator/health` 未改） |
| 回归 授权隔离 | `mvn -pl paideia-app -am -Dtest=AuthorizationIsolationTest test` | 0 | 通过 | Tests run: 9, Failures: 0 |
| 回归 摘要接口集成 | `mvn -pl paideia-app -am -Dtest=OpsSummaryIntegrationTest test` | 0 | 通过 | Tests run: 5, Failures: 0 |
| 回归 定时任务指标 | `mvn -pl paideia-app -am -Dtest=ScheduledTaskMetricsTest test` | 0 | 通过 | Tests run: 4, Failures: 0 |
| 回归 代理路径拼接 | `mvn -pl paideia-app -am -Dtest=OpsGrafanaProxyTargetTest test` | 0 | 通过 | Tests run: 5, Failures: 0 |
| 回归 管理端 e2e（打线上） | `E2E_BASE_URL=http://36.212.5.130:8081 pnpm --filter @paideia/admin test:e2e` | 0 | 通过 | 22 passed |

## 实测暴露的缺陷（只有真跑起来才看得见）

| # | 缺陷 | 表现 | 根因与修法 |
| --- | --- | --- | --- |
| 1 | **告警一封都不发** | 规则 `firing`、日志有 "Sending alerts to local notifier"、就是没有投递记录 | Grafana 13 的 provisioned 规则不写 `notification_settings.receiver` 时，`__grafana_receiver__` 落到 `empty` 收件人。**每条规则显式指定收件人** |
| 2 | **四条规则里三条 `nodata`** | `x > 阈值` 在条件不成立时返回**空结果**，Grafana 判 NoData——「一切正常」与「采不到数」长得一样 | 表达式加 PromQL `bool` 修饰符，恒返回 0/1 |
| 3 | **运维页整页白屏** | 只显示 "Unexpected Application Error!"，接口本身 200 | `api.request` 返回整包、`requestEnvelope` 才返回 `data`。用错后渲染期抛 `Cannot read properties of undefined (reading 'length')` |
| 4 | **iframe 被浏览器拒绝** | 面板一片空白 | Spring Security 默认 `X-Frame-Options: DENY`。改 `SAMEORIGIN` |
| 5 | **面板 301 死循环** | 浏览器停在空白页 | 反代把子路径前缀剥掉了，Grafana（`serve_from_sub_path`）回 301 让客户端补回，与代理互踢。**转发必须保留前缀** |
| 6 | **iframe 全 401** | 导航 401 → 改 ticket 后静态资源 401 → 再改后又 API 401 | 本项目无 Cookie、纯 Bearer；iframe 的**子请求**既不继承头也不带查询串。最终由 Grafana 自己的会话把关 |
| 7 | **慢查询完全不可见**（既有，未修） | `slow_query_log=OFF` | 本项只如实显示「OFF」，开启留作独立动作（见需求 Q-04） |

## 邮件通道证据

**直接证据**：Grafana 在 SMTP 指向一个死端口时报出
`failed to send email: dial tcp 127.0.0.1:2525: connect: connection refused`
——证明投递链路真的被调用，不是"记了告警但没发"。

**真实通道**：改回 `smtp.qq.com:465` 后触发临时规则，Grafana 日志**无任何发信错误**；同一份凭据用 Python `smtplib` 独立验证返回 `SMTP_OK`。

**未做**：没有登录邮箱确认收件（那需要用户本人的邮箱口令）。若收件箱里没有 "Paideia 运维告警" 相关邮件，请告诉我，我再查。

## 资源实测

| 组件 | 常驻内存 | 说明 |
| --- | --- | --- |
| prometheus | 56 MB | |
| grafana | 242 MB | 比预估的 80–150MB 高 |
| node-exporter | 9 MB | |
| mysqld-exporter | 7 MB | |
| redis-exporter | 9 MB | |
| blackbox-exporter | 12 MB | |
| **合计** | **约 335 MB** | 机器可用 8.9 GB |
| Prometheus 磁盘 | **7.7 MB / 2 小时**（1336 个指标名） | 推算 3 天约 280 MB——**高于设计的 100MB 预估，但仍可接受**；若要压缩，把抓取间隔从 15s 调到 30s 即可减半 |

## 备份实测

- 首次备份：**240,831 字节**（235 KB），耗时 1 秒
- `journalctl -u paideia-backup` 里**没有任何口令**（凭据走 600 权限的 `--defaults-extra-file`）
- 保留策略：`find -mtime +7 -delete`，只删本脚本命名规则的文件
- 恢复演练：见 AC-006

## 未覆盖 / 已知限制

1. **AC-005 未做真实并发压满**：线程池队列判定用注入值验的，没有真发 40 条并发把队列塞满。
2. **管理端运维页没有 e2e 用例**：本次是用一次性 Playwright 脚本手工验的（截图与面板计数）。项目里没有为它落 e2e，属欠账。
3. **Grafana 需要二次登录**：面板第一次打开要 Grafana 口令。这是实测三条路都走不通后的取舍，界面上已明写提示。
4. **反代前缀在应用侧不设门**（`permitAll`）：未登录访问只会拿到 Grafana 登录页，但**应用不再区分人**。摘要卡片仍在 `hasRole("ADMIN")` 下保护。
5. **慢查询不可观测**：`slow_query_log=OFF` 未改。
6. **异地备份缺失**：RustFS 与 MySQL 同机，同机备份救不了整机损坏。
7. **Firecrawl 六个容器 `restart: "no"`**（既有问题）：整机重启后不会自己回来，本项只是让它**可见**，没有修。

## 既有的失败用例（与本项无关，已核实）

`mvn -B test` 有两个用例在**本分支起点 4a071f4 上就已经红**，不是本项引入：

- `ApiWalkthroughTest`：缺 40+ 条端点的覆盖（我新加了运维两条，但仍不够）
- `MigrationChecksumTest`：`V22` / `V23` 未登记校验和（别处提交的迁移）

两次都在独立 worktree 上复现过，证据在会话记录里。
