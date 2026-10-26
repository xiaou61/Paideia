# 开发节奏：实测数字、陷阱与理由

本文是 `AGENTS.md`「节奏」一节的展开。规则留在那里，这里放**为什么**、**实测多少**、**踩过什么坑**——需要判断时读这一篇。

## 测试节奏

**核心事实：贵的不是用例，是每次运行的固定开销。** 后端集成测试要连远程 MySQL；纯函数测试跑完是 0.1 秒。所以内环要靠"不启容器、不进网络"。

| 环 | 什么时候 | 跑什么 | 目标 |
| --- | --- | --- | --- |
| 内环 | 每改完一处 | `mvn -B -pl <模块> -am test -Dtest=<类>`；前端 `pnpm --filter <包> test` | < 30 秒 |
| 外环 | 一个切片收口（= 提交前） | 非 app 全模块 + 四个守卫类 + 前端 typecheck/test + **改动那一端**的 e2e | < 4 分钟 |
| 全量 | 交接 / 改跨模块契约 / 改 schema 或迁移 / 发版带 `-WithTests` | 后端全量 `-T 1C`（+ 对应端 e2e） | < 5 分钟 |

**没有"发布前"这一档**（2026-10-16 用户裁掉）：本项目上线就是随手跑一次 `release.ps1` 部署，不存在"攒批次、一次性跑全量"的节点，所以不要把任何检查挂在一个虚构的阶段上。另两件事因此都是**按需**而非按阶段：

- `pnpm test:e2e:all`（三端全量）只在**改动跨端**、动了共享包（`@paideia/ui`、`@paideia/auth`、`@paideia/core`）时才跑。
- 桌面产物只在要更新桌面安装包时才打（约 15 分钟：四个应用构建 + 桌面产物 + 全部 e2e）。

## 发版耗时（2026-10-19 实测：183 秒 → 75 秒）

`release.ps1` 原来一次发版 **183 秒**，拆开看没有一处是"服务器慢"，全是本机侧可省的开销：

| 步骤 | 改前 | 改后 | 怎么省的 |
| --- | --- | --- | --- |
| 前端构建 | 78.9 s | **0 s（未改前端时）** | 按 `git diff <上次发版提交>..HEAD -- frontend` 判是否重建；改过才建 |
| 打包 staging 并压缩 | 31.9 s | **0.7 s** | 不再把 79 MB fat jar 塞进去二次压缩（它本身就是 zip，压完只小 9%） |
| 上传 | 19.8 s（77 MB） | **31 s（79 MB）** | jar 原样传，web 单独 tar.gz（4.9 MB）且只在重建时才传 |
| 其余（编译/解包/重启/健康） | ~52 s | ~43 s | 基本无变化 |

**两个关键实测事实：**

1. **fat jar 不要二次压缩**。`tar -czf` 整个 staging（102 MB）要 **32.8 秒**，只把 79 MB 压到 72 MB；
   同样的包用 `gzip -1` 是 2.2 秒 / 77.2 MB。对它做压缩是纯 CPU 空转。
2. **`vite-plugin-pwa` 的 `closeBundle` 单独花 49 秒**（构建日志原话：`callbacks ran for 51.3s, the build took 22.9s`）。
   这是前端构建 79 秒里的主要部分，也是"纯后端发版不该重建前端"的直接理由。

**跳过前端的安全性**：`frontend/` 自上次成功发版以来没有提交、也没有未提交改动才跳；判断不了
（marker 不存在、提交不在历史里）一律当作改过。跳过时还会在远端 `test -f web/index.html`，
缺了就报错退出——不然会安静地发出一份没有前端的版本。标记文件 `target/.last-release-commit`
只在**健康检查通过后**才写，失败的发布不会让下一次误判。

要强制重建：`-WithWeb`。要强制只发后端：`-SkipWeb`。

**"改动那一端"怎么判**：按**改动能到达哪个页面**定，不是按"哪个包被改了"定。实例：WORK-012 动的是共享包 `@paideia/ui`，却只跑了学习者端与管理端、没跑展览页——对，因为 `apps/ui-kit` 对 markdown 的引用为 0 命中，改动的可达面本就不含它。

## 实测基线（2026-10-21）

**用例数**：本轮从 surefire 报告汇总 **667 条**（`paideia-platform` 27、`web` 11、`persistence` 4、
`security` 11、`account` 16、`knowledge` 62、`note` 6、`ai` 242、`sandbox` 6、`mcp` 25、`app` 257）。
历史走过 257 → 543（WORK-020..029）→ 667。此前记的「148 条」是 `paideia-ai` 一个模块的数被
错当成非 app 全模块的总数，实际 `paideia-ai` 自己有 **242 条**。

**耗时**：下表前两行是 **543 条时**的干净实测；用例涨到 667 后**未在干净树上重测**
（本机同时有别的会话在主树里改迁移，全量跑不起来），所以别把 3:30 当成现在必然够。

| 跑法 | 墙钟 | 用例数 | 用途 |
| --- | --- | --- | --- |
| `mvn -B test`（全量，串行模块） | 4:01（543 条时） | 667 | 交接/改契约 |
| `mvn -B -T 1C test`（全量，模块并行） | 3:30（543 条时） | 667 | 同上，**优先用这个** |
| `mvn -B -T 1C -pl '!paideia-app' test` | **1:28（本轮实测）** | 410 | **切片提交第一段** |
| `-pl paideia-app -am -Dtest=ModularityTest,MigrationVersionFormatTest,MigrationChecksumTest,MigrationSeedOverlapTest` | **0:46（本轮实测）** | 9 | **切片提交第二段** |
| `mvn -pl paideia-ai test`（单模块纯单测） | 约 10 秒 | 242 | 改 ai 模块时 |
| 单跑一个集成类 | 15–35 秒 | 1 类 | 定位具体失败 |

**切片提交 = 前两段相加约 2 分 15 秒**，比全量省 1 分以上，且不动 `paideia-app` 那 248 条
要起 Spring 上下文的集成用例。全量里真正能抓到"单模块跑不出来"的，只有四个守卫类
（模块边界/迁移命名/迁移校验和/迁移别污染种子表）——它们在切片提交里单独跑就够了。

### 时间花在哪（2026-10-21 实测）

**`paideia-app` 一个模块吃掉 2 分 15 秒，占全量 55%。** 它是**集成测试模块**：现在 257 条用例
（543 条时是 227 条）要起 **14 次 Spring 上下文**（每次 2–22 秒，累计约 79 秒），外加
`ModularityTest`（ArchUnit 扫全 classpath）单次 **19.5 秒**（本轮单独实测 14.8 秒）。

分层对照：

| 层 | 用例 | 耗时 |
| --- | --- | --- |
| 纯单测（不启 Spring） | 约 317 条 | **约 20 秒** |
| 集成测试（启上下文 + 连 MySQL） | 约 226 条 | **约 220 秒** |

**Flyway 不是瓶颈**：32 个迁移实测 **62 毫秒**。**ArchUnit 是**：单类 19.5 秒。

### 三条结论（别再试一遍）

1. **不要在每轮都跑全量**。切片提交跑「非 app 全模块 + 四个守卫类」（2 分 15 秒），
   全量只留给交接、改跨模块契约、改 schema/迁移、发版带 `-WithTests`。
2. **全量一律加 `-T 1C`**。实测省 31 秒，模块间上下文互相独立，没有共享状态，安全。
3. **不要试图合并那 14 个 Spring 上下文**。31 个 `@SpringBootTest` 类只产生 14 个上下文，说明缓存已在工作；
   剩下的差异是**不同关注点必需的不同装配**（`@MockitoBean` 组合、profile 差异）。硬合并会牺牲隔离性，
   而 `application-test.yml` 已经明令禁止用 `@TestPropertySource` 制造额外上下文——这条纪律执行得很好。
4. **两个会话同时跑后端测试会撞 MySQL 连接上限**（2026-10-21 实测）：本机 `max_connections=151`，
   `Max_used_connections` 已达 152。撞上时 `paideia-app` 的一批集成类**集体报 `Too many connections`**，
   看起来像十几个测试同时坏了，实际一个产品缺陷都没有——`paideia-persistence` 的 4 条和
   `paideia-web` 的 11 条也会一起红。**判断方法**：一看 `grep "Caused by" | head` 里是不是
   `SQLNonTransientConnectionException`，二看 `information_schema.processlist` 里有没有
   `db=paideia_live_*` / 别人的 `paideia_run_*` 在 Sleep。`test-db.sh` 只隔离库名，**不隔离连接数**。

### 已排除的手段

- `mvnd`（Maven Daemon）：未安装，理论上再省 30–50%（跳过每轮 JVM 启动与类加载）。代价是多一个常驻进程，
  且要验证与 `scripts/test-db.sh` 的接线。2026-10-21 用户选择暂不引入。
- 给 `ModularityTest` 加跳过开关：能省 19.5 秒，但要改测试代码，用户选择不为此改代码。

## 旧基线（2026-10-17，已被上面取代，仅留作对照）

**全量一次 ≈ 1.5 分钟。** 2026-10-17 把测试库从公网 `36.212.5.130` 换到**本机 MySQL**，这一项就把后端全量从 **4 分 51 秒压到 1 分 31 秒（省 69%）**。此前那张"10–12 分钟"的表已作废。

