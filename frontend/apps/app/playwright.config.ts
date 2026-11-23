import { defineConfig, devices } from '@playwright/test'

import { requireTestDatabase, springEnvForLocalBackend } from '../../scripts/e2e-db-env'
import { AUTH_FILE } from './e2e/global-setup'

/** 后端工作目录与产物。运行 e2e 前需要先在 backend 目录执行 `mvn -B -DskipTests package`。 */
const BACKEND_DIR = '../../../backend'
const BACKEND_JAR = 'paideia-app/target/paideia-app-0.0.1-SNAPSHOT.jar'

/** 指向已部署实例：设了就跳过本地 webServer（也就**不需要 3307 隧道**）。 */
const REMOTE_BASE_URL = process.env.E2E_BASE_URL

/**
 * 端口可覆写：同一台机器上并发跑多个会话时各挑一组（`PAIDEIA_BACKEND_PORT=8081
 * PAIDEIA_APP_PORT=5183 pnpm test:e2e`）。不覆写就沿用默认值，行为与以前一致。
 *
 * <p>这条存在的原因：`reuseExistingServer: true` 在固定端口下会**静默复用别人的后端**，
 * 于是 e2e 打到别人连的库上——红了看不出是谁的问题，绿了也证明不了自己。
 */
const BACKEND_PORT = process.env.PAIDEIA_BACKEND_PORT ?? '8080'
const APP_PORT = process.env.PAIDEIA_APP_PORT ?? '5173'
const APP_ORIGINS = `http://127.0.0.1:${APP_PORT},http://localhost:${APP_PORT}`

// 测试库连接与 Spring 覆盖项都来自共用模块（见 frontend/scripts/e2e-db-env.ts）：
// 两端各抄一份已经漂移过（CONFIG_DIR 取法、DB_USER 兜底），差异本身不致命，各自演化才是问题。
requireTestDatabase({ remote: REMOTE_BASE_URL !== undefined, pwa: process.env.PWA_BASE_URL !== undefined })

export default defineConfig({
  /**
   * 登录一次、全体用例复用（线上有登录限流，逐条登录必撞 429）。
   *
   * <p>`PWA_BASE_URL` 下不跑：那是一个**只有静态产物**的预览（没有后端代理），
   * 登录必然失败；而 PWA 用例本身不需要身份。
   */
  globalSetup: process.env.PWA_BASE_URL === undefined ? './e2e/global-setup.ts' : undefined,
  testDir: './e2e',
  // 后端是有状态资源，并行执行会让多个 worker 抢同一端口与同一份数据
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: REMOTE_BASE_URL ?? process.env.PWA_BASE_URL ?? `http://127.0.0.1:${APP_PORT}`,
    // PWA 预览不跑 globalSetup，AUTH_FILE 不会被生成：这时带上它要么 ENOENT 让整个 run 挂掉，
    // 要么静默复用上次留下的过期令牌。PWA 用例本身不需要身份。
    ...(process.env.PWA_BASE_URL === undefined ? { storageState: AUTH_FILE } : {}),
    // 失败就留 trace：retries 默认 0，`on-first-retry` 永远不会触发（2026-10-26 实测）
    trace: 'retain-on-failure',
    // 本机没装与 Playwright 版本配套的浏览器时用系统 Chrome（`E2E_CHANNEL=chrome`）。
    // 不设就是默认行为；两个 project 都继承这一项。
    ...(process.env.E2E_CHANNEL === undefined ? {} : { channel: process.env.E2E_CHANNEL as 'chrome' }),
  },
  /**
   * 两个 project（WORK-016）：
   * - `chromium`：桌面，跑除 `*.mobile.spec.ts` 之外的全部既有用例（44 条不得回归）
   * - `mobile`：像素 7 的设备描述符，只跑移动专用用例
   *
   * 不给每条桌面用例在手机下重跑一遍：那会把一轮 e2e 从 44 条撑到 88 条，
   * 而后端是有状态单进程（`workers: 1`），时间翻倍换不到新的信息——
   * 移动端要验的是"形态换了、没溢出、点得到"，那是专门的用例。
   */
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      testIgnore: '**/*.mobile.spec.ts',
    },
    {
      name: 'mobile',
      use: { ...devices['Pixel 7'] },
      testMatch: '**/*.mobile.spec.ts',
    },
  ],

  /**
   * 同时拉起后端与前端。只起前端会拿到代理错误而不是后端真实返回，
   * 那验证不了「页面展示的是接口真实数据」这条验收标准。
   */
  /**
   * `PWA_BASE_URL` 指向一个**已经起好的生产预览**（`vite preview`）：PWA 只在生产构建里生成
   * SW 与 manifest，开发服务器刻意不注册。这时不能另起 webServer，否则会抢端口/起错产物。
   */
  webServer:
    REMOTE_BASE_URL !== undefined || process.env.PWA_BASE_URL !== undefined
      ? undefined
      : [
    {
      /**
       * 用 local profile 启动后端：应用必须有数据源才能起来，而数据源**由下面的 env 覆盖**，
       * 不读 `application-local.yml`（那份是本地开发用的）。2026-11-11 起测试库在服务器上、
       * 公网 3306 可直连，再也没有本机 3307 隧道这个前置条件（地址只在被忽略的 env 文件里）。
       */
      command: `java -jar ${BACKEND_JAR} --spring.profiles.active=local --server.port=${BACKEND_PORT}`,
      // 必须在 backend 目录启动：Spring Boot 从工作目录读 ./config/ 下的本地配置
      cwd: BACKEND_DIR,
      /**
       * 两处覆盖，环境变量优先级高于 ./config/application-local.yml 里的配置：
       * ① 数据源指向专用的 paideia_e2e —— 端到端测试会注册账号、造登录记录，
       *    不该写进开发用的 paideia 库；
       * ② 额外加载 db/devdata —— 种子账号（seed-learner / seed-admin）在那里，
       *    它**不在**任何 profile 的默认迁移目录里，只有这里显式加上才会执行。
       */
      env: springEnvForLocalBackend(APP_ORIGINS),
      url: `http://127.0.0.1:${BACKEND_PORT}/actuator/health`,
      // **不复用已有后端**：固定端口上复用会静默打到别人的库（2026-11-03 实测：上一轮的
      // java 子进程漏在 8080，本轮复用它、连的是已删库，表现为"登录后停在登录页"）。
      // 端口被占就让 Playwright 直接报错——跑之前先 `netstat -ano | grep :8080`。
      reuseExistingServer: false,
      // 空库首启要跑完 migration + devdata，而库在公网（单条查询 ~36ms），实测建库那一次
      // 120 秒不够。之后库已迁移好，启动只需几秒——这里给的是上限，不是常态等待。
      timeout: 300_000,
    },
    {
      // 端口由 vite.config.ts 读 PAIDEIA_APP_PORT 决定，这里不必再传 --port
      command: 'pnpm dev',
      url: `http://127.0.0.1:${APP_PORT}`,
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
})
