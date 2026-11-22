---
artifact: tasks
work_id: WORK-026
work: 决策模型接入（Laya）
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-025, WORK-009, WORK-022]
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 11:31:38 +0800
approver_role: CTO
---

# 任务 · 决策模型接入（Laya）

**排序原则**：先在服务器上把模型跑起来并能用 curl 打通（S1/S2），再写 Java（S3），最后收成 systemd（S4）。**如果模型本身跑不通，后面三件全白做**——这是唯一有真实不确定性的部分。

**全局约束**：不新增 Maven 依赖；不改数据库、不加迁移；不动 `paideia` 服务与 Firecrawl 的配置；API key 与权重不进仓库。

## 任务

### TASK-001 | done | 服务器环境与权重

在 `codenest-online` 建 `/opt/laya-test` venv，装 **CPU 版** torch（默认会拉 CUDA 版 2.5G）与 laya 0.4.12；用 curl 直下 `laya-multilingual` 的 6 个文件到 `/opt/laya-test/models/multilingual`。

**验收**：`/opt/laya-test/bin/python -c 'import torch,laya; print(torch.__version__, laya.__version__)'` 输出 `2.14.1+cpu 0.4.12`；模型目录 647MB 且含 `model.safetensors`/`rl_agent_config.json`/`tokenizer/`/`encoder/`。

### TASK-002 | done | laya-serve 起得来并能回答

用 `LAYA_EXTRA_MODELS` 指向本地权重，`LAYA_PORT=8091`、`LAYA_THREADS=2` 起 `laya-serve`。

**验收**：`curl /health` 返回 `{"status":"ok"}`；中文面经返回 `kind=technical` 且 `P(technical)≥0.9`。

### TASK-003 | done | 判定原语选型（choice vs noul）

对同一批样例比较两种原语的概率稳定性，定下用 `choice`。

**验收**：记录 noul 的漂移（同一文本改三个字 0.243→0.675）与 choice 的稳定性（7 条对 6 条，P 0.95~0.98）。结论写进 design.md 的「判定原语的选择」。

### TASK-004 | done | 端口与实现

新增 `ContentClassifier`（根包）+ `LayaContentClassifier`（`internal.decision`），用 JDK 自带 `HttpClient`，失败一律返回 empty。

**验收**：`enabled=false`、地址不可达、HTTP 401、JSON 缺字段四种情形都返回 empty 且不抛。

### TASK-005 | done | 技术内容过滤器

新增 `TechEssayFilter`：规则数不同技术词种类数，`≥5` 直接判是、`0` 直接判否、`1..4` 才问模型。

**验收**：命中 0 种时不调用模型（用会抛异常的桩验证）；边界地带确实交给模型；模型不可用时退回规则。

### TASK-006 | done | 配置与默认值

`DecisionProperties` + `DecisionConfiguration` + `application.yml` 的 `paideia.ai.decision.*`，**默认关闭**。

**验收**：默认配置下应用行为与没有本项时一致。

### TASK-007 | done | 测试

单测 6 条（`TechEssayFilterTest`）+ 真连测试 2 条（`LayaContentClassifierLiveTest`，`@EnabledIfSystemProperty` 默认跳过）。

**验收**：单测 6 运行 / 0 失败 / 0 跳过；真连测试默认不跑，加 `-Dpaideia.laya.live=true` 时能真打服务。

### TASK-008 | done | systemd 单元与开机自启

`/etc/systemd/system/laya.service` + `/etc/laya/laya.env`（600，含生成的 API key）。运行参数全部走 EnvironmentFile——`Environment=` 会吃掉 JSON 的引号。

**验收**：`systemctl is-enabled laya` = enabled、`is-active` = active；无 key 请求返回 401；有 key 能推理；`MemoryMax=3G` 生效。

### TASK-009 | done | 端口化与降级语义的真连验证（服务器侧）

在服务器上复刻 Java 类的完整请求体，跑 7 条中文样例。

**验收**：技术面经 4/4 判对（P 0.957~0.980）；纯求职帖 3 条里 2 条正确落到 career；记录已知判错 1 条（「如何提高工程能力」P=0.953，但规则层命中 0 种技术词，实际不会误收）。

## 评审焦点

| 失败模式 | 为什么值得单独看 | 现有覆盖 |
| --- | --- | --- |
| **模型判"非技术"被当成否决** | 会让有技术词的帖子被丢掉 | `borderlineCaseDefersToModel` 验"模型不可用时退回规则"；但**没有**验"模型明确说 career 时规则仍能救回" |
| 阈值 0.7 与真实分布不匹配 | 太松会把闲聊收进来，太紧会让模型永不生效 | 没有自动覆盖；靠 `LayaContentClassifierLiveTest` 的 7 条样例，样本小 |
| 规则词表漏词 | 新框架（如 Rust、Go）不在词表里 → 命中 0 → 直接判否 | 未覆盖。词表当前只含 Java 生态 + 通用概念 |
| 冷启动期批量失败 | 定时任务恰好在服务刚重启时跑 | 未覆盖。降级到规则是设计意图，但没有任何日志计数 |
| 正则回溯性能 | 词表是一个大 alternation，长文本可能有性能问题 | 未覆盖。当前文本已截断到 4000 字符 |

## 未包含

- 容器化部署（官方 compose 路径未采用）
- 温度拟合与微调
- 接入 WORK-022 遥测
- 内容审核、提示注入防护、场景路由、记忆相关性判断
- 多实例与负载均衡
- `english` / `typed-decisions` 检查点（只装了 multilingual）
