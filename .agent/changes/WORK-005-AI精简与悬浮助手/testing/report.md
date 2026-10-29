---
artifact: test-report
work_id: WORK-005
work: AI 精简与悬浮助手
status: passed
evidence: required
created: 2026-10-12
updated: 2026-10-12
accepted_by: xiaou61
accepted_at: 2026-10-29 13:16:53 +0800
---

# AI 精简与悬浮助手验证报告

## 验证环境

- 后端：JDK 25 / Spring Boot 4.1.1；AgentScope 2.0.0（core + OpenAI 扩展，同版本号）；MySQL 8.0.46（自有服务器，经本机 `127.0.0.1:3307` 的 SSH 隧道），验证库 `paideia_test`。
- 前端：pnpm 12.10.1 workspace；Playwright chromium；e2e 复用一个手工启动的后端。
- 测试库口令从被忽略的 `backend/config/application-local.yml` 读入环境变量，**未回显**。
- 凭据主密钥在测试 profile 里用一把**测试专用**值（明文即 `paideia-test-ai-master-key-00001`），与测试 JWT 密钥同理：任何真实环境都必须用自己的。

## 验证结果

- 后端全量 `mvn -B verify`：**159 条测试 0 失败 0 错误 0 跳过**（app 67、ai 26、knowledge 22、account 13、security 11、platform 7、persistence 4、web 9），约 2 分钟，退出码 0（`logs/backend-verify.txt`）。
- 前端单测 `pnpm -r test`：**66 条通过**（core 14、ui 17、knowledge 15、auth 13、platform-desktop 4、**assistant 3**），退出码 0（`logs/frontend-unit.txt`）。
- 类型检查 `pnpm -r typecheck`：10 个包全过，退出码 0（`logs/frontend-typecheck.txt`）。
- 三端 e2e：**25 条通过**（学习者端 13、管理端 7、展览页 5）（`logs/e2e-app.txt`、`logs/e2e-admin.txt`、`logs/e2e-ui-kit.txt`）。
- 桌面产物：构建退出码 0；`app.asar` 里「管理端」**0** 次、「AI 精简」**1** 次（`logs/build-desktop.txt`）。
- 反向验证两条都咬住（见下文「反向验证」一节），证据 `logs/reverse-verification-ssrf.txt`、`logs/reverse-verification-cache.txt`。

各验收标准的逐条命令、退出码、结果与证据位置见下表。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 触发精简并在壳中显示 | `pnpm --filter @paideia/app test:e2e`（assistant.spec.ts）+ `mvn -B verify`（集成） | 0 | passed | `logs/e2e-app.txt`（13 passed）、`logs/backend-verify.txt` |
| AC-002 凭据加密落库、响应与日志无明文 | `mvn -B verify`（`credentialIsEncryptedAndNeverEchoed`：只回 last4、响应不含 Key、库中密文不含明文；`CredentialCipherTest` 5 条） | 0 | passed | `logs/backend-verify.txt` |
| AC-003 命中缓存不产生第二次调用 | `mvn -B verify`（`generatesThenServesFromCache` 用 `verify(times(1))` 证明） | 0 | passed | `logs/backend-verify.txt` |
| AC-004 私有不可读、公开可读 | `mvn -B verify`（`visibilityIsEnforced`） | 0 | passed | `logs/backend-verify.txt` |
| AC-005 无凭据可读不可生成 | `mvn -B verify`（`keylessUserCanReadButNotGenerate`）+ `pnpm --filter @paideia/app test:e2e`（按钮禁用 + 提示"需要你自己的模型凭据"） | 0 | passed | `logs/backend-verify.txt`、`logs/e2e-app.txt` |
| AC-006 正文改动后旧结果失效 | `mvn -B verify`（`contentChangeInvalidatesCache`） | 0 | passed | `logs/backend-verify.txt` |
| AC-007 非法 baseUrl 被拒且请求未发出 | `mvn -B verify`（`rejectsInternalBaseUrlBeforeAnyCall`：400 + `verify(never())`）+ `UpstreamGuardTest` 16 条 | 0 | passed | `logs/backend-verify.txt` |
| AC-008 构建、边界检查与桌面产物 | 上表四行 + 既有的 `source-coverage`／`tokens`／`contrast`／`workspace-boundaries` | 0 | passed | `logs/frontend-unit.txt`、`logs/build-desktop.txt` |
| AC-009 结构化字段校验 | `mvn -B verify`（`SummarySchemaValidatorTest` 5 条：缺字段、空小节、非 JSON、**空内容**） | 0 | passed | `logs/backend-verify.txt` |
| AC-010 用量返还；命中缓存标"未产生调用" | `mvn -B verify`（用量 42、缓存命中时 `usage` 为 null）+ `pnpm --filter @paideia/assistant test`（文案断言不含 "0 tokens"） | 0 | passed | `logs/backend-verify.txt`、`logs/frontend-unit.txt` |
| 质量要求：错误不泄露上游报文 | `mvn -B verify`（`failureIsNotCached` 断言错误体不含上游原文） | 0 | passed | `logs/backend-verify.txt` |
| 质量要求：SSRF 是信任边界（含传输层二次校验） | 见 AC-007；传输层装饰 `GuardedHttpTransport` 每次出站前再校验 | 0 | passed | 源码 + `logs/backend-verify.txt` |
| 质量要求：并发写不把 500 抛给用户 | `mvn -B verify`（唯一键兜底 + 回读路径覆盖） | 0 | passed | `logs/backend-verify.txt` |

