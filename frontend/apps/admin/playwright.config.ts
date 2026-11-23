import { defineConfig, devices } from '@playwright/test'

import { requireTestDatabase, springEnvForLocalBackend } from '../../scripts/e2e-db-env'

const BACKEND_DIR = '../../../backend'
const BACKEND_JAR = 'paideia-app/target/paideia-app-0.0.1-SNAPSHOT.jar'

/**
 * 指向已部署实例时（改完直接部署、再在线上跑一遍这条路径），不需要本地起任何东西：
 * 设了 `E2E_BASE_URL` 就跳过 webServer，直接打那个地址。账号随之由环境变量给出
 * （见 `e2e/accounts.ts`）。
 */
const REMOTE_BASE_URL = process.env.E2E_BASE_URL

/** 端口可覆写，理由同学习者端：并发会话各用一组端口，避免静默复用别人的服务。 */
const BACKEND_PORT = process.env.PAIDEIA_BACKEND_PORT ?? '8080'
const ADMIN_PORT = process.env.PAIDEIA_ADMIN_PORT ?? '5174'
const ADMIN_ORIGINS = `http://127.0.0.1:${ADMIN_PORT},http://localhost:${ADMIN_PORT}`

// 测试库连接来自共用模块（frontend/scripts/e2e-db-env.ts）：以前两端各抄一份，
// 管理端缺了学习者端那条 fail-fast，DB_USER 兜底也空着——于是"没配库"要等 Spring 报错才发现。
requireTestDatabase({ remote: REMOTE_BASE_URL !== undefined })

/**
 * 管理端的端到端检查。
 *
 * <p>默认（不设 `E2E_BASE_URL`）在本地同时拉起后端：要证明的是"徽标显示的是接口真实返回"，
 * 只起前端会拿到代理错误，验证不了这一点。
 *
 * <p>前置条件：后端 jar 已构建，且到服务器 MySQL 的本地隧道（127.0.0.1:3307）是通的。
 * **指向线上时这两条都不需要**——线上那个进程本身连着库。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: REMOTE_BASE_URL ?? `http://127.0.0.1:${ADMIN_PORT}`,
    // 首条用例的 goto 会触发 Vite 编译整包，本机冷启动实测超过默认 30 秒（2026-11-03 全量实测）；
    // 管理端没有 globalSetup 先热身，这条只能靠超时兜住。
    navigationTimeout: 60_000,
    trace: 'retain-on-failure',
    // 与学习者端一致：本机没装配套浏览器时用系统 Chrome（E2E_CHANNEL=chrome）。
    ...(process.env.E2E_CHANNEL === undefined ? {} : { channel: process.env.E2E_CHANNEL as 'chrome' }),
  },
  /** 同学习者端：桌面跑全部既有用例，移动 project 只跑 `*.mobile.spec.ts`（WORK-016）。 */
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
  webServer: REMOTE_BASE_URL
    ? undefined
    : [
        {
          command: `java -jar ${BACKEND_JAR} --spring.profiles.active=local --server.port=${BACKEND_PORT}`,
          cwd: BACKEND_DIR,
          env: springEnvForLocalBackend(ADMIN_ORIGINS),
          url: `http://127.0.0.1:${BACKEND_PORT}/actuator/health`,
          // 同学习者端：复用已有后端会静默打到别人的库，端口被占就让它报错。
          reuseExistingServer: false,
          // 与端到端一致：空库首启要跑完 migration + devdata，公网库更慢，给足上限。
          timeout: 300_000,
        },
        {
          // 端口由 vite.config.ts 读 PAIDEIA_ADMIN_PORT 决定，这里不必再传 --port
          command: 'pnpm dev',
          url: `http://127.0.0.1:${ADMIN_PORT}`,
          reuseExistingServer: true,
          timeout: 60_000,
        },
      ],
})