| 动作 | 实测 |
| --- | --- |
| 内环：`mvn -pl paideia-ai test`（纯单测） | **8.5 秒** |
| 内环：单跑一个集成类 | **17.6 秒** |
| 后端全量 `mvn -B test`（**257 条**） | **1 分 31 秒墙钟**；用例自身 63.4 秒；0 失败 0 error 4 跳过 |
| 前端单测 `pnpm -r test`（176 条） | **11.5–14 秒** |
| 学习者端 e2e（35 条） | **约 1.6 分** |
| 管理端 e2e（8 条） | **39 秒**（打线上约 15 秒） |
| 桌面打包 `pnpm -w build:desktop` | **59 秒**（Electron 已缓存；首次下载约 20 分钟） |

**分层（本地库）**：纯单测 22 类 131 条 **14.4 秒**；集成 17 类 126 条 **47.0 秒**。集成那 47 秒里 `AssistantConversationIntegrationTest` 占 23.6 秒——它要真跑 10 轮来验限流，属于测试内容本身，不是浪费。

### 时间花在哪（2026-10-17 surefire 实测）

**真凶从来是"每个 SQL 都过公网"**：远端库实测 TCP RTT 中位 45ms、单次查询 36.09ms；本机 **0.27ms**（133×）。换本地后：`MemoryAutoExtractionIntegrationTest` 50.4→**2.2 秒**（23×）、最慢类 54.2→**10.5 秒**。

**前端的是 DOM 模拟器环境**：`environment` 阶段曾占各包 57%–96%。换 happy-dom 后降幅 knowledge 33%、auth 24%、assistant 15%、ui 12%，整套 14.3→11.5 秒。

### 测试库怎么切

`backend/config/remote-db.local.env`（被忽略，不入库）默认指本机 `127.0.0.1:3306` 的 `paideia_test`。需要和线上库对跑时，把该文件里远端那三行注释放开即可。**本机库要自己建**：`CREATE DATABASE paideia_test CHARACTER SET utf8mb4`；迁移与夹具由 Flyway 自动跑。

## 三条既定结论（别再试一遍）

1. **`--no-isolate` 在前端无收益**：ui 包实测 6.97→6.92 秒。瓶颈是 DOM 环境与 RTL 渲染本身，不是文件隔离。**换 happy-dom 才是有效的那一刀。**
2. **集成测试多 schema 并行不做**：收益（47→约 15 秒）低于"每个用例都在自己 schema 里跑"的改造风险，且现有用例大量 `DELETE FROM 表`，串库代价高。
3. **合并 Spring 上下文不做**：本地库下 6 个上下文启动合计约 18 秒，合并收益 10–15 秒；而"多一个配置键就多一个上下文"是这个仓库反复踩过的坑（见 `application-test.yml` 的注释）。

## 接口自查（2026-10-18 起，WORK-017）

一套**手动跑**的全接口自查，不接 CI、不改部署脚本。标准与边界见
[`api-testing-standard.md`](./api-testing-standard.md)，历史记录在 `.agent/testing/runs/`。

```bash
cd backend && source config/remote-db.local.env
mvn -B -pl paideia-app test -Dtest=ApiWalkthroughTest
```

20 秒，自己注册账号、把 69 步（覆盖 62 个端点）走一遍、注销收尾，比对响应结构快照，
并在 `.agent/testing/runs/` 留一份报告。

**新增或修改接口时必须做的两件事**：

1. 在控制器类（或方法）上标 `@ApiAccess(ApiAccess.Access.…)`（`io.github.xiaou61.platform`）。
   不标 → `ApiSurfaceTest` 红；标错 → `ApiContractTest` 红（声明 ADMIN 必须真 403）。
2. 在 `ApiWalkthroughTest` 里加一段真实调用。不加 → 覆盖断言红，点名那条没被打到。
   加完之后第一次跑会自动把新端点的结构写进 `src/test/resources/api-shape.json`（首次或 `-Dapi.shape.update=true` 时），
   review 那份 diff 再提交。

**改了响应字段（改名/增删/改类型）时**：结构快照会红并逐条列出差异。确认是有意变更后，
`mvn -B -pl paideia-app test -Dtest=ApiWalkthroughTest -Dapi.shape.update=true` 更新快照，
review diff 并与前端改动一起提交。

**五个已经踩到的坑**：

- `RequestMappingHandlerMapping` 有**两个** Bean（Actuator 也注册了一个），注入必须带
  `@Qualifier("requestMappingHandlerMapping")`，否则上下文起不来。
- **不要用 `@RequestMapping` 挂业务端点**：不写 `method` 的映射会响应所有动词，
  `getMethodsCondition()` 为空，按方法枚举的检查会静默跳过它。`ApiSurfaceTest` 现在专门拦这个。
- **附件接口按字节嗅探类型**，白名单是 png/jpeg/gif/webp/pdf——传 `text/plain` 或随便一段文本会被拒。
- **注销后 access JWT 仍可用**（`TokenService` 类注释写明的取舍）：它无状态、到期才失效。
  注销真正切断的是 refresh。断言"注销后 access 立刻 401"是错的。
- 本地共用库 `paideia_test` 会与 e2e 的 `db/devdata` 漂移（V953/V954 改过内容），
  表现为 Flyway checksum mismatch。自查请用独立库（`createDatabaseIfNotExist=true` 指向新库名），
  不要为此改共用库。

**没配 `PAIDEIA_TEST_DB_PASSWORD` 时这三套测试全部 Skipped，`BUILD SUCCESS` 照样成立**——
看结果先看 `Skipped` 列。这条坑本项目记述过两次。

## 在线上跑 e2e（2026-10-12 起，改完直接部署再验）

管理端的 e2e **可以不用本地任何东西**：设了 `E2E_BASE_URL` 就跳过 `webServer`，直接打已部署实例。界面类改动走这条最快——不用本地后端、不用本地 dev server、也不用那条 3307 隧道。

```
MSYS_NO_PATHCONV=1 E2E_BASE_URL=http://<服务器>:8081 E2E_ADMIN_PATH=/admin/ \
  E2E_ADMIN_IDENTIFIER=… E2E_ADMIN_PASSWORD=… \
  E2E_LEARNER_IDENTIFIER=… E2E_LEARNER_PASSWORD=… \
  pnpm --filter @paideia/admin test:e2e
```

- 线上实测 **7 passed / 14.7 秒**（本地那条路 23–27 秒，且依赖隧道）。
- `E2E_ADMIN_PATH` 必须给：部署形态是"一个进程同时供 API 与两个前端"，**两个前端各占一个地址**——学习者端在 `/`、管理端在 `/admin/`（`/admin` 与 `/admin/` 都由 view controller 转发到管理端的 index.html）。
- **账号必须给**：种子账号（`seed-admin`/`seed-learner`）不在线上——口令写在公开仓库里，放到公网可达的实例上等于开放管理权限。线上是 `admin` 与 `e2e-learner`（后者是为 e2e 建的普通学习者），口令见被忽略的 `backend/config/application-local.yml`。
- **`MSYS_NO_PATHCONV=1` 不能省**：Git Bash 会把 `/admin/index.html` 这类值改写成 Windows 路径，测试会全红、且报错指向"元素找不到"，极易误判成界面坏了。
- **选择性跑 e2e 的实测成本**（2026-10-21）：全量 61 条 156 秒，但**固定启动约 20 秒、单条边际约 2.4 秒**。所以：
  | 跑法 | 实测 |
  | --- | --- |
  | 全量 61 条 | 2 分 36 秒 |
  | 单文件 2 条（`identity.spec.ts`） | **27 秒** |
  | 单文件 6 条（`notes.spec.ts`） | **54 秒** |
  | `--grep "八股文"` 1 条 | **13 秒** |
  即：**跑一个文件省 65–83%**，只验一条路径省 92%。全量只留给改共享包或发版前。
- 这条路径的两个边界：① 它**真的写线上库**（知识库那条用例自己建、自己删）；② 注册/邮箱验证码那几条**只能在本机跑**——要靠内存信箱读验证码，线上是真实 SMTP。
- **学习者端也能打线上**（2026-10-21 实测订正，旧说法"学习者端仍走本地那条"已过期）。
  命令与上一节相同，只把末尾换成 `pnpm --filter @paideia/app test:e2e`，**五个环境变量都要给**：
  `E2E_BASE_URL` / `E2E_ADMIN_IDENTIFIER` / `E2E_ADMIN_PASSWORD` / `E2E_LEARNER_IDENTIFIER` / `E2E_LEARNER_PASSWORD`。
  实测 **61 条 / 2 分 36 秒**（串行，`workers: 1`，因为后端有状态）。
- **三组凭据缺一不可，漏了会伪装成"产品缺陷"**（2026-10-21 踩到）：`graph.spec.ts` 单独硬编码了
  `seed-admin` 作默认值，而线上没有这个账号 → 4 条用例报「管理员登录失败：夹具没法造」。
  补上 `E2E_ADMIN_*` 后 6 条全过。**全仓只有 `graph.spec.ts` 需要管理员身份**。

## 工作树与磁盘（2026-10-21 实测）

多个会话同时改仓库时各开一个 `git worktree`（规则见 `AGENTS.md`）。**没有任何机制会自动回收它们**，合并后必须手动删，否则会一直躺在 `.worktrees/` 里。

实测占用（`du -sh`）：

| 树的状态 | 大小 | 说明 |
| --- | --- | --- |
| 只 `worktree add`，不构建 | **约 5MB** | `.git` 目录是共享的，不复制历史 |
| + 跑过一次 `mvn test` | **+100MB** | 各模块 `target/` |
| + 跑过 `pnpm install` | **+814MB** | `node_modules` |
| **真正用起来的一棵树** | **接近 1GB** | |