## 反向验证（都咬住了）

| 反向验证 | 做法 | 结果 | 证据 |
| --- | --- | --- | --- |
| SSRF 校验真的在跑 | 在 `UpstreamGuard.assertAllowed` 开头直接 `return`（临时） | **16 条挂 15 条**；仅"公网放行"那条照常通过（符合预期） | `logs/reverse-verification-ssrf.txt` |
| 缓存命中真的不再调模型 | 把 `SummaryService.generate` 的缓存命中分支改为"继续往下调用"（临时） | **`generatesThenServesFromCache` 失败**（调用次数断言咬住） | `logs/reverse-verification-cache.txt` |

两次临时改动均已用 `git checkout --` 精确还原，工作区无残留。

**一条过程教训（记下来比掩盖有用）**：第一次跑 SSRF 那条反向验证时漏了 `-am`，Maven 因解析不到兄弟模块直接失败——退出码非 0，看起来"咬住了"，其实**测试根本没跑**。重做后才得到真实的 15/16。判据不只是"红了"，而是"红在哪一行"。

## 实施偏离（都写回 `design.md` 了）

1. **结构化输出不走框架的合成工具回退**，改为"提示词要求返回 JSON + 字段级校验"。理由：自建工具 schema 与解析工具调用参数的面比收益大，而校验器本来就不可少。
2. **读缓存不按模型名过滤**（模型名只留在写入的唯一键里）。原设计把它当读过滤条件，实施时发现那样"没配凭据的用户永远读不到公开结果"，与 AC-005 直接冲突。

另外三处实施期修正（不改范围，只改实现）：容器里没有 `ObjectMapper` Bean（改为自建）；record 反序列化依赖参数名模块（改为按 JSON 树显式构造）；测试自身的数据与限流隔离（每用例清表 + 每组用例独立用户 id，否则会污染共享测试库、把知识库那边的断言弄红）。

## 未验证项与残余风险

- **未做真连上游的验证**（刻意）：测试全程用桩，真实调用只会在用户填了自己的 Key 之后发生。因此"与 DeepSeek 实际协议的契合度"没有自动化证据，只能靠人工冒烟。
- **DNS rebinding 未根治**：校验通过后到真正连接之间仍有解析窗口；根治要在连接层按已解析 IP 直连并自带 SNI，而 JDK 的 HttpClient 做不到两全（直连 IP 就会用 IP 校验证书）。触发重评：多实例部署或对外网开放时上网络层出口策略。
- **限流是单实例内存态**：重启清零、多实例各算各的（代码里有 `ponytail:` 注释标明天花板）。
- **孤儿行**：删条目/删用户后 `ai_entry_summaries` 留下不可达的行，无定时清理（与孤儿附件同类）。
- **主密钥轮换 = 全体用户重新填一次 Key**；主密钥泄露等于全部凭据泄露。
- **AgentScope 2.0.0 很年轻**（GA 三个月、仓库一年内 891 个未关 issue）：只用最小面（模型 + formatter，不用运行时），风险可控但不可免除。
- **证据文件在本机**：`testing/logs/` 被 `.gitignore` 忽略（与 WORK-001..004 一致），换台机器看不到这些日志。
