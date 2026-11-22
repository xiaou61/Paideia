---
artifact: test-report
work_id: WORK-001
work: 架构选型与项目骨架
status: passed
evidence: required
verified_commit: 0df2f9c
verified_at: 2026-10-17
created: 2026-10-09
updated: 2026-10-12
accepted_by: xiaou61
accepted_at: 2026-10-09 13:43:08 +0800
---

# 架构选型与项目骨架验证报告

> **2026-10-12 复验**：本报告原锚定 `77b8290`（2026-10-09）。此后 WORK-002/003/004/005 与前端拆址陆续改了源码，代码核对判为 `stale`。本次在 `412ffe9` 上按同一套验收标准**重跑全部可自动化检查**，全部通过；锚点更新为 `412ffe9`。下面的数字与证据均为 2026-10-12 复验的实测值（原文的 38 条测试是骨架期，模块数与用例数此后增长）。

## 验证环境

- 复验时间：2026-10-12 13:44–13:54（+0800）。
- 本机：Windows，JDK 25.0.4（Temurin）、Maven 3.9.0、Node 24.14.1、pnpm 12.10.1、Electron 44.7.0。
- 后端运行 profile：`local`（数据源指向本机 3307 隧道到服务器 MySQL）。
- 数据库：服务器上的 MySQL 8.0.46，测试库 `paideia_test`（经 SSH 隧道 127.0.0.1:3307）。测试库口令从被忽略的 `backend/config/application-local.yml` 读入环境变量 `PAIDEIA_TEST_DB_PASSWORD`，**未回显**。
- 前端：React 19.3.0、Vite 8.3.4、Vitest 5.0.3、Playwright 1.64.0。

## 验证结果

- 后端全量 `mvn -B verify`：**159 条测试 0 失败 0 错误 0 跳过**，8 个代码模块全部 SUCCESS，耗时 **1:44**，退出码 0（`logs/backend-verify.txt`）。
  - 模块分布：app 67、ai 26、knowledge 22、account 13、security 11、platform 7、persistence 4、web 9。
  - 集成用例本次**真实执行**（非跳过）：需 `PAIDEIA_TEST_DB_PASSWORD` 才启用，未设时会静默跳过 66 条——第一次跑就踩到这一点（`Skipped: 66`），设上后才得到 0 跳过。