所以这不是"占内存"，是占磁盘。清理两步：

```bash
git worktree remove .worktrees/<名字>     # 有未提交改动时会拒删，加 --force 才强删
git branch -d <分支>                       # 合并后 -d 安全；未合并会拒删（这是保护）
```

2026-10-20 那次合并完只删了工作树、忘了删调试用的 `probe-base`，第二天才发现。写这条规矩就是为了不再靠人记。

## 迁移版本号：为什么改成时间戳（2026-10-21 实测）

多个会话并行做任务时，**递增版本号是被争抢的共享命名空间**。用项目自己的 Flyway 13.10 在独占探针库上实测四个场景：

| 场景 | 实测结果 |
| --- | --- |
| 两个分支都写 `V25` | `Found more than one migration with version 25` → **应用整体起不来**，不是某个迁移失败 |
| 时间戳 `V20261021103045` 接在 `V24` 之后 | 正常应用；重复跑幂等；更晚的时间戳继续追加 |
| 已用时间戳后，有人写了 `V25`（生产配置不放乱序） | `Validate failed: Detected resolved migration not applied: 25` |
| 两个文件同一秒（`V20261021120000`×2） | 和第一条一样的 `Found more than one migration` |

**结论**：时间戳治的是必然撞，不是永远不撞。所以：

- 新迁移用 `V<14位时间戳>__描述.sql`（`date +%Y%m%d%H%M%S`）。
- 守卫 `MigrationVersionFormatTest`：新文件是纯数字号 → 红；两个时间戳相同 → 红；历史 `V1..V24` 在白名单里冻结。
- **动 schema 前先看有没有人在改同一张表**：`git log --all --oneline -- backend/paideia-app/src/main/resources/db/migration`。
  两个会话改同一张表的不同列、版本号不冲突这种语义冲突，**换任何迁移工具都查不出来**。

## 迁移与种子的分工（2026-10-21 起的硬规矩）

**生产迁移只建结构，测试/开发数据一律放 `db/devdata`。**

被这条规矩挡住的那次真实事故：`V24`（WORK-025）用 `INSERT ... SELECT` 给「八股文」建分类时不指定 `id`。在生产库上表里已有 16 个分类，自增给了 20，一切正常；**在全新库上整张表是空的，它拿到 `id = 1`**，紧接着 `devdata/V951` 的种子要插硬编码的 `id = 1`（「后端基础」），主键冲突。

表现极具欺骗性：`mvn test` **201 个错误**，全部发生在 **Spring 上下文启动阶段**——不是某个用例失败，看起来像"整个项目坏了"。

为什么生产库和开发库都发现不了：**它们都有历史数据，自增从 16 以上开始**。只有零数据的新库才会撞。

- **不要指望换迁移工具解决它**。换 Flyway / Liquibase / 别的都挡不住：这是"生产迁移写业务数据 + 种子用硬编码 id"的组合造成的，与版本号模型无关。
- 守卫是 `MigrationSeedOverlapTest`：生产迁移向种子表插行却不指定 `id` 就构建失败。已反向验证过（造一个违规文件，测试精确点名它）。
- 若将来确实需要生产迁移插业务数据：显式写 `id` 且 ≥ 900（种子占低位、夹具占 900+）。

## 踩过的坑

- **不要用 `pnpm -r build`**：`apps/desktop` 的 `build` 会跑 `electron-builder`（下载 Electron 再打包），一轮接近 20 分钟。构建用 `pnpm --filter @paideia/<app> build` 或 `pnpm -r --filter '!@paideia/desktop' build`。
- **不要用 `@SpringBootTest(properties = …)` 覆盖测试配置**：配置键不同 = 又一个 Spring 上下文。需要的数据源统一列在 `paideia-app/src/test/resources/application-test.yml`；测试装配统放在 `TestProfileConfiguration`（`@Profile("test")`，会被组件扫描）。
- **删掉一个资源文件（如迁移 SQL）后必须 `mvn clean`**：Maven 不会从 `target/classes` 删掉已移除的资源，它会继续被打进 jar。2026-10-10 把两个临时探针迁移带上了部署。
- **`pnpm -r test:e2e` 会把桌面端也跑起来**，而它的 e2e 需要已打包的 Electron 产物、本机跑不起来，整条命令会失败。用 `pnpm test:e2e:all`（已排除桌面端）。
- **管道会吃掉退出码**：`cmd | head` 的退出码是 `head` 的；判断成败不要走管道，或显式取 `${PIPESTATUS[0]}`。
- **写断言前先读数据**：写 e2e 断言时凭印象假设种子内容，连错两次（条目只有一个标题所以没有目录、种子里的已发布条目各占一个分类所以没有上下篇），每次都要完整跑一轮 e2e 才发现。读一次文件比跑两轮便宜得多。

## 提交节奏的理由

本仓库可能同时有多个会话在同一工作区改动，攒着不提交会让"哪些改动属于谁"变得不可判定，也让误操作的回退范围变大。分笔提交把回退粒度压到一轮。

## 附件存储：对象存储（WORK-007，2026-10-15 起）

**形态**：附件走自建 S3 兼容服务 **RustFS 1.0.1**，与后端**同机**（服务器没有 Docker）。

| 项 | 位置/取值 |
| --- | --- |
| systemd 单元 | `rustfs`（enabled + active，`Restart=always`） |
| 二进制 | `/usr/local/bin/rustfs`（来自官方 `.deb`，**解包而不是 `dpkg -i`**） |
| 数据 | `/var/lib/rustfs/data`（桶 `paideia-attachments`） |
| 配置 | `/etc/rustfs/rustfs.env`（**600，仅 root**，凭据随机生成；`*.bak-*` 是改动前的备份） |
| 监听 | **`0.0.0.0:9000`（S3 API，刻意对公网开放）**；控制台**已关闭**（`RUSTFS_CONSOLE_ENABLE=false`） |
| 应用侧 | `/opt/paideia-app/application.yml` 的 `paideia.knowledge.storage: s3` + `s3.*`（endpoint 用**公网地址**，因为浏览器要直连它） |
| 应用日志 | 启动时打一行 `附件存储：对象存储 <endpoint> 桶 <bucket>`（**不含凭据**） |

**为什么对象存储要对公网开放**：读路径是"后端 302 → 预签名 URL → 浏览器直连对象存储"，所以浏览器必须够得着它。加固是配套的：控制台关闭、桶私有（无匿名策略，任何读取都要有效签名）、凭据随机且只在 600 文件里、签名有效期 7 天。

**为什么没开 9000 以外的口**：这台机器上非 80/443/8080 的端口本来就公网可达（云安全组只管那三个），所以"开放"和"只开这一个口"是同一件事。

### 切换与回滚

```yaml
# /opt/paideia-app/application.yml
paideia:
  knowledge:
    storage: s3        # 改回 local 即回退
```

改完 `systemctl restart paideia` 生效（**不做运行时不重启热切**：存储位置会变，热切需要额外的一致性设计）。

**⚠️ 回滚是有损的**：切回 `local` 之后，期间上传到对象存储的附件**会 404**（字节在对象存储里，本地目录没有）。切换前先确认这个损失面可接受——2026-10-15 首次切换时线上附件为 **0 条**，所以那次损失面为零。

### 新环境要手动建桶（应用**不自动建桶**）

故意不自动建：配错桶名时自动建会让"附件看起来全都消失了"（其实进了另一个桶），极难排查。新环境先建一次：

```bash
python3 -c "import boto3; boto3.client('s3', endpoint_url='http://<host>:9000', aws_access_key_id='<AK>', aws_secret_access_key='<SK>', region_name='us-east-1').create_bucket(Bucket='paideia-attachments')"
```

（或者用 `mc mb`；注意 `dl.min.io` 已 410，mc 要从 GitHub 下。）

### 两个参数与它们的理由

- `presign-ttl: 7d`——预签名 URL 的有效期。
- `redirect-cache: 1h`——**302 跳转本身**允许被浏览器缓存多久，必须**小于** presign-ttl（装配期会拦住反过来的配置）。
  这条参数是为了补一个副作用：签名 URL 每次都不同，而浏览器缓存按完整 URL 索引，所以"每次都换签名"会把本项之前靠"id 不变"换来的一年浏览器缓存打到 0。让 302 自己缓存一小时，同一小时内的重复加载直接用缓存里的跳转，既不回后端也不回对象存储。

### 排障

```bash
systemctl status rustfs; journalctl -u rustfs -n 50          # 对象存储侧
journalctl -u paideia | grep 附件存储                          # 应用选了哪个后端
curl -i http://<host>:8081/api/v1/knowledge/files/<uuid>      # 期望 302 + Location 指向 :9000
curl -sL -o /tmp/a.png http://<host>:8081/api/v1/knowledge/files/<uuid>   # 跟随后应拿到字节
```

**跑集成/契约测试需要两条隧道**：3307（MySQL）与 9000（对象存储）。契约测试的 S3 那条以环境变量为开关（`PAIDEIA_KNOWLEDGE_S3_ENDPOINT`），没有隧道时**跳过而不是失败**。

## AI 计费与平台凭据（WORK-009，2026-10-16 起）

**凭据来源变了**：AI 的上游 Key 由**平台**持有、放**部署配置**（`paideia.ai.billing.platform-credential.{base-url,model,api-key}`，环境变量占位），**不入库**。用户不再配置 Key，`/api/v1/ai/credentials` 只剩 GET（返回"配没配"，**连末四位也不给**）。随之删掉的是用户加密存 Key 的那张表 `ai_model_credentials` 与 `CredentialCipher`——**没有秘密要落库了**，`paideia.ai.master-key` 也不用再配（老配置里还留着它无害，只是没人读）。

