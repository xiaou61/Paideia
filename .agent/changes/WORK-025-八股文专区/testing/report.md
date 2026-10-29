---
artifact: test-report
work_id: WORK-025
work: 八股文专区
status: passed
evidence: required
workflow: full
created: 2026-10-20
updated: 2026-10-20
accepted_by: xiaou61
accepted_at: 2026-10-29 13:19:10 +0800
---

# 八股文专区验证报告

## 验证环境

| 项 | 值 |
| --- | --- |
| 后端 | 本机 JDK 25，`scripts/test-db.sh` 分配独占库 |
| 抓取目标 | 牛客 `job-experience/experience/job/list`（匿名，无 Cookie） |
| AI | 线上平台凭据 `deepseek-flash`（`/opt/paideia-app/application.yml`） |
| 决策模型 | `codenest-online` 的 systemd `laya.service`（`127.0.0.1:8091`） |
| 本机限制 | 抓取与 AI 调用在服务器侧执行（本机到生产内网端口不可达） |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 定时任务可运行（两个自然日有批次） | `systemctl` 未启用，改为手动触发 `runOnce()` | 人工 | passed | 端到端脚本一次跑通全链路；**定时任务本身未在生产启用**（`enabled: false`），故「连续两日」未实测 |
| AC-002 同题跨天不增长 | **线上连续两次运行**（`systemctl restart` 触发） | 0 | passed | 第一次「新建 5 条，重复 0 条」→ 第二次「**新建 0 条，重复 5 条**」，库里稳定 5 条。**这条最初是错的，见下方「线上发现的设计缺陷」** |
| AC-003 每条有解析且非原文复制 | 服务器侧真调 DeepSeek | 0 | passed | 抽出「如何可靠传递 ThreadLocal 上下文」+ 466 字解析；`render()` 只拼题目与解析，不含帖子正文 |
| AC-004 专区可发现、有来源链接 | `npx tsc --noEmit -p packages/knowledge` | 0 | passed | `EntryDetail.sourceUrl` 已加；详情页渲染「题目来源：原帖」外链。**未做 e2e 点进去看** |
| AC-005 抓取源不可达时失败可读、不影响其它页面 | 端口内 try/catch + 返回 `skipReason` | 0 | passed | `EssayCollectionService` 各阶段接住异常并记日志；`EssayCollectionJob` 再兜一层，不让调度器炸 |
| AC-006 平台精讲与真题无重复 | 本项未建「平台精讲」 | 人工 | passed | Q7 取值：一期不建该分类，无重复可能 |
| AC-007 正文不含个人信息 | 真调抽查 | 人工 | passed | 实测产出仅含技术题干与解析，未出现公司名/薪资/昵称 |
| AC-008 密度不足跳过且计数 | `mvn test -Dtest=TechEssayFilterTest` | 0 | passed | 6 条单测；真跑时 14 篇里规则直判技术帖 2 篇 |
| AC-009 单一分类不细分 | 迁移 V24 | 0 | passed | 只插入 `essay-java` 一个分类 |
| V24 迁移可执行 | `EssayCollectionIntegrationTest` 建表即启动 | 0 | passed | Spring 上下文启动时 Flyway 跑 V24，测试通过 |
| 解析器边界 | `mvn test -Dtest=EssayQuestionParserTest` | 0 | passed | 8 条：空数组合法 / 空内容故障 / 围栏剥离 / 单条丢弃 / 超长拒绝 |
| 指纹归一化 | `mvn test -Dtest=EssayFingerprintTest` | 0 | passed | 4 条：序号标点大小写归一 / 不同题不撞 / 中文引号 / 稳定 64 位 |
| 全量回归 | `scripts/test-db.sh mvn -pl paideia-ai,paideia-knowledge -am test` | 0 | passed | **148 运行 / 0 失败 / 3 跳过**（跳过为 `LayaContentClassifierLiveTest`） |
| 牛客接口可用 | 服务器 `curl` | 0 | passed | 200 / 0.54s / total 2000 / 当日 4 条新帖 |
| 抓取数量与过滤 | 端到端脚本 | 0 | passed | 面经帖 14 篇 → 规则直判技术帖 2 篇（得物长沙 hits=8、拼多多 hits=5） |
| 决策模型判定 | 服务器 `curl /v1/systemone` | 0 | passed | 首条 `choice=technical`，`P(tech)=0.829` |
| AI 抽题（含截断修复） | 服务器 `python3 /tmp/extract2.py` | 0 | passed | 4.1s / 输出 665 tokens / `finish=stop` / 1 题 / 解析 466 字 |

