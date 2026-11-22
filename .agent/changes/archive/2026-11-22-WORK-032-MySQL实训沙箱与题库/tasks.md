---
artifact: tasks
work_id: WORK-032
work: MySQL 实训沙箱与题库
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-015, WORK-020, WORK-024]
created: 2026-10-21
updated: 2026-10-21
approved_by: xiaou61
approved_at: 2026-10-21 18:06:32 +0800
approver_role: CTO
---

# 任务 · MySQL 实训沙箱与题库

**排序原则**：先把**不需要 Docker** 的层钉死（镜像构建、命令构造、结果解析、判题语料），再接题库与界面。因为「题目能不能被可信地判」是本项的核心，而它完全可以在本机验证完。

**全局约束**：不新增表；镜像按 digest；服务端不存答案（隐藏集用哈希）；SQL 不落库（只哈希与长度）；不改 WORK-015 的 Java/Python 路径；每道题的四类元数据由门禁强制。

**关键实测基线**（设计已记录，此处只列任务依赖它的数字）：预初始化启动 4.4–4.7s、常驻 348MB、初始化 17.9s、初始化后 datadir 180MB、不限时笛卡尔积 259s。

## 任务

### TASK-001 | done | 预初始化 MySQL 镜像

- 对应：REQ-001、REQ-002；AC-001
- 依赖：无
- 修改：新增 `backend/paideia-sandbox/images/mysql/Dockerfile`、`build.sh`、镜像说明
- 步骤：
  1. 基于 `mysql:8.0`（**按 digest 钉住**）构建；在构建期跑 `mysqld --initialize-insecure --datadir=/opt/datadir-base`，产物留在镜像里。
  2. 构建期验证 `du -sh /opt/datadir-base` 约 180MB，并把数字写进镜像说明。
  3. 镜像里**不含**任何用户数据；只含系统库。
  4. 记录最终 digest 并写进 `application.yml` 的默认值。
- 验证：服务器上 `docker run --rm … mysqld --version`；启动到可 ping 的耗时 ≤ 6s（实测基线 4.4–4.7s，留余量）。

### TASK-002 | done | SQL 命令构造与逐条加固断言

- 对应：REQ-001、REQ-004；AC-001、AC-004
- 依赖：TASK-001
- 修改：`backend/paideia-sandbox/.../run/DockerCommand.java`、`SandboxProperties.java`、`DockerCommandTest.java`
- 步骤：
  1. `SandboxProperties` 加 `sqlImage` 字段与 `imageFor` 的 `sql` 分支；`languages()` 改为按**镜像可用性**返回（由 runner 探测后注入），不是写死常量。
  2. `DockerCommand` 加 `SQL_SCRIPT` 常量：拷 `/opt/datadir-base` 到 `/tmp/data`、起 mysqld（`--skip-networking`、`--socket`、`--secure-file-priv=/tmp/nofiles`、`--local-infile=0`、`--max-execution-time`）、轮询 ping、喂数据集、执行用户 SQL。
  3. 加固标志：`--network=none`、`--read-only`、`--tmpfs /tmp:rw,size=768m`、`--cap-drop ALL`、非 root、`--memory=512m`、`--cpus=1`、`--pids-limit=128`。
  4. `secure_file_priv` 指向的目录**必须在脚本里先 mkdir 且 chown mysql**（实测指向不存在的路径 mysqld 直接 Aborting）。
  5. 每条加固标志一个单测断言（沿用现有 `contains` 写法）；`secure_file_priv` 指向不存在路径这条要有一条回归测试。
- 验证：`mvn -B -pl paideia-sandbox test`；`DockerCommandTest` 全绿且新增断言逐条可指认。

### TASK-003 | done | 结果编解码（结构化 tables，多结果集）

- 对应：REQ-003、REQ-002a；AC-003、AC-016
- 依赖：TASK-002
- 修改：新增 `backend/paideia-sandbox/.../run/SqlResultCodec.java` 与其单测；`DockerCodeRunner` 接线
- 步骤：
  1. 容器内用 `--batch --raw --column-names` 导结果到 **fd 3**（与程序输出分离，沿用 WORK-023 的做法）。
  2. 解析为 `List<Table>`，`Table = {statementIndex, columns, rows}`；**值一律字符串**。
  3. 多语句（U-07）按分隔标记切出多个结果集并记录 statementIndex；BEGIN/COMMIT 这类不产生结果集的语句不占位；**任一语句出错即停止后续**但保留已产生的结果集；`NULL` 用固定标记而不是空串。
  4. 行数上限与总字节上限：超限时停止读取并置 `overLimit=true`，**不返回半截结果冒充成功**。
  5. `CodeRun.Status` 加 `OVER_LIMIT`（唯一涉及的枚举扩展，不涉及迁移）。
