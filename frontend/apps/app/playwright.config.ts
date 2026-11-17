import { defineConfig, devices } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

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

/** 配置文件所在目录（`import.meta.dirname` 在 Node 20.11+ 才有，取不到就退回 cwd）。 */
const CONFIG_DIR = typeof import.meta.dirname === 'string' ? import.meta.dirname : process.cwd()

/**
 * 测试库连接的来源：`backend/config/remote-db.local.env`（在 `.gitignore` 里，口令不入库）。
 *
 * <p>以前要先 `source` 这个文件；忘了就得到一个 MySQL 认证失败的后端启动错误，而原因埋在
 * 二十行 Spring 日志里。这里直接解析同一份文件，于是"先 source"与"直接跑"等价；环境变量
 * 仍然优先，临时指向别的库时不必改文件。
 */
function localDbEnv(): Record<string, string> {
  try {
    const path = join(CONFIG_DIR, '../../../backend/config/remote-db.local.env')
    const entries: Array<[string, string]> = []
    for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
      const matched = /^export\s+([A-Z0-9_]+)=['"]?([^'"]*)['"]?$/.exec(line.trim())
      if (matched !== null) {
        entries.push([matched[1] ?? '', matched[2] ?? ''])
      }
    }
    return Object.fromEntries(entries)
  } catch {
    return {}
  }
}

const LOCAL_DB_ENV = localDbEnv()
const DB_URL = process.env.PAIDEIA_TEST_DB_URL ?? LOCAL_DB_ENV.PAIDEIA_TEST_DB_URL ?? ''
const DB_USER = process.env.PAIDEIA_TEST_DB_USER ?? LOCAL_DB_ENV.PAIDEIA_TEST_DB_USER ?? 'paideia_e2e'
const DB_PASSWORD = process.env.PAIDEIA_TEST_DB_PASSWORD ?? LOCAL_DB_ENV.PAIDEIA_TEST_DB_PASSWORD ?? ''

// 要起本地后端却拿不到库连接时，在**读配置**这一步就说清楚，别等 Spring 起不来再猜。
if (REMOTE_BASE_URL === undefined && process.env.PWA_BASE_URL === undefined && DB_URL === '') {
  throw new Error(
    '缺少测试库连接：backend/config/remote-db.local.env 不存在。请找回该文件，或先 `source` 它再跑。',
  )
}

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
      env: {
        // forceConnectionTimeZoneToSession 与 connectionTimeZone 必须成对（服务器 MySQL 会话是 +08:00）：
        // 少了它，建表默认值写进去的是本地钟点，而驱动按 UTC 解释，时间整体快 8 小时。
        /**
         * 连接信息优先取环境变量，其次取 `backend/config/remote-db.local.env`（见文件顶部
         * `localDbEnv`）：那份文件是"测试库在哪"的唯一来源，改库只改它。
         */
        // 全仓库只用一个专用测试库 `paideia_e2e`（账号只授权该库；生产库 paideia
        // 没有任何可远程登录的账号），口令只存在于被 gitignore 的那份 env 文件里。
        SPRING_DATASOURCE_URL: DB_URL,
        SPRING_DATASOURCE_USERNAME: DB_USER,
        SPRING_DATASOURCE_PASSWORD: DB_PASSWORD,
        SPRING_FLYWAY_LOCATIONS: 'classpath:db/migration,classpath:db/devdata',
        // paideia_e2e 被三种迁移配置共用（见 backend 的 application-test.yml 注释）：
        // 夹具用的 V900 会出现在这个库里，而本次 locations 不含 db/testdata，
        // 不忽略它就会被判成"已应用但本地找不到"而起不来。
        SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS: '*:future,*:missing',
        SPRING_FLYWAY_OUT_OF_ORDER: 'true',
        // 学习者端的来源显式给出：浏览器对同源 POST 也会带 Origin 头，
        // 后端按来源判定，未列入直接 403。Actuator 端点另有一份 CORS 配置。
        PAIDEIA_WEB_CORS_ALLOWED_ORIGINS: APP_ORIGINS,
        MANAGEMENT_ENDPOINTS_WEB_CORS_ALLOWED_ORIGINS: APP_ORIGINS,
      },
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