**积分**：每用户一个余额（`ai_credit_accounts`）+ 一条**只增不改**的流水（`ai_credit_ledger`，自带 model/input/output，**不靠 join** 别的表）。流程是**先预留、后按实际用量结算**：

- 预留额 = `max-credits-per-call × reserve-factor`（默认 5000 × 0.2 = **1000**）。余额不够时**一个上游请求都不发**，返回 HTTP **402**（业务码 40200，界面据此提示"积分不足，当前没有自动充值，请联系管理员"）。
- 结算按价目表折算并**截到单次上限**；实际 < 预留就退还差额。**拿不到用量就整笔退**——没有依据就不扣。
- **本期没有充值**：积分唯一入口是管理端 `POST /api/v1/ai/admin/credits/{userId}/adjust`（带 delta 与 note，操作人与原因进流水）。

**价目表改法**（改配置即生效，不用重新构建）：

```yaml
paideia:
  ai:
    billing:
      pricing:
        deepseek-flash:
          input: 100     # 每千 input token 的积分
          output: 200    # 每千 output token 的积分
```

**没配到价目就按 0 扣**（宁可漏扣、不误扣）：审计页上"有用量但 0 扣减"一眼看得出来。

**回滚档位一**：`paideia.ai.billing.enabled=false` → 回到"平台凭据 + 不计量"，用户无感，已生成的流水不受影响。**不写**整表删除的回滚脚本（流水是审计证据）。

**两条顺序敏感的约束**（写反了**不报错**，静默失效）：

1. `/api/v1/ai/admin/**` 的 `hasRole("ADMIN")` **必须排在 `/api/v1/ai/**` 之前**——顺序写反只会在某天表现为"任何已登录的学习者都能看别人的用量、给自己发积分"。
2. 新迁移接着仓库当前最大号（本项是 **V11**）。

**已知缺口**（登记，不在本项解决）：

1. **进程在"预留"之后、"结算"之前被杀** → 留下一笔未结算的预留，余额被多占。本项只保证**不超扣**；多占要人工按流水核对（缺 `REFUND` 即可定位）。不引入定时任务。
2. **缓存折扣拿不到**：`TokenUsage` 只有 input/output/total，上游把缓存命中单独计价时我们按全价扣，计费偏高。
3. 回收不足时（预留估少了）那笔差额**不补扣**，只写进流水备注留痕。

**跑集成测试**：需要 DB 隧道 + `PAIDEIA_TEST_DB_PASSWORD`。**没设口令时 `paideia-app` 整模块跳过（118 条全 Skipped）而 BUILD 仍 SUCCESS**——看结果只看 `Skipped` 那一列。隧道本身要服务器的 SSH 口令，`quick-server` 的包装器只支持远程执行、**不支持本地端口转发**，所以非交互环境下拿不到这条隧道。


## AI 助手面板与会话历史（WORK-011，2026-10-16 起）

**面板形态**：**可随处拖动的浮动对话框**（2026-10-16 用户看过"右侧停靠"版后改向——他要"随心所欲拖动、任意区域、小小的对话框、不要固定到某个界面"，并反馈过入口"不太明显"）。做法：`fixed` + 位置/尺寸都存本地（键 `paideia:assistant-panel`，值 `{open,x,y,w,h}`）；**拖标题栏移动**、**拖右下角握把缩放**（握把键盘左右箭头也能改宽）；默认右下角、384×560，范围宽 320–720、高 360–900；**始终夹回可视区**（至少留 16px 边缘可见，窗口缩小也会夹回来）；收起后是一个**显眼的药丸按钮**（图标 + 「AI 助手」），不是小圆点。

**没有"内容让位"这回事**：浮动即覆盖，所以不设任何根 CSS 变量，`AppShell` 也没有为它留 padding（停靠版的 `--assistant-panel-w` 那一处已撤回）。

**当前会话存本地**（键 `paideia:assistant-conversation`）。**三种状态必须分清**，它们都表现为"没有当前会话"：

| 值 | 含义 | 打开面板时 |
| --- | --- | --- |
| 键不存在 | 从没选过 | 恢复**最近一条**（有当前条目时优先绑在该条目上的那条） |
| 空串 `''` | 刚点了「新开对话」 | 保持空白，**不要再**去恢复旧的 |
| `'<id>'` | 选过某条 | 恢复这一条 |

少了这个区分，"点了新开 → 收面板 → 再打开"就会把刚丢开的那条又拉回来。

**换条目不再清空会话**：该条目有绑定的会话就切过去，没有就**保持当前这一段**；只有点「新开对话」才清。（旧实现一换条目就丢，那是用户最直接的不满。）

**读历史**：`GET /api/v1/ai/conversations/{id}/messages`（WORK-011 新增）。归属校验与其它会话端点**同一条 404**；条数上限取库里保留数（200）；返回旧 → 新，含 `role`/`content`/`usage`/`createdAt`。思考过程不落库，所以历史里没有它。

**两个坑，都踩过**：

1. **`ai_messages.token_usage`（列）与 `tokenUsageJson`（字段）自动映射对不上** —— 下划线转驼峰得到 `tokenUsage`，而字段名多了 `Json`，MyBatis **静默给 null**。这一列在此之前**只被写过、从没被读过**（`history()` 只取 role/content），所以潜伏到 2026-10-16 才被 WORK-011 的读历史用例抓出来。**读它的 SELECT 必须显式 `token_usage AS tokenUsageJson`。**
2. **面板挂载时的"恢复上次会话"是异步的**（先列会话、再拉消息），用户可能在这几十毫秒里已经点了发送；恢复完成时若照旧写入，会把在飞的那一轮 **abort 掉**（实测报 `signal is aborted without reason`）。修法：**一旦用户动过（发送 / 新开 / 切换）就让恢复作废**。

**收起 = 卸载**：外壳收起时走的是另一条 `return` 分支，所以"卸载即 abort 上游"这条**仍然覆盖收起**。将来若改成"收起但保持挂载"，必须显式在收起时 abort，否则一次调用会继续计费到用户看不见的地方。

## 流式 markdown 渲染（WORK-012，2026-10-16 起）

**选型**：`streamdown@2.7.0`（内含 `remend`），**钉死版本**。它把 markdown 渲染拆成"切块 → 补全未闭合 → 逐块记忆化 → 只动新增字符"四步；我们只接它的能力，不重写管线。"自研四层状态机"那版方案在看过调研与体积实测后作废。

**体积真相是"总量不变、首屏更小"，不是"变大了"**（学习者端实测）：

| 形态 | 主 chunk | 按需 chunk | 合计 |
| --- | --- | --- | --- |
| WORK-012 之前（`react-markdown` 在知识库包里，随知识库路由走） | 836 kB | — | — |
| 接了 streamdown 但**没做懒加载** | 1203 kB | — | 1203 kB |
| 现在（懒加载 + 去掉 `react-markdown`/`remark-gfm`） | **697 kB**（gzip 219 kB） | **505 kB**（gzip 154 kB） | 1202 kB |

所以：**首屏比接线前还少 139 kB**，剩下 505 kB 在**面板打开时**才下载（`preloadMarkdown()` 在打开面板那一刻发起，与用户打字并行）。第一次回答若chunk还没到，渲染器的 Suspense 兜底是**上一版的真实行为**——纯文本，一字不少，只是晚一步上排版。

**两种模式是同一条管线，不是两条路**（上游那句 `mode === "streaming" && parseIncompleteMarkdown ? remend(text) : text` 是关键）：**只有流式模式做未闭合补全**，静态模式原样用文本。所以 `streaming` 这个 prop 的准确含义是"**这段文字可能还没长完**"——流式回答是，边打字边预览的笔记也是，已定稿的知识库条目不是。历史消息一律静态（否则每帧白跑一遍补全）。

**`@source` 那一行必须有，而且必须指向真实存在的目录** —— 这是本项最贵的坑：

- Tailwind v4 **只为已定义的主题值生成工具类**；扫描不到 `streamdown` 的 `dist` 时，它的类名**一条都不生成、也不报错**。实测：`--sdm-c`（代码配色变量）、`counter(line)`（行号）、`contain-intrinsic-size` 全部缺失，症状是"代码块没行号、间距全乱"，而控制台干净。
- 这一行最初写成了仓库根的 `node_modules/streamdown/dist/*.js` —— **那儿什么都没有**（它是 `@paideia/ui` 的依赖，落在 `packages/ui/node_modules` 下）。正确写法是按文件位置算：`@source '../../node_modules/streamdown/dist/*.js'`。
- **它当时看起来"生效了"**：`.bg-sidebar` 这条规则确实出现了，因为我自己在注释与测试里写过 `bg-sidebar` 这几个字，被扫到了。**验证扫描是否真的工作，必须用只有它 dist 里才有的类**（`--sdm-c`、`counter(line)`）；用"两边都可能出现的类"去验会得到假绿。修好后 CSS 从 78.79 kB 涨到 **88.49 kB**。
- 已固化成检查：`source-coverage.test.ts` 现在会断言**每个 `@source` 指向的目录都真实存在**。