- 前端单测 `pnpm -r test`：**66 条通过**（core 14、ui 17、knowledge 15、auth 13、platform-desktop 4、assistant 3），退出码 0（`logs/frontend-checks.txt`）。
- 类型检查 `pnpm -r typecheck`：10 个包全过，退出码 0（`logs/frontend-checks.txt`）。
- 三端 e2e：**25 条通过**（学习者端 13、管理端 7、展览页 5），串行共用一个后端，退出码 0（`logs/frontend-surfaces.txt`）。
- 公开页预渲染：`pnpm --filter @paideia/public build` 退出码 0，向 `index.html` **注入 1439 个字符**正文（`logs/frontend-surfaces.txt`）。
- 桌面产物：`pnpm -w build:desktop`（双镜像）退出码 0，产出 `Paideia Setup 0.0.1.exe` **111,603,891 字节**、`app.asar` 746,799 字节（`logs/desktop-packaged.txt`）。
- 桌面运行时（打包产物）：渲染服务 `GET http://127.0.0.1:5310/` = **200**；白名单来源 `http://127.0.0.1:5310` 访问 `/actuator/health` 获 `Access-Control-Allow-Origin`；未列入来源被拒 **403**；`/actuator/health` = `{"groups":["liveness","readiness"],"status":"UP"}`（`logs/runtime-and-desktop.txt`）。
- surface 隔离（双向交叉检索，`logs/artifact-exclusion.txt`）：公开页产物含「这个页面为什么单独存在」2 处、不含「AI 精简」；桌面 `app.asar` 含「AI 精简」1 处、「我标记过的内容」1 处，不含公开页文案、不含「知识库管理」（管理端）。方法自证有效（阳性文案在对应产物里能搜到）。

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 技术选项覆盖与裁决留痕 | 人工检查登记表与已裁决清单（`ls` 复核两文件在库） | manual | passed | `.agent/references/technology-options.md`、`.agent/rules/always.md` |
| AC-002 构建与健康检查 | `mvn -B verify`；`java -jar … --spring.profiles.active=local` + `curl /actuator/health` | 0 | passed | `logs/backend-verify.txt`、`logs/runtime-and-desktop.txt` |
| AC-003 浏览器端到端 | `MSYS_NO_PATHCONV=1 pnpm --filter @paideia/{app,admin,ui-kit} test:e2e` | 0 | passed | `logs/frontend-surfaces.txt`（13+7+5 passed） |
| AC-004 模块边界构建期强制 | `mvn -B verify`（`ModularityTest`，ArchUnit，1 条） | 0 | passed | `logs/backend-verify.txt` |
| AC-005 MySQL 分页与归属过滤 | `mvn -B verify`（`PaginationIntegrationTest`，4 条） | 0 | passed | `logs/backend-verify.txt` |
| AC-006 授权隔离与越权拒绝 | `mvn -B verify`（`AuthorizationIsolationTest`，9 条） | 0 | passed | `logs/backend-verify.txt` |
| AC-007 桌面端运行时（渲染服务、调用后端、CORS） | 启动 `release/win-unpacked/Paideia.exe`；`curl http://127.0.0.1:5310/`；带 `Origin` 探测 CORS | 0 | passed | `logs/runtime-and-desktop.txt`（渲染 200、ACAO 存在、非白名单 403） |
| AC-007 Windows 安装包产出与打包应用运行 | `ELECTRON_MIRROR=… ELECTRON_BUILDER_BINARIES_MIRROR=… pnpm -w build:desktop`；随后启动安装包内容 | 0 | passed | `logs/desktop-packaged.txt`（安装包 111,603,891 字节）、`logs/runtime-and-desktop.txt` |
| AC-008 公开页预渲染与 surface 隔离 | `pnpm --filter @paideia/public build` + 产物交叉检索 | 0 | passed | `logs/frontend-surfaces.txt`（注入 1439 字符）、`logs/artifact-exclusion.txt` |

关键数值（2026-10-12 复验）：

- 后端 `mvn -B verify`：8 个代码模块全部 SUCCESS，共 **159** 个测试、0 跳过，耗时 1:44。
- `/actuator/health` 返回 `{"groups":["liveness","readiness"],"status":"UP"}`（与骨架期一致）。
- CORS：白名单来源获得 `Access-Control-Allow-Origin`；未列入来源被拒 403。
- 桌面端：打包产物渲染服务 200，`app.asar` 746,799 字节。
- 公开页：预渲染注入 1439 个字符正文（骨架期是 699，内容此后增长）；公开页产物与桌面产物双向无交叉文案。
- 安装包：`Paideia Setup 0.0.1.exe` **111,603,891** 字节。**构建必须同时设置 `ELECTRON_MIRROR` 与 `ELECTRON_BUILDER_BINARIES_MIRROR`**，只设后者仍会去 GitHub 拉 Electron 运行时并超时。

## 验收结果

- AC-001 至 AC-008：**全部通过**（2026-10-12 复验）。
- 骨架期 AC-007 曾记为"部分通过"（安装包一度因网络未产出）；此后已真实产出，本次复验同样为 passed，故状态归为全通过。

## 失败与未验证项

1. **未运行：`frontend/apps/desktop` 的 Playwright Electron 用例**。本环境不允许 node spawn `cmd.exe`（实测 `error=ENOENT`），而 Playwright 在 Windows 上经 cmd.exe 启动 Electron。用例已保留，在普通 shell 或 CI 中应可运行。桌面端运行时结论由打包产物上的渲染服务探测、CORS 探测与健康检查给出（见 AC-007 两行）。
2. **未做性能与容量验证**。

## 剩余风险

