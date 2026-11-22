---
artifact: test-report
work_id: WORK-054
work: Spring 与 Spring Boot 课程线及手写实战
status: passed
evidence: required
created: 2026-10-29
updated: 2026-10-29
---

# WORK-054 验证报告

## 验证环境

- 本机：Windows 10（win32 10.0.22000），Git Bash（MSYS）
- JDK：本机 `javac 25`；沙箱基线是 **Java 21**，因此所有 Java 校验都用 `javac --release 21` 交叉验证
- 前端：pnpm workspace，`@paideia/knowledge`（Vitest）
- 无数据库参与：本项零迁移、零接口、零后端改动，所有校验都是文件级
- 无 Docker 参与：不新增镜像，实战线走既有 `java` + project 判题

## 验证结果

| 检查项 | 命令 | 退出码 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-001 课程线结构 + 每个 `<!-- run -->` 真执行 | `python scripts/verify_course.py --dir content/courses/spring` | 0 | passed | `testing/logs/ac001-verify-course-spring.txt` |
| AC-002 文件数与 `order` 连续 | `wc -l content/courses/spring/*.md` 与 `grep -n '^order:' content/courses/spring/*.md` | 0 | passed | `testing/logs/ac002-count-order.txt` |
| AC-003 实战线结构校验 | `python scripts/verify_course.py --dir content/courses/spring-handmade` | 0 | passed | `testing/logs/ac003-verify-course-handmade.txt` |
| AC-004 实战五关 starter 不绿 / 答案全绿 | `python scripts/verify_handmade.py` | 0 | passed | `testing/logs/ac004-verify-handmade.txt` |
| AC-005 岗位地图改动不破坏既有断言 | `pnpm --filter @paideia/knowledge test` | 0 | passed | `testing/logs/ac005-knowledge-tests.txt` |
| AC-006 两条线导入 dry-run | `node scripts/import-course.mjs --dry-run --dir content/courses/spring`（与 `spring-handmade`） | 0 | passed | `testing/logs/ac006-import-dryrun.txt` |
| AC-007 运行块不依赖 Spring 依赖 | `rg -n "org\.springframework" content/courses/spring/` | 0 | passed | `testing/logs/ac007-spring-refs.txt` |

具体读数：

- **AC-001**：`通过：21 个课程文件（含代码执行）`——"含代码执行"这几个字是判据，它证明每个 `<!-- run -->` 都被真正编译、运行、逐字比对过，不是 `--no-run`。
- **AC-002**：`文件数：21`，`order` 序列为 `0 1 2 … 20`，无缺号无重复。
- **AC-003**：`通过：6 个课程文件（含代码执行）`（1 总览 + 5 关）。
- **AC-004**：五关逐条 `OK`，汇总 `通过：5 个实战关卡（starter 编译通过但不绿，答案全绿，演示输出一致）`。这条是本次的核心证据：它同时证明了 ① starter 能编译（学习者拿到的是可改的骨架，不是一堆编译错误）② starter 跑不绿（没有被提前做完）③ 正文里公布的答案真能 `ALL PASS`。
- **AC-005**：`12 passed (12) / 93 passed (93)`。改动前有 4 条断言以 `spring` 为"核心"样例，`role-map` 把它移到 `advanced` 后这 4 条按设计变红；已把样例换成 `spring-from-zero`，并新增一条显式断言 `tierOf('backend','spring').tier === 'advanced'`（把本次的定位变化固定下来）。
- **AC-006**：`spring：21 课`（order 0–20）、`spring-handmade：6 课`（order 0–5），两次退出码均为 0，`created 0 / updated 0 / relations 0` 是 dry-run 的预期值（dry-run 不写库）。
- **AC-007**：`org.springframework` / `spring-boot-starter` 只出现在正文、`pom.xml` 片段、quiz 选项与错误原文里；没有一处出现在 `<!-- run -->` 块中。这一点由 AC-001 的 `javac` 执行结构性证明——如果运行块引了 Spring，裸 `javac` 连编译都过不了。

另外单独跑过一次 `javac --release 21` 全覆盖：课程线 20 个 Java 示例（总览无运行块）全部编译通过，0 失败。这一点比 `verify_course.py` 严格——它用的是本机 `javac 25`，会放过 Java 22+ 的语法，而沙箱只有 21。

## 验收结果