**`--color-sidebar` 是我们必须补的令牌**（同类静默）：它的代码块容器与悬浮操作条用 `bg-sidebar` / `border-sidebar`，而 shadcn 的 sidebar 语义我们不用。缺令牌时那两个类**不生成**（反向验证：去掉令牌，构建产物里 `bg-sidebar`/`border-sidebar` 规则数都是 **0**；补上后是 `.bg-sidebar{background-color:var(--muted)}`）。已固化成 `streamdown-tokens.test.ts`：扫描它 dist 里出现过的每个颜色工具类，逐个断言令牌能解析到（Tailwind 自带调色板从 `tailwindcss/theme.css` 里取，不算缺失）。

**三条验收口径与它们各自的位置**（全在 `packages/ui/src/markdown/markdown.test.tsx`，`pnpm --filter @paideia/ui test`）：

1. **逐前缀等价**（AC-003）：语料是**真实上游的增量序列**（`scripts/collect-md-corpus.mjs` 离线采、固化成 `corpus.json`，运行时不依赖上游），每条按抽样前缀回放，只比较"两个前缀里都不是最后一块"的位置。11 条全部 0 例不一致。**单块语料要分开处理**——一段话/一个表格/一个列表天生只有一个块，没有"已完成块"可比，它该验的是"块数不得凭空分裂"（第一次写这条测试就栽在这里，4 条语料报了"没有可比较的块"）。
2. **增量真的省**（AC-004）：不看墙钟时间，看"**块内元素被渲染的次数**"——记忆化生效时根本走不到那些组件。合成 20 段：逐段追加每次最多重渲染 2 次（刚完成的那段 + 正在长的那段），而"每次都从零挂载"的对照组是它的数倍。真实语料用同比口径：增量式必须**不贵于**重装甲，且多块语料必须**严格更便宜**（单块语料两种算法本来就等价，不能拿它冒充"省了"）。
3. **未闭合不破形**（AC-002）+ **安全**（AC-007）：`<script>`、`javascript:` 链接与图片、代码块里的 HTML，**两种模式都查**。补全占位符 `streamdown:incomplete-link` / `streamdown:incomplete-image` 是真名（来自 remend）：前者进不了 `href`、点它也不打开任何东西（上游在占位符上直接 `return`），后者渲染成占位块而不是真 `<img>`。

**降级（healing）不写成分支，改成搜索**（AC-005）：上游的补全是概率性的，**没有哪个分支能预先列出"补不出来的输入"**，所以做的是拿 **30 条恶劣输入逐字符**回放（未闭合围栏/强调/行内码/链接/图片、奇数 `$$`、未闭合 HTML 与嵌套、半张表、缩进代码块、CRLF、CJK 边界、注入串……），断言任一前缀的已完成块都等于最终渲染。**实测 0 例不一致**。（工件里写好了退路：真撞出一条改形的输入，那一类必须明确降级为纯文本并登记，**不放宽断言**。）

**两种模式有意不同的三处**（都是"按场景给的策略"，不是"两套渲染"；已各自断言钉住）：

| 场景 | 流式（AI 回答） | 长文（知识库条目） |
| --- | --- | --- |
| 外链 | `<button>` + 确认后 `window.open`（**模型生成的链接先问一次**） | 真 `<a target=_blank rel=noreferrer>`（管理员写的） |
| 表格 | 带复制/下载下拉（"把表拿走"） | 不带 |
| 代码块 | 围栏未闭合时复制/下载 `disabled` | 可用 |

