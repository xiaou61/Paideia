# 项目长期记忆

本文件只记录会影响后续多个任务、并且当前仍有使用价值的项目知识。它不是聊天记录、任务日志或待办清单。

- 使用 `rg -n "^## MEM-" .agent/memory.md` 列出全部记忆。
- 使用 `rg -n -i -C 6 "关键词|标签" .agent/memory.md` 查找相关记忆。
- `active` 表示当前有效，`stale` 表示需要复核，`superseded` 表示已有替代条目。
- 详细决策理由放入 `.agent/notes/`，共享资料放入 `.agent/references/`。

## 记忆条目

## MEM-001 | active | lesson

- 摘要：Boot 4 把测试切片拆成了独立 artifact，包名也变了。`@WebMvcTest` / `@AutoConfigureMockMvc` 在 `spring-boot-webmvc-test` 的 `org.springframework.boot.webmvc.test.autoconfigure`，不再是 `spring-boot-test-autoconfigure` 下的 `...autoconfigure.web.servlet`；按技术各自成 artifact，按需引入。
- 标签：`spring-boot-4` `testing` `webmvc`
- 范围：`backend/**` 的测试
- 依据：`.agent/changes/archive/2026-11-22-WORK-001-架构选型与项目骨架/design.md`（测试策略的实施期确认）；实测 `spring-boot-webmvc-test-4.1.1.jar` 内的类路径
- 记录：2026-10-09
- 复核：2026-10-09
- 失效条件：升级到把切片合并回单一 artifact 的 Boot 版本。

## MEM-002 | active | lesson

- 摘要：模块内的 `@WebMvcTest` 需要一份**同包**的启动配置类，且必须用 `@SpringBootApplication` 而不是 `@SpringBootConfiguration`——后者不含组件扫描，表现为所有控制器都注册不上、请求一律 404。也不要用 `@ContextConfiguration` 显式指定配置类，那会关掉切片自己的组件过滤。
- 标签：`spring-boot-4` `testing` `webmvc` `modulith`
- 范围：`backend/paideia-*/src/test/**`
- 依据：`.agent/changes/archive/2026-11-22-WORK-001-架构选型与项目骨架/design.md`（测试策略的实施期确认）；`backend/paideia-web/src/test/java/io/github/xiaou61/web/WebSliceTestApplication.java`
- 记录：2026-10-09
- 复核：2026-10-09
- 失效条件：模块不再需要切片测试，或转而全部使用 `@SpringBootTest`。

## MEM-003 | active | lesson

- 摘要：CORS 在这个项目里有**两处开关，都要配**：`paideia.web.cors.allowed-origins` 只管 `/api/**`（走 `WebMvcConfigurer`）；Actuator 端点由它自己的 HandlerMapping 处理，只认 `management.endpoints.web.cors.allowed-origins`。另外安全链必须显式 `.cors()`，否则预检 OPTIONS 会先被 `anyRequest().authenticated()` 挡成 401，表现为"同源可用、跨域全挂"。
- 标签：`cors` `spring-security` `actuator` `web`
- 范围：`backend/paideia-web/`、`backend/paideia-security/`
- 依据：`backend/paideia-app/src/test/java/io/github/xiaou61/AuthorizationIsolationTest.java` 的三个 CORS 用例
- 记录：2026-10-09
- 复核：2026-10-09
- 失效条件：改为在网关统一处理 CORS，或 Actuator 不再由独立 HandlerMapping 承载。

## MEM-004 | active | operation

- 摘要：本机到 GitHub 的连接不稳，涉及 GitHub 的构建都要走镜像。桌面端打包**必须同时设置两个变量**：`ELECTRON_MIRROR`（Electron 运行时）与 `ELECTRON_BUILDER_BINARIES_MIRROR`（NSIS/签名辅助二进制）——只设后者仍会去 GitHub 拉运行时并超时。pnpm 侧另有 `allowBuilds` 放行依赖的构建脚本（不是旧版的 `onlyBuiltDependencies`）。 另外 **git 必须显式走本机 Clash 代理**：mixed-port 为 `7897`（TUN 关闭，只有主动走代理的程序才用得上），未配置时代码直连 github.com，表现为推送**时通时不通**。已用 `git config --global http.https://github.com/.proxy` 与 `https.https://github.com/.proxy` 固化到 github.com 这一个域；代理端口若变更需同步更新。
  **诊断方法（2026-10-10 补，两次踩坑）**：判断代理是否在服务**只能用 `netstat -ano | grep :7897`**，看有没有 `LISTENING` 与对应 PID（`verge-mihomo`）——**不要用 `bash -c "</dev/tcp/127.0.0.1/7897"` 探测**，它在本机给过两次假否定（明明在监听却说不可连），据此得出"Clash 没在跑"的结论是错的。另注意 `netstat` 里出现 `FIN_WAIT_2`/`TIME_WAIT` 属正常残留，不代表没在监听。
  **一次失败可直接重试**：2026-10-10 首次推送报 `Failed to connect to 127.0.0.1 port 7897`，而端口未变、监听正常，重试即成功——属瞬时抖动。**不要**因此改代理配置；确认端口与监听后先重试一次，再考虑其他原因。**直连不可用**：把代理覆盖为空直连 github 会得到 `Recv failure: Connection was reset`（TCP 握手过、TLS 被 reset），所以"直连也能连上"的探测会骗人。
- 标签：`build` `electron` `pnpm` `network` `git`
- 范围：`frontend/**`
- 依据：`.agent/rules/always.md` 的构建命令段；`.agent/changes/archive/2026-11-22-WORK-001-架构选型与项目骨架/testing/logs/desktop-packaged.txt`；2026-10-10 推送 WORK-003 时的实测
- 记录：2026-10-09
- 复核：2026-10-10

## MEM-005 | active | convention

- 摘要：持久层分页是**约定式显式分页**，不做 SQL 改写拦截器：`PageQuery` 给出 `limit()`/`offset()`，mapper 自己写 `LIMIT #{limit} OFFSET #{offset}`，总数由独立的 count 语句提供。不要引入 PageHelper 或自研 SQL 改写拦截器——线程本地状态污染与 COUNT 改写错误是那类方案的固有代价，而显式写法只多两行 SQL。
- 标签：`mybatis` `pagination` `persistence`
- 范围：`backend/paideia-persistence/`、所有 mapper
- 依据：`backend/paideia-persistence/src/main/java/io/github/xiaou61/persistence/package-info.java`
- 记录：2026-10-09
- 复核：2026-10-09
- 失效条件：出现大量分页查询且手写成本明显不可接受时，重评是否引入受控的抽象。
