---
artifact: design
work_id: WORK-026
work: 决策模型接入（Laya）
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-025, WORK-009, WORK-022]
created: 2026-10-20
updated: 2026-10-20
approved_by: xiaou61
approved_at: 2026-10-20 11:31:33 +0800
approver_role: CTO
---

# 设计 · 决策模型接入（Laya）

## 系统上下文

```
WORK-025 每日抓取
        │  面经正文（来自牛客列表 API）
        ▼
 TechEssayFilter.isTechnical(text)
        │
        ├─ 规则层：数「不同技术词种类数」hits
        │     hits >= 5  → true（不问模型）
        │     hits == 0  → false（不问模型）
        │     1..4       → 交给模型
        │
        ▼
 ContentClassifier.classify(text)          ← 端口（根包，无框架类型）
        │
        ▼
 LayaContentClassifier                     ← 实现（internal.decision）
        │  POST http://127.0.0.1:8091/v1/systemone
        ▼
 laya-serve（systemd: laya.service，仅绑 127.0.0.1）
        │
        ▼
 /opt/laya-test/models/multilingual（mmBERT-base，647MB）
```

## 为什么规则在前

| | 规则 | 模型 |
| --- | --- | --- |
| 延迟 | ~0ms | 150~260ms |
| 成本 | 0 | 0（本地）但吃 CPU |
| 稳定性 | 不会挂 | 可能超时/格式变更 |
| 准确度 | 高置信区间准，边界不准 | 边界较好 |

如果反过来（先问模型），每条帖子都要付一次延迟与 CPU，而其中大部分是规则一眼就能判的。「几乎找到实习了」这类纯信息帖（命中 0 种技术词）根本不该走到模型。

**边界 1..4 的选择**：`MIN_DISTINCT_TERMS=3` 是判定线，但只有落在这个小区间才问模型——`hits >= 3+2` 直接判是、`hits == 0` 直接判否，中间地带才值得花 200ms。

## 判定原语的选择：choice 而非 noul

这是本项最重要的技术决定，**由实测倒逼**：

同一段面经 `面经：1、HashMap为什么线程不安全 2、说说JVM内存模型 3、MySQL索引失效的场景`，用 `noul`（是非题）问"这段是不是技术面试题"：

| 输入结尾 | noul 概率 |
| --- | --- |
| `…索引失效的场景` | 0.243 |
| `…索引失效的场景有哪些` | **0.675** |
| `…索引失效的场景有哪些 4、Spring事务传播` | 0.644 |

多三个字就翻近三倍。**任何固定阈值在这种漂移下都不可用。**

换成三分类 `choice`（technical / career / other）后同一批样例：

| 样例 | choice | P(technical) |
| --- | --- | --- |
| HashMap/JVM/MySQL 面经 | technical | 0.980 |
| MySQL B+树与事务 | technical | 0.957 |
| 拼多多服务端一面 | technical | 0.978 |
| 从零学 Java 变量类型 | technical | 0.978 |
| 「终于找到实习了！」 | career | 0.002 |
| 恒生电子二面闲聊 | career | 0.231 |
| 「如何提高工程能力」 | technical | **0.953（判错）** |

7 条判对 6 条。原因推测是 softmax 在选项间归一化，给了一个稳定的相对基准；而孤立的 noul 是一个未校准的绝对概率。

## 端口签名

```java
public interface ContentClassifier {
    Optional<ContentClassification> classify(String text);
}
```

**返回 Optional 而不是抛异常**，与 `EntrySummarizer` 相反。理由：精简失败意味着"这次没生成内容"，是业务失败；判定失败意味着"这次没判断出来"，是**可降级**的——退回规则即可。用异常表达可降级的东西会让调用方到处 try-catch。

## 失败与降级矩阵

