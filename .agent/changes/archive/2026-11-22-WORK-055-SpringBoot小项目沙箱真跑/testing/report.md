---
artifact: test-report
work_id: WORK-055
work: Spring Boot 小项目（沙箱里真跑 mvn test）
status: passed
evidence: required
workflow: full
created: 2026-11-01
updated: 2026-11-01
verified_commit: db295fc
accepted_by: xiaou61
accepted_at: 2026-11-01 11:05:33 +0800
---

# WORK-055 验证报告

**结论**：Spring 项目线 **3 关全部上线**（`content/courses/spring-boot-project`），判分是真跑 `mvn test`：
线上沙箱参考实现 **exit=0 / 15.2–17.9 秒**，starter **exit=1 且点名失败用例**；浏览器里打开第 3 关，
CodeMirror 编辑器（Java 高亮）与运行按钮都正常，点运行返回 ✗ 与"期望/实际"。
本地 `scripts/verify_spring_project.py` 对 3 关各跑两遍（starter / 参考实现），全部符合预期。

**未达**（如实记录）：AC-005 的"1 核 27 秒"是尖刺时的基线，线上是 2 核 1g（15–18 秒），
**没有在 1 核配置下复测**；并发争用（两个用户同时跑）仍未测。

## 验证环境

| 项 | 值 |
| --- | --- |
| 线上 | `codenest-online`（36.212.5.130:8081），JDK 25 / MySQL 8 / Docker |
| 沙箱镜像 | `paideia-spring:3`（989 MB，预烤离线 Maven 仓库 `/opt/m2`） |
| 沙箱参数 | `--network=none`、`--read-only`、`--cap-drop=ALL`、`--user 65534:65534`、`--memory=1g`、`--cpus=2`、超时 90 秒 |
| 本机 | Windows / Git Bash；Maven 3.9 + JDK 25（`release 21` 编译）；骨架副本 `scripts/spring-project-skeleton/` |
| 内容 | 3 关 + 总览；`category=spring-boot-project`、轨道 `spring-boot-project`（practice，3 关） |

## 验收矩阵

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 正确实现跑绿且含 surefire 汇总 | 线上 `POST /api/v1/sandbox/run`（`language=spring`，参考实现） | 0 | passed | 3 关全部 `exitCode=0`，stdout 含 `Tests run: 2, Failures: 0`；耗时 15803 / 16345 / 15240 ms |
| AC-002 错误实现失败并点名 | 同一请求换成 starter | 0 | passed | 3 关全部 `exitCode=1`，stdout 含 `<<< FAILURE!` 与失败用例名（2 failures / 2 errors / 1 failure）；浏览器里第 3 关显示"✗ 两条测试全绿"并列出期望与实际 |
| AC-003 未配置镜像时不给 spring | `DockerCommandTest`（21 条） | 0 | passed | `properties.languages()` 不含 spring；控制器 `isSupported` 为假时抛 `INVALID_ARGUMENT`（"只支持 …"），不是 500 |
| AC-004 沙箱参数不放松 | `DockerCommandTest`（21 条） | 0 | passed | 命令断言含 `--network=none`、`--read-only`、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、`--user 65534:65534`、`--stop-timeout=0` |
| AC-005 单次 ≤ 90 秒 | 线上 3 关 × 2 次 | 0 | passed | 全部 `timedOut=false`，15.2–17.9 秒（2 核）；超时档 `springTimeoutMs=90_000` |
| AC-006 前端渲染与 Java 高亮 | 线上浏览器探针（`/#/learn/spring-boot-project` → 第 3 关） | 0 | passed | `challenge-code` 与 `challenge-run` 可见且按钮可用；`.cm-editor` 已挂载（CodeMirror）；点运行后出现 `challenge-run-results` |
| AC-007 3 关 starter 不绿、参考解全绿 | `python scripts/verify_spring_project.py` | 0 | passed | 3 关：starter `exit=1`（失败原因各自可读）、参考实现 `exit=0`；线上复跑结论一致 |

## 测试与构建汇总

- 本地交叉验证：`python scripts/verify_spring_project.py` → 3 关 × 2 次 `mvn test` 全过
  （starter 2 failures / 2 errors / 1 failure；参考实现均 2/2 绿）。
- 后端：`DockerCommandTest` **21/21**；`mvn -B -pl paideia-sandbox -am ... test` `BUILD SUCCESS`（独占库）。
- 内容导入：`node scripts/import-course.mjs --dir content/courses/spring-boot-project`
  → `created 2 / updated 1`，轨道 `spring-boot-project` 变为 **3 关**。
- 部署：`release.ps1` 189.1 秒（后端 96.9s / 前端 73.5s / 打包 37.1s / 上传 18.9s / 重启 2.0s / 健康检查 25.5s）。

## 实现订正（验证中发现）

- **骨架原本只存在于镜像里**：`/opt/skeleton` 在仓库中没有副本，等于"判分依赖的东西不可复现"。
  已捞回 `scripts/spring-project-skeleton/` 并在 README 写明"改它必须重建镜像并同步 `sandbox.spring-image`"。
- **`TestRestTemplate` 把字符串 URL 当 URI 模板**：`?name=%20Zoe%20` 里的 `%20` 不会被解码，
  测试读到的名字是 `%20zoe%20`。改用 `URI.create(...)` 原样发送（第 2、3 关的测试已改）。
- 第 2 关的 `letters` 一开始写成 8：`"hello zoe"` 是 **9** 个字符（5 字母 + 空格 + 3 字母），
  被验证脚本当场抓出来。

## 未覆盖与已知限制

- **1 核复测没做**：线上是 2 核，15–18 秒；尖刺时 1 核是 27 秒，仍低于 90 秒上限，但没有实测复核。
- **并发未测**：两个用户同时跑 1g/2 核的争用没验证；槽位与配额走的是既有 `/run` 通道。
- 容器**无网络**：不能临时加 starter；第 3 关的校验只能手写，不能用 `spring-boot-starter-validation`（正文已写明）。
- 每次运行全量编译（新容器），改一个字符也要 15 秒左右；常驻容器是后续优化。

## 反向验证

- AC-007 的"starter 必不绿"是**先跑出红、再看失败原因**：三关分别是 2 failures、2 errors、1 failure，
  不是"随便非 0 就算过"——验证脚本还要求输出里有 `Tests run:`（编译必须通过）。
- 第 3 关的 starter 失败是"少 `@ExceptionHandler` → 500"，参考实现补上后 400，线上浏览器与 API 两边一致。
