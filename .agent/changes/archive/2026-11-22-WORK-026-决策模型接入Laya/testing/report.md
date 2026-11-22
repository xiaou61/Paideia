---
artifact: test-report
work_id: WORK-026
work: 决策模型接入（Laya）
status: passed
evidence: required
workflow: full
created: 2026-10-20
updated: 2026-10-20
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:17 +0800
---

# 决策模型接入（Laya）验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25，`mvn -pl paideia-ai -am test`（纯单测，不碰数据库） |
| 决策服务 | `codenest-online` 本机，systemd `laya.service`，`127.0.0.1:8091` |
| 模型 | `laya-multilingual`（mmBERT-base，647MB），`/opt/laya-test/models/multilingual` |
| 机器 | 4 核 / 15.9G，无 GPU；同机跑着 Firecrawl 六容器、MySQL、RustFS、paideia |
| 本机限制 | **本机到 8091 不可达**（服务只绑回环，这是刻意的），所以真连验证全部在服务器侧用 curl/Python 复刻请求体完成 |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 关闭开关时行为与没有 Laya 一致 | `mvn -pl paideia-ai -am test -Dtest=TechEssayFilterTest` | 0 | passed | `noTermMeansNoModelCall`、`pureInformationPostIsRejectedByRuleAlone` 用桩证明不进模型 |
| AC-002 地址不可达时返回 empty 不抛 | `mvn -pl paideia-ai -am test -Dtest=TechEssayFilterTest` | 0 | passed | `unreachableServerFallsBackInsteadOfThrowing`（指向 127.0.0.1:1，超时 500ms）；实现里全部异常路径返回 empty |
| AC-003 真连时技术面经判 technical、求职帖判非技术 | `ssh codenest-online python3 /tmp/layaverify.py` | 0 | passed | 7 条样例复刻 Java 请求体：技术 4/4 判对（P=0.957~0.980），求职帖 3 条中 2 条正确落 career（0.002 / 0.231） |
| AC-004 命中 0 种技术词不触发模型 | `mvn -pl paideia-ai -am test -Dtest=TechEssayFilterTest` | 0 | passed | `noTermMeansNoModelCall`（桩会抛 AssertionError，未触发） |
| TASK-008 开机自启生效 | `systemctl is-enabled laya && systemctl is-active laya` | 0 | passed | 输出 `enabled` / `active` |
| TASK-008 鉴权生效 | `curl -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8091/v1/systemone` | 0 | passed | 无 key 返回 401；带 key 正常返回 `choice=technical P=0.933` |
| TASK-002 服务健康 | `curl http://127.0.0.1:8091/health` | 0 | passed | `{"status":"ok"}` |
| WORK-026 工件结构 | `project-lifecycle.ps1 validate F:/Paideia` | 0 | passed | 无 WORK-026 相关错误 |
| TASK-008 崩溃自愈 | `kill -9 $(systemctl show laya -p MainPID --value)` 后轮询 | 0 | passed | 6 秒内自动拉起新进程（PID 3445081 → 3600546） |
| TASK-008 服务重启恢复 | `systemctl restart laya` 后轮询 `/health` | 0 | passed | 18 秒就绪（冷加载模型） |
| TASK-008 **整机重启自启** | `reboot` 后轮询 SSH 与各服务 | 0 | passed | 服务器 15 秒回来；laya/paideia/mysql/rustfs/docker 全部 active；laya 在重启后 1 分钟内加载完模型 |
| 并发 1 | 2 次串行请求 | 0 | passed | p50 547ms |
| 并发 2 | 4 次请求 | 0 | passed | p50 644ms |
| 并发 4 | 8 次请求 | 0 | passed | p50 1042ms / p95 1067ms |
| 并发 8 | 8 次请求 | 0 | passed | p50 1219ms / p95 1633ms |
| 并发 16 | 16 次请求 | 0 | passed | p50 1853ms / p95 **2965ms** / max 3146ms；0 错误 |

## 实测数据

### 单测

```
Test set: io.github.xiaou61.ai.internal.decision.TechEssayFilterTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.197 s
```

真连测试 `LayaContentClassifierLiveTest` 按 `@EnabledIfSystemProperty(named="paideia.laya.live")` 默认跳过——设计如此，不是遗漏。

### 判定原语对比（决定用 choice 的依据）

同一段面经，`noul` 原语：

| 输入结尾 | noul |
| --- | --- |
| `…MySQL索引失效的场景` | 0.243 |
| `…MySQL索引失效的场景有哪些` | 0.675 |
| `…索引失效的场景有哪些 4、Spring事务传播` | 0.644 |

换 `choice` 三分类后：