## 实测数据：token 截断（本项最关键的发现）

初版提示词要求"抽出所有技术题、每篇解析 200~600 字"：

| max_tokens | 结果 |
| --- | --- |
| 4096 | `Unterminated string starting at char 2560` —— 输出被截断 |
| 8192 | `Unterminated string starting at char 12843` —— **同样吃满** |

根因：deepseek-flash 默认思考，思考 token 与正文共用 `max_tokens`。

修正：提示词压到"**只挑 1 道题**、解析 150~300 字" + `MAX_TOKENS=16384` 作余量。修正后：

```
耗时 4.1s 输入 738 输出 665 finish=stop
抽出: 1
  题: 在线程池中执行任务时，如何可靠地传递 ThreadLocal 上下文？
  解析 466 字: 结论：不能依赖 ThreadLocal 本身，必须显式包装任务传递上下文……
```

## 线上发现的设计缺陷（已修）

**按标题去重是错的。** 原设计按"题目文本"算指纹，理由是"同一道题会在不同面经里反复出现"。
线上实测暴露了它：

| 运行 | 日志 | 库内 |
| --- | --- | --- |
| 第一次（15:57） | 新建 4 条，重复 0 条 | 4 条 |
| 第二次（16:13，重启触发） | 新建 4 条，重复 0 条 | **8 条** |

同一篇帖子两次抽出的标题措辞变了：
- 第一次「线程池中如何可靠地传递 ThreadLocal 上下文？」
- 第二次「在线程池中如何可靠地把父线程的 ThreadLocal 上下文传给子线程？」

意思相同、文本不同 ⇒ 指纹不同 ⇒ 每次运行都新增。**模型复述的措辞本来就不该被当成身份标识。**

修法：改按 `externalId`（上游给的内容 id，不漂移）算指纹，加来源前缀以防将来多站撞号。
代价是同一道题出现在两篇不同面经里会存两份——当前一篇只抽一道题，量很小，可接受。

修复后复测：第一次「新建 5 条」→ 第二次「新建 0 条，重复 5 条」，稳定。

## 缺口与风险

| 缺口 | 影响 | 何时处理 |
| --- | --- | --- |
| ~~**定时任务未在生产启用**~~ | 不会自动抓 | **2026-10-21 核实：线上已 `enabled: true`**，已产出 5 条（全为 draft，因 `publish-directly: false`，待管理端过目后发布） |
| ~~**无 e2e**~~ | 「从知识库点进专区看到条目」这条界面路径没验 | **2026-10-21 已补**：首页加了「八股文专区」入口（按 slug 解析分类，不写死 id），`home.spec.ts` 新增一条用例从卡片点进去并断言落到该分类页；打线上 8 passed |
| **一篇只抽 1 道题** | 覆盖率低（帖子通常有 5~20 题） | 可改成分批调用，但要先解决截断 |
| **未接遥测** | 抽题 token 消耗不可观测 | 与 WORK-022 一起 |
| **不扣积分** | 平台成本不在产品面板可见 | 设计取舍，非缺陷 |
| 分类并发判重窗口 | 单实例定时任务下不会触发 | 多实例时再加唯一约束 |

## 业务验收

待用户确认。本报告只证明技术验证通过。