| AC | 结论 |
| --- | --- |
| AC-001 | 通过（21 个文件，含代码执行） |
| AC-002 | 通过（21 个文件，order 0–20 连续） |
| AC-003 | 通过（6 个文件） |
| AC-004 | 通过（5 关 starter 不绿 + 答案全绿） |
| AC-005 | 通过（93 条全绿） |
| AC-006 | 通过（21 + 6 条待写入） |
| AC-007 | 通过（运行块零 Spring 依赖） |

REQ 覆盖：REQ-001/002/003/004 → AC-001/002/007；REQ-005/006 → AC-003/004；REQ-007 → AC-005；REQ-008 → 人工核对（`content/reading/spring/00-总览.md` 的"八篇的走法"与 `01`–`08` 八个正文文件一致，`learning-path` 也是 8 站）。

**用户验收未登记。** 本次只做到"测试证据通过"，业务验收需要用户另行确认（`approve --artifact test-report`）。

## 失败与未验证项

**一次复查修正（2026-10-29，用户追问"实战是不是草率"之后）**：初版五关的 `TestMain` 往 stdout 打中文
（`FAIL 构造器注入生效 -> ...`）。容器里没有 `LANG`，`System.out` 的编码不保证是 UTF-8，中文会变成 `?`，
而这五关恰恰靠那行文字告诉学习者差在哪。既有 `java-project` 线的 `expected` **全是 ASCII**、
`TestMain` 也只有注释里出现中文——那是一份现成的约定，初版没有遵守。已把五关的判题输出全部改成
ASCII，并在 `scripts/verify_handmade.py` 里加了守卫（`TestMain` 与 `expected` 含非 ASCII 即失败）。
反向验证：往一关的 `TestMain` 里塞一个中文 → 脚本退出码 1 并点名该文件；去掉 → 退出码 0。

**另补一项证据（同一次复查）**：把 `DockerCommand.projectScriptFor` 里那段脚本**原样**在 Git Bash 里
跑了一遍，输入按 `DockerCodeRunner` 的字节协议拼（文件数 / 路径 / 字节数 / 内容，文件顺序与
`RunBody` 一致：先 `TestMain.java` 再 `Main.java`，末尾追加 `code`）。结果：五关 starter 全部
`exit 1` 且 FAIL 文案完整；五关答案全部 `exit 0` 且 stdout 恰好 `ALL PASS`。也就是说**分帧协议与
入口脚本这两段是真的验过了**，剩下的未知只有容器运行时本身。

三处**仍未验证**，逐条说明：

1. **真实导入未跑**。`import-course.mjs` 需要运行中的后端与管理端凭据，本项只授权到"内容与 dry-run"。导入属部署动作，未授权。
2. **容器运行时未跑**。本机没有 Docker（WORK-032 已记录）。已用真实分帧脚本验过协议与入口，未验的是 `--network=none`、只读根、`/tmp` 64MB、`-Xmx` 256MB、非 root 这一组限制下 `javac` + `java` 是否仍正常。这五关不建连接、不写临时文件、不占大内存，风险低但不为零。**`javac` 在 256MB 下的堆**是唯一值得盯的点。
3. **`/learn` 页面渲染未用浏览器验证**。新增的两条线是 `line_kind: course` 与 `line_kind: reading + line_mode: practice + verify: project`，后者与既有的 `java-project` 完全同构（同一套 `RunBody` + project 模式，`parseCodeChallenge` 已读源码确认支持 `files` 与 20 文件上限），但**没有在真页面上点过一次「运行」**。

另有一条只做了人工核对：`line_sort` 变化的影响（`spring` 阅读线 15 → 28）没有截图证据。

## 剩余风险

- **判题依赖的入口名**：五关都写死 `TestMain` 与 `Main.java`。这与 `java-project` 一致（`DockerCommand.projectScriptFor` 里是常量），但如果将来改入口名，这五关会一起失效，而不会给出明显报错——表现为"运行成功但输出对不上"。
- **答案公开**：每关的参考实现折叠在 `<details>` 里。这是刻意的（学习者会卡住），代价是"看一眼就能抄"。若将来要防作弊，需要改成服务端校验或延迟展开。
- **`--release 21` 只覆盖 Java 语法层面**：如果课程里的 Spring 代码片段有 API 变更（例如 Boot 4 把 `@MockBean` 移除），`verify_course.py` 抓不到——它不编译那些块。这类错误只能靠正文里的版本标注（第 19 课已标注）与人工阅读。
- **`scripts/verify_handmade.py` 是新增守卫**，它自己没有被测试覆盖；它的正确性靠"五关全 OK"与"故意改坏一关会红"两条人工验证过，但没有单元测试。这是已知缺口，成本与收益比不划算（脚本只服务这一条线）。