1. **授权隔离的端到端验证建立在测试作用域示例表之上**，不是真实业务实体（`AuthorizationIsolationTest` 仍是示例表）。第一个带归属的业务功能落地后必须迁到真实实体，否则这条回归会逐渐失去代表性。
2. **前端构建产物未做体积预算与控制**。桌面端打包 Chromium 后安装包偏大（Electron 的既定代价），当前无监控。
3. **`paideia.auth.secret` 缺失时生成一次性随机密钥并告警**。部署到面向他人的环境而不配密钥时，重启会让所有令牌失效——部署前需人工确认。
4. **`POST /api/v1/auth/token` 不校验凭据**，仅靠 profile（dev/local）把守（WORK-003 已引入真实登录；该端点仍只应在开发/本地 profile 暴露）。
5. **多数据库适配已由用户决定不做**，数据访问层只面向 MySQL（已接受的取舍）。
6. **依赖服务器的 SSH 隧道做集成测试**，隧道不可用时相关用例静默跳过（有 stderr 提示），存在"本地看着绿、实际没验 SQL"的可能。复验时以 `Skipped: 0` 与 `EXIT=0` 双重确认集成用例真的跑了。

## 2026-10-17 重新核对（结算）

骨架契约未变：`PlatformContractTest` 7/0、`ModularityTest` 1/0、授权隔离 9/0 全绿。锚点之后新增的业务模块（知识库、AI、账号等）不改变本项确立的模块边界与对外契约。同一轮全量后端里唯一的红是 `AssistantConversationIntegrationTest` 的 MockMvc 响应头竞态（见 WORK-006 报告），与本项无关。

## 2026-10-17 积分闸回滚修复后的复核

本轮改动落在 `ConversationService.prepare`（AI 模块内部），随后重跑本项证据：`PlatformContractTest` 7/0、`ModularityTest` 1/0、`paideia-platform` 模块 11/0（含 `SearchQueryTest` 4 条）。模块边界与对外契约未变。

锚点改绑到 `e941bcb`。

## 2026-10-17 测试基建改动后的复核

本轮改动是测试基建：测试库改本机、四个前端包换 happy-dom、会话集成测试改用同步执行器。
业务代码未变。重跑结果：后端全量 **257 条 / 0 失败 / 0 error / 4 跳过**（墙钟 1 分 31 秒），
前端单测 **176 条全过**，`typecheck` 干净。

锚点改绑到 `d184e36`（其后只有 `.agent/` 工件变化）。

## 复验记录（2026-10-18，锚点 0df2f9c）

**为什么会有这一段**：原报告的证据是在更早的提交上采的，之后仓库里又落了 WORK-014／015／016 三个工作项
（源码与配置都变过），校验器因此把本报告判为 `stale`。本轮在**当前 HEAD**（`9c99033`）上重跑了与本项
**受影响面**相关的那几组检查，逐条结果如下。**没有改任何功能代码**。

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 模块边界构建期强制 | `mvn -B test`（`ModularityTest`，ArchUnit） | 0 | passed | 143 运行 / 0 失败 / 4 跳过（后端全量，独立验证库） |
| 平台契约 | `mvn -B test`（`PlatformContractTest`） | 0 | passed | 同上；`paideia-platform` 模块全绿 |
| 授权隔离与越权拒绝 | `mvn -B test`（`AuthorizationIsolationTest`） | 0 | passed | 同上 |

**这段区间里本项的受影响面**：`ErrorCode` 新增 `SANDBOX_DISABLED`、`GlobalExceptionHandler` 加一条映射、`SecurityConfiguration` 加沙箱与 PWA 入口两类规则、根 `pom.xml` 加一个模块——**都发生在既有契约之上**（追加枚举值/新增 matcher），模块边界与对外契约的形态未变。

**结论**：骨架契约未变：`ModularityTest`、`PlatformContractTest`、`AuthorizationIsolationTest` 全绿。锚点之后新增的 `paideia-sandbox` 模块与两条安全规则都**追加在既有结构之上**，没有推翻本项确立的模块边界。