- 验证：单测覆盖单结果集、多结果集、`NULL`、超行数、超字节；`mvn -B -pl paideia-sandbox test`。

### TASK-004 | done | 判题语料与两侧实现

- 对应：REQ-005、REQ-006；AC-005、AC-008、AC-014
- 依赖：无（纯逻辑，可与 TASK-002/003 并行）
- 修改：新增 `tests/sql-judge/corpus.json`、`frontend/packages/ui/src/markdown/renderers/sql-judge.ts` 及其测试、`scripts/sql_judge.py` 及其测试
- 步骤：
  1. 先写语料：每条 = {两侧输入 tables、normalize 口径、期望判定}。至少覆盖：列顺序忽略、行序忽略/严格、`DECIMAL` 等值、`NULL` 与空串区分、日期格式归一、多行重复值、空结果集。
  2. 两侧各自实现 `judgeSql(tables, normalize, expected)`，**不互相 import、不共享代码**。
  3. 两侧各自跑同一份语料，结论必须逐条一致。
  4. 规范序列化格式写死并在两侧各有一条测试（用于隐藏集哈希）：列名按名排序、行按序列化后排序、值按类型规整。
- 验证：`node`/`vitest` 与 `python` 两侧对同一语料结论一致；**故意改坏一侧的一条规则，对照测试必须红**（反向验证）。

### TASK-005 | done | 内容门禁扩展（题目质量断言）

- 对应：REQ-007、REQ-008、REQ-009、REQ-012、REQ-013；AC-005、AC-006、AC-009、AC-010
- 依赖：TASK-004（判定口径）、TASK-001~003（要真跑 reference 才能算哈希）
- 修改：`scripts/verify_course.py`
- 步骤：
  1. 从文件里读 `cases/*.json`（同名课目录），校验五题型的结构、`goal`/`trap` 非空。
  2. 对每道可执行题：在一次性 MySQL 里灌数据集、跑 `reference` → 得期望；跑 `alternatives` → 必须全部相等；跑 `antiPatterns` → 至少一个数据集不等。
  3. 生成规范序列化哈希并**回写** `expectHash`（带 `--write-hashes` 开关，默认只校验）。
  4. 校验每课至少一道非执行题；`trap` 文本能在该课「常见误区」小节里找到。
  5. 校验判题列里没有 `FLOAT`/`DOUBLE`。
- 验证：对现有 3 道先行题跑通；**再造一道故意不合格的题**（反模式能通过 / alternative 会失败），确认门禁报错并指出原因（反向验证，必须记录输出）。

### TASK-006 | done | 能力探测与分档配额

- 对应：REQ-016、REQ-018；AC-001、AC-004、AC-015
- 依赖：TASK-001、TASK-002
- 修改：`SandboxController`、`DockerCodeRunner`、`SandboxProperties`
- 步骤：
  1. `/status` 的 `languages` 按"镜像存在性 + 开关"计算，SQL 镜像缺失时不出现。
  2. `/run` 增加 `dataset` 参数，只接受服务端白名单里的键；未知键 400。
  3. 配额检查在**启动容器之前**（沿用现有顺序，不要挪）；**配额按语言分档**：SQL 20/天、Java/Python 各 50/天（U-09），`code_runs` 已有 `language` 列可直接按语言计数。
  4. 四种失败（起不来 / 超时 / 超限 / 配额）各自状态可区分。
- 验证：`SandboxIntegrationTest` 加 SQL 用例（需服务器有 Docker）；无 Docker 时跳过且不报红。

### TASK-007 | done | 数据集与先行 3 道题

- 对应：REQ-014；AC-005~AC-009
- 依赖：TASK-005
- 修改：`content/courses/mysql/datasets/orders-public.sql`、`orders-hidden-a.sql`；`content/courses/mysql/cases/*.json`
- 步骤：
  1. 数据集：订单四表（客户/订单/明细/商品），公开集小、隐藏集行数与分布不同（让硬编码失败）。
  2. 先出 3 道：01 课一道 `sql-blank`（不执行）、03 课一道 `sql-write`（排序严格）、05 课一道 `sql-fix`（LEFT JOIN 退化）。
  3. 每题都带 `goal` / `trap` / `reference` / `alternatives` / `antiPatterns`，并跑门禁回写哈希。