**加插件（公式 / mermaid / 代码高亮）的配方**：这三样在上游是**独立包**（`@streamdown/math` / `@streamdown/mermaid` / `@streamdown/code`），现在一个都没装（`ui` 的依赖里只有 `streamdown`），构建产物里也就没有它们的代码（mermaid 单独一个库就 >2MB，而按需 chunk 总共 505 kB）。要用时必须**连懒加载一起做**：装包 → 在 `Markdown` 里按需 `import()` → 重新实测体积。**平台自己的围栏语言是零新依赖的**：`renderers` 已经是公开扩展点（` ```某语言 ` → 我们自己的组件），有测试证明"注册了就由我们的组件渲染、没注册就是普通代码块"。

**升级版本的前置动作**：先跑语料与那 30 条恶劣输入。上游 696 commits、发版很密，语料回归是唯一的缓冲。

**测试环境补丁**：DOM 模拟器不实现 `Element.prototype.scrollTo`（它是布局能力），而上游在动效期间会调它做"只在用户本来就在底部时才跟着滚"。缺它每帧抛一个未捕获异常，把断言报告淹掉——`packages/ui/src/test-setup.ts` 里补成空实现（它滚的是自己那个容器，不是我们的消息列表，所以对滚动位置无影响）。**2026-10-17 起 DOM 环境是 happy-dom（不再用 jsdom），这个守卫仍然保留。**

**验收这三条口径之外，还要人工看一眼**：深浅两套主题下代码块/表格/行号的观感（令牌齐了不等于好看），以及知识库条目页的目录锚点（标题 id 由 `MarkdownBody` 在 effect 里回填，换渲染器没有改动这条约定，但值得实地确认）。

**一处上游的小瑕疵（已知、不修）**：补全出未完成的图片（`![](streamdown:incomplete-image`）时，上游把占位块渲染成 `<div>`，而它所在的位置在 `<p>` 里 —— React 会报警 `In HTML, <div> cannot be a descendant of <p>`（jsdom 测试里也能看到）。触发面是"模型正好吐到 `![](` 的那一帧"，用户看不到坏掉的东西，只是开发控制台多一条告警。记在这里，免得下次看到以为是自己写错了。

## 数据库：从 SSH 隧道改为公网直连（2026-10-16）

**为什么改**：隧道会断（这一轮就断了两次），每次排查测试失败都要先怀疑它。用户原话：「直接把服务器上的弄成本机可访问的，也就是公网可访问的就可以了」。规则里本来就写了这条路径（"若确需直接连公网，再改 `bind-address` 并同步 MySQL 账号的 host 限制"），所以是**照规则执行**，不是绕规则。

**做了什么（三步，缺一不可）**：

| 步 | 动作 | 为什么不能省 |
| --- | --- | --- |
| 1 | 新建 `paideia_test@'%'`，**只 `GRANT ALL ON paideia_test.*`** | 只改 bind-address 是没用的：MySQL 的 host 限制会拒掉远程登录。而**正因为原来没有 `@'%'` 账号**（只有 `paideia@127.0.0.1` / `@localhost`），开端口才**不会**连带暴露生产库——这条是"能不能安全地开"的关键事实，先查了才动手 |
| 2 | `/etc/mysql/mysql.conf.d/mysqld.cnf`：`bind-address = 0.0.0.0`，备份 `.bak-2026-10-16`，重启 mysql | 监听从 `127.0.0.1:3306` 变成 `0.0.0.0:3306`；`mysqlx-bind-address`(33060) **刻意没改**，少一个面 |
| 3 | 口令 32 位随机、写入被忽略的 `backend/config/remote-db.local.env`（匹配 `*.local.*`）；**建账号的 SQL 走 stdin**，不进命令行 | 项目纪律：凭据只进被忽略的文件，且不写进命令行/历史 |

**用的时候**：

```bash
source backend/config/remote-db.local.env     # 之后再 mvn -B test 或跑 e2e
```

两个 playwright 配置的默认 JDBC 已改 `36.212.5.130:3306`，并把 `SPRING_DATASOURCE_USERNAME/PASSWORD` 指到 `paideia_test` + 环境变量——**口令不写进被跟踪的文件**，所以 e2e 也必须先 source 那个文件。

**验证（不是"看起来通了"）**：本机 TCP 连上 3306 拿到 MySQL 8.0.46 握手；`MemoryAutoExtractionIntegrationTest` 在**停掉隧道之后** 10/10 通过；线上服务健康检查仍是 UP。

**风险与回滚**：3306 现在公网可达，扫描器会一直戳。能连上的只有那个**只碰得到 `paideia_test`** 的账号 ⇒ 最坏情况是测试库被写坏（它本来就是被写坏的用途），**碰不到生产库**。回滚＝`bind-address` 改回 `127.0.0.1` + 重启；收窄＝把 `@'%'` 换成固定出口 IP。**如果哪天要让"本地跑应用连开发库 `paideia`"也免隧道，那是另一件事**：那等于给生产库开一个公网可登录的账号，必须先单独确认。

## AI 记忆的自动提取与控制面（WORK-013，2026-10-16 起）

**用户裁决**：①写 = 每 N 轮（默认 6）批量提取一次 ②写入时去重合并 ③控制面照 ChatGPT 三层 ④不引外部记忆服务、不做向量检索（原话"我们记忆目前不需要那么标准"）。

**两步分开，是为了让用户永远不等它**：`scheduleIfDue()` 同步、极轻（判断该不该整理 + 抢游标），`run()` 异步、重（发调用、过账、写记忆）。触发点刻意放在 `emitter.complete()` **之后**——回答已经完整发给用户了。

**四件"看着像实现细节、其实是命门"的东西**：

1. **单飞靠一条条件 UPDATE，不是锁**：`UPDATE ai_conversations SET last_memory_extract_message_id = ? WHERE ... AND last_memory_extract_message_id <=> ?`。用 `<=>` 而不是 `=` 是因为**游标第一次为 NULL，而 `= NULL` 永远不成立**——那会让第一次整理永远抢不到。抢到即推进游标，所以**失败不重跑**（设计里登记的取舍：重跑要引入重试状态机与"失败要不要计费"一串问题）。
2. **判重不检索、直接把已有记忆全给模型看**（上限 40 条）：我们不做向量，而 SQL 关键词检索拿一段对话当关键词效果很差；一个用户的记忆只有几十条，全喂更准也更简单。`kind` 只认白名单三个值（`weak_topic`/`goal`/`preference`），**越界不是错误**——那条候选落到全文那路（"他说过的话"本来不需要分类）。
3. **模型的 id 是不可信输入**：`UPDATE`/`DELETE` 的 targetId 只接受正整数，且改/删的 WHERE 里带 `user_id`（幻觉出的 id 改不到别人的行）；改不到只记一条日志、**不报错**——报错等于告诉它"你猜的这个 id 存在"。
4. **"空内容"是故障，不是"没什么可记的"**：DeepSeek 把 `max_tokens` 吃在思考上时会静默返回空。这一条与 WORK-005 同一个坑，所以解析器把空/非法 JSON 一律判失败、**整次放弃**（一个事务里全有或全无）。

**花的是用户的积分，所以必须能解释**：提取走同一套预扣/结算，流水种类单独记 `MEMORY_EXTRACT`——否则用户对着账目"没聊几次怎么花了这么多"算不明白。余额不足**静默跳过**（回答早就发出去了，不该因为整理报错）。默认 N=6 时摊到每轮约 +13%；每轮自动是 +80%，所以没选它。

**三个开关是"总闸 + 两条轴"**：`memory_enabled`（关掉＝不召回、不整理、手动写入也被拒 40910）、`reference_saved_memories`（只管读）、`reference_chat_history`（关掉＝只回答本轮这一句）。**关掉不删已有记忆**（照 OpenAI 语义），清空是另一个动作；而"清空并关闭"是**一个端点**——分成两次调用会出现"清空了但没关上"的半状态。

**"记住这条"只写了全文那一路**（WORK-006 的老状况），本项把结构化那路的**自动**入口补上了；"自评 → 结构化记忆"那座桥仍属 WORK-010。

**顺带**：`AiConfiguration` 里注册了 `MemoryProperties`（`@EnableConfigurationProperties` 是显式的，不注册就不绑定）；注销级联清单加了 `ai_user_settings`（不加，那条按表断言的用例就会红）。

## 代码执行沙箱（WORK-015，2026-10-18）

**默认关闭**。打开要三件事同时成立：`paideia.sandbox.enabled=true`、宿主机有 Docker、两个镜像已拉取。启动时 `SandboxCapability` 探一次，三者缺一就置 `ready=false`，接口返回 503、前端不显示「运行」。**探测失败不会让应用起不来**——只想读文章部署不该被这个能力绑架。

```bash
# 服务器上准备镜像（2026-10-18 实测 Docker Hub 偶尔 EOF，重试即可）
docker pull eclipse-temurin:21-jdk-alpine
docker pull python:3.12-alpine
# 想把镜像钉死到 digest：
docker image inspect eclipse-temurin:21-jdk-alpine --format '{{index .RepoDigests 0}}'
```

**配置项**（`paideia.sandbox.*`，服务器上写在 `/opt/paideia-app/application.yml` 的 `paideia:` 段下）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关；关掉后前端隐藏运行按钮、接口 503 |
| `runtime` | `""` | 留空 = Docker 默认（runc）；设 `runsc` 即切 gVisor（需先装 runsc 并重启 dockerd） |
| `java-image` / `python-image` | temurin 21 / python 3.12 | 按 digest 钉更稳 |
| `timeout-ms` | `10000` | 墙钟上限，超时即 `destroyForcibly` |
| `max-concurrency` | `2` | 全局并发；超出直接 429，不排队 |
| `daily-limit` | `50` | 每人每天次数，**被拒绝的不计入** |
| `memory` / `cpus` / `pids-limit` | `256m` / `0.5` / `64` | 容器资源硬上限 |

**容器加固参数**（`DockerCommand`，逐条有单测）：`--network=none --read-only --tmpfs /tmp:rw,size=64m,exec --user 65534:65534 --cap-drop=ALL --security-opt=no-new-privileges --memory-swap --pids-limit --ulimit nofile/nproc --stop-timeout=0`，**没有任何 `-v` 挂载**，代码只走 stdin。

**2026-10-18 真机实测**（codenest-online，4C/16G）：Python `print(sum(range(5)))` → `10`，992ms；Java 打印 42 → 4139ms；死循环 → `timedOut` 且正好 10005ms；`urllib` 访问外网 → `BLOCKED URLError`；编译错误 → exit 1 + javac stderr。审计表只存 `code_hash` 与长度。

**已知天花板**：容器共享宿主内核，内核漏洞可导致逃逸。要更强隔离就切 gVisor（`runtime: runsc`），或另起独立 runner 服务（应用自身容器化时**必须**换，因为容器里没有 docker socket）。

**测试口径**：本机没有 Docker，`SandboxIntegrationTest` 用桩 `CodeRunner`，覆盖权限/校验/配额/审计/状态映射；真容器只在服务器验。

## 全量测试时种子条目 id=1 神秘消失（2026-10-18，**未定位，已登记**）

**现象**：`mvn -B test` 全量跑时，`KnowledgeReadIntegrationTest`（3 条）、`KnowledgeSelfAssessmentIntegrationTest`（3 条）、`KnowledgeAdminIntegrationTest`（2 条）报"读不到种子条目 id=1"；**每个类单独跑都全绿**，迁移记录完整、`V951` 内容确实插入了 id=1。

**已排除的**（逐个验证过，别再重复）：

| 怀疑对象 | 验证 | 结论 |
| --- | --- | --- |
| 迁移没跑 / 种子没插 | 全新库跑单个类 → `[1,2,3,4,5,10]` 齐全 | 排除 |
| V14 我加的唯一键 | 迁移全表 success=1；单类全绿 | 排除 |
| `KnowledgeAdminIntegrationTest` | 单跑后 id=1 仍在 | 排除 |
| `KnowledgeSelfAssessmentIntegrationTest` | 同上 | 排除 |
| `ApiWalkthroughTest` / `ApiContractTest` / `ApiSurfaceTest` | 同上 | 排除 |
| 并发会话 drop 库 | 用唯一库名 + 轮询，`曾存在: False` | 排除（不是被删，是**从头就没出现**） |
| DELETE 触发器审计 | 装好后全量跑，审计表里**没有 id=1 的删除记录** | 排除"被 DELETE" |

**当前最可能的方向**：`曾存在: False` 说明表一旦可见时 id=1 就已缺席——而单类跑同一套迁移却有。差别只在**多个测试类共用一个 Spring 上下文**时 Flyway 的执行时机。这一条没有验证完。

**影响**：只影响"全量一次跑完"的绿/红判断，不影响任何单类验证与运行时行为。CI 若按类分片跑不受影响。

**2026-10-18 追到的确定事实**（比上面那段更进一层）：

| 事实 | 证据 |
| --- | --- |
| id=1 **先被插入、后被删除**（不是"从未出现"） | 轮询时间线：`no-table → 0 → 1（17:59:06）→ 0（17:59:20）` |
| 删除者是一条**长期复用的连接**（5559），执行 `DELETE FROM knowledge_entries WHERE id = 1` | 早期装的 DELETE 触发器 + `information_schema.general_log` 对齐到同一秒 |
| 那条连接的上下文是 **`ApiWalkthroughTest`** | 同一连接前后在打 `/api/v1/account/profile`、`ai/interviews/*`、`resume/*`、`knowledge/admin/entries/*`，且 `userId = 1` |
| **但 `ApiWalkthroughTest` 单独跑是清白的** | 临时打点：`WALKTHROUGH_ENTRY_ID=11`（自建条目 id 是 11，删的也是 11） |
| 种子迁移只应用过一次 | `flyway_schema_history` 里 951–954 的 `installed_on` 全是同一时刻 |

**所以最贴合的机制**：全量时 `ApiWalkthroughTest` 跑之前 `knowledge_entries` 的 `AUTO_INCREMENT` 被**重置**过（TRUNCATE 或等价操作），于是"建条目"拿到 **id=1**，与种子行冲突/覆盖，最后它按自己的 `entryId` 去删就把种子删了。单跑时自增是 11，所以复现不出来。**这一步仍未直接证实**（没抓到那次重置）。

**"自增被重置"这个假设也已排除**：全仓 `grep "TRUNCATE"` 与 `"AUTO_INCREMENT"` 在测试代码里零命中，测试配置里也没开 `flyway.clean`、没有任何 `DROP DATABASE`/`CREATE DATABASE`。所以自增没被重置过，这条假设不成立。

**至此确定与不确定分开**：
- 确定：**有一条复用的连接执行了 `DELETE FROM knowledge_entries WHERE id = 1`**，其上下文与 `ApiWalkthroughTest` 高度吻合；而该测试**单跑时自建条目 id=11、删的也是 11**。
- 不确定：它在全量时为什么拿到了 `entryId = 1`。**没有抓到那条能解释它的证据**，不再靠推测往下写。

**给接手的人**：最省力的下一步不是继续读代码，而是**在 `ApiWalkthroughTest` 里给 `entryId` 加一条断言**（`assertThat(entryId).isGreaterThan(10)` 或直接断言它不在种子 id 集合里）——它一旦在全量里拿到 1，就会**当场红并指出是它自己**，比任何事后排查都快。第二条：把 `ApiSurfaceTest.concretePath` 里"路径变量一律填 `1`"改成 `999999`（唯一一处拿 `1` 撞真实数据的机制，即使不是元凶也该改）。

**影响面**：只影响"全量一次跑完"的绿/红判断；**任何单类验证与运行时行为都不受影响**。

## 迁移文件被改过：`paideia_test` 为什么"history 说已应用、对象却不存在"（2026-10-18，已修）

**结论先行**：不是库坏了，是 **`V14__create_learner_profiles.sql` 在被应用之后又追加了三句 SQL**。Flyway 的校验和按"逐行拼接算 CRC32"，只认文件内容——改了已应用的迁移，它不会重跑，只会让 `flyway_schema_history` 里的记录与文件对不上。

**证据（可复算）**：

- 当前 V14 全文的 Flyway 校验和 = `-796408691`，这正是所有**新建**库记录的值；
- 把当前 V14 截断到第一句 `CREATE TABLE learner_profiles` 的 `) ENGINE = InnoDB;` = **`2059499851`**，正是坏库里记录的值。

所以坏库里 `learner_profiles` 存在，而 `ai_conversations.harness` 列、`uk_ai_memory_structured_ref` 唯一键、`ai_interview_evaluations` 表三样都不存在（逐项 `information_schema` 查过 = 0）。

**顺带订正一处误判**：WORK-018 报告里写的"换行符变化导致校验和不匹配"是错的。Flyway 按行拼接，CRLF 与 LF 算出来**一样**；真正的差别是内容少了三句。别再为行尾去折腾。

**修法**：`paideia_test` 是丢弃型沙箱，直接删库重建。实测空库跑完 21 个迁移 **1.3 秒**（`Successfully applied 21 migrations … 00:00.905s`），重跑 `KnowledgeReadIntegrationTest` 12 条全绿。服务器上的 `paideia_test` 只到 V13，反而没坏，下次跑会自动补 V14/V15。

**加了两道防线**：

1. **`MigrationChecksumTest`**（`paideia-app/src/test/java/`）：把三处 locations（`db/migration`、`db/devdata`、`paideia-persistence` 的 `db/testdata`）里每个 `.sql` 的校验和登记进 `src/test/resources/migration-checksums.txt`。改了已登记的迁移 → 红；新增迁移没登记 → 也红，用 `-Dmigration.checksums.update=true` 登记（**该开关只登记新文件，洗不掉改旧迁移**，反向验证过两次）。
2. **`scripts/test-db.sh`**：给一次测试运行分配独占库 `paideia_run_<时间>_<pid>`，URL 加 `createDatabaseIfNotExist=true`，成功后自动删库。指向非本机地址会直接拒绝（远程账号只被授权 `paideia_test.*`，建不了新库）。

**种子 id 不再写死**：`KnowledgeRead` / `KnowledgeSelfAssessment` / `KnowledgeAdmin` 三个类改成按标题/slug 现查（`SeedIds` 工具类）。硬编码 `id==1/2/3/4` 是全量跑时被同库其他写入连累的那几条断言，现在是"查不到就当场红并说明"，不会再看起来像产品坏了。

**日常用法**：

```bash
scripts/test-db.sh                                   # 在 backend 跑 mvn -B test
scripts/test-db.sh bash -c 'cd frontend && pnpm --filter @paideia/app test:e2e'
scripts/test-db.sh --keep mvn -B test                # 失败时保留库排查
scripts/test-db.sh --drop paideia_run_xxx            # 手动删
```

## 并发会话隔离（2026-10-19）

**四层共享都要拆开**，少一层就会复发"单类绿、全量红"：

| 层 | 做法 |
| --- | --- |
| 测试库 | `scripts/test-db.sh` 是**唯一入口**。裸跑 `mvn -B test` 连 `paideia_test` 不是证据 |
| 工作区 | 每个会话 `git worktree add .worktrees/<名字> -b <分支>`（目录已忽略）；主树留 `master` 只做 review/落库/部署 |
| 端口 | `PAIDEIA_BACKEND_PORT` / `PAIDEIA_APP_PORT` / `PAIDEIA_ADMIN_PORT` 都可覆写，`vite.config.ts` 与两个 `playwright.config.ts` 一起读 |
| 落库 | 未做（P2）：仍直接提交 `master`，没有"合上前重测"的闸 |

**端口这条是实测过的**：2026-10-19 本机 8080 已被另一个会话的后端占着，用 `PAIDEIA_BACKEND_PORT=8099 PAIDEIA_APP_PORT=5199` 跑学习者端 e2e，起的是自己的后端与前端，76 条通过。固定端口下 `reuseExistingServer: true` 会**静默复用别人的后端**，于是打到别人连的库上——红了看不出是谁的问题，绿了也证明不了自己，这比报错更坏。

**注意 `pnpm dev -- --port N` 没用**：pnpm 把 `--` 原样传给 vite，端口不生效（实测起在 5174）。要改用环境变量。

**独占库顺带抓到的第一个真缺陷**（记在这里供 WORK-020 接手）：`V17__add_learning_lines.sql` 里 `UPDATE ... WHERE slug='source-reading'` 在**空库**上是 0 行——`source-reading` 是 `db/devdata/V953` 建的，而 V17 < V953，全新库按版本顺序先跑 V17。共用开发库上 V953 早已应用、V17 后补跑，所以一直是绿的。症状：`LearningProgressIntegrationTest.linesComeFromCategoryMarks` 与 `learn.spec` 四条红；生产/测试库同样受影响（生产 locations 不含 devdata，这条 UPDATE 永远不生效）。**这是"迁移里引用种子数据"的通用坑**：迁移只能依赖迁移自己创建的东西。


## 身份化与模拟面试（WORK-010，2026-10-18 起）

**一条对话 = 公共行为 × 私有数据**：`harness` 是前者（提示词 + 工具集），记忆/历史/凭据是后者（按用户取）。所以**不是"每个场景一个 Agent"**，而是同一个助手在不同页面按这套行为说话。术语上"人"与"行为"要分开——harness 是行为，不是人。

**三套 harness**：`assistant`（既有，提示词一字未改）、`interview`（面试官）、`review`（点评）。注册表是穷尽 switch 且**没有 default**：新增一套时编译器会强制你决定它的提示词与工具集，而不是静默继承——那会让"面试"悄悄变成闲聊且没有报错。

**点评那一次工具集为空**：这不只是设计洁癖——DeepSeek 上"工具调用与结构化输出不能在同一次调用里并用"，而点评要的正是**可解析的结构化评分**。所以它走独立的 `InterviewReviewer` 端口（非流式、无工具），不走工具循环。

**行为跟着会话走**：`ai_conversations.harness` 存下来，恢复时按它选行为。建会话时页面给 `interview`，之后**从通用入口继续这段对话仍然是面试官在说话**——"那是同一个人说过的话"，不该中途换人格。

**身份不是角色**（AC-001）：`role` 决定能做什么，身份只影响推荐顺序与助手形态。两条纪律：
- `Identity` 枚举一期只有 `job_seeker` 是实心的，其余三个 `selectable()` 为 false、**接口写不进去**（否则会出现"库里是考研、界面上没这个身份"的幽灵状态）。
- 身份**不参与任何权限判定**，`/api/v1/account/**` 只要求已登录。

**自评 → 记忆的桥用事件，不是直接调用**：自评在 `paideia-knowledge`，记忆在 `paideia-ai`，而 AI 已经依赖 knowledge（要检索条目）。反向调用会成环、构建期被 Modulith 拦下。所以发 `EntryAssessmentChangedEvent`，AI 侧用 `@TransactionalEventListener(BEFORE_COMMIT)` 消费——与自评在同一个事务里，自评回滚了不会留下假记忆。

**覆盖语义靠唯一键**：`ai_memories_structured` 加 `(user_id, kind, ref_id)` + `ON DUPLICATE KEY UPDATE`。**加键前必须核一遍有没有冲突行**（有就删不掉键）：实测两个库里有 `ref_id` 的结构化记忆都是 0 行。注意 MySQL 唯一键里 NULL 不参与去重，所以"没有 ref_id 的记忆"不受这条约束影响。

**"笔记默认不参与"是保守默认**：笔记是严格私有数据，送进上游＝那些内容离开本机（上游是第三方）。要送必须由用户在面试开始时**显式勾选**。

**前端**：
- 新包 `@paideia/identity`（DTO + 客户端 + 推荐配置包）。配置只表达"谁排前面"，**不表达"谁被藏起来"**——推荐不是限制，没提到的入口追加在后面。
- `/profile` 页明写"不影响你能做什么"：这是最容易被误读成第二套权限的地方。
- `/interview` 页的会话在建的时候就钉死 `harness=interview`，不是每轮重复指定。
- 页头与首页读身份用 `staleTime: 5min` + `retry: false`：读不到就退回默认配置，**不阻塞页面**——推荐用的接口慢不该让整页转圈。


### 2026-10-18 第二轮追查：又排除了一条，并新增一道守卫

- **新增守卫**：`ApiWalkthroughTest` 里加了 `assertThat(entryId).isGreaterThan(10)`（"走查建的条目绝不能是种子 id"）。全量跑**没红** —— 证明它拿到的 id 是合法的，**先前从 `general_log` 对齐到它身上的判断是错的**（`general_log` 是**整个 MySQL 实例**级别的，同一台机器上还有别的会话在跑它们自己的全量，连接上下文会串）。
- **失败数从 8 降到 6**：`KnowledgeAdminIntegrationTest` 那两条不再红（它只依赖自己建的条目），红的只剩 `KnowledgeRead`（3）与 `KnowledgeSelfAssessment`（3）—— 都是**硬编码种子 id=1** 的断言。
- **库状态**：种子迁移 950–954 各应用一次；`knowledge_entries` 最终是 `[2,3,4,5,10]`（缺 1）。

**结论（这就是我给的最终判断）**：这是**同机多会话共用一台 MySQL、又都跑全量测试**造成的测试数据竞争，**不是产品代码缺陷**。证据链：
1. 每个类单独跑都全绿（多次验证）；
2. 没有任何 TRUNCATE / `flyway.clean` / 重建库；
3. 守卫证明走查自己拿的是合法 id；
4. 失败面精确落在"硬编码种子 id"的那几条断言上，而不是任何业务行为。

**建议（按成本排序）**：
1. **让这些断言不依赖固定 id**：用"列表里第一条"或"按标题查"取代 `id==1`。这是治本且成本最低的一条。
2. 每个会话用**自己的库名**跑全量（本轮的 `paideia_guard2` 做法），CI 里天然成立。
3. 若一定要共用库，把全量测试串行化到一把跨进程锁上。

## 从零开始课程与学习线（WORK-020）

**一条学习线 = 一个知识库分类上的标记**，不建表：`knowledge_categories.line_kind`（`course` / `reading` / NULL）就是全部身份，线名与顺序用分类自己的 `name / slug / sort_order`。站的顺序用 `knowledge_entries.sort_order`（可空）；**没声明的线退回 `(published_at, id)`**，所以存量条目一条都不用改。

**"上一篇/下一篇"仍按 `(published_at, id)`**：声明了 `sort_order` 的线靠"导入时按课号顺序发布"保证两者一致。管理员事后手工插一条同分类条目会打乱课号——这是已知缺口。

**新增一条线不改代码**：① 在 `content/courses/<线>/` 写 `00-总览.md`（frontmatter 必须有 `category / line_name / line_kind / line_sort`）② 按同一套七节写课 ③ 跑
`node scripts/import-course.mjs --dir content/courses/<线>`。脚本里**没有任何一门课的名字**——第一次做这条验收时它写死了 python/java，已经改成从 frontmatter 读。

**内容门禁**：`python scripts/verify_course.py`。它做三件事：七节顺序与 frontmatter、围栏块 JSON 与上限、**把每个 `<!-- run -->` 示例真的执行一次并逐字比对输出**（Python 用 `python`，Java 写 `Main.java` 再 `javac`+`java`）。所以"课程里的代码能跑"不是靠作者自觉。

**通过判定在服务端**（`LearningProgressRules`）：至少一条挑战 + 一条小测、且每项 `score/total ≥ 80%`。客户端只上报分数，**请求里没有 `status` 字段**——想伪造也没语法。分数合并取更高分（重做一次手滑不降级），重置才清空。

**复习阶梯 1/3/7/21 天**，按"有新得分的再次通过"前进一档；读取时算到期，不起定时任务（一期没有通知渠道）。

**导师行为 `TUTOR`**：先要思路、四级提示、默认不给完整答案。这不是文案偏好——PNAS 2025 的 RCT 里，无护栏的通用 ChatGPT 让练习涨 48% 但撤掉后考试降 17%，加护栏后涨 127% 且撤掉无损失。学情由服务端取（`KnowledgeApi.learningSummary`）注入系统提示词，前端传不了。

**已知缺口**（四条）：
1. 复习只有"进 `/learn` 看"，没有提醒（无邮件/推送）。
2. 掌握度是"最高分"，不是知识追踪；重做一次就通过，质量靠题库不靠算法。
3. 声明了 `sort_order` 的线，手工插条目会打乱"上一篇/下一篇"。
4. 导师是否真不给答案只由提示词保证，**没有自动检测完整答案的机制**——语义级行为无法单测。

## 线上排障：Arthas（2026-10-23 本机实测，不建常驻）

**定位**：Grafana（WORK-028）看 JVM 与 HTTP 的**结果**；Arthas 看**单个请求内部哪一行慢、收到了什么参数**。前者常驻，后者**临时 attach、查完 stop**，不进仓库、不加依赖、不建 WORK。

**唯一用法**（jar 下到 `/tmp`，别落进仓库）：

```bash
cd /tmp && curl -O https://arthas.aliyun.com/arthas-boot.jar && /opt/jdk25/bin/java -jar arthas-boot.jar
# 选 pid → dashboard -n 1 → trace/watch → 用完 stop
```

**实测能力**（JDK 25.0.4 + Arthas 4.3.5，与服务器同一个 JDK 版本，打的真实登录请求）：

| 命令 | 拿到什么 |
| --- | --- |
| `dashboard -n 1` / `jvm` | 堆 114MB/4GB、42 线程、GC 28 次 |
| `watch LoginService login '{params,throwExp}' -n 1 -x 2` | 入参三字段、抛出的 `BizException`、耗时 124ms、**`LoginService.java:69` 行号** |
| `trace LoginService login -n 1` | 带行号与占比的耗时树；当场测出 `PasswordHasher:wasteTimeLikeAMatch()` 占 **95.2% / 54ms**（设计上的恒定延迟，防时序攻击，不是缺陷） |
| `stop` | 释放 3658/8563，应用仍 `status: UP`，业务未断 |

**五条实测约束**：

1. **只绑回环**，这点是安全底线：实测 `TCP 127.0.0.1:3658` 与 `127.0.0.1:8563`，不对外。**控制台等于对我们进程的任意代码执行**，而那个进程里有数据源口令、JWT 密钥、SMTP 授权码、AI 平台 Key。绝不能改绑 `0.0.0.0`。
2. **JDK 25 打六条 WARNING，不是四条**。除了 JEP 451 那四条（`A Java agent has been loaded dynamically` … `will be disallowed by default in a future release`），还有两条来自它自己的依赖：`sun.misc.Unsafe::arrayBaseOffset has been called by ...fastjson2.util.JDKUtils` 与 `... will be removed in a future release`。**现在是警告不是禁止**，加 `-XX:+EnableDynamicAgentLoading` 可消前四条。**触发重评**：升 JDK 大版本时若默认改成禁止，Arthas 会 attach 不上——看启动日志里有没有这几行即可判断；那时只能改 `-javaagent` 启动加载。
3. **`watch` 不加限制会刷屏**：只写 `'{params,throwExp}'` 实测输出 18KB，绝大部分是 Tomcat filter 链。真用必须 `-n 1` 并排除框架类（`--exclude-class-pattern`）或压 `-x`。
4. **服务器出网必须带代理**：`http_proxy=http://127.0.0.1:7890`，否则 `curl -O` 表现为**静默卡住、无报错**（与 Docker/apt 同一个坑）。
5. **每次连接有固定开销**：从 attach 到出结果不是"秒级"，实测约 25 秒才拿到第一条 `watch` 输出。

**不用 `redefine`（热更新）**：我们纯后端发版实测 57–64 秒，为省一分钟承担"不能新增方法/字段、事后必须补正式发版"的代价不值。只做诊断，不做热修复。

**并发会话注意**：本机可能同时有别的会话的 jar 在跑（实测遇到 PID 40724 与我的 45168 并存）。attach 前用 `netstat -ano | grep :<端口>` 确认 pid 归属，别打错人。

## AIOps 监控与诊断（WORK-042，2026-10-26）

- **拓扑**：Prometheus（保留 30 天）+ Grafana + Loki（3100）+ Alloy（12345），全部只绑回环；Alloy 只采 `paideia/laya/rustfs/mysql/redis-server` 的 journald，标签 `unit`/`level`。生产日志量实测约 6200 行/天，Loki 的存储压力很小。
- **Grafana 13.2.4 的后台插件升级会打掉 Prometheus**：随包插件是 13.2.3，服务端 13.2.4，启动时 installer 试图原地升级；`ProtectSystem=strict` 下 `/usr/share` 只读，升级失败后插件被注销，所有 Prometheus 规则变成 `plugin.notRegistered`。已在 `grafana.ini` 设 `[plugins] preinstall_disabled = true` 与 `preinstall_auto_update = false`。**改监控配置后必须重启 Grafana**（provisioning 不热加载）。
- **textfile 指标只接受 LF**：PowerShell here-string 是 CRLF，`release.ps1` 直接写会让 node_exporter 以 `unknown metric type "gauge\r"` **整份拒绝**；写前 `-replace "\r\n","\n"` 并补末尾换行。排查看 `journalctl -u node-exporter | grep textfile`。
- **Alloy 的注释是 `//` 不是 `#`**：用 `#` 会报 `illegal character U+3002` 并启动失败，日志末尾是 `could not perform the initial load successfully`。
- **5xx 比例的分子与分母都要 `or vector(0)`**：没有 5xx 或没有请求时两边都是空集，整条表达式变空 → Grafana 产生 `DatasourceNoData` 活动告警；只给分子兜底不够，`clamp_min` 里的分母也要兜。
- **诊断链路**：确定性采集（摘要 + 固定指标 + firing 告警 + 脱敏日志 + 发版时间）→ 一次 DeepSeek 调用；报告落 `ops_diagnoses`，流水种类 `OPS_DIAGNOSIS`。手动触发记触发者 id；告警触发用 systemd drop-in 里的 `PAIDEIA_OPS_DIAGNOSIS_USER_ID`。webhook 只接受回环 + `X-Paideia-Ops-Token`（令牌在 `/etc/paideia-ops/hook.token`，600）。
- **实测成本**：一次诊断约 1000 input / 3000 output tokens，扣 11–13 积分，耗时约 25 秒；10 分钟冷却 + 单飞挡住告警风暴。
- **发布**：装/改监控走 `install-ops.sh`（服务器必须带代理；本次 GitHub 经 7890 的 TLS 失败，二进制从本机下载后 scp 过去）；它只重启监控单元，`release.ps1` 只重启 `paideia`。本次两次发版 122.8 / 68.5 秒。