| 情形 | 行为 | 结果 |
| --- | --- | --- |
| `enabled=false` | 直接返回 empty | 规则判定 |
| 连接失败 / 超时 | 返回 empty，首次打一条 warn，后续静默 | 规则判定 |
| HTTP 非 200 | 同上（含 401 鉴权失败） | 规则判定 |
| JSON 缺 `kind.choice` | 同上 | 规则判定 |
| 判为 career/other | 返回 empty | 规则判定 |
| P(technical) < 0.7 | 返回 empty | 规则判定 |
| 判为 technical 且 P ≥ 0.7 | 返回 present | 判为技术帖 |

**注意最后一条的语义**：模型说"不是技术"时并不直接否决，只是"没判断出来"，最终由规则定。这是刻意的——模型在边界上比规则强，但在明显有技术词的帖子上，规则不该被一个概率否决。

## 配置

```yaml
paideia.ai.decision:
  enabled: false            # 默认关，部署时显式打开
  base-url: http://127.0.0.1:8091
  api-key: ""               # 部署注入，不进仓库
  timeout-ms: 3000
  min-confidence: 0.7
  max-text-chars: 4000
```

## 安全边界

- **只绑回环**：`LAYA_HOST=127.0.0.1`。该服务除 `LAYA_API_KEY` 外无鉴权，暴露到公网等于给一个开放推理端点。
- **API key 放 `/etc/laya/laya.env`（600）**，仓库里只有空占位。
- **不跟随重定向**：地址来自部署配置，允许跟随会让一次配置错误变成静默跳转。
- **不复用 `GuardedHttpTransport`**：那条防线是给"用户自己填的 baseUrl"准备的（SSRF）。这里的地址不是用户输入，是非用户可控的部署常量，两条信任边界不同。

## 资源占用（实测）

| 项 | 值 |
| --- | --- |
| 常驻内存 | 1.5~1.7 GB |
| `MemoryMax` | 3 G（超限被杀重启，保护同机 MySQL/沙箱） |
| 磁盘 | torch-cpu 903MB + 模型 647MB + venv 其余 ≈ 1.6 G |
| 冷启动 | 16~22 秒 |
| 热态单条 | 147~260 ms（`LAYA_THREADS=2`，4 核） |
| 线程数 | 2（官方基准：超过物理核数反而变慢，SMT 争抢） |

## 部署形态

systemd 单元 `/etc/systemd/system/laya.service`：

- `Type=simple`、`Restart=on-failure`、`RestartSec=5`
- 全部运行参数走 `EnvironmentFile=/etc/laya/laya.env`
  - **不能用 `Environment=`**：多行/带引号的 JSON 会被 systemd 的解析吃掉，`LAYA_EXTRA_MODELS` 是 JSON，必须走 env 文件
- `HF_HUB_OFFLINE=1`：权重已落地，禁止启动时联网
- `TimeoutStartSec=180`：冷启动要 16~22 秒

## 关键取舍

| 取舍 | 选择 | 代价 |
| --- | --- | --- |
| venv vs 容器 | venv + systemd | 不享受官方镜像的构建封装；但不用重建镜像，本机改一行就能试 |
| 官方 compose 的 modelscope 烘焙 | 未用 | 那是容器路径；本机直接用 curl 下权重更快（5.6MB/s vs 客户端 20MB/min） |
| 只挂 multilingual | 未装 english/typed-decisions | 中文是主场景；多挂一个多吃 1G 内存 |
| 不做温度拟合 | 阈值给 0.7 且注释写明不可当准确率 | 概率只能用排序，不能当置信度对外展示 |

## 已知缺口

1. **未接遥测**（WORK-022）：判定耗时、失败率、降级次数都不可观测。
2. **未做温度拟合**：概率偏乐观（官方 zh-CN ECE 0.219），所以代码里所有注释都写明"这不是准确率"。
3. **样本太小**：7 条样例定阈值，真实抓取量上去后必须复测。
4. **单点**：只有一台机器一个进程，没有多实例与负载均衡（当前量级不需要）。