- 验证：`python scripts/verify_course.py --dir content/courses/mysql` 全绿；`--write-hashes` 前后哈希稳定（幂等）。

### TASK-008 | done | 补满 12 关题目（每关 1 道精做题）

- 对应：REQ-002a、REQ-011、REQ-014；AC-009、AC-016
- 依赖：TASK-007
- 修改：`content/courses/mysql/cases/*.json`、对应课正文的 `code-challenge` 围栏块
- 步骤：
  1. **每关 1 道可执行题，共 12 道**（U-06）。题量不再往上堆，力气花在每题的 alternatives 与 antiPatterns 上。
  2. 09（事务与 ACID）、10（隔离级别与锁）两关**用多语句出实操题**（U-07 已允许），例如"这段事务边界写错了，改对并让回滚生效"。
  3. 每课保持至少一道非执行题（预测/诊断/填空），用于沙箱不可用时降级。
  4. 每题的 `trap` 必须对应本课「常见误区」里已写的一条。
- 验证：门禁全绿；12 关逐关列出「题型 + goal + trap」清单，人工过一遍是否超纲；09/10 两关用 ROLLBACK 后的结果证明事务真的生效（AC-016）。

### TASK-009 | done | 自由练习页

- 对应：REQ-015、REQ-016；AC-011、AC-012、AC-015
- 依赖：TASK-006、TASK-007
- 修改：`frontend/apps/app/src/pages/SqlPracticePage.tsx`、`router.tsx`、导航入口
- 步骤：
  1. **只提供一个示例库**（订单四表，U-08）：不做数据集管理，只做一个选择器。
  2. SQL 编辑区（多语句可写）、运行按钮、多结果集表格（标出第几条语句）、错误展示。
  3. 顶部显示 SQL 剩余次数（20/天，与其它语言分开）。
  4. 沙箱不可用时按钮不渲染 + 说明原因。
  5. **绝不调用进度上报**。
- 验证：e2e 打开页面、跑一条 SQL、看到结果；数据库断言 `learning_progress` 无新增（AC-011）；SQL 配额与 Java/Python 互不影响（AC-015）。

### TASK-010 | done | 关卡内题型渲染与进度

- 对应：REQ-011、REQ-012；AC-012
- 依赖：TASK-009（共用编辑器组件）、TASK-008
- 修改：`frontend/packages/ui/src/markdown/renderers/`（schema + 新题型组件）
- 步骤：
  1. schema 放开 `language: 'sql'`；新增 `sql-write` / `sql-fix` 的解析与判定接线。
  2. 判定结果按现有 `onGraded` 上报挑战分（沿用 `learning_progress`，不改服务端规则）。
  3. 沙箱不可用时降级为只读题面。
- 验证：`learn.spec.ts` 回归通过（2026-10-21 线上 16 passed / 1 skipped）；
  "在关卡里做 SQL 题并计入进度"落在渲染层：`blocks.test.tsx` 的三条用例注入假沙箱，
  分别验通过写 1 分、不一致写 0 分、沙箱不可用不渲染按钮。
  **e2e 级别未覆盖**——关卡正文要真的跑起沙箱才有结果集，线上环境不保证开着。

## 依赖图

```text
TASK-001 镜像
   └─ TASK-002 命令构造 ─┬─ TASK-003 结果编解码 ─┐
                          └─ TASK-006 探测/配额 ─┤
TASK-004 判题语料（独立）──┴─ TASK-005 门禁 ─ TASK-007 先行3题 ─ TASK-008 补满
                                                    └─ TASK-009 练习页 ─ TASK-010 关卡渲染
```

关键路径是 **004 → 005 → 007**：题目可信度的技术前提全在这条线上。

## 停止点

- TASK-005 完成后应停下来评审一次：这时门禁能对题目判"合格/不合格"，需要人确认这套断言**真的能挡住坏题**，再批量出题（TASK-008）。
- TASK-008 完成后需要一次**人工题目评审**：脚本能挡机械错误，挡不住"平庸的题"。