| 样例 | choice | P(technical) | 判定 |
| --- | --- | --- | --- |
| HashMap/JVM/MySQL 面经 | technical | 0.980 | ✓ |
| MySQL B+树与事务隔离 | technical | 0.957 | ✓ |
| 拼多多服务端一面 | technical | 0.978 | ✓ |
| 从零学 Java 变量类型 | technical | 0.978 | ✓ |
| 「终于找到实习了！」 | career | 0.002 | ✓ |
| 恒生电子二面闲聊 | career | 0.231 | ✓ |
| 「如何提高工程能力」 | technical | 0.953 | ✗ |

**命中 6/7。** 唯一错的这条，规则层命中 0 种技术词，实际运行中不会走到模型，因此不会被误收（`knownMisc judgementIsAbsorbedByTheRuleLayer` 覆盖了这一点）。

### 资源与延迟

| 项 | 实测 |
| --- | --- |
| 常驻内存 | 1.5 G（`MemoryMax=3G`）；冷加载后峰值 2.6 G |
| 冷启动 | 16~22 s（`TimeoutStartSec=180`） |
| 热态单条 | 147~260 ms |
| 模型 + 运行时磁盘 | 约 1.6 G |

### 并发实测（4 核 / `LAYA_THREADS=2`）

| 并发 | p50 | p95 | max | 错误 | P(tech) 一致性 |
| --- | --- | --- | --- | --- | --- |
| 1 | 547 ms | 382 ms | 547 ms | 0 | 0.978 |
| 2 | 644 ms | 644 ms | 703 ms | 0 | 0.978 |
| 4 | 1042 ms | 1067 ms | 1107 ms | 0 | 0.978 |
| 8 | 1219 ms | 1633 ms | 1859 ms | 0 | 0.944~0.978 |
| 16 | 1853 ms | **2965 ms** | 3146 ms | 0 | 0.944 |

**结论**：延迟随并发近似线性增长（并发 16 时是单条的 3.4 倍），说明 `LAYA_THREADS=2` 是瓶颈。并发 16 时 p95 已到 2965ms——**原定的 3000ms 超时刚好卡在 p95 上**，因此把 `timeout-ms` 从 3000 调到 5000。

实际调用场景是每日抓取的**串行过滤**（一天几十条），并发度 1~2，对应 p50 547~644ms，余量充足。

### 整机重启实测

| 阶段 | 结果 |
| --- | --- |
| 重启前基线 | 5 个 systemd 服务 active、7 个容器、Paideia health 200 |
| SSH 恢复 | 15 秒 |
| systemd 服务 | laya / paideia / mysql / rustfs / docker **全部自动 active** |
| laya 模型加载 | 重启后 1 分钟内完成（内存峰值 2.6G） |
| laya 功能 | `/health` ok；技术面经 P=0.935、求职帖 P=0.115；无 key 仍 401 |
| Paideia | health UP、`/` 200、`/admin/` 200、MySQL 可查 |
| **Firecrawl** | **7 个容器里只有 searxng 自启**（`unless-stopped`），其余 6 个是 `restart: "no"` → 手动 `docker compose up -d` 后全部恢复，真实 scrape 200 |

**这是本项之外暴露的一个既有问题**：Firecrawl 的六个容器不会开机自启。本次已手动恢复，但下次整机重启会再发生一次。建议给 compose 加 `restart: unless-stopped`（**未改，属另一个工作项的范围**）。

### 运维验证

| 项 | 结果 |
| --- | --- |
| `systemctl is-active laya` | active |
| `systemctl is-enabled laya` | enabled（开机自启） |
| `GET /health` | `{"status":"ok"}` |
| 无 key POST | **401**（鉴权生效） |
| 带 key POST | 正常推理 |
| 运行参数 | 全部走 `/etc/laya/laya.env`（600）；`Environment=` 会吃掉 JSON 引号，已改用 EnvironmentFile |

## 缺口与风险

| 缺口 | 影响 | 何时处理 |
| --- | --- | --- |
| **样本只有 7 条** | 阈值 0.7 的依据太薄 | 真实抓取跑起来后按实际分布复测 |
| **未做温度拟合** | 概率不能当准确率解读（官方 zh-CN ECE 0.219） | 若要调低 `min-confidence` 必须先做 |
| **未接遥测** | 判定耗时/失败率/降级次数不可观测 | 与 WORK-025 一起接 WORK-022 |
| **规则词表只覆盖 Java 生态** | Rust/Go 等技术帖命中 0 会被直接判否 | 扩语种时扩词表 |
| **并发 16 时 p95 达 2965ms** | 若将来改成批量并发调用会贴近超时线 | 真需要高并发时调 `LAYA_THREADS` 或加实例；当前串行场景用不到 |
| **单点部署** | 进程挂了下一次请求降级到规则 | 已验证崩溃 6 秒自愈、重启 18 秒就绪，暂不需要多实例 |
| **Firecrawl 容器不自启**（非本项） | 整机重启后 6 个容器需手动拉起 | 建议给 `/opt/firecrawl` 的 compose 加 `restart: unless-stopped` |

## 业务验收

待用户确认。本报告只证明技术验证通过，不代表业务验收。
