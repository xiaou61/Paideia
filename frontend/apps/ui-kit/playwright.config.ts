import { defineConfig, devices } from '@playwright/test'

/**
 * 展览页的端到端检查。
 *
 * 这一页不依赖后端，因此 webServer 只起前端 —— 也不需要 SSH 隧道，
 * 任何时候都能跑，这是它比主体应用更适合当设计系统回归入口的原因。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5175',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'pnpm dev',
      url: 'http://127.0.0.1:5175',
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
})
